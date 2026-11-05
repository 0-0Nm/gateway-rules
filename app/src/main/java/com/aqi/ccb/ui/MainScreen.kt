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
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
data class VirtualRow(
    val name: String,
    val memberCount: Int,
    val ruleId: String,
    val enabled: Boolean,
)

data class ProviderHealthRow(
    val providerId:String, val name:String, val state:String, val kind:String="",
    val scope:String="", val modelId:String="", val remainingSec:Long=0L,
    val observedAgoSec:Long=-1L, val retrySafe:Boolean=false, val localImpact:String="",
    val httpStatus:Int?=null,
)

data class MainUiState(
    val running: Boolean = false,
    val health: List<ProviderHealthRow> = emptyList(),
    val virtualModels: List<VirtualRow> = emptyList(),
    val floatAlpha: Int = 80,
    val floatLocked: Boolean = false,
    val port: String = "38080",
    val requests: Long = 0,
    val ok: Long = 0,
    val fail: Long = 0,
    val providers: List<ProviderRow> = emptyList(),
    val rules: List<RuleRow> = emptyList(),
    val logTail: String = "",
    val statusNote: String? = null,
    // ---- 实时监控 / 路径诊断（照 DeepSeek 本地 API 面板）----
    val diagPath: String = "",
    val diagBind: String = "",
    val diagAcceptAlive: Boolean = false,
    val diagTcpConns: Int = 0,
    val diagDisconnects: Long = 0,
    val diagWorkers: Int = 0,
    val diagQueued: Int = 0,
    val diagUptimeSec: Long = 0,
    val diagLatestRoute: String = "",
    val diagLatestFailure: String = "",
    val diagFailureReason: String = "",
    val diagLanHint: String = "",
)

data class ProviderRow(
    val index: Int,
    val id: String,
    val name: String,
    val baseUrl: String,
    val modelCount: Int,
    val rateSummary: String,
    val enabled: Boolean,
    val healthSummary: String = "",
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
    currentTab: String = "agg",
    onTabChange: (String) -> Unit = {},
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPortChange: (String) -> Unit,
    onAddProvider: () -> Unit,
    onEditProvider: (Int) -> Unit,
    onAddRule: () -> Unit,
    onEditRule: (String) -> Unit,
    onAddVirtual: () -> Unit = {},
    onEditVirtual: (String) -> Unit = {},
    onFloatAlpha: (Int) -> Unit = {},
    onCopyApiKey: () -> Unit = {},
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
                .padding(pad),
        ) {
            PagerTabs(
                tabs = listOf(
                    TabItem("agg", "聚合"),
                    TabItem("providers", "提供商"),
                    TabItem("rules", "规则"),
                    TabItem("vmodels", "虚拟模型"),
                    TabItem("settings", "设置"),
                ),
                selected = currentTab,
                onSelect = onTabChange,
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
            // ==================== 网关状态（聚合 tab）====================
            if (currentTab == "agg") {
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
            }

            // ==================== 实时监控与路径诊断（聚合 tab）====================
            if (currentTab == "agg") {
            Spacer(Modifier.height(8.dp))
            CardGroup(title = { Text("实时监控") }) {
                // 路径诊断：最值钱的一条，直接告诉用户"为什么连不上"
                formItem(
                    headlineContent = { Text("路径诊断") },
                    supportingContent = {
                        Text(
                            state.diagPath.ifBlank { "启动网关后显示" },
                            color = if (state.diagPath.contains("正常") ||
                                state.diagPath.contains("空闲"))
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MonitorRow("实际绑定", state.diagBind.ifBlank { "—" })
                        MonitorRow("accept 线程", if (state.diagAcceptAlive) "Alive" else "Dead")
                        MonitorRow("TCP 连接", state.diagTcpConns.toString())
                        MonitorRow("客户端断开", state.diagDisconnects.toString())
                        MonitorRow("HTTP 线程", "${state.diagWorkers} 活跃 / ${state.diagQueued} 排队")
                        MonitorRow("运行时长", fmtUptime(state.diagUptimeSec))
                        MonitorRow("最近路由", state.diagLatestRoute.ifBlank { "—" })
                        if (state.diagFailureReason.isNotBlank()) {
                            MonitorRow("最近失败", state.diagFailureReason)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            CardGroup(title = { Text("上游健康") }) {
                if (state.health.isEmpty()) {
                    item(
                        headlineContent = { Text(if(state.running)"暂无提供商" else "等待网关启动") },
                        supportingContent = { Text("健康状态来自实际请求与本地冷却，不读取密钥或提示词正文") },
                    )
                } else {
                    state.health.forEach { h ->
                        val color=when(h.state){
                            "ready"->MaterialTheme.colorScheme.primary
                            "cooldown"->MaterialTheme.colorScheme.tertiary
                            "degraded"->MaterialTheme.colorScheme.error
                            else->MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        item(
                            headlineContent = { Text("${h.name} · ${healthStateLabel(h.state)}",color=color) },
                            supportingContent = {
                                Column(verticalArrangement=Arrangement.spacedBy(2.dp)){
                                    Text(healthDescription(h))
                                    if(h.observedAgoSec>=0 && h.state in setOf("cooldown","degraded"))
                                        Text("最近观察 ${fmtAgo(h.observedAgoSec)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            trailingContent = { CardGroupValue(when(h.state){"ready"->"●";"cooldown"->"${h.remainingSec}s";"degraded"->"!";"disabled"->"—";else->"○"}) },
                        )
                    }
                }
            }

            // ==================== 连接配置（一键复制）====================
            Spacer(Modifier.height(8.dp))
            CardGroup(title = { Text("连接配置") }) {
                formItem(
                    headlineContent = { Text("本机地址") },
                    supportingContent = { Text("客户端 base_url 填这个") },
                ) {
                    val cb = LocalClipboardManager.current
                    Column {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "http://127.0.0.1:${state.port}/v1",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                cb.setText(AnnotatedString("http://127.0.0.1:${state.port}/v1"))
                                UiToast.show("已复制本机地址")
                            }) { Text("复制 URL") }
                            OutlinedButton(onClick = onCopyApiKey) {
                                Text("复制 API Key")
                            }
                        }
                    }
                }

                if (state.diagLanHint.isNotBlank()) {
                    formItem(
                        headlineContent = { Text("局域网地址") },
                        supportingContent = { Text("同 WiFi 的其他设备用这个") },
                    ) {
                        val cb = LocalClipboardManager.current
                        Column {
                            Text(
                                text = state.diagLanHint,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = {
                                cb.setText(AnnotatedString(state.diagLanHint))
                                UiToast.show("已复制局域网地址")
                            }) { Text("复制局域网地址") }
                        }
                    }
                }
            }
            }

            // ==================== 悬浮窗（聚合 tab）====================
            if (currentTab == "agg") {
            Spacer(Modifier.height(8.dp))
            CardGroup(title = { Text("悬浮窗") }) {
                formItem(
                    headlineContent = { Text("透明度 ${state.floatAlpha}%") },
                    supportingContent = {
                        Text(if (state.floatLocked) "已锁定（单击悬浮球解锁）" else "单击悬浮球锁定 · 长按拖动位置")
                    },
                ) {
                    Slider(
                        value = state.floatAlpha.toFloat(),
                        onValueChange = { onFloatAlpha(it.toInt()) },
                        valueRange = 10f..100f,
                        steps = 17,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            }

            // ==================== 提供商 ====================
            if (currentTab == "providers") {
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
                                Column(verticalArrangement=Arrangement.spacedBy(2.dp)){
                                    Text("${p.modelCount} 个模型 · ${p.rateSummary}")
                                    if(p.healthSummary.isNotBlank())Text(p.healthSummary,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
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
            }

            // ==================== 虚拟模型 ====================
            if (currentTab == "vmodels") {
            Spacer(Modifier.height(8.dp))
            CardGroup(title = { Text("虚拟模型（${state.virtualModels.size}）") }) {
                if (state.virtualModels.isEmpty()) {
                    item(
                        headlineContent = { Text("尚未创建") },
                        supportingContent = {
                            Text("把多个提供商的模型融合成一个自定义模型，客户端直接用它")
                        },
                    )
                } else {
                    state.virtualModels.forEach { v ->
                        item(
                            onClick = { onEditVirtual(v.name) },
                            headlineContent = {
                                Text("${v.name}${if (v.enabled) "" else "（已禁用）"}")
                            },
                            supportingContent = {
                                Text("${v.memberCount} 个成员 · 规则 ${v.ruleId}")
                            },
                            trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge) },
                        )
                    }
                }
                formItem {
                    FilledTonalButton(onClick = onAddVirtual, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("新建虚拟模型")
                    }
                }
            }
            }

            // ==================== 规则 ====================
            if (currentTab == "rules") {
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
            }

            // ==================== 日志（聚合 tab）====================
            if (currentTab == "agg") {
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
            }

            Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/** 上游健康状态的纯展示转换。 */
private fun healthStateLabel(v:String)=when(v){"ready"->"可用";"cooldown"->"冷却中";"degraded"->"异常";"disabled"->"已禁用";else->"未运行"}
private fun healthKindLabel(v:String)=when(v){
    "rate_limit"->"上游限流";"credit_exhausted"->"余额或额度不足";"auth_rejected"->"认证被拒";
    "access_rejected"->"访问被拒";"model_or_route_missing"->"模型或路由不存在";
    "upstream_timeout"->"上游超时";"upstream_unavailable"->"上游暂不可用";
    "upstream_failure"->"上游故障";"connectivity"->"连接失败";
    "ambiguous_transport"->"发送后连接中断";else->if(v.isBlank())"无异常" else v
}
private fun healthScopeLabel(v:String)=when(v){"provider"->"整个提供商";"credential"->"当前凭据";"provider_model"->"当前模型路由";"attempt"->"本次尝试";else->"未分类"}
private fun healthImpactLabel(v:String)=when(v){"provider_cooldown"->"本地整家冷却";"attempt_failover"->"允许切换候选";"request_stopped"->"本请求停止";else->"无本地限制"}
private fun healthDescription(h:ProviderHealthRow):String=when(h.state){
    "ready"->"最近没有影响健康的错误"
    "stopped"->"启动网关后开始观察实际请求"
    "disabled"->"配置已禁用，不参与选路"
    else->buildString{append(healthKindLabel(h.kind));append(" · ");append(healthScopeLabel(h.scope));append(" · ");append(healthImpactLabel(h.localImpact));if(h.modelId.isNotBlank())append(" · "+h.modelId);h.httpStatus?.let{append(" · HTTP "+it)};if(!h.retrySafe)append(" · 不自动重放")}
}
private fun fmtAgo(sec:Long):String=when{sec<60->"${sec}s前";sec<3600->"${sec/60}分钟前";else->"${sec/3600}小时前"}

@Composable
private fun MonitorRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
        )
    }
}

/** 秒 -> 人话时长 */
private fun fmtUptime(sec: Long): String {
    if (sec <= 0) return "—"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s2 = sec % 60
    return when {
        h > 0 -> "${h}h${m}m"
        m > 0 -> "${m}m${s2}s"
        else -> "${s2}s"
    }
}
