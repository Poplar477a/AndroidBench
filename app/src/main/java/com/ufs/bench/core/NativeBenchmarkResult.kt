package com.ufs.bench.core

/**
 * JNI 返回的结果结构。
 *   仿真版使用: totalRead/WriteMbps、IOPS、延迟、finalSpecScore
 *   移植版使用: verified / verifyDetail / specRatio / workUnits / opsPerSec
 *   存储测试使用: ioMethod / privilegeMethod（如实披露实际生效的 I/O 通道与权限手段）
 */
class NativeBenchmarkResult(
    val success: Boolean,
    val errorMessage: String?,
    val totalReadMbps: Double,
    val totalWriteMbps: Double,
    val totalReadIops: Long,
    val totalWriteIops: Long,
    val minLatencyUs: Double,
    val avgLatencyUs: Double,
    val maxLatencyUs: Double,
    val p99LatencyUs: Double,
    val finalSpecScore: Long,
    val finalCpuMetric: Double,
    val finalMemMetric: Double,
    val verified: Boolean,
    val verifyDetail: String?,
    val specRatio: Double,
    val workUnits: Long,
    val opsPerSec: Double,
    val referenceSeconds: Double,
    /** **本机实测耗时**（秒）。此前缺该字段，实测时间在结果里恒为 0。 */
    val measuredSeconds: Double,
    val ioMethod: String?,
    val privilegeMethod: String?,
    val totalBytesDone: Long,
    val stoppedByCapacity: Boolean,
    val detail: NativeDetailedStats?,
    /**
     * 编译工具链标识（**编译期宏生成**，Kotlin 侧只搬运、不硬编码）：
     * __clang_version__ / __VERSION__、优化等级与真实编译参数（CMake 注入）、
     * 目标 ABI、__cplusplus。
     *
     * 用途：官方 run rules 要求按 base/peak 调优等级上报，成绩脱离编译器与
     * 优化选项无法比较；本移植版是单一固定优化等级，展示层必须如实披露这一行。
     * 所有路径（存储测试 / SPEC 完整版）都会带该字段。
     */
    val compilerToolchain: String?,
    /**
     * 本 .so 的 -mcpu 档位自述（编译期宏 DYNAMO_PROFILE_ID，Kotlin 侧只搬运）：
     * "baseline" / "x1" / "x2" / "x3" / "x4" / "x925"。
     * 这是"实际执行本次测试的库是哪一档"的权威答案，不依赖 Kotlin 侧推断。
     */
    val engineProfile: String?,
    /** 该档对应的 -mcpu（编译期宏 DYNAMO_PROFILE_MCPU；baseline = "generic"） */
    val engineMcpu: String?
)