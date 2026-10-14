package com.ufs.bench.ui.components

/*
 * SPEC 相关文案（跨页面共用，避免两处各写一份导致措辞漂移）。
 */

/**
 * 基准可比性声明（SPEC 移植版与上游官方基准共用）。
 *
 * 事实依据：
 *   · SPEC 官方要求按 base/peak 分档调优规则上报，成绩与编译器、优化选项绑定；
 *   · EEMBC CoreMark / STREAM / tinymembench 的官方成绩同样需要官方认证流程与官方参考机；
 *   · 本项目的两类基准都由 Android NDK 工具链以**单一固定优化等级**自行编译。
 * 因此比值只能相对本机自标定基线，对两者都不可与任何官方公布成绩比较。
 */
const val SPEC_TUNING_DISCLAIMER =
    "本套基准由本项目以单一固定优化等级自行编译：既非 SPEC 官方 base/peak 上报口径，" +
        "也非 EEMBC CoreMark / STREAM 等上游认证成绩；比值仅相对本机自标定基线，" +
        "不可与任何官方公布成绩比较。"

/**
 * 优化等级差异说明（措辞以 lead 最终确认版为准）。
 *
 * 事实（native-charts 与 verify-pack 各自从构建产物独立取证，不是从 build.gradle 推断）：
 *   · 原生库：Debug = -O3；Release 走 AGP 的 RelWithDebInfo，追加 -O2 -g -DNDEBUG 覆盖基础 -O3；
 *   · Kotlin/JVM 侧：Release 另经 R8 压缩。
 * 两侧方向相反，因此只能说「不同口径」，不能断言谁更快；跨包比较 SPEC 比值无意义。
 */
const val SPEC_OPT_LEVEL_NOTE =
    "原生库编译参数：Debug 使用 -O3，Release 使用 -O2（Release 走 AGP 的 RelWithDebInfo 配置，" +
        "追加 -O2 -g -DNDEBUG 覆盖基础 -O3）；Release 的 Kotlin/JVM 侧另经 R8 压缩。" +
        "两者并非同口径，跨包比较 SPEC 比值无意义。"

/**
 * 「不具备权威性」声明（CPU 定向调优档位、预编译包编译环境表共用）。
 *
 * 必须说清三件事，不能用「仅供参考」这类含糊措辞：
 *   (a) 本项目自行编译，非任何官方发布；
 *   (b) 不构成权威性能认证；
 *   (c) 不同档位乃至不同编译器/优化等级的库并非同一口径，跨档位数值不可直接比较。
 */
const val COMPILER_AUTHORITY_DISCLAIMER =
    "本档位仅为编译优化选项：相关原生库由本项目自行编译，非任何官方发布，" +
        "也不构成权威性能认证。不同档位，乃至不同编译器与优化等级编出的库并非同一口径，" +
        "跨档位数值不可直接比较；切换档位后请分别测量。"

/**
 * 新架构核心（ARM C1 / C2 系列）无对应调优档位的说明。
 *
 * 事实（lead 已实测 + 联网查证）：
 *   · 本表所有档位由 Android NDK r30 内置的 Clang 21.0.0 编译；
 *   · ARM C1 系列（C1-Ultra / C1-Premium / C1-Pro / C1-Nano，ARM Lumex 微架构，
 *     2025 年 9 月发布，Armv9.3-A）所需调度模型在 LLVM 23 开发树才合入 →**当前 r30 不支持**，
 *     实测 `-mtune=c1-ultra` 返回 unsupported；C2-Ultra 的 LLVM 支持提交更晚，同样不可用；
 *   · 对这类设备只能选表中**最接近**的可用档位（例如 C1-Ultra 用 X925），并非精确匹配。
 *
 * 措辞要求：把「已实测不支持」与「待将来工具链支持」分开说清，不能含糊成「暂不支持」。
 */
const val C1_CORE_NO_TIER_NOTE =
    "本表所有档位由 Android NDK r30 内置的 Clang 21.0.0 编译。\n\n" +
        "新架构核心暂无对应档位：ARM C1 系列（C1-Ultra / C1-Premium / C1-Pro / C1-Nano，" +
        "ARM Lumex 微架构，2025 年 9 月发布，Armv9.3-A）所需的调度模型在 LLVM 23 开发树才合入，" +
        "因此当前的 NDK r30 没有对应档位——已实测 `-mtune=c1-ultra` 返回 unsupported；" +
        "C2-Ultra 的 LLVM 支持提交更晚，同样不可用。对采用这些核心的设备，只能选用表中" +
        "最接近的可用档位（例如 C1-Ultra 设备可选用 X925 档）；该档位并非精确匹配，" +
        "不代表已针对该核心优化。待工具链提供相应调度模型后，本表才会出现对应的精确档位。"

/** 表格里给 C1 / C2 系列用户的短提示（放在表头附近与 X925 行备注里） */
const val C1_CORE_TABLE_HINT =
    "C1 / C2 系列设备：无精确匹配档位，建议用 X925 档（近似，非精确匹配）"
