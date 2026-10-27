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
) {
    val mcpUrl: String get() = "http://127.0.0.1:$mcpPort/mcp"
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
                        Text("显示网关状态（待命白/生成蓝/中断红），单击锁定，长按2秒解锁")
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
                                "运行中 · http://127.0.0.1:${state.mcpPort}/mcp"
                            else if (state.mcpEnabled)
                                "已启用但未启动（网关启动后自动拉起）"
                            else "关闭状态"
                        )
                    },
                )
                formItem(headlineContent = { Text("MCP 端口") }) {
                    OutlinedTextField(
                        value = state.mcpPort,
                        onValueChange = { onMcpPort(it.filter { c -> c.isDigit() }.take(5)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = {
                            Text("默认 38081，与网关端口分开。改动后 MCP 会重启")
                        },
                    )
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
