# ============================================================================
#  AndroidBenech —— 原生基准内核构建脚本
#
#  交付形态：libandroidbench.so（由 Kotlin/JNI 调用；docs/ARCHITECTURE.md 明确否决
#  了"把 ELF 塞进 APK 再 exec"的旧模式）。
#  androidbench_cli 只是开发期验证驱动（宿主机 / adb push 到 /data/local/tmp），
#  用 -DBENCH_BUILD_CLI=OFF 即可从交付物中排除。
#
#  设计要点：
#   · 上游基准源码（coremark 的 core_*.c 等）逐字不改，平台适配全部在 port/ 与
#     bench_<id>.c 适配层里完成；编译期宏是唯一的配置入口。
#   · 内核用 weak 符号注册，任何内核未落地都不会导致整库链接失败。
#   · 16KB 页对齐：Android 15+ 要求 PT_LOAD 对齐 16KB，漏掉会导致加载失败。
# ============================================================================
cmake_minimum_required(VERSION 3.22.1)
project(androidbench C ASM)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# 基准内核必须开满优化：用 -O0 测出来的数字没有意义（也不允许对外上报）。
set(BENCH_C_FLAGS -D_GNU_SOURCE -O3 -fno-omit-frame-pointer -Wall -Wextra -Wno-unused-parameter)

option(BENCH_BUILD_CLI "构建开发验证用命令行驱动（不随 APK 交付）" ON)

set(BENCH_ROOT ${CMAKE_CURRENT_SOURCE_DIR})
set(COREMARK_DIR ${BENCH_ROOT}/src/bench/coremark)

find_package(Threads REQUIRED)

# 从真实编译参数里提取优化等级（取最后一个 -O 选项，Clang 是后者覆盖前者），
# 供 CoreMark 在结果里如实披露"这一份成绩是在什么优化等级下得到的"。
string(REGEX MATCHALL "-O[0-9sz]+|-Ofast" _BENCH_OPT_ALL "${BENCH_C_FLAGS}")
list(LENGTH _BENCH_OPT_ALL _BENCH_OPT_N)
if(_BENCH_OPT_N GREATER 0)
    list(GET _BENCH_OPT_ALL -1 _BENCH_OPT_LAST)
else()
    set(_BENCH_OPT_LAST "-O2")
endif()
string(REGEX MATCH "[0-9]+" _BENCH_OPT_LEVEL "${_BENCH_OPT_LAST}")
if(NOT _BENCH_OPT_LEVEL)
    set(_BENCH_OPT_LEVEL 2)
endif()
message(STATUS "CoreMark 将披露的优化等级: -O${_BENCH_OPT_LEVEL}")

# ---------------------------------------------------------------------------
#  CoreMark：上游算法文件零改动
#  上游 core_main.c 自带 main()，这里用 -Dmain=coremark_cli_main 把它变成普通函数，
#  从而无需改动任何一个字节的上游源码即可嵌入调用。
# ---------------------------------------------------------------------------
add_library(coremark_upstream OBJECT
    ${COREMARK_DIR}/upstream/core_list_join.c
    ${COREMARK_DIR}/upstream/core_main.c
    ${COREMARK_DIR}/upstream/core_matrix.c
    ${COREMARK_DIR}/upstream/core_state.c
    ${COREMARK_DIR}/upstream/core_util.c
    ${COREMARK_DIR}/port/core_portme_android.c
)
target_include_directories(coremark_upstream PUBLIC
    ${BENCH_ROOT}/include
    ${COREMARK_DIR}/upstream
    ${COREMARK_DIR}/port
)
target_compile_options(coremark_upstream PRIVATE ${BENCH_C_FLAGS})
target_compile_definitions(coremark_upstream PUBLIC
    MAIN_HAS_NOARGC=0
    SEED_METHOD=SEED_ARG
    MEM_METHOD=MEM_MALLOC
    MULTITHREAD=8
    COREMARK_MAX_THREADS=8
    USE_PTHREAD=1
    HAS_FLOAT=1
    HAS_TIME_H=1
    HAS_STDIO=1
    # 必须为 0：上游 coremark.h 在 HAS_PRINTF=1 时会自己 #define ee_printf printf，
    # 那样分数与 CRC 直接打到 stdout，我们的捕获层与解析器就全废了（实测踩过）。
    HAS_PRINTF=0
    USE_CLOCK=0
    PRINT_ARGS=0
    main=coremark_cli_main
    # 计分时必须如实披露优化等级：只传纯数字宏，字符串在头文件里拼（避免引号/空格被生成器拆坏）
    BENCH_OPT_LEVEL=${_BENCH_OPT_LEVEL}
)

# ---------------------------------------------------------------------------
#  主库
# ---------------------------------------------------------------------------
add_library(androidbench SHARED
    src/bench_common.c
    src/bench/coremark/bench_coremark.c
    $<TARGET_OBJECTS:coremark_upstream>
)
target_include_directories(androidbench PUBLIC ${BENCH_ROOT}/include)
target_compile_options(androidbench PRIVATE ${BENCH_C_FLAGS})
target_compile_definitions(androidbench PRIVATE COREMARK_MAX_THREADS=8)
target_link_libraries(androidbench PRIVATE coremark_upstream Threads::Threads)

# 可选内核：按 BENCH_KERNELS 列表编入。默认全编；开发期（某个内核还没落地/正在改）可以
# 用 -DBENCH_KERNELS=coremark;stream 只编其中几个，保证主线永远可编译、可验证。
set(BENCH_KERNELS "coremark;stream;tinymembench" CACHE STRING "要编入的内核 id 列表")
foreach(_pair "stream=src/bench/stream/bench_stream.c"
              "tinymembench=src/bench/tinymembench/bench_tinymembench.c")
    string(REPLACE "=" ";" _kv "${_pair}")
    list(GET _kv 0 _kid)
    list(GET _kv 1 _kfile)
    list(FIND BENCH_KERNELS "${_kid}" _kid_idx)
    if(_kid_idx GREATER -1 AND EXISTS "${BENCH_ROOT}/${_kfile}")
        target_sources(androidbench PRIVATE "${BENCH_ROOT}/${_kfile}")
        message(STATUS "编入内核: ${_kid}")
        # tinymembench 在 ARM64 上走上游的 NEON 汇编路径，需要一并编入 .S
        if(_kid STREQUAL "tinymembench"
           AND ANDROID_ABI STREQUAL "arm64-v8a"
           AND EXISTS "${BENCH_ROOT}/src/bench/tinymembench/aarch64-asm.S")
            # 上游 aarch64-asm.S 是按 GNU as 写的，带 .func/.endfunc；clang 的集成汇编器
            # 不认这两条指示符（实测 "unknown directive"）。这里在构建期生成一份派生副本，
            # 把这两行去掉 —— 上游文件保持逐字节不变，改动在构建脚本里显式可见、可复现。
            set(_tmb_asm_src "${BENCH_ROOT}/src/bench/tinymembench/aarch64-asm.S")
            set(_tmb_asm_gen "${CMAKE_CURRENT_BINARY_DIR}/tinymembench_aarch64_asm.gen.S")
            add_custom_command(
                OUTPUT "${_tmb_asm_gen}"
                COMMAND sh -c "sed -E 's/^[[:space:]]*[.](func|endfunc).*$//' '${_tmb_asm_src}' > '${_tmb_asm_gen}'"
                DEPENDS "${_tmb_asm_src}"
                COMMENT "生成去掉 .func/.endfunc 的 NEON 汇编副本（适配 clang 集成汇编器）"
                VERBATIM)
            target_sources(androidbench PRIVATE "${_tmb_asm_gen}")
            message(STATUS "  + 上游 NEON 汇编路径: aarch64-asm.S（构建期派生副本）")
        endif()
    else()
        message(STATUS "未编入内核: ${_kid}（不在 BENCH_KERNELS 中，或源文件不存在）")
    endif()
endforeach()

if(ANDROID)
    # Android 15+ / 16KB 页：不显式指定会让 .so 在某些设备上直接加载失败
    target_link_options(androidbench PRIVATE "-Wl,-z,max-page-size=16384")
endif()

# ---------------------------------------------------------------------------
#  开发期验证驱动
# ---------------------------------------------------------------------------
if(BENCH_BUILD_CLI)
    add_executable(androidbench_cli tools/bench_cli.c)
    target_compile_options(androidbench_cli PRIVATE ${BENCH_C_FLAGS})
    target_link_libraries(androidbench_cli PRIVATE androidbench)
endif()

