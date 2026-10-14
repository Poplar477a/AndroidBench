#!/usr/bin/env bash
# ============================================================================
#  实验性档位构建（非 NDK 内置工具链）
#
#    llvm23 → 独立 LLVM 23.1.3      → C1 系列（c1ultra…），NDK 内置 Clang 21 不支持
#    sdllvm → Snapdragon LLVM 19.0.0 → Oryon 系列（oryon2/oryon1），前两者都不支持
#
#  路线（Lead 实测通过，照此实现）：
#    · LLVM 23：单阶段 clang++ 直接编译+链接；需幂等补内建库 symlink（LLVM 23 独立装只带 host 版）
#    · SDLLVM ：两阶段 —— 用 SDLLVM clang++ 逐源文件 -c，
#               再用 **NDK 的 clang++/lld** 链接（SDLLVM 自带 ld.lld 依赖旧 soname libxml2.so.2，
#               而 Ubuntu 新包只给 libxml2.so.16；链接阶段不依赖 -mcpu，换链接器即可）
#    · 两者都必须 -fPIC（漏了报 R_AARCH64_ADR_PREL_PG_HI21），并挂 16KB 页对齐
#    · 定向调优一律用 **-mtune**（只影响指令调度），**绝不用 -mcpu**：
#      -mcpu 会启用目标核独有的 ISA 特性（如 cortex-x2 的 +flagm/+sve2），
#      而 Android 调度器会把线程扔到任意核心，小核执行到这些指令就是 SIGILL
#      （已在 Redmi SM7475 上实测复现）。-mtune 不启用新特性，故全核安全。
#
#  产物：app/src/main/jniLibs/arm64-v8a/libdynamo_engine_<profile>.so（AGP 按 jniLibs 打包）
#  分档表来自 app/src/main/cpp/profiles.cmake —— 单一事实源，脚本只解析不抄表。
#  工具链缺失时打印警告并跳过（不阻断 NDK 档位构建）；自检不合格则非零退出（不静默）。
# ============================================================================
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"

LLVM23_DIR="${LLVM23_DIR:-$REPO_ROOT/.tools/llvm23}"
SDLLVM_DIR="${SDLLVM_DIR:-/opt/qcom/Qualcomm_Snapdragon_LLVM_ARM_Toolchain_OEM/19.0.0.0}"
NDK_DIR="${NDK_DIR:-$REPO_ROOT/.tools/android-sdk/ndk/30.0.16248370}"
SYSROOT="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
NDK_CXX="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++"
NDK_BUILTINS_DIR="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/21/lib/linux"
READELF="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
STRIP="${STRIP:-$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip}"
PROFILES_CMAKE="$SCRIPT_DIR/profiles.cmake"
OUT_DIR="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
OBJ_DIR="$REPO_ROOT/.tools/tmp/experimental_obj"
SOURCES=(dynamo_engine.cpp native_bridge.cpp builtin_compiler_sim.cpp builtin_fluid.cpp builtin_graph.cpp builtin_ntt.cpp)
LANDROID_ARGS=(-landroid -llog)

# 真实构建参数（单一来源）：优化等级由本脚本显式传给编译器，披露串与之同源。
# BUILD_CONFIG 由 build.sh 按实际 Gradle 任务传入（assembleDebug/assembleRelease），
# 拿不到时标注 Unknown —— 不冒充某个构建类型。
BUILD_CONFIG="${DYNAMO_BUILD_CONFIG:-Unknown}"

# 优化等级必须与同构建类型的 NDK 档位保持一致，否则跨档位对比会同时混入
# 「编译器不同」与「优化等级不同」两个变量，结论不可解释。
#   NDK 侧实测：Debug = -O3（基础 -O3 未被覆盖）
#                Release = -O2（AGP 的 RelWithDebInfo 追加 -O2 覆盖基础 -O3）
# 故实验档同样按构建类型取 -O2 / -O3，而不是固定 -O3。
# 允许用环境变量 OPT_LEVEL 显式覆盖（联调/实验用）。
if [ -z "${OPT_LEVEL:-}" ]; then
    case "$BUILD_CONFIG" in
        Release) OPT_LEVEL="-O2" ;;
        *)       OPT_LEVEL="-O3" ;;
    esac
fi

# ---------------------------------------------------------------------------
#  骁龙（sdllvm）重优化参数集
#
#  约束（ISA 规则）：常规档**只用 -mtune**（不改变指令集要求），否则调度器把线程扔到小核就是
#  SIGILL（已在 SM7475 实测复现）；只有 profiles.cmake 里登记 set(DYNAMO_ISA_<p> "allbig") 的档位
#  允许 -mcpu，且必须由应用的**全大核运行期门禁**放行。
#
#  这一组只影响「调用/布局/常量合并」，不改 ISA：
#    -fno-semantic-interposition  shared lib 内允许直接调用，省掉 PLT 间接跳转
#    -fno-plt                     调用点内联 GOT 载入，减少一次跳转
#    -ffunction-sections/-fdata-sections + --gc-sections  丢弃未引用代码
#    -fvisibility=hidden/-fvisibility-inlines-hidden      缩小动态符号表
#                                                         （JNI 入口有 JNIEXPORT，仍是 default）
#    -fomit-frame-pointer         arm64 下释放一个通用寄存器
#    -fno-math-errno/-fno-trapping-math                   纯计算负载可安全放宽
#    -fmerge-all-constants        合并相同常量，减小只读段
#    -Wl,--icf=safe               安全级别相同代码折叠（不用 =all，避免函数指针比较出错）
#
#  关于「库替换」的查证结论（**必须如实披露，不得声称已用**）：
#    SDLLVM 19.0.0 确实随包提供 libclang_rt.optlibc-krait-android.a（高通优化的 libc 替换库），
#    但实测该归档为 **ELF32 / Machine=ARM**，即面向 32 位 Krait（armv7）目标；
#    本项目的 Oryon 档位全部是 **aarch64**，无法链接该 32 位归档。
#    因此"库替换"在 arm64 上没有可用的高通专有库，本项目继续使用
#    -static-libstdc++（NDK 的 libc++ 静态库）+ NDK compiler-rt；
#    这一点会写进产物的编译参数披露串，避免对外宣称不存在的优化。
# ---------------------------------------------------------------------------
SD_TUNING_FLAGS=(
    -fno-semantic-interposition
    -fno-plt
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -fomit-frame-pointer
    -fno-math-errno
    -fno-trapping-math
    -fmerge-all-constants
)
SD_LINK_FLAGS=(
    -Wl,--gc-sections
    -Wl,--icf=safe
)

# ---- PGO 开关（**默认关闭**；没有真实 profile 数据时绝不假装启用）----
#   DYNAMO_PGO_GENERATE=1  产出插桩库（-fprofile-generate），用于在真机采集
#   DYNAMO_PGO_PROFILE=... 指定 .profdata，产出使用档（-fprofile-use）
#   两者互斥；都没设时走普通优化路径，并在披露串里写明"未应用 PGO"。
PGO_GENERATE="${DYNAMO_PGO_GENERATE:-0}"
PGO_PROFILE="${DYNAMO_PGO_PROFILE:-}"
PGO_STATE="未应用（无 profile 数据）"
# hook 宏只能给**插桩**构建：-fprofile-use 应用档不链接 profile 运行时，
# 一旦带强引用就 dlopen 失败（cannot locate symbol __llvm_profile_set_filename），
# 应用会静默回退 baseline —— 真机实测教训。
# 不能用 ${pgo_applied:+...}：pgo_applied=0 时它是非空字符串，条件照样成立！
PGO_HOOK_DEF=""
if [ "$PGO_GENERATE" = "1" ]; then PGO_HOOK_DEF="-DDYNAMO_PGO_HOOK=1"; fi

PGO_FLAGS=()
if [ "$PGO_GENERATE" = "1" ]; then
    PGO_FLAGS=(-fprofile-generate -fprofile-update=atomic)
    PGO_STATE="插桩采集构建（-fprofile-generate）"
elif [ -n "$PGO_PROFILE" ] && [ -f "$PGO_PROFILE" ]; then
    PGO_FLAGS=(-fprofile-use="$PGO_PROFILE" -fprofile-correction -Wno-profile-instr-out-of-date)
    PGO_STATE="已应用 PGO（$PGO_PROFILE）"
elif [ -n "$PGO_PROFILE" ]; then
    # 注意：这里**不能**调 log()（它定义在下一行之后，此刻尚未存在，
    # 直接调用会变成 "log: command not found" 并把这条诚实回退提示吞掉）。
    printf '[exp-variant] 警告：DYNAMO_PGO_PROFILE=%s 不存在 —— 本次按**未应用 PGO** 构建\n' "$PGO_PROFILE"
fi
log() { printf '[exp-variant] %s\n' "$*"; }

# ---- LTO 开关（**默认关闭**；LTO 会改变代码布局，必须与 PGO 分开评估）----
#   DYNAMO_LTO=thin | full  → 追加 -flto=<mode> 并在链接时用 -Wl,-O2
LTO_MODE="${DYNAMO_LTO:-}"
LTO_FLAGS=()
LTO_STATE="未应用"
# ---- ISA 优化开关（**默认关闭**；只允许面向"全大核"处理器）----
#   DYNAMO_ISA=allbig → 把 -mtune=<x> 升级为 -mcpu=<x>（真正的 ISA 级优化）。
#   **为什么默认关**：-mcpu 会启用该核独有的指令；若 SoC 还有小核、或线程会被调度到
#   不同代的核心上，就会 SIGILL（本项目已在 SM7475 复现过同类问题）。只有"全大核"
#   （所有核心同代，例如 Snapdragon 8 Elite 系列的 Oryon）才允许开启。
#   披露串会写明 ISA 状态，界面/报告据此标注"全大核专用"。
ISA_MODE="${DYNAMO_ISA:-}"
ISA_STATE="未启用（仅 -mtune 调度调优，可安全运行于同 SoC 全部核心）"
# PGO 一致性修复：**必须**用 SDLLVM 自己的 profile 运行时。
# 原因（真机实测）：链接走的是 NDK 驱动，它会自动追加 *NDK* 的 libclang_rt.profile，
# 于是插桩由 SDLLVM、运行时却是 NDK 那一代 —— 采集到的 .profraw raw 版本 2573，
# SDLLVM/NDK/LLVM23 三家的 llvm-profdata 全都读不了。
# 把 SDLLVM 的运行时**排在前面**，让 __llvm_profile_runtime 从它解析。
# 调优实验用：额外编译参数原样透传（会写进披露串，便于回溯是哪次实验）。
# 例：DYNAMO_EXTRA_FLAGS="-fno-math-errno -fno-trapping-math -ffp-contract=fast"
EXTRA_TUNE_FLAGS=${DYNAMO_EXTRA_FLAGS:-}

SDLLVM_PROFILE_RT="$SDLLVM_DIR/lib/clang/19/lib/linux/libclang_rt.profile-aarch64-android.a"
if [ -n "$LTO_MODE" ]; then
    LTO_FLAGS=("-flto=$LTO_MODE" "-Wl,-O2")
    LTO_STATE="已应用（-flto=$LTO_MODE）"
fi
log "PGO 状态：$PGO_STATE"

# ---- 解析 profiles.cmake（单一事实源）----
catalog=""
declare -A MCPU TOOLCHAIN ISA
while IFS= read -r line; do
    if [[ "$line" =~ ^set\(DYNAMO_MCPU_([a-z0-9]+)[[:space:]]+\"([^\"]+)\" ]]; then
        MCPU["${BASH_REMATCH[1]}"]="${BASH_REMATCH[2]}"
        catalog+="${BASH_REMATCH[1]}=${BASH_REMATCH[2]},"
    elif [[ "$line" =~ ^set\(DYNAMO_TOOLCHAIN_([a-z0-9]+)[[:space:]]+\"([^\"]+)\" ]]; then
        TOOLCHAIN["${BASH_REMATCH[1]}"]="${BASH_REMATCH[2]}"
    elif [[ "$line" =~ ^set\(DYNAMO_ISA_([a-z0-9]+)[[:space:]]+\"([^\"]+)\" ]]; then
        # 单档属性：allbig = 该档为全大核 ISA 专用（运行期门禁必须放行才可加载）
        ISA["${BASH_REMATCH[1]}"]="${BASH_REMATCH[2]}"
    fi
done < "$PROFILES_CMAKE"

exp_block=$(sed -n '/set(DYNAMO_EXPERIMENTAL_PROFILES/,/)/p' "$PROFILES_CMAKE" \
    | sed 's/#.*$//' | tr -d '()"' | tr '\n' ' ')
experimental_list=""
for tok in $exp_block; do
    case "$tok" in
        set*|DYNAMO_*|"") ;;
        *) experimental_list+="$tok," ;;
    esac
done

if [ -z "$catalog" ] || [ -z "$experimental_list" ]; then
    log "警告：profiles.cmake 解析失败（catalog/experimental 为空）—— 跳过实验性档位"
    exit 0
fi
log "catalog=$catalog"
log "experimental=$experimental_list"
# ---- 档位列表覆盖（PGO 试点要在 NDK 档位（如 x2）上做插桩/应用）----
# 否则试点会把 x2 标成实验档，界面与披露都会失真。
exp_original="$experimental_list"
if [ -n "${DYNAMO_EXPERIMENTAL_OVERRIDE:-}" ]; then
    experimental_list="${DYNAMO_EXPERIMENTAL_OVERRIDE}"
    log "档位列表被 DYNAMO_EXPERIMENTAL_OVERRIDE 覆盖：$experimental_list（目录未变）"
fi


# ---- LLVM 23 内建库路径幂等补齐 ----
ensure_llvm23_builtins() {
    local d="$LLVM23_DIR/lib/clang/23/lib/aarch64-unknown-linux-android23"
    mkdir -p "$d"
    ln -sfn "$NDK_BUILTINS_DIR/libclang_rt.builtins-aarch64-android.a" "$d/libclang_rt.builtins.a"
    ln -sfn "$NDK_BUILTINS_DIR/aarch64/libunwind.a" "$d/libunwind.a"
}

mkdir -p "$OUT_DIR" "$OBJ_DIR"
fail=0

for profile in ${experimental_list//,/ }; do
    [ -z "$profile" ] && continue
    mcpu="${MCPU[$profile]:-}"
    tc="${TOOLCHAIN[$profile]:-}"
    # 工具链覆盖（试点用）：只改"本次用哪支编译器"，不动 profiles.cmake 里声明的归属。
    # 目的：让插桩、采集、合并、-fprofile-use 四步落在**同一代** LLVM 上 ——
    # 实测 NDK r30 的运行时写的 raw 版本(2573)与它自带 llvm-profdata(期望 10) 不一致，
    # 混代必然死在合并这一步。
    if [ -n "${DYNAMO_TOOLCHAIN_OVERRIDE:-}" ]; then
        tc="${DYNAMO_TOOLCHAIN_OVERRIDE}"
        log "工具链被 DYNAMO_TOOLCHAIN_OVERRIDE 覆盖：$profile -> $tc"
    fi

    [ -z "$mcpu" ] && { log "警告：profiles.cmake 里没有 $profile 的 -mtune 目标，跳过"; continue; }
    out="$OUT_DIR/libdynamo_engine_${profile}.so"
    # 编译参数披露：**与真正传给编译器的 flags 同源**（单一变量展开），
    # 不硬编码第二份，避免"披露说 -O3、实际编 -O2"这类漂移。
    # 注意：实验档由本脚本直接驱动编译器，拿不到 CMAKE_CXX_FLAGS_<CONFIG>，
    # 因此这里显式构造并同时用于「编译」与「披露」两处。
    opt_level="$OPT_LEVEL"
    base_flags="-std=c++17 $opt_level -fexceptions -frtti"
    # ISA：以 profiles.cmake 的 DYNAMO_ISA_<profile> 为准（单一事实源），
    # 环境变量 DYNAMO_ISA=allbig 仅作实验性全局覆盖。
    isa_mode="${ISA[$profile]:-${ISA_MODE}}"
    isa_def='-DDYNAMO_PROFILE_ISA_REQ="none"'
    if [ "$isa_mode" = "allbig" ]; then
        # 全大核专用：-mcpu 启用该微架构的完整 ISA。
        # **必须**由运行期门禁（全大核判定）放行才允许加载 —— 否则线程落到小核即 SIGILL。
        compile_flags="$base_flags -fPIC -mcpu=$mcpu"
        ISA_STATE="已启用 -mcpu=$mcpu（全大核 ISA 档；非全大核设备拒绝加载）"
        isa_def='-DDYNAMO_PROFILE_ISA_REQ="allbig"'
    else
        compile_flags="$base_flags -fPIC -mtune=$mcpu"
    fi
    # 额外调优参数（可能为空）
    [ -n "$EXTRA_TUNE_FLAGS" ] && compile_flags="$compile_flags $EXTRA_TUNE_FLAGS"
    # 骁龙档位在基线之上追加一组不改 ISA 的重优化参数 + PGO（若已采集到 profile）。
    # 披露串用的就是**真正传下去的那一份**，不做第二次拼接。
    extra_flags=""
    # PGO 参数目前只在 sdllvm（Oryon）档位生效；后面「插桩自检」必须按**每个档位
    # 实际是否拿到 PGO 参数**来判定，否则 llvm23 档位（c1ultra 不带 PGO）会被
    # 误判成"插桩构建但运行时没链入"而整脚本非零退出（实测：PGO_GENERATE=1 时
    # c1ultra 必然触发该误判，导致 oryon 档位虽已产出、脚本却报失败）。
    # PGO 生效范围：sdllvm（Oryon）档位一直生效；其它工具链仅当显式
    # DYNAMO_PGO_SCOPE=all 时生效（PGO 试点需要），并让插桩自检按各档实际情况判定。
    pgo_applied=0
    if [ "$tc" = "sdllvm" ]; then
        extra_flags=" ${SD_TUNING_FLAGS[*]}"
    fi
    if [ "${#PGO_FLAGS[@]}" -gt 0 ] && { [ "$tc" = "sdllvm" ] || [ "${DYNAMO_PGO_SCOPE:-}" = "all" ]; }; then
        extra_flags="$extra_flags ${PGO_FLAGS[*]}"
        pgo_applied=1
    fi
    disclose_flags="[$compile_flags$extra_flags] PGO=$PGO_STATE LTO=$LTO_STATE ISA=$ISA_STATE"

    # 只有**原本就在实验档名单里**的档位才标 experimental=1；
    # 被 OVERRIDE 拉进来做试点的常规档位保持 0，披露不得失真。
    is_exp=0
    case ",${exp_original}," in *",$profile,"*) is_exp=1 ;; esac

    common_defs=(
        -DDYNAMO_PROFILE_ID="\"$profile\""
        -DDYNAMO_PROFILE_MCPU="\"$mcpu\""
        -DDYNAMO_PROFILE_EXPERIMENTAL="\"$is_exp\""
        -DDYNAMO_PROFILE_CATALOG="\"$catalog\""
        -DDYNAMO_EXPERIMENTAL_PROFILES="\"$experimental_list\""
        # 与 NDK 档位同等的四项披露：编译器(CC_ORIGIN) / 优化等级 / 编译参数 / 目标 ABI(编译期宏)
        -DDYNAMO_BUILD_CONFIG="\"$BUILD_CONFIG\""
        -DDYNAMO_BUILD_OPT_LEVEL="\"$opt_level\""
        -DDYNAMO_BUILD_FLAGS="\"$disclose_flags\""
        # 插桩构建才注入：让 native_bridge.cpp 用强引用调用 profile 运行时
        # （弱引用在共享库下会解析成 nullptr，实测导致 flush 静默失效）
        ${PGO_HOOK_DEF:+"$PGO_HOOK_DEF"}
        # ISA 要求（none/allbig）：供应用侧门禁与披露核对
        ${isa_def:+"$isa_def"}
    )

    case "$tc" in
      llvm23)
        if [ ! -x "$LLVM23_DIR/bin/clang++" ]; then
            log "警告：未找到 LLVM 23（$LLVM23_DIR）—— 跳过 $profile"
            continue
        fi
        ensure_llvm23_builtins
        ver=$("$LLVM23_DIR/bin/clang++" --version | head -1 | awk '{print $3}')
        origin="独立 LLVM $ver（非 NDK 内置，实验性）"
        log "编译 profile=$profile -mtune=$mcpu 工具链=LLVM $ver"
        ( cd "$SCRIPT_DIR" && "$LLVM23_DIR/bin/clang++" \
            --target=aarch64-linux-android23 --sysroot="$SYSROOT" \
            $compile_flags $extra_flags ${LTO_FLAGS[@]+"${LTO_FLAGS[@]}"} \
            "${common_defs[@]}" \
            -DDYNAMO_CC_ORIGIN="\"$origin\"" \
            -shared -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
            -static-libstdc++ \
            "${LANDROID_ARGS[@]}" -o "$out" "${SOURCES[@]}" ) \
            || { log "错误：$profile 编译失败"; fail=1; continue; }
        ;;
      sdllvm)
        if [ ! -x "$SDLLVM_DIR/bin/clang++" ]; then
            log "警告：未找到 SDLLVM（$SDLLVM_DIR）—— 跳过 $profile"
            continue
        fi
        if [ ! -x "$NDK_CXX" ]; then
            log "警告：未找到 NDK clang++（$NDK_CXX）—— 跳过 $profile"
            continue
        fi
        sdver=$("$SDLLVM_DIR/bin/clang++" --version | head -1 | awk '{print $3}')
        origin="Qualcomm Snapdragon LLVM ARM Compiler $sdver（非 NDK 内置，实验性）"
        log "编译 profile=$profile -mtune=$mcpu 工具链=SDLLVM $sdver（链接用 NDK lld）"
        objs=()
        ok=1
        for src in "${SOURCES[@]}"; do
            obj="$OBJ_DIR/${profile}_${src%.cpp}.o"
            ( cd "$SCRIPT_DIR" && "$SDLLVM_DIR/bin/clang++" \
                --target=aarch64-linux-android23 --sysroot="$SYSROOT" \
                $compile_flags ${LTO_FLAGS[@]+"${LTO_FLAGS[@]}"} \
                "${SD_TUNING_FLAGS[@]}" \
                ${PGO_FLAGS[@]+"${PGO_FLAGS[@]}"} \
                "${common_defs[@]}" -DDYNAMO_CC_ORIGIN="\"$origin\"" \
                -c "$src" -o "$obj" ) || { log "错误：$profile/$src 编译失败"; ok=0; break; }
            objs+=("$obj")
        done
        if [ "$ok" != "1" ]; then fail=1; continue; fi
        # 链接改用 NDK 工具链（-mtune 不参与链接，SDLLVM 自带 ld.lld 有 soname 问题）
        # 注意：续行命令里**不能**放 # 注释 —— bash 的词法会把命令截断，
        # 表现为"链接失败"而看不出原因（本轮踩过）。注释一律写在命令之外。
        # 链接用 NDK 驱动：它会自行追加其 profile 运行时；实测那支运行时写的 raw 版本是 2573，
        # 而 SDLLVM 19 / NDK r30 / LLVM 23 的 llvm-profdata 分别只认 10/10/11 ——
        # 这是当前 PGO **合并**环节的已知阻塞（采集本身已跑通，见 .evidence/PGO_STATUS.md）。
        # 备选（待验证）：改成只链 SDLLVM 自带运行时，让版本回到 10：
        #   ${PGO_FLAGS[@]+"$SDLLVM_PROFILE_RT"}
        "$NDK_CXX" --target=aarch64-linux-android23 --sysroot="$SYSROOT" \
            -shared -fPIC -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
            "${SD_LINK_FLAGS[@]}" \
            ${LTO_FLAGS[@]+"${LTO_FLAGS[@]}"} \
            ${PGO_FLAGS[@]+"${PGO_FLAGS[@]}"} \
            -static-libstdc++ \
            "${objs[@]}" "${LANDROID_ARGS[@]}" -o "$out" \
            || { log "错误：$profile 链接失败"; fail=1; continue; }
        ;;
      ndk)
        if [ ! -x "$NDK_CXX" ]; then
            log "警告：未找到 NDK clang++（$NDK_CXX）—— 跳过 $profile"
            continue
        fi
        ndkver=$("$NDK_CXX" --version | head -1 | awk '{print $3}')
        origin="NDK 30.0.16248370 内置 Clang $ndkver"
        log "编译 profile=$profile -mtune=$mcpu 工具链=$origin"
        ( cd "$SCRIPT_DIR" && "$NDK_CXX" \
            --target=aarch64-linux-android26 --sysroot="$SYSROOT" \
            $compile_flags $extra_flags ${LTO_FLAGS[@]+"${LTO_FLAGS[@]}"} \
            "${common_defs[@]}" \
            -DDYNAMO_CC_ORIGIN="\"$origin\"" \
            -shared -fPIC -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
            -static-libstdc++ \
            "${LANDROID_ARGS[@]}" -o "$out" "${SOURCES[@]}" ) \
            || { log "错误：$profile 编译失败"; fail=1; continue; }
        ;;
      *)
        log "警告：$profile 的工具链未声明（DYNAMO_TOOLCHAIN_$profile）—— 跳过"
        continue
        ;;
    esac

    # ---- 修复：实验档此前会依赖 libc++_shared.so，而 APK 里没有它（我们用 c++_static 换 16KB 对齐），
    #      → dlopen 必然失败。这里静态链接 libc++，并**必须 strip**（libc++_static.a 自带调试信息，
    #      不 strip 体积会从 ~1MB 涨到 ~5.6MB；NDK 档位由 AGP 自动 strip，实验档绕过了那一步）。
    size_raw=$(stat -c%s "$out" 2>/dev/null || echo 0)
    needed=$("$READELF" -d "$out" 2>/dev/null | grep -o "libc\+\+_shared\.so" | head -1)
    if [ -n "$needed" ]; then
        log "错误：$profile 仍依赖 libc++_shared.so（APK 内不存在该库，dlopen 必失败）"
        fail=1
        continue
    fi
    if [ -x "$STRIP" ]; then
        "$STRIP" --strip-unneeded "$out" 2>/dev/null || log "警告：strip 失败（不阻断，但体积会偏大）"
    else
        log "警告：未找到 llvm-strip（$STRIP），跳过 strip（体积会偏大）"
    fi

    size=$(stat -c%s "$out" 2>/dev/null || echo 0)
    if [ "$size" -lt 10000 ]; then log "错误：$out 体积异常（$size）"; fail=1; continue; fi
    sizes_note="strip 前 $size_raw → 后 $size 字节"
    jni_syms=$(grep -a -c "Java_com_ufs_bench_core_DynamoNativeBridge_" "$out" 2>/dev/null || true)
    aligns=$("$READELF" -l "$out" | awk '/^  LOAD/{print $NF}' | sort -u | tr '\n' ' ')
    ok_align=1
    for a in $aligns; do [ "$a" = "0x4000" ] || ok_align=0; done
    has_profile=$(grep -a -c "engine_profile=" "$out" 2>/dev/null || true)
    # 门禁（本轮教训）：**非插桩**产物绝不能引用 profile 运行时符号 ——
    # 那会让 dlopen 直接失败，应用静默回退 baseline（实测 active=baseline fallback=true）。
    if [ "$PGO_GENERATE" != "1" ]; then
        undef_prof=$("$READELF" --dyn-syms "$out" 2>/dev/null | grep -c "UND.*__llvm_profile" || true)
        if [ "${undef_prof:-0}" != "0" ]; then
            log "错误：$profile 非插桩构建却引用 profile 运行时符号（$undef_prof 处）—— dlopen 必失败"
            fail=1; continue
        fi
    fi

    # PGO 自检：插桩构建必须把 profile 运行时**链进来**（否则引用是 UND，
    # dlopen 直接失败）。这里查符号是否为已定义（T/W/t 段），只看 UND 会漏。
    # 判据用 **profile 运行时自带的数据段与特征串**，而不是符号名：
    #   · strip --strip-unneeded 会删掉局部符号，靠 __llvm_profile_* 符号名判断必然误判为"没链"；
    #   · 运行时一旦真正链入，二进制里必定出现 __llvm_prf_* 段名与 LLVM_PROFILE_FILE 字面量。
    # 阳性对照：未插桩产物命中数为 0，故此判据能区分"真链入"与"引用未解析"。
    if [ "$PGO_GENERATE" = "1" ] && [ "$pgo_applied" = "1" ]; then
        prf_cnt=$(grep -a -c "__llvm_prf_cnts" "$out" 2>/dev/null || true)
        prf_env=$(grep -a -c "LLVM_PROFILE_FILE" "$out" 2>/dev/null || true)
        if [ "$prf_cnt" = "0" ] || [ "$prf_env" = "0" ]; then
            log "错误：$profile 是插桩构建，但 profile 运行时未真正链入（__llvm_prf_cnts=$prf_cnt, LLVM_PROFILE_FILE=$prf_env）"
            fail=1
            continue
        fi
        log "  插桩自检：profile 运行时已链入（__llvm_prf_cnts=$prf_cnt, LLVM_PROFILE_FILE=$prf_env）"
    elif [ "$PGO_GENERATE" = "1" ]; then
        log "  插桩自检：本档位未应用 PGO（工具链 $tc 不在 PGO 生效范围内），跳过检查"
    fi
    # 披露完整性自检：四个字段必须齐全（本缺陷正是"编译参数/优化等级缺失"且直到复核才发现）
    has_opt=$(grep -a -c -F -- "优化等级" "$out" 2>/dev/null || true)
    has_flags=$(grep -a -c -F -- "编译参数" "$out" 2>/dev/null || true)
    has_optval=$(grep -a -c -F -- "$opt_level" "$out" 2>/dev/null || true)
    [ "$has_opt" = "0" ] && { log "错误：$profile 披露串缺少「优化等级」"; fail=1; }
    [ "$has_flags" = "0" ] && { log "错误：$profile 披露串缺少「编译参数」"; fail=1; }
    [ "$has_optval" = "0" ] && { log "错误：$profile 披露串里找不到实际优化等级 $opt_level"; fail=1; }
    log "  披露自检：优化等级标记=$has_opt / 编译参数标记=$has_flags / 优化等级值($opt_level)=$has_optval / 构建类型=$BUILD_CONFIG"
    log "  产物 $sizes_note；PT_LOAD align = $aligns；JNI 符号名字面量=$jni_syms；档位自述串=$has_profile；工具链=$origin"
    [ "$ok_align" != "1" ] && { log "错误：$profile 的 PT_LOAD 未全部 16384 对齐（$aligns）"; fail=1; }
    [ "$has_profile" = "0" ] && { log "错误：$profile 产物里找不到档位自述串"; fail=1; }
done

if [ "$fail" != "0" ]; then
    log "实验性档位构建失败 —— 中止（避免静默产出不可用产物）"
    exit 1
fi
built=$(ls -1 "$OUT_DIR"/libdynamo_engine_*.so 2>/dev/null || true)
[ -n "$built" ] && built=$(printf '%s\n' "$built" | xargs -n1 basename | tr '\n' ' ')
log "完成：$built"
exit 0
