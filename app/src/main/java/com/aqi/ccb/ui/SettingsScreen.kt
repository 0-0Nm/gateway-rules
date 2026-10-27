package com.aqi.ccb.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsState,
    onGatewayPort: (String) -> Unit,
    onFloatToggle: (Boolean) -> Unit,
    onMcpToggle: (Boolean) -> Unit,
    onMcpPort: (String) -> Unit,
    onToastToggle: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
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
