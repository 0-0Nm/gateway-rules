package com.aqi.ccb.ui

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
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    testResult: String?,
) {
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
                textFieldItem(
                    label = "模型 ID",
                    value = draft.modelId,
                    onValueChange = { onDraftChange(draft.copy(modelId = it.trim())) },
                    supportingText = { Text("可点下方「探测模型」自动获取，多个用英文逗号分隔") },
                    monospace = true,
                )
            }

            // ---------- 探测 ----------
            Spacer(Modifier.height(16.dp))
            CardGroup(title = { Text("模型探测") }) {
                formItem(headlineContent = { Text("从上游 /v1/models 拉取") }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { onTest?.invoke() },
                            enabled = draft.baseUrl.isValidHttpUrl() && draft.modelId.isNotBlank(),
                        ) { Text("连接测试") }
                        Text(
                            text = testResult ?: "未测试",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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

// ==================== 小工具 ====================

internal fun String.filterDigits(): String = filter { it.isDigit() }

internal fun String.isValidHttpUrl(): Boolean =
    startsWith("http://") || startsWith("https://")
