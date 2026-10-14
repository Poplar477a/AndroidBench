package com.ufs.bench.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/**
 * 一条散点系列。
 *
 * [values] 与横坐标一一对应：传入 [ScatterChart.xValues] 时按同一下标取时刻，
 * 未传（或长度不足）时退化为采样序号。
 */
data class ScatterSeries(
    val label: String,
    val values: DoubleArray,
    val color: Color,
    /**
     * 该系列自己的横坐标；为 null 时用 [ScatterChart.xValues]（再退化为采样序号）。
     * 标准读写是「先读后写」两个阶段，写阶段的曲线需要整体平移读阶段时长后才能与读阶段同图。
     */
    val xValues: DoubleArray? = null
)

/**
 * 通用散点图：本项目的曲线图统一由它渲染（原先的折线图组件已删除，不再并存）。
 *
 * - 以 drawCircle 绘制离散采样点，不做折线连接
 * - 带横纵坐标轴：X 为时间（秒），Y 按量纲取 1/2/5×10^n 的整步长
 * - 图下给出每个系列的最小 / 最大 / 平均值与颜色图例
 * - **Y 轴上界默认取 P99 稳健值**：延迟/吞吐里个别尖峰（实测 541040 μs）会把上界撑到几十万，
 *   其余点全被压成贴着横轴的一条线，整图看上去「什么都没画出来」；
 *   超出上界的点贴顶显示，真实最大值仍在最小/最大/平均标注里，并把截断情况写在图上。
 * - 采样点过密时按步长抽稀绘制（统计仍按全量），避免 800+ 点叠成实心带
 * - 绘图区按「刻度标签宽度 + 点半径」内缩，首末点与 y=0 上的点都不会被轴或画布边缘裁掉一半
 *
 * 颜色一律由调用方从 MaterialTheme.colorScheme 取语义角色后再传入：
 * Canvas 的绘制 lambda 不是 @Composable 作用域，硬编码颜色会让 Monet 动态取色失效。
 */
@Composable
fun ScatterChart(
    series: List<ScatterSeries>,
    title: String,
    unit: String,
    modifier: Modifier = Modifier,
    xValues: DoubleArray? = null,
    plotHeight: Int = 190,
    xAxisLabel: String = "时间 (s)",
    clampZero: Boolean = true,
    caption: String? = null,
    robustUpperBound: Boolean = true,
    fixedXMin: Double? = null,
    fixedXMax: Double? = null,
    /**
     * 采样间隔（ms）。给出且同时给了固定横轴时，抽稀步长按**整段测试的预计点数**
     * 一次算定，而不是随当前点数增长逐级跳变。
     *
     * 这是「长时间大容量测试（>200s）散点图抽搐」的根因修复：
     * 100ms 采样跑 200s 就是 2000 点，n/MAX_DRAWN 会在 5→6→7… 之间不断变化，
     * 每变一次被抽掉的点就整体错位，画面上表现为持续抖动。
     */
    sampleIntervalMs: Int = 0
) {
    val valid = series.filter { s -> s.values.any { it.isFinite() } }
    if (valid.isEmpty()) return

    // ---------- 数据范围 / 轴 / 抽稀：组合期算好，Canvas 只负责画 ----------
    val pooled = ArrayList<Double>()
    var dataMin = Double.MAX_VALUE
    var dataMax = -Double.MAX_VALUE
    for (s in valid) {
        for (v in s.values) {
            if (!v.isFinite()) continue
            pooled.add(v)
            dataMin = min(dataMin, v)
            dataMax = max(dataMax, v)
        }
    }
    if (dataMin > dataMax) return

    // 纵轴恒为线性轴（需求方已否决对数轴方案，改为「读写分表 + 各自独立量程」）。
    // 量程口径统一由 planYScale 给出：**页内散点图与结果分享卡共用同一份**，
    // 杜绝两处各自漂移（分享卡曾把下界钉死在 0，把窄幅曲线画成一条直线）。
    val yPlan = planYScale(pooled, clampZero = clampZero, targetTicks = 4, robust = robustUpperBound)
    val yScale = yPlan.scale
    val clampedCount = yPlan.aboveCount
    val clampedLowCount = yPlan.belowCount

    fun xAt(s: ScatterSeries, i: Int): Double {
        val xv = s.xValues ?: xValues
        return if (xv != null && i < xv.size && xv[i].isFinite()) xv[i] else i.toDouble()
    }

    val effectiveXMin = fixedXMin ?: run {
        var mn = Double.MAX_VALUE
        for (s in valid) {
            for (i in s.values.indices) {
                if (!s.values[i].isFinite()) continue
                mn = min(mn, xAt(s, i))
            }
        }
        if (mn.isFinite() && mn < Double.MAX_VALUE) mn else 0.0
    }

    val effectiveXMax = fixedXMax ?: run {
        var mx = -Double.MAX_VALUE
        for (s in valid) {
            for (i in s.values.indices) {
                if (!s.values[i].isFinite()) continue
                mx = max(mx, xAt(s, i))
            }
        }
        if (mx.isFinite() && mx > effectiveXMin) mx else (effectiveXMin + 1.0)
    }

    // 若指定了固定横轴范围（如定时/定容测试要求直接显示完整坐标轴），直接取整步长锁定量程，
    // 杜绝测试进行中坐标轴跳变与散点图抽搐。
    val xScale = if (fixedXMin != null && fixedXMax != null) {
        val span = max(1e-3, effectiveXMax - effectiveXMin)
        val step = niceStep(span / 4.0)
        AxisScale(effectiveXMin, effectiveXMax, step)
    } else {
        niceScale(effectiveXMin, effectiveXMax, targetTicks = 4, clampZero = false)
    }

    // 抽稀步长：
    //  · 有固定横轴 + 采样间隔时 -> 用**整段测试预计点数**算，全程恒定，绝不跳变；
    //  · 否则退回按当前点数估，并吸附到 nice 阶梯减少跳变次数。
    val plannedPoints: Int? = run {
        if (fixedXMin == null || fixedXMax == null || sampleIntervalMs <= 0) return@run null
        val span = fixedXMax - fixedXMin
        if (!span.isFinite() || span <= 0) return@run null
        ((span / (sampleIntervalMs / 1000.0)).toInt() + 2).coerceAtLeast(1)
    }
    val steps = valid.associateWith { s ->
        val actual = s.values.count { it.isFinite() }
        val basis = max(plannedPoints ?: 0, actual)
        if (plannedPoints != null) max(1, basis / MAX_DRAWN) else niceDecimationStep(basis)
    }
    val maxStep = steps.values.maxOrNull() ?: 1
    val drawnTotal = valid.sumOf { s ->
        val n = s.values.count { it.isFinite() }
        val st = steps[s] ?: 1
        if (n == 0) 0 else (n - 1) / st + 1
    }

    // 上界被截断 / 做了抽稀时都要明说，否则图上的「贴顶」会被误读成真实读数
    val axisNote = buildString {
        if (clampedCount > 0) {
            append(
                String.format(
                    Locale.US,
                    "Y 轴上界取 P99 = %s %s；高于上界的 %d 个采样点贴顶显示，实际最大 %s",
                    formatTick(yScale.hi, stepDecimals(yScale.step)), unit, clampedCount,
                    formatTick(dataMax, stepDecimals(yScale.step))
                )
            )
        }
        if (clampedLowCount > 0) {
            if (isNotEmpty()) append(" · ")
            append(
                String.format(
                    Locale.US,
                    "Y 轴下界取%s = %s %s；低于下界的 %d 个采样点贴底显示，实际最小 %s",
                    if (yPlan.robust) " P1" else "最小值",
                    formatTick(yScale.lo, stepDecimals(yScale.step)), unit, clampedLowCount,
                    formatTick(yPlan.dataMin, stepDecimals(yScale.step))
                )
            )
        }
        if (maxStep > 1) {
            if (isNotEmpty()) append(" · ")
            append("点太密，绘制时每 " + maxStep + " 点取 1（最小/最大/平均仍按全量统计）")
        }
    }

    val titleColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val axisColor = MaterialTheme.colorScheme.outline
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    // DrawScope 里不是组合作用域，TextMeasurer 必须先在组合期取好
    val textMeasurer = rememberTextMeasurer()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontSize = 12.sp, color = titleColor, fontWeight = FontWeight.SemiBold)
            Text("单位 $unit", fontSize = 10.sp, color = titleColor)
        }

        Spacer(Modifier.height(6.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(plotHeight.dp)
        ) {
            val labelStyle = TextStyle(fontSize = 9.sp, color = labelColor)
            val yTexts = yScale.ticks.map { formatTick(it, stepDecimals(yScale.step)) }
            val xTexts = xScale.ticks.map { formatTick(it, stepDecimals(xScale.step)) }

            // ---------- 2. 绘图区：刻度标签 + 点半径双向内缩 ----------
            // 半径必须计入边距，否则首末点、以及落在 y=0 上的点会被坐标轴或画布边缘裁掉一半。
            val radius = if (drawnTotal > 250) 1.8.dp.toPx() else 2.6.dp.toPx()
            val yLabelW = yTexts.maxOf { textMeasurer.measure(it, labelStyle).size.width }.toFloat()
            val xLabelH = xTexts.maxOf { textMeasurer.measure(it, labelStyle).size.height }.toFloat()
            val lastXHalf = textMeasurer.measure(xTexts.last(), labelStyle).size.width / 2f

            val padLeft = yLabelW + 8.dp.toPx() + radius
            val padRight = max(6.dp.toPx(), lastXHalf) + radius
            val padTop = 4.dp.toPx() + radius
            val padBottom = xLabelH + 8.dp.toPx() + radius

            val plotW = (size.width - padLeft - padRight).coerceAtLeast(1f)
            val plotH = (size.height - padTop - padBottom).coerceAtLeast(1f)
            val plotLeft = padLeft
            val plotRight = padLeft + plotW
            val plotTop = padTop
            val plotBottom = padTop + plotH

            val ySpan = (yScale.hi - yScale.lo).coerceAtLeast(1e-12)
            val xSpan = (xScale.hi - xScale.lo).coerceAtLeast(1e-12)
            fun toX(v: Double) = plotLeft + (((v - xScale.lo) / xSpan).toFloat() * plotW)
            fun toY(v: Double) = plotBottom - (((v - yScale.lo) / ySpan).toFloat() * plotH)

            // ---------- 3. 网格 ----------
            for (t in yScale.ticks) {
                val y = toY(t)
                drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1f)
            }
            for (t in xScale.ticks) {
                val x = toX(t)
                drawLine(gridColor, Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1f)
            }

            // ---------- 4. 坐标轴与刻度线 ----------
            drawLine(axisColor, Offset(plotLeft, plotTop), Offset(plotLeft, plotBottom), strokeWidth = 1.5f)
            drawLine(axisColor, Offset(plotLeft, plotBottom), Offset(plotRight, plotBottom), strokeWidth = 1.5f)
            val tick = 3.dp.toPx()
            for (t in yScale.ticks) {
                val y = toY(t)
                drawLine(axisColor, Offset(plotLeft - tick, y), Offset(plotLeft, y), strokeWidth = 1f)
            }
            for (t in xScale.ticks) {
                val x = toX(t)
                drawLine(axisColor, Offset(x, plotBottom), Offset(x, plotBottom + tick), strokeWidth = 1f)
            }

            // ---------- 5. 散点（抽稀 + 超出上界贴顶） ----------
            for (s in valid) {
                val n = s.values.size
                val st = steps[s] ?: 1
                val lastFinite = s.values.indexOfLast { it.isFinite() }
                var i = 0
                while (i < n) {
                    val v = s.values[i]
                    if (v.isFinite()) {
                        val px = toX(xAt(s, i))
                        // 超出量程的点贴顶 / 贴底显示：不画到轴外，也不让个别点压扁整图
                        val py = when {
                            v > yScale.hi -> plotTop
                            v < yScale.lo -> plotBottom
                            else -> toY(v)
                        }
                        if (px >= plotLeft - 0.5f && px <= plotRight + 0.5f) {
                            drawCircle(color = s.color, radius = radius, center = Offset(px, py))
                        }
                    }
                    i += st
                }
                // 末点必画：抽稀不能把最后一段的走势丢掉
                if (lastFinite >= 0 && st > 1) {
                    val v = s.values[lastFinite]
                    val px = toX(xAt(s, lastFinite))
                    val py = when {
                        v > yScale.hi -> plotTop
                        v < yScale.lo -> plotBottom
                        else -> toY(v)
                    }
                    if (px >= plotLeft - 0.5f && px <= plotRight + 0.5f) {
                        drawCircle(color = s.color, radius = radius, center = Offset(px, py))
                    }
                }
            }

            // ---------- 6. 刻度数值标签 ----------
            yScale.ticks.forEachIndexed { i, t ->
                val layout = textMeasurer.measure(yTexts[i], labelStyle)
                val labelY = (toY(t) - layout.size.height / 2f)
                    .coerceIn(0f, size.height - layout.size.height)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(plotLeft - 6.dp.toPx() - layout.size.width, labelY)
                )
            }
            xScale.ticks.forEachIndexed { i, t ->
                val layout = textMeasurer.measure(xTexts[i], labelStyle)
                val labelX = (toX(t) - layout.size.width / 2f)
                    .coerceIn(0f, size.width - layout.size.width)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(labelX, plotBottom + 4.dp.toPx())
                )
            }
        }

        if (axisNote.isNotEmpty()) {
            Text(
                text = axisNote,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                fontSize = 9.sp,
                color = labelColor
            )
        }

        Text(
            text = xAxisLabel,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            fontSize = 9.sp,
            color = labelColor,
            textAlign = TextAlign.Center
        )

        // ---------- 图例 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            valid.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(s.color)
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(s.label, fontSize = 10.sp, color = s.color, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // ---------- 图下标注：最小 / 最大 / 平均 ----------
        valid.forEach { s ->
            val finite = s.values.filter { it.isFinite() }
            if (finite.isEmpty()) return@forEach
            Spacer(Modifier.height(3.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(s.label + "  ", fontSize = 10.sp, color = s.color, fontWeight = FontWeight.SemiBold)
                Text(
                    text = String.format(
                        Locale.US,
                        "最小 %.2f · 最大 %.2f · 平均 %.2f %s",
                        finite.min(), finite.max(), finite.average(), unit
                    ),
                    fontSize = 10.sp,
                    color = labelColor
                )
            }
        }

        caption?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 10.sp, color = labelColor)
        }
    }
}

/** 单系列最多绘制的点数；超过则按步长抽稀（800+ 点在手机屏幕上只会叠成实心带） */
private const val MAX_DRAWN = 400

/**
 * 抽稀步长吸附到 1/2/3/4/5/10/20/25/50/100… 这类"人类习惯"的阶梯。
 *
 * 用 n/MAX_DRAWN 直接取整时，n 每 +1 都可能让步长变化（400→401 就让 1 变 2），
 * 被抽掉的点集整体错位，视觉上就是持续抽搐。吸附到阶梯后，
 * 步长只在跨越量级时才变一次。
 */
internal fun niceDecimationStep(n: Int): Int {
    if (n <= MAX_DRAWN) return 1
    val ladder = intArrayOf(2, 3, 4, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000)
    for (st in ladder) {
        if (n / st <= MAX_DRAWN) return st
    }
    return maxOf(1, (n + MAX_DRAWN - 1) / MAX_DRAWN)
}



/** 升序数组的分位值（[q] ∈ [0,1]） */
internal fun quantile(sorted: DoubleArray, q: Double): Double {
    if (sorted.isEmpty()) return Double.NaN
    if (sorted.size == 1) return sorted[0]
    val pos = (sorted.size - 1) * q.coerceIn(0.0, 1.0)
    val loIdx = floor(pos).toInt()
    val hiIdx = ceil(pos).toInt()
    if (loIdx == hiIdx) return sorted[loIdx]
    val frac = pos - loIdx
    return sorted[loIdx] * (1 - frac) + sorted[hiIdx] * frac
}

/** 坐标轴量程：lo/hi 为取整后的边界，step 为「好看」的刻度步长 */
internal class AxisScale(val lo: Double, val hi: Double, val step: Double) {
    /** 落在 [lo, hi] 内的刻度值 */
    val ticks: List<Double> = buildList {
        var v = lo
        var guard = 0
        while (v <= hi + step * 1e-6 && guard < 16) {
            add(v)
            v += step
            guard++
        }
    }
}

/**
 * 把原始步长吸附到 1 / 2 / 5 × 10^n 上 —— 直接拿最大值当刻度会得到 3.7、7.4 这类无法读数的标签。
 */
internal fun niceStep(rawStep: Double): Double {
    if (!rawStep.isFinite() || rawStep <= 0.0) return 1.0
    val exp = floor(log10(rawStep))
    val base = 10.0.pow(exp)
    val f = rawStep / base
    val nice = when {
        f <= 1.0 -> 1.0
        f <= 2.0 -> 2.0
        f <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * base
}

/**
 * 依数据范围取「好看」量程。
 * [clampZero] 为 true 时下界不越过 0（吞吐 / 延迟 / 占用率都是非负量纲）。
 */
internal fun niceScale(
    dataMin: Double,
    dataMax: Double,
    targetTicks: Int = 4,
    clampZero: Boolean = false
): AxisScale {
    var lo = dataMin
    var hi = dataMax
    if (!lo.isFinite() || !hi.isFinite()) {
        lo = 0.0
        hi = 1.0
    }
    if (clampZero) {
        lo = max(0.0, lo)
        hi = max(0.0, hi)
    }
    if (hi - lo < 1e-9) {
        // 全平 / 单点：给一个对称的假想区间，否则整图退化成一条线
        val pad = if (abs(hi) < 1e-9) 1.0 else abs(hi) * 0.1
        lo -= pad
        hi += pad
        if (clampZero) lo = max(0.0, lo)
    }
    val step = niceStep((hi - lo) / max(1, targetTicks))
    val nlo = floor(lo / step) * step
    val nhi = ceil(hi / step) * step
    val fixedLo = if (clampZero) max(0.0, nlo) else nlo
    val fixedHi = if (nhi <= fixedLo + 1e-12) fixedLo + step else nhi
    return AxisScale(fixedLo, fixedHi, step)
}

/** 稳健上界所需的最小样本数：低于此数不做分位裁剪，直接用真实极值 */
private const val ROBUST_MIN_SAMPLES = 20

/**
 * 纵轴量程方案：量程本身 + 真实极值 + 贴边（截断）计数。
 *
 * **页内散点图与结果分享卡共用这一份口径**，否则两张图的纵轴会各自漂移：
 * 分享卡曾长期把下界钉死在 0，于是 4000 μs 上下的读延迟、1990–2010 MB/s 的带宽
 * 被画成贴着量程顶端的一条直线 —— 看图的人只会以为「什么都没测出来」。
 */
internal data class YScalePlan(
    val scale: AxisScale,
    val dataMin: Double,
    val dataMax: Double,
    val belowCount: Int,
    val aboveCount: Int,
    /** 上界是否走了 P99 稳健分位（样本足够）；否则用的是真实最大值 */
    val robust: Boolean
)

/**
 * 依数据取「好看」且**有分辨率**的纵轴量程。
 *
 * 上界：样本足够（≥ [ROBUST_MIN_SAMPLES]）时取 P99 —— 个别尖峰（实测 541040 μs）
 * 会把量程撑到几十万，其余点全被压成贴轴的一条线。
 *
 * 下界：取**真实最小值**（不下压到 0，也不做分位裁剪）。这是本函数存在的关键：
 * 只要下界被钉死在 0，窄幅曲线（读延迟 3850–4050 μs、带宽 1990–2010 MB/s）
 * 就必然被画成一条近似直线；而用 P1 之类的分位当下界，又会把真实的低谷样本
 * 贴成一条假的底线。两者都会让图说谎，故取真实最小值。
 *
 * 贴边点仍按真实值画在边界上，并在图下如实注明截断数量与真实极值（不粉饰）。
 */
internal fun planYScale(
    values: List<Double>,
    clampZero: Boolean = true,
    targetTicks: Int = 4,
    robust: Boolean = true
): YScalePlan {
    val finite = values.filter { it.isFinite() }
    if (finite.isEmpty()) {
        return YScalePlan(niceScale(0.0, 1.0, targetTicks, clampZero), 0.0, 0.0, 0, 0, false)
    }
    val sorted = finite.toDoubleArray().apply { sort() }
    val dataMin = sorted.first()
    val dataMax = sorted.last()
    val useRobust = robust && sorted.size >= ROBUST_MIN_SAMPLES
    val hiRaw = if (useRobust) quantile(sorted, 0.99) else dataMax
    val scale = niceScale(dataMin, max(hiRaw, dataMin), targetTicks = targetTicks, clampZero = clampZero)
    return YScalePlan(
        scale = scale,
        dataMin = dataMin,
        dataMax = dataMax,
        belowCount = sorted.count { it < scale.lo },
        aboveCount = sorted.count { it > scale.hi },
        robust = useRobust
    )
}

/** 步长需要几位小数才不至于把 0.5 写成 1 */
internal fun stepDecimals(step: Double): Int {
    var d = 0
    var s = step
    while (d < 4 && abs(s - round(s)) > 1e-6) {
        s *= 10.0
        d++
    }
    return d
}

/** 刻度标签：量级过大时省掉小数，避免轴标签互相挤压 */
internal fun formatTick(v: Double, decimals: Int): String {
    if (abs(v) < 1e-9) return "0"
    if (abs(v) >= 100000.0) return String.format(Locale.US, "%.0f", v)
    if (abs(v) >= 10000.0) return String.format(Locale.US, "%.1fk", v / 1000.0)
    return String.format(Locale.US, "%." + decimals + "f", v)
}
