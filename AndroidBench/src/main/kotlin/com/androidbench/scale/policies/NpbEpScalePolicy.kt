package com.androidbench.scale.policies

import com.androidbench.core.*
import com.androidbench.scale.BenchmarkScalePolicy
import com.androidbench.scale.IScaleConfiguration

/**
 * NPB EP 合法 Problem Class 枚举 (严格对应 NASA 上游规范)
 */
enum class NpbProblemClass(val className: String, val powerOfTwo: Int, val expectedChecksumNote: String) {
    CLASS_S("S", 24, "用于快速功能与正确性校验 (Smoke)"),
    CLASS_W("W", 25, "工作站轻量负载 (Small)"),
    CLASS_A("A", 28, "上游官方标准性能评测基线 (Standard)"),
    CLASS_B("B", 30, "高性能大计算量负载 (Large)"),
    CLASS_C("C", 32, "超级计算机 / PC 重型负载 (Extreme)")
}

/**
 * NPB EP 规模配置
 */
data class NpbEpConfig(
    override val scaleLevel: ScaleLevel,
    val problemClass: NpbProblemClass,
    val threadCount: Int = 0,
    val isOfficialVerificationRun: Boolean = false
) : IScaleConfiguration {
    override val displaySummary: String
        get() = "NPB EP [${scaleLevel.levelName}] - Class ${problemClass.className} (2^${problemClass.powerOfTwo} pairs), ${threadCount} threads"

    override val parameters: Map<String, Any>
        get() = mapOf(
            "problemClass" to problemClass.className,
            "powerOfTwo" to problemClass.powerOfTwo,
            "threadCount" to threadCount,
            "isOfficialVerificationRun" to isOfficialVerificationRun,
            "upstreamStandard" to "NASA NAS Parallel Benchmarks 3.4.2 EP"
        )
}

/**
 * 对应第 14.5 节 E 项：NPB EP (Embarrassingly Parallel) 规模管理与资源估算
 */
class NpbEpScalePolicy : BenchmarkScalePolicy {
    override val benchmarkId: BenchmarkId = BenchmarkId.NPB_EP

    override fun getSupportedScales(): List<IScaleConfiguration> = listOf(
        NpbEpConfig(ScaleLevel.SMOKE, NpbProblemClass.CLASS_S, threadCount = 1, isOfficialVerificationRun = true),
        NpbEpConfig(ScaleLevel.SMALL, NpbProblemClass.CLASS_W, threadCount = 2, isOfficialVerificationRun = false),
        NpbEpConfig(ScaleLevel.STANDARD, NpbProblemClass.CLASS_A, threadCount = 0, isOfficialVerificationRun = false),
        NpbEpConfig(ScaleLevel.LARGE, NpbProblemClass.CLASS_B, threadCount = 0, isOfficialVerificationRun = false)
    )

    override fun getConfigurationForLevel(level: ScaleLevel): IScaleConfiguration {
        return getSupportedScales().firstOrNull { it.scaleLevel == level }
            ?: NpbEpConfig(level, NpbProblemClass.CLASS_A, threadCount = 0)
    }

    override fun estimateResources(
        config: IScaleConfiguration,
        device: DeviceResourceSnapshot
    ): ResourceEstimation {
        val epConfig = config as NpbEpConfig
        val effectiveThreads = if (epConfig.threadCount <= 0) device.cpuCoreCount else epConfig.threadCount

        // 内存需求：EP 属于计算密集型，数据以流式生成伪随机高斯对为主，数组工作区较小
        // 单个 worker 统计高斯分布 bin 数组 (10 个区间) 及线程私有状态，基础开销约 16MB
        val estimatedMemory = 16L * 1024L * 1024L + (effectiveThreads * 2L * 1024L * 1024L)

        // 耗时估算：EP 计算对数随 2^M 成正比增长
        // 现代 ARM64 单核每秒大约处理 2^23 ~ 2^24 对随机数
        val totalPairs = 1L shl epConfig.problemClass.powerOfTwo
        val pairsPerCoreSec = 8_000_000.0 // 假设单核处理速率 8M pairs/s
        val effectiveComputeRate = pairsPerCoreSec * (effectiveThreads * 0.95) // EP 具有极高线性并行度
        val estimatedDurationSec = maxOf(0.5, totalPairs.toDouble() / effectiveComputeRate)
        val estimatedRuntimeMs = (estimatedDurationSec * 1000).toLong()

        val minRuntime = maxOf(400L, estimatedRuntimeMs / 2)
        val maxRuntime = maxOf(minRuntime + 4000L, estimatedRuntimeMs * 2)

        return ResourceEstimation(
            benchmarkId = benchmarkId,
            targetScaleLevel = epConfig.scaleLevel,
            estimatedPeakMemoryBytes = estimatedMemory,
            estimatedMinRuntimeMs = minRuntime,
            estimatedMaxRuntimeMs = maxRuntime,
            estimatedTempStorageBytes = 64L * 1024L,
            recommendedThreads = effectiveThreads,
            growthOrder = MemoryGrowthOrder.CONSTANT, // 内存为常量，计算量为指数 2^M
            confidence = EstimationConfidence.HIGH,
            errorSources = listOf("FMA/NEON 向量指令集优化程度", "浮点调度效率"),
            formulaExplanation = "EP is compute-bound. Pairs = 2^${epConfig.problemClass.powerOfTwo}; Mem ~ 16MB constant"
        )
    }

    override fun evaluateComparability(
        config: IScaleConfiguration,
        actualObservedMemoryBytes: Long?
    ): Pair<Boolean, String> {
        val epConfig = config as NpbEpConfig
        return if (epConfig.problemClass == NpbProblemClass.CLASS_A && epConfig.scaleLevel == ScaleLevel.STANDARD) {
            Pair(true, "采用 NASA NPB 3.4.2 官方标准 Class A 规模，无常量裁剪，具备跨设备横向可比性")
        } else if (epConfig.problemClass == NpbProblemClass.CLASS_S) {
            Pair(false, "Class S 仅作为快速正确性校验 (Verification Run)，不代表标准性能分")
        } else {
            Pair(false, "采用 Class ${epConfig.problemClass.className} 规模，仅同规格下具备可比性")
        }
    }
}
