package com.androidbench.scale.policies

import com.androidbench.core.*
import com.androidbench.scale.BenchmarkScalePolicy
import com.androidbench.scale.IScaleConfiguration

/**
 * Zstandard 规模配置
 */
data class ZstdConfig(
    override val scaleLevel: ScaleLevel,
    val datasetSizeBytes: Long,
    val datasetName: String,
    val compressionLevel: Int,
    val threadCount: Int,
    val targetDurationSec: Double = 10.0
) : IScaleConfiguration {
    override val displaySummary: String
        get() = "Zstd [${scaleLevel.levelName}] - ${datasetSizeBytes / (1024 * 1024)}MB dataset '$datasetName', Level $compressionLevel, ${threadCount} threads"

    override val parameters: Map<String, Any>
        get() = mapOf(
            "datasetSizeBytes" to datasetSizeBytes,
            "datasetName" to datasetName,
            "compressionLevel" to compressionLevel,
            "threadCount" to threadCount,
            "targetDurationSec" to targetDurationSec
        )
}

/**
 * 对应第 14.5 节 B 项：Zstandard 规模管理与资源估算
 */
class ZstdScalePolicy : BenchmarkScalePolicy {
    override val benchmarkId: BenchmarkId = BenchmarkId.ZSTD

    override fun getSupportedScales(): List<IScaleConfiguration> = listOf(
        ZstdConfig(
            scaleLevel = ScaleLevel.SMOKE,
            datasetSizeBytes = 2L * 1024L * 1024L, // 2MB
            datasetName = "corpus_smoke_v1",
            compressionLevel = 1,
            threadCount = 1,
            targetDurationSec = 1.0
        ),
        ZstdConfig(
            scaleLevel = ScaleLevel.SMALL,
            datasetSizeBytes = 16L * 1024L * 1024L, // 16MB
            datasetName = "corpus_small_v1",
            compressionLevel = 3,
            threadCount = 2,
            targetDurationSec = 5.0
        ),
        ZstdConfig(
            scaleLevel = ScaleLevel.STANDARD,
            datasetSizeBytes = 64L * 1024L * 1024L, // 64MB
            datasetName = "silesia_repro_std_v1",
            compressionLevel = 3,
            threadCount = 0, // 全核
            targetDurationSec = 10.0
        ),
        ZstdConfig(
            scaleLevel = ScaleLevel.LARGE,
            datasetSizeBytes = 256L * 1024L * 1024L, // 256MB
            datasetName = "enwik8_large_v1",
            compressionLevel = 3,
            threadCount = 0,
            targetDurationSec = 20.0
        )
    )

    override fun getConfigurationForLevel(level: ScaleLevel): IScaleConfiguration {
        return getSupportedScales().firstOrNull { it.scaleLevel == level }
            ?: ZstdConfig(level, 64L * 1024L * 1024L, "silesia_repro_std_v1", 3, 0)
    }

    override fun estimateResources(
        config: IScaleConfiguration,
        device: DeviceResourceSnapshot
    ): ResourceEstimation {
        val zConfig = config as ZstdConfig
        val effectiveThreads = if (zConfig.threadCount <= 0) device.cpuCoreCount else zConfig.threadCount

        // 窗口大小与工作内存推导：
        // Level 1: 512KB window; Level 3: 4MB window; Level 19: 64MB window
        val windowSizeBytes = when (zConfig.compressionLevel) {
            1 -> 512L * 1024L
            in 2..4 -> 4L * 1024L * 1024L
            in 5..9 -> 8L * 1024L * 1024L
            else -> 32L * 1024L * 1024L
        }

        // 多线程压缩上下文开销：每个 Worker 需约 4*window + hash table (~2MB)
        val perWorkerOverhead = (4 * windowSizeBytes) + (2L * 1024L * 1024L)
        // 原始输入缓冲与目标压缩缓冲 (上限按 srcSize 估算)
        val bufferOverhead = zConfig.datasetSizeBytes * 2
        val estimatedMemory = bufferOverhead + (effectiveThreads * perWorkerOverhead) + (16L * 1024L * 1024L)

        // 运行时间估算：ARM64 现代核心 Level 3 单核吞吐约 80MB/s 压缩，300MB/s 解压
        val singleCoreThroughputMBs = 70.0
        val effectiveThroughputMBs = singleCoreThroughputMBs * (effectiveThreads * 0.75) // 考虑多核非完全线性扩展
        val datasetMB = zConfig.datasetSizeBytes.toDouble() / (1024.0 * 1024.0)
        val estimatedSeconds = maxOf(1.0, (datasetMB / effectiveThroughputMBs) * 2.0 /* 压缩+解压各一轮 */)
        val estimatedRuntimeMs = (estimatedSeconds * 1000).toLong()

        val minRuntime = maxOf(1000L, estimatedRuntimeMs / 2)
        val maxRuntime = maxOf(minRuntime + 5000L, estimatedRuntimeMs * 2)

        return ResourceEstimation(
            benchmarkId = benchmarkId,
            targetScaleLevel = zConfig.scaleLevel,
            estimatedPeakMemoryBytes = estimatedMemory,
            estimatedMinRuntimeMs = minRuntime,
            estimatedMaxRuntimeMs = maxRuntime,
            estimatedTempStorageBytes = zConfig.datasetSizeBytes * 2, // 临时文件落盘
            recommendedThreads = effectiveThreads,
            growthOrder = MemoryGrowthOrder.LINEAR,
            confidence = EstimationConfidence.HIGH,
            errorSources = listOf("输入语料压缩比波动", "多核锁竞争与内存带宽饱和"),
            formulaExplanation = "Mem = 2*DatasetSize + Threads*(4*WindowSize + 2MB) + 16MB"
        )
    }

    override fun evaluateComparability(
        config: IScaleConfiguration,
        actualObservedMemoryBytes: Long?
    ): Pair<Boolean, String> {
        val zConfig = config as ZstdConfig
        return if (zConfig.scaleLevel == ScaleLevel.STANDARD) {
            Pair(true, "采用标准 64MB 固定语料与 Level 3 默认压缩配置，可用于标准横向对比")
        } else {
            Pair(false, "当前采用 ${zConfig.scaleLevel.levelName} 语料规模 (${zConfig.datasetSizeBytes / (1024 * 1024)}MB)，仅作为设备自适应/验证结果，不参与官方标准天梯对比")
        }
    }
}
