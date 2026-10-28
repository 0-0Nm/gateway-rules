#!/usr/bin/env python3
"""
push.py —— 带回读校验的 GitHub 推送

为什么需要它：
之前手写推送脚本，推完就说「PUSHED」，但并没有验证远端内容
是否真的等于本地。出现过「以为推了其实没推」的情况。

本工具的保证：
  1 推送后**回读远端**，逐字节比对本地内容
  2 不一致立即报 VERIFY_FAIL，不谎报成功
  3 本地与远端相同时跳过（幂等）
  4 输出每个文件的 bytes 确认

用法：
  python3 tools/push.py --message "commit msg" file1.kt file2.kt
  python3 tools/push.py --message "msg" --dry file1.kt
"""
import io, json, base64, sys, urllib.request, urllib.error

TOKEN = ("github_pat_11CSHKJQY05HZ6nWJKYuDw_Ltb56FnrFDIIq2KGTxiATW90Uur5xsx9jGWc1ew5"
         "jFBLUIGEOF5Qd9N2Xyi")
REPO = "0-0Nm/gateway-rules"
BRANCH = "main"


def api(method, path, payload=None):
    data = json.dumps(payload).encode() if payload else None
    r = urllib.request.Request("https://api.github.com" + path, data=data, method=method)
    r.add_header("Authorization", "Bearer " + TOKEN)
    r.add_header("Accept", "application/vnd.github+json")
    if data:
        r.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(r, timeout=50) as x:
        return json.loads(x.read().decode())


def push_one(local, remote, message, dry):
    if not io.os.path.exists(local) if False else (not __import__("os").path.exists(local)):
        print("FAIL 本地不存在: " + local)
        return False
    local_txt = io.open(local, encoding="utf-8").read()

    try:
        cur = api("GET", "/repos/%s/contents/%s?ref=%s" % (REPO, remote, BRANCH))
        sha = cur["sha"]
        remote_txt = base64.b64decode(cur["content"]).decode("utf-8")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            sha, remote_txt = None, ""
        else:
            raise

    if remote_txt == local_txt:
        print("SAME  %-28s %d B（远端已一致，跳过）" % (remote.split("/")[-1], len(local_txt)))
        return True

    if dry:
        print("DRY   %-28s %d -> %d B" % (remote.split("/")[-1], len(remote_txt), len(local_txt)))
        return True

    payload = {
        "message": message,
        "content": base64.b64encode(local_txt.encode()).decode(),
        "branch": BRANCH,
    }
    if sha:
        payload["sha"] = sha
    res = api("PUT", "/repos/%s/contents/%s" % (REPO, remote), payload)
    commit = res["commit"]["sha"][:7]

    # ---- 回读校验：这是本工具存在的理由 ----
    back = api("GET", "/repos/%s/contents/%s?ref=%s" % (REPO, remote, BRANCH))
    back_txt = base64.b64decode(back["content"]).decode("utf-8")
    if back_txt != local_txt:
        print("VERIFY_FAIL  %-24s 远端回读与本地不一致！本地 %d B，远端 %d B"
              % (remote.split("/")[-1], len(local_txt), len(back_txt)))
        return False

    print("PUSHED %-10s %-24s %d B  (回读一致)" % (commit, remote.split("/")[-1], len(local_txt)))
    return True


def main():
    args = sys.argv[1:]
    if "--message" not in args:
        print("用法: push.py --message \"msg\" [--dry] file1.kt ...")
        return 2
    idx = args.index("--message")
    message = args[idx + 1]
    dry = "--dry" in args
    files = [a for i, a in enumerate(args)
             if not a.startswith("--") and i not in (idx, idx + 1)]
    if not files:
        print("没有要推送的文件")
        return 2

    ok = True
    for f in files:
        if not push_one(f, f, message, dry):
            ok = False
    print("\n%s %d 个文件" % ("全部成功" if ok else "有失败", len(files)))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
