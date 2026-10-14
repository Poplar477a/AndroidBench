/*
 * bench_cli.c —— 内核开发/验证用命令行驱动（**不是交付形态**）
 *
 * 交付形态永远是 .so + JNI（docs/ARCHITECTURE.md 明确否决了把 ELF 塞进 APK 再 exec 的做法）。
 * 这个可执行文件只做两件事：
 *   1. 在开发机（宿主机或 adb push 到 /data/local/tmp）验证内核的正确性与真实性能；
 *   2. 让验收脚本能在没有 UI 的情况下跑通"规模 -> 预算 -> 执行 -> 结果"全链路。
 *
 * 用法：
 *   bench_cli list
 *   bench_cli <id> [--threads N] [--iters N] [--scale 0..4] [--data-mb N]
 *                 [--budget-mb N] [--max-ms N] [--verify-only]
 */
#include "bench_api.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int g_progress_enabled = 1;

static void on_progress(void *ctx, double fraction, const char *stage)
{
    (void)ctx;
    if (!g_progress_enabled) return;
    if (fraction < 0.0) fraction = 0.0;
    if (fraction > 1.0) fraction = 1.0;
    fprintf(stderr, "  [%3.0f%%] %s\n", fraction * 100.0, stage ? stage : "");
    fflush(stderr);
}

static void usage(void)
{
    fprintf(stderr,
            "用法: bench_cli list\n"
            "      bench_cli <id> [--threads N] [--iters N] [--scale 0..4] [--data-mb N]\n"
            "                     [--budget-mb N] [--max-ms N] [--quiet]\n");
}

static int parse_u64(const char *s, uint64_t *out)
{
    char *end = NULL;
    const unsigned long long v = strtoull(s, &end, 0);
    if (end == s) return 0;
    *out = (uint64_t)v;
    return 1;
}

int main(int argc, char **argv)
{
    size_t count = 0;
    const bench_kernel *kernels = bench_registry(&count);

    if (argc < 2) { usage(); return 2; }
    if (strcmp(argv[1], "list") == 0) {
        printf("已注册内核 %zu 个：\n", count);
        for (size_t i = 0; i < count; i++)
            printf("  %-14s %-34s 单位 %s\n", kernels[i].id, kernels[i].display_name, kernels[i].unit);
        return 0;
    }

    const bench_kernel *k = NULL;
    for (size_t i = 0; i < count; i++)
        if (strcmp(kernels[i].id, argv[1]) == 0) { k = &kernels[i]; break; }
    if (k == NULL) { fprintf(stderr, "未知内核：%s\n", argv[1]); usage(); return 2; }

    bench_params p;
    memset(&p, 0, sizeof p);
    p.threads    = 1;
    p.scale_level = BENCH_SCALE_SMALL;
    p.progress_cb = on_progress;
    uint64_t budget_mb = 0;
    uint64_t budget_kb = 0;

    for (int i = 2; i < argc; i++) {
        const char *a = argv[i];
        const int has_next = (i + 1 < argc);
        uint64_t v = 0;
        if (strcmp(a, "--quiet") == 0) { g_progress_enabled = 0; continue; }
        if (!has_next) { fprintf(stderr, "参数 %s 缺少数值\n", a); return 2; }
        if (!parse_u64(argv[i + 1], &v)) { fprintf(stderr, "参数 %s 数值非法：%s\n", a, argv[i + 1]); return 2; }
        i++;
        if      (strcmp(a, "--threads") == 0)   p.threads = (uint32_t)v;
        else if (strcmp(a, "--iters") == 0)     p.iterations = v;
        else if (strcmp(a, "--scale") == 0)     p.scale_level = (uint32_t)v;
        else if (strcmp(a, "--data-mb") == 0)   p.data_bytes = v * 1024ULL * 1024ULL;
        else if (strcmp(a, "--budget-mb") == 0) { budget_mb = v; }
        else if (strcmp(a, "--budget-kb") == 0) { budget_kb = v; }   /* 演示预算拦截用 */
        else if (strcmp(a, "--max-ms") == 0)    p.max_runtime_ms = v;
        else { fprintf(stderr, "未知参数：%s\n", a); usage(); return 2; }
    }

    if (budget_kb > 0)      bench_set_memory_budget(budget_kb * 1024ULL);
    else if (budget_mb > 0) bench_set_memory_budget(budget_mb * 1024ULL * 1024ULL);

    /* 默认给一个宽松预算：真实预算由 Kotlin 侧按设备探测结果下发 */
    if (bench_memory_budget() == 0) bench_set_memory_budget(1024ULL * 1024ULL * 1024ULL);

    bench_result r;
    memset(&r, 0, sizeof r);
    const bench_status st = k->run(&p, &r);

    printf("\n===== %s (%s) =====\n", k->display_name, k->id);
    printf("状态             : %d %s\n", (int)st,
           st == BENCH_OK ? "OK" : st == BENCH_ERR_ALLOC ? "分配被预算拦截/失败"
           : st == BENCH_ERR_CANCELLED ? "已取消" : st == BENCH_ERR_INVALID_ARG ? "参数非法" : "错误");
    printf("主指标           : %.3f %s\n", r.primary_metric, r.primary_unit ? r.primary_unit : "");
    for (int i = 0; i < r.secondary_count; i++)
        printf("  · %-28s : %.3f\n", r.secondary_name[i] ? r.secondary_name[i] : "?", r.secondary[i]);
    printf("耗时             : %llu ms\n", (unsigned long long)r.elapsed_ms);
    printf("峰值原生内存     : %.2f MB (预算 %.2f MB)\n",
           (double)r.peak_memory_bytes / 1048576.0, (double)bench_memory_budget() / 1048576.0);
    printf("线程             : %u\n", r.threads_used);
    printf("正确性校验       : %s —— %s\n",
           r.verify == BENCH_VERIFY_PASS ? "PASS" : r.verify == BENCH_VERIFY_FAIL ? "FAIL" : "未校验",
           r.verify_detail);
    printf("输入规模         : %s\n", r.input_summary);
    if (r.note[0]) printf("如实披露         : %s\n", r.note);
    return st == BENCH_OK ? 0 : 1;
}

