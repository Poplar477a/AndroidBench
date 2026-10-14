package com.ufs.bench.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import com.ufs.bench.hardware.HardwareStorageInfo
import com.ufs.bench.model.BenchmarkSummary
import com.ufs.bench.ui.components.ScatterSeries
import com.ufs.bench.ui.components.isRootRun
import com.ufs.bench.ui.components.formatTick
import com.ufs.bench.ui.components.latencySections
import com.ufs.bench.ui.components.planYScale
import com.ufs.bench.ui.components.singleSectionPlan
import com.ufs.bench.ui.components.steadyStateOf
import com.ufs.bench.ui.components.stepDecimals
import com.ufs.bench.ui.components.throughputSections
import kotlin.math.ceil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 测试结果分享卡 —— 独立渲染，不抓界面截图。
 *
 * 为什么不直接截屏：
 *   · 截屏只能拿到当前视口，内容会被滚动位置截断
 *   · 会带上底部导航栏与状态栏，分享出去不像一份结果
 *   · 曲线等控件在不同屏幕上比例不一致
 * 因此这里用 Canvas 单独绘制一张定宽的结果卡，内容与排版完全可控。
 */
object ResultCardRenderer {

    private const val W = 1080
    private const val PAD = 56f

    // 配色与 App 深色主题保持一致
    private const val BG = 0xFF121216.toInt()
    private const val CARD = 0xFF1A1A22.toInt()
    private const val CARD2 = 0xFF23232E.toInt()
    private const val ACCENT = 0xFF00E5FF.toInt()
    private const val ACCENT2 = 0xFFFF5252.toInt()
    private const val GOLD = 0xFFFFD54F.toInt()
    private const val GREEN = 0xFF69F0AE.toInt()
    private const val TEXT = 0xFFECEFF1.toInt()
    private const val DIM = 0xFF90A4AE.toInt()

    /**
     * 渲染结果卡；**内置微基准一律返回 null**。
     *
     * SPEC 比值是本项目自标定基线、不是 spec.org 官方成绩，分享出去容易被误当成官方分数，
     * 因此 SPEC 结果不提供任何分享入口；这里再兜一层，避免被误调用时产出半成品图。
     */
    fun render(
        context: Context,
        summary: BenchmarkSummary,
        hw: HardwareStorageInfo,
        professionalMode: Boolean
    ): Bitmap? {
        val spec = summary.spec
        if (spec.portedVersion) return null
        val detail = summary.detail

        // 不预估高度：换行数量随内容变化，任何常量估算都会在某个设备上错位
        // （曾经因此让页脚压在"测量手段披露"上）。
        // 改为在富余画布上绘制，最后按实际用到的 y 裁剪。
        val maxH = 6000
        val full = Bitmap.createBitmap(W, maxH, Bitmap.Config.ARGB_8888)
        val c = Canvas(full)
        c.drawColor(BG)

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        var y = 0f

        // ---------------- 头部 ----------------
        val headerRect = RectF(0f, 0f, W.toFloat(), 200f)
        p.shader = LinearGradient(
            0f, 0f, W.toFloat(), 200f,
            Color.parseColor("#1B2A3A"), Color.parseColor("#121216"),
            Shader.TileMode.CLAMP
        )
        c.drawRect(headerRect, p)
        p.shader = null

        p.color = ACCENT; p.textSize = 46f; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText("存储性能测试报告", PAD, 96f, p)

        p.color = DIM; p.textSize = 26f; p.typeface = Typeface.DEFAULT
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(summary.timestamp))
        // 版本与时间都放头部，分享出去的图自带可追溯信息
        val version = appVersion(context)
        c.drawText("UFS Benchmark v$version · $stamp", PAD, 140f, p)
        p.textSize = 22f
        c.drawText("v$version", W - PAD - p.measureText("v$version"), 140f, p)

        // root 模式标记：整段「测试手段」披露已按需求删除，只留一枚极短徽标，
        // 让人知道本次读数是在 root（专业手段）下取得的。
        if (isRootRun(summary.spec.privilege.professional, summary.privilegeMethod)) {
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = 22f
            val badgeText = "ROOT 模式"
            val bw = p.measureText(badgeText) + 36f
            val rect = RectF(W - PAD - bw, 44f, W - PAD, 92f)
            p.color = 0xFF3A2E12.toInt()
            c.drawRoundRect(rect, 12f, 12f, p)
            p.color = GOLD
            c.drawText(badgeText, rect.left + 18f, 78f, p)
            p.typeface = Typeface.DEFAULT
            p.textSize = 22f
        }

        y = 236f

        // ---------------- 设备 ----------------
        y = section(c, p, "测试设备", y)
        // 全部字段原样输出，不做拼接与归类：
        // 各厂商属性格式不一，任何加工都可能在未知设备上出错。
        val pro = professionalMode && hw.hasRootAccess && hw.sysfsReadable
        // 数据源与 Android「设置」同源：userdata 分区的 StatFs（与设备 df /data 实测一致）。
        // 口径必须写全：孤零零一个百分比会被误读成「可用」或「整机标称容量的比例」，
        // 因此显式写「已用 / 共」，并另给「可用空间」—— 大容量测试能不能落下文件就看它。
        var storageAvailable = "—"
        val storageUsage = runCatching {
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val total = stat.totalBytes
            val avail = stat.availableBytes
            val used = total - avail
            if (total > 0) {
                val gib = 1024.0 * 1024.0 * 1024.0
                storageAvailable = String.format(Locale.US, "%.1f GiB", avail.toDouble() / gib)
                String.format(Locale.US, "%.1f%%（userdata 已用 %.1f / 共 %.1f GiB）",
                    (used.toDouble() / total.toDouble()) * 100.0,
                    used.toDouble() / gib, total.toDouble() / gib)
            } else "—"
        }.getOrDefault("—")

        val deviceRows = mutableListOf(
            "机型" to hw.deviceName.ifEmpty { hw.deviceCode }.ifEmpty { "—" },
            "型号代码" to hw.deviceCode.ifEmpty { "—" },
            "系统版本" to hw.androidRelease.ifEmpty { "—" },
            "SoC" to hw.socModel.ifEmpty { "—" },
            "内部存储占用率" to storageUsage,
            "内部存储可用" to storageAvailable
        )
        if (pro) {
            deviceRows += "存储厂商" to hw.storageVendor.ifEmpty { "—" }
            deviceRows += "存储型号" to hw.storageType.ifEmpty { "—" }
            deviceRows += "块设备" to hw.blockDevice.ifEmpty { "—" }
            deviceRows += "I/O 调度器" to hw.ioScheduler.ifEmpty { "—" }
            deviceRows += "硬件队列深度" to hw.queueDepthLimit.toString()
        } else {
            deviceRows += "存储信息" to "常规模式未读取"
        }
        // 结果卡不再输出"检测环境/模拟器"（按需求去除）。
        // 模拟器成绩的有效性提示仍保留在应用内的仪表盘环境警告中。
        y = cardRow(c, p, y, deviceRows, wrap = true)
        // 口径说明：不写这一行，用户拿系统设置的"已用 26 GB"来对，就会以为本报告算错了。
        // 实测差异来源：设置按「标称容量 − 可用空间」计，含系统分区与格式化开销；
        // 本行只统计 userdata 分区（测试文件能落在哪，就看这个分区）。
        run {
            p.color = DIM
            p.textSize = 18f
            p.typeface = Typeface.DEFAULT
            val noteText = "存储口径：以上为 userdata 分区（/data）。系统设置里的「已用」按" +
                "标称容量 − 可用空间计算，另含系统分区与格式化开销，故数值更大。"
            for (ln in wrapText(noteText, p, W - 2 * PAD - 16f)) {
                c.drawText(ln, PAD + 8f, y, p)
                y += 24f
            }
            y += 12f
        }

        // ---------------- 主成绩 ----------------
        y = section(c, p, "吞吐成绩", y)
        val boxH = 168f
        val boxRect = RectF(PAD, y, W - PAD, y + boxH)
        p.color = CARD; c.drawRoundRect(boxRect, 20f, 20f, p)

        p.color = DIM; p.textSize = 24f; p.typeface = Typeface.DEFAULT
        c.drawText("读取", PAD + 36f, y + 56f, p)
        c.drawText("写入", W / 2f + 20f, y + 56f, p)

        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 56f
        p.color = ACCENT
        c.drawText(fmt(summary.readMbps) + " MB/s", PAD + 36f, y + 122f, p)
        p.color = ACCENT2
        c.drawText(fmt(summary.writeMbps) + " MB/s", W / 2f + 20f, y + 122f, p)

        p.textSize = 22f; p.typeface = Typeface.DEFAULT; p.color = DIM
        c.drawText("IOPS " + summary.readIops, PAD + 36f, y + 154f, p)
        c.drawText("IOPS " + summary.writeIops, W / 2f + 20f, y + 154f, p)

        y += boxH + 40f

        // ---------------- 测试条件 ----------------
        y = section(c, p, "测试条件", y)
        val cond = buildList {
            // SPEC 已在 render() 入口直接返回 null，这里只保留存储测试的分支
            add("读写模式" to when (spec.mode) {
                com.ufs.bench.model.BenchmarkMode.STANDARD_READ_WRITE -> "标准读写"
                com.ufs.bench.model.BenchmarkMode.MIXED_SIMULTANEOUS -> "独立混合"
                com.ufs.bench.model.BenchmarkMode.READ_ONLY -> "单项纯读"
                else -> "单项纯写"
            })
            val dsMb = spec.fileSizeMb
            val dsText = if (dsMb >= 1024) String.format(Locale.US, "%.1f GB (%d MB)", dsMb / 1024.0, dsMb) else "$dsMb MB"
            val addrText = if (dsMb >= 1024) String.format(Locale.US, "0 ~ %.1f GB (%s寻道)", dsMb / 1024.0, if (spec.percentRandom >= 50) "随机" else "顺序")
                           else "0 ~ ${dsMb} MB (${if (spec.percentRandom >= 50) "随机" else "顺序"}寻道)"
            add("测试数据集" to dsText)
            add("地址范围" to addrText)
            add("块大小 / 访问方式" to "${spec.blockSizeLabel} · " +
                (if (spec.percentRandom >= 50) "随机" else "顺序"))
            add("并发" to spec.concurrencyLabel)
            add("终止准则" to if (spec.stopCriterion ==
                    com.ufs.bench.model.StopCriterion.CAPACITY)
                "按容量 ${spec.targetCapacityMb} MB" else "按时间 ${spec.durationSec}s")
            add("缓存模式" to spec.cacheMode.label)
        }
        y = cardRow(c, p, y, cond)

        // ---------------- 延迟分位（专业模式：读写严格分开） ----------------
        if (detail != null) {
            val writer = summary.writeDetail
            val hasRead = summary.readMbps > 0 || detail.p50LatencyUs > 0
            val hasWrite = summary.writeMbps > 0 || (writer != null && writer.p50LatencyUs > 0)

            if (hasRead) {
                y = section(c, p, if (hasWrite) "读延迟分位 (μs)" else "I/O 延迟分位 (μs)", y)
                val r = RectF(PAD, y, W - PAD, y + 150f)
                p.color = CARD; c.drawRoundRect(r, 20f, 20f, p)
                val cells = listOf(
                    "P50" to detail.p50LatencyUs, "P95" to detail.p95LatencyUs,
                    "P99" to detail.p99LatencyUs, "P99.9" to detail.p999LatencyUs,
                    "MAX" to detail.maxLatencyUs
                )
                val cw = (W - 2 * PAD) / cells.size
                cells.forEachIndexed { i, (label, v) ->
                    val cx = PAD + cw * i + cw / 2f
                    p.color = DIM; p.textSize = 22f; p.typeface = Typeface.DEFAULT
                    p.textAlign = Paint.Align.CENTER
                    c.drawText(label, cx, y + 58f, p)
                    p.color = if (label == "P99.9" || label == "MAX") GOLD else ACCENT
                    p.textSize = 36f; p.typeface = Typeface.DEFAULT_BOLD
                    c.drawText(fmt0(v), cx, y + 112f, p)
                }
                p.textAlign = Paint.Align.LEFT
                y += 150f + 20f
            }

            if (hasWrite && writer != null) {
                y = section(c, p, "写延迟分位 (μs)", y)
                val r = RectF(PAD, y, W - PAD, y + 150f)
                p.color = CARD; c.drawRoundRect(r, 20f, 20f, p)
                val cells = listOf(
                    "P50" to writer.p50LatencyUs, "P95" to writer.p95LatencyUs,
                    "P99" to writer.p99LatencyUs, "P99.9" to writer.p999LatencyUs,
                    "MAX" to writer.maxLatencyUs
                )
                val cw = (W - 2 * PAD) / cells.size
                cells.forEachIndexed { i, (label, v) ->
                    val cx = PAD + cw * i + cw / 2f
                    p.color = DIM; p.textSize = 22f; p.typeface = Typeface.DEFAULT
                    p.textAlign = Paint.Align.CENTER
                    c.drawText(label, cx, y + 58f, p)
                    p.color = if (label == "P99.9" || label == "MAX") GOLD else ACCENT2
                    p.textSize = 36f; p.typeface = Typeface.DEFAULT_BOLD
                    c.drawText(fmt0(v), cx, y + 112f, p)
                }
                p.textAlign = Paint.Align.LEFT
                y += 150f + 20f
            }
            y += 20f

            // ---------------- 三张散点图（与页内视觉语言一致） ----------------
            // 曲线口径完全复用 ui/components 的 CurveSeries：读=ACCENT、写=ACCENT2，
            // 标准读写的写阶段按读阶段时长平移时间轴（不在这里重写第二份逻辑）。
            val readColor = androidx.compose.ui.graphics.Color(ACCENT)
            val writeColor = androidx.compose.ui.graphics.Color(ACCENT2)
            val cpuColor = androidx.compose.ui.graphics.Color(GOLD)

            // 与页内同一套「分表」口径：非混合（标准读写两阶段 / 单项）恒定分表，
            // 混合模式动态范围超 20 倍才分表；每张图各自独立 Y 轴。
            val plans = throughputSections(
                detail, writer, readColor, writeColor,
                spec.mode == com.ufs.bench.model.BenchmarkMode.MIXED_SIMULTANEOUS
            ) + latencySections(
                detail, writer, readColor, writeColor,
                spec.mode == com.ufs.bench.model.BenchmarkMode.MIXED_SIMULTANEOUS
            ) + singleSectionPlan(
                "CPU 占用率散点图（仅 benchmark 工作线程，曲线为各核累加）",
                "%", "CPU 占用率", detail.curveCpuPercent, detail.curveTimeSec, cpuColor
            )
            plans.forEach { plan ->
                // footer 必须来自**本图自己的曲线**：原来直接印 detail.steadyStateMbps（读阶段口径），
                // 于是写吞吐图右下角写着读的稳态值（实测 1968.8 MB/s 出现在 884.8 MB/s 的图上）。
                val steady = plan.series.firstOrNull()?.values?.let { steadyStateOf(it) }
                val footer = when (plan.unit) {
                    "%" -> "归一化均值 " + fmt(detail.cpuPercentOfCores) + "%（" +
                        detail.cpuCoreCount + " 核）"
                    "MB/s" -> steady?.let { "稳态 " + fmt(it) + " MB/s" }
                    else -> null
                }
                y = scatterSection(
                    c, p, y,
                    plan.title + "（采样 " + fmt0(detail.sampleIntervalMs) + " ms）",
                    plan.series,
                    plan.unit,
                    footer = footer
                )
            }

            // ---------------- 稳定性与错误（读、写各一组） ----------------
            // 抖动 / 掉速 / 超时 / 系统调用错误都是分方向的量：写要等 FTL 回收与落盘确认，
            // 掉速与超时事件通常明显多于读，只印读阶段那一套会把写路径的问题盖掉。
            y = section(c, p, "稳定性与错误", y)
            y = stabilityCard(c, p, y, if (writer != null) "读阶段" else null, detail)
            if (writer != null) {
                y = stabilityCard(c, p, y, "写阶段", writer)
            }
        }

        // ---------------- 手段披露 ----------------
        y = section(c, p, "测量手段披露", y)
        val disc = buildList {
            if (summary.ioMethod.isNotEmpty()) add("I/O 通道" to summary.ioMethod)
            if (summary.verifyDetail.isNotEmpty()) add("数据校验" to if (summary.verified) "通过（无异常）" else "未通过")
            // 测试负载 CPU 占用：归一化口径；SPEC 路径不埋点，故不显示，避免 0% 误导
            if (professionalMode && detail != null) {
                add("CPU 占用（测试负载）" to String.format(Locale.US, "%.1f%%",
                    detail.cpuPercentOfCores))
            }
        }
        y = cardRow(c, p, y, disc, wrap = true)

        // ---------------- 页脚（紧跟内容，不再按固定底部定位） ----------------
        y += 26f
        p.color = DIM; p.textSize = 20f; p.typeface = Typeface.DEFAULT
        c.drawText("UFS Benchmark v$version · $stamp", PAD, y, p)
        y += 34f
        c.drawText("成绩受温度、后台负载与剩余空间影响，仅代表本次测量", PAD, y, p)
        y += 40f

        // 按实际用到的纵向范围裁剪，去掉下方空白
        val outH = y.toInt().coerceIn(600, maxH)
        return Bitmap.createBitmap(full, 0, 0, W, outH)
    }

    // ---------------- 绘制辅助 ----------------

    /**
     * 稳定性与错误卡片（单个方向的 detail）。
     *
     * 读、写各画一张：抖动 / 掉速 / 超时 / 系统调用错误都带方向性，
     * 写路径要等 FTL 回收与落盘确认，掉速与超时事件通常明显多于读。
     */
    private fun stabilityCard(
        c: Canvas,
        p: Paint,
        y: Float,
        dirLabel: String?,
        d: com.ufs.bench.core.NativeDetailedStats
    ): Float {
        var cy = y
        if (dirLabel != null) {
            p.color = ACCENT
            p.textSize = 22f
            p.typeface = Typeface.DEFAULT_BOLD
            p.textAlign = Paint.Align.LEFT
            c.drawText(dirLabel, PAD + 8f, cy, p)
            cy += 30f
        }
        val stab = RectF(PAD, cy, W - PAD, cy + 110f)
        p.style = Paint.Style.FILL
        p.color = CARD
        c.drawRoundRect(stab, 20f, 20f, p)
        val cols = listOf(
            "抖动" to String.format(Locale.US, "%.2f%%", d.jitterPercent),
            "掉速事件" to d.dropEvents.toString(),
            "I/O 超时" to d.timeoutCount.toString(),
            "系统调用错误" to d.ioErrorCount.toString()
        )
        val cw2 = (W - 2 * PAD) / cols.size
        cols.forEachIndexed { i, (label, v) ->
            val cx = PAD + cw2 * i + cw2 / 2f
            p.textAlign = Paint.Align.CENTER
            p.color = DIM; p.typeface = Typeface.DEFAULT; p.textSize = 20f
            c.drawText(label, cx, cy + 48f, p)
            p.color = if (label != "抖动" && v != "0") GOLD else TEXT
            p.typeface = Typeface.DEFAULT_BOLD; p.textSize = 32f
            c.drawText(v, cx, cy + 92f, p)
        }
        p.textAlign = Paint.Align.LEFT
        return cy + 110f + 40f
    }

    private fun section(c: Canvas, p: Paint, title: String, y: Float): Float {
        p.color = DIM; p.textSize = 24f; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText(title, PAD, y, p)
        return y + 28f
    }

    private fun cardRow(
        c: Canvas, p: Paint, y: Float,
        rows: List<Pair<String, String>>,
        wrap: Boolean = false
    ): Float {
        var h = 24f
        rows.forEach { (_, v) ->
            h += if (wrap) lineHeight(p, v, W - 2 * PAD - 340f) + 16f else 44f
        }
        h += 16f
        val r = RectF(PAD, y, W - PAD, y + h)
        p.color = CARD; c.drawRoundRect(r, 20f, 20f, p)

        var cy = y + 46f
        rows.forEach { (k, v) ->
            p.color = DIM; p.textSize = 22f; p.typeface = Typeface.DEFAULT
            c.drawText(k, PAD + 28f, cy, p)
            p.color = TEXT; p.typeface = Typeface.DEFAULT_BOLD
            p.textAlign = Paint.Align.RIGHT
            if (wrap) {
                val lines = wrapText(v, p, W - 2 * PAD - 340f)
                lines.forEachIndexed { i, ln ->
                    c.drawText(ln, W - PAD - 28f, cy + i * 30f, p)
                }
                cy += lines.size * 30f + 16f
            } else {
                c.drawText(v, W - PAD - 28f, cy, p)
                cy += 44f
            }
            p.textAlign = Paint.Align.LEFT
        }
        return y + h + 34f
    }

    private fun lineHeight(p: Paint, text: String, maxW: Float): Float =
        wrapText(text, p, maxW).size * 30f

    private fun wrapText(text: String, p: Paint, maxW: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        if (p.measureText(text) <= maxW) return listOf(text)
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (p.measureText(sb.toString()) > maxW) {
                out.add(sb.dropLast(1).toString())
                sb.clear()
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /**
     * 分享卡上的一张散点图：坐标轴 + 抽稀散点 + 图例 + 每系列 最小/最大/平均，返回本节末的 y。
     *
     * 系列装配（读写分色、标准读写两阶段时间轴平移）直接复用 ui/components 的 CurveSeries，
     * 图表参数（P99 上界、抽稀、贴顶）与页内 ScatterChart 保持一致；
     * 结果卡本身仍是 android.graphics 位图，不涉及 Compose 绘制。
     */
    private fun scatterSection(
        c: Canvas, p: Paint, y: Float,
        title: String,
        series: List<ScatterSeries>,
        unit: String,
        footer: String? = null
    ): Float {
        // 全 0 的系列说明该方向没有实际 I/O，画出来只是贴地假线
        val valid = series.filter { s -> s.values.any { it.isFinite() && it > 0.0 } }
        if (valid.isEmpty()) return y

        var cy = section(c, p, title, y)
        val chartH = 250f
        val cardRect = RectF(PAD, cy, W - PAD, cy + chartH)
        p.style = Paint.Style.FILL
        p.color = CARD
        c.drawRoundRect(cardRect, 20f, 20f, p)
        val plot = RectF(PAD + 74f, cy + 22f, W - PAD - 20f, cy + chartH - 46f)

        // 值域口径与页内 ScatterChart 完全同源（planYScale：下界真实最小值、上界 P99）。
        // 历史缺陷：这里曾写死 niceScale(0.0, P99)，下界恒为 0，于是读延迟（≈4000 μs）
        // 与带宽（≈2000 MB/s）全被画成贴着量程顶端的一条直线 —— 分享卡看上去像没测出东西。
        val pooled = ArrayList<Double>()
        for (s in valid) for (v in s.values) if (v.isFinite()) pooled.add(v)
        val yPlan = planYScale(pooled, clampZero = true, targetTicks = 4, robust = true)
        val scale = yPlan.scale
        val ySpan = (scale.hi - scale.lo).coerceAtLeast(1e-12)
        fun toY(v: Double) = plot.bottom - (((v - scale.lo) / ySpan).toFloat() * plot.height())

        // 时间轴：各系列自带横坐标（写阶段已在 CurveSeries 里平移过），取并集
        var xMin = Double.MAX_VALUE
        var xMax = -Double.MAX_VALUE
        for (s in valid) {
            val xs = s.xValues
            if (xs == null) {
                xMin = minOf(xMin, 0.0); xMax = maxOf(xMax, (s.values.size - 1).toDouble())
            } else {
                for (v in xs) if (v.isFinite()) { xMin = minOf(xMin, v); xMax = maxOf(xMax, v) }
            }
        }
        if (xMin > xMax) { xMin = 0.0; xMax = 1.0 }
        val xSpan = (xMax - xMin).coerceAtLeast(1e-9)
        fun toX(v: Double) = plot.left + (((v - xMin) / xSpan).toFloat() * plot.width())

        // 网格 + 左 / 下坐标轴
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = 0xFF2C2C34.toInt()
        for (t in scale.ticks) {
            val yy = toY(t)
            c.drawLine(plot.left, yy, plot.right, yy, p)
        }
        p.color = 0xFF4A4A56.toInt()
        c.drawLine(plot.left, plot.top, plot.left, plot.bottom, p)
        c.drawLine(plot.left, plot.bottom, plot.right, plot.bottom, p)
        p.style = Paint.Style.FILL

        // Y 轴刻度数值（与页内同源：niceScale + formatTick）
        p.color = DIM
        p.textSize = 18f
        p.typeface = Typeface.DEFAULT
        p.textAlign = Paint.Align.RIGHT
        for (t in scale.ticks) {
            val yy = (toY(t) + 6f).coerceIn(plot.top + 18f, plot.bottom + 6f)
            c.drawText(formatTick(t, stepDecimals(scale.step)), plot.left - 10f, yy, p)
        }
        // X 轴两端时刻
        p.textAlign = Paint.Align.LEFT
        c.drawText(fmt0(xMin) + " s", plot.left, plot.bottom + 26f, p)
        p.textAlign = Paint.Align.RIGHT
        c.drawText(fmt0(xMax) + " s", plot.right, plot.bottom + 26f, p)
        p.textAlign = Paint.Align.LEFT

        // 散点：>400 点抽稀；高于上界的贴顶（真实值在下方标注里）
        p.style = Paint.Style.FILL
        for (s in valid) {
            val xs = s.xValues
            val n = s.values.size
            val finiteCount = s.values.count { it.isFinite() }
            val stride = maxOf(1, ceil(finiteCount / 400.0).toInt())
            val radius = if (finiteCount > 250) 2f else 3.5f
            p.color = s.color.toArgb()
            var i = 0
            while (i < n) {
                val v = s.values[i]
                if (v.isFinite()) {
                    val xv = if (xs != null && i < xs.size && xs[i].isFinite()) xs[i] else i.toDouble()
                    val px = toX(xv)
                    val py = when {
                        v > scale.hi -> plot.top
                        v < scale.lo -> plot.bottom
                        else -> toY(v)
                    }
                    if (px >= plot.left - 1f && px <= plot.right + 1f) {
                        c.drawCircle(px, py, radius, p)
                    }
                }
                i += stride
            }
        }

        // 贴边（截断）如实披露：与页内 ScatterChart 的 axisNote 同一口径
        var gy = cy + chartH + 34f
        val clampNote = buildString {
            if (yPlan.aboveCount > 0) {
                append(
                    "Y 轴上界取 P99；高于上界的 " + yPlan.aboveCount +
                        " 个点贴顶显示，实际最大 " + fmt(yPlan.dataMax)
                )
            }
            if (yPlan.belowCount > 0) {
                if (isNotEmpty()) append(" · ")
                append(
                    "Y 轴下界取" + (if (yPlan.robust) " P1" else "最小值") + "；低于下界的 " +
                        yPlan.belowCount + " 个点贴底显示，实际最小 " + fmt(yPlan.dataMin)
                )
            }
        }
        if (clampNote.isNotEmpty()) {
            p.color = DIM
            p.textSize = 18f
            p.typeface = Typeface.DEFAULT
            for (ln in wrapText(clampNote, p, W - 2 * PAD - 16f)) {
                c.drawText(ln, PAD + 8f, gy, p)
                gy += 24f
            }
            gy += 6f
        }

        // 图例（色点 + 系列名）
        var gx = PAD + 8f
        for (s in valid) {
            p.color = s.color.toArgb()
            p.style = Paint.Style.FILL
            c.drawCircle(gx, gy - 7f, 8f, p)
            p.color = TEXT
            p.textSize = 22f
            p.typeface = Typeface.DEFAULT_BOLD
            c.drawText(s.label, gx + 16f, gy, p)
            gx += 16f + p.measureText(s.label) + 40f
        }
        if (footer != null) {
            p.color = DIM
            p.typeface = Typeface.DEFAULT
            p.textSize = 20f
            p.textAlign = Paint.Align.RIGHT
            c.drawText(footer, W - PAD, gy, p)
            p.textAlign = Paint.Align.LEFT
        }
        gy += 32f

        // 图下标注：每个系列的最小 / 最大 / 平均值（按全量算，不受抽稀与贴顶影响）
        for (s in valid) {
            val vals = s.values.filter { it.isFinite() }
            if (vals.isEmpty()) continue
            p.color = s.color.toArgb()
            p.textSize = 20f
            p.typeface = Typeface.DEFAULT_BOLD
            c.drawText(s.label, PAD + 8f, gy, p)
            p.color = DIM
            p.typeface = Typeface.DEFAULT
            c.drawText(
                "最小 " + fmt(vals.min()) + " · 最大 " + fmt(vals.max()) +
                    " · 平均 " + fmt(vals.average()) + " " + unit,
                PAD + 150f, gy, p
            )
            gy += 30f
        }
        return gy + 26f
    }

    private fun appVersion(context: Context): String {
        return try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(context.packageName, 0)
            val name = info.versionName ?: "—"
            val code = if (android.os.Build.VERSION.SDK_INT >= 28)
                info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            "$name($code)"
        } catch (_: Exception) {
            "—"
        }
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)
}