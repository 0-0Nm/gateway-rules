package com.aqi.ccb.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 侧栏/顶部 Tab 导航 —— 移植 rikka 的 PagerTabs 范式。
 *
 * 原版用 HorizontalPager + 自定义 PagerTab 接口；这里用 Material3 的
 * ScrollableTabRow + Tab，语义等价、依赖更少。
 * 目的：把原来"一长条上下滚动"的聚合页拆成并列 tab。
 */
data class TabItem(val key: String, val label: String)

@Composable
fun PagerTabs(
    tabs: List<TabItem>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val idx = tabs.indexOfFirst { it.key == selected }.coerceAtLeast(0)
    Column(modifier = modifier.fillMaxWidth()) {
        ScrollableTabRow(
            selectedTabIndex = idx,
            edgePadding = 8.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            tabs.forEachIndexed { i, tab ->
                Tab(
                    selected = i == idx,
                    onClick = { onSelect(tab.key) },
                    text = {
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (i == idx) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    },
                )
            }
        }
    }
}
