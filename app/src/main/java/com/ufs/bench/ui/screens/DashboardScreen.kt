package com.ufs.bench.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.ufs.bench.ui.theme.ContentBottomInset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ufs.bench.model.BenchmarkMode
import com.ufs.bench.model.IometerAccessSpec
import com.ufs.bench.model.PresetSuites
import com.ufs.bench.model.SpecTier
import com.ufs.bench.ui.BenchmarkState
import com.ufs.bench.ui.UiState

/*
 * SPEC 套件类型与档位名一律取自 model 层单一口径（SpecSuiteKind / officialTierName）：
 * 后缀 `_s` = 单任务口径（参考档 参考档），`_r` = 多任务口径（参考档 参考档）；
 * 故 821.gcc_s / 854.graph500_s → 参考档，766.femflow_r → 参考档。
 * 本项目无法获得 任何官方参考时间，所有比值均为「相对自标定基线」，
 * 不是 任何官方成绩，也不可与 spec.org 公布分数比较。
 */

@Composable
fun DashboardScreen(
    state: UiState,
    onStartTest: () -> Unit,
    onStopTest: () -> Unit,
    onSelectPreset: (IometerAccessSpec) -> Unit,
    onSelectEngine: (String) -> Unit,
    onSelectTier: (Int) -> Unit = {}
) {
    val scrollState = rememberScrollState()

    // 状态徽标配色全部走语义角色，保证 Monet 动态取色生效。
    val statusContainerColor = when (state.benchmarkState) {
        BenchmarkState.RUNNING -> MaterialTheme.colorScheme.primary
        BenchmarkState.PREPARING -> MaterialTheme.colorScheme.tertiary
        BenchmarkState.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val statusContentColor = when (state.benchmarkState) {
        BenchmarkState.RUNNING -> MaterialTheme.colorScheme.onPrimary
        BenchmarkState.PREPARING -> MaterialTheme.colorScheme.onTertiary
        BenchmarkState.ERROR -> MaterialTheme.colorScheme.onError
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = ContentBottomInset),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. 硬件与介质信息卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // 硬件详情仅在专业模式（已获 root）下展示确切信息；
                // 常规模式不展示存储设备，避免给出未经确证的硬件结论。
                val professional = state.currentSpec.privilege.professional && state.hardwareInfo.hasRootAccess
                if (professional) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "存储型号：${state.hardwareInfo.storageVendor} ${state.hardwareInfo.storageType}".trim(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "机型：" + state.hardwareInfo.deviceName.ifEmpty { state.hardwareInfo.deviceCode } +
                            " | 型号代码：" + state.hardwareInfo.deviceCode +
                            " | 系统：" + state.hardwareInfo.androidRelease,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "调度算法：${state.hardwareInfo.ioScheduler} | 硬件队列深度上限：${state.hardwareInfo.queueDepthLimit}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp)) {
                            Text(text = "🔒", fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "常规模式不展示存储设备与硬件详情。切到「专业模式」（需 root）后可读取" +
                                    "确切的存储类型、I/O 调度算法与队列深度。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "执行引擎：", style = MaterialTheme.typography.bodySmall)
                    state.availableEngines.forEach { eng ->
                        val isEngSel = state.selectedEngine == eng
                        FilterChip(
                            selected = isEngSel,
                            onClick = { onSelectEngine(eng) },
                            label = { Text(if (eng.contains("Dynamo")) "Iometer Native" else "FIO") },
                            leadingIcon = if (isEngSel) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                            } else null,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
            }
        }

        // 2. 实时性能面板（首页核心读数）
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "当前用例：${state.currentSpec.name}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "模式：" + when (state.currentSpec.mode) {
                                BenchmarkMode.STANDARD_READ_WRITE -> "标准读写双向测试（CDM 模式）"
                                BenchmarkMode.MIXED_SIMULTANEOUS -> "独立读写混合压测（同时并发）"
                                BenchmarkMode.BUILTIN_KERNEL ->
                                    "内置基准（上游官方源码裁剪 · " +
                                        "强度 ${state.currentSpec.officialTierName} · " +
                                        "含正确性校验 · 纯 CPU+内存）"
                                BenchmarkMode.READ_ONLY -> "单项读测试"
                                BenchmarkMode.WRITE_ONLY -> "单项写测试"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        // ── 通道性质提示（需求：破除「换了 CPU 调优档闪存测速不变」的误解）──
                        if (state.currentSpec.mode != BenchmarkMode.BUILTIN_KERNEL) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "存储通道测试：速度由 UFS 颗粒与内核 I/O 路径决定，" +
                                    "CPU 编译器调优档对读数无直接影响（想观测编译器差异请跑 CoreMark / tinymembench）。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        // 测试数据集与地址范围：结果页 / 分享图 / 历史详情都已有，
                        // 首页此前缺失，导致开测前看不到"这次要压多大范围"。
                        // SPEC 是纯 CPU+内存负载，没有存储数据集概念，故不显示。
                        if (state.currentSpec.mode != BenchmarkMode.BUILTIN_KERNEL) {
                            val dsMb = state.currentSpec.fileSizeMb
                            val isRand = state.currentSpec.percentRandom >= 50
                            val walk = if (isRand) "随机" else "顺序"
                            val dsText = if (dsMb >= 1024)
                                String.format(java.util.Locale.US, "%.1f GB (%d MB)", dsMb / 1024.0, dsMb)
                            else "$dsMb MB"
                            val addrText = if (dsMb >= 1024)
                                String.format(
                                    java.util.Locale.US, "0 ~ %.1f GB (%s寻道)", dsMb / 1024.0, walk
                                )
                            else "0 ~ $dsMb MB (" + walk + "寻道)"
                            Text(
                                text = "测试数据集：$dsText ｜ 地址范围：$addrText",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Surface(
                        color = statusContainerColor,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = when (state.benchmarkState) {
                                BenchmarkState.RUNNING -> if (state.realtimeMetric.currentPhase.isNotEmpty()) state.realtimeMetric.currentPhase else "正在压测"
                                BenchmarkState.PREPARING -> "预分配空间"
                                BenchmarkState.COMPLETED -> "已完成"
                                BenchmarkState.ERROR -> "错误"
                                else -> "就绪"
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = statusContentColor,
                            fontSize = 12.sp
                        )
                    }
                }

                if (state.statusMessage.isNotEmpty() && state.statusMessage != "就绪") {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = state.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.benchmarkState == BenchmarkState.ERROR) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        }
                    )
                }

                // 首页不再展示任何成绩数字（需求：把首页成绩数字删了）。
                // 结果统一在「历史报告」中查看；首页只保留设备信息、当前用例与启动入口。
                val isSpecRun = state.currentSpec.mode == BenchmarkMode.BUILTIN_KERNEL

                Spacer(modifier = Modifier.height(12.dp))

                // 进度条
                if (state.benchmarkState == BenchmarkState.RUNNING) {
                    val progress = if (state.currentSpec.durationSec > 0) {
                        state.realtimeMetric.elapsedSeconds.toFloat() / state.currentSpec.durationSec
                    } else 0f
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "本阶段已进行 ${state.realtimeMetric.elapsedSeconds}s / 目标：${state.currentSpec.durationSec}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // 读 / 写 / 延迟：**存储测试口径**，SPEC 是纯 CPU+内存负载，
                // 套用只会得到一堆 0（读 0 MB/s、IOPS 0、延迟 0），故整块只在非 SPEC 时渲染。
                if (!isSpecRun) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("读取带宽（Throughput）", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Text(
                            "${String.format("%.1f", state.realtimeMetric.readMbps)} MB/s",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("读取 IOPS", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Text(
                            "${state.realtimeMetric.readIops}",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // 写性能指标
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("写入带宽（Throughput）", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Text(
                            "${String.format("%.1f", state.realtimeMetric.writeMbps)} MB/s",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("写入 IOPS", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Text(
                            "${state.realtimeMetric.writeIops}",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 延迟指标
                if (state.realtimeMetric.avgLatencyUs > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "平均延迟：${String.format("%.1f", state.realtimeMetric.avgLatencyUs)} μs",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                        Text(
                            "P99 延迟：${String.format("%.1f", state.realtimeMetric.p99LatencyUs)} μs",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }
                }
            }
        }

        // 3. 开始 / 停止操作按钮
        val isBusy = state.benchmarkState == BenchmarkState.RUNNING || state.benchmarkState == BenchmarkState.PREPARING
        Button(
            onClick = { if (isBusy) onStopTest() else onStartTest() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isBusy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                contentColor = if (isBusy) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(
                imageVector = if (isBusy) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isBusy) "终止基准测试（Stop）" else "开始基准测试（Run Benchmark）",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // 4. 标准模式预设方案（CDM 风格：先读后写，完整双向结果）
        Text(
            text = "标准访问模式（参考 CrystalDiskMark 模型预设，先测读后测写；非其官方测试）",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        PresetSuites.CDM_PRESETS.forEach { preset ->
            val isSelected = state.currentSpec.name == preset.name
            OutlinedCard(
                onClick = { onSelectPreset(preset) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.surface
                ),
                border = if (isSelected) {
                    CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary), width = 2.dp)
                } else CardDefaults.outlinedCardBorder()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onSelectPreset(preset) },
                        modifier = Modifier.padding(end = 10.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preset.name,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${preset.description} (${preset.blockSizeLabel}, ${preset.concurrencyLabel})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(text = "${preset.durationSec * 2}s（读+写）", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // 5. 独立混合模式预设（读写比例 90/10、70/30、50/50）
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "4KMIX 独立读写混合预设（读写并发）",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary
        )
        PresetSuites.FOUR_K_MIX_PRESETS.forEach { preset ->
            val isSelected = state.currentSpec.name == preset.name
            OutlinedCard(
                onClick = { onSelectPreset(preset) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.surface
                ),
                border = if (isSelected) {
                    CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.secondary), width = 2.dp)
                } else CardDefaults.outlinedCardBorder()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onSelectPreset(preset) },
                        modifier = Modifier.padding(end = 10.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preset.name,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${preset.description} (${preset.blockSizeLabel}, ${preset.concurrencyLabel})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(text = "${preset.durationSec}s", style = MaterialTheme.typography.labelMedium)
                }
            }
        }


        // 6. 内置基准（内核裁剪自上游官方源码 · 含上游自带的正确性校验）
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "内置基准（上游官方源码裁剪 · 含正确性校验）",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary
        )
        // 官方规模档位快捷选择：无需进入「自定义测试」即可切换
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "官方规模档位：", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.width(8.dp))
            com.ufs.bench.model.SpecTier.entries.forEach { t ->
                val isTierSel = state.currentSpec.specTier == t.level
                FilterChip(
                    selected = isTierSel,
                    onClick = { onSelectTier(t.level) },
                    label = {
                        Text(
                            if (t == SpecTier.REFSPEED) state.currentSpec.suiteKind.referenceTierName
                            else t.officialName
                        )
                    },
                    leadingIcon = if (isTierSel) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else null,
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }

        state.currentSpec.tier.warning?.takeIf { it.isNotBlank() }?.let { w ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (state.currentSpec.tier.pcClass) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.tertiaryContainer
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(modifier = Modifier.padding(12.dp)) {
                    Text(text = if (state.currentSpec.tier.pcClass) "⚠️" else "ℹ️", fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = w,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.currentSpec.tier.pcClass) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        }
                    )
                }
            }
        }
        PresetSuites.BUILTIN_KERNEL_PRESETS.forEach { preset ->
            val isSelected = state.currentSpec.name == preset.name
            val activeTierName = if (isSelected) state.currentSpec.officialTierName else preset.officialTierName
            val isPcClass = if (isSelected) state.currentSpec.tier.pcClass else preset.tier.pcClass
            OutlinedCard(
                onClick = { onSelectPreset(preset) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.surface
                ),
                border = if (isSelected) {
                    CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.tertiary),
                        width = 2.dp
                    )
                } else CardDefaults.outlinedCardBorder()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onSelectPreset(preset) },
                        modifier = Modifier.padding(end = 10.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preset.name,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = preset.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = activeTierName,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isPcClass) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.tertiary
                            },
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "T${preset.workerThreads}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
