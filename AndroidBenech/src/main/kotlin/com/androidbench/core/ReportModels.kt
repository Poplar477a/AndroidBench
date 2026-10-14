package com.androidbench.core

/**
 * 并行实现类型
 */
enum class ParallelRuntimeType(val label: String) {
    SINGLE_THREAD("单线程"),
    PTHREAD("POSIX pthreads"),
    OPENMP("OpenMP"),
    ASYNC_COROUTINE("原生协程/异步工作池")
}

/**
 * 对应第 14.8 节：基准测试报告元数据契约
 */
data class BenchmarkReportMetadata(
    // 1. 基准基本信息
    val benchmarkId: BenchmarkId,
    val benchmarkVersion: String,
    val sourceCommitHash: String,

    // 2. 规模与模式
    val scaleLevel: ScaleLevel,
    val isUpstreamStandardScale: Boolean, // 是否符合上游官方标准规模
    val isAdaptiveScale: Boolean,         // 是否为自适应裁剪规模
    
    // 3. 实际输入度量
    val actualInputScaleMetric: String,   // 例如 "Array Length: 10,000,000 (Stream)" / "Matrix N: 2000 (HPL)"
    val actualInputParameters: Map<String, Any>,

    // 4. 并行度与架构
    val threadCount: Int,
    val parallelRuntime: ParallelRuntimeType,

    // 5. 内存度量 (估算 vs 实测)
    val estimatedPeakMemoryBytes: Long,
    val observedPeakMemoryBytes: Long?,   // 原生堆观测或 VmHWM

    // 6. 时间度量 (预算 vs 实测)
    val runtimeBudgetMs: Long,
    val actualRuntimeMs: Long,

    // 7. 规模裁剪记录
    val wasPruned: Boolean,
    val pruneReason: String?,
    val decisionLog: String,              // 自动选择日志与决策链

    // 8. 校验与可比性判定
    val isCorrectnessVerified: Boolean,
    val verificationDetails: String,
    val isStandardEligible: Boolean,      // 是否符合标准成绩上榜要求
    val isComparableWithOtherDevices: Boolean, // 与其他设备是否可比
    val comparabilityGroupKey: String,   // 结果分组依据：例如 "STREAM_DOUBLE_STANDARD_10M_T8"

    // 9. 性能得分指标
    val primaryMetricName: String,        // 例如 "CoreMark/MHz", "MB/s", "GFLOPS", "Compression MB/s"
    val primaryMetricValue: Double,
    val secondaryMetrics: Map<String, Double> = emptyMap(),

    // 10. 执行状态
    val executionStatus: BenchmarkExecutionStatus,
    val deviceSnapshot: DeviceResourceSnapshot?
) {
    /**
     * 校验成绩是否能够混入固定标准规模排行榜
     * 规则：必须是经过正确性验证、未被裁剪、且符合上游标准规模的测试
     */
    fun canEnterStandardLeaderboard(): Boolean {
        return isStandardEligible &&
                isCorrectnessVerified &&
                !wasPruned &&
                scaleLevel == ScaleLevel.STANDARD &&
                executionStatus == BenchmarkExecutionStatus.COMPLETED_VERIFIED
    }
}
