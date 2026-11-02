package com.aqi.ccb.gateway

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.util.Locale

/** HTTP/1.1 short-connection framing. Unsupported transfer encoding is rejected. */
object SocketIo {
    const val MAX_HEADER_BYTES = 32 * 1024
    const val MAX_MCP_BODY_BYTES = 1024 * 1024
    const val MAX_GATEWAY_BODY_BYTES = 8 * 1024 * 1024
    class HttpInputException(val status: Int, message: String) : java.io.IOException(message)
    data class RequestHead(val method: String, val path: String, val headers: Map<String, String>)

    fun readHeader(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var tail = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null // never process a partial header
            if (b == 0 || (b < 32 && b != 9 && b != 10 && b != 13)) {
                throw HttpInputException(400, "Invalid header character")
            }
            out.write(b)
            if (out.size() > MAX_HEADER_BYTES) throw HttpInputException(431, "Headers too large")
            tail = (tail shl 8) or b
            if (tail == 0x0d0a0d0a) return out.toString("ISO-8859-1").dropLast(2)
        }
    }

    fun parseRequestHead(text: String): RequestHead {
        val first = text.substringBefore("\r\n").split(' ')
        if (first.size != 3 || first[2] != "HTTP/1.1" || !first[1].startsWith("/") ||
            first[1].contains('#') || first[1].any { it <= ' ' }) {
            throw HttpInputException(400, "Invalid request line")
        }
        if (!Regex("[A-Z]+").matches(first[0])) throw HttpInputException(400, "Invalid method")
        return RequestHead(first[0], first[1].substringBefore('?'), parseHeaders(text))
    }

    fun parseHeaders(headerText: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in headerText.split("\r\n").drop(1)) {
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx <= 0 || line[0].isWhitespace()) throw HttpInputException(400, "Invalid header")
            val rawName = line.substring(0, idx)
            if (!Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+").matches(rawName)) {
                throw HttpInputException(400, "Invalid header name")
            }
            val name = rawName.lowercase(Locale.ROOT)
            if (out.containsKey(name)) throw HttpInputException(400, "Duplicate header")
            val value = line.substring(idx + 1).trim(' ', '\t')
            if (value.any { it == '\r' || it == '\n' || it == 127.toChar() }) {
                throw HttpInputException(400, "Invalid header value")
            }
            out[name] = value
        }
        if (!out.containsKey("host")) throw HttpInputException(400, "Host required")
        return out
    }

    fun contentLength(headers: Map<String, String>, maximum: Int): Int {
        if (headers.containsKey("transfer-encoding")) throw HttpInputException(400, "Transfer-Encoding unsupported")
        if (headers.containsKey("expect")) throw HttpInputException(417, "Expect unsupported")
        val raw = headers["content-length"] ?: return 0
        if (!Regex("[0-9]+").matches(raw)) throw HttpInputException(400, "Invalid Content-Length")
        val value = raw.toLongOrNull() ?: throw HttpInputException(413, "Body too large")
        if (value > maximum) throw HttpInputException(413, "Body too large")
        return value.toInt()
    }

    fun readExactly(input: InputStream, len: Int): ByteArray {
        if (len < 0 || len > MAX_GATEWAY_BODY_BYTES) throw HttpInputException(413, "Body too large")
        val out = ByteArray(len)
        var used = 0
        while (used < len) {
            val n = input.read(out, used, len - used)
            if (n < 0) throw EOFException("Truncated request body")
            if (n == 0) {
                val b = input.read()
                if (b < 0) throw EOFException("Truncated request body")
                out[used++] = b.toByte()
            } else used += n
        }
        return out
    }

    /** Validate the actual HTTP framing even when a JDK silently returns early EOF. */
    fun responseLength(conn: java.net.HttpURLConnection): Long? {
        if (conn.getHeaderField("Transfer-Encoding") != null) return null
        val value = conn.getHeaderField("Content-Length") ?: return null
        return value.trim().toLongOrNull()?.takeIf { it >= 0 }
            ?: throw java.io.IOException("Invalid upstream Content-Length")
    }

    fun readResponse(conn: java.net.HttpURLConnection, maximum: Int = MAX_GATEWAY_BODY_BYTES): ByteArray {
        val length = responseLength(conn)
        if (length != null && length > maximum) throw java.io.IOException("Upstream body too large")
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val out = ByteArrayOutputStream()
        stream?.use { input ->
            val buf = ByteArray(4096)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                if (out.size().toLong() + n > maximum) throw java.io.IOException("Upstream body too large")
                out.write(buf, 0, n)
            }
        }
        if (length != null && out.size().toLong() != length) throw EOFException("Truncated upstream body")
        return out.toByteArray()
    }

    /** OpenAI SSE completion, bounded across arbitrary network chunks and multiline events. */
    class SseCompletion(private val onEvent: (org.json.JSONObject) -> Unit = {}) {
        private val line = ByteArrayOutputStream()
        private val data = StringBuilder()
        private var previousCr = false
        private var done = false
        fun accept(bytes: ByteArray, count: Int) {
            for (i in 0 until count) {
                val b = bytes[i].toInt() and 255
                if (b == 10 && previousCr) { previousCr = false; continue }
                previousCr = b == 13
                if (b == 10 || b == 13) {
                    val text = line.toString("UTF-8"); line.reset()
                    if (text.isEmpty()) {
                        if (data.isNotEmpty()) {
                            val event = data.toString().removeSuffix("\n"); data.setLength(0)
                            if (done) throw java.io.IOException("SSE data after DONE")
                            if (event.trim() == "[DONE]") done = true
                            else {
                                val json = try { org.json.JSONObject(event) } catch (_: Exception) {
                                    throw java.io.IOException("Invalid SSE JSON")
                                }
                                if (json.has("error") && !json.isNull("error")) throw java.io.IOException("Upstream SSE error")
                                onEvent(json)
                            }
                        }
                    } else if (text == "data" || text.startsWith("data:")) {
                        val value = if (text == "data") "" else text.substring(5).removePrefix(" ")
                        if (data.length + value.length > 262144) throw java.io.IOException("SSE event too large")
                        data.append(value).append('\n')
                    }
                } else {
                    if (line.size() >= 262144) throw java.io.IOException("SSE line too large")
                    line.write(b)
                }
            }
        }
        fun requireComplete() {
            if (!done || data.isNotEmpty() || line.size() != 0) throw EOFException("SSE ended without complete DONE event")
        }
    }
}
