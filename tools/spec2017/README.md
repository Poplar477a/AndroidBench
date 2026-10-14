# SPEC2017 → Android 组件包 流水线

把 SPEC CPU2017 的基准源码按**官方口径**交叉编译成 Android 组件包，
供 UFS Benchmark 的「二进制测试（BYOB）」导入运行。

## 用法

    python3 tools/spec2017/build_components.py \
        --spec-root SPEC2017 \         # 安装介质解包树（内含 benchspec/CPU/*）
        --out .evidence/byob_pkg \     # 产物目录（组件包 zip + MANIFEST.json）
        [--only 519.lbm_r] \           # 只做某一个
        [--scale test|train|refrate] \ # 数据集档位（默认 test）
        [--no-host-check]              # 跳过宿主口径自检（不推荐）

## 它做了什么

1. **宿主口径自检**：用宿主 gcc 按同一套宏编译并运行，把输出与基准自带的
   `data/<scale>/output/<ref>` 逐行比对（token 级数值容差 1e-4，忽略空行与声明要忽略的行）。
   只有 PASS 的组件才会出包 —— 这是"该组件忠实复现官方口径"的证明。
2. **交叉编译**：NDK clang 出 arm64（动态 + 静态）与 x86_64（动态）三份；
   组件包默认使用**动态 arm64** 版本（约 20~40 KB，bionic 依赖在应用侧白名单内）。
3. **打包**：`bin/<exe>` + `data/input/*` + `data/output/<ref>` + `runner.json`
   （含 command_args、stdout_file、oracle_rule 容差与 ignore_line_patterns）。
4. **清单**：`MANIFEST.json` 记录每个组件的宿主自检结论、二进制 sha256、包体积与运行参数。

## 关键约定（踩过的坑）

| 约定 | 原因 |
|---|---|
| 必须 `-DSPEC` | 官方参考输出在该宏下生成；否则走 `#if !defined(SPEC)` 分支，输出口径不同 |
| mcf 需 `-DSPEC_HAVE_INTTYPES_H` + `src/spec_qsort/*.c` | 官方用 SPEC 自带 qsort，排序稳定性会改变迭代轨迹（实测不加则 58 行参考对不上） |
| 判分用 `ignore_line_patterns` | 时间/内存分配行每次运行都不同（对齐 SPEC runrules 的排除口径） |
| 组件包必须 arm64 | 应用侧 ELF 准入只接受 `e_machine=0xB7`（见 ElfValidator） |
| 组件包用动态链接即可 | 白名单本就是 bionic 风格（libc.so/libm.so/libdl.so），无需静态（9 MB vs 20 KB） |

## 增加一个基准

在 `build_components.py` 的 `BENCHES` 表里加一条：源码 glob、包含目录、宏、运行参数、
输入文件、参考输出路径、忽略行模式。加完直接跑，宿主自检会告诉你是否忠实复现官方口径。

