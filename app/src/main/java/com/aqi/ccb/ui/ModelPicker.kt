package com.aqi.ccb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aqi.ccb.data.ModelInfo
import com.aqi.ccb.data.ProviderDraft

/**
 * 模型选择器 —— 信息架构照搬 RikkaHub 的 ModelList。
 *
 * 从 smali + resources.arsc 实测得到的原始结构：
 *   ModelListState { modelId, providers, type, visible }
 *   model_list_search_placeholder = "Enter model name to search"
 *   model_list_favorite           = "Favorites"
 *   model_list_no_providers       = "No available AI providers, please add in settings"
 *
 * 即：顶部搜索框 → 收藏区 → 按 provider 分组 → 每行一个模型。
 * 这套结构解决「一次拉回 104 个模型怎么筛」的实际问题：
 * 搜索快速定位，分组看清来源，勾选决定去留。
 */
@Composable
internal fun ModelPicker(
    providers: List<ProviderDraft>,
    onToggleModel: (providerIdx: Int, modelId: String, enabled: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }

    // 搜索过滤：按模型 ID 子串匹配（瑞卡同款交互）
    val filtered = remember(query, providers) {
        if (query.isBlank()) providers
        else providers.map { p ->
            p.copy(models = p.models.filter { it.modelId.contains(query, ignoreCase = true) })
        }.filter { it.models.isNotEmpty() }
    }

    val totalModels = providers.sumOf { it.models.size }
    val enabledModels = providers.sumOf { p -> p.models.count { it.enabled } }

    Column(modifier = modifier.fillMaxWidth()) {
        // ---------- 搜索框 ----------
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = {
                Text("输入模型名搜索", style = MaterialTheme.typography.bodySmall)
            },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "清空")
                    }
                }
            },
            textStyle = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(4.dp))

        // ---------- 统计条 ----------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "已选 $enabledModels / 共 $totalModels",
                style = MaterialTheme.typography.labelSmall,
                color = if (enabledModels > 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "勾选后才会出现在 /v1/models",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(4.dp))

        // ---------- 空状态 ----------
        if (filtered.isEmpty()) {
            Text(
                if (query.isBlank()) "暂无可用模型，请先拉取模型列表"
                else "没有匹配「$query」的模型",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            return@Column
        }

        // ---------- 按 provider 分组 ----------
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            filtered.forEach { prov ->
                // ProviderDraft 没有 id 字段，标识由 name 派生（见 idOr）
                val pIdx = providers.indexOfFirst { it.idOr() == prov.idOr() }
                // 分组标题
                item(key = "hdr-${prov.idOr()}") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            prov.name.ifBlank { prov.idOr() },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "(${prov.models.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 该 provider 下的模型
                items(prov.models, key = { "m-${prov.idOr()}-${it.modelId}" }) { m ->
                    PickerRow(
                        model = m,
                        onToggle = { onToggleModel(pIdx, m.modelId, it) },
                    )
                }
            }
        }
    }
}

/** 单个模型行：勾选框 + 名称 + 能力标签 */
@Composable
private fun PickerRow(
    model: ModelInfo,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!model.enabled) }
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(checked = model.enabled, onCheckedChange = { onToggle(it) })
        Column(modifier = Modifier.weight(1f)) {
            Text(
                model.modelId,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                color = if (model.enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Tag(typeLabel(model.type))
                if (model.abilities.contains("TOOL")) Tag("工具")
                if (model.abilities.contains("REASONING")) Tag("推理")
                if (model.abilities.isEmpty()) Tag("未探测", dim = true)
                if (model.hasPrice) Tag("¥" + fmtPrice(model.blendedPrice))
            }
        }
    }
}

@Composable
private fun Tag(text: String, dim: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (dim) MaterialTheme.colorScheme.outline
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
