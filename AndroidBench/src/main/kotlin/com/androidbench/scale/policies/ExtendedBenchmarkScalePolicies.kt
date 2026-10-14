package com.androidbench.scale.policies

import com.androidbench.core.*
import com.androidbench.scale.BenchmarkScalePolicy
import com.androidbench.scale.IScaleConfiguration

/**
 * HPL 矩阵规模配置
 * 内存随 N 呈平方级增长 O(N^2)，运算量呈立方级增长 O(N^3)
 */
data class HplConfig(
    override val scaleLevel: ScaleLevel,
    val matrixN: Int,
    val blockSizeNB: Int = 128,
    val threadCount: Int = 0
) : IScaleConfiguration {
    val matrixMemoryBytes: Long
        get() = (matrixN.toLong() * matrixN.toLong() * 8L) // FP64 double

    override val displaySummary: String
        get() = "HPL [${scaleLevel.levelName}] - Matrix N=$matrixN, NB=$blockSizeNB, Mem=${matrixMemoryBytes / (1024 * 1024)}MB"

    override val parameters: Map<String, Any>
        get() = mapOf(
            "matrixN" to matrixN,
            "blockSizeNB" to blockSizeNB,
            "threadCount" to threadCount,
            "matrixMemoryBytes" to matrixMemoryBytes
        )
}

/**
 * 对应第 14.5 节 G 项：HPL (High Performance Linpack) 规模策略
 */
class HplScalePolicy : BenchmarkScalePolicy {
    override val benchmarkId: BenchmarkId = BenchmarkId.HPL

    override fun getSupportedScales(): List<IScaleConfiguration> = listOf(
        HplConfig(ScaleLevel.SMOKE, matrixN = 512, blockSizeNB = 64),    // ~2MB
        HplConfig(ScaleLevel.SMALL, matrixN = 2048, blockSizeNB = 128),  // ~33MB
        HplConfig(ScaleLevel.STANDARD, matrixN = 8192, blockSizeNB = 128), // ~536MB
        HplConfig(ScaleLevel.LARGE, matrixN = 16384, blockSizeNB = 256)   // ~2.1GB
    )

    override fun getConfigurationForLevel(level: ScaleLevel): IScaleConfiguration {
        return getSupportedScales().firstOrNull { it.scaleLevel == level }
            ?: HplConfig(level, 8192, 128)
    }

    override fun estimateResources(
        config: IScaleConfiguration,
        device: DeviceResourceSnapshot
    ): ResourceEstimation {
        val hConfig = config as HplConfig
        val effectiveThreads = if (hConfig.threadCount <= 0) device.cpuCoreCount else hConfig.threadCount

        // 内存需求：N^2 * 8 (主矩阵 A) + BLAS 工作区与面板分解 (~25% 额外内存)
        val estimatedMemory = (hConfig.matrixMemoryBytes * 1.25).toLong() + (32L * 1024L * 1024L)

        // 运算量：(2/3) * N^3 FLOPs
        val totalFlops = (2.0 / 3.0) * Math.pow(hConfig.matrixN.toDouble(), 3.0)
        // 现代 ARM64 旗舰核心 (Cortex-X4/Oryon) FP64 峰值大约单核 50~100 GFLOPS
        val assumedGflops = 20.0 * effectiveThreads // 保守假设实测 20 GFLOPS/core
        val estimatedSec = maxOf(1.0, totalFlops / (assumedGflops * 1e9))
        val estimatedRuntimeMs = (estimatedSec * 1000).toLong()

        val minRuntime = maxOf(500L, estimatedRuntimeMs / 2)
        val maxRuntime = maxOf(minRuntime + 5000L, estimatedRuntimeMs * 3)

        return ResourceEstimation(
            benchmarkId = benchmarkId,
            targetScaleLevel = hConfig.scaleLevel,
            estimatedPeakMemoryBytes = estimatedMemory,
            estimatedMinRuntimeMs = minRuntime,
            estimatedMaxRuntimeMs = maxRuntime,
            estimatedTempStorageBytes = 1L * 1024L * 1024L,
            recommendedThreads = effectiveThreads,
            growthOrder = MemoryGrowthOrder.QUADRATIC,
            confidence = EstimationConfidence.HIGH,
            errorSources = listOf("矩阵阶数必须为块大小整数倍", "BLAS DGEMM 线程调度开销"),
            formulaExplanation = "Mem = 1.25 * N^2 * 8 + 32MB; FLOPs = (2/3) * N^3"
        )
    }

    override fun evaluateComparability(
        config: IScaleConfiguration,
        actualObservedMemoryBytes: Long?
    ): Pair<Boolean, String> {
        val hConfig = config as HplConfig
        return if (hConfig.scaleLevel == ScaleLevel.STANDARD) {
            Pair(true, "采用标准 N=8192 FP64 规模，符合移动端/边缘端 HPL 标准基线")
        } else {
            Pair(false, "N=${hConfig.matrixN} 属于自适应/裁剪规模，不同 N 值的 GFLOPS 无法直接等价比较")
        }
    }
}

/**
 * Graph500 规模配置
 * 内存随规模 Scale S 与 EdgeFactor 增长：V = 2^S, E = 16 * 2^S
 */
data class Graph500Config(
    override val scaleLevel: ScaleLevel,
    val scaleS: Int,
    val edgeFactor: Int = 16,
    val threadCount: Int = 0
) : IScaleConfiguration {
    val vertexCount: Long
        get() = 1L shl scaleS
    val edgeCount: Long
        get() = vertexCount * edgeFactor

    override val displaySummary: String
        get() = "Graph500 [${scaleLevel.levelName}] - Scale $scaleS (V=${vertexCount}, E=${edgeCount})"

    override val parameters: Map<String, Any>
        get() = mapOf(
            "scaleS" to scaleS,
            "edgeFactor" to edgeFactor,
            "vertexCount" to vertexCount,
            "edgeCount" to edgeCount
        )
}

/**
 * 对应第 14.5 节 H 项：Graph500 图遍历与不规则访存策略
 */
class Graph500ScalePolicy : BenchmarkScalePolicy {
    override val benchmarkId: BenchmarkId = BenchmarkId.GRAPH500

    override fun getSupportedScales(): List<IScaleConfiguration> = listOf(
        Graph500Config(ScaleLevel.SMOKE, scaleS = 10),    // V=1024, E=16K (~1MB)
        Graph500Config(ScaleLevel.SMALL, scaleS = 14),    // V=16K, E=256K (~16MB)
        Graph500Config(ScaleLevel.STANDARD, scaleS = 18), // V=262K, E=4.2M (~250MB)
        Graph500Config(ScaleLevel.LARGE, scaleS = 21)     // V=2M, E=33.5M (~2GB)
    )

    override fun getConfigurationForLevel(level: ScaleLevel): IScaleConfiguration {
        return getSupportedScales().firstOrNull { it.scaleLevel == level }
            ?: Graph500Config(level, 18)
    }

    override fun estimateResources(
        config: IScaleConfiguration,
        device: DeviceResourceSnapshot
    ): ResourceEstimation {
        val gConfig = config as Graph500Config
        val effectiveThreads = if (gConfig.threadCount <= 0) device.cpuCoreCount else gConfig.threadCount

        // 内存需求：CSR 压缩稀疏行表示法
        // row_starts 数组: (V + 1) * 8 bytes
        // column 边数组: 2 * E * 8 bytes (无向图)
        // BFS 队列与 Parent 数组: V * 8 bytes + V 位图
        val csrMemory = ((gConfig.vertexCount + 1) * 8L) + (gConfig.edgeCount * 2 * 8L) + (gConfig.vertexCount * 16L)
        val estimatedMemory = csrMemory + (32L * 1024L * 1024L)

        // 耗时估算：Graph500 包含图生成器 (Kronecker 图) 与 64 次 BFS 搜索
        val estimatedRuntimeMs = when (gConfig.scaleS) {
            10 -> 800L
            14 -> 3000L
            18 -> 12000L
            21 -> 45000L
            else -> 15000L
        }

        return ResourceEstimation(
            benchmarkId = benchmarkId,
            targetScaleLevel = gConfig.scaleLevel,
            estimatedPeakMemoryBytes = estimatedMemory,
            estimatedMinRuntimeMs = estimatedRuntimeMs / 2,
            estimatedMaxRuntimeMs = estimatedRuntimeMs * 2,
            estimatedTempStorageBytes = 512L * 1024L,
            recommendedThreads = effectiveThreads,
            growthOrder = MemoryGrowthOrder.GRAPH_SPARSE,
            confidence = EstimationConfidence.HIGH,
            errorSources = listOf("图生成器随机数开销", "图遍历不规则访存对 Cache 命中率的扰动"),
            formulaExplanation = "Mem = (V + 2E)*8 + auxiliary; V=2^S, E=16*V"
        )
    }

    override fun evaluateComparability(
        config: IScaleConfiguration,
        actualObservedMemoryBytes: Long?
    ): Pair<Boolean, String> {
        val gConfig = config as Graph500Config
        return if (gConfig.scaleLevel == ScaleLevel.STANDARD && gConfig.scaleS == 18) {
            Pair(true, "采用标准 Scale 18 (26.2 万顶点 / 420 万边)，符合官方中型图遍历基线")
        } else {
            Pair(false, "Scale ${gConfig.scaleS} 属于非标准自适应图规模，TEPS (Traversed Edges Per Second) 不得跨规模混用")
        }
    }
}
