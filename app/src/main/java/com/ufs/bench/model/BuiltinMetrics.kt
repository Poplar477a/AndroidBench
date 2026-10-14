package com.ufs.bench.model

/**
 * 内置基准的指标口径（每个内核自己的量纲）。
 *
 * 为什么必须有这张表：
 *   不同内核产出的物理量根本不同 —— CoreMark 是 iterations/s（整数迭代速率），
 *   STREAM / tinymembench 是内存带宽。旧实现统一按 "M-Ops/s" 与 "GB/s" 展示，于是：
 *     · CoreMark 实测 175,676 iterations/s 被写成 "175676.2 M-Ops/s"（**虚高 10⁶ 倍**）；
 *     · CoreMark 根本没有内存吞吐，却照样显示 "0.00 GB/s"；
 *     · STREAM 反过来显示 "0.0 M-Ops/s"。
 *   这里按内核 id 给出真实口径，并用 scale 做单位换算；内核不产出的量纲返回 null，界面不显示。
 */
data class BuiltinMetricSpec(
    /** 左侧指标名；null = 该内核不产出这个量纲，界面必须隐藏 */
    val cpuLabel: String? = null,
    val cpuUnit: String = "",
    val cpuScale: Double = 1.0,
    /** 右侧指标名；null = 隐藏 */
    val memLabel: String? = null,
    val memUnit: String = "",
    val memScale: Double = 1.0
)

fun builtinMetricSpec(kernelId: Int): BuiltinMetricSpec = when (kernelId) {
    // CoreMark：上游计分口径就是 iterations/s，不产出带宽
    1001 -> BuiltinMetricSpec(
        cpuLabel = "整数迭代速率", cpuUnit = "iterations/s",
        memLabel = null
    )
    // STREAM：上游口径 MB/s（1e6 字节）；原生回填的是 GB/s，故 ×1000
    1002 -> BuiltinMetricSpec(
        cpuLabel = null,
        memLabel = "持续内存带宽（Triad）", memUnit = "MB/s", memScale = 1000.0
    )
    // tinymembench：主指标是顺序读带宽（MB/s）
    1003 -> BuiltinMetricSpec(
        cpuLabel = null,
        memLabel = "顺序读带宽", memUnit = "MB/s", memScale = 1000.0
    )
    // 其它（含已弃用的自研内核编号）：沿用旧口径，避免历史记录显示空白
    else -> BuiltinMetricSpec(
        cpuLabel = "CPU 算力", cpuUnit = "M-Ops/s",
        memLabel = "内存层次吞吐", memUnit = "GB/s"
    )
}

