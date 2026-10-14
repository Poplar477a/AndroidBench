#ifndef SPEC_WORKLOADS_H
#define SPEC_WORKLOADS_H

#include <string>
#include <cstdint>
#include <atomic>
#include <functional>

// 内置基准内核接口（2026-10 起）
//
// 内核裁剪自上游官方源码：CoreMark（EEMBC）/ STREAM（McCalpin）/ tinymembench（ssvb），
// 含上游自带的正确性校验；测量口径为纯 CPU + 内存层次，不含存储 I/O。
// 旧的自研实现（821 编译器模拟 / 766 流体 / 750 NTT / 854 图分析）已整体删除。

namespace dynamo {
namespace builtin {

// 强度挡位（每个基准用自己的参数定义，见 bench/builtin_bench.cpp 的 planFor）：
//   1 = 低性能（快速自检，明确不构成标准成绩）
//   2 = 常规  （上游标准/官方口径）
//   3 = 拓展  （更长工作集 / 全核，压热与压带宽）
enum SpecTierId {
    TIER_TEST = 1,
    TIER_TRAIN = 2,
    TIER_REFSPEED = 3,
};

struct RunConfig {
    uint32_t threads = 1;                  // 并发任务数（对齐 单任务口径 多任务/OpenMP）
    uint32_t scale = 1;                    // 档位：1=test, 2=train, 3=参考档（参考档 或 参考档）
    uint64_t avail_mem_bytes = 0;          // 设备可用内存（0 = 跳过预检）
    std::atomic<bool>* stop = nullptr;     // 外部中断
};

struct LiveStats {
    double elapsed_seconds = 0.0;
    double ops_per_sec = 0.0;              // 算力指标 (M-Ops/s)
    double mem_gbps = 0.0;                 // 内存层次吞吐 (GB/s)
    double projected_ratio = 0.0;          // 实时预测的 SPEC 比值
    uint32_t progress_percent = 0;
    std::string phase;
};

struct RunResult {
    bool success = false;
    bool verified = false;                 // 输出正确性校验是否通过
    std::string verify_detail;             // 校验细节（用于结果披露）
    std::string error;
    double seconds = 0.0;
    double reference_seconds = 1.0;        // 参考机基准时间（归一化用）
    double ratio = 0.0;                    // reference / measured（单任务口径 口径）
    uint64_t work_units = 0;               // 算法相关的工作量计数
    double ops_per_sec = 0.0;
    double mem_gbps = 0.0;
};

using LiveCallback = std::function<void(const LiveStats&)>;

// ============================================================================
//  内置基准（2026-10 更换）：内核全部来自 AndroidBenech/upstream 的官方源码，
//  经裁剪后接入（详见 bench/builtin_bench.cpp 与 PresetSuites 的介绍/来源字段）。
//    CoreMark 1.0        eembc/coremark @1f483d5（Apache-2.0）—— 整数与控制流
//    STREAM              jeffhammond/STREAM @6703f75        —— 持续内存带宽
//    tinymembench        ssvb/tinymembench @a2cf6d7（MIT）  —— 缓存/内存延迟与带宽扫描
//  id 空间与旧的自研实现（821/750/766/854）刻意分开，避免被误读成 SPEC 套件成绩。
// ============================================================================
bool        IsBenchKernelId(uint32_t id);
const char* BenchKernelSource(uint32_t id);
RunResult   RunBenchKernel(uint32_t bench_id, const RunConfig& cfg, const LiveCallback& live);

// 内核实现见 bench/builtin_bench.cpp

} // namespace builtin
} // namespace dynamo

#endif // SPEC_WORKLOADS_H