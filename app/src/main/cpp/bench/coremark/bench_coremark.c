/*
 * bench_coremark.c —— CoreMark 内核适配层（对上只暴露统一 bench_api 契约）
 *
 * 为什么要有这一层：
 *   上游 core_main.c 自带 main()、自带命令行解析、自带 printf 汇报与 CRC 校验，
 *   它不是一个库。本层通过 -Dmain=coremark_cli_main 把上游 main() 变成一个普通函数
 *   （**上游源码零改动**），再用官方参数驱动它，最后把它的输出解析成结构化结果。
 *
 * 计分口径（严格照上游，不做任何"换算"或"美化"）：
 *   CoreMark 分数 = Iterations/Sec，即 core_main 打印的 "Iterations/Sec : <v>"。
 *   上游 run rules 要求单次计分运行 ≥ 10 s；不足 10 s 的运行会在 note 里如实标注，
 *   由上层（Kotlin 可比性判定）决定是否允许作为标准成绩。
 *
 * 取消语义（如实披露，不假装支持）：
 *   上游 core_main.c 的内层迭代循环没有任何可插入的轮询点，而我们不允许改算法/主控文件，
 *   因此 cancel 只能在**本次运行前后**生效。需要硬性中断时，请由上层用 max_runtime 控制规模
 *   或直接结束进程。这一限制写进结果的 note 字段，绝不静默。
 */
#include "bench_api.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>   /* strcasecmp */

/* 上游 core_main.c 的 main()，经 -Dmain=coremark_cli_main 改名后在此声明 */
int coremark_cli_main(int argc, char **argv);

#ifndef COREMARK_MAX_THREADS
/* 必须与构建时的 -DMULTITHREAD=<n> 保持一致：上游用编译期常量分配 core_results 数组 */
#define COREMARK_MAX_THREADS 8
#endif

/* ---------------- 上游输出捕获 ---------------- */

#define BENCH_CM_LOG_CAP 16384
static char   g_log[BENCH_CM_LOG_CAP];
static size_t g_log_len = 0;

/** 上游 core_portme.h 把 ee_printf 重定向到这里（见 port/core_portme.h） */
int bench_ee_printf(const char *fmt, ...)
{
    if (g_log_len + 1 >= sizeof(g_log)) return 0;
    va_list ap;
    va_start(ap, fmt);
    const size_t room = sizeof(g_log) - 1 - g_log_len;
    const int n = vsnprintf(g_log + g_log_len, room + 1, fmt, ap);
    va_end(ap);
    if (n > 0) {
        const size_t written = ((size_t)n < room) ? (size_t)n : room;
        g_log_len += written;
        g_log[g_log_len] = '\0';
    }
    return n;
}

/*
 * 按**行首**匹配键再取冒号后的数字。
 * 不能直接用 strstr("Iterations") —— 它会先命中 "Iterations/Sec"，把速率当成迭代数。
 */
static int parse_line_metric(const char *key, double *out)
{
    const size_t klen = strlen(key);
    const char *p = g_log;
    while (p != NULL && *p != '\0') {
        const char *nl = strchr(p, '\n');
        const size_t len = (nl != NULL) ? (size_t)(nl - p) : strlen(p);
        if (len > klen && strncmp(p, key, klen) == 0) {
            const char *colon = (const char *)memchr(p, ':', len);
            if (colon != NULL) {
                char *end = NULL;
                const double v = strtod(colon + 1, &end);
                if (end != colon + 1) { *out = v; return 1; }
            }
        }
        p = (nl != NULL) ? (nl + 1) : NULL;
    }
    return 0;
}

/** 取某个 "[0]xxxxx" 行的值（找不到返回 -1）；用于 CRC 三行的规范化 */
static int line_hex_named(const char *name, char *dst, size_t cap)
{
    const char *p = g_log;
    while (p != NULL && *p != '\0') {
        const char *nl = strchr(p, '\n');
        const size_t len = (nl != NULL) ? (size_t)(nl - p) : strlen(p);
        if (len > 3 && strncmp(p, "[0]", 3) == 0 && strncmp(p + 3, name, strlen(name)) == 0) {
            const char *colon = (const char *)memchr(p, ':', len);
            if (colon != NULL) {
                const char *v = colon + 1;
                while (*v == ' ') v++;
                size_t vlen = len - (size_t)(v - p);
                if (vlen >= cap) vlen = cap - 1;
                memcpy(dst, v, vlen);
                dst[vlen] = '\0';
                return 1;
            }
        }
        p = (nl != NULL) ? (nl + 1) : NULL;
    }
    return 0;
}

/** 把 CRC 三行摘进 verify_detail，输出成 "crclist 0xe714 / crcmatrix 0x1fd7 / crcstate 0x8e3a" */
static void copy_crc_lines(char *dst, size_t cap)
{
    static const char *names[3] = { "crclist", "crcmatrix", "crcstate" };
    dst[0] = '\0';
    for (int i = 0; i < 3; i++) {
        char v[32];
        if (!line_hex_named(names[i], v, sizeof v)) continue;
        if (dst[0] != '\0') strncat(dst, " / ", cap - strlen(dst) - 1);
        strncat(dst, names[i], cap - strlen(dst) - 1);
        strncat(dst, " ", cap - strlen(dst) - 1);
        strncat(dst, v, cap - strlen(dst) - 1);
    }
}

/* ---------------- 内核入口 ---------------- */

static bench_status coremark_run(const bench_params *p, bench_result *out)
{
    if (p == NULL || out == NULL) return BENCH_ERR_INVALID_ARG;
    memset(out, 0, sizeof(*out));
    out->primary_unit = "iterations/s";

    if (p->cancel_flag != NULL && *p->cancel_flag != 0) return BENCH_ERR_CANCELLED;

    uint32_t threads = p->threads ? p->threads : 1;
    if (threads > COREMARK_MAX_THREADS) threads = COREMARK_MAX_THREADS;

    /* 分配预检：上游会给 MULTITHREAD 个上下文各分配一块数据区（即使只跑 1 个上下文），
     * 且**不检查 portable_malloc 的返回值**，预算不足会直接崩。这里先用一次探针分配把
     * 预算问题拦在外面，失败即返回 BENCH_ERR_ALLOC。 */
    {
        const size_t per_ctx = 2000;                                 /* 官方 6k 配置 */
        const size_t need = per_ctx * (size_t)COREMARK_MAX_THREADS;
        void *probe = bench_alloc(need, 64);
        if (probe == NULL) {
            snprintf(out->note, sizeof out->note,
                     "内存预检未通过：需要约 %zu 字节，预算 %.2f MB（当前占用 %.2f MB）",
                     need, (double)bench_memory_budget() / 1048576.0,
                     (double)bench_live_memory_bytes() / 1048576.0);
            out->verify = BENCH_VERIFY_NOT_RUN;
            snprintf(out->verify_detail, sizeof out->verify_detail, "未执行：分配被预算拦截");
            return BENCH_ERR_ALLOC;
        }
        bench_free(probe);
    }

    g_log_len = 0;
    g_log[0] = '\0';

    /* 官方标准参数：seed1=0 seed2=0 seed3=0x66（performance run）；execs=7 三种算法全跑；
     * 第 7 个参数是每算法数据块大小（上游默认 2000，官方 6k 配置即为 2000）。
     * 迭代数为 0 时 core_main 会按上游规则自动标定到约 10 s。 */
    char mopt[16];
    char iters[32];
    const char *argv[10];
    int argc = 0;
    argv[argc++] = "coremark";
    if (threads > 1) {
        snprintf(mopt, sizeof mopt, "M%u", threads);  /* 上游 posix 端口用 M<n> 指定并发上下文 */
        argv[argc++] = mopt;
    }
    argv[argc++] = "0x0";
    argv[argc++] = "0x0";
    argv[argc++] = "0x66";
    snprintf(iters, sizeof iters, "%llu", (unsigned long long)p->iterations);
    argv[argc++] = iters;
    argv[argc++] = "7";
    argv[argc++] = "1";
    argv[argc++] = "2000";
    argv[argc] = NULL;

    const uint64_t t0 = bench_now_ms();
    const int rc = coremark_cli_main(argc, (char **)argv);
    out->elapsed_ms = bench_now_ms() - t0;

    /* ---------------- 结果解析 ---------------- */
    double score = 0.0, secs = 0.0, iters_done = 0.0;
    const int has_score = parse_line_metric("Iterations/Sec", &score);
    const int has_secs  = parse_line_metric("Total time (secs)", &secs);
    const int has_iters = parse_line_metric("Iterations ", &iters_done);

    if (!has_score) {
        out->verify = BENCH_VERIFY_NOT_RUN;
        out->verify_detail[0] = '\0';
        snprintf(out->note, sizeof out->note,
                 "上游未输出 Iterations/Sec，无法计分（返回码 %d）；原始输出已捕获供排查", rc);
        return BENCH_ERR_INTERNAL;
    }

    out->primary_metric = score;
    out->threads_used   = threads;
    out->peak_memory_bytes = bench_peak_memory_bytes();

    int si = 0;
    if (has_iters) { out->secondary[si] = iters_done; out->secondary_name[si] = "总迭代数"; si++; }
    if (has_secs)  { out->secondary[si] = secs;       out->secondary_name[si] = "运行时长 (s)"; si++; }
    /* 注意：主指标（上游 Iterations/Sec）已经含 default_num_contexts，
     * 即多线程时它本来就是全线程总分；这里再乘线程数会是重复计数。 */
    out->secondary[si] = (double)threads; out->secondary_name[si] = "并发上下文数"; si++;
    out->secondary_count = si;

    /* 正确性判定必须区分两类完全不同的 "ERROR"：
     *   a) CRC 不匹配（"[0]ERROR! list crc ..." / "Errors detected"）→ 算法或移植真的错了，判 FAIL；
     *   b) "ERROR! Must execute for at least 10 secs for a valid result!" → CRC 是对的，
     *      只是时长不满足上游 run rules，这属于**成绩资格**问题，不是正确性问题：
     *      此处判 PASS，并在 note 里如实标注"不构成标准成绩"，由上层可比性逻辑去限制上榜。 */
    /* 注意：不能把 "Errors detected" 当 CRC 失败 —— 上游在「时长 <10 s」时也会打印它，
     * 而那种情况 CRC 其实是全对的。真正的 CRC 失败只会伴随下面三行之一。 */
    const int crc_bad    = (strstr(g_log, "]ERROR! list crc") != NULL) ||
                           (strstr(g_log, "]ERROR! matrix crc") != NULL) ||
                           (strstr(g_log, "]ERROR! state crc") != NULL);
    const int too_short  = (strstr(g_log, "Must execute for at least 10 secs") != NULL);

    /* 正确性用**上游公开的已知输出**独立判定，而不是依赖上游那句
     * "Correct operation validated."（「<10 s」时它不会打印，但 CRC 其实是对的，
     * 那是成绩资格问题，不是正确性问题）：
     *   标准 6k 配置（seed1=0, seed2=0, seed3=0x66, 每算法 666 字节）的官方已知值为
     *   crclist=0xe714 / crcmatrix=0x1fd7 / crcstate=0x8e3a（见 core_main.c 的
     *   list/matrix/state_known_crc 表，known_id=3）。 */
    char crc_list[32] = {0}, crc_mat[32] = {0}, crc_st[32] = {0};
    const int have_crc = line_hex_named("crclist", crc_list, sizeof crc_list) &
                         line_hex_named("crcmatrix", crc_mat, sizeof crc_mat) &
                         line_hex_named("crcstate", crc_st, sizeof crc_st);
    const int crc_known = have_crc &&
                          strcasecmp(crc_list, "0xe714") == 0 &&
                          strcasecmp(crc_mat,  "0x1fd7") == 0 &&
                          strcasecmp(crc_st,   "0x8e3a") == 0;
    if (crc_bad)            out->verify = BENCH_VERIFY_FAIL;
    else if (crc_known)     out->verify = BENCH_VERIFY_PASS;
    else if (have_crc)      out->verify = BENCH_VERIFY_NOT_RUN;   /* 非官方 seed/尺寸：不与已知值比对 */
    else if (strstr(g_log, "Correct operation validated") != NULL) out->verify = BENCH_VERIFY_PASS;
    else                    out->verify = BENCH_VERIFY_NOT_RUN;

    char crcs[160];
    copy_crc_lines(crcs, sizeof crcs);
    snprintf(out->verify_detail, sizeof out->verify_detail,
             "%s%s%s",
             out->verify == BENCH_VERIFY_PASS
                 ? (crc_known ? "三算法 CRC 与上游官方已知值一致"
                              : "上游自校验通过（Correct operation validated）")
             : out->verify == BENCH_VERIFY_FAIL ? "CRC 与官方已知值不符，结果不可信"
             : "CRC 已采集但无法与官方已知值比对（非标准 seed/尺寸）",
             crcs[0] ? " —— " : "",
             crcs[0] ? crcs : "未见 CRC 行");

    snprintf(out->input_summary, sizeof out->input_summary,
             "线程 %u · 迭代 %s · 数据块 2000/算法（官方 6k 配置）",
             threads, p->iterations == 0 ? "官方自动标定" : iters);

    /* 如实披露两件事：运行时长是否达到上游 10 s 规则、以及取消为何可能延迟生效 */
    snprintf(out->note, sizeof out->note,
             "%s%s取消需等本次运行结束（上游无轮询点）",
             has_secs ? "" : "未解析到时长；",
             (too_short || (has_secs && secs < 10.0))
                 ? "本次 < 10 s：按上游 run rules 不构成标准成绩；"
                 : (p->iterations == 0 ? "官方自动标定 ≥10 s；" : ""));

    if (p->cancel_flag != NULL && *p->cancel_flag != 0) {
        /* 结果已经算出来了，但用户要求取消：不把结果当成功返回 */
        return BENCH_ERR_CANCELLED;
    }
    (void)rc;
    return out->verify == BENCH_VERIFY_FAIL ? BENCH_ERR_INTERNAL : BENCH_OK;
}

const bench_kernel bench_kernel_coremark = {
    .id           = "coremark",
    .display_name = "CoreMark 整数与核心性能",
    .unit         = "iterations/s",
    .run          = coremark_run,
};

