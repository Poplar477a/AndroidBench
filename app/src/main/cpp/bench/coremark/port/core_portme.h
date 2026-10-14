/*
 * core_portme.h —— AndroidBench 的 CoreMark 平台配置入口。
 *
 * 上游 coremark.h 会 #include "core_portme.h"，因此**必须由本目录提供该文件名**。
 * 这里只做两件事：引入上游 posix 头（逐字保留，仅改名），以及把输出重定向到我们自己的
 * 捕获函数 —— 其余配置项（MULTITHREAD / SEED_METHOD / MEM_METHOD 等）一律由构建系统以
 * 编译期宏注入（见 CMakeLists.txt），与本文件保持单一事实来源。
 */
#ifndef BENCH_CORE_PORTME_H
#define BENCH_CORE_PORTME_H

/*
 * 上游结果里会打印"内存位置"。默认值是占位串（"Please put data memory location here"），
 * 那种占位串出现在成绩里等于没披露，因此这里在包含上游头**之前**给出真实分配路径。
 * 注意必须是字符串字面量：上游头把它直接当 printf 参数用，传裸标识符会编译失败（实测踩过）。
 */
#define MEM_LOCATION "Heap (posix_memalign 64B via bench_alloc)"

#include "core_portme_posix.h"   /* 上游 posix/core_portme.h，逐字保留 */

/*
 * 上游 posix 头要求构建系统提供 FLAGS_STR（用于在结果里如实写明计分时的编译选项）。
 * 直接把带引号的宏塞进 CMake 很容易出错，因此这里统一转接到 BENCH_C_FLAGS_STR ——
 * 该宏由构建系统在配置期从真实生效的编译参数中提取后注入（见 CMakeLists.txt），
 * 绝不在源码里硬编码，否则必然与真实构建漂移。
 */
#ifndef BENCH_OPT_LEVEL
#define BENCH_OPT_LEVEL 0
#endif
#define BENCH_STR_(x) #x
#define BENCH_STR(x) BENCH_STR_(x)
#ifndef FLAGS_STR
#define FLAGS_STR "AndroidBench -O" BENCH_STR(BENCH_OPT_LEVEL) " -fno-omit-frame-pointer (NDK/Clang)"
#endif

/*
 * 本地改动：CoreMark 全程通过 ee_printf 汇报（分数、CRC、校验结论），
 * 而我们要在 JNI 层解析这些信息、且不能污染 stdout/日志，因此把它换成捕获实现。
 * 实现见 src/bench/coremark/bench_coremark.c。
 */
#undef ee_printf
int bench_ee_printf(const char *fmt, ...);
#define ee_printf bench_ee_printf

#endif /* BENCH_CORE_PORTME_H */

