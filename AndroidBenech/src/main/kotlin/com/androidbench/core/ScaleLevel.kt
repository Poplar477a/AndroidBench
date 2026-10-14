package com.androidbench.core

/**
 * 对应第 14.4 节：统一测试规模等级定义
 */
enum class ScaleLevel(
    val levelName: String,
    val purpose: String,
    val isOfficialScoring: Boolean,
    val allowsLeaderboard: Boolean
) {
    /**
     * Smoke: 安装后快速验证
     * 使用最小输入，验证 JNI 执行链路、正确性检查点与资源释放。
     * 严禁代替正式性能成绩。
     */
    SMOKE("Smoke", "安装后快速验证 (链路与正确性检查)", false, false),

    /**
     * Small: 低资源设备安全模式
     * 限制内存开销、运行时间与线程数，保障 2GB~4GB RAM 低配设备不触发 OOM/系统强杀。
     */
    SMALL("Small", "低资源设备保护模式 (限制内存/时间/线程)", false, false),

    /**
     * Standard: 常规固定基准测试
     * 采用上游标准、固定输入与可复现参数。各设备间完全具备横向可比性。
     */
    STANDARD("Standard", "常规性能标准测试 (固定、可复现、上榜基准)", true, true),

    /**
     * Large: 高性能设备 / Android PC 模式
     * 针对 12GB+ RAM 旗舰机、平板或 x86/ARM64 Android PC，采用更大工作集和深度计算负载。
     */
    LARGE("Large", "高性能设备/Android PC 扩展规模 (超大工作集)", true, true),

    /**
     * Advanced: 自定义模式
     * 允许高级用户在安全包络线内微调输入参数、线程数与内存上限。
     */
    ADVANCED("Advanced", "专家自定义参数模式", false, false);

    companion object {
        fun fromString(str: String): ScaleLevel {
            return entries.firstOrNull { it.name.equals(str, ignoreCase = true) } ?: STANDARD
        }
    }
}
