package com.androidbench

import com.androidbench.core.*
import com.androidbench.guard.*
import com.androidbench.probe.DeviceResourceProber
import com.androidbench.report.ComparabilityValidator
import com.androidbench.scale.ScaleSelectionEngine
import com.androidbench.scale.ScaleSelectionException
import com.androidbench.scale.policies.*
import java.io.File

/**
 * 对应第 14.9 节：全量验收测试套件 (Kotlin 版)
 */
class ScaleManagerTestSuite {

    private val policies = mapOf(
        BenchmarkId.COREMARK to CoreMarkScalePolicy(),
        BenchmarkId.ZSTD to ZstdScalePolicy(),
        BenchmarkId.STREAM to StreamScalePolicy(),
        BenchmarkId.TINYMEMBENCH to TinyMemBenchScalePolicy(),
        BenchmarkId.NPB_EP to NpbEpScalePolicy(),
        BenchmarkId.HPL to HplScalePolicy(),
        BenchmarkId.GRAPH500 to Graph500ScalePolicy()
    )

    private val selectionEngine = ScaleSelectionEngine(policies)
    private val comparabilityValidator = ComparabilityValidator()

    // 模拟低内存设备 (2GB RAM，仅剩 350MB 可用)
    private val lowRamDevice = DeviceResourceSnapshot(
        abi = "arm64-v8a",
        cpuCoreCount = 4,
        cpuClusters = listOf(
            CpuCluster(0, 4, listOf(0, 1, 2, 3), 1000000L, 2000000L, "Little")
        ),
        totalPhysicalRamBytes = 2L * 1024L * 1024L * 1024L,
        systemAvailRamBytes = 350L * 1024L * 1024L,
        appMaxHeapBytes = 192L * 1024L * 1024L,
        appCurrentAllocatedBytes = 50L * 1024L * 1024L,
        appInternalStorageFreeBytes = 500L * 1024L * 1024L,
        thermalState = ThermalState.NONE,
        isPowerConnected = false,
        batteryPercent = 45,
        collectionTimestampMs = System.currentTimeMillis(),
        isConservativeFallback = false,
        dataSourceNotes = mapOf("test" to "simulated low ram"),
        collectionErrors = emptyList()
    )

    // 模拟旗舰设备 (12GB RAM，可用 7GB)
    private val flagshipDevice = DeviceResourceSnapshot(
        abi = "arm64-v8a",
        cpuCoreCount = 8,
        cpuClusters = listOf(
            CpuCluster(0, 4, listOf(0, 1, 2, 3), 1800000L, 2000000L, "Little"),
            CpuCluster(1, 3, listOf(4, 5, 6), 2500000L, 3100000L, "Big"),
            CpuCluster(2, 1, listOf(7), 3000000L, 3400000L, "Prime")
        ),
        totalPhysicalRamBytes = 12L * 1024L * 1024L * 1024L,
        systemAvailRamBytes = 7L * 1024L * 1024L * 1024L,
        appMaxHeapBytes = 512L * 1024L * 1024L,
        appCurrentAllocatedBytes = 80L * 1024L * 1024L,
        appInternalStorageFreeBytes = 50L * 1024L * 1024L * 1024L,
        thermalState = ThermalState.NONE,
        isPowerConnected = true,
        batteryPercent = 90,
        collectionTimestampMs = System.currentTimeMillis(),
        isConservativeFallback = false,
        dataSourceNotes = mapOf("test" to "simulated flagship"),
        collectionErrors = emptyList()
    )

    /**
     * 验收准则 1: 每个已集成基准均有独立资源估算器
     */
    fun testIndependentEstimatorsExist() {
        for (id in BenchmarkId.entries.filter { it.phase == BenchmarkPhase.P0 }) {
            val policy = policies[id] ?: throw AssertionError("P0 Benchmark $id 缺失独立 ScalePolicy")
            val stdConfig = policy.getConfigurationForLevel(ScaleLevel.STANDARD)
            val est = policy.estimateResources(stdConfig, flagshipDevice)
            assert(est.estimatedPeakMemoryBytes > 0) { "$id 估算内存异常" }
            assert(est.estimatedMaxRuntimeMs > 0) { "$id 估算耗时异常" }
            assert(est.formulaExplanation.isNotBlank()) { "$id 缺失估算公式解释" }
        }
    }

    /**
     * 验收准则 2 & 4: 低内存设备下拒绝危险配置或安全自动降级
     */
    fun testLowMemoryAutoDegradeOrRefusal() {
        val tightBudget = ResourceBudget(
            maxAllowedPeakMemoryBytes = 80L * 1024L * 1024L, // 仅允许 80MB 内存
            maxTimeBudgetMs = 30000L,
            maxTempStorageBytes = 100L * 1024L * 1024L,
            maxThreadConcurrency = 4,
            allowAdaptiveDegrade = true
        )

        // STREAM Standard 需要约 352MB 内存，超出 80MB 预算
        val decision = selectionEngine.selectScale(
            benchmarkId = BenchmarkId.STREAM,
            requestedLevel = ScaleLevel.STANDARD,
            device = lowRamDevice,
            budget = tightBudget
        )

        // 验证：必须发生自动裁剪，且裁剪原因明确记录
        assert(decision.wasPruned) { "STREAM 在 80MB 预算下必须被裁剪" }
        assert(decision.selectedLevel == ScaleLevel.SMALL || decision.selectedLevel == ScaleLevel.SMOKE) {
            "降级后规模应为 Small 或 Smoke，当前为: ${decision.selectedLevel}"
        }
        assert(!decision.pruneReason.isNullOrBlank()) { "必须记录裁剪原因" }
    }

    /**
     * 验收准则 3: 标准设备对比模式下禁止静默降级，必须显式抛出异常
     */
    fun testStandardComparisonModeRefusal() {
        val standardStrictBudget = ResourceBudget(
            maxAllowedPeakMemoryBytes = 80L * 1024L * 1024L, // 80MB
            maxTimeBudgetMs = 30000L,
            maxTempStorageBytes = 100L * 1024L * 1024L,
            maxThreadConcurrency = 4,
            allowAdaptiveDegrade = true,
            isStandardComparisonMode = true // 严格标准模式
        )

        var caught = false
        try {
            selectionEngine.selectScale(
                benchmarkId = BenchmarkId.STREAM,
                requestedLevel = ScaleLevel.STANDARD,
                device = lowRamDevice,
                budget = standardStrictBudget
            )
        } catch (e: ScaleSelectionException) {
            if (e.reasonCode == "STANDARD_MODE_RESOURCE_INSUFFICIENT") {
                caught = true
            }
        }
        assert(caught) { "标准设备对比模式下，资源不足时必须显式拒绝并提示用户，禁止静默降级" }
    }

    /**
     * 验收准则 5: 崩溃与 LMK 杀死恢复测试
     */
    fun testCrashAndLmkRecovery() {
        val tempDir = File(".tools/test_journal_temp")
        tempDir.mkdirs()
        val journal = CrashRecoveryJournal(tempDir)

        // 模拟开始任务并推进到 KERNEL_EXECUTION 阶段后突然非正常退出 (未调用 finishTask)
        journal.startTask(BenchmarkId.STREAM, "Testing STREAM")
        journal.recordStage(BenchmarkId.STREAM, ExecutionStage.KERNEL_EXECUTION, "Array N=10M")

        // 模拟应用重启，重新实例化 Journal
        val recoveryJournal = CrashRecoveryJournal(tempDir)
        val interrupted = recoveryJournal.checkAndRecoverInterruptedTask()

        assert(interrupted != null) { "必须成功检测到未完成的崩溃/LMK 任务" }
        assert(interrupted!!.isRecognizedAsCrashOrLmk) { "必须标记为崩溃或强杀" }
        assert(interrupted.lastStage == ExecutionStage.KERNEL_EXECUTION) { "最后阶段记录错误" }

        tempDir.deleteRecursively()
    }

    /**
     * 验收准则 8: 严格区分标准成绩、自适应成绩与 Smoke 验证结果
     */
    fun testComparabilitySeparation() {
        val stdReport = BenchmarkReportMetadata(
            benchmarkId = BenchmarkId.STREAM,
            benchmarkVersion = "5.10",
            sourceCommitHash = "git-abc1234",
            scaleLevel = ScaleLevel.STANDARD,
            isUpstreamStandardScale = true,
            isAdaptiveScale = false,
            actualInputScaleMetric = "Array N: 10,000,000 (320MB)",
            actualInputParameters = mapOf("arrayLength" to 10000000L),
            threadCount = 8,
            parallelRuntime = ParallelRuntimeType.OPENMP,
            estimatedPeakMemoryBytes = 352L * 1024L * 1024L,
            observedPeakMemoryBytes = 350L * 1024L * 1024L,
            runtimeBudgetMs = 30000L,
            actualRuntimeMs = 12000L,
            wasPruned = false,
            pruneReason = null,
            decisionLog = "Selected standard directly",
            isCorrectnessVerified = true,
            verificationDetails = "All 4 kernels verified with tolerance 1e-11",
            isStandardEligible = true,
            isComparableWithOtherDevices = true,
            comparabilityGroupKey = "STREAM_STANDARD_10M_8T",
            primaryMetricName = "Triad Bandwidth (MB/s)",
            primaryMetricValue = 48520.0,
            executionStatus = BenchmarkExecutionStatus.COMPLETED_VERIFIED,
            deviceSnapshot = flagshipDevice
        )

        val adaptiveReport = stdReport.copy(
            scaleLevel = ScaleLevel.SMALL,
            isAdaptiveScale = true,
            wasPruned = true,
            pruneReason = "Low memory safe degrade",
            actualInputScaleMetric = "Array N: 1,500,000 (48MB)"
        )

        val smokeReport = stdReport.copy(
            scaleLevel = ScaleLevel.SMOKE,
            isUpstreamStandardScale = false,
            actualInputScaleMetric = "Array N: 200,000 (6.4MB)"
        )

        val stdResult = comparabilityValidator.validateEligibility(stdReport)
        val adaptiveResult = comparabilityValidator.validateEligibility(adaptiveReport)
        val smokeResult = comparabilityValidator.validateEligibility(smokeReport)

        assert(stdResult.isEligibleForOfficialRanking) { "标准成绩必须具备排行榜资格" }
        assert(!adaptiveResult.isEligibleForOfficialRanking) { "自适应裁剪成绩严禁进入标准官方天梯" }
        assert(adaptiveResult.comparabilityTier == "ADAPTIVE_DEVICE_INTERNAL")
        assert(!smokeResult.isEligibleForOfficialRanking) { "Smoke 快速验证严禁进入官方天梯" }
    }
}
