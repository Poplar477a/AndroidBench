#!/usr/bin/env bash
# ==============================================================================
# 编译 FIO 为 Android ARM64 原生可执行二进制 (libfio.so)
# 产物将部署到: app/src/main/jniLibs/arm64-v8a/libfio.so
# ==============================================================================

set -euo pipefail

# 1. 检测或配置 Android NDK 路径
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    if [ -d "$HOME/Android/Sdk/ndk" ]; then
        LATEST_NDK=$(ls -d $HOME/Android/Sdk/ndk/* 2>/dev/null | sort -V | tail -n1 || true)
        if [ -n "$LATEST_NDK" ]; then
            ANDROID_NDK_HOME="$LATEST_NDK"
        fi
    fi
fi

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
    echo "错误: 未找到 ANDROID_NDK_HOME，请手动设置，例如:"
    echo "export ANDROID_NDK_HOME=/path/to/android-ndk-r25c"
    exit 1
fi

echo "==> 使用 Android NDK: $ANDROID_NDK_HOME"

# 2. 确定 Host 架构与工具链
UNAME_S=$(uname -s | tr '[:upper:]' '[:lower:]')
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/${UNAME_S}-x86_64"
if [ ! -d "$TOOLCHAIN" ]; then
    TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64"
fi

TARGET="aarch64-linux-android"
API="29" # Android 10+ (API 29)

export AR="$TOOLCHAIN/bin/llvm-ar"
export CC="$TOOLCHAIN/bin/${TARGET}${API}-clang"
export CXX="$TOOLCHAIN/bin/${TARGET}${API}-clang++"
export LD="$TOOLCHAIN/bin/ld"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"

# 3. 准备 fio 源代码
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="$SCRIPT_DIR/fio_build_cache"
mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

if [ ! -d "fio" ]; then
    echo "==> 正在拉取 FIO 源代码..."
    git clone --depth 1 https://github.com/axboe/fio.git
fi

cd fio

echo "==> 配置 FIO Android 交叉编译参数..."
./configure \
    --cc="$CC" \
    --extra-cflags="-O3 -fPIE -fPIC" \
    --extra-ldflags="-pie" \
    --disable-numa \
    --disable-rdma \
    --disable-rbd \
    --disable-gfapi \
    --disable-libaio \
    --disable-cuda \
    --build-static

echo "==> 开始编译 FIO..."
make clean
make -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu || echo 4)"

# 4. 拷贝产物至 Android 工程目录
OUTPUT_DIR="$SCRIPT_DIR/../app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUTPUT_DIR"

$STRIP fio
# 将二进制命名为 libfio.so，以便 Android 安装包管理器赋予可执行权限
cp fio "$OUTPUT_DIR/libfio.so"
chmod +x "$OUTPUT_DIR/libfio.so"

echo "=========================================================="
echo "编译完成！"
echo "产物路径: $OUTPUT_DIR/libfio.so"
echo "=========================================================="
