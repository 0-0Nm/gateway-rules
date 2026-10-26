package com.aqi.ccb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 聚合页 —— 本项目自做的一页（另一页是规则页）。
 *
 * 设计目标：一屏看清「网关在不在跑 / 连了哪些提供商 / 用了哪条规则 / 最近日志」。
 * 视觉沿用 rikka 的 CardGroup 分组范式，避免与提供商页风格割裂。
 */
data class MainUiState(
    val running: Boolean = false,
    val port: String = "38080",
    val requests: Long = 0,
    val ok: Long = 0,
    val fail: Long = 0,
    val providers: List<ProviderRow> = emptyList(),
    val rules: List<RuleRow> = emptyList(),
    val logTail: String = "",
    val statusNote: String? = null,
)

data class ProviderRow(
    val index: Int,
    val id: String,
    val name: String,
    val baseUrl: String,
    val modelCount: Int,
    val rateSummary: String,
    val enabled: Boolean,
)

data class RuleRow(
    val id: String,
    val name: String,
    val selectCount: Int,
    val executeOp: String,
    val enabled: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPortChange: (String) -> Unit,
    onAddProvider: () -> Unit,
    onEditProvider: (Int) -> Unit,
    onAddRule: () -> Unit,
    onEditRule: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GatewayRules", fontWeight = FontWeight.SemiBold)
                        Text(
                            "本地 OpenAI 兼容网关",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
            // ==================== 网关状态 ====================
            CardGroup(title = { Text("网关") }) {
                item(
                    leadingContent = {
                        CardGroupIcon(
                            icon = Icons.Filled.Cloud,
                            containerColor = if (state.running)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = if (state.running)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    headlineContent = {
                        Text(if (state.running) "运行中" else "已停止")
                    },
                    supportingContent = {
                        Text(
                            if (state.running)
                                "127.0.0.1:${state.port} · 请求 ${state.requests} · 成功 ${state.ok} · 失败 ${state.fail}"
                            else "启动后客户端把 base_url 指向 http://127.0.0.1:${state.port}/v1"
                        )
                    },
                    trailingContent = {
                        CardGroupValue(if (state.running) "●" else "○")
                    },
                )

                formItem(headlineContent = { Text("端口") }) {
                    OutlinedTextField(
                        value = state.port,
                        onValueChange = { s -> onPortChange(s.filter { it.isDigit() }.take(5)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.running,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { Text("建议 1024 以上，避免与系统端口冲突") },
                    )
                }

                formItem {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.running) {
                            OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Stop, null, Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("停止")
                            }
                        } else {
                            Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("启动")
                            }
                        }
                    }
                    state.statusNote?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // ==================== 提供商 ====================
            Spacer(Modifier.height(8.dp))
            CardGroup(title = { Text("提供商（${state.providers.size}）") }) {
                if (state.providers.isEmpty()) {
                    item(
                        headlineContent = { Text("尚未导入") },
                        supportingContent = { Text("点下方「添加提供商」填入 Base URL 与密钥") },
                    )
                } else {
                    state.providers.forEach { p ->
                        item(
                            onClick = { onEditProvider(p.index) },
                            leadingContent = {
                                CardGroupIcon(
                                    icon = Icons.Filled.Settings,
                                    containerColor = if (p.enabled)
                                        MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                                )
                            },
                            headlineContent = {
                                Text("${p.name}${if (p.enabled) "" else "（已禁用）"}")
                            },
                            supportingContent = {
                                Text("${p.modelCount} 个模型 · ${p.rateSummary}")
                            },
                            trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge) },
                        )
                    }
                }
                formItem {
                    FilledTonalButton(onClick = onAddProvider, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("添加提供商")
                    }
                }
            }

            // ==================== 规则 ====================
            Spacer(Modifier.height(8.dp))
            CardGroup(title = { Text("规则（${state.rules.size}）") }) {
                if (state.rules.isEmpty()) {
                    item(
                        headlineContent = { Text("尚未配置") },
                        supportingContent = { Text("点下方「添加规则」配置选路与执行方式") },
                    )
                } else {
                    state.rules.forEach { r ->
                        item(
                            onClick = { onEditRule(r.id) },
                            headlineContent = { Text(r.name.ifBlank { r.id }) },
                            supportingContent = {
                                Text("选路 ${r.selectCount} 步 · 执行 ${r.executeOp}")
                            },
                            trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge) },
                        )
                    }
                }
                formItem {
                    FilledTonalButton(onClick = onAddRule, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("添加规则")
                    }
                }
            }

            // ==================== 日志 ====================
            Spacer(Modifier.height(8.dp))
            CardGroup(title = { Text("系统日志") }) {
                formItem(headlineContent = { Text("最近 25 行") }) {
                    Text(
                        text = state.logTail.ifBlank { "（暂无日志）" },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = MonoFont,
                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
