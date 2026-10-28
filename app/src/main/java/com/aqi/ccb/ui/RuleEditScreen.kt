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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 规则页 —— 本项目自做的第二页。
 *
 * 结构对应契约：select（有序纯算子链）+ execute（单个执行算子）。
 * 视觉沿用 CardGroup 范式。
 */
data class RuleDraft(
    val id: String = "default",
    val name: String = "默认规则",
    val selectedOps: List<String> = listOf("breaker_filter", "fastest_first"),
    val executeOp: String = "first",
    val enabled: Boolean = true,
    /** 模型切换粒度：SESSION / TURN / CALL */
    val switchGranularity: String = "TURN",
    val ratePerMin: String = "30",
    val burst: String = "3",
    val minIntervalMs: String = "800",
    val jitterMs: String = "150",
    val maxInflight: String = "2",
) {
    companion object {
        /** select 算子清单：key -> (中文名, 说明) */
        val SELECT_OPS = listOf(
            Triple("breaker_filter", "熔断过滤", "剔除正在熔断的提供商"),
            Triple("require", "能力要求", "只留支持 tools 的"),
            Triple("quota_filter", "配额过滤", "余额低于阈值剔除"),
            Triple("where", "表达式过滤", "如 lat < 3000"),
            Triple("tier_first", "免费优先", "免费额度优先"),
            Triple("fastest_first", "最快优先", "按实测延迟排序"),
            Triple("cheapest_first", "最省优先", "免费在前"),
            Triple("least_errors", "错误最少", "按错误数排序"),
            Triple("lru", "最久未用", "摊平负载"),
            Triple("round_robin", "轮询轮换", "确定性轮转"),
            Triple("weighted_rr", "加权轮换", "按权重随机"),
            Triple("random", "随机", "纯随机"),
            Triple("sticky_session", "会话粘性", "同会话固定一家"),
            Triple("sticky_prefix", "前缀粘性", "同 system prompt 固定"),
            Triple("ab_split", "A/B 分桶", "确定性分桶灰度"),
            Triple("canary", "灰度", "按百分比放量"),
            Triple("error_rate", "错误率过滤", "滑动窗口"),
            Triple("budget_cap", "预算上限", "超预算剔除"),
            Triple("take", "截断", "只留前 N 个"),
        )

        /**
         * 执行算子清单。
         *
         * 诚实原则：只列服务端真正实现了的。
         * 之前列了 8 个但服务端只跑 first，用户选 race 也无效——
         * 这是静默忽略配置，比缺少功能更糟。
         *
         * 待实现（服务端会记 WARN 并降级）：race / hedge / shadow /
         * retry_backoff / circuit_breaker / quorum / escalate
         */
        val EXEC_OPS = listOf(
            "first" to "顺序降级（当前唯一已实现）",
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditScreen(
    draft: RuleDraft,
    isNew: Boolean,
    onDraftChange: (RuleDraft) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "添加规则" else "编辑规则") },
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
            // ---------- 基本 ----------
            CardGroup(title = { Text("基本") }) {
                textFieldItem(
                    label = "规则名",
                    value = draft.name,
                    onValueChange = { onDraftChange(draft.copy(id = it.ifBlank { "default" }, name = it)) },
                )
                switchItem(
                    checked = draft.enabled,
                    onCheckedChange = { onDraftChange(draft.copy(enabled = it)) },
                    headlineContent = { Text("启用") },
                )
            }

            // ---------- select ----------
            Spacer(Modifier.height(16.dp))
            CardGroup(title = { Text("① 选路（按勾选顺序执行）") }) {
                RuleDraft.SELECT_OPS.forEach { (key, label, desc) ->
                    val checked = draft.selectedOps.contains(key)
                    item(
                        onClick = {
                            val next = draft.selectedOps.toMutableList()
                            if (checked) next.remove(key) else next.add(key)
                            onDraftChange(draft.copy(selectedOps = next))
                        },
                        leadingContent = {
                            Checkbox(checked = checked, onCheckedChange = null)
                        },
                        headlineContent = { Text(label) },
                        supportingContent = { Text(desc) },
                    )
                }
            }

            // ---------- execute ----------
            Spacer(Modifier.height(16.dp))
            CardGroup(title = { Text("② 执行（只能选一个）") }) {
                RuleDraft.EXEC_OPS.forEach { (key, label) ->
                    val checked = draft.executeOp == key
                    item(
                        onClick = { onDraftChange(draft.copy(executeOp = key)) },
                        leadingContent = {
                            RadioButton(selected = checked, onClick = null)
                        },
                        headlineContent = { Text(key) },
                        supportingContent = { Text(label) },
                    )
                }
            }

            // ---------- 切换粒度 ----------
            Spacer(Modifier.height(16.dp))
            CardGroup(title = { Text("③ 模型切换粒度") }) {
                item(
                    onClick = { onDraftChange(draft.copy(switchGranularity = "SESSION")) },
                    leadingContent = {
                        RadioButton(selected = draft.switchGranularity == "SESSION", onClick = null)
                    },
                    headlineContent = { Text("对话级 · SESSION") },
                    supportingContent = {
                        Text("整个会话固定同一家。缓存命中最高，但单号排队会拖死会话")
                    },
                )
                item(
                    onClick = { onDraftChange(draft.copy(switchGranularity = "TURN")) },
                    leadingContent = {
                        RadioButton(selected = draft.switchGranularity == "TURN", onClick = null)
                    },
                    headlineContent = { Text("轮次级 · TURN（推荐）") },
                    supportingContent = {
                        Text("同一轮内（含工具调用往返）固定，下一轮才换。避开 tool_call 格式不兼容")
                    },
                )
                item(
                    onClick = { onDraftChange(draft.copy(switchGranularity = "CALL")) },
                    leadingContent = {
                        RadioButton(selected = draft.switchGranularity == "CALL", onClick = null)
                    },
                    headlineContent = { Text("调用级 · CALL（有风险）") },
                    supportingContent = {
                        Text("每次请求都可能换。负载最分散，但多轮工具调用可能因 tool_call_id 不兼容而失败")
                    },
                )
            }

            // ---------- 限流 ----------
            Spacer(Modifier.height(16.dp))
            CardGroup(title = { Text("④ 限流（只填充未配置的提供商）") }) {
                textFieldItem(
                    label = "每分钟请求数 ratePerMin",
                    value = draft.ratePerMin,
                    onValueChange = { onDraftChange(draft.copy(ratePerMin = it.filterDigits())) },
                    supportingText = { Text("0 表示不限速") },
                )
                textFieldItem(
                    label = "突发上限 burst",
                    value = draft.burst,
                    onValueChange = { onDraftChange(draft.copy(burst = it.filterDigits())) },
                )
                textFieldItem(
                    label = "最小间隔 ms minIntervalMs",
                    value = draft.minIntervalMs,
                    onValueChange = { onDraftChange(draft.copy(minIntervalMs = it.filterDigits())) },
                    supportingText = { Text("防风控关键：两次发送至少间隔多久") },
                )
                textFieldItem(
                    label = "抖动 ms jitterMs",
                    value = draft.jitterMs,
                    onValueChange = { onDraftChange(draft.copy(jitterMs = it.filterDigits())) },
                )
                textFieldItem(
                    label = "并发上限 maxInflight",
                    value = draft.maxInflight,
                    onValueChange = { onDraftChange(draft.copy(maxInflight = it.filterDigits())) },
                )
            }

            // ---------- 操作 ----------
            Spacer(Modifier.height(24.dp))
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("保存规则")
            }
            if (onDelete != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("删除此规则", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
