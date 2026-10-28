#!/usr/bin/env python3
"""
patch.py —— 原子化文本补丁工具

为什么需要它：
之前手写的补丁脚本模板是

    rep(A)  # 成功
    rep(B)  # MISS -> sys.exit(1)
    write() # 永远执行不到

结果是「A 改成功了但整文件没写盘」，而且终端只显示 "MISS B"，
看起来像只是 B 失败，实际 A 的改动被静默丢弃。
这个坑导致过一次构建失败（SettingsState 缺字段）。

本工具的保证：
  1 先在内存里**全部**应用，任一锚点 MISS 就整体放弃、不碰磁盘
  2 失败时明确报告「已完成 N 项，全部未落盘」
  3 成功时报告字节数与改动项数
  4 支持 --dry 只预览不写盘

用法：
  python3 tools/patch.py --file X.kt --edits edits.json [--dry]

edits.json 格式：
  [{"tag":"字段", "old":"...", "new":"..."},
   {"tag":"UI",   "old":"...", "new":"..."}]
"""
import io, json, sys, os


def main():
    args = sys.argv[1:]
    if "--file" not in args or "--edits" not in args:
        print("用法: patch.py --file X.kt --edits edits.json [--dry]")
        return 2

    path = args[args.index("--file") + 1]
    edits_path = args[args.index("--edits") + 1]
    dry = "--dry" in args

    if not os.path.exists(path):
        print("FAIL 目标文件不存在: " + path)
        return 2

    original = io.open(path, encoding="utf-8").read()
    s = original
    edits = json.load(io.open(edits_path, encoding="utf-8"))

    applied = []
    for i, e in enumerate(edits):
        tag = e.get("tag", "edit#%d" % i)
        old = e.get("old", "")
        new = e.get("new", "")
        if not old:
            print("FAIL [%s] old 为空" % tag)
            return 2
        if old not in s:
            # 关键：明确告知前面已应用但整体未落盘
            print("FAIL [%s] 锚点未找到 —— 本次 %d 项改动全部放弃，文件未修改"
                  % (tag, len(edits)))
            if applied:
                print("     已完成但未落盘: " + ", ".join(applied))
            return 1
        s = s.replace(old, new, 1)
        applied.append(tag)

    if s == original:
        print("NOOP 内容无变化")
        return 0

    # 括号配平自检（只提示，不阻止——字符串里可能有括号）
    ob, cb = s.count("{"), s.count("}")
    warn = "" if ob == cb else "  ⚠ 花括号 { %d / } %d 不平衡，请核查" % (ob, cb)

    if dry:
        print("DRY %d 项可应用 -> %s (bytes %d -> %d)%s"
              % (len(applied), path, len(original), len(s), warn))
        return 0

    io.open(path, "w", encoding="utf-8").write(s)
    print("OK  已落盘 %d 项 -> %s (bytes %d -> %d)%s"
          % (len(applied), path, len(original), len(s), warn))
    print("    应用: " + ", ".join(applied))
    return 0


if __name__ == "__main__":
    sys.exit(main())
