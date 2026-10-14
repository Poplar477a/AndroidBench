package com.androidbench.scale.policies

import com.androidbench.core.*
import com.androidbench.scale.BenchmarkScalePolicy
import com.androidbench.scale.IScaleConfiguration

/**
 * CoreMark 规模配置
 */
data class CoreMarkConfig(
    override val scaleLevel: ScaleLevel,
    val iterations: Int,
    val threadCount: Int,
    val minRequiredDurationSeconds: Double = 10.0
) : IScaleConfiguration {
    override val displaySummary: String
        get() = "CoreMark [${scaleLevel.levelName}] - ${iterations} iters, ${threadCount} threads"

    override val parameters: Map<String, Any>
        get() = mapOf(
            "iterations" to iterations,
            "threadCount" to threadCount,
            "minRequiredDurationSeconds" to minRequiredDurationSeconds,
            "upstreamAlgorithm" to "EEMBC CoreMark 1.0"
        )
}

/**
 * 对应第 14.5 节 A 项：CoreMark 规模管理与资源估算
 */
class CoreMarkScalePolicy : BenchmarkScalePolicy {
    override val benchmarkId: BenchmarkId = BenchmarkId.COREMARK

    override fun getSupportedScales(): List<IScaleConfiguration> = listOf(
        CoreMarkConfig(ScaleLevel.SMOKE, iterations = 1000, threadCount = 1, minRequiredDurationSeconds = 0.5),
        CoreMarkConfig(ScaleLevel.SMALL, iterations = 10000, threadCount = 2, minRequiredDurationSeconds = 5.0),
        CoreMarkConfig(ScaleLevel.STANDARD, iterations = 50000, threadCount = 0 /* 0表示全核 */, minRequiredDurationSeconds = 10.0),
        CoreMarkConfig(ScaleLevel.LARGE, iterations = 120000, threadCount = 0, minRequiredDurationSeconds = 15.0)
    )

    override fun getConfigurationForLevel(level: ScaleLevel): IScaleConfiguration {
        return getSupportedScales().firstOrNull { it.scaleLevel == level }
            ?: CoreMarkConfig(level, iterations = 50000, threadCount = 0)
    }

    override fun estimateResources(
        config: IScaleConfiguration,
        device: DeviceResourceSnapshot
    ): ResourceEstimation {
        val cmConfig = config as CoreMarkConfig
        val effectiveThreads = if (cmConfig.threadCount <= 0) device.cpuCoreCount else cmConfig.threadCount

        // 内存需求：CoreMark 单线程静态结构极小 (约 4KB 矩阵与链表池)
        // 原生线程栈与 libc 开销：每个线程约 1MB
        val estimatedMemory = (4L * 1024L * 1024L) + (effectiveThreads * 1024L * 1024L)

        // 耗时估算：现代 ARM64 核心单核单次迭代约 0.05ms ~ 0.2ms
        val baseIterationMs = when (cmConfig.scaleLevel) {
            ScaleLevel.SMOKE -> 0.15
            ScaleLevel.SMALL -> 0.18
            ScaleLevel.STANDARD -> 0.20
            ScaleLevel.LARGE -> 0.22
            ScaleLevel.ADVANCED -> 0.20
        }
        val estimatedRuntimeMs = ((cmConfig.iterations * baseIterationMs) / maxOf(1, effectiveThreads / 2)).toLong()
        val minRuntime = maxOf((cmConfig.minRequiredDurationSeconds * 1000).toLong(), estimatedRuntimeMs / 2)
        val maxRuntime = maxOf(minRuntime + 5000L, estimatedRuntimeMs * 2)

        return ResourceEstimation(
            benchmarkId = benchmarkId,
            targetScaleLevel = cmConfig.scaleLevel,
            estimatedPeakMemoryBytes = estimatedMemory,
            estimatedMinRuntimeMs = minRuntime,
            estimatedMaxRuntimeMs = maxRuntime,
            estimatedTempStorageBytes = 64L * 1024L, // 极小日志开销
            recommendedThreads = effectiveThreads,
            growthOrder = MemoryGrowthOrder.CONSTANT,
            confidence = EstimationConfidence.HIGH,
            errorSources = listOf("不同微架构每周期指令吞吐率差异 (IPC)", "温控降频对运行时间影响"),
            formulaExplanation = "Mem = 4MB base + 1MB*threads; Time ~ (iterations * 0.2ms) / effectiveCores"
        )
    }

    override fun evaluateComparability(
        config: IScaleConfiguration,
        actualObservedMemoryBytes: Long?
    ): Pair<Boolean, String> {
        val cmConfig = config as CoreMarkConfig
        return if (cmConfig.scaleLevel == ScaleLevel.STANDARD) {
            Pair(true, "符合 EEMBC CoreMark 上游标准多核满载基线，具有横向可比性")
        } else {
            Pair(false, "当前为 ${cmConfig.scaleLevel.levelName} 规模，非官方标准时长，仅用于功能验证，不具横向可比性")
        }
    }
}
