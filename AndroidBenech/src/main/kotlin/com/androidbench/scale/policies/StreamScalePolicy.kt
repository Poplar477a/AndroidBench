package com.androidbench.scale.policies

import com.androidbench.core.*
import com.androidbench.scale.BenchmarkScalePolicy
import com.androidbench.scale.IScaleConfiguration

/**
 * STREAM 规模配置
 */
data class StreamConfig(
    override val scaleLevel: ScaleLevel,
    val arrayLength: Long,
    val elementBytes: Int = 8, // double 精度
    val arrayCount: Int = 4,   // a, b, c 及校验/对齐缓冲
    val threadCount: Int = 0
) : IScaleConfiguration {
    val totalWorkingSetBytes: Long
        get() = arrayLength * elementBytes * arrayCount

    override val displaySummary: String
        get() = "STREAM [${scaleLevel.levelName}] - N=${arrayLength}, WorkingSet=${totalWorkingSetBytes / (1024 * 1024)}MB (${if (totalWorkingSetBytes < 64 * 1024 * 1024) "Cache-Resident" else "DRAM Sustained"})"

    override val parameters: Map<String, Any>
        get() = mapOf(
            "arrayLength" to arrayLength,
            "elementBytes" to elementBytes,
            "arrayCount" to arrayCount,
            "totalWorkingSetBytes" to totalWorkingSetBytes,
            "threadCount" to threadCount
        )
}

/**
 * 对应第 14.5 节 C 项：STREAM 持续内存带宽规模管理与资源估算
 */
class StreamScalePolicy : BenchmarkScalePolicy {
    override val benchmarkId: BenchmarkId = BenchmarkId.STREAM

    override fun getSupportedScales(): List<IScaleConfiguration> = listOf(
        StreamConfig(ScaleLevel.SMOKE, arrayLength = 200_000L),      // ~6.4MB (Cache 驻留)
        StreamConfig(ScaleLevel.SMALL, arrayLength = 1_500_000L),    // ~48MB (低端机 DRAM)
        StreamConfig(ScaleLevel.STANDARD, arrayLength = 10_000_000L), // ~320MB (常规标准，远超 LLC)
        StreamConfig(ScaleLevel.LARGE, arrayLength = 30_000_000L)    // ~960MB (Android PC / 旗舰机)
    )

    override fun getConfigurationForLevel(level: ScaleLevel): IScaleConfiguration {
        return getSupportedScales().firstOrNull { it.scaleLevel == level }
            ?: StreamConfig(level, arrayLength = 10_000_000L)
    }

    override fun estimateResources(
        config: IScaleConfiguration,
        device: DeviceResourceSnapshot
    ): ResourceEstimation {
        val sConfig = config as StreamConfig
        val effectiveThreads = if (sConfig.threadCount <= 0) device.cpuCoreCount else sConfig.threadCount

        // 内存需求：4 个大数组 + 页面对齐 (64 字节/4KB 边界) + 线程本地上下文 (预留 32MB)
        val estimatedMemory = sConfig.totalWorkingSetBytes + (32L * 1024L * 1024L)

        // 耗时估算：手机 LPDDR5/5X 实测单/多核带宽一般在 15GB/s ~ 60GB/s
        val assumedBandwidthBytesPerSec = 20L * 1024L * 1024L * 1024L // 保守按 20 GB/s
        val totalTransferredBytes = sConfig.arrayLength * sConfig.elementBytes * 10 /* 4操作各跑数轮 */
        val estimatedDurationSec = maxOf(1.0, totalTransferredBytes.toDouble() / assumedBandwidthBytesPerSec.toDouble())
        val estimatedRuntimeMs = (estimatedDurationSec * 1000).toLong()

        val minRuntime = maxOf(800L, estimatedRuntimeMs / 2)
        val maxRuntime = maxOf(minRuntime + 4000L, estimatedRuntimeMs * 3)

        return ResourceEstimation(
            benchmarkId = benchmarkId,
            targetScaleLevel = sConfig.scaleLevel,
            estimatedPeakMemoryBytes = estimatedMemory,
            estimatedMinRuntimeMs = minRuntime,
            estimatedMaxRuntimeMs = maxRuntime,
            estimatedTempStorageBytes = 64L * 1024L,
            recommendedThreads = effectiveThreads,
            growthOrder = MemoryGrowthOrder.LINEAR,
            confidence = EstimationConfidence.HIGH,
            errorSources = listOf("内存带宽通道争用", "末级缓存命中率差异"),
            formulaExplanation = "Mem = 4 * N * sizeof(double) + 32MB; Growth is O(N)"
        )
    }

    override fun evaluateComparability(
        config: IScaleConfiguration,
        actualObservedMemoryBytes: Long?
    ): Pair<Boolean, String> {
        val sConfig = config as StreamConfig
        val workingSetMB = sConfig.totalWorkingSetBytes / (1024 * 1024)

        // 手机末级缓存 (SLC / LLC) 通常为 8MB ~ 16MB，PC 级为 32MB
        val llcThresholdBytes = 64L * 1024L * 1024L // 64MB 作为安全超出 LLC 阈值

        return if (sConfig.totalWorkingSetBytes < llcThresholdBytes) {
            Pair(
                false,
                "当前工作集仅 ${workingSetMB} MB，未能超越末级缓存 (LLC)，属于【缓存工作集测试 (Cache-Resident)】，不得作为【持续内存带宽 (Sustained Memory Bandwidth)】成绩对比"
            )
        } else if (sConfig.scaleLevel == ScaleLevel.STANDARD) {
            Pair(true, "工作集 ${workingSetMB} MB 已完全击穿系统末级缓存，符合标准持续内存带宽口径，具有完全可比性")
        } else {
            Pair(false, "当前为自适应/扩展规模 (${sConfig.scaleLevel.levelName})，仅同规模内可比")
        }
    }
}
