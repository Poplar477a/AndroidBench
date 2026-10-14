/*
 * bench_common.c —— 内存预算 / 峰值统计 / 内核注册表
 *
 * 为什么内存必须集中在这里：
 *   第 14 章要求「原生分配预检、超限拦截、报告真实峰值」。若各内核自行 malloc，
 *   预算就只是纸面数字。所有内核统一走 bench_alloc，超预算直接返回 NULL，
 *   内核据此返回 BENCH_ERR_ALLOC —— 宁可拒绝执行，也不触发 LMK 被系统强杀。
 */
#include "bench_api.h"

#include <stdlib.h>
#include <string.h>
#include <time.h>

/* ---------------- 内存预算与统计 ---------------- */

static uint64_t g_budget_bytes = 0;      /* 0 = 未设置（不拦截，仅统计） */
static uint64_t g_live_bytes   = 0;
static uint64_t g_peak_bytes   = 0;

void bench_set_memory_budget(uint64_t bytes)
{
    g_budget_bytes = bytes;
    g_live_bytes   = 0;
    g_peak_bytes   = 0;
}

uint64_t bench_memory_budget(void) { return g_budget_bytes; }
uint64_t bench_peak_memory_bytes(void) { return g_peak_bytes; }
uint64_t bench_live_memory_bytes(void) { return g_live_bytes; }

/* 分配头：记录 size 以便 free 时准确回退计数（posix_memalign 不提供可用长度） */
typedef struct {
    uint64_t magic;
    uint64_t size;
} bench_hdr;

#define BENCH_HDR_MAGIC 0x42454E4348414C4CULL /* "BENCHALL" */

void *bench_alloc(size_t bytes, size_t align)
{
    if (bytes == 0) return NULL;
    if (align < sizeof(void *)) align = sizeof(void *);
    /* 16 字节对齐：NEON / SSE 向量访存与 STREAM 内核都要求至少 16B 对齐 */
    if (align < 16) align = 16;
    if (align & (align - 1)) return NULL;   /* 非 2 的幂 */

    const uint64_t total = (uint64_t)bytes + sizeof(bench_hdr);
    if (g_budget_bytes != 0 && g_live_bytes + total > g_budget_bytes) {
        return NULL;                        /* 预算拦截：调用方应报 BENCH_ERR_ALLOC */
    }

    void *raw = NULL;
    if (posix_memalign(&raw, (size_t)align, (size_t)total) != 0 || raw == NULL) return NULL;

    bench_hdr *h = (bench_hdr *)raw;
    h->magic = BENCH_HDR_MAGIC;
    h->size  = total;

    g_live_bytes += total;
    if (g_live_bytes > g_peak_bytes) g_peak_bytes = g_live_bytes;
    return (void *)((char *)raw + sizeof(bench_hdr));
}

void bench_free(void *p)
{
    if (p == NULL) return;
    bench_hdr *h = (bench_hdr *)((char *)p - sizeof(bench_hdr));
    if (h->magic != BENCH_HDR_MAGIC) return;   /* 不是 bench_alloc 分配的，直接忽略 */
    const uint64_t total = h->size;
    h->magic = 0;
    if (g_live_bytes >= total) g_live_bytes -= total; else g_live_bytes = 0;
    free(h);
}

uint64_t bench_now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000ULL + (uint64_t)(ts.tv_nsec / 1000000ULL);
}

/* ---------------- 内核注册表 ---------------- */

/*
 * 用 weak 符号注册内核：每个内核是独立目录、独立编译单元，任何一个尚未落地都不会
 * 拖垮整库链接。未定义时取地址得到 NULL，注册表在运行期过滤掉 ——
 * 因此 "list 里出现什么" 就等于 "本次构建真的编进了什么"，不会出现幽灵条目。
 */
extern const bench_kernel bench_kernel_coremark    __attribute__((weak));
extern const bench_kernel bench_kernel_stream      __attribute__((weak));
extern const bench_kernel bench_kernel_tinymembench __attribute__((weak));

#define BENCH_KERNEL_SLOTS 3
static const bench_kernel *const g_kernels_all[BENCH_KERNEL_SLOTS] = {
    &bench_kernel_coremark,
    &bench_kernel_stream,
    &bench_kernel_tinymembench,
};

const bench_kernel *bench_registry(size_t *count)
{
    /* bench_kernel 只有几个指针字段，按值拷进静态数组即可，
     * 这样 API 保持 "const bench_kernel *" + 下标访问的简单形态 */
    static bench_kernel live[BENCH_KERNEL_SLOTS];
    size_t n = 0;
    for (size_t i = 0; i < BENCH_KERNEL_SLOTS; i++) {
        if (g_kernels_all[i] != NULL) live[n++] = *g_kernels_all[i];
    }
    if (count) *count = n;
    return live;
}

