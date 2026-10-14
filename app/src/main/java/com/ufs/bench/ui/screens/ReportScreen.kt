package com.ufs.bench.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import com.ufs.bench.ui.theme.ContentBottomInset
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ufs.bench.hardware.HardwareStorageInfo
import com.ufs.bench.model.BenchmarkMode
import com.ufs.bench.model.builtinMetricSpec
import com.ufs.bench.model.BenchmarkSummary
import com.ufs.bench.ui.components.RootModeBadge
import com.ufs.bench.ui.components.isRootRun
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ReportScreen(
    history: List<BenchmarkSummary>,
    hardwareInfo: HardwareStorageInfo,
    onClearHistory: () -> Unit,
    onShare: () -> Unit = {}
) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // 选中的记录 → 打开详情页；此前历史卡片点不开，只能看摘要
    var selected by remember { mutableStateOf<BenchmarkSummary?>(null) }

    selected?.let { item ->
        ReportDetailScreen(
            item = item,
            hw = hardwareInfo,
            onBack = { selected = null },
            onShare = onShare
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "基准测试历史报告（${history.size}）",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (history.isNotEmpty()) {
                Row {
                    // 分享的是最新一条记录：它是 内置微基准时不提供分享入口
                    // （SPEC 比值是本项目自标定基线，非 spec.org 官方成绩）
                    if (!history.first().spec.portedVersion) {
                        IconButton(onClick = onShare) {
                            Icon(Icons.Default.Share, contentDescription = "分享报告", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = onClearHistory) {
                        Icon(Icons.Default.Delete, contentDescription = "清空历史", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (history.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "暂无测试记录，请在首页启动测试", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                // 列表底部的最后一张卡也要能完整滚出来（与可滚动页共享同一留白）
                contentPadding = PaddingValues(bottom = ContentBottomInset)
            ) {
                items(history) { item ->
                    // 两套模板：SPEC 是纯 CPU+内存负载，吞吐/IOPS/延迟这些存储字段对它没有意义
                    if (item.spec.portedVersion) {
                        SpecHistoryCard(item = item, dateFormat = dateFormat) { selected = item }
                        return@items
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = item },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // 模式徽标：容器色与前景色成对取自语义角色
                                    val badgeContainer = when (item.spec.mode) {
                                        BenchmarkMode.STANDARD_READ_WRITE -> MaterialTheme.colorScheme.primaryContainer
                                        BenchmarkMode.MIXED_SIMULTANEOUS -> MaterialTheme.colorScheme.secondaryContainer
                                        BenchmarkMode.BUILTIN_KERNEL -> MaterialTheme.colorScheme.tertiaryContainer
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                    val badgeContent = when (item.spec.mode) {
                                        BenchmarkMode.STANDARD_READ_WRITE -> MaterialTheme.colorScheme.onPrimaryContainer
                                        BenchmarkMode.MIXED_SIMULTANEOUS -> MaterialTheme.colorScheme.onSecondaryContainer
                                        BenchmarkMode.BUILTIN_KERNEL -> MaterialTheme.colorScheme.onTertiaryContainer
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Surface(
                                        color = badgeContainer,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = when (item.spec.mode) {
                                                BenchmarkMode.STANDARD_READ_WRITE -> "标准读写"
                                                BenchmarkMode.MIXED_SIMULTANEOUS -> "独立混合"
                                                BenchmarkMode.BUILTIN_KERNEL -> "SPEC移植"
                                                BenchmarkMode.READ_ONLY -> "单项读"
                                                BenchmarkMode.WRITE_ONLY -> "单项写"
                                            },
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            color = badgeContent,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = item.spec.name,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                Text(
                                    text = dateFormat.format(Date(item.timestamp)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "引擎：${item.engineName} | 规格：${item.spec.blockSizeLabel} ${item.spec.concurrencyLabel} ${item.spec.patternLabel}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isRootRun(item.spec.privilege.professional, item.privilegeMethod)) {
                                    RootModeBadge()
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            if (item.ioMethod.isNotEmpty() || item.privilegeMethod.isNotEmpty() ||
                                item.verifyDetail.isNotEmpty()
                            ) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        if (item.ioMethod.isNotEmpty()) {
                                            Text(
                                                text = "I/O 通道：" + item.ioMethod,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (item.ioMethod.startsWith("O_DIRECT")) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                                            )
                                        }
                                        if (item.verifyDetail.isNotEmpty()) {
                                            Text(
                                                text = item.verifyDetail,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }



                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text("读取吞吐量", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${String.format("%.1f", item.readMbps)} MB/s", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Text("IOPS: ${item.readIops}", fontSize = 12.sp)
                                    if (item.readAvgLatencyUs > 0) {
                                        Text("延迟：${String.format("%.1f", item.readAvgLatencyUs)} μs", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("写入吞吐量", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${String.format("%.1f", item.writeMbps)} MB/s", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                    Text("IOPS: ${item.writeIops}", fontSize = 12.sp)
                                    if (item.writeAvgLatencyUs > 0) {
                                        Text("延迟：${String.format("%.1f", item.writeAvgLatencyUs)} μs", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 内置微基准历史卡片：纯 CPU + 内存负载，**不展示任何存储字段**
 * （吞吐 / IOPS / 延迟 / 分位 / 抖动 / 掉速与 SPEC 无关，强套只会得到一堆 0）。
 */
@Composable
private fun SpecHistoryCard(
    item: BenchmarkSummary,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "SPEC移植",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.spec.name,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    text = dateFormat.format(Date(item.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "引擎：${item.engineName} · 官方 ${item.spec.officialTierName} 档",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "相对自标定基线比值（非 官方参考值）",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = "自标定基线 ${String.format("%.1f", item.referenceSeconds)}s · 工作量 ${item.workUnits}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (item.verified) "输出正确性校验：通过" else "输出正确性校验：未通过",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (item.verified) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.error
                            )
                        }
                        Text(
                            text = if (item.spec.tier.showsRatio) {
                                String.format("%.3f", item.specRatio)
                            } else if (item.verified) {
                                "自检通过"
                            } else {
                                "自检失败"
                            },
                            fontSize = if (item.spec.tier.showsRatio) 22.sp else 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (item.spec.tier.showsRatio) MaterialTheme.colorScheme.tertiary
                            else if (item.verified) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                    if (item.verifyDetail.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.verifyDetail,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    val bm = builtinMetricSpec(item.spec.builtinKernelId)
                    val metricLine = buildList {
                        bm.cpuLabel?.let {
                            add(it + " " + String.format("%.1f", item.cpuMetric * bm.cpuScale) + " " + bm.cpuUnit)
                        }
                        bm.memLabel?.let {
                            add(it + " " + String.format("%.2f", item.memMetric * bm.memScale) + " " + bm.memUnit)
                        }
                    }.joinToString(" · ")
                    if (metricLine.isNotEmpty()) {
                        Text(
                            text = metricLine,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}