#!/usr/bin/env python3
"""
check_kt.py v3 —— Kotlin 跨文件一致性检查（推送前 lint）

五项检查（每项都对应一次真实踩过的坑）：
  A 花括号配平（剥离字符串/注释后）
  B 命名参数不存在（构造函数/函数调用用了声明里没有的参数）
  C 高频 Compose 符号缺 import
  D companion object 内声明的类型被外部用 Outer.Name 引用
    —— 实例：GatewayService.StateListener 放 companion 里导致 Unresolved reference
  E Composable 回调型可选参数未被传入
    —— 实例：SettingsScreen(onOpenAutoStart = null) 没传 -> 点「自启动」毫无反应

局限：查不了类型错误、方法不存在、泛型不匹配。这是启发式，不是编译器。
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
    "Surface": "androidx.compose.material3.Surface",
    "AlertDialog": "androidx.compose.material3.AlertDialog",
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
    def repl_triple(m):
        return '""' + "\n" * m.group(0).count("\n")
    s = re.sub(r'"""[\s\S]*?"""', repl_triple, s)
    s = re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', s)
    s = re.sub(r"'(?:[^'\\\n]|\\.)*'", "''", s)
    s = re.sub(r"/\*[\s\S]*?\*/", lambda m: "\n" * m.group(0).count("\n"), s)
    s = re.sub(r"//[^\n]*", "", s)
    return s


def balanced(s, open_idx):
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
    if body is None:
        return set()
    names = set()
    depth = 0
    start = 0
    segments = []
    for i, c in enumerate(body):
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "," and depth == 0:
            segments.append(body[start:i])
            start = i + 1
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
            body = balanced(s, m.end() - 1)
            if body:
                classes.setdefault(m.group(1), set()).update(
                    re.findall(r"\b(?:val|var)\s+(\w+)\s*[:=]", body))
        for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(\w+)\s*\(", s):
            body = balanced(s, m.end() - 1)
            if body:
                params = set()
                for seg in body.split(","):
                    pm = re.match(r"\s*(?:@\w+\s+)*(\w+)\s*:", seg)
                    if pm:
                        params.add(pm.group(1))
                funcs.setdefault(m.group(1), set()).update(params)
    return classes, funcs


def check_braces(files):
    issues = []
    for p, src in files.items():
        s = strip_comments_and_strings(src)
        o, c = s.count("{"), s.count("}")
        if o != c:
            issues.append("%s  花括号不配平: { %d / } %d" % (p, o, c))
    return issues


def check_named_args(files, classes, funcs):
    issues = []
    for p, src in files.items():
        s = strip_comments_and_strings(src)
        for m in re.finditer(r"\b([A-Z]\w*)\s*\(", s):
            name = m.group(1)
            decl = classes.get(name) or funcs.get(name)
            if decl is None:
                continue
            named = top_level_args(balanced(s, m.end() - 1))
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
            # 全限定名调用（androidx.compose.material3.AlertDialog(...)）不需要 import
            if re.search(r"\b\w+(?:\.\w+)*\." + re.escape(sym) + r"\s*\(", s):
                continue
            if not re.search(r"(?<![\w.])" + re.escape(sym) + r"\s*\(", s):
                continue
            issues.append("%s  用到 %s( 但缺 import %s" % (p, sym, imp))
    return issues


def check_companion_nesting(files):
    issues = []
    nested = {}
    for p, src in files.items():
        s = strip_comments_and_strings(src)
        for m in re.finditer(r"\b(?:class|object)\s+(\w+)", s):
            cls = m.group(1)
            after = s[m.end():]
            ci = after.find("companion object")
            if ci < 0:
                continue
            body = balanced(after, after.find("{", ci))
            if not body:
                continue
            inner = set(re.findall(r"\b(?:interface|class)\s+(\w+)", body))
            if inner:
                nested.setdefault(cls, set()).update(inner)
    if not nested:
        return issues
    for p, src in files.items():
        s = strip_comments_and_strings(src)
        for cls, names in nested.items():
            for n in names:
                pat = r"\b" + cls + r"\." + n + r"\b"
                for m in re.finditer(pat, s):
                    line = s[:m.start()].count("\n") + 1
                    issues.append(
                        "%s:%d  %s.%s 不存在——%s 声明在 %s 的 companion 内，"
                        "外部应写 %s.Companion.%s 或移到类层级"
                        % (p, line, cls, n, n, cls, cls, n))
    return issues


def check_optional_params(files, funcs):
    """Composable 声明了回调型可选参数，但调用处一个都没传 -> 点了没反应"""
    issues = []
    callback_funcs = {}
    for p, src in files.items():
        s = strip_comments_and_strings(src)
        for m in re.finditer(r"\bfun\s+([A-Z]\w*)\s*\(", s):
            body = balanced(s, m.end() - 1)
            if not body:
                continue
            opts = set()
            for seg in body.split(","):
                seg = seg.strip()
                pm = re.match(
                    r"(\w+)\s*:\s*\(+[^)]*\)\s*->\s*[\w?]+\s*\)?\s*\?\s*=\s*null", seg)
                if pm:
                    opts.add(pm.group(1))
            if opts:
                callback_funcs[m.group(1)] = opts

    if not callback_funcs:
        return issues

    for p, src in files.items():
        s = strip_comments_and_strings(src)
        for name, opts in callback_funcs.items():
            for m in re.finditer(r"\b" + name + r"\s*\(", s):
                if s[max(0, m.start()-4):m.start()].rstrip().endswith("fun"):
                    continue
                named = top_level_args(balanced(s, m.end() - 1))
                not_passed = opts - named
                if not_passed:
                    line = s[:m.start()].count("\n") + 1
                    issues.append(
                        "%s:%d  %s(...) 未传回调: %s —— UI 对应按钮点了会没反应"
                        % (p, line, name, ", ".join(sorted(not_passed))))
    return issues


def main():
    quiet = "--quiet" in sys.argv
    files = read_files()
    if not files:
        print("未找到 .kt 文件"); return 1
    classes, funcs = parse_decls(files)
    problems = (check_braces(files)
                + check_named_args(files, classes, funcs)
                + check_imports(files)
                + check_companion_nesting(files)
                + check_optional_params(files, funcs))
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
