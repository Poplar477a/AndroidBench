package com.ufs.bench.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 编译工具链的折叠状态：**全局共享**，保证「结果页」与「历史详情页」行为一致，
 * 且在页面切换 / 重新组合后仍记得上次的选择（进程内记忆；重启回默认收起）。
 */
object CompilerToolchainUi {
    /** 默认收起：400 字符的编译串平铺会占掉整屏 */
    var expanded: Boolean by mutableStateOf(false)
}

/**
 * 「编译工具链」独立分区。
 *
 * - 默认收起，摘要行形如「Clang 14.0.7 · -O3 · x86_64」，点击整块展开
 * - 展开后按 ` | ` 字段分组逐行显示，等宽字体，长行折行但**绝不省略**
 * - 「优化等级差异」与 SPEC 调优声明**始终可见**：它们比编译串本身更该被看到
 */
@Composable
fun CompilerToolchainSection(
    toolchain: String,
    modifier: Modifier = Modifier
) {
    val expanded = CompilerToolchainUi.expanded

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { CompilerToolchainUi.expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "编译工具链",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (expanded) "收起" else "展开",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起编译工具链" else "展开编译工具链",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 摘要行：收起/展开都在，展开时相当于「一眼看关键事实」
            Text(
                text = compilerSummary(toolchain),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                compilerGroups(toolchain).forEach { group ->
                    Text(
                        text = group,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            // ---- 以下两段不折叠：声明比编译串更需要被看到 ----
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "优化等级差异",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = SPEC_OPT_LEVEL_NOTE,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = SPEC_TUNING_DISCLAIMER,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 摘要行：从编译串里抽出「编译器版本 · 优化等级 · 目标 ABI」三个关键事实 */
internal fun compilerSummary(toolchain: String): String {
    if (toolchain.isBlank()) return "（原生未提供）"
    val clang = Regex("Clang ([0-9]+\\.[0-9]+\\.[0-9]+)").find(toolchain)?.groupValues?.get(1)
    val opt = Regex("优化等级 (-O[0-9a-zA-Z]+)").find(toolchain)?.groupValues?.get(1)
    val abi = Regex("目标 ABI ([A-Za-z0-9_]+)").find(toolchain)?.groupValues?.get(1)
    val parts = listOfNotNull(
        clang?.let { "Clang $it" },
        opt,
        abi
    )
    return if (parts.isEmpty()) "（点按展开完整编译串）" else parts.joinToString(" · ")
}

/** 完整串按 ` | ` 分组：每行一个字段，便于逐项阅读（编译参数那一项本身较长，允许折行） */
internal fun compilerGroups(toolchain: String): List<String> {
    if (toolchain.isBlank()) return listOf("（原生未提供）")
    return toolchain.split("|").map { it.trim() }.filter { it.isNotEmpty() }
}
