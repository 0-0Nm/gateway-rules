package com.aqi.ccb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 动态圆角气泡 —— 移植 Dynamic-Bubble-Kit 的 Android/Compose 实现。
 *
 * 视觉逻辑（原 skill 的黄金参数）：
 *   行数 ≤ 2  → 左上/右上/左下 = 基准角（默认 22dp），右下 = 8dp
 *   行数 > 2  → 圆角 = max(下限, 基准 - (行数-2) × 步长)，右下恒 8dp
 *
 * 右下角固定小圆角是这套设计的灵魂：它给出"说话方向"，
 * 长文本时其余三角收缩保持视觉平衡，不会变成臃肿的大圆角矩形。
 */
@Composable
fun DynamicBubble(
    text: String,
    style: UiStyle,
    modifier: Modifier = Modifier,
    bubbleColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    fontSize: Int = 14,
    lineHeight: Int = 22,
) {
    var lineCount by remember { mutableIntStateOf(1) }
    val r = style.radiusForLines(lineCount).dp

    val shape = RoundedCornerShape(
        topStart = r,
        topEnd = r,
        bottomEnd = style.bottomEndRadius.dp,
        bottomStart = r,
    )

    val bubbleAlpha = (style.cardAlpha.coerceIn(0, 100)) / 100f

    BasicText(
        text = text,
        modifier = modifier
            .width(IntrinsicSize.Max)
            .background(bubbleColor.copy(alpha = bubbleAlpha), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        style = TextStyle(
            color = textColor,
            fontSize = fontSize.sp,
            lineHeight = lineHeight.sp,
        ),
        onTextLayout = { layoutResult: TextLayoutResult ->
            if (layoutResult.lineCount != lineCount) {
                lineCount = layoutResult.lineCount
            }
        },
    )
}

/**
 * 动态圆角卡片 —— 同样的算法，用于日志/监控这类高度不定的容器。
 * 标题 + 内容两段，按总行数收缩圆角。
 */
@Composable
fun DynamicCard(
    title: String,
    content: String,
    style: UiStyle,
    modifier: Modifier = Modifier,
) {
    var lines by remember { mutableIntStateOf(1) }
    val r = style.radiusForLines(lines).dp
    val shape = RoundedCornerShape(
        topStart = r,
        topEnd = r,
        bottomEnd = style.bottomEndRadius.dp,
        bottomStart = r,
    )
    val alpha = style.cardAlpha.coerceIn(0, 100) / 100f

    Surface(
        modifier = modifier.width(IntrinsicSize.Max),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onTextLayout = { lr ->
                    val t = lr.lineCount + 1
                    if (t != lines) lines = t
                },
            )
        }
    }
}
