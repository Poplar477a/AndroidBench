package com.ufs.bench.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.ufs.bench.ui.theme.ContentBottomInset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ufs.bench.model.BenchmarkMode
import com.ufs.bench.model.builtinMetricSpec
import com.ufs.bench.model.IometerAccessSpec
import com.ufs.bench.ui.BenchmarkState
import com.ufs.bench.ui.components.RootModeBadge
import com.ufs.bench.ui.components.ScatterChart
import com.ufs.bench.ui.components.isRootRun
import com.ufs.bench.ui.components.CompilerToolchainSection
import com.ufs.bench.ui.components.ScatterSeries
import com.ufs.bench.ui.components.SPEC_OPT_LEVEL_NOTE
import com.ufs.bench.ui.components.SPEC_TUNING_DISCLAIMER
import com.ufs.bench.ui.components.latencySections
import com.ufs.bench.ui.components.singleSectionPlan
import com.ufs.bench.ui.components.steadyStateOf
import com.ufs.bench.ui.components.throughputSections
import com.ufs.bench.ui.PhaseCurve
import com.ufs.bench.ui.RealtimeCurves
import com.ufs.bench.ui.StandardPhaseCurves
import com.ufs.bench.ui.UiState

/**
 * 测试中页面：测试运行期间的专用进行时视图。
 * 集中展示进度、实时指标与最终结论；空闲时给出引导。
 */
@Composable
fun RunningScreen(
    state: UiState,
    onStopTest: () -> Unit,
    onGoDashboard: () -> Unit,
    onShare: () -> Unit = {}
) {
    val scroll = rememberScrollState()
    val busy = state.benchmarkState == BenchmarkState.RUNNING ||
               state.benchmarkState == BenchmarkState.PREPARING
    val spec = state.currentSpec
    val metric = state.realtimeMetric

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = ContentBottomInset),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ---------- 空闲态引导 ----------
        if (state.benchmarkState == BenchmarkState.IDLE &&
            state.statusMessage == "就绪") {
            Spacer(modifier = Modifier.height(80.dp))
            Icon(
                imageVector = Icons.Default.HourglassEmpty,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "当前没有进行中的测试",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "在「首页」选择预设用例，或在「自定义测试」自定义参数后开始测试",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onGoDashboard, shape = RoundedCornerShape(12.dp)) {
                Text("前往首页")
            }
            return
        }

        // ---------- 运行时长与状态 ----------
        // 终止准则互斥：按容量时进度以字节为分母，不能拿时间当分母
        val byCapacity = !spec.portedVersion &&
            spec.stopCriterion == com.ufs.bench.model.StopCriterion.CAPACITY
        val targetBytes = if (byCapacity) spec.targetCapacityMb.toLong() * 1024L * 1024L else 0L
        val targetSec = if (spec.portedVersion || byCapacity) 0 else spec.durationSec

        // ---------- 整轮总进度 ----------
        // 标准读写是「先读后写」两个阶段，且每个阶段的 elapsedSeconds 都从 0 重新计。
        // 只按当前阶段算会出现「走到底又回退、永远到不了 100%」；
        // 因此总进度 =（已完成阶段数 + 当前阶段完成比例）/ 阶段总数。
        val phaseCount = state.phaseCount.coerceAtLeast(1)
        val phaseIndex = state.phaseIndex.coerceIn(0, phaseCount - 1)
        val phaseFraction = when {
            targetBytes > 0L -> metric.bytesDone.toFloat() / targetBytes
            targetSec > 0 -> metric.elapsedSeconds.toFloat() / targetSec
            else -> 0f
        }.coerceIn(0f, 1f)
        val progress = if (state.benchmarkState == BenchmarkState.COMPLETED) {
            1f
        } else {
            ((phaseIndex + phaseFraction) / phaseCount).coerceIn(0f, 1f)
        }

        val animatedProgress by animateFloatAsState(targetValue = progress, label = "progress")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = spec.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                // 模式徽标：容器色与前景色成对取自语义角色，对比度交由主题保证
                val badgeContainer = when (spec.mode) {
                    BenchmarkMode.BUILTIN_KERNEL -> MaterialTheme.colorScheme.tertiaryContainer
                    BenchmarkMode.STANDARD_READ_WRITE -> MaterialTheme.colorScheme.primaryContainer
                    BenchmarkMode.MIXED_SIMULTANEOUS -> MaterialTheme.colorScheme.secondaryContainer
                    BenchmarkMode.READ_ONLY, BenchmarkMode.WRITE_ONLY -> MaterialTheme.colorScheme.surfaceVariant
                }
                val badgeContent = when (spec.mode) {
                    BenchmarkMode.BUILTIN_KERNEL -> MaterialTheme.colorScheme.onTertiaryContainer
                    BenchmarkMode.STANDARD_READ_WRITE -> MaterialTheme.colorScheme.onPrimaryContainer
                    BenchmarkMode.MIXED_SIMULTANEOUS -> MaterialTheme.colorScheme.onSecondaryContainer
                    BenchmarkMode.READ_ONLY, BenchmarkMode.WRITE_ONLY -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = badgeContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = when (spec.mode) {
                                BenchmarkMode.BUILTIN_KERNEL -> "内置基准 · 强度：${spec.officialTierName}"
                                BenchmarkMode.STANDARD_READ_WRITE -> "标准读写双向"
                                BenchmarkMode.MIXED_SIMULTANEOUS -> "独立混合并发"
                                BenchmarkMode.READ_ONLY -> "单项纯读"
                                BenchmarkMode.WRITE_ONLY -> "单项纯写"
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            color = badgeContent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    // root 模式标记：紧挨模式徽标，测试中/完成后都在首屏可见
                    if (isRootRun(state.professionalMode, state.currentRunSummary?.privilegeMethod.orEmpty())) {
                        Spacer(modifier = Modifier.width(8.dp))
                        RootModeBadge()
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 完成后 realtimeMetric 会被清零，此时应展示最终规模而不是"0 MB"
                val finished = state.benchmarkState == BenchmarkState.COMPLETED
                val progressText = when {
                    finished && targetBytes > 0L -> String.format(
                        "已完成 %d MB（目标 %d MB）",
                        state.currentRunSummary?.totalBytesDone ?: 0L / 1024L / 1024L,
                        targetBytes / 1024L / 1024L
                    )
                    finished -> "已完成"
                    targetBytes > 0L -> String.format(
                        "已读写 %d MB / %d MB · 已用 %ds",
                        metric.bytesDone / 1024L / 1024L,
                        targetBytes / 1024L / 1024L,
                        metric.elapsedSeconds
                    )
                    targetSec > 0 -> "已进行 ${metric.elapsedSeconds}s / ${targetSec}s"
                    else -> "已运行 ${metric.elapsedSeconds}s"
                }
                Text(
                    text = (if (phaseCount > 1 && !finished) {
                        "第 ${phaseIndex + 1}/${phaseCount} 阶段 · "
                    } else "") + progressText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )

                if (metric.currentPhase.isNotEmpty() && busy) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = metric.currentPhase,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = state.statusMessage,
                    color = when (state.benchmarkState) {
                        BenchmarkState.ERROR -> MaterialTheme.colorScheme.error
                        BenchmarkState.COMPLETED -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        // ---------- PC 级档位警告 ----------
        if (spec.portedVersion) {
            spec.tier.warning?.takeIf { it.isNotBlank() }?.let { w ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = if (spec.tier.pcClass) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Text(text = if (spec.tier.pcClass) "⚠️" else "ℹ️", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = w,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (spec.tier.pcClass) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        }

        // ---------- 实时指标 ----------
        if (spec.portedVersion) {
            // 官方仅在 参考档/参考档 档给出可上报成绩；完工后回填最终值
            val finalRec = state.currentRunSummary
            val shownRatio = if (state.benchmarkState == BenchmarkState.COMPLETED && finalRec != null)
                finalRec.specRatio else metric.specRatio
            val shownCpu = if (state.benchmarkState == BenchmarkState.COMPLETED && finalRec != null)
                finalRec.cpuMetric else metric.cpuMetric
            val shownMem = if (state.benchmarkState == BenchmarkState.COMPLETED && finalRec != null)
                finalRec.memMetric else metric.memMetric
            SpecPortedMetricsCard(
                spec, shownRatio, shownCpu, shownMem,
                measuredSeconds = finalRec?.measuredSeconds ?: 0.0,
                referenceSeconds = finalRec?.referenceSeconds ?: 0.0,
                isEmulator = state.hardwareInfo.isEmulator
            )
            // 编译工具链独立成块（默认收起、可展开完整串）：原生按编译期宏生成，这里只展示
            CompilerToolchainSection(finalRec?.compilerToolchain.orEmpty())
        } else {
            StorageMetricsCard(metric)
        }

        // ---------- 专业模式：全量指标与性能曲线 ----------
        // 关键：这里只认「本次」运行的 summary（currentRunSummary），**不读累积的 history**。
        // 否则跑过一轮后再开一轮，history 里上一轮的 summary 会让 detail != null 成立，
        // 于是整个测试过程都显示旧结果、实时散点卡也不再出现。
        // currentRunSummary 在开测时置 null、结束时写入，正好对应两种状态。
        val thisRun = state.currentRunSummary
        val prof = thisRun?.detail
        if (spec.privilege.professional && thisRun != null && prof != null) {
            // 写阶段详情与 detail 取自同一条 summary（标准读写才有）
            ProfessionalStatsCard(
                d = prof,
                writeDetail = thisRun.writeDetail,
                isSpec = spec.portedVersion,
                isMixed = spec.mode == BenchmarkMode.MIXED_SIMULTANEOUS,
                spec = spec
            )
        } else if (spec.privilege.professional && !spec.portedVersion) {
            // 【严禁混表】标准读写是「阶段 1 纯读 → 阶段 2 纯写」两段绝对串行的独立测量：
            // 两阶段各有各的时间原点与量纲，必须用四张互不干扰的独立图表呈现，
            // 不得复用混合并发（读写同刻发生、共享同一时间轴）的同轴组件。
            if (spec.mode == BenchmarkMode.STANDARD_READ_WRITE) {
                if (state.standardCurves.sampleCount >= 2) {
                    StandardPhaseScatterCard(state.standardCurves, spec, state.phaseIndex)
                }
            } else if (state.realtimeCurves.sampleCount >= 2) {
                RealtimeScatterCard(state.realtimeCurves, spec)
            }
        }

        // ---------- 结论 ----------
        if (state.benchmarkState == BenchmarkState.COMPLETED ||
            state.benchmarkState == BenchmarkState.ERROR) {
            val last = state.currentRunSummary
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (state.benchmarkState == BenchmarkState.ERROR) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (state.benchmarkState == BenchmarkState.ERROR)
                                Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (state.benchmarkState == BenchmarkState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (state.benchmarkState == BenchmarkState.ERROR) "测试异常" else "测试完成",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (!spec.portedVersion && last != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (last.ioMethod.isNotEmpty()) {
                            Text(
                                text = "I/O 通道：" + last.ioMethod,
                                fontSize = 12.sp,
                                color = if (last.ioMethod.startsWith("O_DIRECT")) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        val dsMb = spec.fileSizeMb
                        val dsText = if (dsMb >= 1024) String.format("%.1f GB (%d MB)", dsMb / 1024.0, dsMb) else "$dsMb MB"
                        val addrText = if (dsMb >= 1024) String.format("0 ~ %.1f GB (%s寻道)", dsMb / 1024.0, if (spec.percentRandom >= 50) "随机" else "顺序")
                                       else "0 ~ ${dsMb} MB (${if (spec.percentRandom >= 50) "随机" else "顺序"}寻道)"
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "测试数据集：$dsText ｜ 地址范围：$addrText",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (last.verifyDetail.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (last.verified) "数据完整性校验：通过" else "数据完整性校验：未通过",
                                fontSize = 12.sp,
                                color = if (last.verified) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = last.verifyDetail,
                                fontSize = 11.sp,
                                color = if (last.verified) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    if (spec.portedVersion && last != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (last.verified) "输出正确性校验：通过" else "输出正确性校验：未通过",
                            fontSize = 12.sp,
                            color = if (last.verified) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                        if (last.verifyDetail.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = last.verifyDetail,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ---------- 操作 ----------
        // 内置微基准不提供分享：比值为自标定基线，分享出去易被当成 spec.org 官方成绩
        if (state.benchmarkState == BenchmarkState.COMPLETED && !busy && !spec.portedVersion) {
            Button(
                onClick = onShare,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("生成结果卡并分享", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (busy) {
            Button(
                onClick = onStopTest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("终止测试", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            OutlinedButton(
                onClick = onGoDashboard,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("返回首页", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun SpecPortedMetricsCard(
    spec: com.ufs.bench.model.IometerAccessSpec,
    specRatio: Double,
    cpuMetric: Double,
    memMetric: Double,
    /** 本机实测耗时（秒）；0 = 该路径不产出，不编造 */
    measuredSeconds: Double = 0.0,
    /** 自标定基线耗时（秒） */
    referenceSeconds: Double = 0.0,
    /** 模拟器/虚拟机：跨设备比值不可比，不展示 */
    isEmulator: Boolean = false
) {
    val metrics = com.ufs.bench.model.builtinMetricSpec(spec.builtinKernelId)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (spec.tier.showsRatio) "相对自标定基线比值"
                            else "强度：${spec.officialTierName}（自检档，不出分）",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Text(
                        text = when {
                            isEmulator -> "模拟器/虚拟机环境的指令翻译与宿主调度差异过大，" +
                                "与真机基线不可比；本页只展示原始读数，不给出比值"
                            !spec.tier.showsRatio -> "自检档：缩小规模只验正确性，不计时、不出分"
                            !spec.tier.reportsScore -> "常规档；比值相对本机自标定基线，不是上游官方分数"
                            // 不冒充官方分数：CoreMark/STREAM/tinymembench 的官方分数都需要官方认证流程
                            else -> "比值为相对本机自标定基线，非上游官方分数，不可与 EEMBC / STREAM 等公布成绩比较"
                        },
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (spec.tier.showsRatio && !isEmulator) {
                    Text(
                        text = String.format("%.3f", specRatio),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                } else {
                    Text(
                        text = "—",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 实测耗时 vs 自标定基线：比值 = 基线 / 实测，缺了实测这一半结果无法自证
                if (measuredSeconds > 0.0) {
                    Text(
                        text = "实测 " + String.format("%.3f", measuredSeconds) + " s" +
                            (if (referenceSeconds > 0.0)
                                "  ·  自标定基线 " + String.format("%.1f", referenceSeconds) + " s"
                             else ""),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                metrics.cpuLabel?.let { lbl ->
                    Column {
                        Text(lbl, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${String.format("%.1f", cpuMetric * metrics.cpuScale)} ${metrics.cpuUnit}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                metrics.memLabel?.let { lbl ->
                    Column(horizontalAlignment = Alignment.End) {
                        Text(lbl, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${String.format("%.2f", memMetric * metrics.memScale)} ${metrics.memUnit}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

        }
    }
}


@Composable
private fun StorageMetricsCard(metric: com.ufs.bench.model.RealtimeMetric) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("读取带宽", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${String.format("%.1f", metric.readMbps)} MB/s",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("IOPS: ${metric.readIops}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("写入带宽", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${String.format("%.1f", metric.writeMbps)} MB/s",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text("IOPS: ${metric.writeIops}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (metric.avgLatencyUs > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("平均延迟 ${String.format("%.1f", metric.avgLatencyUs)} μs", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("P99 ${String.format("%.1f", metric.p99LatencyUs)} μs", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * 测试进行中的实时散点卡片。
 *
 * 原生层的完整曲线只在测试结束后随 detail 返回，所以进行中只能按进度回调攒点；
 * 视觉语言与结束后的 ProfessionalStatsCard 保持一致（读=primary、写=error）。
 */
@Composable
private fun RealtimeScatterCard(curves: RealtimeCurves, spec: IometerAccessSpec) {
    val readColor = MaterialTheme.colorScheme.primary
    val writeColor = MaterialTheme.colorScheme.error

    // 定时与定容测试：直接显示完整的时间或容量横轴，不随采样逐渐递进，根除轴缩放引起的抽搐
    val isCapacity = spec.stopCriterion == com.ufs.bench.model.StopCriterion.CAPACITY
    val xVals: DoubleArray
    val xLabel: String
    val xMinVal = 0.0
    val xMaxVal: Double

    if (isCapacity) {
        val capMb = spec.targetCapacityMb.toDouble()
        if (capMb >= 1024.0) {
            xVals = curves.capacityMb.map { it / 1024.0 }.toDoubleArray()
            xLabel = "完成容量 (GB)"
            xMaxVal = capMb / 1024.0
        } else {
            xVals = curves.capacityMb.toDoubleArray()
            xLabel = "完成容量 (MB)"
            xMaxVal = capMb
        }
    } else {
        xVals = curves.timeSec.toDoubleArray()
        xLabel = "时间 (s)"
        xMaxVal = spec.durationSec.toDouble()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "实时散点图",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "测试进行中按采样档位实时累积；横轴完整呈现当前测试目标，点位从左往右绘制。" +
                    "CPU 占用率曲线由原生层在测试结束后一次性给出。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 【不混表】四项指标四张独立的表，任何一张只出现单一量纲。
            val bwSeries = buildList {
                if (curves.readBwMbps.any { it > 0.0 }) add(
                    ScatterSeries("读吞吐", curves.readBwMbps.toDoubleArray(), readColor)
                )
                if (curves.writeBwMbps.any { it > 0.0 }) add(
                    ScatterSeries("写吞吐", curves.writeBwMbps.toDoubleArray(), writeColor)
                )
            }
            if (bwSeries.isNotEmpty()) {
                ScatterChart(
                    series = bwSeries,
                    title = "吞吐散点图",
                    unit = "MB/s",
                    xValues = xVals,
                    xAxisLabel = xLabel,
                    fixedXMin = xMinVal,
                    fixedXMax = xMaxVal,
                    sampleIntervalMs = spec.sampleIntervalMs,
                    caption = "已采样 " + curves.sampleCount + " 个点 · 横轴为完整目标量程"
                )
                Spacer(modifier = Modifier.height(18.dp))
            }

            val latSeries = buildList {
                if (curves.readLatUs.any { it > 0.0 }) add(
                    ScatterSeries("读响应延迟", curves.readLatUs.toDoubleArray(), readColor)
                )
                if (curves.writeLatUs.any { it > 0.0 }) add(
                    ScatterSeries("写响应延迟", curves.writeLatUs.toDoubleArray(), writeColor)
                )
            }
            if (latSeries.isNotEmpty()) {
                ScatterChart(
                    series = latSeries,
                    title = "延迟散点图",
                    unit = "μs",
                    xValues = xVals,
                    xAxisLabel = xLabel,
                    fixedXMin = xMinVal,
                    fixedXMax = xMaxVal,
                    sampleIntervalMs = spec.sampleIntervalMs,
                    caption = "本采样区间内读 / 写请求的平均响应延迟（相邻采样增量口径）"
                )
            }
        }
    }
}

/**
 * 标准读写（先读后写两阶段）进行中的实时散点卡片：**四张完全独立的表**。
 *
 * 读带宽 / 写带宽 / 读时延 / 写时延各占一张图，互不共轴：
 * - 数据源隔离：每张表只吃自己阶段的样本，读的样本永不进写的表（反之亦然）；
 * - 横轴隔离：每张表一条自己的横轴（阶段内时间从 0 s 起算；容量模式为阶段内完成容量），
 *   写阶段的时间轴不会跳回 0 去和读阶段重叠；
 * - 纵轴隔离：每张表各自算量程（[ScatterChart] 只汇总本表系列），
 *   读时延（数十 μs 级）不会被写时延（数千 μs 级）压成贴底的一条死线；
 * - 阶段结束的表冻结展示（样本不清空），尚未开始的阶段给出占位提示而不是空图。
 */
@Composable
private fun StandardPhaseScatterCard(
    curves: StandardPhaseCurves,
    spec: IometerAccessSpec,
    phaseIndex: Int
) {
    val readColor = MaterialTheme.colorScheme.primary
    val writeColor = MaterialTheme.colorScheme.error
    val isCapacity = spec.stopCriterion == com.ufs.bench.model.StopCriterion.CAPACITY
    val capMb = spec.targetCapacityMb.toDouble()
    val capIsGb = capMb >= 1024.0
    val xMaxVal = if (isCapacity) {
        if (capIsGb) capMb / 1024.0 else capMb
    } else {
        spec.durationSec.toDouble()
    }
    val xLabel = if (isCapacity) {
        if (capIsGb) "完成容量 (GB)" else "完成容量 (MB)"
    } else {
        "时间 (s)"
    }

    fun xOf(phase: PhaseCurve): DoubleArray = if (isCapacity) {
        if (capIsGb) phase.capacityMb.map { it / 1024.0 }.toDoubleArray()
        else phase.capacityMb.toDoubleArray()
    } else {
        phase.timeSec.toDoubleArray()
    }

    val readDone = phaseIndex > 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "实时散点图 · 标准读写（读 / 写分表）",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "标准读写是「阶段 1 纯读 → 阶段 2 纯写」两段串行的独立测量：两阶段各有自己的" +
                    "时间原点、目标时长与纵轴量程。四张表分别只呈现本阶段的样本，读与写不共表、不共轴。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))

            PhaseChartBlock(
                heading = "① 读取带宽 (Read Bandwidth)",
                unit = "MB/s",
                values = curves.readPhase.bwMbps,
                xValues = xOf(curves.readPhase),
                xLabel = xLabel,
                xMaxVal = xMaxVal,
                color = readColor,
                seriesLabel = "读吞吐",
                sampleIntervalMs = spec.sampleIntervalMs,
                statusNote = if (readDone) "阶段 1/2 · 读阶段已完成，曲线冻结" else "阶段 1/2 · 正在测试读取",
                waitingNote = "等待阶段 1（纯读）的带宽采样…",
                caption = "只含读阶段样本；纵轴量程独立计算，写入样本不会进入本表。"
            )

            PhaseChartBlock(
                heading = "② 写入带宽 (Write Bandwidth)",
                unit = "MB/s",
                values = curves.writePhase.bwMbps,
                xValues = xOf(curves.writePhase),
                xLabel = xLabel,
                xMaxVal = xMaxVal,
                color = writeColor,
                seriesLabel = "写吞吐",
                sampleIntervalMs = spec.sampleIntervalMs,
                statusNote = if (readDone) "阶段 2/2 · 正在测试写入" else "阶段 2/2 · 等待读阶段结束",
                waitingNote = "阶段 2 尚未开始（标准读写先跑完纯读）。此处不会出现读阶段的样本。",
                caption = "只含写阶段样本；横轴为写阶段自己的时间原点（从 0 s 起算）。"
            )

            PhaseChartBlock(
                heading = "③ 读取时延 (Read Latency)",
                unit = "μs",
                values = curves.readPhase.latUs,
                xValues = xOf(curves.readPhase),
                xLabel = xLabel,
                xMaxVal = xMaxVal,
                color = readColor,
                seriesLabel = "读响应延迟",
                sampleIntervalMs = spec.sampleIntervalMs,
                statusNote = if (readDone) "阶段 1/2 · 读阶段已完成，曲线冻结" else "阶段 1/2 · 正在测试读取",
                waitingNote = "等待阶段 1（纯读）的延迟采样…",
                caption = "采样区间内的读请求平均响应延迟；纵轴按读延迟自身量程定标，不被写延迟拉平。"
            )

            PhaseChartBlock(
                heading = "④ 写入时延 (Write Latency)",
                unit = "μs",
                values = curves.writePhase.latUs,
                xValues = xOf(curves.writePhase),
                xLabel = xLabel,
                xMaxVal = xMaxVal,
                color = writeColor,
                seriesLabel = "写响应延迟",
                sampleIntervalMs = spec.sampleIntervalMs,
                statusNote = if (readDone) "阶段 2/2 · 正在测试写入" else "阶段 2/2 · 等待读阶段结束",
                waitingNote = "阶段 2 尚未开始；写入要等 FTL 回收与闪存落盘，时延量级与读不同，故独立成表。",
                caption = "采样区间内的写请求平均响应延迟；纵轴按写延迟自身量程定标，含 FTL 与落盘等待。"
            )
        }
    }
}

/**
 * 四张独立图表中的一张：只画一个阶段的一个量纲。
 *
 * 没有样本时给出占位说明而不是空图，避免把「还没跑到」误读成「跑出来是 0」。
 */
@Composable
private fun PhaseChartBlock(
    heading: String,
    unit: String,
    values: List<Double>,
    xValues: DoubleArray,
    xLabel: String,
    xMaxVal: Double,
    color: androidx.compose.ui.graphics.Color,
    seriesLabel: String,
    sampleIntervalMs: Int,
    statusNote: String,
    waitingNote: String,
    caption: String
) {
    if (values.any { it > 0.0 }) {
        ScatterChart(
            series = listOf(ScatterSeries(seriesLabel, values.toDoubleArray(), color)),
            title = heading,
            unit = unit,
            xValues = xValues,
            xAxisLabel = xLabel,
            fixedXMin = 0.0,
            fixedXMax = xMaxVal,
            sampleIntervalMs = sampleIntervalMs,
            caption = statusNote + " · " + caption
        )
    } else {
        Text(
            text = heading,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = statusNote + " · " + waitingNote,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
    Spacer(modifier = Modifier.height(18.dp))
}

/**
 * 稳定性与控制器错误的一组（单个方向的 detail）。
 *
 * 读、写各调一次：抖动 / 掉速 / 超时 / 系统调用错误都带方向性 ——
 * 写路径要等 FTL 回收与落盘确认，掉速与超时事件通常明显多于读，
 * 用读阶段的数字兼作写阶段的表现会掩盖真实问题。
 */
@Composable
private fun StabilityGroup(
    title: String,
    src: com.ufs.bench.core.NativeDetailedStats
) {
    Text(
        title,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(6.dp))
    StatRow("稳态吞吐", String.format("%.1f MB/s", src.steadyStateMbps))
    StatRow("抖动（变异系数）", String.format("%.2f %%", src.jitterPercent))
    StatRow(
        "掉速事件（相邻跌幅 ≥30%）",
        src.dropEvents.toString() + (if (src.dropEvents > 0)
            String.format("，最严重 %.1f%%", src.worstDropPercent) else "")
    )
    Spacer(modifier = Modifier.height(8.dp))
    StatRow(
        "I/O 超时（单次 > " + String.format("%.0f", src.timeoutThresholdUs / 1000.0) + " ms）",
        src.timeoutCount.toString(),
        warn = src.timeoutCount > 0
    )
    StatRow("系统调用错误", src.ioErrorCount.toString(), warn = src.ioErrorCount > 0)
}

/**
 * 专业模式全量统计面板：对齐 fio 的 lat percentiles 与运行期指标，
 * 并以散点图呈现各量纲的采样曲线。
 */
@Composable
private fun ProfessionalStatsCard(
    d: com.ufs.bench.core.NativeDetailedStats,
    writeDetail: com.ufs.bench.core.NativeDetailedStats? = null,
    isSpec: Boolean = false,
    /** 混合模式（同一次压测内读写并发）：只有动态范围超阈值才分表 */
    isMixed: Boolean = false,
    spec: IometerAccessSpec? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "专业模式详细指标",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "采样 ${"%.0f".format(d.sampleIntervalMs)} ms",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---- 延迟百分位（fio lat percentiles 口径）----
            // 需求方裁定：**读写严格分开**。合并成一套会掩盖写路径特有的长尾
            // （写要等 FTL 回收 / 落盘确认，P99.9 常比读高一个量级）。
            val wdLat = writeDetail
            val hasReadLat = d.p50LatencyUs > 0 || d.p99LatencyUs > 0 || d.maxLatencyUs > 0
            val hasWriteLat = wdLat != null &&
                (wdLat.p50LatencyUs > 0 || wdLat.p99LatencyUs > 0 || wdLat.maxLatencyUs > 0)
            if (hasWriteLat && wdLat != null) {
                Text("读响应延迟分位（μs）", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LatCell("P50", d.p50LatencyUs)
                    LatCell("P95", d.p95LatencyUs)
                    LatCell("P99", d.p99LatencyUs)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LatCell("P99.9", d.p999LatencyUs)
                    LatCell("MAX", d.maxLatencyUs)
                    if (!isSpec) LatCell("CPU", d.cpuPercentOfCores, suffix = "%")
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("写响应延迟分位（μs）", fontSize = 12.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LatCell("P50", wdLat.p50LatencyUs)
                    LatCell("P95", wdLat.p95LatencyUs)
                    LatCell("P99", wdLat.p99LatencyUs)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LatCell("P99.9", wdLat.p999LatencyUs)
                    LatCell("MAX", wdLat.maxLatencyUs)
                    Spacer(modifier = Modifier.weight(1f))
                }
            } else if (hasReadLat) {
                Text("I/O 延迟分位（μs）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LatCell("P50", d.p50LatencyUs)
                    LatCell("P95", d.p95LatencyUs)
                    LatCell("P99", d.p99LatencyUs)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LatCell("P99.9", d.p999LatencyUs)
                    LatCell("MAX", d.maxLatencyUs)
                    // CPU 汇总用归一化口径（0~100%，符合直觉）；曲线仍用各核累加口径。
                    // 内置微基准不产出 CPU 占用埋点，显示 0% 会误导，因此直接不显示。
                    if (!isSpec) LatCell("CPU", d.cpuPercentOfCores, suffix = "%")
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(12.dp))

            // ---- 稳定性与控制器错误：读写各出一套 ----
            // 抖动 / 掉速 / 超时 / 系统调用错误都是**分方向**的量：写要等 FTL 回收与落盘确认，
            // 掉速与超时事件通常明显多于读。只出一套（读阶段）会把写路径的问题盖掉。
            StabilityGroup(
                if (writeDetail != null) "稳定性与控制器错误（读阶段）" else "稳定性与控制器错误",
                d
            )
            if (writeDetail != null) {
                Spacer(modifier = Modifier.height(12.dp))
                StabilityGroup("稳定性与控制器错误（写阶段）", writeDetail)
            }

            // ---- 性能散点图：吞吐 / 延迟 / CPU ----
            val curveSamples = maxOf(
                d.curveReadBwMbps.size, d.curveWriteBwMbps.size,
                d.curveReadLatUs.size, d.curveWriteLatUs.size,
                d.curveCpuPercent.size
            )
            if (curveSamples >= 2) {
                // Canvas 的绘制 lambda 不是组合作用域，语义色必须先在组合层取好
                val readColor = MaterialTheme.colorScheme.primary
                val writeColor = MaterialTheme.colorScheme.error
                val cpuColor = MaterialTheme.colorScheme.tertiary

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))

                // 分表方案：非混合（标准读写两阶段 / 单项）恒定分表；
                // 混合模式动态范围超 20 倍才分表，否则保持「同表不同色」。
                // 分表后每张图各自独立 Y 轴，写阶段用自己的时间轴（从 0 起算）。
                val plans = buildList {
                    addAll(throughputSections(d, writeDetail, readColor, writeColor, isMixed))
                    addAll(latencySections(d, writeDetail, readColor, writeColor, isMixed))
                    if (!isSpec) {
                        // 内置微基准不产出 CPU 占用埋点，避免出现 0% 的误导性数字
                        addAll(
                            singleSectionPlan(
                                "CPU 占用率散点图", "%", "CPU 占用率",
                                d.curveCpuPercent, d.curveTimeSec, cpuColor
                            )
                        )
                    }
                }

                plans.forEach { plan ->
                    val samples = plan.series.maxOf { s -> s.values.size }
                    val splitNote = if (plan.series.size == 1) " · 本图独立 Y 轴" else " · 读写同表分色"
                    val caption = when (plan.unit) {
                        "%" -> String.format(
                            "测试负载 CPU 占用（仅统计 benchmark 工作线程，不含界面渲染）；" +
                                "曲线为各核累加口径，多线程时可超过 100%%。归一化均值 %.1f%%（%d 核）",
                            d.cpuPercentOfCores, d.cpuCoreCount
                        )
                        "μs" -> String.format(
                            "采样间隔 %.0f ms · 共 %d 个采样点%s · 区间平均响应延迟（相邻采样增量口径）",
                            d.sampleIntervalMs, samples, splitNote
                        )
                        else -> {
                            // 稳态按本图曲线现算：原生的 detail.steadyStateMbps 是读阶段（或读写合计）口径，
                            // 写表照抄它会印出读的数值。
                            val steady = plan.series.firstOrNull()?.values?.let { steadyStateOf(it) }
                            String.format(
                                "采样间隔 %.0f ms · 共 %d 个采样点%s%s",
                                d.sampleIntervalMs, samples, splitNote,
                                if (steady != null) String.format(" · 稳态 %.1f MB/s", steady) else ""
                            )
                        }
                    }
                    val targetXMax = if (spec != null && spec.stopCriterion == com.ufs.bench.model.StopCriterion.TIME) {
                        spec.durationSec.toDouble()
                    } else null
                    ScatterChart(
                        series = plan.series,
                        title = plan.title,
                        unit = plan.unit,
                        xValues = plan.xValues,
                        fixedXMin = if (targetXMax != null) 0.0 else null,
                        fixedXMax = targetXMax,
                        sampleIntervalMs = d.sampleIntervalMs.toInt(),
                        caption = caption
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                }
            }
        }
    }
}

@Composable
private fun LatCell(label: String, value: Double, suffix: String = "") {
    Column {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = String.format("%.0f%s", value, suffix),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (label == "P99.9" || label == "MAX") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun StatRow(label: String, value: String, warn: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}
