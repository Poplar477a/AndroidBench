package com.androidbench.scale

import com.androidbench.core.*
import java.security.MessageDigest

/**
 * 规模选择异常
 */
class ScaleSelectionException(
    val reasonCode: String,
    message: String,
    val excludedCandidates: List<ExcludedCandidate>
) : Exception("[$reasonCode] $message")

/**
 * 对应第 14.6 节：确定性、可复现的自动规模选择引擎
 */
class ScaleSelectionEngine(
    private val policies: Map<BenchmarkId, BenchmarkScalePolicy>
) {

    /**
     * 执行规模选择
     * @param benchmarkId 目标基准
     * @param requestedLevel 用户请求的目标规模等级 (默认 STANDARD)
     * @param device 设备资源探测快照
     * @param budget 用户配置的运行资源预算
     * @return 最终确定的规模决策与完整审计信息
     */
    fun selectScale(
        benchmarkId: BenchmarkId,
        requestedLevel: ScaleLevel = ScaleLevel.STANDARD,
        device: DeviceResourceSnapshot,
        budget: ResourceBudget
    ): ScaleDecision {
        val policy = policies[benchmarkId]
            ?: throw IllegalArgumentException("No registered scale policy for benchmark: $benchmarkId")

        // 1. 读取基准项目支持的合法规模列表
        val supportedScales = policy.getSupportedScales()
        if (supportedScales.isEmpty()) {
            throw ScaleSelectionException(
                "NO_SUPPORTED_SCALES",
                "Benchmark $benchmarkId 没有声明任何合法支持规模",
                emptyList()
            )
        }

        // 2 & 3. 为每个候选规模估算内存、时间和存储需求
        val candidateEstimations = mutableMapOf<IScaleConfiguration, ResourceEstimation>()
        for (candidate in supportedScales) {
            candidateEstimations[candidate] = policy.estimateResources(candidate, device)
        }

        // 4. 排除不满足预算的候选项
        val validCandidates = mutableListOf<IScaleConfiguration>()
        val excludedCandidates = mutableListOf<ExcludedCandidate>()

        for ((candidate, estimation) in candidateEstimations) {
            val budgetError = policy.checkBudgetExceeded(estimation, budget)
            if (budgetError != null) {
                excludedCandidates.add(
                    ExcludedCandidate(
                        scaleLevel = candidate.scaleLevel,
                        reasonCode = "BUDGET_EXCEEDED",
                        detailMessage = budgetError,
                        estimatedMemoryBytes = estimation.estimatedPeakMemoryBytes,
                        estimatedRuntimeMs = estimation.estimatedMaxRuntimeMs
                    )
                )
            } else {
                validCandidates.add(candidate)
            }
        }

        // 5. 应用优先级规则进行规模决策
        val requestedConfig = supportedScales.firstOrNull { it.scaleLevel == requestedLevel }
        val isRequestedValid = validCandidates.any { it.scaleLevel == requestedLevel }

        // 优先级 1: 用户明确指定的合法规模且安全满足预算
        if (requestedConfig != null && isRequestedValid) {
            val selectedEstimation = candidateEstimations[requestedConfig]!!
            return buildDecision(
                benchmarkId = benchmarkId,
                requestedLevel = requestedLevel,
                selectedConfig = requestedConfig,
                estimation = selectedEstimation,
                wasPruned = false,
                pruneReason = null,
                excluded = excludedCandidates,
                device = device
            )
        }

        // 规则保护：若处于正式设备对比模式 (isStandardComparisonMode == true)，禁止静默降级
        if (budget.isStandardComparisonMode) {
            val standardExclusion = excludedCandidates.firstOrNull { it.scaleLevel == ScaleLevel.STANDARD }
            throw ScaleSelectionException(
                "STANDARD_MODE_RESOURCE_INSUFFICIENT",
                "设备对比模式要求严格使用标准配置，但标准配置超出预算 (${standardExclusion?.detailMessage ?: "未知原因"})。禁止自动静默降级，请切换至自适应模式或释放内存。",
                excludedCandidates
            )
        }

        // 优先级 2 & 3: 若不允许自适应降级，直接拒绝
        if (!budget.allowAdaptiveDegrade) {
            throw ScaleSelectionException(
                "ADAPTIVE_DEGRADE_FORBIDDEN",
                "请求的 ${requestedLevel.levelName} 规模无法满足预算，且用户配置了禁止自动降级",
                excludedCandidates
            )
        }

        // 优先级 4: 寻找经上游允许的更小合法规模 (顺序降级: LARGE -> STANDARD -> SMALL -> SMOKE)
        val degradeOrder = listOf(ScaleLevel.LARGE, ScaleLevel.STANDARD, ScaleLevel.SMALL, ScaleLevel.SMOKE)
        val startIndex = degradeOrder.indexOf(requestedLevel).let { if (it >= 0) it + 1 else 0 }

        var downgradedConfig: IScaleConfiguration? = null
        for (i in startIndex until degradeOrder.size) {
            val candidateLevel = degradeOrder[i]
            val found = validCandidates.firstOrNull { it.scaleLevel == candidateLevel }
            if (found != null) {
                downgradedConfig = found
                break
            }
        }

        // 优先级 5: 不存在任何安全配置时拒绝执行
        if (downgradedConfig == null) {
            throw ScaleSelectionException(
                "INSUFFICIENT_RESOURCE_ALL_SCALES_EXCEEDED",
                "设备当前可用资源不足以安全运行 ${benchmarkId.key} 的任何合法测试规模 (包括最小 Smoke 验证规模)，已终止执行以保护设备免于 OOM/强杀",
                excludedCandidates
            )
        }

        val selectedEstimation = candidateEstimations[downgradedConfig]!!
        val requestedExclusion = excludedCandidates.firstOrNull { it.scaleLevel == requestedLevel }
        val pruneReason = "原请求 ${requestedLevel.levelName} 超限: ${requestedExclusion?.detailMessage ?: "资源不足"}，自动安全降级至 ${downgradedConfig.scaleLevel.levelName}"

        return buildDecision(
            benchmarkId = benchmarkId,
            requestedLevel = requestedLevel,
            selectedConfig = downgradedConfig,
            estimation = selectedEstimation,
            wasPruned = true,
            pruneReason = pruneReason,
            excluded = excludedCandidates,
            device = device
        )
    }

    private fun buildDecision(
        benchmarkId: BenchmarkId,
        requestedLevel: ScaleLevel,
        selectedConfig: IScaleConfiguration,
        estimation: ResourceEstimation,
        wasPruned: Boolean,
        pruneReason: String?,
        excluded: List<ExcludedCandidate>,
        device: DeviceResourceSnapshot
    ): ScaleDecision {
        val timestamp = System.currentTimeMillis()
        val hashPayload = "$benchmarkId:$requestedLevel:${selectedConfig.scaleLevel}:${estimation.estimatedPeakMemoryBytes}:${device.totalPhysicalRamBytes}"
        val md5 = MessageDigest.getInstance("MD5").digest(hashPayload.toByteArray()).joinToString("") { "%02x".format(it) }

        return ScaleDecision(
            benchmarkId = benchmarkId,
            requestedLevel = requestedLevel,
            selectedLevel = selectedConfig.scaleLevel,
            wasPruned = wasPruned,
            pruneReason = pruneReason,
            inputParameters = selectedConfig.parameters,
            estimatedResources = estimation,
            excludedCandidates = excluded,
            decisionTimestampMs = timestamp,
            reproducibilityHash = md5
        )
    }
}
