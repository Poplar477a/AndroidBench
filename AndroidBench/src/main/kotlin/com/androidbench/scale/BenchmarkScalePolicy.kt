package com.androidbench.scale

import com.androidbench.core.BenchmarkId
import com.androidbench.core.DeviceResourceSnapshot
import com.androidbench.core.ResourceBudget
import com.androidbench.core.ResourceEstimation
import com.androidbench.core.ScaleLevel

/**
 * 候选输入规模配置接口
 */
interface IScaleConfiguration {
    val scaleLevel: ScaleLevel
    val displaySummary: String
    val parameters: Map<String, Any>
}

/**
 * 对应第 14.1 与 14.3 节：每个基准项目必须实现的独立资源估算与策略接口
 * 明确禁止全局统一缩放系数，每个基准必须根据自身算法复杂度、内存访问模式定义独立策略
 */
interface BenchmarkScalePolicy {
    val benchmarkId: BenchmarkId

    /**
     * 获取该基准上游支持的合法规模配置列表
     */
    fun getSupportedScales(): List<IScaleConfiguration>

    /**
     * 获取指定等级的合法配置
     */
    fun getConfigurationForLevel(level: ScaleLevel): IScaleConfiguration

    /**
     * 独立资源估算器：估算指定配置在目标设备上的资源需求
     */
    fun estimateResources(
        config: IScaleConfiguration,
        device: DeviceResourceSnapshot
    ): ResourceEstimation

    /**
     * 运行前预算校验：检查估算是否在允许预算内
     * @return 校验通过返回 null；超限时返回具体的拒绝/裁剪原因
     */
    fun checkBudgetExceeded(
        estimation: ResourceEstimation,
        budget: ResourceBudget
    ): String? {
        if (estimation.estimatedPeakMemoryBytes > budget.maxAllowedPeakMemoryBytes) {
            val peakMB = estimation.estimatedPeakMemoryBytes / (1024 * 1024)
            val budgetMB = budget.maxAllowedPeakMemoryBytes / (1024 * 1024)
            return "预计峰值内存 (${peakMB} MB) 超过可用内存预算 (${budgetMB} MB)"
        }
        if (estimation.estimatedMaxRuntimeMs > budget.maxTimeBudgetMs) {
            val maxSec = estimation.estimatedMaxRuntimeMs / 1000
            val budgetSec = budget.maxTimeBudgetMs / 1000
            return "预计最大运行耗时 (${maxSec} s) 超过单项时间预算 (${budgetSec} s)"
        }
        if (estimation.estimatedTempStorageBytes > budget.maxTempStorageBytes) {
            val storageMB = estimation.estimatedTempStorageBytes / (1024 * 1024)
            val budgetStorageMB = budget.maxTempStorageBytes / (1024 * 1024)
            return "预计临时存储 (${storageMB} MB) 超过存储预算 (${budgetStorageMB} MB)"
        }
        return null
    }

    /**
     * 判定该测试结果是否具有设备间横向可比性
     */
    fun evaluateComparability(
        config: IScaleConfiguration,
        actualObservedMemoryBytes: Long?
    ): Pair<Boolean, String>
}
