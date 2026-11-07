package com.aqi.ccb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aqi.ccb.gateway.UsageSummary

/** Retained metadata, not a vendor bill. Theme reads stay in composable slots. */
@Composable
internal fun UsagePanel(summary: UsageSummary) {
    CardGroup(title = { Text("用量账本") }) {
        item(
            headlineContent = { Text("已记录 ${summary.requests} 个请求 · ${summary.attempts} 次上游尝试") },
            supportingContent = {
                Text("成功 ${summary.successful} · 失败 ${summary.failed} · 取消 ${summary.cancelled} · 本地拒绝 ${summary.localRejected}")
            },
        )
        formItem(headlineContent = { Text("已报告 Token 小计") }) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                UsageMetric("输入", summary.inputTokens.toString())
                UsageMetric("输出", summary.outputTokens.toString())
                UsageMetric("缓存输入（输入子集）", if (summary.cacheReportedAttempts > 0) summary.cachedTokens.toString() else "未报告")
                UsageMetric("推理输出（输出子集）", if (summary.reasoningReportedAttempts > 0) summary.reasoningTokens.toString() else "未报告")
            }
        }
        item(
            headlineContent = { Text("用量已报告 ${summary.reportedAttempts} · 用量未知 ${summary.unknownAttempts}") },
            supportingContent = {
                Text("未发送 ${summary.notSentAttempts} · 省略详情 ${summary.omittedAttempts}。未知、断流和省略项不按零费用计算。")
            },
        )
        item(
            headlineContent = { Text("Token费用估算 · 不自动换汇") },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (summary.estimatedCosts.isEmpty()) Text("暂无可估算记录；请为新请求配置币种与所需单价。")
                    summary.estimatedCosts.forEach { price -> Text("${price.currency} ${price.amount} · ${price.attempts} 次尝试") }
                    Text("已估算 ${summary.estimatedAttempts} · 未能估算 ${summary.unestimatedSentAttempts}；另有 ${summary.omittedAttempts} 次省略详情。")
                }
            },
        )
        item(
            headlineContent = { Text("预算扣限：尚未启用") },
            supportingContent = { Text("仅当前保留窗口的Token估算，缺失用量、币种或缓存单价不会记零；不含工具费、税费、阶梯价，不是供应商账单。") },
        )
        item(
            headlineContent = {
                Text(when (summary.storageStatus) { "ok" -> "账本存储正常"; "partial" -> "历史记录部分不可读"; else -> "账本存储异常" },
                    color = if (summary.storageStatus == "ok") MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
            },
            supportingContent = {
                Text("最多显示保留的 ${summary.retainedLimit} 条请求，磁盘最多两份2MiB日志，不是全部历史。未落盘 ${summary.unpersisted} · 不可读记录 ${summary.readErrors}。只存用量和路由元数据。")
            },
        )
        summary.recent.forEach { recent ->
            item(
                headlineContent = { Text("${recent.requestId} · ${usageOutcome(recent.outcome)}", style = MaterialTheme.typography.bodySmall) },
                supportingContent = { Text("上游尝试 ${recent.attempts} 次", style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

private fun usageOutcome(value: String): String = when (value) {
    "success" -> "成功"; "cancelled" -> "已取消"; "local_reject" -> "本地拒绝"; else -> "上游失败"
}

@Composable
private fun UsageMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
