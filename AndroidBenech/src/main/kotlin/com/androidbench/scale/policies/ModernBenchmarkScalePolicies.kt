package com.androidbench.scale.policies

import com.androidbench.core.*
import com.androidbench.scale.BenchmarkScalePolicy
import com.androidbench.scale.IScaleConfiguration

/**
 * 7-Zip LZMA 压缩规模配置 (7z b)
 */
data class SevenZipConfig(
    override val scaleLevel: ScaleLevel,
    val dictionarySizeMB: Int,
    val threadCount: Int = 0
) : IScaleConfiguration {
    override val displaySummary: String
        get() = "7-Zip [${scaleLevel.levelName}] - Dict: ${dictionarySizeMB}MB, Threads: $threadCount"

    override val parameters: Map<String, Any>
        get() = mapOf(
            "dictionarySizeMB" to dictionarySizeMB,
            "threadCount" to threadCount,
            "algorithm" to "LZMA benchmark (7z b)"
        )
}

/**
 * 7-Zip 规模策略
 */
class SevenZipScalePolicy : BenchmarkScalePolicy {
    override val benchmarkId: BenchmarkId = BenchmarkId.SEVEN_ZIP

    override fun getSupportedScales(): List<IScaleConfiguration> = listOf(
        SevenZipConfig(ScaleLevel.SMOKE, dictionarySizeMB = 1, threadCount = 1),
        SevenZipConfig(ScaleLevel.SMALL, dictionarySizeMB = 16, threadCount = 2),
        SevenZipConfig(ScaleLevel.STANDARD, dictionarySizeMB = 32, threadCount = 0),
        SevenZipConfig(ScaleLevel.LARGE, dictionarySizeMB = 64, threadCount = 0)
    )

    override fun getConfigurationForLevel(level: ScaleLevel): IScaleConfiguration {
        return getSupportedScales().firstOrNull { it.scaleLevel == level }
            ?: SevenZipConfig(level, 32, 0)
    }

    override fun estimateResources(config: IScaleConfiguration, device: DeviceResourceSnapshot): ResourceEstimation {
        val cfg = config as SevenZipConfig
        val threads = if (cfg.threadCount <= 0) device.cpuCoreCount else cfg.threadCount
        val mem = (threads * (cfg.dictionarySizeMB * 3L + 12L) + 32L) * 1024L * 1024L
        val timeMs = when (cfg.scaleLevel) {
            ScaleLevel.SMOKE -> 1500L
            ScaleLevel.SMALL -> 5000L
            ScaleLevel.STANDARD -> 15000L
            ScaleLevel.LARGE -> 35000L
            ScaleLevel.ADVANCED -> 15000L
        }
        return ResourceEstimation(
            benchmarkId = benchmarkId,
            targetScaleLevel = cfg.scaleLevel,
            estimatedPeakMemoryBytes = mem,
            estimatedMinRuntimeMs = timeMs / 2,
            estimatedMaxRuntimeMs = timeMs * 2,
            estimatedTempStorageBytes = 256L * 1024L,
            recommendedThreads = threads,
            growthOrder = MemoryGrowthOrder.LINEAR,
            confidence = EstimationConfidence.HIGH,
            errorSources = listOf("多核超线程扩展性", "LZMA 字典哈希冲突"),
            formulaExplanation = "Mem = Threads * (3 * DictMB + 12MB) + 32MB"
        )
    }

    override fun evaluateComparability(config: IScaleConfiguration, actualObservedMemoryBytes: Long?): Pair<Boolean, String> {
        val cfg = config as SevenZipConfig
        return if (cfg.scaleLevel == ScaleLevel.STANDARD && cfg.dictionarySizeMB == 32) {
            Pair(true, "采用标准 32MB 字典全核压测，符合 PC/移动端 7-Zip 官方 MIPS 排名口径")
        } else {
            Pair(false, "字典大小 ${cfg.dictionarySizeMB}MB 为自适应规模，仅限同字典尺寸比对")
        }
    }
}

/**
 * Stockfish 象棋引擎配置
 */
data class StockfishConfig(
    override val scaleLevel: ScaleLevel,
    val searchDepth: Int,
    val hashSizeMB: Int,
    val threadCount: Int = 0
) : IScaleConfiguration {
    override val displaySummary: String
        get() = "Stockfish [${scaleLevel.levelName}] - Depth: $searchDepth, Hash: ${hashSizeMB}MB"

    override val parameters: Map<String, Any>
        get() = mapOf(
            "searchDepth" to searchDepth,
            "hashSizeMB" to hashSizeMB,
            "threadCount" to threadCount
        )
}

/**
 * Stockfish 规模策略
 */
class StockfishScalePolicy : BenchmarkScalePolicy {
    override val benchmarkId: BenchmarkId = BenchmarkId.STOCKFISH

    override fun getSupportedScales(): List<IScaleConfiguration> = listOf(
        StockfishConfig(ScaleLevel.SMOKE, searchDepth = 10, hashSizeMB = 16, threadCount = 1),
        StockfishConfig(ScaleLevel.SMALL, searchDepth = 16, hashSizeMB = 64, threadCount = 2),
        StockfishConfig(ScaleLevel.STANDARD, searchDepth = 22, hashSizeMB = 256, threadCount = 0),
        StockfishConfig(ScaleLevel.LARGE, searchDepth = 26, hashSizeMB = 1024, threadCount = 0)
    )

    override fun getConfigurationForLevel(level: ScaleLevel): IScaleConfiguration {
        return getSupportedScales().firstOrNull { it.scaleLevel == level }
            ?: StockfishConfig(level, 22, 256, 0)
    }

    override fun estimateResources(config: IScaleConfiguration, device: DeviceResourceSnapshot): ResourceEstimation {
        val cfg = config as StockfishConfig
        val threads = if (cfg.threadCount <= 0) device.cpuCoreCount else cfg.threadCount
        val mem = (cfg.hashSizeMB.toLong() + (threads * 4L) + 16L) * 1024L * 1024L
        val timeMs = when (cfg.scaleLevel) {
            ScaleLevel.SMOKE -> 1000L
            ScaleLevel.SMALL -> 4000L
            ScaleLevel.STANDARD -> 12000L
            ScaleLevel.LARGE -> 30000L
            ScaleLevel.ADVANCED -> 12000L
        }
        return ResourceEstimation(
            benchmarkId = benchmarkId,
            targetScaleLevel = cfg.scaleLevel,
            estimatedPeakMemoryBytes = mem,
            estimatedMinRuntimeMs = timeMs / 2,
            estimatedMaxRuntimeMs = timeMs * 2,
            estimatedTempStorageBytes = 64L * 1024L,
            recommendedThreads = threads,
            growthOrder = MemoryGrowthOrder.CONSTANT,
            confidence = EstimationConfidence.HIGH,
            errorSources = listOf("分支预测命中率", "L3/SLC 缓存对哈希表查找延迟的波动"),
            formulaExplanation = "Mem = HashMB + Threads * 4MB + 16MB"
        )
    }

    override fun evaluateComparability(config: IScaleConfiguration, actualObservedMemoryBytes: Long?): Pair<Boolean, String> {
        val cfg = config as StockfishConfig
        return if (cfg.scaleLevel == ScaleLevel.STANDARD) {
            Pair(true, "采用标准 Depth 22 全核压测，具有横向 NPS 节点吞吐可比性")
        } else {
            Pair(false, "非标准深度 (Depth ${cfg.searchDepth})，不同深度 NPS 无法直接线性折算")
        }
    }
}

/**
 * C-Ray 光追渲染配置
 */
data class CRayConfig(
    override val scaleLevel: ScaleLevel,
    val width: Int,
    val height: Int,
    val raysPerPixel: Int = 4,
    val threadCount: Int = 0
) : IScaleConfiguration {
    override val displaySummary: String
        get() = "C-Ray [${scaleLevel.levelName}] - ${width}x${height}, ${raysPerPixel} rays/pixel"

    override val parameters: Map<String, Any>
        get() = mapOf(
            "width" to width,
            "height" to height,
            "raysPerPixel" to raysPerPixel,
            "threadCount" to threadCount
        )
}

/**
 * C-Ray 规模策略
 */
class CRayScalePolicy : BenchmarkScalePolicy {
    override val benchmarkId: BenchmarkId = BenchmarkId.C_RAY

    override fun getSupportedScales(): List<IScaleConfiguration> = listOf(
        CRayConfig(ScaleLevel.SMOKE, width = 400, height = 300, raysPerPixel = 1, threadCount = 1),
        CRayConfig(ScaleLevel.SMALL, width = 800, height = 600, raysPerPixel = 2, threadCount = 2),
        CRayConfig(ScaleLevel.STANDARD, width = 1920, height = 1080, raysPerPixel = 4, threadCount = 0),
        CRayConfig(ScaleLevel.LARGE, width = 3840, height = 2160, raysPerPixel = 8, threadCount = 0)
    )

    override fun getConfigurationForLevel(level: ScaleLevel): IScaleConfiguration {
        return getSupportedScales().firstOrNull { it.scaleLevel == level }
            ?: CRayConfig(level, 1920, 1080, 4, 0)
    }

    override fun estimateResources(config: IScaleConfiguration, device: DeviceResourceSnapshot): ResourceEstimation {
        val cfg = config as CRayConfig
        val threads = if (cfg.threadCount <= 0) device.cpuCoreCount else cfg.threadCount
        // 帧缓冲内存：W * H * 4 bytes + 场景几何图元结构 (~8MB)
        val mem = (cfg.width.toLong() * cfg.height.toLong() * 4L) + (16L * 1024L * 1024L)
        val totalRays = cfg.width.toLong() * cfg.height.toLong() * cfg.raysPerPixel.toLong()
        val raysPerSecCore = 1_800_000.0 // ARM64 单核求交速率
        val timeSec = maxOf(0.5, totalRays.toDouble() / (raysPerSecCore * threads * 0.95))
        val timeMs = (timeSec * 1000).toLong()

        return ResourceEstimation(
            benchmarkId = benchmarkId,
            targetScaleLevel = cfg.scaleLevel,
            estimatedPeakMemoryBytes = mem,
            estimatedMinRuntimeMs = timeMs / 2,
            estimatedMaxRuntimeMs = timeMs * 2,
            estimatedTempStorageBytes = 64L * 1024L,
            recommendedThreads = threads,
            growthOrder = MemoryGrowthOrder.CONSTANT,
            confidence = EstimationConfidence.HIGH,
            errorSources = listOf("FPU 浮点调度效率", "多核温控降频"),
            formulaExplanation = "Mem = FrameBuffer + 16MB; Pure FP compute-bound"
        )
    }

    override fun evaluateComparability(config: IScaleConfiguration, actualObservedMemoryBytes: Long?): Pair<Boolean, String> {
        val cfg = config as CRayConfig
        return if (cfg.scaleLevel == ScaleLevel.STANDARD && cfg.width == 1920 && cfg.height == 1080) {
            Pair(true, "采用标准 1080p 4-rays/pixel 配置，符合跨平台 C-Ray 官方耗时标准")
        } else {
            Pair(false, "分辨率 ${cfg.width}x${cfg.height} 仅在同配置下可比")
        }
    }
}

/**
 * llama.cpp 端侧大模型推理配置
 */
data class LlamaCppConfig(
    override val scaleLevel: ScaleLevel,
    val modelParamBillion: Double, // 0.5B, 1.1B, 3.2B, 7.0B
    val quantization: String,       // Q4_0, Q4_K_M
    val contextLength: Int,
    val batchPromptTokens: Int,
    val genTokens: Int,
    val threadCount: Int = 0
) : IScaleConfiguration {
    override val displaySummary: String
        get() = "llama.cpp [${scaleLevel.levelName}] - ${modelParamBillion}B $quantization, Ctx: $contextLength, Gen: $genTokens"

    override val parameters: Map<String, Any>
        get() = mapOf(
            "modelParamBillion" to modelParamBillion,
            "quantization" to quantization,
            "contextLength" to contextLength,
            "batchPromptTokens" to batchPromptTokens,
            "genTokens" to genTokens,
            "threadCount" to threadCount
        )
}

/**
 * llama.cpp 规模策略
 */
class LlamaCppScalePolicy : BenchmarkScalePolicy {
    override val benchmarkId: BenchmarkId = BenchmarkId.LLAMA_CPP

    override fun getSupportedScales(): List<IScaleConfiguration> = listOf(
        LlamaCppConfig(ScaleLevel.SMOKE, 0.5, "Q4_0", 512, 128, 32, threadCount = 2),
        LlamaCppConfig(ScaleLevel.SMALL, 1.1, "Q4_0", 1024, 256, 64, threadCount = 4),
        LlamaCppConfig(ScaleLevel.STANDARD, 3.2, "Q4_K_M", 2048, 512, 128, threadCount = 0),
        LlamaCppConfig(ScaleLevel.LARGE, 7.0, "Q4_K_M", 4096, 1024, 256, threadCount = 0)
    )

    override fun getConfigurationForLevel(level: ScaleLevel): IScaleConfiguration {
        return getSupportedScales().firstOrNull { it.scaleLevel == level }
            ?: LlamaCppConfig(level, 3.2, "Q4_K_M", 2048, 512, 128, 0)
    }

    override fun estimateResources(config: IScaleConfiguration, device: DeviceResourceSnapshot): ResourceEstimation {
        val cfg = config as LlamaCppConfig
        val threads = if (cfg.threadCount <= 0) device.cpuCoreCount else cfg.threadCount

        // 内存估算：Q4 模型权重约为 0.6GB / 1B 参数
        val modelWeightBytes = (cfg.modelParamBillion * 650.0 * 1024.0 * 1024.0).toLong()
        // KV Cache 内存：随上下文长度线性增长 (约 150KB / token for 3B)
        val kvCacheBytes = cfg.contextLength.toLong() * (cfg.modelParamBillion * 50.0 * 1024.0).toLong()
        // 计算图与激活工作区 (~250MB)
        val mem = modelWeightBytes + kvCacheBytes + (250L * 1024L * 1024L)

        val timeMs = when (cfg.scaleLevel) {
            ScaleLevel.SMOKE -> 2500L
            ScaleLevel.SMALL -> 6000L
            ScaleLevel.STANDARD -> 18000L
            ScaleLevel.LARGE -> 45000L
            ScaleLevel.ADVANCED -> 18000L
        }

        return ResourceEstimation(
            benchmarkId = benchmarkId,
            targetScaleLevel = cfg.scaleLevel,
            estimatedPeakMemoryBytes = mem,
            estimatedMinRuntimeMs = timeMs / 2,
            estimatedMaxRuntimeMs = timeMs * 2,
            estimatedTempStorageBytes = modelWeightBytes,
            recommendedThreads = minOf(threads, 8), // LLM 推理多核超过 8 核后往往带宽饱和
            growthOrder = MemoryGrowthOrder.LINEAR,
            confidence = EstimationConfidence.HIGH,
            errorSources = listOf("内存带宽通道饱和", "ARMv8.4 DotProd / I8MM 指令支持差异"),
            formulaExplanation = "Mem = ModelWeights(0.65GB/B) + KVCache(Tokens) + ScratchBuffer"
        )
    }

    override fun evaluateComparability(config: IScaleConfiguration, actualObservedMemoryBytes: Long?): Pair<Boolean, String> {
        val cfg = config as LlamaCppConfig
        return if (cfg.scaleLevel == ScaleLevel.STANDARD && cfg.modelParamBillion == 3.2 && cfg.quantization == "Q4_K_M") {
            Pair(true, "采用标准 3B Q4_K_M 规模与 512/128 Token 基线，具有行业通用端侧大模型横向可比性")
        } else {
            Pair(false, "模型规模 (${cfg.modelParamBillion}B ${cfg.quantization}) 仅限同模型规格比对")
        }
    }
}
