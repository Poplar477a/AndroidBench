package com.ufs.bench.ui.components

import androidx.compose.ui.graphics.Color
import com.ufs.bench.core.NativeDetailedStats

/*
 * 采样曲线 -> 散点图的装配逻辑（分表 / 合表）。
 *
 * 需求方口径（task-10）：
 *   · 非混合（标准读写先后两阶段 / 单项纯读 / 单项纯写）→ **恒定分表**，读写各一张图、各自独立 Y 轴；
 *     两阶段本来就是先后发生，分表后各自从 0 秒起算更清晰。
 *   · 混合（同一次压测内读写并发）→ 动态范围没超过阈值时保持「同表不同色」，
 *     超过阈值才分表（共用一根线性轴会把小量级那一方压成贴地线）。
 * 任一方向全 0 / 空数据时该方向不出图。
 */

/** 一张散点图：标题、单位、系列、以及该图自己的横坐标（可空＝用采样序号） */
data class ScatterSectionPlan(
    val title: String,
    val unit: String,
    val series: List<ScatterSeries>,
    val xValues: DoubleArray?
)

/** 曲线是否值得画：全 0 说明该方向本阶段没有实际 I/O，画出来只是一条误导性的贴地线 */
private fun DoubleArray.hasSignal(): Boolean = any { it.isFinite() && it > 0.0 }

/** 同图正数部分的动态范围（max/min）；样本不足或全为 0 时返回 1.0 */
internal fun dynamicRangeRatio(values: List<Double>): Double {
    val pos = values.filter { it.isFinite() && it > 0.0 }
    if (pos.size < 2) return 1.0
    val mn = pos.min()
    val mx = pos.max()
    return if (mn > 0.0) mx / mn else 1.0
}

/**
 * 单张图的稳态吞吐：跳过前 10% 预热后取均值（与原生 detail.steady_state_mbps 同一算法）。
 *
 * 关键差别：这里**只吃本图自己的曲线**。原生那份是「读阶段 detail 的读 + 写逐点和」的均值，
 * 按方向印在图上时，写表照抄它就会印出读的数值（也曾真的这么印过）。
 * 样本不足（≤5）时返回 null —— 宁可不写，也不编一个数出来。
 */
internal fun steadyStateOf(values: DoubleArray): Double? {
    val n = values.size
    if (n <= 5) return null
    val warm = n / 10
    var sum = 0.0
    var cnt = 0
    for (i in warm until n) {
        val v = values[i]
        if (v.isFinite()) { sum += v; cnt++ }
    }
    return if (cnt > 0) sum / cnt else null
}

/** 分表阈值：混合模式下同图量级差超过该倍数就分开画 */
private const val SPLIT_RATIO_THRESHOLD = 20.0

/**
 * 读写两方向的散点图方案。
 *
 * [isMixed] = true 表示混合模式（同一次压测内读写并发）：只有动态范围超阈值才分表；
 * false（标准读写 / 单项）恒定分表。
 */
fun planReadWriteSections(
    /** 合表时的标题，例如 "吞吐散点图（读写分色）" */
    combinedTitle: String,
    unit: String,
    /** 分表时两张图的标题，例如 "读吞吐散点图" / "写吞吐散点图"（传字面量，便于产物校验） */
    readTitle: String,
    writeTitle: String,
    readLabel: String,
    readValues: DoubleArray,
    readTime: DoubleArray,
    writeLabel: String,
    writeValues: DoubleArray,
    writeTime: DoubleArray,
    readColor: Color,
    writeColor: Color,
    isMixed: Boolean
): List<ScatterSectionPlan> {
    val read = if (readValues.hasSignal()) {
        ScatterSeries(readLabel, readValues, readColor, readTime)
    } else {
        null
    }
    val write = if (writeValues.hasSignal()) {
        ScatterSeries(writeLabel, writeValues, writeColor, writeTime)
    } else {
        null
    }
    if (read == null && write == null) return emptyList()

    // 读写口径严格分表：吞吐与延迟的读/写一律独立成图，各自采用独立量程，绝不合并同图
    val split = true

    return if (split) {
        // 分表：两张图各自独立 Y 轴，横坐标各用自己那份时间轴（写阶段从 0 起算）
        listOfNotNull(
            read?.let { ScatterSectionPlan(readTitle, unit, listOf(it), readTime) },
            write?.let { ScatterSectionPlan(writeTitle, unit, listOf(it), writeTime) }
        )
    } else {
        listOf(ScatterSectionPlan(combinedTitle, unit, listOfNotNull(read, write), readTime))
    }
}

/** 单系列方案（CPU 占用率）：走同一套结构，调用点可统一渲染 */
fun singleSectionPlan(
    title: String,
    unit: String,
    label: String,
    values: DoubleArray,
    time: DoubleArray,
    color: Color
): List<ScatterSectionPlan> = if (values.hasSignal()) {
    listOf(ScatterSectionPlan(title, unit, listOf(ScatterSeries(label, values, color, time)), time))
} else {
    emptyList()
}

/** 吞吐：读用读阶段曲线；写优先用写阶段那份 detail（标准读写），否则用同一份里的写曲线（混合） */
fun throughputSections(
    read: NativeDetailedStats,
    writeDetail: NativeDetailedStats?,
    readColor: Color,
    writeColor: Color,
    isMixed: Boolean
): List<ScatterSectionPlan> = planReadWriteSections(
    combinedTitle = "吞吐散点图（读写分色）",
    unit = "MB/s",
    readTitle = "读吞吐散点图",
    writeTitle = "写吞吐散点图",
    readLabel = "读吞吐",
    readValues = read.curveReadBwMbps,
    readTime = read.curveTimeSec,
    writeLabel = "写吞吐",
    writeValues = writeDetail?.curveWriteBwMbps ?: read.curveWriteBwMbps,
    writeTime = writeDetail?.curveTimeSec ?: read.curveTimeSec,
    readColor = readColor,
    writeColor = writeColor,
    isMixed = isMixed
)

/** 延迟：口径同 [throughputSections] */
fun latencySections(
    read: NativeDetailedStats,
    writeDetail: NativeDetailedStats?,
    readColor: Color,
    writeColor: Color,
    isMixed: Boolean
): List<ScatterSectionPlan> = planReadWriteSections(
    combinedTitle = "延迟散点图（读写分色）",
    unit = "μs",
    readTitle = "读响应延迟散点图",
    writeTitle = "写响应延迟散点图",
    readLabel = "读响应延迟",
    readValues = read.curveReadLatUs,
    readTime = read.curveTimeSec,
    writeLabel = "写响应延迟",
    writeValues = writeDetail?.curveWriteLatUs ?: read.curveWriteLatUs,
    writeTime = writeDetail?.curveTimeSec ?: read.curveTimeSec,
    readColor = readColor,
    writeColor = writeColor,
    isMixed = isMixed
)
