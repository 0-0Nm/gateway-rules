package com.aqi.ccb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aqi.ccb.data.ProviderDraft

/**
 * 提供商配置页 —— 视觉与交互 1:1 沿用 rikka 的 ProviderConfigure。
 *
 * 沿用其范式：
 *  - CardGroup 分组（圆角 20dp / 项间距 2dp）
 *  - formItem：标题在上、输入框在下
 *  - secret 字段右侧眼睛按钮切换明文
 *  - baseUrl 非默认值时出现「重置默认」按钮
 *  - 底部单独一组：测试连接 / 删除 / 保存
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditScreen(
    draft: ProviderDraft,
    isNew: Boolean,
    onDraftChange: (ProviderDraft) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
    onTest: (() -> Unit)?,
    onProbeModels: (() -> Unit)? = null,
    onTestAll: (() -> Unit)? = null,
    onToggleModel: ((String) -> Unit)? = null,
    /** 整条回传更新（价格等字段用） */
    onUpdateModel: ((com.aqi.ccb.data.ModelInfo) -> Unit)? = null,
    onSuffixChange: ((String) -> Unit)? = null,
    testResult: String?,
    busy: Boolean = false,
    onCancelTest: (() -> Unit)? = null,
) {
    var pendingTest by remember { mutableStateOf<String?>(null) }
    pendingTest?.let { kind ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingTest = null },
            title = { Text("确认上游测试？") },
            text = { Text(if(kind=="batch") "仅测试已勾选模型，最多20个；每个模型可能触发目录查询和生成能力请求，可能计费。取消不保证上游已发请求不计费。" else "测试第一个已勾选模型，包含非流式、SSE和工具调用，最多3次生成请求，可能计费。不会因成功自动启用模型。") },
            confirmButton = { TextButton(onClick={pendingTest=null;if(kind=="batch")onTestAll?.invoke() else onTest?.invoke()}) { Text("确认测试") } },
            dismissButton = { TextButton(onClick={pendingTest=null}) { Text("取消") } },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "添加提供商" else "编辑提供商") },
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
            // ---------- 基本信息 ----------
            CardGroup(title = { Text("基本信息") }) {
                textFieldItem(
                    label = "名称",
                    value = draft.name,
                    onValueChange = { onDraftChange(draft.copy(name = it)) },
                )
                textFieldItem(
                    label = "Base URL",
                    value = draft.baseUrl,
                    onValueChange = { onDraftChange(draft.copy(baseUrl = it.trim())) },
                    supportingText = {
                        if (!draft.baseUrl.isValidHttpUrl()) {
                            Text("需以 http:// 或 https:// 开头", color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("例如 https://integrate.api.nvidia.com/v1")
                        }
                    },
                    monospace = true,
                )
                textFieldItem(
                    label = "API Key",
                    value = draft.apiKey,
                    onValueChange = { onDraftChange(draft.copy(apiKey = it.trim())) },
                    secret = true,
                    maxLines = 3,
                )
                formItem(
                    headlineContent = { Text("模型（${draft.models.size} 个）") },
                    supportingContent = {
                        Text(
                            "搜索定位 + 勾选决定去留。未勾选的不会出现在 /v1/models，" +
                                "也不参与选路。"
                        )
                    },
                ) {
                    if (draft.models.isEmpty()) {
                        Text(
                            "暂无模型，点下方「探测模型」自动获取",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // 模型选择器：结构照搬 RikkaHub 的 ModelList
                        // （搜索框 + 按 provider 分组 + 勾选）
                        ModelPicker(
                            providers = listOf(draft),
                            onToggleModel = { _, modelId, enabled ->
                                val cur = draft.models.toMutableList()
                                val i = cur.indexOfFirst { it.modelId == modelId }
                                if (i >= 0) {
                                    cur[i] = cur[i].copy(enabled = enabled)
                                    onDraftChange(draft.copy(models = cur))
                                }
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "展开单个模型可编辑类型/能力/内置工具",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        // 详细的逐模型编辑（三 tab，照搬 ModelFormTab）
                        draft.models.filter { it.enabled }.forEach { m ->
                            ModelRow(
                                model = m,
                                onToggle = { onToggleModel?.invoke(m.modelId) },
                                onUpdate = onUpdateModel,
                            )
                        }
                    }
                }
            }

            // ---------- 端点类型（后缀自动探测）----------
            Spacer(Modifier.height(16.dp))
            CardGroup(title = { Text("端点类型") }) {
                formItem(
                    headlineContent = { Text("当前：${draft.suffixStyle}") },
                    supportingContent = {
                        Text(
                            if (draft.suffixStyle == "AUTO")
                                "自动：依次尝试 /v1 · /v1beta · /openai · /compatible-mode/v1"
                            else "手动指定，不做自动探测"
                        )
                    },
                ) {
                    var expanded by remember { mutableStateOf(false) }
                    Column {
                        OutlinedButton(onClick = { expanded = true }) {
                            Text("选择类型")
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                        ) {
                            listOf(
                                "AUTO" to "自动探测（推荐）",
                                "OPENAI" to "OpenAI 兼容 /v1",
                                "GOOGLE" to "Google /v1beta",
                                "OPENAI_COMPAT" to "中转 /openai",
                                "ALIYUN" to "阿里云 /compatible-mode/v1",
                                "INPUT" to "原样（不追加后缀）",
                            ).forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text("$label  ·  $key") },
                                    onClick = {
                                        onSuffixChange?.invoke(key)
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // ---------- 探测 ----------
            Spacer(Modifier.height(16.dp))
            CardGroup(title = { Text("探测与测试") }) {
                formItem(headlineContent = { Text("① 探测模型（拉 /models 列表）") }) {
                    Column {
                        OutlinedButton(
                            onClick = { onProbeModels?.invoke() },
                            enabled = !busy && draft.baseUrl.isValidHttpUrl(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("探测模型") }
                        if (draft.models.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "已发现 " + draft.models.size + " 个模型",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                formItem(
                    headlineContent = {
                        Text("② 批量测试（逐模型测延迟+可用性）")
                    },
                    supportingContent = {
                        if (draft.testingProgress.isNotEmpty())
                            Text("测试中 ${draft.testingProgress}")
                        else
                            Text("结果会显示在下方每个模型右侧")
                    },
                ) {
                    OutlinedButton(
                        onClick = { pendingTest = "batch" },
                        enabled = !busy && draft.baseUrl.isValidHttpUrl() && draft.models.any{it.enabled},
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("测试选中模型（最多20个）") }
                }

                formItem(headlineContent = { Text("③ 连接测试（非流式/流式/工具）") }) {
                    Column {
                        OutlinedButton(
                            onClick = { pendingTest = "single" },
                            enabled = !busy && draft.baseUrl.isValidHttpUrl() && draft.models.any{it.enabled},
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("连接测试") }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = testResult ?: "未测试",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (busy) {
                OutlinedButton(onClick={onCancelTest?.invoke()},modifier=Modifier.fillMaxWidth()) { Text("取消当前测试/发现任务") }
                Text("已发送的请求可能仍在上游处理；取消会阻止后续阶段与旧结果回填",style=MaterialTheme.typography.bodySmall)
            }

            // ---------- 限流（本项目的关键差异化，放在 provider 级） ----------
            Spacer(Modifier.height(16.dp))
            CardGroup(title = { Text("限流（防风控）") }) {
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
                    supportingText = { Text("防风控关键项：两次发送至少间隔多久") },
                )
                textFieldItem(
                    label = "抖动 ms jitterMs",
                    value = draft.jitterMs,
                    onValueChange = { onDraftChange(draft.copy(jitterMs = it.filterDigits())) },
                    supportingText = { Text("随机抖动，避免固定节奏被判定为机器人") },
                )
                textFieldItem(
                    label = "并发上限 maxInflight",
                    value = draft.maxInflight,
                    onValueChange = { onDraftChange(draft.copy(maxInflight = it.filterDigits())) },
                )
                // 权重：只有 weighted_rr 算子会用到，但放在这里让用户能看见
                textFieldItem(
                    label = "权重 weight（仅加权轮换用）",
                    value = draft.weight,
                    onValueChange = { onDraftChange(draft.copy(weight = it.filterDigits())) },
                    supportingText = {
                        Text("规则里选了「加权轮换」时生效；权重越大被选中概率越高，默认 1")
                    },
                )
                // 余额/配额：本系统无法自动查询上游余额，只能手填。
                // 填了之后 quota_filter 才有意义；不填则该算子恒定通过。
                textFieldItem(
                    label = "余额/配额 quota（可留空）",
                    value = draft.quota,
                    onValueChange = { onDraftChange(draft.copy(quota = it.filterDigits())) },
                    supportingText = {
                        Text(
                            "本系统不查询上游真实余额，此值仅供 quota_filter 使用。" +
                                "留空则 quota_filter 不生效（会记录警告提醒你）"
                        )
                    },
                )
                switchItem(
                    checked = draft.enabled,
                    onCheckedChange = { onDraftChange(draft.copy(enabled = it)) },
                    headlineContent = { Text("启用") },
                )
            }

            // ---------- 操作 ----------
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("保存") }

            if (onDelete != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("删除此提供商", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ==================== 复用 rikka 的 formItem 范式 ====================

internal fun CardGroupScope.textFieldItem(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
    isError: Boolean = false,
    secret: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    monospace: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
) = formItem(headlineContent = { Text(label) }) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        isError = isError,
        singleLine = maxLines == 1,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = if (monospace) {
            MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont)
        } else LocalTextStyle.current,
        supportingText = supportingText,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        visualTransformation = if (secret && !visible) PasswordVisualTransformation()
        else VisualTransformation.None,
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (visible) "隐藏" else "显示",
                    )
                }
            }
        } else null,
    )
}

// ==================== 模型行（逐模型状态）====================

/**
 * 单个模型行：名称 + 状态徽标（未测 / 1200ms / 失败原因） + 启停开关。
 * 对应需求「直接把延迟和可用性显示在单个模型块底下」。
 */
@Composable
/**
 * 模型编辑行 —— 信息架构照搬 RikkaHub 的 Model 编辑页。
 *
 * RikkaHub 的 ModelFormTab 分三个 tab（从 smali + 资源实测）：
 *   BASIC            "Basic Settings"     基础：ID/名称/类型/上下文
 *   ADVANCED         "Advanced Settings"  高级：能力位/模态/自定义头体
 *   BUILT_IN_TOOLS   "Built-in Tools"     内置工具：Search/UrlContext/ImageGeneration
 *
 * 折叠态只显示一行摘要（模型 ID + 状态 + 能力），点开才展开三 tab。
 * 这样一屏能放下几十个模型，同时保留了完整的编辑能力。
 */
internal fun ModelRow(
    model: com.aqi.ccb.data.ModelInfo,
    onToggle: () -> Unit,
    onUpdate: ((com.aqi.ccb.data.ModelInfo) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // ==================== 折叠摘要行 ====================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.modelId,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                    color = if (model.enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = buildString {
                        append(typeLabel(model.type))
                        if (model.abilities.isNotEmpty()) {
                            append(" · ").append(model.abilities.joinToString(","))
                        } else append(" · 未探测")
                        if (model.contextLength > 0) {
                            append(" · ").append(model.contextLength / 1000).append("K")
                        }
                        if (model.hasPrice) {
                            append(" · ").append(model.priceCurrency ?: "币种未设").append(" ").append(fmtPrice(model.blendedPrice))
                        }
                        if (model.lastError.isNotEmpty()) {
                            append(" · ").append(model.lastError.take(20))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        model.available == true -> Color(0xFF2E7D32)
                        model.available == false -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                if (expanded) "收起" else "编辑",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Checkbox(checked = model.enabled, onCheckedChange = { onToggle() })
        }

        if (!expanded || onUpdate == null) return@Column

        // ==================== 三个 tab ====================
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("基础", "高级", "内置工具").forEachIndexed { idx, label ->
                val sel = tab == idx
                Text(
                    label,
                    modifier = Modifier
                        .clickable { tab = idx }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (sel) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp)) {
            when (tab) {
                // ---------- BASIC ----------
                0 -> {
                    smallField(
                        label = "显示名",
                        value = model.displayName,
                        onChange = { onUpdate(model.copy(displayName = it)) },
                    )
                    chipRow(
                        label = "类型",
                        options = listOf("CHAT" to "对话", "IMAGE" to "图像", "EMBEDDING" to "向量"),
                        selected = model.type,
                        onPick = { onUpdate(model.copy(type = it)) },
                    )
                    smallField(
                        label = "上下文长度（0=未知）",
                        value = if (model.contextLength > 0) model.contextLength.toString() else "",
                        numeric = true,
                        onChange = {
                            onUpdate(model.copy(contextLength = it.toIntOrNull() ?: 0))
                        },
                    )
                    chipRow(
                        label = "计价币种",
                        options = listOf("" to "未设置", "USD" to "USD", "CNY" to "CNY"),
                        selected = model.priceCurrency.orEmpty(),
                        onPick = { onUpdate(model.copy(priceCurrency = it.ifEmpty { null })) },
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        priceField(
                            identity = model.modelId + ":input",
                            label = "普通输入单价 / 百万Token",
                            value = model.priceInPerM,
                            onChange = { onUpdate(model.copy(priceInPerM = it)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        priceField(
                            identity = model.modelId + ":cached",
                            label = "缓存输入单价 / 百万Token",
                            value = model.priceCachedInPerM,
                            onChange = { onUpdate(model.copy(priceCachedInPerM = it)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        priceField(
                            identity = model.modelId + ":output",
                            label = "输出单价 / 百万Token",
                            value = model.priceOutPerM,
                            onChange = { onUpdate(model.copy(priceOutPerM = it)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("仅估算Token费用，不含税费、工具费或阶梯价。币种和缓存口径不明则不估算；修改只影响新请求，不重算旧账本。",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // ---------- ADVANCED ----------
                1 -> {
                    // 能力位：TOOL / REASONING。与 RikkaHub 的 ModelAbility 完全一致。
                    // 探测得到的 toolCall 会写进 abilities，这里也能手动纠正。
                    chipRowMulti(
                        label = "能力",
                        options = listOf("TOOL" to "工具", "REASONING" to "推理"),
                        selected = model.abilities,
                        onToggle = { key ->
                            val next = model.abilities.toMutableList()
                            if (next.contains(key)) next.remove(key) else next.add(key)
                            onUpdate(model.copy(abilities = next))
                        },
                    )
                    chipRowMulti(
                        label = "输入模态",
                        options = listOf("TEXT" to "文本", "IMAGE" to "图像"),
                        selected = model.inputModalities,
                        onToggle = { key ->
                            val next = model.inputModalities.toMutableList()
                            if (next.contains(key)) next.remove(key) else next.add(key)
                            onUpdate(model.copy(inputModalities = next))
                        },
                    )
                    chipRowMulti(
                        label = "输出模态",
                        options = listOf("TEXT" to "文本", "IMAGE" to "图像"),
                        selected = model.outputModalities,
                        onToggle = { key ->
                            val next = model.outputModalities.toMutableList()
                            if (next.contains(key)) next.remove(key) else next.add(key)
                            onUpdate(model.copy(outputModalities = next))
                        },
                    )
                    Text(
                        "探测结果：" + when {
                            model.toolCall == true -> "工具 ✓"
                            model.toolCall == false -> "工具 ✗"
                            else -> "未探测"
                        } + "（点「探测」按钮实测，结果自动写入能力位）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                // ---------- BUILT_IN_TOOLS ----------
                else -> {
                    chipRowMulti(
                        label = "内置工具",
                        options = listOf(
                            "SEARCH" to "联网搜索",
                            "URL_CONTEXT" to "URL 读取",
                            "IMAGE_GENERATION" to "图像生成",
                        ),
                        selected = model.tools,
                        onToggle = { key ->
                            val next = model.tools.toMutableList()
                            if (next.contains(key)) next.remove(key) else next.add(key)
                            onUpdate(model.copy(tools = next))
                        },
                    )
                    Text(
                        "内置工具由上游服务商执行，网关只做透传声明。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** Price fields keep accepted decimal text while the model retains the numeric value. */
@Composable
internal fun priceField(identity: String, label: String, value: Double?, onChange: (Double?) -> Unit,
                        modifier: Modifier = Modifier.width(140.dp)) {
    var text by remember(identity) { mutableStateOf(com.aqi.ccb.gateway.ModelPricing.format(value)) }
    var lastEmitted by remember(identity) { mutableStateOf(value) }
    var rejectedInput by remember(identity) { mutableStateOf(false) }
    LaunchedEffect(identity, value) {
        // Refresh external updates without normalizing our own "0." edit back to "0".
        if (value != lastEmitted) {
            text = com.aqi.ccb.gateway.ModelPricing.format(value)
            lastEmitted = value
            rejectedInput = false
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { proposed ->
            runCatching { com.aqi.ccb.gateway.ModelPricing.editorText(proposed) }
                .onSuccess { accepted ->
                    text = accepted
                    rejectedInput = false
                    val number = com.aqi.ccb.gateway.ModelPricing.parseInput(accepted)
                    lastEmitted = number
                    onChange(number)
                }.onFailure { rejectedInput = true }
        },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        supportingText = { Text(if (rejectedInput) "输入未采用，保留原值" else "空=未知；0=零价") },
        isError = rejectedInput,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
        singleLine = true,
        textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFont),
    )
}

/** 单行小输入框，三个 tab 共用 */
@Composable
internal fun smallField(
    label: String,
    value: String,
    numeric: Boolean = false,
    width: Int = 0,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { txt ->
            onChange(if (numeric) txt.filter { it.isDigit() || it == '.' } else txt)
        },
        modifier = if (width > 0) Modifier.width(width.dp) else Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFont),
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
    )
}

/** 单选 chip 行 */
@Composable
internal fun chipRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        options.forEach { (key, name) ->
            val sel = selected == key
            AssistChip(
                onClick = { onPick(key) },
                label = {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sel) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}

/** 多选 chip 行 */
@Composable
internal fun chipRowMulti(
    label: String,
    options: List<Pair<String, String>>,
    selected: List<String>,
    onToggle: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        options.forEach { (key, name) ->
            val sel = selected.contains(key)
            AssistChip(
                onClick = { onToggle(key) },
                label = {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sel) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}

/** 类型代码转中文 */
internal fun typeLabel(t: String): String = when (t.uppercase()) {
    "IMAGE" -> "图像"
    "EMBEDDING" -> "向量"
    else -> "对话"
}


/** 价格显示：去掉多余小数位 */
internal fun fmtPrice(v: Double): String =
    com.aqi.ccb.gateway.ModelPricing.format(v)

// ==================== 小工具 ====================

internal fun String.filterDigits(): String = filter { it.isDigit() }

internal fun String.isValidHttpUrl(): Boolean =
    startsWith("http://") || startsWith("https://")
