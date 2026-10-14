#!/usr/bin/env bash
set -e

# ---------------------------------------------------------------
# 自动串行化。
#
# Gradle 的中间产物目录（app/build/tmp/kotlin-classes 等）不允许多个
# 构建实例并发写入。多人/多任务同时构建会互相清理中间产物，
# 表现为 "Failed to create MD5 hash for ... as it does not exist" 或
# "NoSuchFileException: XXX.class" —— 都是假失败，不是代码问题。
#
# 这里在脚本内部自锁，任何调用方式（含直接 ./build.sh）都会被串行化，
# 不依赖调用者记得去用某个包装脚本。
# ---------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -z "${UFS_BUILD_LOCKED:-}" ]; then
    export UFS_BUILD_LOCKED=1
    # 锁文件必须放在工作区内。
    # 本沙箱每次 bash 调用的 /tmp 是私有挂载，跨调用不可见，
    # 放在 /tmp 的锁等于没有锁（多人并发仍会互踩 kotlin-classes）。
    exec flock "$SCRIPT_DIR/.build.lock" "$0" "$@"
fi

export JAVA_HOME="$SCRIPT_DIR/.tools/jdk-17"
export TMPDIR="$SCRIPT_DIR/.tools/tmp"
export GRADLE_USER_HOME="$SCRIPT_DIR/.tools/gradle_home"
export ANDROID_HOME="$SCRIPT_DIR/.tools/android-sdk"
export ANDROID_USER_HOME="$SCRIPT_DIR/.tools/android_home"
unset ANDROID_PREFS_ROOT
export PATH="$JAVA_HOME/bin:$SCRIPT_DIR/.tools/gradle-8.6/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

mkdir -p "$TMPDIR" "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"

# ---------------------------------------------------------------
# 实验性微架构档位（独立工具链，非 NDK 内置）：
#   · C1 系列（c1ultra）→ 独立 LLVM 23.1.3
#   · Oryon 系列（oryon2/oryon1）→ Snapdragon LLVM 19.0.0
#   统一入口 app/src/main/cpp/build_experimental_variants.sh，
#   档位与工具链映射取自 app/src/main/cpp/profiles.cmake（单一事实源）。
#   产物落在 app/src/main/jniLibs/arm64-v8a/，由 AGP 作为 jniLibs 打包。
#   只在打包 APK 的任务里跑；工具链缺失时脚本自行跳过（不阻断构建）。
#   NDK 档位（baseline/x1…x925）仍由 CMake + NDK r30 编译，互不影响。
# ---------------------------------------------------------------
case "$*" in
    *assemble*)
        # 把实际构建类型透传给实验档脚本（它不经 AGP/CMake，拿不到 CMAKE_BUILD_TYPE）
        case "$*" in
            *Release*) export DYNAMO_BUILD_CONFIG=Release ;;
            *Debug*)   export DYNAMO_BUILD_CONFIG=Debug ;;
            *)         export DYNAMO_BUILD_CONFIG=Unknown ;;
        esac
        "$SCRIPT_DIR/app/src/main/cpp/build_experimental_variants.sh" || exit 1
        ;;
esac

if [ $# -eq 0 ]; then
    gradle assembleDebug --no-daemon
else
    gradle "$@" --no-daemon
fi