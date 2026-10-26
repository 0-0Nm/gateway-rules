package com.aqi.ccb.gateway

/**
 * SafeExpr -- L1 受限条件表达式求值器（零依赖，无脚本引擎）
 *
 * 设计约束（沿用 Python 侧结论）：
 *  1 只支持白名单文法：数字/字符串/布尔/变量名 + 比较 + and/or/not + 括号
 *  2 不支持函数调用、属性访问、下标、赋值、lambda —— 因此没有 RCE 面
 *  3 长度/token 数上限，防 DoS
 *  4 任何解析失败 -> 返回 false（安全默认拒绝），绝不返回 true
 *
 * 文法：
 *   or_expr   := and_expr ( 'or' and_expr )*
 *   and_expr  := not_expr ( 'and' not_expr )*
 *   not_expr  := 'not' not_expr | primary
 *   primary   := '(' or_expr ')' | comparison
 *   comparison:= value ( ('=='|'!='|'<='|'>='|'<'|'>') value )?
 *   value     := number | string | true | false | identifier
 */
object SafeExpr {

    private const val MAX_LEN = 512
    private const val MAX_TOKENS = 200

    /** 求值；解析或类型错误一律返回 false */
    fun eval(expr: String, env: Map<String, Any?>): Boolean {
        if (expr.isBlank() || expr.length > MAX_LEN) return false
        return try {
            val toks = tokenize(expr)
            if (toks.size > MAX_TOKENS) return false
            val p = Parser(toks, env)
            val v = p.parseOr()
            if (!p.atEnd()) return false
            truthy(v)
        } catch (_: Exception) {
            false
        }
    }

    // ---------- 词法 ----------
    private sealed class Tok {
        data class Num(val v: Double) : Tok()
        data class Str(val v: String) : Tok()
        data class Id(val v: String) : Tok()
        data class Op(val v: String) : Tok()
    }

    private fun tokenize(s: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || (c == '-' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                    val start = i
                    if (c == '-') i++
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    out.add(Tok.Num(s.substring(start, i).toDouble()))
                }
                c == '\'' || c == '"' -> {
                    val q = c
                    val start = ++i
                    while (i < s.length && s[i] != q) i++
                    if (i >= s.length) error("unterminated string")
                    out.add(Tok.Str(s.substring(start, i)))
                    i++
                }
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                    out.add(Tok.Id(s.substring(start, i)))
                }
                else -> {
                    val two = if (i + 1 < s.length) s.substring(i, i + 2) else ""
                    if (two == "==" || two == "!=" || two == "<=" || two == ">=") {
                        out.add(Tok.Op(two)); i += 2
                    } else if (c == '<' || c == '>') {
                        out.add(Tok.Op(c.toString())); i++
                    } else if (c == '(' || c == ')') {
                        out.add(Tok.Op(c.toString())); i++
                    } else {
                        error("illegal char: $c")
                    }
                }
            }
        }
        return out
    }

    // ---------- 语法 ----------
    private class Parser(private val t: List<Tok>, private val env: Map<String, Any?>) {
        private var pos = 0

        fun atEnd() = pos >= t.size

        private fun peek(): Tok? = if (pos < t.size) t[pos] else null
        private fun next(): Tok = t[pos++]

        private fun isKw(id: String): Boolean {
            val k = peek()
            return k is Tok.Id && k.v.equals(id, ignoreCase = true)
        }

        private fun isOp(op: String): Boolean {
            val k = peek()
            return k is Tok.Op && k.v == op
        }

        fun parseOr(): Any? {
            var left = parseAnd()
            while (isKw("or")) {
                next()
                val right = parseAnd()
                left = truthy(left) || truthy(right)
            }
            return left
        }

        private fun parseAnd(): Any? {
            var left = parseNot()
            while (isKw("and")) {
                next()
                val right = parseNot()
                left = truthy(left) && truthy(right)
            }
            return left
        }

        private fun parseNot(): Any? {
            if (isKw("not")) {
                next()
                return !truthy(parseNot())
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Any? {
            if (isOp("(")) {
                next()
                val v = parseOr()
                if (!isOp(")")) error("expected )")
                next()
                return v
            }
            return parseComparison()
        }

        private fun parseComparison(): Any? {
            val left = parseValue()
            val ops = listOf("==", "!=", "<=", ">=", "<", ">")
            for (o in ops) {
                if (isOp(o)) {
                    next()
                    val right = parseValue()
                    return compare(o, left, right)
                }
            }
            return left
        }

        private fun parseValue(): Any? {
            val k = peek() ?: error("unexpected end")
            return when (k) {
                is Tok.Num -> { next(); k.v }
                is Tok.Str -> { next(); k.v }
                is Tok.Id -> {
                    next()
                    when (k.v.lowercase()) {
                        "true" -> true
                        "false" -> false
                        else -> env[k.v] ?: error("undefined variable: ${k.v}")
                    }
                }
                is Tok.Op -> error("unexpected operator: ${k.v}")
            }
        }

        private fun compare(op: String, a: Any?, b: Any?): Boolean {
            val na = num(a); val nb = num(b)
            if (na != null && nb != null) {
                return when (op) {
                    "==" -> na == nb
                    "!=" -> na != nb
                    "<" -> na < nb
                    "<=" -> na <= nb
                    ">" -> na > nb
                    ">=" -> na >= nb
                    else -> false
                }
            }
            val sa = a?.toString(); val sb = b?.toString()
            return when (op) {
                "==" -> sa == sb
                "!=" -> sa != sb
                // 字符串只允许相等比较，避免隐式排序歧义
                else -> false
            }
        }

        private fun num(v: Any?): Double? = when (v) {
            is Number -> v.toDouble()
            is Boolean -> if (v) 1.0 else 0.0
            else -> null
        }
    }

    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        else -> true
    }
}
