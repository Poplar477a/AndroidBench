package com.androidbench.core

/**
 * 对应第 14.2 节：设备热状态枚举 (对标 Android PowerManager.OnThermalStatusChangedListener)
 */
enum class ThermalState(val level: Int, val description: String) {
    NONE(0, "正常无温升"),
    LIGHT(1, "轻微温升，微弱调度"),
    MODERATE(2, "中度温升，可能出现轻微降频"),
    SEVERE(3, "重度发热，强烈降频"),
    CRITICAL(4, "极高发热，系统启动散热保护"),
    EMERGENCY(5, "紧急状态，即将关机或强制杀进程"),
    SHUTDOWN(6, "设备过热关机中"),
    UNKNOWN(-1, "无法获取温控状态 (使用保守预算)")
}

/**
 * 处理器集群拓扑
 */
data class CpuCluster(
    val clusterId: Int,
    val coreCount: Int,
    val coreIndices: List<Int>,
    val minFreqKHz: Long,
    val maxFreqKHz: Long,
    val clusterType: String // "Prime", "Big", "Mid", "Little"
)

/**
 * 对应第 14.2 节：设备资源探测快照 (包含数据源、时间戳、成功标志与保守策略)
 */
data class DeviceResourceSnapshot(
    val abi: String,
    val cpuCoreCount: Int,
    val cpuClusters: List<CpuCluster>,
    val totalPhysicalRamBytes: Long,
    val systemAvailRamBytes: Long,
    val appMaxHeapBytes: Long,
    val appCurrentAllocatedBytes: Long,
    val appInternalStorageFreeBytes: Long,
    val thermalState: ThermalState,
    val isPowerConnected: Boolean,
    val batteryPercent: Int,
    val collectionTimestampMs: Long,
    val isConservativeFallback: Boolean,
    val dataSourceNotes: Map<String, String>,
    val collectionErrors: List<String>
) {
    /**
     * 计算当前原生分配可用的安全物理内存预算上限
     * 保守原则：不简单等于系统当前可用内存，扣除系统低内存红线与应用安全余量 (默认预留至少 30% 或 512MB)
     */
    fun calculateSafeNativeMemoryBudget(): Long {
        val conservativeHeadroom = 512L * 1024L * 1024L // 512MB 系统保护底线
        val usableSystem = (systemAvailRamBytes * 0.70).toLong() // 70% 安全系数
        val safeBudget = minOf(usableSystem, (totalPhysicalRamBytes * 0.50).toLong())
        return maxOf(128L * 1024L * 1024L, safeBudget - conservativeHeadroom)
    }
}

/**
 * 内存随输入规模的理论算法增长阶数
 */
enum class MemoryGrowthOrder {
    CONSTANT,       // O(1)
    LINEAR,         // O(N)
    QUADRATIC,      // O(N^2) 如 HPL 矩阵
    CUBIC,          // O(N^3)
    GRAPH_SPARSE    // O(V + E) 如 Graph500
}

/**
 * 估算置信度
 */
enum class EstimationConfidence {
    HIGH,           // 高置信度 (算法确定性分配)
    MEDIUM,         // 中置信度 (依赖运行时工作区)
    LOW             // 低置信度 (受中间产物或外部环境影响大)
}

/**
 * 对应第 14.3 节：基准估算器统一返回接口
 */
data class ResourceEstimation(
    val benchmarkId: BenchmarkId,
    val targetScaleLevel: ScaleLevel,
    val estimatedPeakMemoryBytes: Long,
    val estimatedMinRuntimeMs: Long,
    val estimatedMaxRuntimeMs: Long,
    val estimatedTempStorageBytes: Long,
    val recommendedThreads: Int,
    val growthOrder: MemoryGrowthOrder,
    val confidence: EstimationConfidence,
    val errorSources: List<String>,
    val formulaExplanation: String
)

/**
 * 对应第 14.3 节与 14.6 节：统一运行资源预算配置
 */
data class ResourceBudget(
    val maxAllowedPeakMemoryBytes: Long,
    val maxTimeBudgetMs: Long,
    val maxTempStorageBytes: Long,
    val maxThreadConcurrency: Int,
    val allowAdaptiveDegrade: Boolean = true,
    val isStandardComparisonMode: Boolean = false // 正式设备对比模式下禁止静默降级
)

/**
 * 被排除的候选规模原因
 */
data class ExcludedCandidate(
    val scaleLevel: ScaleLevel,
    val reasonCode: String,
    val detailMessage: String,
    val estimatedMemoryBytes: Long,
    val estimatedRuntimeMs: Long
)

/**
 * 对应第 14.6 节：规模决策结果 (完整决策信息，满足复现性)
 */
data class ScaleDecision(
    val benchmarkId: BenchmarkId,
    val requestedLevel: ScaleLevel,
    val selectedLevel: ScaleLevel,
    val wasPruned: Boolean,
    val pruneReason: String?,
    val inputParameters: Map<String, Any>,
    val estimatedResources: ResourceEstimation,
    val excludedCandidates: List<ExcludedCandidate>,
    val decisionTimestampMs: Long,
    val reproducibilityHash: String
)

/**
 * 对应第 14.7 节：执行状态与错误分类
 */
enum class BenchmarkExecutionStatus {
    IDLE,
    ESTIMATING,
    PRE_CHECK_PASSED,
    RUNNING,
    COMPLETED_VERIFIED,
    COMPLETED_UNVERIFIED,
    OOM_PREVENTED,          // 预算超限或原生预分配拒绝
    TIMEOUT,                // 超过单项/单次运行预算
    USER_CANCELLED,         // 用户主动取消
    CORRECTNESS_FAILED,     // 计算校验失败
    SYSTEM_INTERRUPTED,     // 系统异常或进程被杀死前置中断
    STORAGE_EXHAUSTED       // 临时存储不足
}
