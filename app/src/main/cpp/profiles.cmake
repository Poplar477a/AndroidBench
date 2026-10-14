# ============================================================================
#  微架构分档单一事实源（-mtune 为主；-mcpu 仅限登记为全大核 ISA 的档位）
#
#  CMakeLists.txt（NDK 档位）与 build_experimental_variants.sh（实验性档位）都读这一份表，
#  避免"两处各写一份分档表"导致漂移。
#  新增档位只改这里：脚本用正则解析 DYNAMO_MCPU_<profile>、DYNAMO_TOOLCHAIN_<profile>、
#  DYNAMO_ISA_<profile> 与 DYNAMO_EXPERIMENTAL_PROFILES。
#
#  ISA 规则（取代旧的"绝不用 -mcpu"）：
#    · 常规档**只用 -mtune**（只调调度、不改 ISA 要求）—— SoC 内可能仍有小核，
#      大核指令落到小核即 SIGILL（已在 SM7475 复现，tombstone 指令为 SVE 的 index z0.s）；
#    · 登记为 set(DYNAMO_ISA_<p> "allbig") 的档位**允许 -mcpu**（完整 ISA），
#      前提是运行期门禁会在非全大核设备上拒绝加载该库；产物门禁的豁免前提自动核验。
#
#  工具链三选一（DYNAMO_TOOLCHAIN_<profile>）：
#    ndk    = NDK r30 内置 Clang 21     → 由 CMakeLists 编译（baseline/x1/x2/x3/x4/x925）
#    llvm23 = 独立 LLVM 23.1.3          → 脚本编译（C1 系列，NDK 21 不支持）
#    sdllvm = Snapdragon LLVM 19.0.0    → 脚本编译（Oryon 系列，前述两者都不支持）
# ============================================================================
set(DYNAMO_ALL_PROFILES
    baseline
)

# 2026-10-13（V3 规划）：专属调优取消 —— SoC 专属档与 ISA 档全部下架，仅保留 baseline。
#   · 实测：微架构定向（-mtune/-mcpu）在本项目负载上均为负收益，generic 基线最优；
#   · ISA 档（-mcpu + SVE）同代对照下仍慢约 9%，且依赖全大核门禁，净收益为负；
#   · 测试设备已变更，专属调优已无意义。

set(DYNAMO_MCPU_baseline "generic")
set(DYNAMO_TOOLCHAIN_baseline "ndk")
set(DYNAMO_EXPERIMENTAL_PROFILES "")
