package com.androidbench.core

/**
 * 分类枚举：对应第 3 节测试能力分类及现代扩展
 */
enum class BenchmarkCategory(val displayName: String) {
    INTEGER_AND_CPU("整数与综合 CPU 性能"),
    FP_AND_SCIENTIFIC("浮点与科学计算"),
    MEMORY_AND_CACHE("内存带宽、延迟与缓存"),
    AI_AND_MULTIMEDIA("端侧 AI 与多媒体处理"),
    MANAGEMENT_FRAMEWORK("测试管理框架")
}

/**
 * 交付阶段优先级：P0 (核心基准), P1 (实战与PC级扩展), P2 (现代前沿/重型负载)
 */
enum class BenchmarkPhase(val level: String, val description: String) {
    P0("P0", "第一版必须完成：核心硬件基线"),
    P1("P1", "第二阶段扩展：专业计算、真实 PC 应用与系统负载"),
    P2("P2", "现代前沿计算：端侧大模型、重型科学计算与高阶多媒体")
}

/**
 * 原生基准测试唯一标识符 (包含既有 P0/P1/P2 及 PTS 扩展项目)
 */
enum class BenchmarkId(
    val key: String,
    val upstreamRepo: String,
    val category: BenchmarkCategory,
    val phase: BenchmarkPhase,
    val description: String
) {
    // ==========================================
    // P0: 第一版必须完成 (核心硬件物理基线)
    // ==========================================
    COREMARK("coremark", "eembc/coremark", BenchmarkCategory.INTEGER_AND_CPU, BenchmarkPhase.P0, "整数计算、控制逻辑、核心性能"),
    ZSTD("zstd", "facebook/zstd", BenchmarkCategory.INTEGER_AND_CPU, BenchmarkPhase.P0, "压缩与解压缩吞吐量"),
    STREAM("stream", "jeffhammond/STREAM", BenchmarkCategory.MEMORY_AND_CACHE, BenchmarkPhase.P0, "持续内存带宽"),
    TINYMEMBENCH("tinymembench", "ssvb/tinymembench", BenchmarkCategory.MEMORY_AND_CACHE, BenchmarkPhase.P0, "连续访问带宽、随机访问延迟"),
    NPB_EP("npb_ep", "NASA NPB 3.4.2", BenchmarkCategory.FP_AND_SCIENTIFIC, BenchmarkPhase.P0, "EP 易并行浮点密集型科学计算"),

    // ==========================================
    // P1: 第二阶段扩展 (实战应用与 Android PC 扩展)
    // ==========================================
    SEVEN_ZIP("7zip", "ip7z/7zip", BenchmarkCategory.INTEGER_AND_CPU, BenchmarkPhase.P1, "7-Zip LZMA 压缩与解压多核基准 (7z b)"),
    STOCKFISH("stockfish", "official-stockfish/Stockfish", BenchmarkCategory.INTEGER_AND_CPU, BenchmarkPhase.P1, "Stockfish 国际象棋引擎分支密集压测 (stockfish bench)"),
    C_RAY("c_ray", "jtsiomb/c-ray", BenchmarkCategory.FP_AND_SCIENTIFIC, BenchmarkPhase.P1, "C-Ray 浮点多线程光线追踪渲染基准"),
    SQLITE("sqlite", "sqlite/sqlite3", BenchmarkCategory.INTEGER_AND_CPU, BenchmarkPhase.P1, "SQLite 嵌入式关系型数据库 B-Tree 事务基准 (speedtest1)"),
    HPL("hpl", "icl-utk-edu/hpl", BenchmarkCategory.FP_AND_SCIENTIFIC, BenchmarkPhase.P1, "HPL FP64 矩阵计算、BLAS 吞吐量"),
    GRAPH500("graph500", "graph500/graph500", BenchmarkCategory.INTEGER_AND_CPU, BenchmarkPhase.P1, "Graph500 图遍历、整数运算和不规则访存"),
    GCC("gcc", "gcc-mirror/gcc", BenchmarkCategory.INTEGER_AND_CPU, BenchmarkPhase.P1, "GCC 编译工作负载、整数与控制流"),
    LMBENCH("lmbench", "intel/lmbench", BenchmarkCategory.MEMORY_AND_CACHE, BenchmarkPhase.P1, "lmbench 内存延迟、缓存与系统开销"),
    COREMARK_PRO("coremark_pro", "eembc/coremark-pro", BenchmarkCategory.INTEGER_AND_CPU, BenchmarkPhase.P1, "CoreMark-PRO 复杂多工作负载综合评测"),
    NPB_BT("npb_bt", "NASA NPB 3.4.2", BenchmarkCategory.FP_AND_SCIENTIFIC, BenchmarkPhase.P1, "BT 块三对角求解器 (3D 网格)"),
    NPB_SP("npb_sp", "NASA NPB 3.4.2", BenchmarkCategory.FP_AND_SCIENTIFIC, BenchmarkPhase.P1, "SP 标量五对角求解器 (3D 网格)"),
    NPB_CG("npb_cg", "NASA NPB 3.4.2", BenchmarkCategory.FP_AND_SCIENTIFIC, BenchmarkPhase.P1, "CG 共轭梯度法 (稀疏矩阵)"),
    NPB_FT("npb_ft", "NASA NPB 3.4.2", BenchmarkCategory.FP_AND_SCIENTIFIC, BenchmarkPhase.P1, "FT 三维快速傅里叶变换"),

    // ==========================================
    // P2: 现代前沿与高阶计算
    // ==========================================
    LLAMA_CPP("llama_cpp", "ggml-org/llama.cpp", BenchmarkCategory.AI_AND_MULTIMEDIA, BenchmarkPhase.P2, "llama.cpp 端侧大模型推理 (llama-bench, Prompt/Token吞吐)"),
    DAV1D("dav1d", "videolan/dav1d", BenchmarkCategory.AI_AND_MULTIMEDIA, BenchmarkPhase.P2, "dav1d AV1 视频高效解码吞吐 (ARM64 NEON 手工汇编)"),
    OPENSSL("openssl", "openssl/openssl", BenchmarkCategory.INTEGER_AND_CPU, BenchmarkPhase.P2, "OpenSSL 密码学与 ARMv8 硬件加密指令压测 (speed)"),
    NCNN("ncnn", "Tencent/ncnn", BenchmarkCategory.AI_AND_MULTIMEDIA, BenchmarkPhase.P2, "腾讯 ncnn 移动端神经网络推理与 Vulkan/NEON 异构基准"),
    NAMD("namd", "NAMD Official", BenchmarkCategory.FP_AND_SCIENTIFIC, BenchmarkPhase.P2, "NAMD 分子动力学与原子间作用力计算"),
    DEAL_II_FEM("deal_ii_fem", "deal.II", BenchmarkCategory.FP_AND_SCIENTIFIC, BenchmarkPhase.P2, "FEM / CFD 有限元数值计算、偏微分方程求解")
}
