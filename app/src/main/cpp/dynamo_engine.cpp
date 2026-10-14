#include "dynamo_engine.h"
#include "run_boost.h"
#include "builtin_kernels.h"

#include <fcntl.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <thread>
#include <vector>
#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "DynamoEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) printf(__VA_ARGS__)
#define LOGE(...) fprintf(stderr, __VA_ARGS__)
#endif

namespace dynamo {

// 预分配阶段需要知道缓存模式（由 RunBenchmark 在调用前写入）
static uint32_t g_pending_cache_mode = 0;

// ============================================================================
//  缓存控制：两套手段
//    Root 专用   : echo 3 > /proc/sys/vm/drop_caches  —— 清空内核页缓存
//    常规(无root) : posix_fadvise(POSIX_FADV_DONTNEED) —— 指示内核丢弃该文件的页缓存
// ============================================================================

// AOSP system/vold/Benchmark.cpp 的做法（已核实部分）：
//   1) PerformanceBoost：setpriority(PRIO_PROCESS, 0, -10) 提升进程优先级，
//      android_set_ioprio(0, IoSchedClass_RT, 0) 置实时 I/O 调度类，析构时恢复
//   2) 每阶段 20 秒超时（kTimeout = 20s）
//   注：drop_caches 相关行为在本轮查证的片段中未取得原文佐证，
//   因此本引擎不对外断言"drop_caches 失败即判失败"；sync + 写 "3" 到
//   /proc/sys/vm/drop_caches 仅作为本项目专业模式的实际实现手段如实披露。
static bool DropCachesAsRoot() {
    sync();
    const int rc = system("su -c 'sync; echo 3 > /proc/sys/vm/drop_caches' >/dev/null 2>&1");
    return rc == 0;
}

// 物理内存总量：用于判断测试文件是否可能整体驻留页缓存
static uint64_t PhysicalRamBytes() {
    const long pages = sysconf(_SC_PHYS_PAGES);
    const long psize = sysconf(_SC_PAGE_SIZE);
    if (pages <= 0 || psize <= 0) return 0;
    return static_cast<uint64_t>(pages) * static_cast<uint64_t>(psize);
}

// I/O 优先类置为 RT（对齐 AOSP PerformanceBoost 的 android_set_ioprio(..., IoSchedClass_RT, 0)）
// IOPRIO_WHO_PROCESS=1, IOPRIO_CLASS_RT=1, 优先级 0 -> 编码 (1 << 13)
static int IoprioSetRt() {
#ifdef SYS_ioprio_set
    return static_cast<int>(syscall(SYS_ioprio_set, 1, 0, (1 << 13)));
#else
    return -1;
#endif
}
static int IoprioGet() {
#ifdef SYS_ioprio_get
    return static_cast<int>(syscall(SYS_ioprio_get, 1, 0));
#else
    return -1;
#endif
}
static int IoprioSetRaw(int val) {
#ifdef SYS_ioprio_set
    if (val < 0) return -1;
    return static_cast<int>(syscall(SYS_ioprio_set, 1, 0, val));
#else
    return -1;
#endif
}

static bool FadviseDontNeed(const std::string& path) {
    const int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) return false;
    const int rc = posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
    close(fd);
    return rc == 0;
}

// 单次 I/O 采用的通道：1=O_DIRECT，2=O_SYNC 回退，3=缓冲
// 映射关系（披露文案必须与此一致）：含缓存模式 → 缓冲 I/O；无缓存参与模式 → O_DIRECT（不允许回退）
// 单次 I/O 延迟超过该值即计为一次"控制器超时征兆"（fio 的 clat 长尾口径）
static constexpr double kTimeoutThresholdUs = 1000000.0;   // 1 秒

static constexpr int kIoDirect = 1;
static constexpr int kIoSync = 2;
static constexpr int kIoBuffered = 3;

static const char* IoModeName(int m) {
    switch (m) {
        case kIoDirect:   return "O_DIRECT（绕过页缓存，直连存储）";
        case kIoSync:     return "O_SYNC 回退（O_DIRECT 打开失败后的回退通道）";
        case kIoBuffered: return "缓冲 I/O（页缓存参与）";
        default:          return "未知";
    }
}

// 采样档位归一：契约只允许 5/10/25/50/75/100 ms 六档。
// 0 视作"未设置"，回落到默认档 100 ms（而不是被钳成 5 ms 造成 200 Hz 空转）；
// 其它非法值取最接近的合法档，保证原生侧永远不会跑出契约集合之外。
static uint32_t NormalizeSampleIntervalMs(uint32_t v) {
    static const uint32_t kOptions[] = {5, 10, 25, 50, 75, 100};
    if (v == 0) return 100;
    uint32_t best = 100;
    uint32_t best_diff = 0xFFFFFFFFu;
    for (uint32_t o : kOptions) {
        const uint32_t diff = (v > o) ? (v - o) : (o - v);
        if (diff < best_diff) { best_diff = diff; best = o; }
    }
    return best;
}

// 线程级 CPU 时间（纳秒）：只统计**调用线程自身**。
// 这是"仅测试负载"CPU 口径的基石——不会把 UI 线程与 Compose 渲染算进来。
static uint64_t ThreadCpuNs() {
    struct timespec ts{};
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL +
           static_cast<uint64_t>(ts.tv_nsec);
}

// 在线 CPU 核数（用于把"各核累加"归一化成 0~100% 的直觉口径）
static uint32_t OnlineCores() {
    const long n = sysconf(_SC_NPROCESSORS_ONLN);
    return (n > 0) ? static_cast<uint32_t>(n) : 1u;
}


// ============================================================================
//  编译工具链标识（**全部由编译期宏生成**，Kotlin 侧只做搬运）
//
//  为什么需要：官方 run rules 要求按 base(-O1)/peak(-O2) 调优等级上报，
//  成绩脱离编译器与优化选项无法比较；本移植版是单一固定优化等级的 NDK 构建，
//  与官方规则不同，必须如实披露，否则比值会被误读。
//  宏来源：CMake 在配置期把真实生效的编译参数写入 DYNAMO_BUILD_*（见 CMakeLists.txt），
//  其余由编译器内建宏给出。任何一项都不得在 Kotlin/Java 侧硬编码。
// ============================================================================
const char* EngineProfileId() {
#ifdef DYNAMO_PROFILE_ID
    return DYNAMO_PROFILE_ID;
#else
    return "baseline";          // 未注入宏时按最保守的公平基线自述，绝不虚报高档位
#endif
}

const char* EngineMcpuName() {
#ifdef DYNAMO_PROFILE_MCPU
    return DYNAMO_PROFILE_MCPU;
#else
    return "generic";
#endif
}

const char* EngineProfileCatalog() {
#ifdef DYNAMO_PROFILE_CATALOG
    return DYNAMO_PROFILE_CATALOG;
#else
    // 未注入宏时的最小可用目录：只报基线，绝不虚报高档位
    return "baseline=generic,";
#endif
}

const char* EngineCcOrigin() {
#ifdef DYNAMO_CC_ORIGIN
    return DYNAMO_CC_ORIGIN;
#else
    return "未知工具链";
#endif
}

bool EngineProfileIsExperimental() {
#ifdef DYNAMO_PROFILE_EXPERIMENTAL
    return std::string(DYNAMO_PROFILE_EXPERIMENTAL) == "1";
#else
    return false;
#endif
}

const char* EngineExperimentalProfiles() {
#ifdef DYNAMO_EXPERIMENTAL_PROFILES
    return DYNAMO_EXPERIMENTAL_PROFILES;
#else
    return "";
#endif
}



std::string CompilerToolchainInfo() {
    std::string s;
    s.reserve(384);

    // ---- 编译器与完整版本串 ----
    s += "编译器 ";
#ifdef __clang__
    s += "Clang __clang_version__=";
    s += __clang_version__;
#elif defined(__GNUC__)
    s += "GCC ";
#endif
#ifdef __VERSION__
    s += " __VERSION__=";
    s += __VERSION__;
#else
    s += " __VERSION__=不可用";
#endif

    // ---- 优化等级与真实编译参数（CMake 注入，来自实际 flags）----
#ifdef DYNAMO_BUILD_OPT_LEVEL
    s += " | 优化等级 ";
    s += DYNAMO_BUILD_OPT_LEVEL;
#else
    s += " | 优化等级 未注入";
#endif
#ifdef DYNAMO_BUILD_CONFIG
    s += " (构建类型 ";
    s += DYNAMO_BUILD_CONFIG;
    s += ")";
#endif
#ifdef DYNAMO_BUILD_FLAGS
    s += " | 编译参数 [";
    s += DYNAMO_BUILD_FLAGS;
    s += "]";
#endif

    // ---- 目标 ABI ----
    s += " | 目标 ABI ";
#if defined(__aarch64__)
    s += "arm64-v8a(__aarch64__)";
#elif defined(__arm__)
    s += "armeabi-v7a(__arm__)";
#elif defined(__x86_64__)
    s += "x86_64(__x86_64__)";
#elif defined(__i386__)
    s += "x86(__i386__)";
#else
    s += "未知";
#endif

    // ---- 生效的 -mcpu 档位（-mcpu 分档）----
    // 用户在结果页一眼能看出这条成绩是哪一档编出来的；降级与否由加载器另行披露。
    s += " | 生效 profile ";
    s += EngineProfileId();
    s += " (-mtune=";
    s += EngineMcpuName();
    if (std::string(EngineProfileId()) == "baseline") {
        s += "，未显式指定";
    }
    // 口径说明：档位是**调度调优**（-mtune），不启用目标核独有的 ISA 扩展，
    // 因此可安全运行于同 SoC 的全部核心（含小核）——异构 big.LITTLE 上 -mcpu 会 SIGILL。
    s += "；仅指令调度调优，不启用该核独有 ISA 扩展，可安全运行于同 SoC 全部核心)";

    // ---- 工具链来源 + 实验性标记 ----
    // 必须让用户一眼区分「NDK 内置 Clang 编的」与「独立 LLVM 23 编的」，
    // 实验性档位另加显式标记，避免与 NDK 档位混为一谈。
    s += " | 工具链来源 ";
    s += EngineCcOrigin();
    if (EngineProfileIsExperimental()) {
        // 用工具链中性的措辞：实验性档位可能来自独立 LLVM 23（C1）或 SDLLVM（Oryon）
        s += " | 档位属性 实验性（非 NDK 内置工具链）";
    }

    // ---- C++ 标准 ----
    s += " | __cplusplus=";
    s += std::to_string(static_cast<long long>(__cplusplus));
    return s;
}

static double GetMonotonicTimeSec() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + (ts.tv_nsec * 1e-9);
}

DynamoEngine::DynamoEngine() {}
DynamoEngine::~DynamoEngine() {
    Stop();
}

void DynamoEngine::Stop() {
    stop_requested_.store(true);
}

// 格式化 4KB 块的确定性可校验数据（魔数 0x55465342 "UFSB" + 64位块偏移 + 反码 + 伪随机序列）
static inline void FormatVerifiableBlock(void* block_ptr, uint64_t block_offset) {
    uint32_t* words = reinterpret_cast<uint32_t*>(block_ptr);
    const size_t word_count = 4096 / sizeof(uint32_t);
    words[0] = 0x55465342u; // "UFSB"
    words[1] = static_cast<uint32_t>(block_offset & 0xFFFFFFFFu);
    words[2] = static_cast<uint32_t>((block_offset >> 32) & 0xFFFFFFFFu);
    words[3] = ~words[1];
    const uint32_t seed = static_cast<uint32_t>(0xA5A50000u | ((block_offset >> 12) & 0xFFFFu));
    for (size_t i = 4; i < word_count; ++i) {
        words[i] = seed ^ static_cast<uint32_t>(i * 2654435761u);
    }
}

// 快速校验 4KB 数据块（魔数与偏移双重校验，抵御脏读/零读/串读）
static inline bool VerifyBlock(const void* block_ptr, uint64_t block_offset) {
    const uint32_t* words = reinterpret_cast<const uint32_t*>(block_ptr);
    if (words[0] != 0x55465342u) return false;
    if (words[1] != static_cast<uint32_t>(block_offset & 0xFFFFFFFFu)) return false;
    if (words[2] != static_cast<uint32_t>((block_offset >> 32) & 0xFFFFFFFFu)) return false;
    if (words[3] != ~words[1]) return false;
    return true;
}

bool DynamoEngine::PrepareTargetFile(const std::string& file_path, uint64_t size_bytes, uint32_t cache_mode, std::string& err) {
    stop_requested_.store(false);
    is_running_.store(false);
    uint32_t eff_cache_mode = (cache_mode != 0) ? cache_mode : g_pending_cache_mode;
    g_pending_cache_mode = eff_cache_mode;

    int flags = O_RDWR | O_CREAT | O_TRUNC;
    if (eff_cache_mode != 2) {          // 非"含缓存"模式：优先 O_DIRECT
#ifdef O_DIRECT
        flags |= O_DIRECT;
#endif
    }

    int fd = open(file_path.c_str(), flags, 0666);
    if (fd < 0) {
        if (eff_cache_mode == 1) {
            // "无缓存参与"模式不接受静默回退，必须如实报错
            err = "无缓存参与模式要求 O_DIRECT，但当前文件系统不支持（" +
                  std::string(strerror(errno)) + "）。请改用「含缓存」模式";
            return false;
        }
        flags = O_RDWR | O_CREAT | O_TRUNC | O_SYNC;
        fd = open(file_path.c_str(), flags, 0666);
        if (fd < 0) {
            err = "无法打开/创建测试文件：" + std::string(strerror(errno));
            return false;
        }
    }

    const size_t buf_size = 1024 * 1024; // 1MB 写入块
    void* buffer = nullptr;
    if (posix_memalign(&buffer, 4096, buf_size) != 0) {
        close(fd);
        err = "posix_memalign 内存分配失败";
        return false;
    }

    uint64_t written = 0;
    while (written < size_bytes && !stop_requested_.load()) {
        size_t to_write = std::min<uint64_t>(buf_size, size_bytes - written);
        // 按 4KB 粒度逐块填充可校验数据，打上精确的文件偏移标记
        char* buf_ptr = reinterpret_cast<char*>(buffer);
        for (size_t b = 0; b < to_write; b += 4096) {
            FormatVerifiableBlock(buf_ptr + b, written + b);
        }
        ssize_t ret = write(fd, buffer, to_write);
        if (ret <= 0) {
            err = "写入测试文件错误：" + std::string(strerror(errno));
            free(buffer);
            close(fd);
            return false;
        }
        written += ret;
    }

    fdatasync(fd);
#ifdef POSIX_FADV_DONTNEED
    // 关键：绕过缓存时必须显式驱逐 page cache，保证后续 O_DIRECT 直接读取底层闪存
    if (eff_cache_mode != 2) {
        posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
    }
#endif
    free(buffer);
    close(fd);

    if (stop_requested_.load()) {
        err = "用户中断文件预分配";
        return false;
    }
    return true;
}

struct WorkerStats {
    uint64_t read_bytes{0};
    uint64_t write_bytes{0};
    uint64_t read_ops{0};
    uint64_t write_ops{0};
    uint64_t cpu_ops{0};
    uint64_t mem_bytes{0};
    uint64_t timeout_count{0};      // 单次 I/O 延迟超阈值
    uint64_t io_error_count{0};     // 系统调用失败
    uint64_t validation_passed_blocks{0}; // 数据校验通过块数 (4KB)
    uint64_t validation_error_blocks{0};  // 数据校验错误块数 (4KB)
    std::vector<double> latencies_us;

    // ---- 分读写的延迟计数器（单调递增）----
    // 只累加"延迟总和(μs)"与"完成次数"，由监控线程对相邻两次采样取增量求区间平均延迟。
    // 契约明确不要把所有延迟样本堆进 vector（内存与拷贝开销过大），
    // 这里的累加是 worker 私有字段，无跨线程写竞争。
    uint64_t read_lat_sum_us{0};
    uint64_t read_lat_count{0};
    uint64_t write_lat_sum_us{0};
    uint64_t write_lat_count{0};

    // 本 worker 线程自身的累计 CPU 时间（单调递增，纳秒）。
    // 由 worker 每 64 次 I/O 发布一次（不做逐次系统调用，避免把测量开销计入被测负载），
    // 监控线程对相邻采样取增量求和后除以墙钟间隔 = "仅测试负载"的 CPU 占用。
    uint64_t cpu_ns{0};
};

BenchmarkResult DynamoEngine::RunBenchmark(
    const std::string& file_path,
    const AccessSpecification& spec,
    MetricCallback progress_cb
) {
    BenchmarkResult res{};
    res.success = false;
    // 编译工具链披露：所有路径（存储测试 / SPEC 完整版 / 仿真版）都要带上，
    // 因此在这里一次性填好，而不是塞进只对专业模式填充的 detail。
    res.compiler_toolchain = CompilerToolchainInfo();
    // 本 .so 的档位自述（编译期宏）：随每条结果带出，历史报告也能如实标注
    res.engine_profile = EngineProfileId();
    res.engine_mcpu = EngineMcpuName();

    // ==================== 运行期资源提升（覆盖**整个**压测周期）====================
    // 旧实现把 setpriority(-10)/I/O RT 在清缓存处就地恢复，早于 worker 启动 —— 真正跑压测的
    // 线程全程是默认优先级（源码审查发现）。现由 RAII 持有：析构点在函数返回处，覆盖 SPEC 与
    // 存储两条路径；任何提前 return 也会正确恢复。
    // 大核亲和性：消除 Android EAS 把计算线程迁到小核造成的抖动（主频落差可达 2 倍以上）；
    // 该动作会改变测量条件，因此必须如实披露（写入 res.privilege_method）。
    dynamo::BigCoreMasks big_core_masks = dynamo::BuildBigCoreMasks();
    std::string run_boost_note;
    dynamo::RunScopeBoost run_boost(spec.privilege == 2, big_core_masks, spec.worker_threads, true, &run_boost_note);
    // 运行环境披露收口：任何返回路径都要带上绑核/提升的实际结果（常规模式同样披露，不得静默）
    auto append_run_env = [&res, &run_boost_note]() {
        if (run_boost_note.empty()) return;
        if (!res.privilege_method.empty()) res.privilege_method += "；";
        res.privilege_method += std::string("运行环境：") + run_boost_note;
    };

    // ==================== 内置基准内核分派 ====================
    // 内核裁剪自上游官方源码（CoreMark / STREAM / tinymembench），纯 CPU + 内存层次测量，
    // 含上游自带的正确性校验；给出的比值相对**本机自标定基线**，不是上游官方分数。
    // 旧的自研实现（821/766/750/854）已按需求整体删除，编号空间不再复用。
    if (spec.full_version && spec.spec_workload_id != 0) {
        stop_requested_.store(false);
        is_running_.store(true);

        dynamo::builtin::RunConfig cfg;
        cfg.threads = std::max<uint32_t>(1, spec.worker_threads);
        cfg.scale = std::max<uint32_t>(1, std::min<uint32_t>(3, spec.spec_scale));  // 1=test 2=train 3=参考档（单任务口径→参考档 / 多任务口径→参考档）
        cfg.avail_mem_bytes = spec.avail_mem_bytes;
        cfg.stop = &stop_requested_;

        auto live_cb = [&](const dynamo::builtin::LiveStats& st) {
            if (!progress_cb) return;
            ProgressMetric pm{};
            pm.current_read_mbps = 0.0;
            pm.current_write_mbps = 0.0;
            pm.current_read_iops = 0;
            pm.current_write_iops = 0;
            pm.avg_latency_us = 0.0;
            pm.max_latency_us = 0.0;
            pm.p99_latency_us = 0.0;
            pm.elapsed_seconds = static_cast<uint32_t>(st.elapsed_seconds);
            pm.total_seconds = spec.duration_seconds;
            pm.live_spec_score = 0;
            pm.live_cpu_metric = st.ops_per_sec;
            pm.live_mem_metric = st.mem_gbps;
            pm.live_spec_ratio = st.projected_ratio;
            progress_cb(pm);
        };

        dynamo::builtin::RunResult sr;
        if (!dynamo::builtin::IsBenchKernelId(spec.spec_workload_id)) {
            res.error_message = "未知的内置基准编号（本版本只认 CoreMark / STREAM / tinymembench）";
            is_running_.store(false);
            append_run_env();
            return res;
        }
        sr = dynamo::builtin::RunBenchKernel(spec.spec_workload_id, cfg, live_cb);

        res.success = sr.success;
        res.error_message = sr.error;
        res.verified = sr.verified;
        res.verify_detail = sr.verify_detail;
        res.spec_ratio = sr.ratio;
        res.reference_seconds = sr.reference_seconds;
        res.work_units = sr.work_units;
        res.ops_per_sec = sr.ops_per_sec;
        res.final_cpu_metric = sr.ops_per_sec;
        res.final_mem_metric = sr.mem_gbps;
        res.final_spec_score = static_cast<uint64_t>(sr.ratio * 1000.0);
        res.total_read_mbps = 0.0;
        res.total_write_mbps = 0.0;
        res.total_read_iops = 0;
        res.total_write_iops = 0;
        res.avg_latency_us = 0.0;
        res.min_latency_us = 0.0;
        res.max_latency_us = 0.0;
        res.p95_latency_us = 0.0;
        res.p99_latency_us = 0.0;

        // 纯 CPU + 内存层次测量，不经过任何存储 I/O 通道：
        // 因此不设置 io_method / privilege_method（留空即"无存储 I/O 通道"，绝不伪造通道）
        is_running_.store(false);
        append_run_env();
        return res;
    }

    if (spec.request_size_bytes == 0 || spec.file_size_bytes < spec.request_size_bytes) {
        res.error_message = "测试参数不合法：块大小或文件大小错误";
        append_run_env();
        return res;
    }

    // ============ 缓存与权限手段：先于计时执行 ============
    // 依据：
    //   AOSP system/vold/Benchmark.cpp —— 官方存储基准运行在 vold(root) 内，
    //     参考其 PerformanceBoost 做法（提升进程优先级 + RT I/O 调度类）；
    //     drop_caches 的成败本引擎如实披露，不援引未经原文佐证的判定规则。
    //   AndroBench 论文 —— 要绕开 buffer cache 必须走 JNI 原生 C（Java 无此能力）。
    //   posix_fadvise(2) —— POSIX_FADV_DONTNEED 仅是对内核的提示，对脏页与在用页可能不生效。
    g_pending_cache_mode = spec.cache_mode;

    // 已取消"自动"档：1=常规模式，2=专业模式；专业模式才走 root 手段
    const bool want_root = (spec.privilege == 2);
    const uint64_t ram = PhysicalRamBytes();
    bool root_applied = false;

    if (want_root) {
        // 优先级与 I/O 调度类不在此处恢复：由 RunScopeBoost 持有到压测结束

        root_applied = DropCachesAsRoot();


        if (root_applied) {
            res.privilege_method = std::string("专业手段（root）：参考 AOSP PerformanceBoost")
                + "；并以 sync + drop_caches(写 3) 清空内核页缓存";
        } else {
            res.privilege_method = "请求专业手段（root）但 drop_caches 失败（未获 CAP_SYS_ADMIN 或 su 被拒）";
            if (spec.privilege == 2 && spec.cache_mode == 1) {
                res.success = false;
                res.error_message =
                    "无缓存参与 + 专业手段（root）要求清空内核页缓存，但 drop_caches 失败。"
                    "本应用不静默降级，直接判失败；本引擎专业手段采用的做法是"
                    "参考 AOSP PerformanceBoost 的提升进程优先级 + RT I/O 调度类。"
                    "请确认已授权 root，或改用「常规（无 root）」手段。";
                is_running_.store(false);
                append_run_env();
        return res;
            }
        }
    }

    if (!root_applied) {
        if (spec.cache_mode == 1 && !file_path.empty()) {
            const bool fav_ok = FadviseDontNeed(file_path);
            res.privilege_method = std::string("常规手段（无 root）：O_DIRECT 直连 + posix_fadvise(DONTNEED)")
                + (fav_ok ? "（已请求丢弃该文件页缓存）" : "（fadvise 调用失败）")
                + "；注意 POSIX_FADV_DONTNEED 仅为提示，对脏页与在用页可能不生效";
        } else {
            // 含缓存模式：本来就走缓冲 I/O，绝不能声称"依赖 O_DIRECT"
            // —— 实测出现过「I/O 通道：缓冲 I/O」与「依赖 O_DIRECT 规避页缓存」并列的矛盾披露。
            res.privilege_method =
                "常规手段（无 root）：缓冲 I/O，页缓存参与测量，数值包含缓存命中收益";
        }

        // 无缓存参与 + 无 root：容量必须足够大，否则无法排除整体驻留缓存
        if (spec.cache_mode == 1 && ram > 0) {
            const double ratio = static_cast<double>(spec.file_size_bytes) / static_cast<double>(ram);
            if (ratio < 0.5) {
                char buf[320];
                snprintf(buf, sizeof(buf),
                         "[容量不足] 测试容量 %llu MB 仅为物理内存 %llu MB 的 %.0f%%，"
                         "在无 root 无法清空页缓存的前提下该文件可能整体驻留缓存，读数偏高。"
                         "建议容量 ≥ 物理内存的 1/2（≥ %llu MB），或改用「专业模式（root）」。",
                         (unsigned long long)(spec.file_size_bytes / (1024 * 1024)),
                         (unsigned long long)(ram / (1024 * 1024)),
                         ratio * 100.0,
                         (unsigned long long)(ram / (1024 * 1024) / 2));
                res.verify_detail = buf;
            }
        }
    }

    // 记录工作线程实际打开成功的 I/O 通道（用于如实披露）
    std::atomic<int> io_mode_seen{kIoBuffered};

    stop_requested_.store(false);
    is_running_.store(true);

    // 存储路径的并发 I/O 数 = 队列深度 × 线程数。
    // 本引擎用同步 pread/pwrite，故以"并发线程数"近似队列深度（QD）；
    // 这是让小 QD 与大 QD 真正跑出差异的关键，否则 Q 号只是标签。
    // 上限 64：超过后 pthread 与内核调度开销反噬，且移动端收益递减。
    const uint32_t desired_io = spec.queue_depth * std::max<uint32_t>(1, spec.worker_threads);
    const uint32_t total_workers = std::min<uint32_t>(64, std::max<uint32_t>(1, desired_io));
    std::vector<WorkerStats> worker_stats(total_workers);
    std::vector<std::thread> workers;
    workers.reserve(total_workers);

    // 动态以实际文件在磁盘上的物理尺寸为准计算寻道块上限，
    // 避免大容量测试或扩容后仍被死锁在 256MB 局部小块内频繁绕回。
    uint64_t actual_file_bytes = spec.file_size_bytes;
    struct stat st_file{};
    if (stat(file_path.c_str(), &st_file) == 0 && st_file.st_size > 0) {
        actual_file_bytes = static_cast<uint64_t>(st_file.st_size);
    }
    const uint64_t max_blocks = std::max<uint64_t>(1, actual_file_bytes / spec.request_size_bytes);

    std::atomic<bool> start_signal{false};

    try {
        for (uint32_t w_id = 0; w_id < total_workers; ++w_id) {
            workers.emplace_back([&, w_id]() {
            // worker 各自绑到大核（主线程已在 res.privilege_method 里披露）
            // 开关必须对 worker 同样生效（否则 nopin 只关掉主线程，worker 仍被绑核）
            if (!dynamo::DebugNoPin()) {
                // 多线程：每个 worker 绑到掩码内不同的核（轮转），实现「分开调动多核」
                dynamo::PinWorkerToDistinctCore(dynamo::PickMask(big_core_masks, total_workers), w_id, nullptr);
            }
            int flags = (spec.percent_read == 100) ? O_RDONLY : O_RDWR;
            if (spec.cache_mode != 2) {
#ifdef O_DIRECT
                flags |= O_DIRECT;
#endif
            }
            int fd = open(file_path.c_str(), flags);
            if (fd >= 0) {
                if (spec.cache_mode != 2) {
                    io_mode_seen.store(kIoDirect);
#ifdef POSIX_FADV_DONTNEED
                    // 绕过缓存模式：显式驱逐 page cache，保证读取真实直通 UFS 闪存颗粒
                    posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
#endif
                }
            } else {
                if (spec.cache_mode == 1) {
                    // 无缓存参与模式不允许回退
                    io_mode_seen.store(kIoBuffered);
                    return;
                }
                flags = ((spec.percent_read == 100) ? O_RDONLY : O_RDWR) | O_SYNC;
                fd = open(file_path.c_str(), flags);
                if (fd >= 0) {
                    io_mode_seen.store(kIoSync);
                } else {
                    fd = open(file_path.c_str(), (spec.percent_read == 100) ? O_RDONLY : O_RDWR);
                    if (fd < 0) return;
                    io_mode_seen.store(kIoBuffered);
                }
            }

            void* buf = nullptr;
            if (posix_memalign(&buf, 4096, spec.request_size_bytes) != 0) {
                close(fd);
                return;
            }
            memset(buf, 0xAA, spec.request_size_bytes);

            // 等待统一起跑信号
            while (!start_signal.load() && !stop_requested_.load()) {
                std::this_thread::yield();
            }

            unsigned int seed = static_cast<unsigned int>(time(nullptr) + w_id * 1013);
            uint64_t seq_block = (max_blocks / total_workers) * w_id;

            WorkerStats& stats = worker_stats[w_id];
            stats.latencies_us.reserve(50000);
            // 本线程 CPU 计时的发布节奏（每 64 次 I/O 一次）
            uint32_t cpu_publish_tick = 0;

            while (!stop_requested_.load()) {
                // 1. 判断读还是写 (Iometer: percent_read)
                bool is_read = static_cast<uint32_t>(rand_r(&seed) % 100) < spec.percent_read;

                // 2. 判断寻道位置 (Iometer: percent_random)
                bool is_random = static_cast<uint32_t>(rand_r(&seed) % 100) < spec.percent_random;
                uint64_t block_index = 0;
                if (is_random) {
                    block_index = rand_r(&seed) % max_blocks;
                } else {
                    block_index = seq_block++;
                    if (seq_block >= max_blocks) seq_block = 0;
                }
                off_t offset = static_cast<off_t>(block_index * spec.request_size_bytes);

                // 3. 执行单次 I/O 并测量延迟
                struct timespec t_start, t_end;
                clock_gettime(CLOCK_MONOTONIC, &t_start);

                ssize_t ret = 0;
                if (is_read) {
                    ret = pread(fd, buf, spec.request_size_bytes, offset);
                    if (ret > 0) {
                        // 读操作附带数据完整性校验：逐 4KB 验证魔数、偏移量与反码
                        const char* p_buf = reinterpret_cast<const char*>(buf);
                        for (size_t b = 0; b + 4096 <= static_cast<size_t>(ret); b += 4096) {
                            uint64_t blk_off = static_cast<uint64_t>(offset) + b;
                            if (VerifyBlock(p_buf + b, blk_off)) {
                                stats.validation_passed_blocks++;
                            } else {
                                stats.validation_error_blocks++;
                            }
                        }
                    }
                } else {
                    // 写操作自动打上确定性校验头与偏移量，使后续读取阶段能无缝校验
                    char* b_ptr = reinterpret_cast<char*>(buf);
                    for (size_t b = 0; b < spec.request_size_bytes; b += 4096) {
                        FormatVerifiableBlock(b_ptr + b, static_cast<uint64_t>(offset) + b);
                    }
                    ret = pwrite(fd, buf, spec.request_size_bytes, offset);
                }

                clock_gettime(CLOCK_MONOTONIC, &t_end);

                // 每 64 次 I/O 发布一次本线程累计 CPU 时间（单调递增）。
                // 用 __atomic 存取：armeabi-v7a 上 64 位读写不是天然原子的。
                if ((++cpu_publish_tick & 63u) == 0u) {
                    __atomic_store_n(&stats.cpu_ns, ThreadCpuNs(), __ATOMIC_RELAXED);
                }

                if (ret < 0) {
                    // 记录真实的系统调用失败（EIO / EREMOTEIO / ETIMEDOUT 等）
                    stats.io_error_count++;
                } else if (ret > 0) {
                    double lat_us = (t_end.tv_sec - t_start.tv_sec) * 1e6 +
                                    (t_end.tv_nsec - t_start.tv_nsec) * 1e-3;

                    // 控制器超时征兆：单次 I/O 延迟超过阈值（专业模式统计）
                    if (lat_us > kTimeoutThresholdUs) stats.timeout_count++;

                    // 分读写累计：延迟总和与完成次数分别单调递增，
                    // 供监控线程按采样区间取增量（不保留全量样本）
                    const uint64_t lat_us_i = static_cast<uint64_t>(lat_us + 0.5);
                    if (is_read) {
                        stats.read_bytes += ret;
                        stats.read_ops++;
                        stats.read_lat_sum_us += lat_us_i;
                        stats.read_lat_count++;
                    } else {
                        stats.write_bytes += ret;
                        stats.write_ops++;
                        stats.write_lat_sum_us += lat_us_i;
                        stats.write_lat_count++;
                    }

                    if (stats.latencies_us.size() < 100000) {
                        stats.latencies_us.push_back(lat_us);
                    }
                }

                // 2026-10-13 审计整改：此处原有「内嵌假算力模拟」（排 256 个数、做 510 次加乘、
                // 小数组里跳 256 次指针），耗时不足 I/O 的万分之一，却宣称「真实协同执行算力高压」。
                // 已彻底删除：存储压测即纯存储压测；算力评测走独立微基准，绝不在 I/O 循环掺假负载。
            }

            // 退出前发布最终 CPU 时间，避免最后一次采样丢失尾部
            __atomic_store_n(&stats.cpu_ns, ThreadCpuNs(), __ATOMIC_RELAXED);

            free(buf);
            close(fd);
        });
        }
    } catch (const std::exception& e) {
        stop_requested_.store(true);
        start_signal.store(true);
        for (auto& t : workers) {
            if (t.joinable()) t.join();
        }
        res.success = false;
        res.error_message = std::string("工作线程创建异常：") + e.what();
        is_running_.store(false);
        append_run_env();
        return res;
    }

    start_signal.store(true);
    double bench_start_time = GetMonotonicTimeSec();
    double last_sample_time = bench_start_time;

    uint64_t prev_read_bytes = 0;
    uint64_t prev_write_bytes = 0;
    uint64_t prev_read_ops = 0;
    uint64_t prev_write_ops = 0;
    // 分读写延迟的上一采样值（worker 侧为单调递增计数器，此处只取增量）
    uint64_t prev_read_lat_sum = 0, prev_write_lat_sum = 0;
    uint64_t prev_read_lat_cnt = 0, prev_write_lat_cnt = 0;

    // 采样档位由用户选定（5/10/25/50/75/100 ms），替换原先写死的
    // kSampleInterval = professional ? 0.1 : 1.0。
    // 专业模式与常规模式都使用该间隔：未开启专业模式时不记录曲线，但进度回调仍按该间隔。
    // 这里只做兜底归一（上层漏传/传 0 时回落到默认档，不跑飞）。
    const uint32_t sample_ms = NormalizeSampleIntervalMs(spec.sample_interval_ms);
    const double kSampleInterval = sample_ms / 1000.0;
    // 轮询周期取间隔的一半（下限 5 ms、上限 500 ms）：太大会把两个采样点挤到一起，
    // 太小则纯空转吃 CPU。
    const uint32_t poll_ms =
        std::max<uint32_t>(5, std::min<uint32_t>(500, sample_ms / 2));

    // 专业模式：按用户选定档位高速采样，用于性能曲线 / 抖动 / 掉速记录
    dynamo::DetailedStats detail;
    detail.present = spec.professional_stats;
    if (spec.professional_stats) {
        detail.sample_interval_ms = kSampleInterval * 1000.0;
        detail.timeout_threshold_us = kTimeoutThresholdUs;
        detail.curve_read_bw_mbps.reserve(4096);
        detail.curve_write_bw_mbps.reserve(4096);
        detail.curve_read_lat_us.reserve(4096);
        detail.curve_write_lat_us.reserve(4096);
        detail.curve_cpu_percent.reserve(4096);
        detail.curve_time_s.reserve(4096);
    }
    // 工作线程 CPU 的上一次采样值（各 worker 自身累加，这里只取增量求和）
    uint64_t prev_worker_cpu_ns = 0;
    const uint32_t io_workers = std::max<uint32_t>(1, total_workers);

    bool capacity_reached = false;
    uint64_t bytes_at_capacity = 0;

    // 监控与采样轮询循环
    while (!stop_requested_.load()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(poll_ms));
        double now = GetMonotonicTimeSec();
        double elapsed_total = now - bench_start_time;

        // ---- 终止条件：按容量 或 按时间（互斥，由 stop_criterion 决定）----
        if (spec.stop_criterion == 1 && spec.stop_after_bytes > 0) {
            uint64_t done_now = 0;
            for (const auto& ws : worker_stats) done_now += ws.read_bytes + ws.write_bytes;
            if (done_now >= spec.stop_after_bytes) {
                capacity_reached = true;
                bytes_at_capacity = done_now;
                stop_requested_.store(true);
                break;
            }
            // 安全上限只认真实的高限（max_duration_seconds），
            // 绝不能用 duration_seconds —— 那是"按时间"模式的目标值，
            // 混用会让定容测试被残留的时长设置提前掐断。
            if (spec.max_duration_seconds > 0 &&
                elapsed_total >= spec.max_duration_seconds) {
                stop_requested_.store(true);
                break;
            }
        } else if (spec.duration_seconds > 0 &&
                   elapsed_total >= spec.duration_seconds) {
            stop_requested_.store(true);
            break;
        }

        if (now - last_sample_time >= kSampleInterval) {
            double interval = now - last_sample_time;
            last_sample_time = now;

            uint64_t cur_r_bytes = 0, cur_w_bytes = 0;
            uint64_t cur_r_ops = 0, cur_w_ops = 0;
            uint64_t cur_r_lat_sum = 0, cur_w_lat_sum = 0;
            uint64_t cur_r_lat_cnt = 0, cur_w_lat_cnt = 0;
            for (const auto& ws : worker_stats) {
                cur_r_bytes += ws.read_bytes;
                cur_w_bytes += ws.write_bytes;
                cur_r_ops += ws.read_ops;
                cur_w_ops += ws.write_ops;
                cur_r_lat_sum += ws.read_lat_sum_us;
                cur_w_lat_sum += ws.write_lat_sum_us;
                cur_r_lat_cnt += ws.read_lat_count;
                cur_w_lat_cnt += ws.write_lat_count;
            }

            uint64_t delta_r_bytes = cur_r_bytes - prev_read_bytes;
            uint64_t delta_w_bytes = cur_w_bytes - prev_write_bytes;
            uint64_t delta_r_ops = cur_r_ops - prev_read_ops;
            uint64_t delta_w_ops = cur_w_ops - prev_write_ops;
            const uint64_t delta_r_lat_sum = cur_r_lat_sum - prev_read_lat_sum;
            const uint64_t delta_w_lat_sum = cur_w_lat_sum - prev_write_lat_sum;
            const uint64_t delta_r_lat_cnt = cur_r_lat_cnt - prev_read_lat_cnt;
            const uint64_t delta_w_lat_cnt = cur_w_lat_cnt - prev_write_lat_cnt;

            prev_read_bytes = cur_r_bytes;
            prev_write_bytes = cur_w_bytes;
            prev_read_ops = cur_r_ops;
            prev_write_ops = cur_w_ops;
            prev_read_lat_sum = cur_r_lat_sum;
            prev_write_lat_sum = cur_w_lat_sum;
            prev_read_lat_cnt = cur_r_lat_cnt;
            prev_write_lat_cnt = cur_w_lat_cnt;

            ProgressMetric pm{};
            pm.current_read_mbps = (delta_r_bytes / (1024.0 * 1024.0)) / interval;
            pm.current_write_mbps = (delta_w_bytes / (1024.0 * 1024.0)) / interval;
            pm.current_read_iops = static_cast<uint64_t>(delta_r_ops / interval);
            pm.current_write_iops = static_cast<uint64_t>(delta_w_ops / interval);
            // 本区间的平均响应延迟：对单调计数器的增量求平均（分读/写）。
            // 该区间内没有对应方向的完成次数时为 0（不伪造数值）。
            pm.read_avg_latency_us = (delta_r_lat_cnt > 0)
                ? static_cast<double>(delta_r_lat_sum) / static_cast<double>(delta_r_lat_cnt)
                : 0.0;
            pm.write_avg_latency_us = (delta_w_lat_cnt > 0)
                ? static_cast<double>(delta_w_lat_sum) / static_cast<double>(delta_w_lat_cnt)
                : 0.0;
            // 注：avg_latency_us / max_latency_us / p99_latency_us 保持为 0，
            // 不在运行期伪造"全程分位"。测试结束后的 BenchmarkResult 才给出
            // min/avg/max/p95/p99（由全量样本排序得到）。实时分读写延迟请用上面两个字段。
            pm.elapsed_seconds = static_cast<uint32_t>(elapsed_total);
            pm.total_seconds = spec.duration_seconds;

            // 容量模式的进度：UI 据此画进度条，避免拿时间当分母
            {
                uint64_t done_now = 0;
                for (const auto& ws : worker_stats) {
                    done_now += ws.read_bytes + ws.write_bytes;
                }
                pm.bytes_done = done_now;
                pm.target_bytes = (spec.stop_criterion == 1) ? spec.stop_after_bytes : 0;
            }

            // 统计实时 CPU 算力与 内存 吞吐
            uint64_t cur_cpu_ops = 0, cur_mem_bytes = 0;
            for (const auto& ws : worker_stats) {
                cur_cpu_ops += ws.cpu_ops;
                cur_mem_bytes += ws.mem_bytes;
            }
            double cur_cpu_ops_sec = (cur_cpu_ops / elapsed_total);
            double cur_mem_gbps = (cur_mem_bytes / (1024.0 * 1024.0 * 1024.0)) / elapsed_total;
            pm.live_cpu_metric = cur_cpu_ops_sec / 1e6; // M-Ops/s
            pm.live_mem_metric = cur_mem_gbps;

            if (spec.spec_workload_id > 0) {
                double io_total_mbps = pm.current_read_mbps + pm.current_write_mbps;
                double raw_score = (cur_cpu_ops_sec / 1e5) * 15.0 + (cur_mem_gbps * 1200.0) + (io_total_mbps * 12.0);
                pm.live_spec_score = static_cast<uint64_t>(raw_score);
            } else {
                pm.live_spec_score = 0;
            }

            // ---- 专业模式：记录本采样点的曲线数据 ----
            if (spec.professional_stats) {
                // CPU 口径（需求方已确认「仅测试负载」）：
                // 只把各 benchmark worker 线程自身的 CPU 时间增量求和，
                // 不含 UI 线程、Compose 渲染与监控线程。
                uint64_t cur_worker_cpu_ns = 0;
                for (const auto& ws : worker_stats) {
                    cur_worker_cpu_ns += __atomic_load_n(&ws.cpu_ns, __ATOMIC_RELAXED);
                }
                const uint64_t delta_cpu_ns = cur_worker_cpu_ns - prev_worker_cpu_ns;
                prev_worker_cpu_ns = cur_worker_cpu_ns;
                // 各核累加口径：多线程满载时可 >100%
                const double cpu_pct = (interval > 0.0)
                    ? (static_cast<double>(delta_cpu_ns) / 1e9 / interval) * 100.0
                    : 0.0;
                detail.curve_cpu_percent.push_back(cpu_pct);

                detail.curve_time_s.push_back(elapsed_total);
                // 分读、分写：吞吐与延迟各自成列，合并口径已按契约删除
                detail.curve_read_bw_mbps.push_back(pm.current_read_mbps);
                detail.curve_write_bw_mbps.push_back(pm.current_write_mbps);
                detail.curve_read_lat_us.push_back(pm.read_avg_latency_us);
                detail.curve_write_lat_us.push_back(pm.write_avg_latency_us);
            }

            if (progress_cb) {
                progress_cb(pm);
            }
        }
    }

    // 等待所有工作线程退出
    for (auto& t : workers) {
        if (t.joinable()) t.join();
    }

    double total_elapsed = GetMonotonicTimeSec() - bench_start_time;
    if (total_elapsed <= 0.001) total_elapsed = 0.001;

    // 汇总结果
    std::vector<double> all_latencies;
    for (const auto& ws : worker_stats) {
        res.total_read_bytes += ws.read_bytes;
        res.total_write_bytes += ws.write_bytes;
        res.total_read_iops += ws.read_ops;
        res.total_write_iops += ws.write_ops;
        all_latencies.insert(all_latencies.end(), ws.latencies_us.begin(), ws.latencies_us.end());
    }

    res.total_read_mbps = (res.total_read_bytes / (1024.0 * 1024.0)) / total_elapsed;
    res.total_write_mbps = (res.total_write_bytes / (1024.0 * 1024.0)) / total_elapsed;
    res.total_read_iops = static_cast<uint64_t>(res.total_read_iops / total_elapsed);
    res.total_write_iops = static_cast<uint64_t>(res.total_write_iops / total_elapsed);

    uint64_t total_cpu_ops = 0, total_mem_bytes = 0;
    for (const auto& ws : worker_stats) {
        total_cpu_ops += ws.cpu_ops;
        total_mem_bytes += ws.mem_bytes;
    }
    double final_cpu_ops_sec = total_cpu_ops / total_elapsed;
    double final_mem_gbps = (total_mem_bytes / (1024.0 * 1024.0 * 1024.0)) / total_elapsed;
    res.final_cpu_metric = final_cpu_ops_sec / 1e6; // M-Ops/s
    res.final_mem_metric = final_mem_gbps;

    // 存储通道测试不出"综合跑分"：旧实现对 821/766/854 用 CPU/内存/IO 三项加权拼出来的
    // raw_score 是自造指标（既非上游口径也无物理含义），随旧内核一并删除。
    // 内置基准的分数在内核分派处由内核主指标换算，不在此处再算。
    res.final_spec_score = 0;

    if (!all_latencies.empty()) {
        std::sort(all_latencies.begin(), all_latencies.end());
        res.min_latency_us = all_latencies.front();
        res.max_latency_us = all_latencies.back();

        double sum = 0.0;
        for (double v : all_latencies) sum += v;
        res.avg_latency_us = sum / all_latencies.size();

        size_t p95_idx = static_cast<size_t>(all_latencies.size() * 0.95);
        size_t p99_idx = static_cast<size_t>(all_latencies.size() * 0.99);
        res.p95_latency_us = all_latencies[std::min(p95_idx, all_latencies.size() - 1)];
        res.p99_latency_us = all_latencies[std::min(p99_idx, all_latencies.size() - 1)];
    }

    // ================= 专业模式：汇总全量统计 =================
    if (spec.professional_stats) {
        // --- 延迟百分位（fio lat percentiles 口径）---
        if (!all_latencies.empty()) {
            auto pct = [&](double p) -> double {
                size_t idx = static_cast<size_t>(all_latencies.size() * p);
                if (idx >= all_latencies.size()) idx = all_latencies.size() - 1;
                return all_latencies[idx];
            };
            detail.p50_latency_us  = pct(0.50);
            detail.p95_latency_us  = pct(0.95);
            detail.p99_latency_us  = pct(0.99);
            detail.p999_latency_us = pct(0.999);
            detail.max_latency_us  = all_latencies.back();
        }

        // --- 控制器超时与 I/O 错误 ---
        for (const auto& ws : worker_stats) {
            detail.timeout_count  += ws.timeout_count;
            detail.io_error_count += ws.io_error_count;
        }

        // --- CPU 占用（仅 benchmark 工作线程，见 DetailedStats 中的口径说明）---
        {
            double sum = 0.0;
            for (double v : detail.curve_cpu_percent) sum += v;
            // 各核累加口径（可 >100%）：工作线程 CPU 时间 / 墙钟时间
            detail.cpu_util_percent = detail.curve_cpu_percent.empty()
                ? 0.0 : sum / detail.curve_cpu_percent.size();
            const uint32_t cores = OnlineCores();
            detail.cpu_core_count = cores;
            // 归一化到 0~100%，与系统监视器直觉一致
            detail.cpu_percent_of_cores = (cores > 0)
                ? std::min(100.0, detail.cpu_util_percent / static_cast<double>(cores))
                : 0.0;
            res.cpu_cores_used = detail.cpu_util_percent / 100.0;
        }

        // --- 抖动与掉速（基于高速采样曲线，剔除前 10% 预热）---
        // 曲线已按契约拆成读/写两列；"总吞吐"的稳定性仍按两者逐点相加评估，
        // 这里只在汇总时构造一份局部序列，不额外暴露合并口径字段。
        std::vector<double> bw;
        bw.reserve(detail.curve_read_bw_mbps.size());
        for (size_t i = 0; i < detail.curve_read_bw_mbps.size(); ++i) {
            const double w = (i < detail.curve_write_bw_mbps.size())
                ? detail.curve_write_bw_mbps[i] : 0.0;
            bw.push_back(detail.curve_read_bw_mbps[i] + w);
        }
        if (bw.size() > 5) {
            const size_t warm = bw.size() / 10;
            std::vector<double> s(bw.begin() + warm, bw.end());
            double mean = 0.0;
            for (double v : s) mean += v;
            mean /= std::max<size_t>(1, s.size());
            double var = 0.0;
            for (double v : s) var += (v - mean) * (v - mean);
            var /= std::max<size_t>(1, s.size());
            detail.steady_state_mbps = mean;
            detail.jitter_percent = (mean > 1e-9) ? (std::sqrt(var) / mean) * 100.0 : 0.0;

            for (size_t i = warm + 1; i < bw.size(); ++i) {
                if (bw[i - 1] <= 1e-9) continue;
                const double drop = (bw[i - 1] - bw[i]) / bw[i - 1] * 100.0;
                if (drop >= 30.0) {               // 相邻采样跌幅 ≥30% 记为一次掉速
                    detail.drop_events++;
                    if (drop > detail.worst_drop_percent) detail.worst_drop_percent = drop;
                }
            }
        }
        res.detail = detail;
    }

    // 按容量终止的实际完成量与标志
    uint64_t done_total = 0;
    for (const auto& ws : worker_stats) done_total += ws.read_bytes + ws.write_bytes;
    res.total_bytes_done = done_total;
    res.stopped_by_capacity = capacity_reached;

    uint64_t total_val_passed = 0;
    uint64_t total_val_errors = 0;
    for (const auto& ws : worker_stats) {
        total_val_passed += ws.validation_passed_blocks;
        total_val_errors += ws.validation_error_blocks;
    }
    if (res.total_read_bytes > 0 && (total_val_passed > 0 || total_val_errors > 0)) {
        res.verified = (total_val_errors == 0);
        res.work_units = total_val_passed;
        if (total_val_errors == 0) {
            res.verify_detail = "数据校验通过：已校验 " + std::to_string(total_val_passed) +
                                " 个 4KB 数据块，0 处错误（未发生静默损坏或读空）";
        } else {
            res.verify_detail = "⚠️ 数据校验异常：检测到 " + std::to_string(total_val_errors) +
                                " 处数据块不一致（读取数据非预期写入内容）";
        }
    } else {
        res.verified = (res.total_read_bytes == 0 && res.total_write_bytes > 0);
        res.work_units = 0;
    }
    res.spec_ratio = 0.0;
    res.reference_seconds = 0.0;
    res.ops_per_sec = res.final_cpu_metric;
    const int io_mode = io_mode_seen.load();
    res.io_method = IoModeName(io_mode);
    res.direct_io_ok = (io_mode == kIoDirect);

    // 一致性兜底：披露文本不得与实际通道相悖。
    // 例如声明了 O_DIRECT，但因文件系统不支持而回退到 O_SYNC / 缓冲 I/O 时，
    // 必须改写披露，否则用户看到的是自相矛盾的结论。
    // 同理：含缓存模式本就走缓冲 I/O，任何"依赖 O_DIRECT"的措辞都不得与其并存。
    if (io_mode != kIoDirect &&
        res.privilege_method.find("O_DIRECT") != std::string::npos) {
        res.privilege_method =
            "常规手段（无 root）：O_DIRECT 不可用，已回退为 " +
            std::string(io_mode == kIoSync ? "O_SYNC" : "缓冲 I/O") +
            "，页缓存可能参与测量";
    }
    // 无缓存参与模式若最终未走 O_DIRECT，必须判失败，避免把缓存成绩当闪存成绩
    if (spec.cache_mode == 1 && !res.direct_io_ok) {
        res.success = false;
        res.error_message = "无缓存参与模式要求 O_DIRECT，实际以 " + res.io_method + " 运行，结果不可信";
        is_running_.store(false);
        append_run_env();
        return res;
    }
    res.success = true;
    is_running_.store(false);
    append_run_env();
    return res;
}

} // namespace dynamo