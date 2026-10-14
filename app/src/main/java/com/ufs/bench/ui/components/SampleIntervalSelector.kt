package com.ufs.bench.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ufs.bench.model.IometerAccessSpec

/**
 * 采样档位可选值（毫秒）：5 / 10 / 25 / 50 / 75 / 100。
 *
 * 唯一来源是 model 层的契约常量（docs/chart-data-contract.md §1），
 * 这里只是 UI 侧别名 —— 不要在此另立一份取值表，否则原生层与界面会各说各话。
 */
val SAMPLE_INTERVAL_OPTIONS_MS: List<Int> = IometerAccessSpec.SAMPLE_INTERVAL_OPTIONS_MS

/**
 * 全局采样档位选择器（5 / 10 / 25 / 50 / 75 / 100 ms）。
 *
 * 与专业模式、缓存参与同级，属于应用级设置，不随单次测试的用例切换而变。
 * 颜色全部取 MaterialTheme.colorScheme 语义角色，硬编码会让 Monet 壁纸取色失效。
 */
@Composable
fun SampleIntervalSelector(
    selectedMs: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "采样档位",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "专业模式性能曲线的采样间隔。间隔越短曲线越密、时间分辨率越高，" +
                    "但内存与调度开销同步上升；100 ms 为契约默认档。" +
                    "未开启专业模式时不产出曲线，仅进度回调按该间隔刷新。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            // 六档分两行三列：窄屏单行会溢出，两行可保持点击目标足够大
            val rows = SAMPLE_INTERVAL_OPTIONS_MS.chunked(3)
            rows.forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { ms ->
                        val isMsSel = ms == selectedMs
                        FilterChip(
                            selected = isMsSel,
                            onClick = { onSelect(ms) },
                            leadingIcon = if (isMsSel) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                            } else null,
                            label = {
                                Text(
                                    text = ms.toString() + " ms",
                                    modifier = Modifier.fillMaxWidth(),
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                if (rowIndex != rows.lastIndex) Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "当前：" + selectedMs + " ms",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
