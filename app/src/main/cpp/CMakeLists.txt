cmake_minimum_required(VERSION 3.22.1)
project("dynamo_engine" C CXX ASM)   # ASM：tinymembench 的 ARM64 NEON 例程是 .S

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

# ============================================================================
#  SPEC 结果需如实披露「编译工具链」
#
#  背景：官方 run rules 按 base/peak 调优等级分档上报，成绩脱离编译器与优化
#  选项无法比较；本移植版由 NDK 工具链以单一固定优化等级编译，与官方规则不同。
#  因此这里在 CMake **配置期**从真实生效的编译参数中提取信息写入编译期宏，
#  由 C++ 侧再与 __VERSION__ / __clang_version__ / 目标 ABI 宏 / __cplusplus 拼成字符串。
#  绝不在 Kotlin 侧硬编码 —— 硬编码必然与真实构建漂移。
# ============================================================================
string(TOUPPER "${CMAKE_BUILD_TYPE}" _DYNAMO_BT_UPPER)
set(_DYNAMO_FLAGS "${CMAKE_CXX_FLAGS}")
if(CMAKE_BUILD_TYPE)
    # 本项目把 -O3 放在 CMAKE_CXX_FLAGS（build.gradle 的 cppFlags），
    # 各 CONFIG 专属变量可能为空，因此两者都要计入。
    string(APPEND _DYNAMO_FLAGS " ${CMAKE_CXX_FLAGS_${_DYNAMO_BT_UPPER}}")
endif()
string(STRIP "${_DYNAMO_FLAGS}" _DYNAMO_FLAGS)

# 优化等级：取**最后一个** -O 选项。
# 关键：GCC/Clang 对同一选项是"后者覆盖前者"。本项目基础 -O3 在 CMAKE_CXX_FLAGS，
# 而 AGP 的 release 原生构建用 RelWithDebInfo，会在其后追加 "-O2 -g -DNDEBUG"，
# 因此 **Release 实际生效 -O2、Debug 实际生效 -O3**（ninja 编译命令实测）。
# 只看第一个 -O 会把 Release 误报成 -O3 —— 这正是必须在配置期从真实参数提取的原因。
separate_arguments(_DYNAMO_FLAG_LIST UNIX_COMMAND "${_DYNAMO_FLAGS}")
set(_DYNAMO_OPT_LEVEL "未显式指定")
foreach(_DYNAMO_F IN LISTS _DYNAMO_FLAG_LIST)
    if(_DYNAMO_F MATCHES "^-O([0-9sz]+|fast)$")
        set(_DYNAMO_OPT_LEVEL "${_DYNAMO_F}")
    elseif(_DYNAMO_F STREQUAL "-O")
        set(_DYNAMO_OPT_LEVEL "-O1")   # 裸 -O 等价 -O1
    endif()
endforeach()

# ============================================================================
#  -mcpu 分档：同一组源码产出多个定向调优的引擎库
#
#  · 只有 arm64-v8a 出非基线变体；armeabi-v7a 与 x86_64 只保留 baseline，
#    避免 APK 无谓膨胀（非基线变体对这两个 ABI 也没有意义）。
#  · 每个变体都**各自**挂 16KB 页对齐链接选项 —— 漏掉任何一个，
#    Android 15+ 在加载该变体时会直接失败（验收要求逐个校验 PT_LOAD align）。
#  · 每个变体把自己的 profile id 与 -mcpu 以编译期宏写死，
#    运行期无需猜测、Kotlin 侧也无需硬编码对应关系。
# ============================================================================
set(DYNAMO_SOURCES
    dynamo_engine.cpp
    native_bridge.cpp
    sandboxed_runner.cpp
)

# 分档表来自**单一事实源**（与 LLVM 23 实验性档位脚本共用同一份，避免双表漂移）
include("${CMAKE_CURRENT_LIST_DIR}/profiles.cmake")

# 逐档 -mcpu 目录串：由 CMake 在配置期生成，编译进每个变体。
# UI 要展示的"逐档元数据"必须来自这里，**不允许在 Kotlin 侧再抄一份表**，否则必然漂移。
# 分隔符用**逗号**：分号在 CMake 里是列表分隔符，会被拆成多个 -D 选项（实测 CXX1407）。
set(_DYNAMO_CATALOG "")
foreach(DYNAMO_P IN LISTS DYNAMO_ALL_PROFILES)
    string(APPEND _DYNAMO_CATALOG "${DYNAMO_P}=${DYNAMO_MCPU_${DYNAMO_P}},")
endforeach()

# 实验性档位列表（逗号分隔；供 UI 标"实验性"）
set(_DYNAMO_EXPERIMENTAL "")
foreach(DYNAMO_P IN LISTS DYNAMO_EXPERIMENTAL_PROFILES)
    string(APPEND _DYNAMO_EXPERIMENTAL "${DYNAMO_P},")
endforeach()

# 本 CMake 工程只出 NDK 档位。实验性档位（c1*）由 build_c1_llvm23.sh 用独立 LLVM 23 构建：
# 它们**必须**从 NDK 目标列表里剔除，否则 NDK 内置 Clang 21 会因不认识 -mcpu=c1-ultra 直接报错。
set(DYNAMO_PROFILES baseline)
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
# 2026-10-13 V3：专属调优取消，SoC 专属档不再参与构建（原先此处硬编码 x1 x2 x3 x4 x925）
endif()

# 工具链来源（编译期写入，供 compilerToolchain 如实披露"这一档是谁编的"）
if(DEFINED CMAKE_ANDROID_NDK)
    get_filename_component(_DYNAMO_NDK_VER "${CMAKE_ANDROID_NDK}" NAME)
else()
    set(_DYNAMO_NDK_VER "unknown")
endif()
set(_DYNAMO_CC_ORIGIN "NDK ${_DYNAMO_NDK_VER} 内置 Clang")


# ============================================================================
#  内置基准内核层 —— 来自 AndroidBenech/upstream 的裁剪移植
#   · CoreMark 上游算法文件逐字节未改：用 -Dmain=coremark_cli_main 把它的 main() 变成
#     普通函数。这些定义必须是 PRIVATE，绝不能泄漏到 App 其它源文件。
#   · tinymembench 的 aarch64-asm.S 按 GNU as 写，clang 集成汇编器不认 .func/.endfunc，
#     构建期生成去掉这两行的派生副本（上游文件保持逐字节不变）。
# ============================================================================
set(BENCH_DIR ${CMAKE_CURRENT_SOURCE_DIR}/bench)
set(BENCH_COREMARK ${BENCH_DIR}/coremark)
set(BENCH_SOURCES
    ${BENCH_DIR}/bench_common.c
    ${BENCH_DIR}/builtin_bench.cpp
    ${BENCH_DIR}/coremark/bench_coremark.c
    ${BENCH_COREMARK}/port/core_portme_android.c
    ${BENCH_COREMARK}/upstream/core_list_join.c
    ${BENCH_COREMARK}/upstream/core_main.c
    ${BENCH_COREMARK}/upstream/core_matrix.c
    ${BENCH_COREMARK}/upstream/core_state.c
    ${BENCH_COREMARK}/upstream/core_util.c
    ${BENCH_DIR}/stream/bench_stream.c
    ${BENCH_DIR}/tinymembench/bench_tinymembench.c
)
if(ANDROID_ABI STREQUAL "arm64-v8a" AND EXISTS ${BENCH_DIR}/tinymembench/aarch64-asm.S)
    set(_BENCH_TMB_ASM_GEN ${CMAKE_CURRENT_BINARY_DIR}/bench_tinymembench_aarch64.gen.S)
    add_custom_command(OUTPUT ${_BENCH_TMB_ASM_GEN}
        COMMAND sh -c "sed -E 's/^[[:space:]]*[.](func|endfunc).*$//' '${BENCH_DIR}/tinymembench/aarch64-asm.S' > '${_BENCH_TMB_ASM_GEN}'"
        DEPENDS ${BENCH_DIR}/tinymembench/aarch64-asm.S
        COMMENT "生成 NEON 汇编派生副本（去掉 .func/.endfunc）"
        VERBATIM)
    list(APPEND BENCH_SOURCES ${_BENCH_TMB_ASM_GEN})
endif()

add_library(bench_kernels OBJECT ${BENCH_SOURCES})
target_include_directories(bench_kernels PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}                 # builtin_kernels.h 在 cpp/ 根目录
    ${BENCH_DIR}/include ${BENCH_COREMARK}/upstream ${BENCH_COREMARK}/port)
target_compile_options(bench_kernels PRIVATE -D_GNU_SOURCE -O3 -fno-omit-frame-pointer -Wall)
set_target_properties(bench_kernels PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED ON)
target_compile_definitions(bench_kernels PRIVATE
    MAIN_HAS_NOARGC=0
    SEED_METHOD=SEED_ARG
    MEM_METHOD=MEM_MALLOC
    MULTITHREAD=8
    COREMARK_MAX_THREADS=8
    USE_PTHREAD=1
    HAS_FLOAT=1
    HAS_TIME_H=1
    HAS_STDIO=1
    HAS_PRINTF=0
    USE_CLOCK=0
    PRINT_ARGS=0
    BENCH_OPT_LEVEL=3
    main=coremark_cli_main
)
find_package(Threads REQUIRED)

foreach(DYNAMO_P IN LISTS DYNAMO_PROFILES)
    set(DYNAMO_TGT "dynamo_engine_${DYNAMO_P}")

    add_library(${DYNAMO_TGT} SHARED ${DYNAMO_SOURCES} $<TARGET_OBJECTS:bench_kernels>)
    target_link_libraries(${DYNAMO_TGT} android ${log-lib} Threads::Threads)

    target_compile_definitions(${DYNAMO_TGT} PRIVATE
        DYNAMO_BUILD_CONFIG="${CMAKE_BUILD_TYPE}"
        DYNAMO_BUILD_OPT_LEVEL="${_DYNAMO_OPT_LEVEL}"
        DYNAMO_BUILD_FLAGS="${_DYNAMO_FLAGS}"
        DYNAMO_PROFILE_ID="${DYNAMO_P}"
        DYNAMO_PROFILE_MCPU="${DYNAMO_MCPU_${DYNAMO_P}}"
        DYNAMO_PROFILE_CATALOG="${_DYNAMO_CATALOG}"
        DYNAMO_EXPERIMENTAL_PROFILES="${_DYNAMO_EXPERIMENTAL}"
        DYNAMO_PROFILE_EXPERIMENTAL="0"
        DYNAMO_CC_ORIGIN="${_DYNAMO_CC_ORIGIN}"
    )

    if(NOT DYNAMO_P STREQUAL "baseline")
        # 非基线：**-mtune**（只调调度）而非 -mcpu（会启用目标核独有 ISA 特性）。
        # 实测（NDK r30 / clang 21）同一段填充循环：-mcpu=cortex-x2 会生成 SVE 的
        # "index z0.s"（A510/A710 无 SVE → 调度到小核即 SIGILL，已在 Redmi SM7475 复现）；
        # -mtune=cortex-x2 不生成任何 SVE/FLAGM 指令。故全档位统一用 -mtune，全核安全。
        target_compile_options(${DYNAMO_TGT} PRIVATE "-mtune=${DYNAMO_MCPU_${DYNAMO_P}}")
    endif()

    target_link_options(${DYNAMO_TGT} PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
    )
endforeach()
