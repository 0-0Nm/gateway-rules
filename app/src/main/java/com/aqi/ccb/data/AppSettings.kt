package com.aqi.ccb.data

import android.content.Context

/**
 * AppSettings —— 应用级开关与配置（非 provider/rule 类）。
 * 存 SharedPreferences，简单可靠。
 */
object AppSettings {
    private const val PREF = "app_settings"

    // 悬浮窗
    const val KEY_FLOAT_ENABLED = "float_enabled"
    // MCP
    const val KEY_MCP_ENABLED = "mcp_enabled"
    const val KEY_MCP_PORT = "mcp_port"
    const val KEY_MCP_CLIENT_NAME = "mcp_client_name"
    // 网关
    const val KEY_GATEWAY_PORT = "gateway_port"
    // 提示弹窗
    const val KEY_TOAST_ENABLED = "toast_enabled"

    const val DEFAULT_MCP_PORT = 38081
    const val DEFAULT_GATEWAY_PORT = 38080

    fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun floatEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLOAT_ENABLED, true)

    fun setFloatEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_FLOAT_ENABLED, v).apply()

    /**
     * MCP 服务开关。
     *
     * 默认值改为 true，与悬浮窗（默认 true）保持一致。
     * 之前默认 false 导致首次启动「悬浮窗开着、MCP 关着」的割裂状态。
     * MCP 仅管理接口仅回环监听并要求独立令牌；新安装默认关闭，显式配置的旧开关保留。
     */
    fun mcpEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MCP_ENABLED, false)

    fun setMcpEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_MCP_ENABLED, v).apply()

    fun mcpPort(context: Context): Int =
        prefs(context).getInt(KEY_MCP_PORT, DEFAULT_MCP_PORT)

    fun setMcpPort(context: Context, p: Int) {
        require(p in 1024..65535) { "port out of range" }
        prefs(context).edit().putInt(KEY_MCP_PORT, p).apply()
    }

    /** Client configuration alias only; never changes the MCP serverInfo name or listener. */
    fun mcpClientName(context: Context): String {
        val saved = prefs(context).getString(KEY_MCP_CLIENT_NAME, null)
        return saved?.takeIf { McpClientConfig.nameError(it) == null } ?: McpClientConfig.DEFAULT_NAME
    }

    fun setMcpClientName(context: Context, value: String) {
        McpClientConfig.validateName(value)
        check(prefs(context).edit().putString(KEY_MCP_CLIENT_NAME, value).commit()) { "Cannot save MCP client name" }
    }

    fun gatewayPort(context: Context): Int =
        prefs(context).getInt(KEY_GATEWAY_PORT, DEFAULT_GATEWAY_PORT)

    fun setGatewayPort(context: Context, p: Int) {
        require(p in 1024..65535) { "port out of range" }
        prefs(context).edit().putInt(KEY_GATEWAY_PORT, p).apply()
    }

    fun gatewayLanEnabled(context: Context): Boolean =
        prefs(context).getBoolean("gateway_lan_enabled", false)

    fun setGatewayLanEnabled(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean("gateway_lan_enabled", enabled).commit())
    }

    /** 界面提示弹窗（Toast/Snackbar）开关 */
    fun toastEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TOAST_ENABLED, true)

    fun setToastEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_TOAST_ENABLED, v).apply()
}

/** Conservative export identifier: ASCII alphanumeric, leading letter, no client-specific punctuation. */
object McpClientConfig {
    const val DEFAULT_NAME = "gatewayrules"
    const val MAX_NAME_LENGTH = 64
    private val NAME = Regex("[A-Za-z][A-Za-z0-9]{0,63}")
    fun nameError(value: String): String? = when {
        value.isEmpty() -> "请填写 MCP 名称"
        value.length > MAX_NAME_LENGTH -> "名称最多 64 个字符"
        value.first() !in 'A'..'Z' && value.first() !in 'a'..'z' -> "必须以英文字母开头"
        !NAME.matches(value) -> "只允许英文字母和数字，不含空格或特殊符号"
        else -> null
    }
    fun validateName(value: String): String {
        require(nameError(value) == null) { "Invalid MCP client name" }
        return value
    }
    fun json(name: String, port: Int, token: String): String {
        val checkedName = validateName(name)
        require(port in 1024..65535) { "Invalid MCP port" }
        require(token.isNotBlank() && token.none { it < ' ' || it == 127.toChar() }) { "Invalid MCP token" }
        return org.json.JSONObject().put("mcpServers", org.json.JSONObject().put(checkedName,
            org.json.JSONObject().put("type", "http").put("url", "http://127.0.0.1:$port/mcp")
                .put("headers", org.json.JSONObject().put("Authorization", "Bearer " + token))
        )).toString(2)
    }
}
