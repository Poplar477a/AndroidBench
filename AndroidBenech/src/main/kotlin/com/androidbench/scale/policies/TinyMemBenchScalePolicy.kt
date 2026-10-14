package com.androidbench.scale.policies

import com.androidbench.core.*
import com.androidbench.scale.BenchmarkScalePolicy
import com.androidbench.scale.IScaleConfiguration

/**
 * tinymembench 规模配置
 */
data class TinyMemBenchConfig(
    override val scaleLevel: ScaleLevel,
    val maxBufferSizeMB: Int,
    val enabledPatterns: List<String>,
    val unsupportedPatterns: List<String> = emptyList(),
    val includeRandomLatency: Boolean = true
) : IScaleConfiguration {
    override val displaySummary: String
        get() = "tinymembench [${scaleLevel.levelName}] - MaxBuf: ${maxBufferSizeMB}MB, Patterns: ${enabledPatterns.size}, Latency: $includeRandomLatency"

    override val parameters: Map<String, Any>
        get() = mapOf(
            "maxBufferSizeMB" to maxBufferSizeMB,
            "enabledPatterns" to enabledPatterns,
            "unsupportedPatterns" to unsupportedPatterns,
            "includeRandomLatency" to includeRandomLatency
        )
}

/**
 * 对应第 14.5 节 D 项：tinymembench 内存延迟与带宽规模管理
 */
class TinyMemBenchScalePolicy : BenchmarkScalePolicy {
    override val benchmarkId: BenchmarkId = BenchmarkId.TINYMEMBENCH

    override fun getSupportedScales(): List<IScaleConfiguration> = listOf(
        TinyMemBenchConfig(
            scaleLevel = ScaleLevel.SMOKE,
            maxBufferSizeMB = 8,
            enabledPatterns = listOf("standard_memcpy", "memset"),
            unsupportedPatterns = listOf("hugepages_direct", "kernel_perf_counters"),
            includeRandomLatency = false
        ),
        TinyMemBenchConfig(
            scaleLevel = ScaleLevel.SMALL,
            maxBufferSizeMB = 32,
            enabledPatterns = listOf("standard_memcpy", "neon_memcpy", "memset", "pointer_chase_latency_small"),
            unsupportedPatterns = listOf("hugepages_direct"),
            includeRandomLatency = true
        ),
        TinyMemBenchConfig(
            scaleLevel = ScaleLevel.STANDARD,
            maxBufferSizeMB = 128,
            enabledPatterns = listOf(
                "standard_memcpy",
                "neon_memcpy_aligned",
                "neon_memcpy_unaligned",
                "memset_neon",
                "pointer_chase_latency_full_curve"
            ),
            unsupportedPatterns = listOf("hugepages_direct", "kernel_perf_counters"),
            includeRandomLatency = true
        ),
        TinyMemBenchConfig(
            scaleLevel = ScaleLevel.LARGE,
            maxBufferSizeMB = 256,
            enabledPatterns = listOf(
                "standard_memcpy",
                "neon_memcpy_aligned",
                "neon_memcpy_unaligned",
                "memset_neon",
                "pointer_chase_latency_extended"
            ),
            unsupportedPatterns = listOf("hugepages_direct"),
            includeRandomLatency = true
        )
    )

    override fun getConfigurationForLevel(level: ScaleLevel): IScaleConfiguration {
        return getSupportedScales().firstOrNull { it.scaleLevel == level }
            ?: TinyMemBenchConfig(level, 128, listOf("standard_memcpy", "memset"))
    }

    override fun estimateResources(
        config: IScaleConfiguration,
        device: DeviceResourceSnapshot
    ): ResourceEstimation {
        val tConfig = config as TinyMemBenchConfig

        // 内存需求：两个主要测试缓冲区 (src + dst) 各 maxBufferSizeMB
        // 加上指针追逐随机查找表与对齐页 (额外预留 16MB)
        val estimatedMemory = ((tConfig.maxBufferSizeMB * 2L) + 16L) * 1024L * 1024L

        // tinymembench 为单线程精细微基准
        val patternsCount = tConfig.enabledPatterns.size
        val estimatedRuntimeMs = (patternsCount * 1800L) + (if (tConfig.includeRandomLatency) 4000L else 0L)

        val minRuntime = maxOf(1000L, estimatedRuntimeMs / 2)
        val maxRuntime = maxOf(minRuntime + 6000L, estimatedRuntimeMs * 2)

        return ResourceEstimation(
            benchmarkId = benchmarkId,
            targetScaleLevel = tConfig.scaleLevel,
            estimatedPeakMemoryBytes = estimatedMemory,
            estimatedMinRuntimeMs = minRuntime,
            estimatedMaxRuntimeMs = maxRuntime,
            estimatedTempStorageBytes = 128L * 1024L,
            recommendedThreads = 1, // tinymembench 上游为单线程架构
            growthOrder = MemoryGrowthOrder.LINEAR,
            confidence = EstimationConfidence.HIGH,
            errorSources = listOf("随机访问模式缺页与 TLB 抖动", "后台进程缓存抢占"),
            formulaExplanation = "Mem = 2 * MaxBufMB + 16MB; Single-threaded micro-benchmark"
        )
    }

    override fun evaluateComparability(
        config: IScaleConfiguration,
        actualObservedMemoryBytes: Long?
    ): Pair<Boolean, String> {
        val tConfig = config as TinyMemBenchConfig
        return if (tConfig.scaleLevel == ScaleLevel.STANDARD) {
            Pair(true, "采用标准 128MB 双缓冲与完整 NEON 模式，延迟与带宽数据具备完全可比性")
        } else {
            Pair(false, "当前为 ${tConfig.scaleLevel.levelName} 规模 (MaxBuf ${tConfig.maxBufferSizeMB}MB)，仅适用于低资源环境观测")
        }
    }
}
