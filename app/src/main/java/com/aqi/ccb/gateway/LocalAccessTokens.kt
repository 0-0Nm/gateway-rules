package com.aqi.ccb.gateway

import android.content.Context
import java.security.SecureRandom

/** App-private per-install tokens. Never emitted in status, diagnostics or logs. */
object LocalAccessTokens {
    private const val PREFS = "local_access_tokens_v1"
    @Synchronized
    private fun get(context: Context, role: String): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString(role, null)
        if (!old.isNullOrBlank()) return old
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val value = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(prefs.edit().putString(role, value).commit()) { "Cannot persist local access token" }
        return value
    }
    fun existingForRedaction(context: Context): List<String> {
        val prefs=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        return listOfNotNull(prefs.getString("mcp_control",null),prefs.getString("gateway_data",null))
    }
    fun mcp(context: Context): String = get(context, "mcp_control")
    fun gateway(context: Context): String = get(context, "gateway_data")
}
