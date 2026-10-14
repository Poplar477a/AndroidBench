#!/bin/bash
# ============================================================================
# 单个 SPEC CPU2017 基准的编译（设备端执行；宿主端可用 NDK/交叉工具链先验证）
#   读取 prepare_sources.sh 生成的 bench.json（设备端无需 perl）
#   编译参数 = object.pm 的 bench_cflags + SPEC 标准宏 + 结构化参数 + 用户自定义参数
#
# 用法：
#   tools/spec2017/build_bench.sh --src .spec2017-src/519.lbm_r \
#       --cc aarch64-linux-android24-clang --flags "-O3 -mcpu=cortex-x4" --out .spec2017-out
#   --cc 支持：NDK 包装器 / clang / gcc / 绝对路径
# ============================================================================
set -eo pipefail

SRCDIR=""; CC=""; FLAGS=""; OUT=".spec2017-out"; STD=""; DEFINES=""; LIBS=""
TIMEOUT="$SPEC_BUILD_TIMEOUT"
[ -n "$TIMEOUT" ] || TIMEOUT=1800

while [ $# -gt 0 ]; do
  case "$1" in
    --src)     SRCDIR="$2"; shift 2 ;;
    --cc)      CC="$2"; shift 2 ;;
    --flags)   FLAGS="$2"; shift 2 ;;
    --std)     STD="$2"; shift 2 ;;
    --defines) DEFINES="$2"; shift 2 ;;
    --libs)    LIBS="$2"; shift 2 ;;
    --out)     OUT="$2"; shift 2 ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

[ -n "$SRCDIR" ] || { echo "错误：必须指定 --src <整备后的基准目录>" >&2; exit 2; }
[ -f "$SRCDIR/bench.json" ] || { echo "错误：$SRCDIR 缺少 bench.json，请先运行 prepare_sources.sh" >&2; exit 3; }
SRCDIR="$(cd "$SRCDIR" && pwd)"
[ -n "$CC" ] || CC="clang"
command -v "$CC" >/dev/null 2>&1 || { echo "错误：找不到编译器 $CC" >&2; exit 4; }

jget() { sed -n "s/.*\"$1\": *\"\([^\"]*\)\".*/\1/p" "$SRCDIR/bench.json" | head -1; }
jarr() { sed -n "s/.*\"$1\": *\[\(.*\)\].*/\1/p" "$SRCDIR/bench.json" | head -1 | tr ',' '\n' | tr -d ' "'; }

BENCH="$(basename "$SRCDIR")"
LANG_="$(jget lang)"
BCFLAGS="$(jget bench_cflags)"
EXE_="$(jget exename)"
[ -n "$EXE_" ] || EXE_="$BENCH"
if [ "$LANG_" = "CXX" ]; then DEFSTD="-std=c++17"; else DEFSTD="-std=c99"; fi
[ -n "$STD" ] || STD="$DEFSTD"
case "$(jget need_math)" in yes|YES) LIBS="$LIBS -lm" ;; esac

SOURCES="$(jarr sources)"
[ -n "$SOURCES" ] || { echo "错误：bench.json 中未解析到 sources" >&2; exit 5; }

# 通用包含路径：源码树下每个子目录都加 -I（SPEC 各基准把公共头放在 src/<名字>/ 子目录里）
AUTO_INC=""
# 排除平台专属目录：win32/ 下有 Windows 专用的 inttypes.h 等，误加 -I 会覆盖系统头
while IFS= read -r d; do
  case "$d" in
    */win32|*/win32/*|*/win64|*/win64/*|*/msvc|*/msvc/*) continue ;;
  esac
  AUTO_INC="$AUTO_INC -I$d"
done < <(find "$SRCDIR/src" -type d 2>/dev/null)
FLAGS="$FLAGS $AUTO_INC"

mkdir -p "$OUT/obj-$BENCH"
LOG="$OUT/$BENCH.build.log"
META="$OUT/$BENCH.build.json"
CCABS="$(command -v "$CC")"
CCVER="$("$CCABS" --version 2>/dev/null | head -1)"

echo "== 编译 $BENCH ($LANG_) ==" | tee "$LOG"
echo "  编译器  : $CCABS" | tee -a "$LOG"
echo "  版本    : $CCVER" | tee -a "$LOG"
echo "  标准    : $STD" | tee -a "$LOG"
echo "  配方旗标: $BCFLAGS" | tee -a "$LOG"
echo "  用户参数: $FLAGS $DEFINES" | tee -a "$LOG"

CDEFS="-DSPEC -DSPEC_LP64 -DSPEC_AUTO_SUPPRESS_OPENMP"
# object.pm 里的相对 -I（如 -Ispec_qsort）必须改写为绝对路径，否则按当前工作目录解析
BCFLAGS="$(printf '%s' "$BCFLAGS" | sed "s#-I\([^ ]*\)#-I$SRCDIR/src/\1#g")"
OBJS=""
START_TS=$(date +%s)
for s in $SOURCES; do
  [ -f "$SRCDIR/src/$s" ] || { echo "  [跳过] 清单中的条件源文件不存在：$s" | tee -a "$LOG"; continue; }
  o="$OUT/obj-$BENCH/$(printf '%s' "$s" | tr '/' '_').o"
  echo "  CC $s" | tee -a "$LOG"
  timeout "$TIMEOUT" "$CCABS" $STD $CDEFS $BCFLAGS $DEFINES $FLAGS \
      -I"$SRCDIR/src" -c "$SRCDIR/src/$s" -o "$o" >>"$LOG" 2>&1 \
    || { echo "  !! 编译失败：$s（详见 $LOG）" >&2; echo "BUILD=FAILED"; exit 7; }
  OBJS="$OBJS $o"
done
BIN="$OUT/$EXE_.$BENCH"
timeout "$TIMEOUT" "$CCABS" $FLAGS $OBJS -o "$BIN" $LIBS >>"$LOG" 2>&1 \
  || { echo "  !! 链接失败（详见 $LOG）" >&2; echo "BUILD=FAILED"; exit 8; }
END_TS=$(date +%s)
ELAPSED=$((END_TS - START_TS))

BINHASH="$(sha256sum "$BIN" | cut -d' ' -f1)"
SHORT_HASH="$(printf '%s' "$BINHASH" | cut -c1-16)"
SIZE="$(stat -c %s "$BIN")"
{
  echo "{"
  echo "  \"benchmark\": \"$BENCH\","
  echo "  \"lang\": \"$LANG_\","
  echo "  \"suite_version\": \"$(jget suite_version)\","
  echo "  \"source_tree_sha256\": \"$(jget src_tree_sha256)\","
  echo "  \"compiler\": \"$CCABS\","
  echo "  \"compiler_version\": \"$CCVER\","
  echo "  \"std\": \"$STD\","
  echo "  \"recipe_flags\": \"$BCFLAGS\","
  echo "  \"user_flags\": \"$FLAGS\","
  echo "  \"user_defines\": \"$DEFINES\","
  echo "  \"default_defines\": \"$CDEFS\","
  echo "  \"libs\": \"$LIBS\","
  echo "  \"artifact\": \"$BIN\","
  echo "  \"artifact_sha256\": \"$BINHASH\","
  echo "  \"artifact_bytes\": $SIZE,"
  echo "  \"build_seconds\": $ELAPSED,"
  echo "  \"finished_at\": \"$(date -Is)\","
  echo "  \"log\": \"$LOG\""
  echo "}"
} > "$META"

echo "  [ok] 产物：$BIN（$SIZE 字节，$ELAPSED 秒）"
echo "  [ok] 元数据：$META"
echo "  [ok] 产物哈希：$SHORT_HASH..."
echo "BUILD=SUCCESS"
