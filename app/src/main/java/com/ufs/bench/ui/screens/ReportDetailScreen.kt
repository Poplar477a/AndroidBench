package com.ufs.bench.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.ufs.bench.ui.theme.ContentBottomInset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ufs.bench.hardware.HardwareStorageInfo
import com.ufs.bench.model.BenchmarkMode
import com.ufs.bench.model.builtinMetricSpec
import com.ufs.bench.model.BenchmarkSummary
import com.ufs.bench.model.StopCriterion
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 稳定性四项（单个方向的 detail）。
 *
 * 读、写各调一次：抖动 / 掉速 / 超时 / 系统调用错误都带方向性 ——
 * 写路径要等 FTL 回收与落盘确认，掉速与超时事件通常明显多于读，
 * 用读阶段的数字兼作写阶段的表现会掩盖真实问题。
 */
@Composable
private fun StabilityRows(src: com.ufs.bench.core.NativeDetailedStats) {
    DetailRow("抖动（变异系数）", String.format("%.2f %%", src.jitterPercent))
    DetailRow(
        "掉速事件（相邻跌幅 ≥30%）",
        src.dropEvents.toString() +
            (if (src.dropEvents > 0) String.format("，最严重 %.1f%%", src.worstDropPercent) else "")
    )
    DetailRow(
        "I/O 超时（> ${String.format("%.0f", src.timeoutThresholdUs / 1000.0)} ms)",
        src.timeoutCount.toString()
    )
    DetailRow("系统调用错误", src.ioErrorCount.toString())
}

/**
 * 历史详情入口：按 portedVersion 分派两套模板。
 *
 * SPEC 是纯 CPU + 内存负载，吞吐 / IOPS / 延迟 / 分位 / 抖动 / 曲线对它都没有意义，
 * 强套存储模板只会呈现一堆 0 与空图，所以两套模板各自独立。
 */
@Composable
fun ReportDetailScreen(
    item: BenchmarkSummary,
    hw: HardwareStorageInfo,
    onBack: () -> Unit,
    onShare: () -> Unit
) {
    if (item.spec.portedVersion) {
        SpecReportDetail(item = item, hw = hw, onBack = onBack)
    } else {
        StorageReportDetail(item = item, hw = hw, onBack = onBack, onShare = onShare)
    }
}

/** 存储测试详情模板（吞吐 / 延迟分位 / 三张散点图 / 稳定性 / 手段披露） */
@Composable
private fun StorageReportDetail(
    item: BenchmarkSummary,
    hw: HardwareStorageInfo,
    onBack: () -> Unit,
    onShare: () -> Unit
) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val d = item.detail
    // 标准读写的写阶段详情（第二次独立测量，曲线自带 0 秒起点）
    val writeDetail = item.writeDetail
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = ContentBottomInset),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---------- 标题栏 ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.spec.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = dateFormat.format(Date(item.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "分享", tint = MaterialTheme.colorScheme.primary)
            }
        }

        // ---------- 吞吐成绩 ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("吞吐成绩", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("读取", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${String.format("%.1f", item.readMbps)} MB/s",
                            fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                        )
                        Text("IOPS ${item.readIops}", fontSize = 12.sp)
                        if (item.readAvgLatencyUs > 0) {
                            Text("平均延迟 ${String.format("%.1f", item.readAvgLatencyUs)} μs",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("写入", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${String.format("%.1f", item.writeMbps)} MB/s",
                            fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error
                        )
                        Text("IOPS ${item.writeIops}", fontSize = 12.sp)
                        if (item.writeAvgLatencyUs > 0) {
                            Text("平均延迟 ${String.format("%.1f", item.writeAvgLatencyUs)} μs",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // ---------- 测试条件 ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("测试条件", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                DetailRow("读写模式", when (item.spec.mode) {
                    BenchmarkMode.STANDARD_READ_WRITE -> "标准读写（先读后写）"
                    BenchmarkMode.MIXED_SIMULTANEOUS -> "独立混合（同时并发）"
                    BenchmarkMode.READ_ONLY -> "单项纯读"
                    BenchmarkMode.WRITE_ONLY -> "单项纯写"
                    else -> "内置基准"
                })
                if (item.spec.mode != BenchmarkMode.BUILTIN_KERNEL) {
                    val dsMb = item.spec.fileSizeMb
                    val dsText = if (dsMb >= 1024) String.format("%.1f GB (%d MB)", dsMb / 1024.0, dsMb) else "$dsMb MB"
                    val addrText = if (dsMb >= 1024) String.format("0 ~ %.1f GB (%s寻道)", dsMb / 1024.0, if (item.spec.percentRandom >= 50) "随机" else "顺序")
                                   else "0 ~ ${dsMb} MB (${if (item.spec.percentRandom >= 50) "随机" else "顺序"}寻道)"
                    DetailRow("测试数据集", dsText)
                    DetailRow("地址范围", addrText)
                    DetailRow("块大小 / 访问方式", "${item.spec.blockSizeLabel} · " +
                        (if (item.spec.percentRandom >= 50) "随机" else "顺序"))
                    DetailRow("并发", item.spec.concurrencyLabel)
                    DetailRow("终止准则", if (item.spec.stopCriterion == StopCriterion.CAPACITY)
                        "按容量 ${item.spec.targetCapacityMb} MB" else "按时间 ${item.spec.durationSec}s")
                    DetailRow("缓存模式", item.spec.cacheMode.label)
                    DetailRow("测试手段", item.spec.privilege.label)
                } else {
                    DetailRow("内置档位", item.spec.officialTierName)
                    if (item.spec.tier.showsRatio) {
                        DetailRow("成绩口径", "比值相对本项目自标定基线，非 官方参考值")
                    }
                }
                DetailRow(
                    "实测容量",
                    String.format("%.1f MB", item.totalBytesDone / 1024.0 / 1024.0) +
                        if (item.stoppedByCapacity) {
                            // 标准读写是两阶段，每阶段各自压到目标容量，故总数约为目标的 2 倍
                            val phases = if (item.spec.mode == BenchmarkMode.STANDARD_READ_WRITE) "，两阶段合计" else ""
                            "（各阶段达到 ${item.spec.targetCapacityMb} MB 上限$phases）"
                        } else ""
                )
            }
        }

        // ---------- 设备信息 ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("测试设备", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                DetailRow("机型", hw.deviceName.ifEmpty { "—" })
                DetailRow("型号代码", hw.deviceCode.ifEmpty { "—" })
                DetailRow("系统版本", hw.androidRelease.ifEmpty { "—" })
                DetailRow("SoC", hw.socModel.ifEmpty { "—" })
                if (hw.sysfsReadable) {
                    DetailRow("存储厂商", hw.storageVendor.ifEmpty { "—" })
                    DetailRow("存储型号", hw.storageType.ifEmpty { "—" })
                    DetailRow("块设备", hw.blockDevice.ifEmpty { "—" })
                    DetailRow("I/O 调度器", hw.ioScheduler.ifEmpty { "—" })
                    DetailRow("硬件队列深度", hw.queueDepthLimit.toString())
                }
            }
        }

        // ---------- I/O 延迟分位 ----------
        if (d != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("I/O 延迟分位（μs）", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        LatCell("P50", d.p50LatencyUs)
                        LatCell("P95", d.p95LatencyUs)
                        LatCell("P99", d.p99LatencyUs)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        LatCell("P99.9", d.p999LatencyUs)
                        LatCell("MAX", d.maxLatencyUs)
                        LatCell("CPU", d.cpuPercentOfCores, "%")
                    }
                }
            }
        }

        // ---------- 吞吐 / 延迟散点图：按「分表」口径渲染 ----------
        // 标准读写（两阶段）与单项测试恒定分表：读、写各一张图、各自独立 Y 轴；
        // 混合模式动态范围超 20 倍才分表，否则保持同表分色。与「测试中」页同一套装配。
        if (d != null) {
            // Canvas 不是组合作用域，语义色先取好；读=primary、写=error，与实时指标卡一致
            val readColor = MaterialTheme.colorScheme.primary
            val writeColor = MaterialTheme.colorScheme.error
            val isMixed = item.spec.mode == BenchmarkMode.MIXED_SIMULTANEOUS
            val plans = buildList {
                addAll(throughputSections(d, writeDetail, readColor, writeColor, isMixed))
                addAll(latencySections(d, writeDetail, readColor, writeColor, isMixed))
                addAll(
                    singleSectionPlan(
                        "CPU 占用率散点图", "%", "CPU 占用率",
                        d.curveCpuPercent, d.curveTimeSec, MaterialTheme.colorScheme.tertiary
                    )
                )
            }
            plans.forEach { plan ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val samples = plan.series.maxOf { s -> s.values.size }
                        val splitNote = if (plan.series.size == 1) " · 本图独立 Y 轴" else " · 读写同表分色"
                        // 冻结横轴量程 + 采样间隔：历史详情与运行页同源，
                        // 抽稀步长按整段测试一次算定，避免回看时长图抽搐。
                        val fixedMax = if (item.spec.stopCriterion ==
                            com.ufs.bench.model.StopCriterion.TIME
                        ) item.spec.durationSec.toDouble() else null
                        ScatterChart(
                            series = plan.series,
                            title = plan.title,
                            unit = plan.unit,
                            xValues = plan.xValues,
                            fixedXMin = if (fixedMax != null) 0.0 else null,
                            fixedXMax = fixedMax,
                            sampleIntervalMs = d.sampleIntervalMs.toInt(),
                            caption = when (plan.unit) {
                                "%" -> String.format(
                                    "测试负载 CPU 占用（仅统计 benchmark 工作线程，不含界面渲染）；" +
                                        "曲线为各核累加口径，多线程时可超过 100%%。归一化均值 %.1f%%（%d 核）",
                                    d.cpuPercentOfCores, d.cpuCoreCount
                                )
                                "μs" -> String.format(
                                    "采样间隔 %.0f ms · 共 %d 个采样点%s · 区间平均响应延迟（增量口径）",
                                    d.sampleIntervalMs, samples, splitNote
                                )
                                else -> {
                                    // 同「测试中」页：稳态取本图曲线现算值，避免写表印出读的稳态
                                    val steady = plan.series.firstOrNull()?.values?.let { steadyStateOf(it) }
                                    String.format(
                                        "采样间隔 %.0f ms · 共 %d 个采样点%s%s",
                                        d.sampleIntervalMs, samples, splitNote,
                                        if (steady != null) String.format(" · 稳态 %.1f MB/s", steady) else ""
                                    )
                                }
                            }
                        )
                        // 写阶段详情缺失时才披露（例如该次测量的写阶段没有产出详情）
                        if (item.spec.mode == BenchmarkMode.STANDARD_READ_WRITE &&
                            writeDetail == null && d.curveWriteBwMbps.none { it > 0.0 } &&
                            plan.title.startsWith("读吞吐")
                        ) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "本次标准读写没有保存写阶段曲线，因此没有写阶段图；" +
                                    "写阶段吞吐见上方「吞吐成绩」写入值。",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ---------- 稳定性（读、写各一组） ----------
        if (d != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("稳定性与错误", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    // 抖动 / 掉速 / 超时 / 系统调用错误都是分方向的量：标准读写必须各出一套，
                    // 只印读阶段那一套会把写路径的掉速与超时问题盖掉。
                    if (writeDetail != null) {
                        Text(
                            "读阶段", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    StabilityRows(d)
                    if (writeDetail != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            "写阶段", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        StabilityRows(writeDetail)
                    }
                }
            }
        }

        // ---------- 手段披露 ----------
        if (item.ioMethod.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("测量手段披露", fontWeight = FontWeight.Bold)
                        if (isRootRun(item.spec.privilege.professional, item.privilegeMethod)) {
                            RootModeBadge()
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (item.ioMethod.isNotEmpty()) {
                        Text("I/O 通道：${item.ioMethod}", fontSize = 11.sp,
                            color = if (item.ioMethod.startsWith("O_DIRECT")) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary)
                    }
                    if (item.verifyDetail.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(item.verifyDetail, fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("引擎：${item.engineName}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 12.dp)
        )
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

/**
 * 内置微基准详情模板。
 *
 * SPEC 是纯 CPU + 内存负载，**不产生任何存储 I/O**：吞吐 / IOPS / 延迟分位 / 抖动 / 掉速 /
 * 三张散点图 / CPU 占用行对它都没有意义，因此这里只呈现 SPEC 自己的口径，
 * 也不提供分享入口（比值为本项目自标定基线，非 spec.org 官方成绩）。
 */
@Composable
private fun SpecReportDetail(
    item: BenchmarkSummary,
    hw: HardwareStorageInfo,
    onBack: () -> Unit
) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = ContentBottomInset),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---------- 标题栏（无分享入口） ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.spec.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = dateFormat.format(Date(item.timestamp)) + " · 引擎：" + item.engineName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---------- 成绩口径 ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("成绩口径", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                DetailRow("内置档位", item.spec.officialTierName)
                DetailRow("套件类型", item.spec.suiteKind.label)
                DetailRow(
                    "成绩口径",
                    "比值相对本机自标定基线，非上游官方分数，不可与 EEMBC / STREAM 等公布成绩比较"
                )
                DetailRow("工作量", item.workUnits.toString())
                DetailRow(
                    "自标定基线耗时",
                    String.format("%.1f s", item.referenceSeconds)
                )
            }
        }

        // ---------- 编译工具链：独立分区（默认收起，可展开完整串） ----------
        CompilerToolchainSection(item.compilerToolchain)

        // ---------- 输出校验 ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("输出正确性校验", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (item.verified) "通过" else "未通过",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (item.verified) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.error
                )
                if (item.verifyDetail.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = item.verifyDetail,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---------- 比值与算力 ----------
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (item.spec.tier.showsRatio) "相对自标定基线比值"
                            else "强度：${item.spec.officialTierName}（自检档，不出分）",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (item.spec.tier.showsRatio) {
                                "比值为相对本机自标定基线，非上游官方分数"
                            } else {
                                "自检档：缩小规模只验正确性，不计时、不出分"
                            },
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = if (item.spec.tier.showsRatio) String.format("%.3f", item.specRatio) else "—",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val bm = builtinMetricSpec(item.spec.builtinKernelId)
                    bm.cpuLabel?.let { lbl ->
                        Column {
                            Text(lbl, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = String.format("%.1f ${bm.cpuUnit}", item.cpuMetric * bm.cpuScale),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    bm.memLabel?.let { lbl ->
                        Column(horizontalAlignment = Alignment.End) {
                            Text(lbl, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = String.format("%.2f ${bm.memUnit}", item.memMetric * bm.memScale),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        // ---------- 设备（通用信息，与存储模板共用硬件来源） ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("测试设备", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                DetailRow("机型", hw.deviceName.ifEmpty { "—" })
                DetailRow("型号代码", hw.deviceCode.ifEmpty { "—" })
                DetailRow("系统版本", hw.androidRelease.ifEmpty { "—" })
                DetailRow("SoC", hw.socModel.ifEmpty { "—" })
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}