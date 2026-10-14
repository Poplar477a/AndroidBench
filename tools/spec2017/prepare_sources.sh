#!/bin/bash
# ============================================================================
# SPEC CPU2017 源码整备（宿主端执行一次）
#   用户自备的 SPEC CPU2017 套件  ->  仅整备本项目选用的基准源码
#   产出：<out>/<bench>/{src,Spec,version.txt,bench.json} + SOURCES.lock.json
#
# 合规硬约束：整备结果属于用户许可内容，绝不入库、不打包进 APK、不对外分发。
#   默认输出目录 .spec2017-src/ 已在 .gitignore 中忽略。
#
# 用法：
#   tools/spec2017/prepare_sources.sh --from /mnt/cpu2017iso
#   tools/spec2017/prepare_sources.sh --from /home/Speccpu2017Rate --out /tmp/spec-src
# ============================================================================
set -euo pipefail

BENCHES="519.lbm_r 508.namd_r 502.gcc_r 557.xz_r 510.parest_r 505.mcf_r"
FROM=""
OUT=".spec2017-src"

usage() {
  sed -n '2,15p' "$0"
  echo "选项："
  echo "  --from <dir>     SPEC CPU2017 安装树或已挂载 ISO 的根目录（必填）"
  echo "  --out  <dir>     整备输出目录（默认 .spec2017-src，已 gitignore）"
  echo "  --benches \"...\"  覆盖默认基准列表"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --from)    FROM="$2"; shift 2 ;;
    --out)     OUT="$2";  shift 2 ;;
    --benches) BENCHES="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数：$1" >&2; usage; exit 2 ;;
  esac
done

[ -n "$FROM" ] || { echo "错误：必须指定 --from" >&2; usage; exit 2; }
[ -d "$FROM/benchspec/CPU" ] || { echo "错误：$FROM 不是 SPEC CPU2017 安装树/挂载点（缺 benchspec/CPU）" >&2; exit 3; }

HERE="$(cd "$(dirname "$0")" && pwd)"
VER="$(cat "$FROM/version.txt" 2>/dev/null || echo unknown)"
echo "== SPEC CPU2017 源码整备 =="
echo "  套件版本 : $VER"
echo "  来源     : $FROM"
echo "  输出     : $OUT"
echo "  基准     : $BENCHES"
echo

mkdir -p "$OUT"
LOCK="$OUT/SOURCES.lock.json"
{
  echo "{"
  echo "  \"suite\": \"SPEC CPU2017\","
  echo "  \"suite_version\": \"$VER\","
  echo "  \"origin\": \"$FROM\","
  echo "  \"prepared_at\": \"$(date -Is)\","
  echo "  \"compliance\": \"用户许可内容，禁止入库/打包/分发\","
  echo "  \"benchmarks\": {"
} > "$LOCK"

first=1
for b in $BENCHES; do
  src="$FROM/benchspec/CPU/$b"
  [ -d "$src/src" ] || { echo "  !! 跳过 $b：未找到 $src/src" >&2; continue; }
  dst="$OUT/$b"
  rm -rf "$dst"; mkdir -p "$dst"
  cp -a "$src/src" "$dst/src"
  mkdir -p "$dst/Spec"
  cp -a "$src/Spec/object.pm" "$dst/Spec/" 2>/dev/null || true
  cp -a "$src/Spec/flags.xml" "$dst/Spec/" 2>/dev/null || true
  cp -a "$src/version.txt" "$dst/" 2>/dev/null || true

  meta="$(perl "$HERE/parse_object.pl" "$dst/Spec/object.pm" 2>/dev/null || true)"
  lang="$(printf '%s\n' "$meta" | sed -n 's/^LANG=//p')"
  bcf="$(printf '%s\n' "$meta" | sed -n 's/^BENCH_CFLAGS=//p')"
# 修正：object.pm 的字段名是 bench_flags（不是 bench_cflags），SPEC 官方宏在此
bcf=$(sed -n "s/^\$bench_flags *= *[\x27\x22]\([^\x27\x22]*\).*/\1/p" "$src/Spec/object.pm" | head -1)
  exe="$(printf '%s\n' "$meta" | sed -n 's/^EXE=//p')"
  fcmp="$(printf '%s\n' "$meta" | sed -n 's/^FLOATCOMPARE=//p')"
  ctol="$(printf '%s\n' "$meta" | sed -n 's/^CALCTOL=//p')"
  math="$(printf '%s\n' "$meta" | sed -n 's/^NEED_MATH=//p')"
  srcs="$(printf '%s\n' "$meta" | sed -n 's/^SOURCES=//p')"

  man="$dst/FILES.sha256"
  ( cd "$dst" && find src -type f | LC_ALL=C sort | xargs -r sha256sum ) > "$man"
  nfiles="$(wc -l < "$man" | tr -d ' ')"
  treehash="$(sha256sum "$man" | cut -d' ' -f1)"
  short_hash="$(printf '%s' "$treehash" | cut -c1-16)"
  nsrc="$(printf '%s' "$srcs" | wc -w | tr -d ' ')"

  {
    echo "{"
    echo "  \"benchmark\": \"$b\","
    echo "  \"suite_version\": \"$VER\","
    echo "  \"lang\": \"$lang\","
    echo "  \"exename\": \"$exe\","
    echo "  \"bench_cflags\": \"$bcf\","
    echo "  \"need_math\": \"$math\","
    echo "  \"floatcompare\": \"$fcmp\","
    echo "  \"calctol\": \"$ctol\","
    echo "  \"src_files\": $nfiles,"
    echo "  \"src_tree_sha256\": \"$treehash\","
    echo "  \"sources\": [$(printf '%s' "$srcs" | tr ' ' '\n' | sed 's/.*/"&"/' | paste -sd, -)]"
    echo "}"
  } > "$dst/bench.json"

  [ $first -eq 1 ] || echo "," >> "$LOCK"
  first=0
  printf '    "%s": {"lang": "%s", "src_files": %s, "src_tree_sha256": "%s", "build_sources": %s}' \
    "$b" "$lang" "$nfiles" "$treehash" "$nsrc" >> "$LOCK"

  echo "  [ok] $b  lang=$lang  源文件=$nfiles  构建源=$nsrc  树哈希=$short_hash..."
done
{
  echo ""
  echo "  }"
  echo "}"
} >> "$LOCK"

echo
echo "整备完成：$OUT"
echo "  - 每个基准：src/ + Spec/object.pm + bench.json + FILES.sha256"
echo "  - 锁文件  ：$LOCK"
echo
echo "【合规提醒】$OUT 内含 SPEC 许可源码："
echo "  1) 不得提交到 Git 仓库（.gitignore 已忽略 .spec2017-src/）；"
echo "  2) 不得打包进 APK 或任何对外分发产物；"
echo "  3) 测试结果只能表述为「内部相对基线」，不得称为 SPEC 官方成绩。"
