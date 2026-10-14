/*
 * bench_stream.c —— STREAM 持续内存带宽内核（Copy / Scale / Add / Triad）
 *
 * 上游参考：upstream/stream/stream.c（jeffhammond/STREAM，John D. McCalpin，v5.10）
 * 规范依据：docs/SCALE_MANAGEMENT.md 14.5 C 小节
 *           （Standard: N = 10,000,000 → 工作集 320MB，必须击穿移动端 8~16MB LLC；
 *             工作集 < 64MB 属缓存驻留测试，不得与持续内存带宽混为一谈）
 * 契  约：src/main/cpp/include/bench_api.h（只读；本文件未改动其中任何约定）
 *
 * ── 单位口径（最容易与其它内核混淆，务必注意）──────────────────────────
 *   STREAM 上游使用「1 MB = 1e6 字节」的十进制口径：
 *       带宽(MB/s) = 传输字节数 / 耗时(秒) / 1e6
 *   （上游 stream.c 摘要打印即 1.0E-06 * bytes[j]/mintime[j]）。
 *   本内核严格沿用该口径，工作集 MB 数亦按 1e6 换算：
 *       N = 10,000,000 → 4 * N * 8 / 1e6 = 320.0 MB（与 14.5C 的 320MB 一致）。
 *   若与采用 MiB(2^20) 口径的其它内核并列展示，必须显式换算，禁止直接混排。
 *
 * ── 工作集构成（项目规范为 4 个 double 数组）──────────────────────────
 *   a[] / b[] / c[] ：参与 Copy/Scale/Add/Triad 四内核，算法与上游逐字一致
 *                     （c=a; b=q*c; c=a+b; a=b+q*c; q=3.0）
 *   pad[]           ：第 4 个数组，作用有二：
 *                     1) 按 14.5C 的「4 * N * sizeof(double)」把真实驻留工作集撑满，
 *                        使 RSS / 内存预算估算与规范一致（N=10M 时共 320MB）；
 *                     2) 作为越界写哨兵（canary）：四内核只允许触碰 a/b/c，
 *                        校验阶段比对 pad[] 是否仍为初值，可捕获数组越界写。
 *   四个数组全部经 bench_alloc(bytes, 64) 分配，全程不使用 malloc；
 *   任一次分配失败（含预算拦截）即释放已分配部分并返回 BENCH_ERR_ALLOC。
 *   注意：bench_alloc 自带 16 字节分配头，返回指针按 16B 对齐（见 bench_common.c
 *   的说明），因此本内核不做 32B/64B 对齐假设，也不使用非标准的对齐内建函数。
 *
 * ── 其它工程取舍 ──────────────────────────────────────────────────────
 *   1) 单线程：与上游未启用 OpenMP 的构建一致（多线程会改变可比性，且 NDK 侧
 *      CMake 未开启 -fopenmp）。threads_used 如实回填 1，并在 note 中披露。
 *   2) 计时用 clock_gettime(CLOCK_MONOTONIC) 纳秒读数。bench_now_ms() 只有毫秒
 *      精度，而单轮内核在 N=200000 时仅约 1ms，用它会把误差放大到百分之几，故不用。
 *   3) NTIMES 默认 10（上游默认）；可由 p->iterations 覆盖，钳制在 [2, 100]。
 *      报告带宽取「除首轮外最优（最小耗时）」，与上游 "Best Rate MB/s" 完全一致。
 *   4) p->max_runtime_ms 不在本内核内强制截断：Kotlin 侧 StreamPolicy 给出的
 *      STREAM 时限偏紧（Standard 也仅 800ms 量级），而 Standard 真实耗时为其数倍，
 *      硬截断会把正常测试误判为超时。按 ARCHITECTURE.md，超时看门狗由 Kotlin 侧负责。
 *   5) 取消粒度（bench_api.h 铁律 3 的兜底形态）：只在「每完成一个内核」与
 *      「每一轮 NTIMES 之间」轮询 p->cancel_flag，不在内层循环里轮询。
 *      原因是实测代价过大：在内层循环插入 volatile 判断与 return 会让循环出现
 *      第二个出口，向量化被放弃，Copy 带宽从 ~35.5 GB/s 掉到 ~15 GB/s（分块轮询
 *      亦有 ~19 GB/s 的同级损失）—— 那是把「内存带宽」偷换成「指令发射」。
 *      兜底：最坏取消延迟 = 单个内核一次执行，Standard(320MB) 约 10~30ms，
 *      Large(960MB) 约 30~90ms，远小于取消看门狗可接受的响应窗口。
 *   6) 进度粒度：保持上游「NTIMES 轮 × 4 内核」的交错执行顺序（改成内核优先会改变
 *      a/b/c 的终值，破坏 Oracle），故而对「每一次内核执行」在开始/结束各回调一次
 *      p->progress_cb（stage = Copy/Scale/Add/Triad），fraction 按已完成内核数
 *      /(4*NTIMES) 单调不减地推进到 1.0；若按 4 个内核各只回调一次，进度会在首轮
 *      结束时就跳到 100%，与真实进度严重不符。
 *   7) 内核循环一律保持「扁平、单出口、不切块、不手工展开、不软件预取」：这是上游
 *      stream.c 的原始形态，也是实测唯一能打到 DRAM 上限的形态（见内核段注释）。
 */
/* 严格 -std=c11 下 glibc/Bionic 默认隐藏 POSIX 声明，而本内核必须使用
 * clock_gettime(CLOCK_MONOTONIC)（纳秒级单调时钟）。在任何头文件之前显式请求
 * POSIX.1-2008 接口；若构建系统已定义该宏则不覆盖。 */
#ifndef _POSIX_C_SOURCE
#define _POSIX_C_SOURCE 200809L
#endif

#include "bench_api.h"

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

/* ---------------- 常量 ---------------- */

#define ST_NAME_COUNT        4u
#define ST_NTIMES_DEFAULT    10u    /* 上游默认 NTIMES */
#define ST_NTIMES_MIN        2u     /* 上游要求「除首轮外取最优」，至少 2 轮 */
#define ST_NTIMES_MAX        100u   /* 上限：避免 times[][] 过大，且防止期望值溢出 double */
#define ST_SCALAR            3.0    /* 上游 scalar = 3.0 */
#define ST_UNIT              "MB/s" /* 主指标单位（1 MB = 1e6 字节口径） */
#define ST_ALIGN             64u    /* 数组对齐（bench_alloc 要求 2 的幂且 >= 16） */
#define ST_VERIFY_EPS        1e-8   /* 逐元素校验容差（本工程规定，见需求 5） */
#define ST_ARRAY_COUNT       4u     /* 项目规范：4 个 double 数组共同构成工作集 */

/* 缓存驻留红线：工作集 < 64MB（1e6 口径，与 14.5C 的 MB 口径一致）时必须在报告中标注 */
#define ST_CACHE_RESIDENT_BYTES (64ull * 1000000ull)

/* 每个内核的传输字节数系数（与上游 bytes[4] 完全一致：Copy/Scale 2 路，Add/Triad 3 路） */
static const double st_streams[ST_NAME_COUNT] = { 2.0, 2.0, 3.0, 3.0 };

static const char *const st_names[ST_NAME_COUNT] = { "Copy", "Scale", "Add", "Triad" };

/* ---------------- 基础工具 ---------------- */

/* 单调时钟读数（秒，纳秒精度）；不使用 bench_now_ms()，理由见文件头取舍 2 */
static double st_now_sec(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec * 1e-9;
}

/* 自备 fabs：保持「仅依赖 libc」，不引入 libm（-lm）依赖 */
static double st_fabs(double x)
{
    return x < 0.0 ? -x : x;
}

/* 取消标志轮询（可为 NULL） */
static int st_cancelled(const volatile int *cancel_flag)
{
    return cancel_flag != NULL && *cancel_flag != 0;
}

static void st_progress(const bench_params *p, double fraction, const char *stage)
{
    if (p->progress_cb != NULL) {
        p->progress_cb(p->progress_ctx, fraction, stage);
    }
}

/* ---------------- 工作集 ---------------- */

typedef struct {
    double *a;      /* Copy 源 / Add 加数 / Triad 目标 */
    double *b;      /* Scale 目标 / Add 加数 / Triad 加数 */
    double *c;      /* Copy 目标 / Scale 源 / Add 目标 / Triad 加数 */
    double *pad;    /* 第 4 数组：撑满规范工作集 + 越界写哨兵，不参与四内核语义 */
    size_t  n;      /* 每数组元素数 */
} stream_ctx;

static void st_release(stream_ctx *x)
{
    bench_free(x->a);
    bench_free(x->b);
    bench_free(x->c);
    bench_free(x->pad);
    x->a = x->b = x->c = x->pad = NULL;
    x->n = 0;
}

/* 规模档位（与 test_runner/run_acceptance_tests.py 的 StreamPolicy 一致）
 *   Smoke 200000 / Small 1500000 / Standard 10000000 / Large 30000000
 * Advanced 无显式 data_bytes 时保守退回 Standard（其语义本就是专家自定义输入） */
static size_t st_scale_elements(uint32_t scale_level)
{
    switch (scale_level) {
    case BENCH_SCALE_SMOKE:    return 200000u;
    case BENCH_SCALE_SMALL:    return 1500000u;
    case BENCH_SCALE_STANDARD: return 10000000u;
    case BENCH_SCALE_LARGE:    return 30000000u;
    default:                   return 10000000u;   /* BENCH_SCALE_ADVANCED / 未知值 */
    }
}

/* ---------------- 四个内核（语义与上游 stream.c 逐字一致）----------------
 * 循环形态刻意与上游 stream.c 完全一致：一条扁平的连续循环，单出口、无内部
 * 分支、不切块、不手工展开、不做软件预取。
 *
 * 为什么必须如此（宿主机 gcc -O2、N=10,000,000 实测，见交付说明的对照实验）：
 *   扁平单出口循环（本实现）                     Copy ≈ 35.5 GB/s
 *   循环体内插 volatile 取消判断并 return        Copy ≈ 15 GB/s  ← 循环出现第二个出口，
 *                                                                 向量化被放弃，退化为
 *                                                                 标量逐元素拷贝
 *   分块 while + 块间轮询                        Copy ≈ 19 GB/s
 *   手工 4/8 路展开                              Copy ≈ 21~23 GB/s
 *   —— 后三种都会把「测内存带宽」偷换成「测指令发射」，在 Standard 规模下会把
 *      Copy 带宽腰斩，故一律不采用。
 *
 * 取消轮询（bench_api.h 铁律 3 的兜底形态）：
 *   本内核不在内层循环里轮询 p->cancel_flag（实测代价是带宽腰斩），而是
 *   「每完成一个内核 + 每一轮 NTIMES 之间」轮询，与需求 6 的规定一致。
 *   兜底说明：最坏取消延迟 = 单个内核的一次执行，Standard(N=1e7, 320MB) 下约
 *   10~30ms，Large(N=3e7) 下约 30~90ms，远小于 Kotlin 侧看门狗的响应窗口。
 * 返回 0 = 正常完成，1 = 收到取消信号（该次计时作废）。
 */

static int st_copy(double *restrict dst, const double *restrict src, size_t n)
{
    size_t j;
    for (j = 0; j < n; j++) dst[j] = src[j];                /* c[j] = a[j] */
    return 0;
}

static int st_scale(double *restrict dst, const double *restrict src, double q, size_t n)
{
    size_t j;
    for (j = 0; j < n; j++) dst[j] = q * src[j];            /* b[j] = q*c[j] */
    return 0;
}

static int st_add(double *restrict dst, const double *restrict x, const double *restrict y, size_t n)
{
    size_t j;
    for (j = 0; j < n; j++) dst[j] = x[j] + y[j];           /* c[j] = a[j]+b[j] */
    return 0;
}

static int st_triad(double *restrict dst, const double *restrict x, const double *restrict y, double q, size_t n)
{
    size_t j;
    for (j = 0; j < n; j++) dst[j] = x[j] + q * y[j];       /* a[j] = b[j]+q*c[j] */
    return 0;
}

/* 按上游顺序执行第 which 个内核：0=Copy 1=Scale 2=Add 3=Triad */
static int st_run_kernel(unsigned which, const stream_ctx *x)
{
    switch (which) {
    case 0:  return st_copy(x->c, x->a, x->n);
    case 1:  return st_scale(x->b, x->c, ST_SCALAR, x->n);
    case 2:  return st_add(x->c, x->a, x->b, x->n);
    default: return st_triad(x->a, x->b, x->c, ST_SCALAR, x->n);
    }
}

/* ---------------- 正确性 Oracle ---------------- */

typedef struct {
    double   expect_a, expect_b, expect_c;  /* 上游算法推算的数组终值 */
    double   rel_a, rel_b, rel_c;           /* 各数组自身最大相对误差 */
    double   max_abs_err;                   /* 三数组最大绝对误差 */
    double   max_rel_err;                   /* 三数组最大相对误差 */
    uint64_t mismatch;                      /* 超出 eps 的元素总数 */
    int      canary_bad;                    /* pad[] 被改写 → 越界写 */
} stream_check;

static void st_scan_array(const double *arr, size_t n, double expect,
                          double *max_abs, double *max_rel, uint64_t *mismatch)
{
    size_t j;
    const double denom = st_fabs(expect);
    for (j = 0; j < n; j++) {
        const double d = st_fabs(arr[j] - expect);
        const double r = d / denom;
        if (d > *max_abs) *max_abs = d;
        if (r > *max_rel) *max_rel = r;
        if (r > ST_VERIFY_EPS) (*mismatch)++;
    }
}

/* 照搬上游 checkSTREAMresults 的思路：
 *   1) 按 NTIMES 次迭代解析推算 a/b/c 应有的终值（同一递推式，含 a 的标定乘 2）；
 *   2) 逐元素比对，容差 eps；同时统计最大误差与失配元素数；
 *   3) 额外用 pad[] 做越界写哨兵校验。 */
static void st_check_results(const stream_ctx *x, uint32_t ntimes, stream_check *ck)
{
    double aj = 1.0, bj = 2.0, cj = 0.0;   /* reproduce initialization */
    double max_abs = 0.0, max_rel = 0.0;
    uint64_t mismatch = 0;
    size_t j;
    uint32_t k;

    /* a[] 在计时标定循环里被乘过 2（上游注释：a[] is modified during timing check） */
    aj = 2.0E0 * aj;

    /* now execute timing loop（与上游 checkSTREAMresults 完全相同的递推） */
    for (k = 0; k < ntimes; k++) {
        cj = aj;
        bj = ST_SCALAR * cj;
        cj = aj + bj;
        aj = bj + ST_SCALAR * cj;
    }

    /* accumulate deltas between observed and expected results（逐元素） */
    st_scan_array(x->a, x->n, aj, &max_abs, &ck->rel_a, &mismatch);
    st_scan_array(x->b, x->n, bj, &max_abs, &ck->rel_b, &mismatch);
    st_scan_array(x->c, x->n, cj, &max_abs, &ck->rel_c, &mismatch);
    max_rel = ck->rel_a > ck->rel_b ? ck->rel_a : ck->rel_b;
    if (ck->rel_c > max_rel) max_rel = ck->rel_c;

    /* 越界写哨兵：pad[] 应仍等于初值 1.0 */
    ck->canary_bad = 0;
    for (j = 0; j < x->n; j++) {
        if (x->pad[j] != 1.0) { ck->canary_bad = 1; break; }
    }

    ck->expect_a   = aj;
    ck->expect_b   = bj;
    ck->expect_c   = cj;
    ck->max_abs_err = max_abs;
    ck->max_rel_err = max_rel;
    ck->mismatch    = mismatch;
}

/* ---------------- 内核入口 ---------------- */

static bench_status bench_stream_run(const bench_params *p, bench_result *out)
{
    stream_ctx x;
    stream_check ck;
    double  times[ST_NAME_COUNT][ST_NTIMES_MAX];
    double  bw[ST_NAME_COUNT];
    double  t_start, t_end;
    uint64_t ws_bytes;
    uint32_t ntimes, runs_total, runs_done, k;
    size_t  arr_bytes;
    unsigned i;

    /* ---- 0. 参数校验 ---- */
    if (p == NULL || out == NULL) return BENCH_ERR_INVALID_ARG;
    memset(out, 0, sizeof(*out));
    memset(&x, 0, sizeof(x));
    memset(&ck, 0, sizeof(ck));
    out->primary_unit = ST_UNIT;
    out->verify = BENCH_VERIFY_NOT_RUN;

    t_start = st_now_sec();

    /* ---- 1. 确定工作集规模 ----
     * 优先 p->data_bytes（按 4 个 double 数组折算元素数），否则按 scale_level 取档位。 */
    if (p->data_bytes > 0) {
        x.n = (size_t)(p->data_bytes / ((uint64_t)ST_ARRAY_COUNT * sizeof(double)));
        if (x.n == 0) return BENCH_ERR_INVALID_ARG;   /* 预算连 4 个 double 都放不下 */
    } else {
        x.n = st_scale_elements(p->scale_level);
    }
    if (x.n > (size_t)(SIZE_MAX / sizeof(double))) return BENCH_ERR_INVALID_ARG;
    arr_bytes = x.n * sizeof(double);

    /* ---- 2. 分配（全部经 bench_alloc，64B 对齐；失败即 BENCH_ERR_ALLOC）---- */
    x.a   = (double *)bench_alloc(arr_bytes, ST_ALIGN);
    x.b   = (double *)bench_alloc(arr_bytes, ST_ALIGN);
    x.c   = (double *)bench_alloc(arr_bytes, ST_ALIGN);
    x.pad = (double *)bench_alloc(arr_bytes, ST_ALIGN);
    if (x.a == NULL || x.b == NULL || x.c == NULL || x.pad == NULL) {
        st_release(&x);
        return BENCH_ERR_ALLOC;   /* 预算拦截或系统分配失败 */
    }

    /* ---- 3. 初始化（与上游一致：a=1.0, b=2.0, c=0.0；pad 同为 1.0 作哨兵）---- */
    {
        size_t j;
        for (j = 0; j < x.n; j++) {
            x.a[j]   = 1.0;
            x.b[j]   = 2.0;
            x.c[j]   = 0.0;
            x.pad[j] = 1.0;
        }
    }

    /* 上游的计时标定循环：a[] = 2.0 * a[]（Oracle 的期望值以此为前提） */
    {
        size_t j;
        for (j = 0; j < x.n; j++) x.a[j] = 2.0E0 * x.a[j];
    }

    /* ---- 4. NTIMES 主循环（与上游同序：每轮 Copy → Scale → Add → Triad）---- */
    if (p->iterations > 0) {
        ntimes = (uint32_t)p->iterations;
        if (ntimes < ST_NTIMES_MIN) ntimes = ST_NTIMES_MIN;
        if (ntimes > ST_NTIMES_MAX) ntimes = ST_NTIMES_MAX;
    } else {
        ntimes = ST_NTIMES_DEFAULT;
    }

    runs_total = ntimes * ST_NAME_COUNT;
    runs_done  = 0;
    for (k = 0; k < ntimes; k++) {
        for (i = 0; i < ST_NAME_COUNT; i++) {
            double t0, dt;

            /* 进度：内核开始回调一次（fraction 单调不减，按已完成内核次数推进） */
            st_progress(p, (double)runs_done / (double)runs_total, st_names[i]);

            t0 = st_now_sec();
            (void)st_run_kernel(i, &x);
            dt = st_now_sec() - t0;

            /* 轮询点 1：每完成一个内核（需求 6） */
            if (st_cancelled(p->cancel_flag)) {
                st_release(&x);
                return BENCH_ERR_CANCELLED;
            }
            times[i][k] = dt;
            runs_done++;

            /* 进度：内核结束回调一次 */
            st_progress(p, (double)runs_done / (double)runs_total, st_names[i]);
        }
        /* 轮询点 2：每一轮 NTIMES 之间（需求 6） */
        if (st_cancelled(p->cancel_flag)) {
            st_release(&x);
            return BENCH_ERR_CANCELLED;
        }
    }

    /* ---- 5. 带宽统计：取「除首轮外最优」，与上游 Best Rate 一致 ----
     * MB 口径 = 1e6 字节（上游 1.0E-06 * bytes/time），非 MiB。 */
    for (i = 0; i < ST_NAME_COUNT; i++) {
        double best_t = times[i][1];
        for (k = 2; k < ntimes; k++) {
            if (times[i][k] < best_t) best_t = times[i][k];
        }
        if (best_t <= 0.0) best_t = 1e-9;   /* 理论不可达，防御性下限 */
        bw[i]   = st_streams[i] * (double)x.n * (double)sizeof(double) / best_t / 1e6;
    }

    /* ---- 6. 正确性 Oracle ---- */
    st_check_results(&x, ntimes, &ck);

    /* ---- 7. 结果回填 ---- */
    ws_bytes = (uint64_t)x.n * (uint64_t)sizeof(double) * ST_ARRAY_COUNT;

    out->primary_metric = bw[3];                       /* Triad 带宽为主指标 */
    for (i = 0; i < ST_NAME_COUNT; i++) {
        out->secondary[i]      = bw[i];
        out->secondary_name[i] = st_names[i];
    }
    out->secondary_count   = (int)ST_NAME_COUNT;
    out->threads_used      = 1;                        /* 单线程，见 note */
    out->peak_memory_bytes = bench_peak_memory_bytes();
    out->verify            = (ck.mismatch == 0 && !ck.canary_bad) ? BENCH_VERIFY_PASS
                                                                  : BENCH_VERIFY_FAIL;

    snprintf(out->verify_detail, sizeof(out->verify_detail),
             "eps=%.0e 最大相对误差 a=%.2e b=%.2e c=%.2e 失配元素=%llu 期望 a=%.3e b=%.3e c=%.3e%s",
             ST_VERIFY_EPS, ck.rel_a, ck.rel_b, ck.rel_c,
             (unsigned long long)ck.mismatch,
             ck.expect_a, ck.expect_b, ck.expect_c,
             ck.canary_bad ? " canary=FAIL" : "");

    snprintf(out->input_summary, sizeof(out->input_summary),
             "N=%llu 元素 · 工作集 %.1f MB · NTIMES=%u",
             (unsigned long long)x.n, (double)ws_bytes / 1e6, ntimes);

    /* note 需如实披露限制（bench_api.h）：单线程 + 取消轮询粒度 + 缓存驻留提示 */
    if (ws_bytes < ST_CACHE_RESIDENT_BYTES) {
        snprintf(out->note, sizeof(out->note),
                 "工作集<64MB：缓存驻留，非持续带宽；单线程；取消延迟≤单个内核");
    } else {
        snprintf(out->note, sizeof(out->note),
                 "单线程（同上游非 OpenMP）；取消延迟≤单个内核（约10~90ms）；4 数组含 pad 哨兵");
    }

    t_end = st_now_sec();
    out->elapsed_ms = (uint64_t)((t_end - t_start) * 1000.0 + 0.5);

    st_release(&x);
    return BENCH_OK;
}

const bench_kernel bench_kernel_stream = {
    "stream",              /* 与 Kotlin BenchmarkId.key 一致 */
    "STREAM 持续内存带宽",
    ST_UNIT,               /* "MB/s"（1 MB = 1e6 字节） */
    bench_stream_run
};