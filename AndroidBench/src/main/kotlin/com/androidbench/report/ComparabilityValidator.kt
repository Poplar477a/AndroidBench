package com.androidbench.report

import com.androidbench.core.*

/**
 * 对应第 14.8 节：基准成绩横向可比性验证器与排行榜隔离引擎
 */
class ComparabilityValidator {

    /**
     * 计算结果的分组 Key
     * 确保不同输入规模、不同线程数、不同精度在展示时严格分组，不发生错位对比
     */
    fun computeGroupingKey(report: BenchmarkReportMetadata): String {
        return "${report.benchmarkId.key}_${report.scaleLevel.name}_${report.threadCount}T_${report.actualInputScaleMetric}"
    }

    /**
     * 校验测试结果是否具备与标准基准数据库比对的合法资格
     */
    fun validateEligibility(report: BenchmarkReportMetadata): ComparabilityResult {
        // 1. 正确性门禁
        if (!report.isCorrectnessVerified) {
            return ComparabilityResult(
                isEligibleForOfficialRanking = false,
                comparabilityTier = "INVALID",
                rejectionReason = "未通过正确性验证 (Correctness Oracle Verification Failed)"
            )
        }

        // 2. 状态门禁
        if (report.executionStatus != BenchmarkExecutionStatus.COMPLETED_VERIFIED) {
            return ComparabilityResult(
                isEligibleForOfficialRanking = false,
                comparabilityTier = "INVALID",
                rejectionReason = "执行未正常完成，当前状态为: ${report.executionStatus.name}"
            )
        }

        // 3. 规模裁剪门禁：若发生规模裁剪，降级为自适应非标准结果
        if (report.wasPruned || report.isAdaptiveScale) {
            return ComparabilityResult(
                isEligibleForOfficialRanking = false,
                comparabilityTier = "ADAPTIVE_DEVICE_INTERNAL",
                rejectionReason = "该成绩经历了自适应规模裁剪 (${report.pruneReason ?: "设备自适应"})，只能与完全相同裁剪参数的设备做局部比对，禁止混入标准天梯排行榜"
            )
        }

        // 4. Smoke 测试门禁
        if (report.scaleLevel == ScaleLevel.SMOKE) {
            return ComparabilityResult(
                isEligibleForOfficialRanking = false,
                comparabilityTier = "SMOKE_VERIFICATION",
                rejectionReason = "Smoke 测试仅用于验证代码链路畅通，严禁作为正式性能成绩呈现"
            )
        }

        // 5. 标准规格检查
        if (report.scaleLevel == ScaleLevel.STANDARD && report.isUpstreamStandardScale) {
            return ComparabilityResult(
                isEligibleForOfficialRanking = true,
                comparabilityTier = "STANDARD_OFFICIAL",
                rejectionReason = null
            )
        }

        return ComparabilityResult(
            isEligibleForOfficialRanking = false,
            comparabilityTier = "CUSTOM_OR_LARGE",
            rejectionReason = "非标准基线规模 (${report.scaleLevel.name})"
        )
    }
}

data class ComparabilityResult(
    val isEligibleForOfficialRanking: Boolean,
    val comparabilityTier: String,
    val rejectionReason: String?
)
