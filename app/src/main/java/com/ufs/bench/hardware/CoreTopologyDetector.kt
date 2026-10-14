package com.ufs.bench.hardware

import java.io.File

/**
 * 全大核判定 —— 全大核 ISA 档（-mcpu）的运行期门禁数据源。
 *
 * 为什么必须有这道门禁：
 *   ISA 档用 -mcpu 启用了目标微架构的完整 ISA（实测 oryon2isa 产物含 1526 条 SVE 指令）。
 *   只要 SoC 里还存在缺少该 ISA 的核心，Android 调度器就可能把线程扔过去 → SIGILL
 *   （本项目已在 SM7475 上复现同类崩溃，tombstone 指令为 SVE 的 index z0.s）。
 *   因此只有「所有核心同代（同构）且都不是小核/中核」的设备才允许加载 ISA 档。
 *
 * 判据取自 /proc/cpuinfo 的 CPU part（无需 root，全 Android 可用）：
 *   · 一加 15 / SM8850：8 个核全是 0x002                        → 同构全大核 ✓
 *   · 红米 / SM7475：0xd48(X2)×1 + 0xd47(A710)×3 + 0xd46(A510)×4 → 异构 ✗
 *
 * fail-closed：读不到 part（x86_64 模拟器等）或存在任何小核/中核 → 判定为非全大核，
 * ISA 档一律拒绝加载。宁可回退基线，也不能赌 SIGILL。
 */
object CoreTopologyDetector {

    /** ARM Cortex 小核 / 中核的 part 号（命中任一即判为异构，不允许 ISA 档）。 */
    private val LITTLE_OR_MID_PARTS = setOf(
        "0xd03", "0xd04", "0xd05", "0xd07", "0xd08", "0xd09", "0xd0a", "0xd0b",
        "0xd0c", "0xd0d", "0xd0e", "0xd40", "0xd41", "0xd42", "0xd43", "0xd44",
        "0xd45", "0xd46", "0xd47", "0xd48", "0xd49", "0xd4a", "0xd4b", "0xd4c",
        "0xd4d", "0xd4e", "0xd4f", "0xd80", "0xd81", "0xd82", "0xd83", "0xd84",
        "0xd85", "0xd86", "0xd87", "0xd88", "0xd89"
    )

    data class Topology(
        val parts: List<String>,
        val distinctParts: Int,
        val coreCount: Int,
        val hasLittleOrMid: Boolean,
        val isAllBigCore: Boolean,
        /** 中文摘要，可直接进提示/披露串 */
        val summary: String
    )

    @Volatile
    private var cached: Topology? = null

    fun detect(): Topology {
        cached?.let { return it }
        val topo = detectUncached()
        cached = topo
        return topo
    }

    private fun detectUncached(): Topology {
        val parts = readCpuParts()
        val distinct = parts.distinct()
        val hasLittleMid = distinct.any { it in LITTLE_OR_MID_PARTS }
        val allBig = parts.size >= 2 && distinct.size == 1 && !hasLittleMid
        val summary: String = when {
            parts.isEmpty() -> "无法读取 CPU part（非 ARM 或受限环境）→ 按非全大核处理"
            distinct.size == 1 && !hasLittleMid ->
                parts.size.toString() + "×" + parts[0] + "（同构全大核）"
            distinct.size == 1 ->
                parts.size.toString() + "×" + parts[0] + "（同构但属小核/中核）"
            else -> distinct.sorted().joinToString(" + ") { p ->
                parts.count { it == p }.toString() + "×" + p
            } + "（异构多核，含小核/中核）"
        }
        return Topology(parts, distinct.size, parts.size, hasLittleMid, allBig, summary)
    }

    /** 解析 /proc/cpuinfo 的 CPU part；返回每个核的 part（十六进制小写，如 0xd48）。 */
    private fun readCpuParts(): List<String> {
        val text = runCatching { File("/proc/cpuinfo").readText() }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("CPU part")) {
                val v = t.substringAfter(":").trim().lowercase()
                if (v.isNotEmpty()) out.add(v)
            }
        }
        return out
    }
}

/**
 * 全大核 ISA 专用档的白名单（Kotlin 侧唯一事实源）。
 *
 * 必须与 app/src/main/cpp/profiles.cmake 里的 set(DYNAMO_ISA_<profile> "allbig") 一致；
 * 由 .evidence/check_isa_catalog.py 交叉校验，任一侧漏改即报错。
 */
object AllBigCoreOnlyProfiles {
    val IDS: Set<String> = setOf("oryon2isa", "oryon1isa")
}
