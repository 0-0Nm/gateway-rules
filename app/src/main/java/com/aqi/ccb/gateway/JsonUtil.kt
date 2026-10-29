package com.aqi.ccb.gateway

/**
 * 极简 JSON 辅助。
 *
 * 为什么存在：
 *   GateLog 和 GatewayServer 各有一份完全相同的 esc()，
 *   都是把反斜杠和双引号转义。重复实现的风险在于——
 *   某天发现转义不完整（比如漏了换行符），改了一处忘了另一处。
 *   本项目多处 bug 都来自这个模式：默认值重复、状态源重复、
 *   WakeLock 各自实现、readHeader 各写一份……
 *
 * 只放真正被多处复用的东西，不做大而全的 JSON 库。
 */
object JsonUtil {

    /**
     * 转义字符串，使其可安全嵌入 JSON 字面量。
     *
     * 除了反斜杠和双引号，还处理控制字符——
     * 原来两处实现都漏了 \n / \r / \t，
     * 导致多行错误信息写进 JSONL 时把文件结构打乱
     * （调用日志解析时会突然出现半行 JSON）。
     */
    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (c < ' ') {
                        // 其它控制字符：用 \uXXXX 形式，避免破坏 JSON 结构
                        sb.append("\\u").append(String.format("%04x", c.code))
                    } else {
                        sb.append(c)
                    }
            }
        }
        return sb.toString()
    }
}
