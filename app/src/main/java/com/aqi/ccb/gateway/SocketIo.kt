package com.aqi.ccb.gateway

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 极简 HTTP 报文读写工具。
 *
 * 为什么抽出来：
 *   GatewayServer 和 McpServer 各自实现了一份 readHeader / readExactly，
 *   逻辑完全相同（只差一处toString缓存），属于典型重复。
 *   改一处忘另一处的风险是真实的——本项目的多数 bug 都源于此模式：
 *   默认值重复、状态源重复、WakeLock 各自实现……同一个道理。
 *
 * 只做最基础的事：读到 \r\n\r\n 为止、按长度精确读 body。
 * 不做 chunked、不做 keep-alive——两个 server 都是短连接。
 */
object SocketIo {

    /** 单个请求头最大字节数，超过视为异常请求直接断开 */
    private const val MAX_HEADER = 32 * 1024

    /**
     * 读到空行（\r\n\r\n）为止，返回不含末尾空行的头文本。
     * 流已结束且无内容时返回 null。
     */
    fun readHeader(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            sb.append(b.toChar())
            if (prev == '\r'.code && b == '\n'.code) {
                // 只在刚闭合一个 CRLF 时检查，避免每字节都 toString
                val s = sb.toString()
                if (s.endsWith("\r\n\r\n")) return s.dropLast(2)
            }
            prev = b
            if (sb.length > MAX_HEADER) return null
        }
    }

    /**
     * 精确读取 len 字节。
     * 流提前结束则返回已读到的部分（调用方按实际长度处理）。
     */
    fun readExactly(input: InputStream, len: Int): ByteArray {
        val out = ByteArrayOutputStream(len)
        val buf = ByteArray(4096)
        var remaining = len
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size, remaining))
            if (n <= 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    /** 解析请求头为小写 key 的 map（冒号后去空格） */
    fun parseHeaders(headerText: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val lines = headerText.split("\r\n")
        for (i in 1 until lines.size) {
            val line = lines[i]
            val idx = line.indexOf(':')
            if (idx > 0) {
                out[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }
        }
        return out
    }
}
