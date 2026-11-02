package com.aqi.ccb.gateway

import java.net.URI
import java.security.MessageDigest

/** Transport policy shared by the data plane and MCP control plane. */
class ServerAccessPolicy(
    private val token: String,
    private val loopbackOnly: Boolean = true,
) {
    init { require(token.isNotBlank()) { "A non-empty access token is required" } }

    fun rejectStatus(headers: Map<String, String>, localPort: Int, peerLoopback: Boolean): Int? {
        if (loopbackOnly && !peerLoopback) return 403
        // These are native-client APIs, not browser APIs. Reject even Origin:null.
        if (headers.containsKey("origin")) return 403
        val authority = headers["host"] ?: return 400
        val uri = try { URI("http://" + authority) } catch (_: Exception) { return 400 }
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            !uri.rawPath.isNullOrEmpty() || uri.host.isNullOrBlank()) return 400
        val host = uri.host.lowercase().removePrefix("[").removeSuffix("]")
        if (loopbackOnly && host !in setOf("localhost", "127.0.0.1", "::1")) return 403
        if ((if (uri.port < 0) 80 else uri.port) != localPort) return 403
        val auth = headers["authorization"] ?: return 401
        val split = auth.indexOf(' ')
        if (split < 0 || !auth.substring(0, split).equals("Bearer", ignoreCase = true)) return 401
        val candidate = auth.substring(split + 1)
        if (!MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), candidate.toByteArray(Charsets.UTF_8))) return 401
        return null
    }
}
