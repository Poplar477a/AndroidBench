#pragma once
// ============================================================================
//  运行期资源提升（RAII，覆盖**整个**压测周期）+ 大核亲和性绑定
//
//  背景（另一方源码审查 + 本机实测）：
//   1) 旧实现把 setpriority(-10) / I/O 调度类 RT 在"清缓存"处就地恢复，**早于 worker 启动**，
//      真正跑压测的线程全程是默认优先级 → 现由 RAII 持有到压测结束（析构点在 run 函数返回处）。
//   2) 完全没有亲和性绑定 → Android EAS 会把计算线程迁到低频核（本机 prime 4.61 GHz vs 3.63 GHz），
//      跨簇迁移还带来缓存颠簸。实测修复前不锁频曾出现 116 vs 287 M-Ops/s（2.4×）的塌陷。
//
//  绑核策略（按线程数自适应，避免多线程过度集中）：
//   · prime 掩码 = 最高频 90% 以上的核（本机 cpu6-7 / 4.61 GHz）
//   · big   掩码 = 去掉小核后的性能核集合（最高频 60% 以上；本机为全部 8 核）
//   · 线程数 ≤ prime 核数 → 绑 prime；否则 → 绑 big
//  该动作会改变测量条件，因此**必须如实披露**（写入 res.privilege_method 与 logcat）；
//  联调开关 debug.ufsbench.nopin=1 可关闭绑核，用于 pin on/off 对照。
//  sched_setaffinity 作用于自身线程无需 root。
// ============================================================================
#include <sched.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <errno.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <dirent.h>
#include <algorithm>
#include <android/log.h>
#include <sys/system_properties.h>

#define RUNBOOST_LOG(...) __android_log_print(ANDROID_LOG_INFO, "UFSBench", __VA_ARGS__)

namespace dynamo {

inline int BoostIoprioGet() {
#ifdef SYS_ioprio_get
    return static_cast<int>(syscall(SYS_ioprio_get, 1, 0));
#else
    return -1;
#endif
}
inline int BoostIoprioSet(int v) {
#ifdef SYS_ioprio_set
    return static_cast<int>(syscall(SYS_ioprio_set, 1, 0, v));
#else
    return -1;
#endif
}
inline int BoostIoprioRt() { return (2 << 13) | 0; }

struct BigCoreMask {
    cpu_set_t mask{};
    int count = 0;
    std::string detail;
    bool valid() const { return count > 0; }
};

struct BigCoreMasks {
    BigCoreMask prime;
    BigCoreMask big;
};

inline long ReadCpuMaxFreq(int cpu) {
    char path[128];
    snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    FILE* f = fopen(path, "r");
    if (!f) return -1;
    long v = -1;
    if (fscanf(f, "%ld", &v) != 1) v = -1;
    fclose(f);
    return v;
}

inline BigCoreMasks BuildBigCoreMasks() {
    BigCoreMasks out;
    std::vector<std::pair<long, int>> freq;
    long maxf = 0;
    for (int c = 0; c < CPU_SETSIZE; ++c) {
        long v = ReadCpuMaxFreq(c);
        if (v > 0) { freq.emplace_back(v, c); if (v > maxf) maxf = v; }
    }
    if (freq.empty() || maxf <= 0) return out;
    const long thr_prime = static_cast<long>(maxf * 0.9);
    const long thr_big   = static_cast<long>(maxf * 0.6);
    int plo = -1, phi = -1, blo = -1, bhi = -1;
    for (auto& p : freq) {
        if (p.first >= thr_prime) {
            CPU_SET(p.second, &out.prime.mask); ++out.prime.count;
            if (plo < 0 || p.second < plo) plo = p.second;
            if (p.second > phi) phi = p.second;
        }
        if (p.first >= thr_big) {
            CPU_SET(p.second, &out.big.mask); ++out.big.count;
            if (blo < 0 || p.second < blo) blo = p.second;
            if (p.second > bhi) bhi = p.second;
        }
    }
    char buf[160];
    snprintf(buf, sizeof(buf), "cpu%d-%d（最高频 %.2f GHz 档，共 %d 核）", plo, phi, maxf / 1e6, out.prime.count);
    out.prime.detail = buf;
    snprintf(buf, sizeof(buf), "cpu%d-%d（性能核，共 %d 核）", blo, bhi, out.big.count);
    out.big.detail = buf;
    RUNBOOST_LOG("run_boost: prime=%s(count=%d) big=%s(count=%d)",
                 out.prime.detail.c_str(), out.prime.count, out.big.detail.c_str(), out.big.count);
    return out;
}

inline const BigCoreMask& PickMask(const BigCoreMasks& m, uint32_t threads) {
    if (m.prime.valid() && threads <= static_cast<uint32_t>(m.prime.count)) return m.prime;
    if (m.big.valid()) return m.big;
    return m.prime;
}

inline bool DebugNoPin() {
    char buf[PROP_VALUE_MAX] = {0};
    __system_property_get("debug.ufsbench.nopin", buf);
    return buf[0] == '1';
}

inline bool PinCurrentThreadToBigCores(const BigCoreMask& m, std::string* note) {
    if (!m.valid()) {
        if (note) *note += "大核绑定：跳过（未能读取各核最高频）";
        RUNBOOST_LOG("run_boost: pin skipped (mask invalid)");
        return false;
    }
    const int rc = sched_setaffinity(0, sizeof(cpu_set_t), &m.mask);
    RUNBOOST_LOG("run_boost: sched_setaffinity rc=%d errno=%d mask=%s", rc, errno, m.detail.c_str());
    if (rc != 0) {
        if (note) *note += std::string("大核绑定：失败（") + strerror(errno) + "）";
        return false;
    }
    if (note) *note += "大核绑定：已绑定到 " + m.detail;
    return true;
}

// 多线程：把第 idx 个 worker 绑到掩码内**不同的**核上（轮转），避免所有 worker 挤在同一核。
// 依据需求「多线程读写时应该分开调动多核」：读/写 worker 各占其核，减少 L1/L2 争用与迁移。
inline bool PinWorkerToDistinctCore(const BigCoreMask& m, uint32_t idx, std::string* note) {
    if (!m.valid()) return false;
    int cpus[CPU_SETSIZE]; int n = 0;
    for (int c = 0; c < CPU_SETSIZE; ++c) if (CPU_ISSET(c, &m.mask)) cpus[n++] = c;
    if (n == 0) return false;
    cpu_set_t one; CPU_ZERO(&one); CPU_SET(cpus[idx % (uint32_t)n], &one);
    const int rc = sched_setaffinity(0, sizeof(cpu_set_t), &one);
    RUNBOOST_LOG("run_boost: worker idx=%u -> cpu%d rc=%d errno=%d", idx, cpus[idx % (uint32_t)n], rc, errno);
    if (rc != 0) { if (note) *note += std::string("worker 绑核失败（") + strerror(errno) + "）"; return false; }
    return true;
}

class RunScopeBoost {
public:
    RunScopeBoost(bool enable, const BigCoreMasks& masks, uint32_t threads,
                  bool pin_affinity, std::string* note)
        : enabled_(enable), orig_prio_(0), orig_ioprio_(-1) {
        if (pin_affinity) {
            if (DebugNoPin()) {
                if (note) *note += "大核绑定：已由 debug.ufsbench.nopin 关闭";
                RUNBOOST_LOG("run_boost: pin disabled by debug property");
            } else {
                PinCurrentThreadToBigCores(PickMask(masks, threads), note);
            }
        }
        if (!enable) {
            if (note) *note += "；运行期优先级提升：未启用（常规模式）";
            RUNBOOST_LOG("run_boost: enable=0 (normal mode)");
            return;
        }
        orig_prio_ = getpriority(PRIO_PROCESS, 0);
        orig_ioprio_ = BoostIoprioGet();
        prio_ok_ = (setpriority(PRIO_PROCESS, 0, -10) == 0);
        ioprio_ok_ = (BoostIoprioSet(BoostIoprioRt()) == 0);
        if (note) {
            *note += std::string("；运行期提升（覆盖整个压测周期）：setpriority(-10) ")
                + (prio_ok_ ? "成功" : "失败") + "、I/O 调度类 RT " + (ioprio_ok_ ? "成功" : "失败");
        }
        RUNBOOST_LOG("run_boost: enable=1 pin=%d threads=%u note=%s",
                     (int)pin_affinity, threads, note ? note->c_str() : "");
    }
    ~RunScopeBoost() {
        if (!enabled_) return;
        setpriority(PRIO_PROCESS, 0, orig_prio_);
        if (orig_ioprio_ >= 0) BoostIoprioSet(orig_ioprio_);
        RUNBOOST_LOG("run_boost: restored prio=%d ioprio=%d", orig_prio_, orig_ioprio_);
    }
    RunScopeBoost(const RunScopeBoost&) = delete;
    RunScopeBoost& operator=(const RunScopeBoost&) = delete;
private:
    bool enabled_;
    int orig_prio_;
    int orig_ioprio_;
    bool prio_ok_ = false;
    bool ioprio_ok_ = false;
};

}  // namespace dynamo
