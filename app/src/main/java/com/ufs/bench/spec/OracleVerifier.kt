package com.ufs.bench.spec

import java.io.File

/**
 * ============================================================================
 *  Oracle 容差裁判（V3 规划 M3.2）
 *
 *  判定规则（对齐 SPEC 的 floatcompare 语义）：
 *    · 逐行比对；行内按空白切分 token；
 *    · 两侧都能解析为数字时，用 |a-b| <= abs_tol + rel_tol*|b| 判定；
 *    · 否则按字符串精确比对；
 *    · 任一处不符即 verified=false，并把前若干处差异原样写进明细（不粉饰）。
 *  说明：本模块只做"内部相对基线"的自我一致性判定，不代表任何官方判分。
 * ============================================================================
 */
object OracleVerifier {

    data class Mismatch(val line: Int, val expected: String, val actual: String, val reason: String)

    data class Result(
        val applicable: Boolean,      // 是否执行了判分（reference 缺失时为 false）
        val verified: Boolean,
        val linesCompared: Int,
        val mismatchCount: Int,
        val mismatches: List<Mismatch>,
        val detail: String
    )

    private fun parseNum(s: String): Double? = s.toDoubleOrNull()

    /** 主入口：actual 文件与 reference 文件比对 */
    fun verify(
        actualFile: File,
        referenceFile: File?,
        type: String,
        absTol: Double = 1e-7,
        relTol: Double = 1e-4,
        maxReport: Int = 5
    ): Result {
        if (type == "none" || referenceFile == null || !referenceFile.isFile) {
            return Result(false, false, 0, 0, emptyList(),
                "未配置参考输出（oracle=" + type + "）：仅记录运行结果，不做判分")
        }
        if (!actualFile.isFile) {
            return Result(true, false, 0, 0, emptyList(), "被测输出文件缺失：" + actualFile.name)
        }
        val refLines = referenceFile.readLines()
        val actLines = actualFile.readLines()
        val mismatches = ArrayList<Mismatch>()
        var compared = 0
        val n = minOf(refLines.size, actLines.size)
        for (i in 0 until n) {
            val r = refLines[i]
            val a = actLines[i]
            compared++
            if (r == a) continue
            val rt = r.trim().split(Regex("\\s+"))
            val at = a.trim().split(Regex("\\s+"))
            if (rt.size != at.size) {
                if (mismatches.size < maxReport) {
                    mismatches.add(Mismatch(i + 1, r, a, "token 数不同（" + rt.size + " vs " + at.size + "）"))
                }
                continue
            }
            var lineOk = true
            for (j in rt.indices) {
                val rn = parseNum(rt[j]); val an = parseNum(at[j])
                if (rn != null && an != null) {
                    val tol = absTol + relTol * Math.abs(rn)
                    if (Math.abs(an - rn) > tol) { lineOk = false; break }
                } else if (rt[j] != at[j]) {
                    lineOk = false; break
                }
            }
            if (!lineOk && mismatches.size < maxReport) {
                mismatches.add(Mismatch(i + 1, r, a, "超出容差 abs=" + absTol + " rel=" + relTol))
            }
        }
        val lineCountDiff = refLines.size - actLines.size
        val ok = mismatches.isEmpty() && lineCountDiff == 0
        val detail = StringBuilder().apply {
            append("参考 ").append(refLines.size).append(" 行 / 实测 ").append(actLines.size).append(" 行，比对 ")
            append(compared).append(" 行，容差 abs=").append(absTol).append(" rel=").append(relTol)
            if (ok) append("：一致")
            else {
                append("：不符 ").append(mismatches.size)
                if (lineCountDiff != 0) append("（行数差 ").append(lineCountDiff).append("）")
                mismatches.forEach { m ->
                    append(" | L").append(m.line).append(" 期望[").append(m.expected.take(60))
                        .append("] 实测[").append(m.actual.take(60)).append("] ").append(m.reason)
                }
            }
        }.toString()
        return Result(true, ok, compared, mismatches.size, mismatches, detail)
    }
}
