#!/bin/bash
# 由源图生成两个 flavor 的全套启动图标（见 MakeIcons.java 顶部说明）
#   lab 版      <- tools/icon/source/lab.png      （文件名决定归属，改图直接替换同名文件）
#   standard 版 <- tools/icon/source/standard.jpg
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; cd "$ROOT"
export JAVA_HOME="$ROOT/.tools/jdk-17"; export PATH="$JAVA_HOME/bin:$PATH"
java tools/icon/MakeIcons.java tools/icon/source/lab.png      app/src/lab/res
java tools/icon/MakeIcons.java tools/icon/source/standard.jpg app/src/standard/res
echo "完成。重新构建 APK 后生效：./build.sh --max-workers=4 assembleStandardRelease assembleLabRelease"
