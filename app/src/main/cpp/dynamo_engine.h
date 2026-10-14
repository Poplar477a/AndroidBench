#ifndef DYNAMO_ENGINE_H
#define DYNAMO_ENGINE_H

#include <string>
#include <vector>
#include <atomic>
#include <cstdint>
#include <functional>

namespace dynamo {

struct AccessSpecification {
    uint32_t request_size_bytes = 4096;      // 4K, 128K, 1M, 2M...
    uint32_t percent_read = 100;             // 0~100 (100 = 纯读, 0 = 纯写, 70 = 混合读)
    uint32_t percent_random = 100;           // 0~100 (100 = 随机, 0 = 顺序)
    uint32_t queue_depth = 1;                // 1, 8, 16, 32...
    uint32_t worker_threads = 1;             // 1, 4, 16, 32...
    uint64_t file_size_bytes = 1024ULL * 1024ULL * 1024ULL; // 测试文件大小 (默认 1GB)
    uint32_t duration_seconds = 10;          // 测试持续时间
    uint32_t ramp_up_seconds = 1;            // 预热时间
    uint32_t spec_workload_id = 0;           // 0: 无, 821: gcc_s, 766: femflow_r, 854: graph500_s
    bool full_version = false;               // true = 移植版算法（含输出校验）
    uint32_t spec_scale = 1;                 // 内置档位：1=test 2=train 3=参考档/参考档
    uint64_t avail_mem_bytes = 0;            // 设备可用内存（用于 PC 级档位内存预检）
    bool force_run = false;                  // 跳过内存预检，强制运行 PC 级档位
    // ---- 测试终止准则：按时间 或 按容量（二选一，不同时生效） ----
    uint32_t stop_criterion = 0;             // 0=按时间, 1=按容量
    uint64_t stop_after_bytes = 0;           // 按容量时的目标总字节数（读写合计）
    // 独立的安全上限，避免目标不可达时跑飞。
    // 必须与 duration_seconds 分开：此前复用 duration_seconds 当兜底，
    // 结果按容量测试被上一次设的时长（例如 5 秒）直接掐断。
    // 0 = 不设上限。
    uint32_t max_duration_seconds = 0;
    // ---- 统计档位 ----
    bool professional_stats = false;         // true=专业模式：输出全量 FIO 口径指标与性能曲线
    // ---- 采样档位（用户可选）----
    // 允许值：5 / 10 / 25 / 50 / 75 / 100 (ms)，由上层选择器决定。
    // 专业模式与常规模式都按该间隔采样：未开启专业模式时不记录曲线，但进度回调仍按该间隔。
    // 原生侧不对档位做合法性拒绝，只做兜底钳制（见 dynamo_engine.cpp），避免上层漏改时跑飞。
    uint32_t sample_interval_ms = 100;
    // ---- 存储测试：缓存模式与测试手段 ----
    uint32_t cache_mode = 0;                 // 0=自动 1=无缓存参与(O_DIRECT强约束) 2=含缓存(缓冲IO)
    uint32_t privilege = 0;                  // 0=自动 1=常规(无root) 2=Root专用
    bool has_root = false;                   // 上层探测到的实际 root 状态
};

struct ProgressMetric {
    double current_read_mbps;
    double current_write_mbps;
    uint64_t current_read_iops;
    uint64_t current_write_iops;
    double avg_latency_us;
    double max_latency_us;
    double p99_latency_us;
    uint32_t elapsed_seconds;
    uint32_t total_seconds;
    // 容量模式的进度：按容量终止时 UI 用它画进度条，而不是拿时间当分母
    uint64_t bytes_done;                     // 本次已完成的读写总字节
    uint64_t target_bytes;                   // 目标总字节（按时间模式为 0）
    uint64_t live_spec_score;                // 实时 SPEC 综合跑分
    double live_cpu_metric;                  // 实时 CPU 算力指标 (M-Ops/s)
    double live_mem_metric;                  // 实时 内存 吞吐指标 (GB/s)
    double live_spec_ratio;                  // 完整版：实时预测的 SPEC 比值
    // 本采样区间内的平均响应延迟（分读/写分别统计，单位微秒）。
    // 由 worker 的单调递增计数器（延迟总和 / 完成次数）在监控线程取增量求得，
    // 因此不会随测试时长累积成"全程平均"，曲线才能反映掉速与长尾。
    // 该区间内无对应方向的 I/O 时为 0。
    double read_avg_latency_us;
    double write_avg_latency_us;
};

// ============================================================================
//  专业模式的全量统计（对齐 fio 的 lat percentiles / clat 分布 与运行期指标）
// ============================================================================
struct DetailedStats {
    bool present = false;

    // 延迟百分位（微秒）—— fio 的 lat (usec) percentiles 口径
    double p50_latency_us = 0.0;
    double p95_latency_us = 0.0;
    double p99_latency_us = 0.0;
    double p999_latency_us = 0.0;
    double max_latency_us = 0.0;

    // 运行期资源与错误
    // CPU 口径（本轮修正，需求方已确认选「仅测试负载」）：
    //   只累加 benchmark **工作线程**自身的 CPU 时间（CLOCK_THREAD_CPUTIME_ID），
    //   不含 UI 线程与 Compose 渲染，也不含监控/汇总线程。
    //   cpu_util_percent      = 工作线程 CPU 时间 / 墙钟时间 × 100（各核累加口径，多线程可 >100%）
    //   cpu_percent_of_cores  = 上式再除以设备核数，0~100%，与系统监视器直觉一致
    double cpu_util_percent = 0.0;
    double cpu_percent_of_cores = 0.0;
    uint32_t cpu_core_count = 0;             // 归一化用到的设备核数（如实记录，便于复核）
    uint64_t timeout_count = 0;              // 单次 I/O 延迟超过阈值的次数（控制器超时征兆）
    uint64_t io_error_count = 0;             // 系统调用返回错误的次数
    double timeout_threshold_us = 0.0;

    // 高速采样：性能曲线 / 抖动 / 掉速
    double sample_interval_ms = 100.0;
    double jitter_percent = 0.0;             // 采样吞吐的变异系数（标准差/均值）
    uint64_t drop_events = 0;                // 相邻采样吞吐跌幅超阈值的事件数
    double worst_drop_percent = 0.0;         // 最严重的一次跌幅
    double steady_state_mbps = 0.0;          // 剔除前 10% 预热后的稳态吞吐
    // 曲线一律分读、分写：吞吐与延迟各自独立成列，UI 才能分别画散点并给出各自的
    // 最小/最大/平均值；合并口径（curve_bw_mbps / curve_lat_us）已按契约删除。
    std::vector<double> curve_read_bw_mbps;  // 每采样区间的读吞吐 MB/s
    std::vector<double> curve_write_bw_mbps; // 每采样区间的写吞吐 MB/s
    std::vector<double> curve_read_lat_us;   // 每采样区间读请求的平均响应延迟
    std::vector<double> curve_write_lat_us;  // 每采样区间写请求的平均响应延迟
    // 逐采样「工作线程 CPU 占用」曲线；单位与 cpu_util_percent 一致（各核累加，可 >100%）
    std::vector<double> curve_cpu_percent;
    std::vector<double> curve_time_s;        // 采样时间轴
};

struct BenchmarkResult {
    bool success;
    std::string error_message;
    double total_read_bytes;
    double total_write_bytes;
    double total_read_mbps;
    double total_write_mbps;
    uint64_t total_read_iops;
    uint64_t total_write_iops;
    double min_latency_us;
    double avg_latency_us;
    double max_latency_us;
    double p95_latency_us;
    double p99_latency_us;
    uint64_t final_spec_score;               // 最终 SPEC 综合总分（仿真版加权分）
    double final_cpu_metric;                 // 最终 CPU 指标 (M-Ops/s)
    double final_mem_metric;                 // 最终 内存 指标 (GB/s)
    // ---- 完整版专有 ----
    bool verified;                           // 输出正确性校验是否通过
    std::string verify_detail;               // 校验细节（结果披露）
    double spec_ratio;                       // 单任务口径 口径比值：参考时间 / 实测时间
    double reference_seconds;                // 参考机基准时间
    uint64_t work_units;                     // 算法工作量计数
    double ops_per_sec;                      // 算力 (M-Ops/s)
    // ---- 存储测试的手段披露 ----
    std::string io_method;                   // 实际生效的 I/O 手段（O_DIRECT / O_SYNC 回退 / 缓冲）
    std::string privilege_method;            // 实际生效的测试手段（专业 / 常规）
    bool direct_io_ok;                       // O_DIRECT 是否真正生效
    // ---- 按容量终止时的实际完成量 ----
    uint64_t total_bytes_done = 0;
    bool stopped_by_capacity = false;
    // ---- 测试负载 CPU（各核累加口径，仅 benchmark 工作线程）----
    // 供上层 logcat 复核与后续展示；SPEC 路径当前未计量（见 native_bridge 中的说明）。
    double cpu_cores_used = 0.0;
    // ---- 编译工具链（编译期宏生成，见 BuildCompilerToolchainInfo）----
    // 官方 run rules 要求上报编译器与 base/peak 调优等级；本移植版是单一固定优化等级，
    // 与官方规则不同，必须如实披露。放在结果顶层：SPEC 完整版路径不填充 detail，放 detail 会漏。
    std::string compiler_toolchain;
    // ---- 本 .so 的 -mcpu 档位（编译期宏，见 EngineProfileId / EngineMcpuName）----
    // 由原生自述，避免 Kotlin 侧猜；降级是否发生由加载器（Kotlin）另行披露。
    std::string engine_profile;              // baseline / x1 / x2 / x3 / x4 / x925
    std::string engine_mcpu;                 // 该档对应的 -mcpu（baseline = generic）
    // ---- 专业模式全量统计 ----
    DetailedStats detail;
};

// ============================================================================
//  引擎库自身的 -mcpu 档位自述（**编译期宏生成**）
//  每个变体在 CMake 里被写入 DYNAMO_PROFILE_ID / DYNAMO_PROFILE_MCPU，
//  Kotlin 侧只搬运、不硬编码；这样"实际加载的是哪一档"永远可被权威核实。
// ============================================================================
const char* EngineProfileId();
// 该档的 **-mtune** 目标（宏名沿用 MCPU 以兼容既有字段；语义是调度调优目标，不是 -mcpu）
const char* EngineMcpuName();
// 逐档目录串：形如 "baseline=generic,…,c1ultra=c1-ultra,"（逗号分隔）。
// 由 CMake/脚本在配置期从**同一份 profiles.cmake** 生成，UI 展示逐档元数据时直接用它，杜绝手工表漂移。
const char* EngineProfileCatalog();
// 工具链来源（编译期宏）：如 "NDK 30.0.16248370 内置 Clang"
// 或 "独立 LLVM 23.1.3（非 NDK 内置，实验性）"。用户必须能一眼区分两种编译器。
const char* EngineCcOrigin();
// 本变体是否为实验性档位（由独立 LLVM 23 构建，非 NDK 内置工具链）
bool EngineProfileIsExperimental();
// 实验性档位名列表（逗号分隔），供 UI 标注；与 EngineProfileCatalog 同源。
const char* EngineExperimentalProfiles();
// 完整编译串（与结果里 compilerToolchain 同一份实现）：
// 供 UI 在**不跑测试**的前提下直接展示，避免"新装应用还没跑过测试就显示不出编译环境"。
std::string CompilerToolchainInfo();

using MetricCallback = std::function<void(const ProgressMetric&)>;

class DynamoEngine {
public:
    DynamoEngine();
    ~DynamoEngine();

    bool PrepareTargetFile(const std::string& file_path, uint64_t size_bytes, uint32_t cache_mode, std::string& err);
    bool PrepareTargetFile(const std::string& file_path, uint64_t size_bytes, std::string& err) {
        return PrepareTargetFile(file_path, size_bytes, 0, err);
    }

    BenchmarkResult RunBenchmark(
        const std::string& file_path,
        const AccessSpecification& spec,
        MetricCallback progress_cb
    );

    void Stop();

private:
    std::atomic<bool> is_running_{false};
    std::atomic<bool> stop_requested_{false};
};

} // namespace dynamo

#endif // DYNAMO_ENGINE_H