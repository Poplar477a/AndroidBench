package com.ufs.bench.core

/**
 * 专业模式的全量统计（对齐 fio 的 lat percentiles / clat 分布与运行期指标）。
 *
 * 字段顺序必须与 JNI 构造函数签名严格一致：
 *   (DDDDDDDJJDDDDJ[D[D[D[D[D[D)V
 * 即 (p50,p95,p99,p999,max,cpuUtil,timeoutThreshold)=7D、 (timeoutCount,ioErrorCount)=2J、
 * (sampleInterval,jitter,worstDrop,steadyState)=4D、 (dropEvents)=1J、
 * 再按 读吞吐 / 写吞吐 / 读延迟 / 写延迟 / CPU / 时间轴 的顺序接 6 个 double[]。
 *
 * 曲线口径见 docs/chart-data-contract.md §2：**分读、分写**，
 * 原有的合并口径 curve_bw_mbps / curve_lat_us 已删除。
 */
class NativeDetailedStats(
    val p50LatencyUs: Double,
    val p95LatencyUs: Double,
    val p99LatencyUs: Double,
    val p999LatencyUs: Double,
    val maxLatencyUs: Double,
    val cpuUtilPercent: Double,
    val timeoutThresholdUs: Double,
    val timeoutCount: Long,
    val ioErrorCount: Long,
    val sampleIntervalMs: Double,
    val jitterPercent: Double,
    val worstDropPercent: Double,
    val steadyStateMbps: Double,
    val dropEvents: Long,
    /**
     * 工作线程 CPU 占用归一化到 0~100%（= cpuUtilPercent / 设备核数）。
     * 口径：仅 benchmark 工作线程（CLOCK_THREAD_CPUTIME_ID 累加），不含 UI 与 Compose 渲染。
     */
    val cpuPercentOfCores: Double,
    /** 归一化用到的设备核数（如实带回，便于复核与文案说明） */
    val cpuCoreCount: Int,
    /** 每采样区间的读吞吐（MB/s） */
    val curveReadBwMbps: DoubleArray,
    /** 每采样区间的写吞吐（MB/s） */
    val curveWriteBwMbps: DoubleArray,
    /** 每采样区间读请求的平均响应延迟（μs） */
    val curveReadLatUs: DoubleArray,
    /** 每采样区间写请求的平均响应延迟（μs） */
    val curveWriteLatUs: DoubleArray,
    val curveCpuPercent: DoubleArray,
    val curveTimeSec: DoubleArray
)
