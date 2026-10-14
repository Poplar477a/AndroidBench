package com.ufs.bench.model

object PresetSuites {

    /**
     * 内置基准比值的强制声明：凡展示比值处必须同屏展示本句。
     *
     * 各基准的官方分数（EEMBC CoreMark 认证分、STREAM 公布值等）都需要官方认证流程与
     * 参考机口径，本项目给不出，因此只给"相对本机自标定基线"的比值：
     * 基线常量见 app/src/main/cpp/bench/builtin_bench.cpp 的 kRef* 三个常量，
     * 取本机 baseline 档 -O3 实测值，约 1.0 表示与本机基线持平，跨设备不可比。
     */
    const val BASELINE_RATIO_DISCLAIMER = "相对本机自标定基线，非上游官方分数"

    // 1. CrystalDiskMark 标准套餐（依次测读、测写，双向完整数据）
    val CDM_PRESETS = listOf(
        IometerAccessSpec(
            name = "SEQ1M Q8T1 顺序读写",
            description = "大文件连续读写吞吐，对应录像、拷贝与加载大文件",
            mode = BenchmarkMode.STANDARD_READ_WRITE,
            requestSizeBytes = 1024 * 1024,
            percentRandom = 0,
            queueDepth = 8,
            workerThreads = 1,
            fileSizeMb = 1024,
            durationSec = 10
        ),
        IometerAccessSpec(
            name = "SEQ128K Q32T1 深队列顺序",
            description = "128K 深队列连续流，反映大文件传输的实际带宽",
            mode = BenchmarkMode.STANDARD_READ_WRITE,
            requestSizeBytes = 128 * 1024,
            percentRandom = 0,
            queueDepth = 32,
            workerThreads = 1,
            fileSizeMb = 1024,
            durationSec = 10
        ),
        IometerAccessSpec(
            name = "RND4K Q32T16 高并发随机",
            description = "多线程深队列随机小文件，考验闪存主控的并发 IOPS 上限",
            mode = BenchmarkMode.STANDARD_READ_WRITE,
            requestSizeBytes = 4096,
            percentRandom = 100,
            queueDepth = 32,
            workerThreads = 16,
            fileSizeMb = 1024,
            durationSec = 10
        ),
        IometerAccessSpec(
            name = "RND4K Q1T1 单队列随机",
            description = "单线程单队列随机小文件，决定日常滑动与 App 启动的流畅度",
            mode = BenchmarkMode.STANDARD_READ_WRITE,
            requestSizeBytes = 4096,
            percentRandom = 100,
            queueDepth = 1,
            workerThreads = 1,
            fileSizeMb = 1024,
            durationSec = 10
        )
    )

    // 2. 4K 阶梯压测组（队列深度 × 线程数逐级爬坡：Q1T1 → Q8T8）
    val FOUR_K_LEVEL_PRESETS = listOf(
        Pair(1, 1), Pair(2, 1), Pair(4, 1), Pair(4, 4), Pair(8, 1), Pair(8, 4), Pair(8, 8)
    ).map { (q, t) ->
        IometerAccessSpec(
            name = "4K 阶梯 Q${q}T${t}",
            description = "4KB 随机读写阶梯压测（队列深度 ${q}，线程 ${t}）",
            mode = BenchmarkMode.STANDARD_READ_WRITE,
            requestSizeBytes = 4096,
            percentRandom = 100,
            queueDepth = q,
            workerThreads = t,
            fileSizeMb = 1024,
            durationSec = 10
        )
    }

    // 3. 4K 独立读写混合阶梯（读写比例 90/10、70/30、50/50）
    val FOUR_K_MIX_PRESETS = listOf(
        IometerAccessSpec(
            name = "4KMIX Q1T1 读写 90/10",
            description = "读密集混合负载，读写同时并发",
            mode = BenchmarkMode.MIXED_SIMULTANEOUS,
            requestSizeBytes = 4096,
            percentRead = 90,
            percentRandom = 100,
            queueDepth = 1,
            workerThreads = 1,
            fileSizeMb = 1024,
            durationSec = 10
        ),
        IometerAccessSpec(
            name = "4KMIX Q1T1 读写 70/30",
            description = "经典混合负载，读写同时并发",
            mode = BenchmarkMode.MIXED_SIMULTANEOUS,
            requestSizeBytes = 4096,
            percentRead = 70,
            percentRandom = 100,
            queueDepth = 1,
            workerThreads = 1,
            fileSizeMb = 1024,
            durationSec = 10
        ),
        IometerAccessSpec(
            name = "4KMIX Q1T1 读写 50/50",
            description = "读写对称的均衡负载，同时并发",
            mode = BenchmarkMode.MIXED_SIMULTANEOUS,
            requestSizeBytes = 4096,
            percentRead = 50,
            percentRandom = 100,
            queueDepth = 1,
            workerThreads = 1,
            fileSizeMb = 1024,
            durationSec = 10
        ),
        IometerAccessSpec(
            name = "4KMIX Q4T4 读写 70/30",
            description = "高并发混合负载，贴近真实复杂应用的读写构成",
            mode = BenchmarkMode.MIXED_SIMULTANEOUS,
            requestSizeBytes = 4096,
            percentRead = 70,
            percentRandom = 100,
            queueDepth = 4,
            workerThreads = 4,
            fileSizeMb = 1024,
            durationSec = 10
        ),
        IometerAccessSpec(
            name = "4KMIX Q4T4 读写 50/50",
            description = "高并发对称重载：4 线程 Q4，50% 读 + 50% 写",
            mode = BenchmarkMode.MIXED_SIMULTANEOUS,
            requestSizeBytes = 4096,
            percentRead = 50,
            percentRandom = 100,
            queueDepth = 4,
            workerThreads = 4,
            fileSizeMb = 1024,
            durationSec = 10
        )
    )

    // 4. 内置基准（2026-10 更换：内核全部裁剪自 AndroidBenech/upstream 的**官方上游源码**，
    //    不再使用"仿真/模拟"实现）。每个基准都带上游自带的正确性校验，不通过不计成绩；
    //    强度挡位（低性能 / 常规 / 拓展）由各基准自己的参数决定（见 bench/builtin_bench.cpp 的 planFor）。
    //    展示成绩时必须同屏带上 BASELINE_RATIO_DISCLAIMER：比值是"相对本机基线"，不是官方分数。
    val BUILTIN_KERNEL_PRESETS = listOf(
        IometerAccessSpec(
            name = "CoreMark 1.0 · 整数与控制流",
            description = "EEMBC 官方 CoreMark：链表/矩阵/状态机/CRC 四类整数负载，衡量核心整数流水线与控制逻辑。" +
                "算法文件逐字取自上游，仅移植计时源与内存分配；结果与上游公开的已知 CRC（crclist 0xe714 / crcmatrix 0x1fd7 / crcstate 0x8e3a）逐一比对。" +
                "强度挡位：低性能=2000 次迭代（快速自检，不构成标准成绩）；常规=官方自动标定 ≥10 s（标准口径）；拓展=官方标定 × 全核并列。" +
                "来源：eembc/coremark @1f483d5（Apache-2.0）。",
            mode = BenchmarkMode.BUILTIN_KERNEL,
            requestSizeBytes = 16 * 1024,
            percentRead = 100,
            percentRandom = 0,
            queueDepth = 1,
            workerThreads = 4,
            fileSizeMb = 0,
            durationSec = 0,
            builtinKernelId = 1001,
            portedVersion = true
        ),
        IometerAccessSpec(
            name = "STREAM · 持续内存带宽",
            description = "John D. McCalpin 的 STREAM：Copy / Scale / Add / Triad 四内核测 LPDDR 持续带宽，工作集必须击穿 LLC 才算持续带宽（<64 MiB 只能算缓存驻留）。" +
                "四个内核的终值都与上游同款递推期望值逐元素比对（最大相对误差实测 0）。" +
                "强度挡位：低性能=64 MiB / NTIMES=10；常规=320 MiB / NTIMES=10（上游标准规模）；拓展=640 MiB / NTIMES=20。" +
                "来源：jeffhammond/STREAM @6703f75（STREAM License）。",
            mode = BenchmarkMode.BUILTIN_KERNEL,
            requestSizeBytes = 16 * 1024,
            percentRead = 100,
            percentRandom = 0,
            queueDepth = 1,
            workerThreads = 1,
            fileSizeMb = 0,
            durationSec = 0,
            builtinKernelId = 1002,
            portedVersion = true
        ),
        IometerAccessSpec(
            name = "tinymembench · 内存延迟与带宽扫描",
            description = "ssvb 的 tinymembench：顺序读/写/拷贝带宽扫描 + 指针追逐随机访问延迟阶梯（覆盖 L1 → DRAM），ARM64 上走 NEON 汇编例程。" +
                "校验为双判据：带宽自洽（计时/字节数一致、不超物理上限）+ 延迟随工作集单调不降。" +
                "已剔除无 root 不可用的子测试（HugePages、硬件性能计数器）。" +
                "强度挡位：低性能=扫描到 32 MiB；常规=扫描到 64 MiB × 3 次；拓展=扫描到 256 MiB × 5 次。" +
                "来源：ssvb/tinymembench @a2cf6d7（MIT）。",
            mode = BenchmarkMode.BUILTIN_KERNEL,
            requestSizeBytes = 16 * 1024,
            percentRead = 100,
            percentRandom = 0,
            queueDepth = 1,
            workerThreads = 1,
            fileSizeMb = 0,
            durationSec = 0,
            builtinKernelId = 1003,
            portedVersion = true
        )
    )

}