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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.aqi.ccb.data.VirtualModelDraft

/** providerId -> (providerName, modelIds) */
typealias ProviderModels = Map<String, Pair<String, List<String>>>

/**
 * 虚拟模型编辑页 —— 核心能力。
 *
 * 三步：
 *  ① 命名（客户端看到的 model 名）
 *  ② 从已有 provider 里勾选成员 (provider × model)，顺序即优先级
 *  ③ 绑定规则（决定成员之间怎么并发/轮换/降级）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirtualModelEditScreen(
    draft: VirtualModelDraft,
    isNew: Boolean,
    providerModels: ProviderModels,
    ruleIds: List<String>,
    onDraftChange: (VirtualModelDraft) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "新建虚拟模型" else "编辑虚拟模型") },
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
            // ---------- ① 命名 ----------
            CardGroup(title = { Text("① 模型命名（客户端看到的名称）") }) {
                textFieldItem(
                    label = "名称",
                    value = draft.name,
                    onValueChange = { onDraftChange(draft.copy(name = it)) },
                    supportingText = {
                        Text("客户端请求 {\"model\": \"这个名字\"} 时网关会路由到下列成员")
                    },
                )
                textFieldItem(
                    label = "描述（可选）",
                    value = draft.description,
                    onValueChange = { onDraftChange(draft.copy(description = it)) },
                )
                switchItem(
                    checked = draft.enabled,
                    onCheckedChange = { onDraftChange(draft.copy(enabled = it)) },
                    headlineContent = { Text("启用") },
                )
            }

            // ---------- ② 成员 ----------
            Spacer(Modifier.height(16.dp))
            CardGroup(title = { Text("② 成员（勾选顺序 = 优先级）") }) {
                if (providerModels.isEmpty()) {
                    item(
                        headlineContent = { Text("暂无可用模型") },
                        supportingContent = { Text("先在「提供商」页导入并探测模型") },
                    )
                } else {
                    providerModels.forEach { (pid, pair) ->
                        val pName = pair.first
                        val modelIds = pair.second
                        // provider 分组标题
                        item(
                            headlineContent = {
                                Text(pName, style = MaterialTheme.typography.titleSmall)
                            },
                            supportingContent = { Text("${modelIds.size} 个模型") },
                        )
                        modelIds.forEach { mid ->
                            val key = "$pid|$mid"
                            val checked = draft.members.contains(key)
                            item(
                                onClick = {
                                    val next = draft.members.toMutableList()
                                    if (checked) next.remove(key) else next.add(key)
                                    onDraftChange(draft.copy(members = next))
                                },
                                leadingContent = {
                                    Checkbox(checked = checked, onCheckedChange = null)
                                },
                                headlineContent = { Text(mid) },
                                supportingContent = {
                                    val idx = draft.members.indexOf(key)
                                    Text(if (idx >= 0) "优先级 ${idx + 1}" else pid)
                                },
                            )
                        }
                    }
                }
            }

            // ---------- 已选成员（可按顺序预览/移除） ----------
            if (draft.members.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                CardGroup(title = { Text("已选成员（${draft.members.size}）") }) {
                    formItem {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            draft.members.forEachIndexed { idx, key ->
                                val parts = key.split('|')
                                val label = if (parts.size >= 2) {
                                    val pn = providerModels[parts[0]]?.first ?: parts[0]
                                    "${idx + 1}. $pn / ${parts[1]}"
                                } else key
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = {
                                            val next = draft.members.toMutableList()
                                            next.removeAt(idx)
                                            onDraftChange(draft.copy(members = next))
                                        },
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "移除",
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---------- ③ 绑定规则 ----------
            Spacer(Modifier.height(16.dp))
            CardGroup(title = { Text("③ 绑定规则") }) {
                formItem(headlineContent = { Text("规则决定成员之间如何调度") }) {
                    var expanded by remember { mutableStateOf(false) }
                    Column {
                        OutlinedButton(onClick = { expanded = true }) {
                            Text("当前：${draft.ruleId}")
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            (ruleIds.ifEmpty { listOf("default") }).forEach { rid ->
                                DropdownMenuItem(
                                    text = { Text(rid) },
                                    onClick = {
                                        onDraftChange(draft.copy(ruleId = rid))
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // ---------- 操作 ----------
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = draft.isComplete,
            ) {
                Text(if (draft.isComplete) "保存虚拟模型" else "需填名称并至少勾选一个成员")
            }
            if (onDelete != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("删除此虚拟模型", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
