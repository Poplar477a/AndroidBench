package com.ufs.bench.model

enum class BenchmarkMode {
    STANDARD_READ_WRITE,  // 标准模式：先测读取，再测写入，汇总完整读写成绩（类似 CDM）
    MIXED_SIMULTANEOUS,   // 独立混合模式：读写同时并发进行（如 70% 读 / 30% 写）
    BUILTIN_KERNEL,            // 内置微基准：821.gcc_s（单任务口径）/ 766.femflow_r（多任务口径）/ 854.graph500_s（单任务口径）
    READ_ONLY,            // 单独只测读
    WRITE_ONLY            // 单独只测写
}

/**
 * 基准所属的 SPEC 套件类型 —— 由官方基准名后缀决定，不可混用。
 *
 *   `_s` = 单任务口径（速度类基准，参考档 参考档）
 *   `_r` = 多任务口径（速率/吞吐类基准，参考档 参考档）
 *
 * 本项目移植的三个基准：
 *   821.gcc_s、854.graph500_s → [SPEED]（参考档）
 *   766.femflow_r             → [RATE]（参考档）
 */
enum class SpecSuiteKind(
    val suffix: String,
    val suiteName: String,
    val referenceTierName: String
) {
    SPEED("_s", "单任务口径", "参考档"),
    RATE("_r", "多任务口径", "参考档"),
    UNKNOWN("", "未指定", "拓展");

    /** 展示用短标签，例如「单任务口径（参考档）」 */
    val label: String get() = if (this == UNKNOWN) suiteName else "$suiteName（$referenceTierName）"

    companion object {
        /** 按基准编号判定套件类型；编号取自官方基准名 */
        fun ofWorkload(builtinKernelId: Int): SpecSuiteKind = when (builtinKernelId) {
            821, 854 -> SPEED   // 821.gcc_s / 854.graph500_s
            766 -> RATE         // 766.femflow_r
            else -> UNKNOWN
        }
    }
}

/**
 * 内置微基准档位 —— 对齐官方 SPEC 工作负载规模分类（test / train / 参考档 或 参考档）。
 *
 * 官方命名规则（关键）：基准名后缀 `_s` = 单任务口径（速度类），`_r` = 多任务口径（速率类）；
 * 参考档因此分别是 **参考档** 与 **参考档**，绝不能按统一口径混写。
 *   821.gcc_s      : 单任务口径 → test 极小自检 / train 缩减集 / 参考档 2800 个预处理 C 文件
 *   766.femflow_r  : **多任务口径 → 参考档**：细化层数 0~4、终止时间 .01~4
 *   854.graph500_s : 单任务口径 → test -s18(262,144 顶点) / train -s20(1,048,576) / 参考档 -s26(67,108,864)
 *                    APSP  test 100/62 / train 8000/9491 / 参考档 16000/19384
 *
 * 官方内存要求：单任务口径 large 需约 64 GB 主存，多任务口径 每份拷贝约 2 GB，
 * 因此 参考档/参考档 属 PC/服务器级档位，移动端给出警告并做内存预检。
 *
 * ⚠️ 本项目无法获得 任何官方参考时间，所有比值均为「相对自标定基线」，
 * 不得表述为 单任务口径/多任务口径 官方成绩，也不得与 spec.org 公布的分数比较。
 */
enum class SpecTier(
    val level: Int,
    val officialName: String,
    val label: String,
    val detail: String,
    val pcClass: Boolean,
    val warning: String? = null
) {
    TEST(
        1, "低性能", "低性能",
        "低性能：轻量规模，秒级完成，用于快速自检",
        false
    ),
    TRAIN(
        2, "常规", "常规",
        "常规：标准规模，数十秒至数分钟，日常使用推荐",
        false,
        ""
    ),
    REFSPEED(
        3, "拓展", "拓展",
        "拓展：最大规模，面向 PC/服务器级算力，移动端耗时很长",
        true,
        "");

    /** 官方仅在 参考档（单任务口径）/ 参考档（多任务口径）档给出可上报成绩；test 档不计时，train 档用于估算运行时长 */
    val reportsScore: Boolean get() = this == REFSPEED

    /** 是否应展示比值（单任务口径 与 多任务口径 移植版均按「相对自标定基线」计分） */
    val showsRatio: Boolean get() = this != TEST

    companion object {
        fun fromLevel(level: Int): SpecTier = entries.firstOrNull { it.level == level } ?: TEST
    }
}

/**
 * 按官方参数预估某档位所需内存（MB）。用于 PC 级档位的前置可行性判断。
 */
object SpecMemoryEstimator {
    fun estimateMb(spec: IometerAccessSpec): Long {
        val tier = spec.tier
        return when (spec.builtinKernelId) {
            // CoreMark：上游按 MULTITHREAD 个上下文各分配 2000 字节数据区，量级可忽略
            1001 -> 2L
            // STREAM：工作集 = 4×N×8 字节
            //   低性能 64 MiB / 常规 768 MiB（加压）/
            //   拓展 = PC 级 6.4 GB 但**按可用内存一半自适应** -> 返回 0 表示"无需内存预检"
            1002 -> when (tier) {
                SpecTier.TEST -> 80L
                SpecTier.TRAIN -> 800L
                SpecTier.REFSPEED -> 0L
            }
            // tinymembench：两份缓冲区 + 探针
            //   低性能 32 MiB / 常规 128 MiB（加压）/ 拓展 1 GiB（同样自适应封顶）
            1003 -> when (tier) {
                SpecTier.TEST -> 80L
                SpecTier.TRAIN -> 275L
                SpecTier.REFSPEED -> 0L
            }
            else -> 0L
        }
    }

    /**
     * 真机耗时估算（分钟）。基于内置档位的规模与设备可用内存粗算，
     * 仅用于在启动前给出量级提示，不作为承诺。
     */
    fun estimateMinutes(spec: IometerAccessSpec, availMemBytes: Long): Int {
        val tier = spec.tier
        val baseMin = when (spec.builtinKernelId) {
            // CoreMark：常规/拓展由上游自动标定到 ≥10 s；低性能只有 2000 次迭代（秒级）
            // CoreMark：常规 = 20 s 等效迭代（单线程）；拓展 = 全核 × 每核 30 s 等效
            1001 -> when (tier) {
                SpecTier.TEST -> 1; SpecTier.TRAIN -> 1; SpecTier.REFSPEED -> 2
            }
            // STREAM：四内核 × NTIMES。常规 768 MiB（实测同量级 320 MiB 约 0.5 s，768 MiB 约 1-2 s）；
            // 拓展工作集按可用内存自适应，手机上一轮约数十秒
            1002 -> when (tier) {
                SpecTier.TEST -> 1; SpecTier.TRAIN -> 1; SpecTier.REFSPEED -> 3
            }
            // tinymembench：128 MiB × 5 约十余秒；1 GiB 档约数分钟
            1003 -> when (tier) {
                SpecTier.TEST -> 1; SpecTier.TRAIN -> 1; SpecTier.REFSPEED -> 5
            }
            else -> 1
        }
        return baseMin
    }
}

/**
 * 测试终止准则 —— 二者互斥，同一时刻只生效一个
 */
enum class StopCriterion(val label: String, val detail: String) {
    TIME("按时间", "在指定秒数内持续压测，最终以时间归一化吞吐"),
    CAPACITY("按容量", "压测到累计读写达到指定容量为止，最终以完成容量为准")
}

/**
 * 缓存参与模式 —— 决定测量是否绕过页缓存
 */
enum class CacheMode(val code: Int, val label: String, val detail: String) {
    WITH_CACHE(
        2, "含缓存",
        "使用缓冲 I/O，页缓存参与读写。这是默认档：数值反映「应用视角的实际体验」，包含缓存命中带来的收益；" +
            "若要看闪存裸速请改选「无缓存参与」"
    ),
    NO_CACHE(
        1, "无缓存参与",
        "强制 O_DIRECT 直连存储；每轮测量前对目标文件执行 posix_fadvise(DONTNEED) 丢弃页缓存，" +
            "并要求文件尺寸足够大以避免整体驻留缓存。若 O_DIRECT 不被支持则直接判失败而非静默回退"
    )
}

/**
 * 测试手段 —— 是否有 root 权限决定采用哪套方法
 *
 *   常规（无 root）：I/O 通道随缓存模式而定 ——
 *                    含缓存＝缓冲 I/O（页缓存参与）；无缓存＝O_DIRECT + fadvise(DONTNEED) + fdatasync。
 *                    无 root 时无法清空内核页缓存，只能靠直接 I/O 与足量文件尺寸规避缓存污染
 *   Root 专用      ：echo 3 > /proc/sys/vm/drop_caches 清空页缓存；
 *                    可读取真实 I/O 调度器与队列深度；可对裸块设备做只读顺序测试
 *
 * 注：本引擎的清缓存参考 AOSP system/vold/Benchmark.cpp 的 PerformanceBoost 做法
 * （提升进程优先级 + 置 RT I/O 调度类）；本轮查证未取得 drop_caches 失败判定的原文，
 * 故不对外断言"drop_caches 失败即判失败"这类未经原文确认的细节。
 */
enum class RunPrivilege(val code: Int, val label: String, val detail: String, val professional: Boolean) {
    NORMAL(
        1, "常规模式",
        "不依赖 root，I/O 通道随缓存模式而定：含缓存＝缓冲 I/O（页缓存参与），" +
            "无缓存＝O_DIRECT 直连（文件系统不支持则直接判失败，不静默回退）；" +
            "仅输出吞吐与延迟指标，不读取存储设备与硬件详情（避免给出未经确证的硬件结论）",
        false
    ),
    PROFESSIONAL(
        2, "专业模式",
        "需 root：测量开始前先提升进程优先级并把 I/O 调度类设为 RT（参考 AOSP PerformanceBoost），" +
            "再以 drop_caches 清空页缓存；读取真实调度器与队列深度；" +
            "输出全量 FIO 口径指标（P50/P95/P99/P99.9、CPU 占用、控制器超时、" +
            "分读写的吞吐与响应延迟曲线、抖动与掉速记录）；采样档位由用户选定，" +
            "可选 5/10/25/50/75/100 ms，越短曲线越密、开销越高",
        true
    )
}

/**
 * 对应 Iometer 核心的 Access Specification (存取规范) 模型
 */
data class IometerAccessSpec(
    val name: String,
    val description: String,
    val mode: BenchmarkMode = BenchmarkMode.STANDARD_READ_WRITE,
    val requestSizeBytes: Int = 4096,         // 4K, 128K, 1M, 2M...
    val percentRead: Int = 70,                // 仅在 MIXED 模式下生效 (0~100)
    val percentRandom: Int = 100,             // 100=随机, 0=顺序
    val queueDepth: Int = 1,                  // 队列深度
    val workerThreads: Int = 1,               // 线程数
    val fileSizeMb: Int = 256,                // 测试文件大小 (MB)
    val durationSec: Int = 5,                 // 每个阶段测试时间 (秒)
    val builtinKernelId: Int = 0,              // 0: 无, 821: gcc_s(单任务口径), 766: femflow_r(多任务口径), 854: graph500_s(单任务口径)
    val portedVersion: Boolean = false,       // true = 移植版算法（含输出校验，纯 CPU+内存）
    val specTier: Int = SpecTier.TRAIN.level,   // 默认常规档：保证 20s 以上，经历升频->稳态->热平衡  // 内置档位：1=test 2=train 3=参考档（按基准类型取 参考档 或 参考档）
    val cacheMode: CacheMode = CacheMode.WITH_CACHE,    // 页缓存参与模式（默认含缓存）
    val privilege: RunPrivilege = RunPrivilege.NORMAL,  // 测试手段（默认常规模式）
    val forceRun: Boolean = false,                      // PC 级档位：跳过内存预检强制运行
    val stopCriterion: StopCriterion = StopCriterion.TIME,  // 终止准则（时间 / 容量，互斥）
    val targetCapacityMb: Int = 1024,                   // 按容量时的目标容量（MB）
    /**
     * 采样档位（毫秒），契约允许值：5 / 10 / 25 / 50 / 75 / 100。
     * 替换原先写死的 kSampleInterval（专业模式 100 ms / 常规模式 1 s）。
     * 专业模式与常规模式的进度回调都按该间隔；未开启专业模式时不产出曲线。
     * 持久化见 [SpecStore]；非法值由 UI 选择器与原生兜底钳制。
     */
    val sampleIntervalMs: Int = DEFAULT_SAMPLE_INTERVAL_MS
) {
    val blockSizeLabel: String
        get() = when {
            requestSizeBytes >= 1024 * 1024 -> "${requestSizeBytes / (1024 * 1024)}M"
            requestSizeBytes >= 1024 -> "${requestSizeBytes / 1024}K"
            else -> "${requestSizeBytes}B"
        }

    val patternLabel: String
        get() = when (mode) {
            BenchmarkMode.STANDARD_READ_WRITE -> if (percentRandom == 0) "SEQ 读写" else "RND 读写"
            BenchmarkMode.MIXED_SIMULTANEOUS -> "MIX ${percentRead}/${100 - percentRead}"
            BenchmarkMode.BUILTIN_KERNEL -> "SPEC ${percentRead}/${100 - percentRead}"
            BenchmarkMode.READ_ONLY -> if (percentRandom == 0) "SEQ 纯读" else "RND 纯读"
            BenchmarkMode.WRITE_ONLY -> if (percentRandom == 0) "SEQ 纯写" else "RND 纯写"
        }

    val concurrencyLabel: String
        get() = "Q${queueDepth}T${workerThreads}"

    val tier: SpecTier
        get() = SpecTier.fromLevel(specTier)

    /**
     * 该基准所属的 SPEC 套件类型：`_s` = 单任务口径，`_r` = 多任务口径。
     * 非 内置微基准返回 [SpecSuiteKind.UNKNOWN]。
     */
    val suiteKind: SpecSuiteKind
        get() = SpecSuiteKind.ofWorkload(builtinKernelId)

    /**
     * 官方口径下的档位名：test / train / **参考档**（单任务口径）或 **参考档**（多任务口径）。
     * 展示层请用本属性而非 [SpecTier.officialName]，
     * 否则 766.femflow_r（多任务口径）会被误写成 参考档。
     */
    val officialTierName: String
        get() = if (tier == SpecTier.REFSPEED) suiteKind.referenceTierName else tier.officialName

    /** 是否需要 O_DIRECT 直连（仅"无缓存参与"模式强制要求） */
    val wantsDirectIo: Boolean
        get() = cacheMode == CacheMode.NO_CACHE

    val cacheModeLabel: String get() = cacheMode.label
    val privilegeLabel: String get() = privilege.label

    companion object {
        /** 采样档位允许值（ms）：契约 §1 的六档，UI 选择器与原生层必须一致 */
        val SAMPLE_INTERVAL_OPTIONS_MS: List<Int> = listOf(5, 10, 25, 50, 75, 100)

        /** 契约默认档：100 ms（与原生 AccessSpecification 默认值一致） */
        const val DEFAULT_SAMPLE_INTERVAL_MS = 100

        /**
         * 把任意输入钳制到最接近的合法档位。
         * 用于持久化旧记录（没有该字段）或异常值兜底，保证交给原生层的永远是六档之一。
         */
        fun normalizeSampleIntervalMs(value: Int): Int =
            SAMPLE_INTERVAL_OPTIONS_MS.minByOrNull { kotlin.math.abs(it - value) }
                ?: DEFAULT_SAMPLE_INTERVAL_MS
    }
}

data class RealtimeMetric(
    val readMbps: Double = 0.0,
    val writeMbps: Double = 0.0,
    val readIops: Long = 0,
    val writeIops: Long = 0,
    val avgLatencyUs: Double = 0.0,
    /**
     * 本采样区间的读请求平均响应延迟（μs）—— 分读、分写，各自由原生 worker 的
     * 单调计数器取增量求得；测试进行中按采样档位实时刷新（不是全程平均）。
     */
    val readAvgLatencyUs: Double = 0.0,
    /** 本采样区间的写请求平均响应延迟（μs）；同上 */
    val writeAvgLatencyUs: Double = 0.0,
    val maxLatencyUs: Double = 0.0,
    val p99LatencyUs: Double = 0.0,
    val currentPhase: String = "",
    val elapsedSeconds: Int = 0,
    val totalSeconds: Int = 5,
    val specScore: Long = 0,
    val cpuMetric: Double = 0.0,
    val memMetric: Double = 0.0,
    val specRatio: Double = 0.0,
    /** 容量模式：已完成的读写总字节 */
    val bytesDone: Long = 0L,
    /** 容量模式：目标总字节（按时间模式为 0） */
    val targetBytes: Long = 0L
)

data class BenchmarkSummary(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val spec: IometerAccessSpec,
    val engineName: String,
    val success: Boolean,
    val errorMessage: String? = null,
    val readMbps: Double = 0.0,
    val writeMbps: Double = 0.0,
    val readIops: Long = 0,
    val writeIops: Long = 0,
    val avgLatencyUs: Double = 0.0,
    val readAvgLatencyUs: Double = 0.0,
    val writeAvgLatencyUs: Double = 0.0,
    val p95LatencyUs: Double = 0.0,
    val p99LatencyUs: Double = 0.0,
    val maxLatencyUs: Double = 0.0,
    val specScore: Long = 0,
    val cpuMetric: Double = 0.0,
    val memMetric: Double = 0.0,
    // ---- 内置微基准专有 ----
    val verified: Boolean = false,
    val verifyDetail: String = "",
    /** 相对**自标定基线**的比值 —— 非 任何官方参考时间，不可与 spec.org 公布分数比较 */
    val specRatio: Double = 0.0,
    /** 本项目自标定基线耗时（秒）—— 不是 任何官方参考时间，展示时勿称"参考机" */
    val referenceSeconds: Double = 0.0,
    /** **本机实测耗时**（秒）—— 与自标定基线同一口径（同内核同参数） */
    val measuredSeconds: Double = 0.0,
    val workUnits: Long = 0,
    // ---- 存储测试的手段披露 ----
    val ioMethod: String = "",          // 实际生效的 I/O 通道（O_DIRECT / O_SYNC / 缓冲），须与 cacheMode 一致
    val privilegeMethod: String = "",   // 实际生效的测试手段（专业 root / 常规无 root），不得与 ioMethod 自相矛盾
    // ---- 终止准则与实际完成量 ----
    val totalBytesDone: Long = 0,
    val stoppedByCapacity: Boolean = false,
    // ---- 专业模式全量统计 ----
    val detail: com.ufs.bench.core.NativeDetailedStats? = null,
    /**
     * 标准读写（先读后写）模式下**写阶段**的专业统计。
     *
     * 背景：标准读写会依次跑读、写两个阶段，[detail] 只承载其中一份（历史上取读阶段），
     * 于是历史报告里看不到写阶段的分读写曲线。本字段由 ViewModel 填入写阶段的 detail，
     * 历史页可据此把读、写两组曲线分别渲染。
     * 仅 model 字段：填值在 ui/BenchmarkViewModel.kt（ui-scatter 负责）。
     */
    val writeDetail: com.ufs.bench.core.NativeDetailedStats? = null,
    /**
     * 编译工具链标识（原生层以编译期宏生成后经 JNI 带回，Kotlin 侧只搬运）。
     *
     * 用途：官方要求按 base/peak 调优等级上报，成绩脱离编译器与优化选项无法比较；
     * 本移植版以单一固定优化等级编译，结果页与历史详情页必须如实展示这一行。
     * 存储测试同样会带上该值，展示层按需使用即可。
     */
    val compilerToolchain: String = "",
    /**
     * 本结果由哪一档 -mcpu 引擎库执行（"baseline" / "x1" … "x925"）。
     * 取自原生编译期宏，是"实际执行"的权威值；降级详情见下面三个字段。
     */
    val engineProfile: String = "",
    /** [engineProfile] 对应的 -mcpu（原样搬运，baseline = "generic"） */
    val engineMcpu: String = "",
    /** 本次加载**请求**的档；与 [engineProfile] 不同即发生了降级 */
    val engineProfileRequested: String = "",
    /** 是否发生降级（= engineProfile != engineProfileRequested） */
    val engineProfileFallback: Boolean = false,
    /** 降级原因（中文，非静默）；无需提示时为空串 */
    val engineProfileNote: String = ""
)