/*
 * builtin_bench.cpp —— 把 AndroidBenech 裁剪出的上游基准内核接进 App 的内置基准接口。
 *
 * 设计口径（与需求方确认过的三点）：
 *  1. **真算法、真上游**：内核全部来自 AndroidBenech/upstream 的官方源码（CoreMark 算法文件
 *     逐字节未改），不再使用"仿真/模拟"实现；每个内核都带上游自带的正确性校验（Oracle），
 *     校验不通过的结果一律不算有效成绩。
 *  2. **强度挡位独立**：每个基准按自身算法复杂度给三档参数（低性能 / 常规 / 拓展），
 *     不搞"一个全局缩放系数管所有基准"。
 *  3. **来源可追溯**：每个内核在 UI 侧的名字、介绍与上游来源一一对应（见 PresetSuites.kt
 *     与本文件的 kMeta 表），报告里给出的读数都注明上游项目与提交号。
 *
 * 与 AndroidBenech 的关系：那边是独立套件（自有 scale policy / guard / UI），这里只取
 * 「内核 + 契约」两层做裁剪移植，规模策略改为本 App 的三档强度挡位。
 */
#include "builtin_kernels.h"

#include "bench_api.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>

namespace dynamo {
namespace builtin {
namespace {

// ---------------------------------------------------------------------------
//  内核登记表：id 刻意避开 SPEC 编号空间（821/750/766/854 那些是本项目早期的自研实现，
//  已按需求「更换」掉），避免任何人把读数误读成 SPEC 套件成绩。
// ---------------------------------------------------------------------------
struct KernelMeta {
    uint32_t    id;
    const char *key;
    const char *name;
    const char *unit;
    const char *source;      // 上游项目 + 提交号 + 许可
};

const KernelMeta kMeta[] = {
    { 1001, "coremark",     "CoreMark 1.0",   "iterations/s", "EEMBC coremark @1f483d5 (Apache-2.0)" },
    { 1002, "stream",       "STREAM",         "MB/s",         "jeffhammond/STREAM @6703f75 (STREAM License)" },
    { 1003, "tinymembench", "tinymembench",   "MB/s",         "ssvb/tinymembench @a2cf6d7 (MIT)" },
};
constexpr size_t kMetaCount = sizeof(kMeta) / sizeof(kMeta[0]);

const bench_kernel *findKernel(const char *id)
{
    size_t n = 0;
    const bench_kernel *all = bench_registry(&n);
    for (size_t i = 0; i < n; i++) {
        if (std::strcmp(all[i].id, id) == 0) return &all[i];
    }
    return nullptr;
}

// ---------------------------------------------------------------------------
//  强度挡位：每一档给出**该基准自己的**参数，注释里写明为什么这么定。
//  tier: 1 = 低性能（快速自检，明确不构成标准成绩）
//        2 = 常规（上游标准/官方口径）
//        3 = 拓展（更长工作集 / 全核，压热与压带宽）
// ---------------------------------------------------------------------------
struct TierPlan {
    uint32_t    threads;
    uint64_t    iterations;     // 语义随内核：CoreMark=迭代次数(0=官方自动标定)，STREAM=NTIMES
    uint64_t    data_bytes;     // 工作集；0 = 由内核按 scale_level 决定
    const char *label;
    const char *detail;
};

TierPlan planFor(const char *key, uint32_t tier, uint32_t cfg_threads)
{
    const uint32_t t = (tier < 1) ? 1 : (tier > 3 ? 3 : tier);
    const uint32_t cores = (cfg_threads > 0) ? cfg_threads : 1;

    if (std::strcmp(key, "coremark") == 0) {
        // CoreMark 官方 run rules：单次计分运行必须 ≥ 10 s。
        // 低性能档故意只跑 2000 次迭代（约 0.02 s），只能当功能自检 —— 结果会被标注
        // "不构成标准成绩"；常规档交给上游自动标定到 ≥10 s；拓展档再叠全核并列。
        if (t == 1) return { 1,    2000, 0, "低性能", "2000 次迭代（快速自检，不构成标准成绩）" };
        if (t == 2) return { 1,    0,    0, "常规",   "官方自动标定 ≥10 s（单线程标准口径）" };
        return        { cores, 0,    0, "拓展",   "官方自动标定 ≥10 s × 全核并列（总分口径）" };
    }
    if (std::strcmp(key, "stream") == 0) {
        // STREAM 的强度就是工作集：必须击穿 LLC 才叫持续带宽（规范红线 <64MB 只能算缓存驻留）。
        const uint64_t mib = 1024ull * 1024ull;
        if (t == 1) return { 1, 10,  64 * mib,  "低性能", "工作集 64 MiB · NTIMES=10（贴近缓存边界）" };
        if (t == 2) return { 1, 10, 320 * mib,  "常规",   "工作集 320 MiB · NTIMES=10（上游标准规模）" };
        return        { 1, 20, 640 * mib,       "拓展",   "工作集 640 MiB · NTIMES=20（深度压带宽）" };
    }
    if (std::strcmp(key, "tinymembench") == 0) {
        // 延迟阶梯要覆盖 L1 → DRAM：工作集越大档位越多，拓展档还给更长扫描。
        const uint64_t mib = 1024ull * 1024ull;
        if (t == 1) return { 1, 1, 32 * mib,  "低性能", "扫描到 32 MiB（覆盖 L1/L2，DRAM 仅浅测）" };
        if (t == 2) return { 1, 3, 64 * mib,  "常规",   "扫描到 64 MiB（覆盖到 DRAM，重复 3 次）" };
        return        { 1, 5, 256 * mib,      "拓展",   "扫描到 256 MiB（深度扫描，重复 5 次）" };
    }
    return { 1, 0, 0, "常规", "" };
}

// ---------------------------------------------------------------------------
//  自定标定基线（**不是**上游官方参考时间/分数）
//  取本机（Redmi Note 12 Turbo / SM7475）baseline 档 -O3 实测值，用来给出"相对本机基线"的
//  比值：= 实测 / 基线，约 1.0 表示与本机基线持平。跨设备不可比，UI 必须同屏展示
//  BASELINE_RATIO_DISCLAIMER。要换基线只需改这三个常量，并在介绍里同步说明。
// ---------------------------------------------------------------------------
constexpr double kRefCoreMarkIterPerSec = 120000.0;   // 实测 122,156 iterations/s（单线程，官方标定）
constexpr double kRefStreamTriadMbps    = 34000.0;    // 实测 34,052 MB/s（Triad，64 MiB 档）
constexpr double kRefTinyReadMbps       = 31900.0;    // 实测 31,911 MB/s（顺序读最大档）

/** 取消标志桥：内核轮询的是 const volatile int*，而 App 侧给的是 std::atomic<bool>* */
class CancelFlag {
public:
    explicit CancelFlag(std::atomic<bool> *stop) : stop_(stop)
    {
        if (stop_ != nullptr) {
            watcher_ = std::thread([this] {
                while (!finished_.load()) {
                    if (stop_->load()) flag_ = 1;
                    std::this_thread::sleep_for(std::chrono::milliseconds(5));
                }
            });
        }
    }
    ~CancelFlag()
    {
        finished_.store(true);
        if (watcher_.joinable()) watcher_.join();
    }
    const volatile int *ptr() const { return &flag_; }

private:
    std::atomic<bool> *stop_ = nullptr;
    std::atomic<bool>  finished_{false};
    std::thread        watcher_;
    volatile int       flag_ = 0;
};

struct LiveCtx {
    const LiveCallback *cb;
    uint64_t            started_ms;
    uint32_t            total_seconds;
};

void on_progress(void *ctx, double fraction, const char *stage)
{
    auto *lc = static_cast<LiveCtx *>(ctx);
    if (lc == nullptr || lc->cb == nullptr || !(*lc->cb)) return;
    LiveStats st;
    st.elapsed_seconds  = (bench_now_ms() - lc->started_ms) / 1000.0;
    st.progress_percent = static_cast<uint32_t>(fraction < 0 ? 0 : (fraction > 1 ? 100 : fraction * 100.0));
    st.phase            = (stage != nullptr) ? stage : "";
    // 运行中的实时吞吐：各内核只在结束时给出确定读数，这里不编造中间值（宁缺勿假）
    st.ops_per_sec      = 0.0;
    st.mem_gbps         = 0.0;
    st.projected_ratio  = 0.0;
    (*lc->cb)(st);
}

} // namespace

bool IsBenchKernelId(uint32_t id)
{
    for (size_t i = 0; i < kMetaCount; i++) {
        if (kMeta[i].id == id) return true;
    }
    return false;
}

const char *BenchKernelSource(uint32_t id)
{
    for (size_t i = 0; i < kMetaCount; i++) {
        if (kMeta[i].id == id) return kMeta[i].source;
    }
    return "unknown";
}

RunResult RunBenchKernel(uint32_t bench_id, const RunConfig &cfg, const LiveCallback &live)
{
    RunResult out;
    const KernelMeta *meta = nullptr;
    for (size_t i = 0; i < kMetaCount; i++) {
        if (kMeta[i].id == bench_id) { meta = &kMeta[i]; break; }
    }
    if (meta == nullptr) {
        out.error = "未知的内置基准编号";
        return out;
    }
    const bench_kernel *k = findKernel(meta->key);
    if (k == nullptr) {
        // 该内核未编入本次构建（BENCH_KERNELS 裁剪）——如实报错，不假装跑过
        out.error = std::string("本次构建未编入内核：") + meta->key;
        return out;
    }

    const TierPlan plan = planFor(meta->key, cfg.scale, cfg.threads);

    CancelFlag cancel(cfg.stop);
    LiveCtx lc{ &live, bench_now_ms(), 0 };

    bench_params p;
    std::memset(&p, 0, sizeof p);
    p.threads        = plan.threads;
    p.iterations     = plan.iterations;
    p.scale_level    = (cfg.scale >= 1 && cfg.scale <= 3) ? (cfg.scale - 1) : 1;  // bench_api: 0=Smoke 1=Small 2=Standard
    p.data_bytes     = plan.data_bytes;
    p.max_runtime_ms = 0;
    p.cancel_flag    = cancel.ptr();
    p.progress_cb    = on_progress;
    p.progress_ctx   = &lc;

    // 内存预算：交给 App 侧探测到的可用内存（留 25% 余量给系统，避免被 LMK 强杀）
    if (cfg.avail_mem_bytes > 0) {
        bench_set_memory_budget(static_cast<uint64_t>(cfg.avail_mem_bytes * 3 / 4));
    }

    bench_result r;
    std::memset(&r, 0, sizeof r);
    const bench_status st = k->run(&p, &r);

    out.seconds = (r.elapsed_ms > 0) ? (r.elapsed_ms / 1000.0) : 0.0;
    out.work_units = static_cast<uint64_t>(r.secondary_count > 0 ? r.secondary[0] : 0);
    out.verified = (r.verify == BENCH_VERIFY_PASS);
    out.verify_detail = r.verify_detail;

    double ratio = 0.0;
    if (std::strcmp(meta->key, "coremark") == 0) {
        out.ops_per_sec = r.primary_metric;
        out.mem_gbps = 0.0;
        ratio = (kRefCoreMarkIterPerSec > 0) ? r.primary_metric / kRefCoreMarkIterPerSec : 0.0;
    } else if (std::strcmp(meta->key, "stream") == 0) {
        out.ops_per_sec = 0.0;
        out.mem_gbps = r.primary_metric / 1000.0;                 // MB/s → GB/s
        ratio = (kRefStreamTriadMbps > 0) ? r.primary_metric / kRefStreamTriadMbps : 0.0;
    } else {
        out.ops_per_sec = 0.0;
        out.mem_gbps = r.primary_metric / 1000.0;
        ratio = (kRefTinyReadMbps > 0) ? r.primary_metric / kRefTinyReadMbps : 0.0;
    }
    out.ratio = ratio;
    // 参考时间：使 ratio = reference/measured 成立的等价参考时间（口径与旧实现保持一致）
    out.reference_seconds = (ratio > 1e-9) ? (out.seconds / ratio) : out.seconds;

    // 输入规模 + 挡位 + 上游来源：全部写进 verify_detail，报告可原样还原"到底跑了什么"。
    char meta_line[256];
    std::snprintf(meta_line, sizeof meta_line, "｜%s 档：%s｜上游：%s",
                  plan.label, plan.detail, meta->source);
    out.verify_detail += meta_line;

    if (st == BENCH_OK) {
        out.success = true;
    } else {
        out.success = false;
        switch (st) {
            case BENCH_ERR_ALLOC:       out.error = "内存预检未通过：预算不足以安全运行该挡位（未执行，不产生成绩）"; break;
            case BENCH_ERR_CANCELLED:   out.error = "已取消"; break;
            case BENCH_ERR_TIMEOUT:     out.error = "超出墙钟上限（半程结果不构成有效成绩）"; break;
            case BENCH_ERR_INVALID_ARG: out.error = "参数非法"; break;
            case BENCH_ERR_UNSUPPORTED: out.error = "本设备不支持该内核"; break;
            default:                    out.error = "内核执行失败"; break;
        }
    }
    // 正确性校验未通过：无论内核自评如何，都不作为有效成绩
    if (out.success && !out.verified) {
        out.success = false;
        out.error = "输出正确性校验未通过，结果不计入有效成绩";
    }
    return out;
}

} // namespace builtin
} // namespace dynamo

