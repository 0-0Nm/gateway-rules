package com.aqi.ccb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 设置页 —— 开关组。
 *
 * 覆盖 GREED 提出的：
 *  - 悬浮窗开关（原来默认常开、无法关闭）
 *  - MCP 开关 + MCP 端口可设
 *  - 界面提示弹窗开关（原来默认开、无关闭选项）
 */
data class SettingsState(
    val gatewayPort: String = "38080",
    val floatEnabled: Boolean = true,
    val mcpEnabled: Boolean = false,
    val mcpPort: String = "38081",
    val toastEnabled: Boolean = true,
    val mcpRunning: Boolean = false,
    // ---- MCP 状态检测 ----
    val mcpCalls: Long = 0,
    val mcpErrors: Long = 0,
    val mcpLastMethod: String = "",
    val mcpLastLatencyMs: Long = 0,
    val mcpLastResult: String = "",
    val mcpLastError: String? = null,
    /** 实际监听中的端口（与 mcpPort 草稿可能不同，未点「应用」前不等） */
    val mcpPortApplied: String = "",
    // ---- 权限状态 ----
    val permOverlay: Boolean = false,
    /** 悬浮窗精确状态：GRANTED / DENIED / UNKNOWN */
    val permOverlayStatus: String = "DENIED",
    val permNotification: Boolean = false,
    val permBattery: Boolean = false,
    /** 电池项精确状态：GRANTED / DENIED / UNKNOWN（vivo 等 ROM 判定不可靠时为 UNKNOWN） */
    val permBatteryStatus: String = "DENIED",
    /** 电池项判定依据，便于用户/开发者排查 */
    val permBatteryEvidence: String = "",
    /** 悬浮球最近一次挂载失败原因 */
    val floatError: String? = null,
    /**
     * 自启动状态：DENIED（未前往）/ UNKNOWN（已前往，系统无法确认真实状态）
     * Android 无标准 API 可查询自启动白名单，故只做"是否前往过"的推断。
     */
    val permAutostartStatus: String = "DENIED",
    /** 用户是否曾前往自启动设置页 */
    val permAutostartVisited: Boolean = false,
) {
    /** 地址用实际生效端口，避免显示未应用的草稿值 */
    val mcpUrl: String
        get() {
            val p = if (mcpPortApplied.isNotEmpty()) mcpPortApplied else mcpPort
            return "http://127.0.0.1:$p/mcp"
        }

    /** 草稿与生效值是否不一致（UI 提示"未应用"） */
    val mcpPortDirty: Boolean
        get() = mcpPortApplied.isNotEmpty() && mcpPort != mcpPortApplied
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsState,
    onGatewayPort: (String) -> Unit,
    onFloatToggle: (Boolean) -> Unit,
    onMcpToggle: (Boolean) -> Unit,
    onMcpPort: (String) -> Unit,
    onToastToggle: (Boolean) -> Unit,
    onBack: () -> Unit = {},
    onMcpCopyRaw: (() -> Unit)? = null,
    onMcpPortApply: (() -> Unit)? = null,
    onOpenPerm: ((String) -> Unit)? = null,
    onRequestAllPerms: (() -> Unit)? = null,
    onOpenAutoStart: (() -> Unit)? = null,
    freezeProneRom: Boolean = false,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // ---------- 网关 ----------
            CardGroup(title = { Text("网关") }) {
                formItem(headlineContent = { Text("监听端口") }) {
                    OutlinedTextField(
                        value = state.gatewayPort,
                        onValueChange = { onGatewayPort(it.filter { c -> c.isDigit() }.take(5)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { Text("改动后需重启网关生效") },
                    )
                }
            }

            // ---------- 悬浮窗 ----------
            Spacer(Modifier.height(8.dp))
            CardGroup(title = { Text("悬浮窗") }) {
                switchItem(
                    checked = state.floatEnabled,
                    onCheckedChange = onFloatToggle,
                    headlineContent = { Text("启用悬浮球") },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("显示网关状态（待命白/生成蓝/中断红），单击锁定，长按2秒解锁")
                            Text(
                                "⚠ vivo / iQOO 等定制系统：系统「权限查询」可能报未授予，" +
                                    "但悬浮球实际能正常显示 —— 这是系统判定不完整，" +
                                    "不是真的缺权限。以本页下方「权限」区状态为准。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    },
                )
            }

            // ---------- MCP ----------
            Spacer(Modifier.height(8.dp))
            CardGroup(title = { Text("MCP 服务（供 AI 调用）") }) {
                switchItem(
                    checked = state.mcpEnabled,
                    onCheckedChange = onMcpToggle,
                    headlineContent = { Text("启用 MCP 服务") },
                    supportingContent = {
                        Text(
                            if (state.mcpEnabled && state.mcpRunning)
                                "运行中 · ${state.mcpUrl}"
                            else if (state.mcpEnabled)
                                "已启用但未启动（网关启动后自动拉起）"
                            else "关闭状态"
                        )
                    },
                )
                formItem(
                    headlineContent = { Text("MCP 端口") },
                    supportingContent = {
                        Text(
                            if (state.mcpPortDirty)
                                "未应用：当前实际监听 ${state.mcpPortApplied}，输入的是 ${state.mcpPort}"
                            else if (state.mcpPortApplied.isNotEmpty())
                                "当前监听 ${state.mcpPortApplied}"
                            else "默认 38081"
                        )
                    },
                ) {
                    Column {
                        OutlinedTextField(
                            value = state.mcpPort,
                            // 关键修复：只更新草稿，不触发重启。
                            // 之前每击键都调 setMcpPort -> 每个中间值都 stop/start，
                            // 导致 "3"->"38"->"380" 全部 BindException，最后停在半截端口。
                            onValueChange = { onMcpPort(it.filter { c -> c.isDigit() }.take(5)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = state.mcpPortDirty,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onMcpPortApply?.invoke() },
                                enabled = state.mcpPortDirty || state.mcpPortApplied.isEmpty(),
                            ) { Text("应用并重启") }
                            if (state.mcpPortDirty) {
                                OutlinedButton(onClick = {
                                    onMcpPort(state.mcpPortApplied)
                                }) { Text("撤销") }
                            }
                        }
                    }
                }
                // ---- 状态检测 ----
                item(
                    headlineContent = {
                        Text(
                            when {
                                !state.mcpEnabled -> "状态：已关闭"
                                state.mcpRunning -> "状态：运行中 ✓"
                                else -> "状态：未启动 ✗"
                            }
                        )
                    },
                    supportingContent = {
                        Text(
                            if (state.mcpRunning)
                                "调用 ${state.mcpCalls} 次 · 失败 ${state.mcpErrors} 次" +
                                    (if (state.mcpLastMethod.isNotEmpty())
                                        " · 最近 ${state.mcpLastMethod} ${state.mcpLastLatencyMs}ms"
                                    else "")
                            else if (state.mcpEnabled)
                                "已启用但未启动，检查端口是否被占用"
                            else "打开上方开关以启用"
                        )
                    },
                    leadingContent = {
                        CardGroupIcon(
                            icon = Icons.Filled.Settings,
                            containerColor = if (state.mcpRunning)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                    },
                )

                // ---- 可复制的 MCP 地址 ----
                formItem(
                    headlineContent = { Text("MCP 地址（点击复制）") },
                    supportingContent = {
                        Text("客户端 Base URL 填这个；MCP 类型选 HTTP / Streamable HTTP")
                    },
                ) {
                    val clipboard = LocalClipboardManager.current
                    val ctx = LocalContext.current
                    Column {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = state.mcpUrl,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = MonoFont
                                ),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                clipboard.setText(AnnotatedString(state.mcpUrl))
                                UiToast.show("已复制：" + state.mcpUrl)
                            }) { Text("复制地址") }
                            OutlinedButton(onClick = onMcpCopyRaw ?: {}) {
                                Text("复制配置 JSON")
                            }
                        }
                    }
                }

                state.mcpLastError?.let { err ->
                    item(
                        headlineContent = { Text("最近错误") },
                        supportingContent = {
                            Text(err, color = MaterialTheme.colorScheme.error)
                        },
                    )
                }

                item(
                    headlineContent = { Text("可用工具") },
                    supportingContent = {
                        Text(
                            "gw_list_providers · gw_add_provider · gw_test_models · " +
                                "gw_list_rules · gw_list_virtual_models · gw_get_status · gw_read_log"
                        )
                    },
                )
            }

            // ---------- 权限 ----------
            Spacer(Modifier.height(8.dp))
            CardGroup(title = { Text("权限") }) {
                val missing = listOf(
                    Triple("overlay", "悬浮窗", state.permOverlay),
                    Triple("notification", "通知", state.permNotification),
                    Triple("battery", "忽略电池优化", state.permBattery),
                ).filter { !it.third }

                if (missing.isEmpty()) {
                    item(
                        headlineContent = { Text("全部已授予 ✓") },
                        supportingContent = { Text("悬浮窗 / 通知 / 电池白名单均正常") },
                    )
                } else {
                    item(
                        headlineContent = { Text("有 ${missing.size} 项未授予") },
                        supportingContent = {
                            Text(
                                "缺少这些权限时：悬浮球不显示、后台容易被回收",
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        trailingContent = {
                            Button(onClick = { onRequestAllPerms?.invoke() }) {
                                Text("全部申请")
                            }
                        },
                    )
                }

                item(
                    onClick = { onOpenPerm?.invoke("overlay") },
                    headlineContent = { Text("悬浮窗") },
                    supportingContent = {
                        val unknown = state.permOverlayStatus == "UNKNOWN"
                        Text(
                            when {
                                state.permOverlay -> "已授予"
                                unknown -> "曾成功挂载过，但系统现在报未授予（可能已被撤销），请点右侧确认"
                                else -> "未授予 · 点击去开启（悬浮球必需）"
                            },
                            color = when {
                                state.permOverlay -> MaterialTheme.colorScheme.onSurfaceVariant
                                unknown -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.error
                            }
                        )
                    },
                    trailingContent = {
                        Text(
                            when {
                                state.permOverlay -> "✓"
                                state.permOverlayStatus == "UNKNOWN" -> "待确认"
                                else -> "去开启"
                            }
                        )
                    },
                )

                item(
                    onClick = { onOpenPerm?.invoke("notification") },
                    headlineContent = { Text("通知") },
                    supportingContent = {
                        Text(
                            if (state.permNotification) "已授予"
                            else "未授予 · 前台服务通知不可见",
                            color = if (state.permNotification)
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error
                        )
                    },
                    trailingContent = {
                        Text(if (state.permNotification) "✓" else "去开启")
                    },
                )

                item(
                    onClick = { onOpenPerm?.invoke("battery") },
                    headlineContent = { Text("忽略电池优化") },
                    supportingContent = {
                        val isUnknown = state.permBatteryStatus == "UNKNOWN"
                        Text(
                            when {
                                state.permBattery -> "已加入白名单"
                                isUnknown ->
                                    "已去过设置页；本机系统对该项判定不可靠，请自行确认" +
                                        (if (state.permBatteryEvidence.isNotBlank())
                                            "（" + state.permBatteryEvidence + "）" else "")
                                else -> "未加入 · 息屏后网关可能被回收"
                            },
                            color = when {
                                state.permBattery -> MaterialTheme.colorScheme.onSurfaceVariant
                                isUnknown -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.error
                            }
                        )
                    },
                    trailingContent = {
                        Text(
                            when {
                                state.permBattery -> "✓"
                                state.permBatteryStatus == "UNKNOWN" -> "待确认"
                                else -> "去设置"
                            }
                        )
                    },
                )

                // ---- 厂商自启动（唯一能真正解冻的路径）----
                item(
                    onClick = { onOpenAutoStart?.invoke() },
                    headlineContent = { Text("自启动 / 后台运行管理") },
                    supportingContent = {
                        // Android 无标准 API 查自启动白名单真实状态，
                        // 因此不谎报"已开启"，按"是否前往过"分级提示。
                        val visited = state.permAutostartVisited
                        Text(
                            when {
                                visited ->
                                    "已前往设置页。本机系统不提供自启动状态查询，请自行确认" +
                                        "本应用已在允许列表中；若仍被冻结，请再进去检查一次"
                                freezeProneRom ->
                                    "本机为 ${android.os.Build.BRAND}：息屏后系统会冻结进程" +
                                        "（服务还在但不响应），必须在此加入允许列表"
                                else -> "点击前往系统设置，允许后台运行"
                            },
                            color = when {
                                visited -> MaterialTheme.colorScheme.onSurfaceVariant
                                freezeProneRom -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    },
                    trailingContent = {
                        Text(
                            if (state.permAutostartVisited) "已前往" else "去设置",
                            color = if (state.permAutostartVisited)
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary
                        )
                    },
                )

                state.floatError?.let { err ->
                    item(
                        headlineContent = { Text("悬浮球挂载失败") },
                        supportingContent = {
                            Text(err, color = MaterialTheme.colorScheme.error)
                        },
                    )
                }
            }

            // ---------- 界面 ----------
            Spacer(Modifier.height(8.dp))
            CardGroup(title = { Text("界面") }) {
                switchItem(
                    checked = state.toastEnabled,
                    onCheckedChange = onToastToggle,
                    headlineContent = { Text("操作提示弹窗") },
                    supportingContent = { Text("保存成功、测试结果等气泡提示") },
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
