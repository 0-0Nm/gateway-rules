#!/usr/bin/env python3
"""
check_kt.py v2 —— Kotlin 跨文件一致性检查（推送前 lint）

v1 误报率约 90%，已修正三个根本缺陷：
  1 三引号字符串内的括号未剥离 -> 花括号误报
  2 嵌套调用（lambda 内的 Text(color=X)）被归到外层函数 -> 参数误报
  3 子串匹配（SwitchPolicy 命中 Switch）-> import 误报

能查：
  A 命名参数不存在（仅取 depth-1 参数，排除嵌套）
  B data class 字段被删但别处仍引用
  C 高频 Compose 符号缺 import（词边界匹配）
  D 花括号配平（剥离字符串/注释后）

查不了：类型错误、方法不存在、泛型不匹配。这是启发式，不是编译器。
"""
import os, re, sys

ROOT = "app/src/main/java/com/aqi/ccb"

SYMBOL_IMPORTS = {
    "OutlinedButton": "androidx.compose.material3.OutlinedButton",
    "DropdownMenu": "androidx.compose.material3.DropdownMenu",
    "DropdownMenuItem": "androidx.compose.material3.DropdownMenuItem",
    "Checkbox": "androidx.compose.material3.Checkbox",
    "RadioButton": "androidx.compose.material3.RadioButton",
    "Slider": "androidx.compose.material3.Slider",
    "IconButton": "androidx.compose.material3.IconButton",
    "TopAppBar": "androidx.compose.material3.TopAppBar",
    "Scaffold": "androidx.compose.material3.Scaffold",
    "LocalClipboardManager": "androidx.compose.ui.platform.LocalClipboardManager",
    "AnnotatedString": "androidx.compose.ui.text.AnnotatedString",
    "rememberScrollState": "androidx.compose.foundation.rememberScrollState",
    "verticalScroll": "androidx.compose.foundation.verticalScroll",
    "KeyboardOptions": "androidx.compose.foundation.text.KeyboardOptions",
    "KeyboardType": "androidx.compose.ui.text.input.KeyboardType",
}


def read_files():
    out = {}
    for base, _, files in os.walk(ROOT):
        for fn in files:
            if fn.endswith(".kt"):
                p = os.path.join(base, fn)
                out[p] = open(p, encoding="utf-8", errors="replace").read()
    return out


def strip_comments_and_strings(s):
    """剥离注释与字符串（含三引号），保留换行以维持行号"""
    # 三引号字符串 -> 占位（保留换行数）
    def repl_triple(m):
        return '""' + "\n" * m.group(0).count("\n")
    s = re.sub(r'"""[\s\S]*?"""', repl_triple, s)
    # 普通字符串（含模板 ${} 整体吃掉）
    s = re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', s)
    # 字符字面量
    s = re.sub(r"'(?:[^'\\\n]|\\.)*'", "''", s)
    # 注释
    s = re.sub(r"/\*[\s\S]*?\*/", lambda m: "\n" * m.group(0).count("\n"), s)
    s = re.sub(r"//[^\n]*", "", s)
    return s


def balanced(s, open_idx):
    """从 s[open_idx] 的 '(' 取到配对的 ')' 内部文本"""
    depth = 0
    i = open_idx
    while i < len(s):
        c = s[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return s[open_idx + 1:i]
        i += 1
    return None


def top_level_args(body):
    """只取 depth-1 的命名参数名，排除嵌套调用里的"""
    if body is None:
        return set()
    names = set()
    depth = 0
    i = 0
    start = 0
    segments = []
    while i < len(body):
        c = body[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "," and depth == 0:
            segments.append(body[start:i])
            start = i + 1
        i += 1
    segments.append(body[start:])

    for seg in segments:
        m = re.match(r"\s*(\w+)\s*=(?!=)", seg)
        if m:
            names.add(m.group(1))
    return names


def parse_decls(files):
    classes, funcs = {}, {}
    for p, src in files.items():
        s = strip_comments_and_strings(src)
        for m in re.finditer(r"\bdata\s+class\s+(\w+)\s*(?:<[^>]*>)?\s*\(", s):
            name = m.group(1)
            body = balanced(s, m.end() - 1)
            if body is None:
                continue
            fields = set(re.findall(r"\b(?:val|var)\s+(\w+)\s*[:=]", body))
            classes.setdefault(name, set()).update(fields)
        for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(\w+)\s*\(", s):
            name = m.group(1)
            body = balanced(s, m.end() - 1)
            if body is None:
                continue
            params = set()
            for seg in body.split(","):
                pm = re.match(r"\s*(?:@\w+\s+)*(\w+)\s*:", seg)
                if pm:
                    params.add(pm.group(1))
            funcs.setdefault(name, set()).update(params)
    return classes, funcs


def check_named_args(files, classes, funcs):
    issues = []
    for p, src in files.items():
        s = strip_comments_and_strings(src)
        for m in re.finditer(r"\b([A-Z]\w*)\s*\(", s):
            name = m.group(1)
            # 同时查 data class 构造 与 普通函数调用
            # top_level_args 已排除嵌套 lambda 内的参数，故 DSL 方法不会误报
            decl = classes.get(name) or funcs.get(name)
            if decl is None:
                continue
            body = balanced(s, m.end() - 1)
            named = top_level_args(body)
            missing = named - decl
            if missing:
                line = s[:m.start()].count("\n") + 1
                issues.append("%s:%d  %s(...) 参数不存在: %s"
                              % (p, line, name, ", ".join(sorted(missing))))
    return issues


def check_imports(files):
    issues = []
    for p, src in files.items():
        s = strip_comments_and_strings(src)
        imports = "\n".join(re.findall(r"^import .*$", s, flags=re.M))
        for sym, imp in SYMBOL_IMPORTS.items():
            if imp in imports:
                continue
            if not re.search(r"\b" + re.escape(sym) + r"\s*\(", s):
                continue
            issues.append("%s  用到 %s( 但缺 import %s" % (p, sym, imp))
    return issues


def check_braces(files):
    issues = []
    for p, src in files.items():
        s = strip_comments_and_strings(src)
        o, c = s.count("{"), s.count("}")
        if o != c:
            issues.append("%s  花括号不配平: { %d / } %d" % (p, o, c))
    return issues


def main():
    quiet = "--quiet" in sys.argv
    files = read_files()
    if not files:
        print("未找到 .kt 文件"); return 1
    classes, funcs = parse_decls(files)
    problems = check_braces(files) + check_named_args(files, classes, funcs) + check_imports(files)
    if not quiet:
        print("检查 %d 文件 | %d data class | %d fun" % (len(files), len(classes), len(funcs)))
    if problems:
        print("\n发现 %d 个问题：" % len(problems))
        for x in problems:
            print("  ✗ " + x)
        return 1
    print("\n✓ 未发现结构性问题")
    return 0


if __name__ == "__main__":
    sys.exit(main())
