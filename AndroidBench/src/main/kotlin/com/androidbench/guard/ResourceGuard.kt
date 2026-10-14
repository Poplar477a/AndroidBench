package com.androidbench.guard

import com.androidbench.core.*
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 对应第 14.7 节：执行阶段检查点
 */
enum class ExecutionStage {
    INIT,
    PRE_CHECK,
    NATIVE_ALLOCATION,
    WARMUP,
    KERNEL_EXECUTION,
    VERIFICATION,
    CLEANUP,
    COMPLETED
}

/**
 * 对应第 14.7 节：统一资源保护与执行控制器
 */
class ResourceGuard(
    private val journal: CrashRecoveryJournal? = null
) {
    private val isCancelled = AtomicBoolean(false)
    private var currentStage: ExecutionStage = ExecutionStage.INIT
    private var retryCount = 0
    private val maxAllowedRetries = 1 // 严禁无限次自动重试

    /**
     * 校验当前线程是否非主线程
     * 严格杜绝在 Android 主线程执行基准内核引起 ANR
     */
    fun assertNotMainThread() {
        val threadName = Thread.currentThread().name
        if (threadName.equals("main", ignoreCase = true) || threadName.contains("UI", ignoreCase = true)) {
            throw IllegalStateException("CRITICAL POLICY VIOLATION: 基准内核严禁在 Android UI 主线程执行！必须使用后台协程/Worker 线程。")
        }
    }

    /**
     * 请求主动取消测试
     */
    fun requestCancellation() {
        isCancelled.set(true)
    }

    /**
     * 是否已被取消
     */
    fun isCancellationRequested(): Boolean = isCancelled.get()

    /**
     * 原生大内存分配前预算预检
     */
    fun preCheckAllocation(
        requestedBytes: Long,
        budget: ResourceBudget,
        device: DeviceResourceSnapshot
    ) {
        if (requestedBytes > budget.maxAllowedPeakMemoryBytes) {
            throw OutOfMemoryError(
                "PRE_CHECK_FAILED: 请求原生内存 (${requestedBytes / (1024 * 1024)}MB) 超过硬性预算上限 (${budget.maxAllowedPeakMemoryBytes / (1024 * 1024)}MB)"
            )
        }

        val safeLimit = device.calculateSafeNativeMemoryBudget()
        if (requestedBytes > safeLimit) {
            throw OutOfMemoryError(
                "PRE_CHECK_FAILED: 请求原生内存 (${requestedBytes / (1024 * 1024)}MB) 超过设备保守安全红线 (${safeLimit / (1024 * 1024)}MB)"
            )
        }
    }

    /**
     * 更新阶段检查点并同步至持久化 Journal
     */
    fun updateStage(benchmarkId: BenchmarkId, stage: ExecutionStage, detail: String = "") {
        currentStage = stage
        journal?.recordStage(benchmarkId, stage, detail)
        if (isCancelled.get()) {
            throw CancellationException("用户在阶段 $stage 取消了测试")
        }
    }

    /**
     * 自动重试许可判定
     * 规则：只允许在资源重新满足 (如内存释放) 时最多降级重试 1 次，严禁无限次重试
     */
    fun canAttemptRetry(device: DeviceResourceSnapshot, budget: ResourceBudget): Boolean {
        if (retryCount >= maxAllowedRetries) return false
        val currentAvail = device.calculateSafeNativeMemoryBudget()
        return if (currentAvail > budget.maxAllowedPeakMemoryBytes) {
            retryCount++
            true
        } else {
            false
        }
    }
}
