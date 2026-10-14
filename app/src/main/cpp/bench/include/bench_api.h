/*
 * AndroidBench —— 原生基准统一内核契约 (Native Benchmark Kernel Contract)
 *
 * 适用文档：docs/ARCHITECTURE.md（JNI 动态库、禁止 fork/exec）、
 *           docs/SCALE_MANAGEMENT.md 第 14 章（资源预算 / 取消 / 正确性校验 / 规模裁剪）
 *
 * 铁律（每个内核都必须遵守，JNI 层与验收脚本按此检查）：
 *  1. 严禁在 UI 主线程执行；线程约束由 Kotlin 侧 assertNotMainThread() 保证。
 *  2. 一切大块内存必须经 bench_alloc()，不得直接 malloc —— 预检 + 硬上限 + 峰值统计都在那里。
 *  3. 必须轮询 p->cancel_flag（内核若无法在上游内层循环里轮询，必须在实现里写明并给出兜底）。
 *  4. 必须给出正确性校验结论（bench_verify）；verify != PASS 的结果不得作为有效成绩上报。
 *  5. 必须回填 input_summary（实际使用的输入参数）与 threads_used，报告要能还原"到底跑了什么规模"。
 */
#ifndef ANDROIDBENCH_BENCH_API_H
#define ANDROIDBENCH_BENCH_API_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define BENCH_API_VERSION 1

/* ---- 状态码 ---- */
typedef enum {
    BENCH_OK              =  0,
    BENCH_ERR_INVALID_ARG = -1,
    BENCH_ERR_ALLOC       = -2,   /* 预算拦截或系统分配失败 */
    BENCH_ERR_CANCELLED   = -3,
    BENCH_ERR_TIMEOUT     = -4,
    BENCH_ERR_UNSUPPORTED = -5,
    BENCH_ERR_INTERNAL    = -6
} bench_status;

/* ---- 正确性校验（Oracle）结论 ---- */
typedef enum {
    BENCH_VERIFY_NOT_RUN = 0,
    BENCH_VERIFY_PASS    = 1,
    BENCH_VERIFY_FAIL    = 2
} bench_verify;

/* ---- 规模等级：与 Kotlin 侧 ScaleLevel 一一对应 ---- */
typedef enum {
    BENCH_SCALE_SMOKE    = 0,
    BENCH_SCALE_SMALL    = 1,
    BENCH_SCALE_STANDARD = 2,
    BENCH_SCALE_LARGE    = 3,
    BENCH_SCALE_ADVANCED = 4
} bench_scale_level;

/* ---- 运行参数 ---- */
typedef struct {
    uint32_t threads;        /* 期望并发线程数（内核可下调，必须回填实际值） */
    uint64_t iterations;     /* 迭代/工作量；0 = 按上游规则自动标定（如 CoreMark ≥10s） */
    uint32_t scale_level;    /* bench_scale_level */
    uint64_t data_bytes;     /* 期望工作集字节数；0 = 由内核按 scale_level 决定 */
    uint64_t max_runtime_ms; /* 墙钟上限；0 = 不限（由 Kotlin 侧看门狗负责） */
    /** 原子取消标志；可为 NULL。内核必须周期性读取，读到非 0 必须尽快返回 BENCH_ERR_CANCELLED */
    const volatile int *cancel_flag;
    /** 进度回调；可为 NULL。fraction ∈ [0,1]，stage 为简短阶段名（可为 NULL） */
    void (*progress_cb)(void *ctx, double fraction, const char *stage);
    void *progress_ctx;
} bench_params;

#define BENCH_MAX_SECONDARY 8

/* ---- 运行结果 ---- */
typedef struct {
    double      primary_metric;                 /* 主指标数值 */
    const char *primary_unit;                   /* 主指标单位（"iterations/s"、"MB/s"、"µs" …） */
    double      secondary[BENCH_MAX_SECONDARY]; /* 次级指标（如 STREAM 的四个内核分别的带宽） */
    const char *secondary_name[BENCH_MAX_SECONDARY];
    int         secondary_count;
    uint64_t    elapsed_ms;
    uint64_t    peak_memory_bytes;              /* 来自 bench_alloc 的真实峰值 */
    uint32_t    threads_used;
    bench_verify verify;
    char        verify_detail[192];             /* 校验说明：期望值/实测值/容差 */
    char        input_summary[192];             /* 实际输入规模，报告必须能还原 */
    char        note[192];                      /* 需要如实披露的限制（如"上游无轮询点，取消延后生效"） */
} bench_result;

/* ---- 内存预算与峰值统计（实现见 src/bench_common.c） ---- */
void     bench_set_memory_budget(uint64_t bytes);
uint64_t bench_memory_budget(void);
uint64_t bench_peak_memory_bytes(void);
uint64_t bench_live_memory_bytes(void);
/** 预检 + 对齐分配 + 峰值统计；超预算或失败返回 NULL（内核应返回 BENCH_ERR_ALLOC） */
void    *bench_alloc(size_t bytes, size_t align);
void     bench_free(void *p);
/** 墙钟毫秒（CLOCK_MONOTONIC） */
uint64_t bench_now_ms(void);

/* ---- 内核入口 ---- */
typedef bench_status (*bench_run_fn)(const bench_params *p, bench_result *out);

typedef struct {
    const char   *id;            /* 与 Kotlin BenchmarkId.key 一致："coremark" / "stream" … */
    const char   *display_name;
    const char   *unit;
    bench_run_fn  run;
} bench_kernel;

/** 内核注册表；count 回填条目数（实现见 src/bench_common.c） */
const bench_kernel *bench_registry(size_t *count);

#ifdef __cplusplus
}
#endif
#endif /* ANDROIDBENCH_BENCH_API_H */

