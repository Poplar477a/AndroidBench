#!/bin/bash
# ============================================================================
#  构建 BYOB 沙箱助手（jniLibs/libbyob_run.so）—— 保证与源码一致，杜绝二进制漂移
#
#  为什么需要这个脚本：
#    app/src/main/jniLibs/<abi>/libbyob_run.so 是**签入版本库**的预编译可执行文件
#    （Android 只允许从 lib 目录执行原生二进制，见 PrecompiledRunner.runViaSu）。
#    它的源码就在本仓库（tools/sandbox_test/byob_run.cpp + app/src/main/cpp/sandboxed_runner.cpp），
#    所以必须能从源码一键复现；否则改了源码却忘了同步二进制，就会出现
#    "源码看起来对、设备上是旧行为"的漂移。
#
#  为什么装在 lab 源集：
#    BYOB 终端是实验室版专属能力（standard 构建 FEATURE_EXTERNAL_SPEC=false，界面不出现终端页）。
#    把预编译助手放进 app/src/lab/jniLibs/ 之后，**standard 的 APK 里不含任何预编译可执行文件**，
#    可避免"APK 内嵌原生可执行程序"被安全扫描（Play Protect / 各家安全中心）当作风险项。
#
#  用法：
#    tools/sandbox_test/build_byob_helper.sh            # 构建到临时目录并对比
#    tools/sandbox_test/build_byob_helper.sh --install  # 就地更新 jniLibs（需重新构建 App）
# ============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
NDK="${NDK:-$ROOT/.tools/android-sdk/ndk/30.0.16248370}"
PRE="$NDK/toolchains/llvm/prebuilt/linux-x86_64"   # NDK 的 sysroot 与工具链都在这里
TC="$PRE/bin"
API=26
INSTALL=0
[ "${1:-}" = "--install" ] && INSTALL=1

build_one() {   # $1=安卓三元组 $2=jniLibs 子目录 $3=输出路径
  local tri="$1" abidir="$2" out="$3"
  "$TC/${tri}${API}-clang++" --target="${tri}${API}" --sysroot="$PRE/sysroot" \
    -O2 -std=c++17 -fexceptions -frtti -Iapp/src/main/cpp \
    tools/sandbox_test/byob_run.cpp app/src/main/cpp/sandboxed_runner.cpp \
    -static-libstdc++ -o "$out"
  echo "  构建 $abidir -> $(stat -c%s "$out") 字节  sha256=$(sha256sum "$out" | cut -c1-16)"
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
echo "=== 从源码构建沙箱助手（NDK: $(basename "$NDK")）==="
build_one aarch64-linux-android arm64-v8a "$TMP/arm64"
build_one x86_64-linux-android  x86_64    "$TMP/x86_64"

echo "=== 与签入产物对比 ==="
drift=0
for pair in "arm64:arm64-v8a" "x86_64:x86_64"; do
  new="$TMP/${pair%%:*}"; old="app/src/lab/jniLibs/${pair##*:}/libbyob_run.so"
  a=$(sha256sum "$new" | cut -d' ' -f1); b=$(sha256sum "$old" | cut -d' ' -f1)
  if [ "$a" = "$b" ]; then
    echo "  ${pair##*:}: 一致 ✓ ($a)"
  else
    echo "  ${pair##*:}: 不一致 ✗  源码=$(echo "$a" | cut -c1-16)  签入=$(echo "$b" | cut -c1-16)"
    drift=1
    if [ "$INSTALL" = "1" ]; then
      cp "$new" "$old"; chmod 755 "$old"; echo "     已就地更新 $old"
    fi
  fi
done
if [ "$drift" = "1" ] && [ "$INSTALL" = "0" ]; then
  echo "提示：加 --install 就地更新，然后必须重新构建 App 并在真机复验 BYOB 运行"
fi
exit 0

