# 已联网查证的事实（文案修订依据）

> 本文件是本轮文案"严谨化"的事实基准。所有对外表述必须与下列查证结果一致，
> 不得出现推测、夸大或与官方定义冲突的说法。

## 1. SPEC CPU 2026（官方）

来源：
- https://www.spec.org/pressreleases/2026/20260505-spec-releases-cpu-2026-benchmark-suite
- https://www.spec.org/cpu2026/docs/overview.html
- https://www.spec.org/cpu2026/docs/system-requirements.html

| 项 | 官方事实 |
|:---|:---|
| 套件构成 | 共 52 个基准（43 个沿用 SPEC CPU 2017 + 9 个新增） |
| 代码量 | 约 1670 万行 |
| 语言标准 | C18、C++17、Fortran 2018 |
| 内存要求 | SPECspeed large 需 **64 GB**；SPECrate 每份拷贝 2 GB |
| 四个套件 | SPECrate 2026 Integer / SPECspeed 2026 Integer / SPECrate 2026 FP / SPECspeed 2026 FP |
| 调优等级 | base 与 peak（base 先跑）；合规整轮约 6~20 小时 |

**命名规则（关键）**：后缀 `_s` = SPECspeed，`_r` = SPECrate。

| 编号 | 类型 | 官方描述 |
|:---|:---|:---|
| 821.gcc_s | SPECspeed（`_s`） | 编译器（GCC）代码编译 |
| 766.femflow_r | **SPECrate（`_r`）** | Fluid dynamics: high-order finite element method |
| 854.graph500_s | SPECspeed（`_s`） | Graph analytics |

**档位**：test（不计时/规模最小）/ train（非上报档）/ refspeed 或 refrate（上报档）。
速度类基准的参考档叫 **refspeed**，速率类叫 **refrate**。

> ⚠️ 本项目**无法获得 SPEC 官方参考时间**，因此所有比值均为"相对自标定基线"，
> 不得表述为 SPECspeed/SPECrate 官方成绩，不得与 spec.org 公布分数比较。

## 2. AOSP system/vold/Benchmark.cpp（官方源码）

来源：https://android.googlesource.com/platform/system/vold/+/32a5b9aed304e6619248cb58727e8b29d61d3b1c/Benchmark.cpp

已逐条核实：
- `setpriority(PRIO_PROCESS, 0, -10)` —— 提升进程优先级
- `android_set_ioprio(0, IoSchedClass_RT, 0)` —— 设置实时 I/O 调度类
- `constexpr auto kTimeout = 20s;` 且 `return (timer.duration() < kTimeout);` —— 每阶段 20 秒超时

> 注：`drop_caches` 相关行为在本次抓取的片段中**未能取得原文佐证**。
> 对外表述请使用"参考 AOSP 的 PerformanceBoost 做法（提升优先级 + RT I/O 调度类）"，
> 不要断言"drop_caches 失败即判失败"这类未经原文确认的细节。

## 3. Android Monet 动态取色（官方）

来源：https://developer.android.com/develop/ui/compose/designsystems/material3

- Android 12（API 31）起支持从用户壁纸派生配色
- Compose 用法：`dynamicLightColorScheme(context)` / `dynamicDarkColorScheme(context)`
- API 31 以下必须回退到固定配色方案
- 取色生效的前提：界面颜色必须走 `MaterialTheme.colorScheme.*` 角色，
  **硬编码 Color(0xFF…) 不会随壁纸变化**

## 4. Legado with MD3（参考设计）

来源：https://github.com/HapeLee/legado-with-MD3

- 定位：用 Material Design 3 全新设计的阅读 3.0
- 特征：MD3 组件与色调表面、预测式返回手势、共享元素转场、高可定制
- 本项目取其**设计语言**：MD3 语义色角色 + 大圆角表面 + Bottom NavigationBar + TopAppBar

## 5. 已知需修正的表述（本轮）

| 位置 | 问题 |
|:---|:---|
| 各处 "SPEC 官方参考值" | 必须写明是自标定基线，非官方 |
| 766 相关 | 必须标为 SPECrate（refrate），不是 SPECspeed |
| "drop_caches 失败即判失败" | 未经 AOSP 原文佐证，需改为中性表述 |
| "寻道" | 闪存无寻道，应为"访问" |

## 6. ARM 新核心与 Clang 版本边界（本轮查证 + 实测）

**ARM C1 系列（Lumex 微架构）**
- 2025 年 9 月发布，含 C1-Ultra / C1-Premium / C1-Pro / C1-Nano
- Armv9.3-A，支持 SME2；C1-Ultra 较 Cortex-X925 单线程 +25%
- 来源：arm.com、phoronix.com、Wikipedia ARM C-series

**LLVM/Clang 支持边界（实测 + 查证）**

| 核心 | 所需 Clang | 本项目 NDK r30（Clang 21） |
|:---|:---|:---|
| cortex-x925 / cortex-a725 | Clang 21 | ✅ 支持 |
| **c1-ultra / c1-premium / c1-pro / c1-nano** | **Clang 23+**（调度模型在 LLVM 23 开发树合入） | ❌ **实测报 unsupported** |
| **c2-ultra** | 更晚（提交晚于 r30 发布） | ❌ 不支持 |
| oryon-1-v1 / oryon-2-v1 | 仅 Qualcomm SDLLVM 提供 | ❌ 不支持 |

**结论**：对采用 C1/C2 系列核心的设备，本项目只能选用最接近的可用档位（如 X925），
且必须在界面明确标注「非精确匹配」。此边界写入「预编译包编译环境表」页面。

## 8. P0：-mcpu 逐核定向在异构 SoC 上必然崩溃（真机复现 + 根因）

### 现象
Redmi Note 12 Turbo（1xCortex-X2 + 3xCortex-A710 + 4xCortex-A510）上，
强制 x2 档（由 -mcpu=cortex-x2 编译）运行基准测试 → **必崩**：

    Fatal signal 4 (SIGILL), code 1 (ILL_ILLOPC)
    pid ... (com.ufs.bench), tid ... (DefaultDispatch)

同一测试用 baseline 档（无 -mcpu）**正常完成**。

### 根因（**已校正**：真正的触发指令是 SVE，不是 FLAGM）

**tombstone 实证**：崩溃指令是 SVE 的 `index z0.s` —— `-mcpu=cortex-x2` 把 `PrepareTargetFile`
的填充循环自动向量化成了 SVE，而 Cortex-A510/A710 在真实硅片上不实现 SVE → 调度到小核即 SIGILL。
实测对比：`-mtune=cortex-x2` 该循环 SVE 指令 0 条，`-mcpu=cortex-x2` 有 `index z0.s`。
修复后逐个变体反汇编统计：9 个 arm64 档 SVE=0、FLAGM=0。

### 次要差异（clang 特性集实测，非本次触发原因）
| 核心 | +flagm |
|:---|:--:|
| cortex-x2 | 有 |
| cortex-a710 | 有 |
| cortex-a510 | **无** |

-mcpu=cortex-x2 会启用 +flagm/+sve2/+pauth/+ccidx。
**Android 调度器会把线程调度到任意核心**，代码一旦落到 A510 上执行 FLAGM 指令即 SIGILL。

**这是方法本身的缺陷，不是实现 bug**：big.LITTLE 异构 SoC 上，
任何「按大核定向」的 -mcpu 都会让代码在小核上崩溃。

### 修复：-mcpu 改 -mtune
| 选项 | 启用 +flagm/+sve2 | 调度优化 | 安全 |
|:---|:--:|:--:|:--:|
| 不指定 | 否 | — | 安全 |
| **-mtune=core** | **否** | **生效** | **安全** |
| -mcpu=core | 是 | 生效 | **崩溃** |

-mtune 只影响指令调度、不启用新 ISA 特性，正是所需。
实测 -mtune=cortex-x2 与不指定相比汇编确有差异，证明调度优化真实生效。

### 适用面
影响全部按核心定向的档位：NDK 6 档、LLVM 23 的 C1 档、SDLLVM 的 Oryon 档。
---

## 2026-10-12 晚（G2 修复轮）新增事实

### 1. 切档自重启：AlarmManager 方案在 Android 10+ 不可靠
- 旧实现（AlarmManager + PendingIntent 定时拉起自己再 exit）在 **Android 17 / 一加 15 上直接闪退且不再起来**：
  系统拒绝由已死进程发出的 Activity 启动请求。
- 有效方案：**独立进程跳板 Activity**（android:process=":relaunch"）→ 主进程 exit(0) → 跳板（可见的前台
  Activity）再拉起 MainActivity。实测重启全程 0.4~0.6 s，应用自动回前台。
- 跳板起不来时**绝不自杀**，退化为"下次启动生效"并如实提示。

### 2. 档位持久化必须同步落盘
- SharedPreferences 的 apply() 是异步写；紧接着 exit(0) 会丢写入 —— 实测现象是"档位点了、重启了、但没生效"。
- 必须用 **commit()**，且失败时不得进入重启流程。

### 3. 进程实际加载的 ABI 切片 ≠ Build.SUPPORTED_ABIS 首项
- MuMu 实例：abilist 以 x86_64 开头，进程实际加载的是 **lib/arm64**（ARM 翻译层）。
- 旧代码据此把用户显式选择的档**静默改成 baseline**，同时却打出"已按你的选择加载"的提示 —— 提示与事实矛盾。
- 现在以 **java.library.path 的切片目录**为准；翻译层（跑 arm64 切片但硬件非 arm64）下一律回退基线并如实说明，
  并新增 requestedUnavailable 供界面区分"等重启"与"本进程根本给不了这一档"。

### 4. 专业模式权限校验的两个真实缺陷（已修）
- rootError 弹窗只挂在设置页 → 从首页触发拦截时用户看不到任何提示；已上移到**应用壳层**。
- StorageDetector.checkRootAccess() 在主线程调用 su 且 waitFor() 无超时 → 授权弹窗未响应时假死，被当成"没有校验"。
  现在是四态（OK / NO_SU / DENIED / TIMEOUT）+ 4 s 超时 + IO 线程探测，并写入日志。

### 5. PGO 现状（受环境限制，如实记录）
- 一加 15 已断开，**从未取得任何 .profdata**；产物披露一律为 PGO=未应用（无 profile 数据）。
- 插桩库（-fprofile-generate -fprofile-update=atomic）**绝不能交付**：它是"算力异常低"的根因（原子计数在热循环里开销数倍）。

### 6. 签名与安装陷阱
- release 与 debug 使用**不同签名键**：adb install -r 会以 INSTALL_FAILED_UPDATE_INCOMPATIBLE 失败，
  **但 adb 退出码仍为 0**。必须先 uninstall。脚本据此判定"安装成功"会误判。
- 安装后整包 sha256 与设备端 base.apk 一致（卸载旧包再装 release 时实测 9/9 原生库 + classes.dex 全等）。

### 7. 产物门禁（Release APK 实测）
- 9 个 arm64 档位齐全；逐档 SVE 指令数 = 0；PT_LOAD 对齐 = 0x4000（16 KB 页）；
  无 libc++_shared.so 依赖；__llvm_prf_cnts 符号残留 = 0。

---

## 2026-10-12 深夜（第 4 轮）：全大核 ISA 分支 / LTO 开关 / PGO 采集

### 1. 全大核 ISA 分支（`DYNAMO_ISA=allbig`）
- 语义：把每档的 `-mtune=<x>` 升级为 `-mcpu=<x>`（**真正的 ISA 级优化**，而不仅是调度）。
- **默认关闭**，且只允许用于"全大核"处理器（所有核心同代，例如 Snapdragon 8 Elite 系列的 Oryon）。
  依据就是本文件第 8 节与 `docs/issue-draft.md` 的实测：`-mcpu=cortex-x2` 会生成 SVE，
  在异构 SoC 上落到小核即 SIGILL（tombstone 指令 `index z0.s`）。
- 实测（oryon2 档，SDLLVM 19）：
  | 构建 | 体积 | 指令行 | SIMD/点积类指令 |
  |---|---|---|---|
  | `-mtune=oryon-2-v1` | 1,035,360 B | 157,548 | 360 |
  | `-mcpu=oryon-2-v1` | 1,041,248 B | 159,022 | 419 |
  → `-mcpu` 确实改变代码生成（+8.9 KB、+1,474 条指令、+59 条 SIMD/点积类），不是改个名字。
- 披露：产物内 `-mtune=` 与 `-mcpu=` 字面量互斥出现（各 1 处），披露串新增 `ISA=` 字段，
  如"已启用 -mcpu=oryon-2-v1（全大核 ISA；仅当设备所有核心同代才可加载）"。
- **仍未做（发布前必须补）**：运行期门禁 —— 应用当前只按 rank/deviceMax 红线裁剪，
  还没有"设备是否全大核"的专用判定；在补齐之前，ISA 变体**不得进入正式包**。

### 2. LTO 开关（`DYNAMO_LTO=thin|full`）
- 追加 `-flto=<mode>` + `-Wl,-O2`，披露串新增 `LTO=` 字段（与 PGO 分开披露，避免混变量）。
- **尚未做对照测量**：LTO 会改变代码布局，按既定顺序应在 PGO 闭环之后再单独做单变量 A/B。

### 3. 库替换（L4）
- SDLLVM 只随包提供 `libclang_rt.optlibc-krait-android.a`（Krait = 32 位 ARM），
  **arm64 无可用合规 libc 替换** → 该优化项在当前工具链下不适用（有工具链侧证据，非推测）。

### 4. PGO 采集：链路已通，合并被工具链代际卡住
- 可用：`DYNAMO_PGO_GENERATE=1` 出插桩库；真机运行结束由 JNI 钩子 `nativePgoFlush`
  显式落盘（`[PGO] flush -> true`），产出 46–94 KB 的 `.profraw`。
- 两个必须记住的坑：
  1) **钩子必须用强引用**（编译期 `-DDYNAMO_PGO_HOOK=1`）。弱引用在共享库下若运行时符号
     未进 `.dynsym` 会解析成 `nullptr`，钩子静默返回 false —— 一个 profile 都采不到；
  2) **flush 必须挂在"一轮测试结束"**（run 协程 `finally`）。挂在退出/重启会被 `isBusy()`
     守卫跳过（压测中按设计只存档位、不重启）。
- 阻塞：产出的 raw 版本恒为 **2573 (0xa0d)**，而 SDLLVM 19 / NDK r30 / LLVM 23 的
  `llvm-profdata` 分别只认 10/10/11；把版本字段改成 10 会报 "file header is corrupt"
  → 属于**格式代际差异**（NDK r30 预览版的运行时与同包 profdata 不一致）。
  下一步：链接时只链 SDLLVM 自带运行时（脚本里已留备选行），或改用稳定版 NDK 采集。

### 5. 其他本轮事实
- 切档自重启：独立进程跳板 + **系统 Toast** 提示（跳板自绘那屏在部分设备上子视图不上屏，实测只剩背景色）。
- ABI 切片判定必须用 `applicationInfo.nativeLibraryDir`；`java.library.path` 在 Android 上是
  系统路径，曾据此得到错误证据并把无关路径写进用户可见提示。
- 构建脚本里**续行命令内不能写 # 注释**（bash 词法会截断命令，表现为"链接失败"却看不出原因）。

### 1b. ISA 分支的安全实测（补记，关键）
同一份源码，`-mcpu=oryon-2-v1` 编出的产物含 **SVE 指令 1,526 条**，`-mtune` 版为 **0 条**。
这正是本文件第 8 节与 `docs/issue-draft.md` 记录的危险来源（SVE 落到没有 SVE 的核心即 SIGILL）。
因此该分支有三条硬性前提：
1. 只用于**全大核**（所有核心同代、均支持 SVE）的处理器；
2. **必须有运行期门禁**：设备非全大核时拒绝加载该变体 —— 当前尚未实现，实现前 ISA 变体不得进正式包；
3. 现有产物门禁（`.evidence/scan_apk_allabi.py` 的 SVE=0 检查）**会且应当拒绝** ISA 变体，
   需为这类变体单独登记豁免与前提，不能悄悄放过。

### 6. 工程文件审阅结论（本轮）
| 文档 | PGO | LTO | 全大核 ISA | 切档自重启 | 切片判定 | 构建开关 |
|---|---|---|---|---|---|---|
| AGENTS.md | 缺 | 缺 | 缺 | 缺 | 缺 | 缺 |
| README.md | 缺 | 缺 | 缺 | 缺 | 缺 | 缺 |
| docs/chart-data-contract.md | 缺 | 缺 | 缺 | 缺 | 缺 | 缺 |
| docs/issue-draft.md | 缺 | 缺 | 有（-mcpu 危险记录） | 缺 | 缺 | 缺 |
| docs/verified-facts.md | 有 | 有 | 有 | 有 | 有 | 有 |

结论：`docs/verified-facts.md` 是唯一跟上本轮进度的工程文件；README / AGENTS / issue-draft
对本轮新增能力（跳板重启、切片判定、构建开关、LTO/PGO 状态、全大核 ISA）**均未记录**，
需要一次文档同步（属于交付物"参数与档位说明"那一项）。

---

## 2026-10-12 深夜（第 5 轮）：PGO 阻塞根因查明并修复 —— **不是工具链代际问题，是拉取管道 CRLF 污染**

### 1. 真根因（此前所有"代际冲突"判断全部作废）
Windows 侧 `adb.exe` 的 `shell` 走 **PTY**：传输二进制时会把流里每个 `0x0A` 改写成 `0x0D 0x0A`。
`.evidence` 里的采集脚本当时用 `adb shell "run-as <pkg> cat <file>"` 拉 `.profraw`，于是：

| 文件 | 设备端真实大小 | 拉回主机 | 多出 | 头部版本字段 |
|---|---|---|---|---|
| 红米 `bench_3763.profraw` | 47,760 | 47,902 | **+142**（= 文件内 `0x0A` 的个数） | `0x0A` → 读成 `0x0A0D` = **2573** |
| 一加15 `bench_15464.profraw` | 93,680 | 93,972 | **+292** | 同上 |

`GET_VERSION()` 只取低 32 位 → 报 `Profile uses raw profile format version = 2573; expected version = 10`。
"强改版本为 10 报 header corrupt" 也顺理成章：**其余 141/291 处同样被插入了一个字节**，整个文件结构性移位。
**SDLLVM 19 / NDK r30 的编译器、运行时、llvm-profdata 一直是完全正常的**。

### 2. 修复（fail-closed，防止复发）
- `.evidence/ev.py` 新增 `pull_bin(path)`：走 **base64**（纯 ASCII，免疫 CRLF），并且**拉完立即与设备端 `stat -c %s` 比对长度，不符就抛错**；
- `ev.sh(..., binary=True)` 若命中 `shell ... cat` 组合**直接抛 RuntimeError**（并写明原因与正确做法），不再静默返回损坏数据；
- 7 个采集脚本（`pgo_*.py`）已全部改走 `pull_bin()`。

修复前后自检（同一次拉取）：
```
设备端真实大小 : 47760
旧路径 shell cat: 47902 字节  CRLF=142  多出=+142  版本低32位=2573
新路径 base64   : 47760 字节  CRLF=0    多出=+0    版本低32位=10
新路径 64 位版本字段: 0x010000000000000a   （高位 = VARIANT_MASK_IR_PROF）
```

### 3. 历史文件已逆向还原
对 `.evidence/pgo_collected/` 下 8 份被污染文件做 `\r\n → \n` 逆向还原，长度与设备端完全吻合
（如 93,972 → 93,680、47,902 → 47,760），还原后版本字段恢复为 `0x010000000000000a`。
纯净件存于 `.evidence/pgo_clean/`。

### 4. PGO 闭环打通（有证据）
1. 合并：`oryon2.profdata` 68,160 字节、`x2.profdata` 68,152 字节，**SDLLVM 与 NDK 两家 llvm-profdata 均 RC=0**，
   摘要 201 functions / IR 级插桩；
2. 应用：`DYNAMO_PGO_PROFILE=…/oryon2.profdata` 重建 → 脚本自报
   `PGO 状态：已应用 PGO（…）`；
3. 产物核对：oryon2 = 1,031,400 字节、`__llvm_prf_cnts` = **0**、运行时字符串 = **0**（确认非插桩库），
   披露字面量 `已应用 PGO` ×1、profile 路径 ×1、`插桩采集构建` ×0；
4. 装机实跑：一加 15 上 SEQ1M 用例 10 s 正常完成，日志如实记录
   `本轮结束，但当前库不是插桩构建：未产出 profile（如实记录）` —— 正是 `-fprofile-use` 版应有的行为。

### 5. 教训（写进纪律）
**凡从设备取二进制（.profraw / .so / .png），一律禁止 `adb shell cat`**：
用 `pull_bin()`（base64）或 `adb pull`（Sync 协议，不过 PTY）；`exec-out` 亦是二进制安全路径。
并且**任何二进制传输都必须带长度校验** —— 本次若无长度比对，损坏会一路伪装成"工具链不兼容"。

---

## 2026-10-12 深夜（第 6 轮）：**实验档集体"降级"事故**（我引入的缺陷，已修复）

### 1. 现象（用户先发现的）
真机 logcat：
```
engine load: abi=arm64-v8a active=baseline(-mtune=generic) requested=oryon2 deviceMax=oryon2
             fallback=true preference=oryon2 experimental=false cc=NDK 30.0.16248370
             note=加载 libdynamo_engine_oryon2.so 失败（dlopen failed: cannot locate symbol
                  "__llvm_profile_set_filename" referenced by ".../libdynamo_engine_oryon2.so"...），已回退 baseline
```
即：**三支实验档（oryon1 / oryon2 / c1ultra）全部无法 dlopen，应用静默回退 baseline**；
NDK 档位（baseline/x1…x925）不受影响。

### 2. 根因（bash 参数展开语义 + 我把强引用钩子的条件挂错了）
上一轮为了修"弱引用在共享库下解析成 nullptr、flush 静默失效"，把 JNI 钩子改成**强引用**，
并用 `${pgo_applied:+\"-DDYNAMO_PGO_HOOK=1\"}` 作为注入条件。**错在**：
`pgo_applied=0` 时该变量**已设置且非空**，`${var:+word}` 依然展开 —— 于是**每个构建（包括干净构建）都带上了强引用**，
而干净构建不链接 profile 运行时 → 两个符号永远未定义 → `dlopen` 必然失败。

### 3. 修复
1. 新增专用变量，只在**插桩构建**定义宏：
   ```bash
   PGO_HOOK_DEF=""
   if [ "$PGO_GENERATE" = "1" ]; then PGO_HOOK_DEF="-DDYNAMO_PGO_HOOK=1"; fi
   ...
   ${PGO_HOOK_DEF:+"$PGO_HOOK_DEF"}
   ```
2. **新增产物门禁**（这类缺陷必须被构建期拦下）：非插桩构建若存在未定义的 `__llvm_profile*` 符号 →
   `错误：… 非插桩构建却引用 profile 运行时符号（N 处）—— dlopen 必失败`，并 `fail=1` 中止。

### 4. 修复后证据
| 设备 | engine load |
|---|---|
| 一加 15 | `active=oryon2(-mtune=oryon-2-v1) requested=oryon2 fallback=false experimental=true cc=SDLLVM 19.0.0` |
| 红米 | `active=x2(-mtune=cortex-x2) requested=x2 fallback=false cc=NDK 30.0.16248370` |

三档产物未定义 profile 符号均为 **0**；`-fprofile-use` 版 oryon2 = 1,031,096 字节、`__llvm_prf_cnts=0`、
披露仍含 `已应用 PGO` 与 profile 路径。

### 5. 纪律修正（重要）
- **"应用能跑完"不是验证**。上一轮我据此宣称"PGO 版实跑正常"，但当时跑的其实是 baseline —— 该结论已作废。
- 验证档位生效的唯一判据是 **`engine load` 行的 `active=` 与 `fallback=`**（以及界面上的"当前生效/降级原因"）。
- 二进制/产物级检查必须用**直接 grep 或 readelf**；`strings` 默认不输出中文，用它核对中文披露会得到假阴性（本轮踩过）。

---

## 2026-10-12 深夜（第 7 轮）：全大核 ISA 运行期门禁（已实现并双向验证）

### 1. 判定器（数据源 = 实测，非推测）
新增 `hardware/CoreTopologyDetector.kt`：读 `/proc/cpuinfo` 的 **CPU part**（无需 root），
规则：**同构（所有核 part 相同）且不属于小核/中核家族** 才算"全大核"。
**fail-closed**：读不到 part（如 x86_64 模拟器）或含任何小核/中核 → 判为非全大核，ISA 档一律拒绝。

实测：
| 设备 | 每核 CPU part | 判定 |
|---|---|---|
| 一加 15（SM8850） | `0x002` × 8 | 同构全大核 ✓ |
| 红米（SM7475） | `0xd48`×1 + `0xd47`×3 + `0xd46`×4 | 异构，含小核 ✗ |
| 模拟器（x86_64） | 无 part | 非全大核（fail-closed）✗ |

### 2. 门禁位置与行为
`DynamoNativeBridge` 加载路径第 2b 步（ABI/翻译层判定之后、红线裁剪之前）：
候选档若属于 `AllBigCoreOnlyProfiles`（当前 = `oryon2isa`），必须先过全大核判定；
不满足则**拒绝加载**该库、回退 baseline，并置 `requestedUnavailable=true` + 中文 note（降级不静默）。

### 3. 新增 ISA 档 `oryon2isa`（单档属性，单一事实源在 profiles.cmake）
```
set(DYNAMO_MCPU_oryon2isa "oryon-2-v1")
set(DYNAMO_ISA_oryon2isa  "allbig")      # 触发 -mcpu
set(DYNAMO_TOOLCHAIN_oryon2isa "sdllvm")
```
产物实测：`-mcpu=oryon-2-v1`（无 `-mtune` 字面量）、**SVE 指令 1,526 条**、1,040,976 字节。
对照：同源码 `-mtune` 版 SVE = **0 条** —— 这正是必须有门禁的原因。

### 4. 双向验证证据（真机 engine load 原文）
一加 15（应放行）：
```
active=oryon2isa(-mcpu=oryon-2-v1) requested=oryon2isa fallback=false experimental=true
note=全大核 ISA 档 oryon2isa 已通过门禁：本机 8×0x002（同构全大核）
```
红米（应拒绝，且不得崩溃）：
```
active=baseline(-mtune=generic) requested=oryon2isa fallback=true
note=档位 oryon2isa 为全大核 ISA 专用档（-mcpu，产物含 SVE 指令），
     本机为4×0xd46 + 3×0xd47 + 1×0xd48（异构多核，含小核/中核）：拒绝加载，已回退基线
```
两台均无 SIGILL / Fatal signal 记录。

### 5. 三处一致性交叉校验（本轮踩坑后加的闸门）
`.evidence/check_isa_catalog.py` 校验：profiles.cmake 的 `DYNAMO_ISA_*` ↔ Kotlin
`AllBigCoreOnlyProfiles.IDS` ↔ `DynamoNativeBridge.EXPERIMENTAL_PROFILES`（输入归一化白名单）。
**坑**：Kotlin 归一化白名单漏了 `oryon2isa`，于是 prefs 里写着 `oryon2isa`、日志却是
`requested=baseline` —— 偏好被静默归一化，**门禁根本没机会执行**。这类"漏登记"必须由脚本拦住。

### 6. 披露修正
ISA 档的日志/界面标注改为 `-mcpu=<target>`（此前一律打印 `-mtune=`，对 ISA 档是失真）。

### 7. 仍未做（出 ISA 档前必须补）
- `scan_apk_allabi.py` 的 **SVE=0** 门禁对 ISA 档**预期会失败**，需为 `oryon2isa` 单独登记
  豁免与前提（"仅全大核设备可加载 + 运行期门禁已验证"），不能默默放过；
- ISA 档的**性能对照**（-mcpu vs -mtune 同机同用例）尚未测。

### 8. 台账文件被误覆盖（如实记录）
刷新 `.evidence/AUDIT_UNFINISHED.md` 时我**整体覆盖**了原《未完成目标审查（第 3 轮）》逐条详表；
该文件从未纳入 git（initial commit 中不存在），**无备份、不可恢复**。
已按需求原文（29 项 ADD + 5 项 CONFLICT）重建 78 行台账，状态列只写有证据的结论，
并在文件开头写明本次丢失事件。教训：**台账类"累积型"文件只能追加/局部编辑，禁止整文件覆盖**。

---

## 2026-10-13 凌晨（第 9 轮）：定向调优实测 —— **结论：现有调优杠杆全部不如基线**

### 1. 方法（先解决"测得准"）
- 基线漂移极大：同一 baseline 档在无控制条件下先后测到 **281.5** 与 **116.0 M-Ops/s**（2.4×），
  原因是短用例（7–9 s）的 boost 状态随热历史浮动。
- 处置：用 `su`（KernelSU，`su -c id` → uid=0）**锁定全核频率** `scaling_min=max=2112000`（两 policy），
  并在负载下验证锁定生效（连续 4 次采样均为 2112000 Hz）；测完**已恢复原始 min/max/governor（walt）**。
- 取样规则：交替 A/B、每次记录 `engine load` 的 active/fallback 与运行中频率范围；active 与请求不符的样本作废。

### 2. 结果（锁频 2.112 GHz，SPEC 766.femflow_r）
| 档位 | 编译配置 | M-Ops/s | 内存吞吐 | 相对基线 |
|---|---|---|---|---|
| baseline | NDK Clang 21，generic | **251.7 / 255.3** | 13.20 / 13.36 GB/s | **1.00** |
| x925 | NDK Clang 21，`-mtune=cortex-x925` | 258.1 / 260.1（未锁频时测） | 13.5 | 未在锁频下复测 |
| tunedfp | NDK Clang 21，generic + 浮点松弛 + LTO thin | 251.8 / 254.8 | 13.18 / 13.33 | **0.99×（持平）** |
| oryon1（NDK 版） | NDK Clang 21 + `-mtune=oryon-1` + 浮点松弛 + LTO | 226.5 / 230.4 | 11.87 / 12.06 | **0.90×** |
| oryon2 | **SDLLVM 19**，`-mtune=oryon-2-v1`（干净） | 173.3 | 9.07 | 0.61× |
| oryon2（PGO） | 同上 + 存储用例 PGO | 173.8 / 177.2 | 9.08 / 9.26 | 0.62×（PGO 无影响） |
| oryon2isa | SDLLVM 19，`-mcpu=oryon-2-v1`（SVE） | 118.4 | 6.17 | **0.42×** |

### 3. 结论（与官方资料一致）
- 官方语义：`-mtune` **只影响调度模型与特性假设，不启用新指令**；`-mcpu` 才启用 ISA
  （MaskRay 的 clang 选项说明 + LLVM 文档）。实测**定向调度在 Oryon 上反而是负收益**
  （x925 −9%、oryon-1 −10%），说明 LLVM 21 的通用模型对本用例已足够好，而定向模型并不贴合。
- 换到 SDLLVM 19 损失更大（−39%），`-mcpu`/SVE 再叠加损失到 −58% → **工具链代际的影响远大于参数调优**。
- 浮点松弛 + LTO **与基线持平**（0.99×，在噪声内）。
- 该用例含 `Repartition`（排序 + gather/scatter + `std::vector`），**内存行为主导**（锁频下 13.3 GB/s），
  因此指令选择类优化空间有限 —— 这与"所有杠杆打不动"的实测一致。
- **产品含义**：不得把上述调优档宣传为"更快"；baseline 才是本用例的最优档（默认档已是它）。
  调优档继续保留为实验档并如实披露。

### 4. 未完成的下一杠杆：代表性 PGO
- 已按官方推荐（IR 级 `-fprofile-generate`）构建插桩 tunedfp（`prf=1`）并装机；
- **采集失败**：插桩档在当前 `-fprofile-update=atomic` 下把 SPEC 算力用例拖到 5 分钟以上仍未结束
  （原子计数器在热循环里开销极大）→ 下次改用 `-fprofile-update=single`（LLVM 文档选项）重试，
  或改采存储用例 + SPEC test 档的混合 profile。
- 采集/合并/应用链路本身已验证可用（base64 拉取 + 同版本 llvm-profdata 合并 + `-fprofile-use`）。

---

## 2026-10-13 凌晨（第 10 轮）：采纳另一方的源码审查结论 —— 两处**真实缺陷**已修并取证

### 1. PerformanceBoost 生命周期缺陷（确认并修复）
- 事实：旧代码在 `DropCachesAsRoot()` 之后就 `setpriority(orig)` / `IoprioSetRaw(orig)` 就地恢复，
  **早于 worker 启动** → 真正跑压测的线程不受提升保护。
- 修复：新增 `app/src/main/cpp/run_boost.h` 的 **RAII `RunScopeBoost`**，在 `RunBenchmark` 共同前导处构造、
  在函数返回处析构 —— 覆盖 SPEC 与存储两条路径，任何提前 return 也正确恢复。
- 附注（实测修正）：本机上 `nice` 会被"棘轮"锁在 -10（第一次专业运行后 orig_prio 已是 -10），
  因此旧 bug 的**效果**被掩盖；修复后语义才真正正确。

### 2. 大核亲和性绑定（新增）
- `BuildBigCoreMask()` 依据 `/sys/.../cpufreq/cpuinfo_max_freq` 取最高频 90% 以上的核；
  本机 = **cpu6-7（4.61 GHz prime 档）**；红米那类异构机会自动落到 X2 上。
- 主线程（运行线程）在守卫构造时绑定，存储 worker 各自在启动时绑定；**无需 root**（对自身线程调用）。
- **如实披露**：写入 `res.privilege_method` 与 logcat，例如
  `大核绑定：已绑定到 cpu6-7（最高频 4.61 GHz 档，共 2 核）`；失败也照实记录（不静默）。
- logcat 取证：`run_boost: sched_setaffinity rc=0`、`run_boost: enable=1 pin=1 ...`。

### 3. 效果实测（SPEC 766.femflow_r）
| 条件 | 成绩（M-Ops/s） | 抖动 |
|---|---|---|
| 修复前 · 不锁频 | 281.5 / 287.1，**但另一次会话塌陷到 116.0** | 最多 2.4× |
| 修复前 · 锁频 2.112 GHz | 251.7 / 255.3 | 1.4% |
| **修复后 · 锁频 2.112 GHz** | 252.2 / 254.3 / 253.2 | **0.8%** |
| **修复后 · 不锁频（真实状态）** | **282.7 / 286.6 / 283.5** | **0.7%** |

结论（如实）：锁频条件下绑核与优先级**不改变**单线程 SPEC 成绩（本就同频）；
真实价值在**稳定性** —— 修复后不锁频三次落在 283±2，不再出现线程被 EAS 迁到低频核造成的塌陷。
**尚未做**：pin on/off 的正式对照（需加调试开关），以及多线程用例（存储 T=8）上的绑核策略验证
（当前策略把全部线程绑到 prime 核，多线程下可能过度集中，需按线程数选择 prime/big 掩码）。

### 4. 待办（更新）
1. 绑核策略按线程数自适应（threads ≤ prime 核数 → prime 掩码；否则 → 含性能核的更大掩码）。
2. pin on/off 正式 A/B（加 `debug.ufsbench.nopin` 调试开关）。
3. PGO 闭环改用 `-fprofile-update=single` 重采（atomic 在算力用例上开销过大，实测 5 分钟未跑完）。
4. ISA 档性能对照仍待做（当前 SDLLVM 系全线慢于基线，ISA 档暂不具备出包价值）。

### 5. 绑核策略与正式对照（第 10 轮补充）
- 策略改为**按线程数自适应**：`prime` 掩码 = 最高频 90% 以上的核（本机 cpu6-7 / 4.61 GHz）；
  `big` 掩码 = 排除小核（最高频 60% 以上；本机 cpu0-7）。线程数 ≤ prime 核数 → prime，否则 → big
  —— 避免多线程压测被过度集中到 1-2 个核上排队。
- 新增联调开关 `debug.ufsbench.nopin=1`：关闭绑核并**如实披露**（"大核绑定：已由 debug.ufsbench.nopin 关闭"）。
- 三种情形已取证（logcat）：
  单线程 SPEC → `threads=1` 绑 `cpu6-7`；`nopin=1` → `pin disabled by debug property`；复位后恢复绑核。
- **正式对照（不锁频、交替、各 3 次）**：
  | 组 | 成绩（M-Ops/s） | 内存吞吐（GB/s） |
  |---|---|---|
  | 绑核 | 285.5 / 285.1 / 284.6（均值 **285.1**，极差 0.9 ≈ 0.3%） | 14.93 / 14.92 / 14.89 |
  | 不绑核 | 280.5 / 277.2 / —（均值 ~278.9） | 14.68 / 14.50 |
  → **绑核稳定更快：算力 +2.2%、内存吞吐 +2.9%**，且抖动更小（0.3% 对 1.2%）。
  这是本轮唯一**实测优于默认配置**的优化项（`-mtune`/SDLLVM/ISA/LTO/浮点松弛均未跑赢基线）。

---

## 2026-10-13 凌晨（第 11 轮）：采纳归因结论并把**唯一有正收益的优化放进标准基线**

### 1. 归因结论（与实测数据一致，已并入本文件）
工具链代际（LLVM 21 vs SDLLVM 19，−39%）> 定向调度负收益（`-mtune` x925 −9% / oryon-1 −10%）>
ISA/SVE 反向暴跌（`-mcpu` −58%）> PGO 训练集错配（存储 profile 套算力用例，+0.5% 且在噪声内）。
根因是**访存受限**（锁频 2.112 GHz 下内存吞吐已压到 13.3 GB/s）+ 超大核 OoO 吸收了静态调度收益。

### 2. 决策落地（需求方："其优化手段部分允许放到正常标准基线"）
- **大核亲和性绑定 → 进入标准基线路径**：`RunScopeBoost` 构造函数中
  `if (pin_affinity) { PinCurrentThreadToBigCores(...) }` **位于** `if (!enable) return;` **之前**，
  即绑核与"是否专业模式"**解耦**，常规模式同样生效（代码级验证 + logcat 取证）。
  实测收益：算力 **+2.3%**、内存吞吐 **+2.9%**、抖动 1.2% → 0.3%。
- **其余调优手段保持实验档，且不得宣传为更快**：UI 文案已改写 ——
  baseline 标注为"公平基线（默认·实测最优）"，实验档区块注释写明"实验性参数探索，实测未优于基线"。

### 3. 披露收口修复（本轮发现并修复的真实缺陷）
- 现象：历史报告里 `专业手段…PerformanceBoost大核绑定：…` **缺分隔符**；且补充说明与我新增的
  `append_run_env()` **重复**；更严重的是**主返回点（行 1203）未被包裹** → 常规模式与 SPEC 路径
  **完全不披露绑核**。
- 修复：专业分支去掉内联 note，统一由 `append_run_env()` 收口；共包裹 **7 个返回点**（含主返回）。
- 实测文本（历史报告，原样摘录）：
  ```
  测试手段：专业手段（root）：参考 AOSP PerformanceBoost；并以 sync + drop_caches(写 3) 清空内核页缓存；
  运行环境：大核绑定：已绑定到 cpu6-7（最高频 4.61 GHz 档，共 2 核）；
  运行期提升（覆盖整个压测周期）：setpriority(-10) 成功、I/O 调度类 RT 成功
  ```

### 4. 仍未做
多线程存储用例的**成绩** A/B（当前只验证了机制：8 worker 绑 `cpu0-7`）；
代表性 PGO（改用 `-fprofile-update=single`）；与基线同代的 ISA 对照（NDK Clang 21 + `-mcpu=oryon-1`）。

### 6. 同代 ISA 对照（第 12 轮，带逐次硬门禁）
**动机**：此前的 ISA 结论（−58%）混入了 SDLLVM 19 的代际劣势（−39%），不能回答"ISA 本身是否有收益"。
**做法**：新增 `oryon1isa` 档 = **与基线同代的 NDK Clang 21** + `-mcpu=oryon-1`（启用 ISA），
其余编译参数与 baseline 相同；运行前逐次校验 `engine load active=requested`，不符即作废该样本。

**产物事实**：`oryon1isa` 的 `-mcpu` 字面量=1、`-mtune` 字面量=0，**SVE 指令 = 0 条**
（对比 SDLLVM 的 `-mcpu=oryon-2-v1` 会发射 1,526 条）→ LLVM 21 对该 workload 并未走 SVE 向量化路径。

**成绩（一加 15，未锁频，交替，门禁全部通过）**：
| 档位 | M-Ops/s | 内存吞吐 |
|---|---|---|
| baseline（`-mtune=generic`） | **287.8 / 289.5** | 15.06 / 15.14 GB/s |
| oryon1isa（`-mcpu=oryon-1`，同代） | **261.6** | 13.69 GB/s |

→ **同代条件下 ISA 定向仍慢约 9%**。结合此前 `-mtune` 的 x925 −9% / oryon-1 −10%，
可以确认：**在本用例（访存受限的 CG/SpMV）上，任何"定向到具体微架构"的编译选项都是负收益**，
与工具链代际无关。baseline（generic）才是最优档 —— 与产品默认一致。

**方法学补记（本轮踩到并已修）**：`pack_only.sh` 不会重跑变体脚本，新增档位必须先跑
`build_experimental_variants.sh` 再打包，否则库里没有该档 → 应用静默回退 baseline，
而 `engine load` 会如实显示 `active=baseline`（这正是"逐次门禁"必须存在的原因）。

> **样本数补记**：baseline 共 3 个有效样本（287.8 / 289.5 / 286.4，极差 1.1%）；
> oryon1isa 共 2 个有效样本（261.6 本轮 + 265.0 上一轮门禁运行），另有 2 次运行因
> 我的用例选择脚本未点中按钮而无成绩（不影响档位判定：门禁均显示 active=oryon1isa）。
> 结论不变：同代 ISA 档比 baseline 慢约 9%（261.6~265.0 vs 286.4~289.5）。

---

## 2026-10-13 上午（第 12 轮）：SPEC 750 双轴编译器矩阵（M2/M3 完成）

### 0. 工具链现状（需求："NDK 统一最新版"）
- 联网核对：Android NDK 最新为 **r30（clang-r574158c = LLVM 21）**，尚无 r31
  （来源：github.com/android/ndk/wiki/Changelog-r30、developer.android.com/ndk/downloads/revision_history）。
- 项目 `app/build.gradle.kts` 的 `ndkVersion = "30.0.16248370"` **已经就是最新版** ✓；
  本地另有 `android-ndk-r28b`（LLVM 19），**仅作为实验矩阵中的"19 代样本"**，不参与出包。

### 1. 四套编译器（均已本地就绪并核对版本）
| 代号 | 位置 | 版本 |
|---|---|---|
| NDK19(r28b) | .tools/android-ndk-r28b | clang 19.0.0 |
| SDLLVM19 | /opt/qcom/.../19.0.0.0 | clang 19.0.0（高通专版） |
| NDK21(r30) | .tools/android-sdk/ndk/30.0.16248370 | clang 21.0.0 |
| LLVM23 | .tools/llvm23 | clang 23.1.3 |

每个产物**自报编译身份**（编译期 `-DBENCH_TAG`，运行首行打印 `TAG ...`），杜绝"测的不是那个档"。

### 2. 反汇编复核（关键：参数是否真的生效）
| 产物 | SVE 指令数 | 含义 |
|---|---|---|
| A3 SDLLVM `-mtune=oryon-2-v1` | **0** | `-mtune` **不启用 ISA**（官方语义的实证） |
| A4 SDLLVM `-mcpu=oryon-2-v1` | **1159** | ISA 解锁生效 |
| A5 = A4 + ThinLTO | **149** | **跨代 LTO 丢失厂商 ISA 属性**：LLVM 21 不认识 `oryon-2-v1`（`error: unsupported argument`），链接期按默认 CPU 重新生成 |
| B1/B2/B3 generic | 0 | 基线 |

### 3. 测量方法（本轮最重要的产出）
- 设备端独立可执行文件（**绕开 UI 与 App 状态**），`taskset c0` 绑 prime 核，每轮间隔 2 s 冷却；
- **交错轮转**（同轮内跑完所有档位）+ **丢弃首轮暖机样本**；
- 结论：**非交错**测量会给出 A5 = +11% 的"漂亮"结论；**交错 n=6** 后同一对照只剩：

| 档位 | 中位数（丢弃首轮） | 相对 A1 | 说明 |
|---|---|---|---|
| A1 NDK19 generic | 678.1 | 1.00× | 19 代基线 |
| A4 SDLLVM `-mcpu`（SVE 1159） | 675.9 | 1.00× | **ISA/SVE 在本算子无收益** |
| A5 A4 + ThinLTO | 699.2 | **1.03×** | 唯一稳定正收益（均值口径 +5%） |
| B2 NDK21（LLVM 21） | 687.7 | 1.01× | 代际收益很小 |
| B3 LLVM23 | 680.9 | 1.00× | 与 21 代无显著差 |

- **如实修正预期**：任务书里"LLVM 19→23 代际跨度 60%~120%"的依据未在我方复现；
  本机 750 算子上实测代际收益仅 **1%~2.5%**，最大杠杆是 **LTO（3%~5%）**，ISA/SVE 为 0。
  这类"预期 vs 实测"的落差本身就是该科普功能的素材。

### 4. 仍未完成
- A5 的完整形态（+ 纯算力 PGO，`-fprofile-update=single`）未做；
- M4：结果页双维度柱状对比看板与归因卡片（卡片已实现，柱状图未做）；
- 归因卡片/通道提示的真机逐项验证。

---

## 2026-10-13 下午（第 13 轮）：SPEC26 剔除 + 用户自带源码工作区（合规架构落地）

### 1. 剔除 SPEC26（需求："剔除SPEC26的部分"）
- **5 个文件重命名**：spec_workloads.h→builtin_kernels.h、spec_gcc.cpp→builtin_compiler_sim.cpp、
  spec_femflow.cpp→builtin_fluid.cpp、spec_graph500.cpp→builtin_graph.cpp、spec_sealcrypto.cpp→builtin_ntt.cpp；
- **25 个文件改写**：命名空间 dynamo::spec→dynamo::builtin、Run*Workload→Run*Kernel、
  BenchmarkMode.SPEC_PORT→BUILTIN_KERNEL、specWorkloadId→builtinKernelId，
  以及全部面向用户的文案（"SPEC CPU 2026 移植版"→"内置微基准（非 SPEC）"、
  "SPECrate/SPECspeed"→"多任务/单任务口径"、"官方档位"→"内置档位" 等）；
- **残留自检 = 0**；构建通过；**MuMu（x86_64）装机冒烟测试：FATAL 行 0、进程存活**。

### 2. 设备与工具链现状（重要前提）
- 测试设备已变更：**一加 15 已不在**；现有 REDMI-(已脱敏)（红米 Note 12 Turbo，arm64）、
  127.0.0.1:16384（MuMu，x86_64）、emulator-5556；
- **三台设备都没有任何编译器**（clang/gcc/cc/tcc/make 全无）→ 结论：**"现场编译"必须由使用者
  一并提供设备端工具链**，App 不内置（体积与许可都不允许）。

### 3. 新增：用户自带源码工作区 app/src/main/java/com/ufs/bench/spec/SpecWorkspace.kt
- **外置工作区**（用户可直接用数据线放入）：/sdcard/Android/data/com.ufs.bench/files/spec/
  下含 src/<名称>/（源码树）、inputs/<名称>/（输入）、toolchain/cc（用户提供的编译器）、
  manifest.sha256（每行 <sha256>  <相对路径>）；
- **SHA-256 校验**：缺清单 / 缺文件 / 哈希不符 / 存在未登记文件 → 一律拒绝编译与运行（绝不静默）；
- **现场编译**：源码复制进**内置私有目录**（Android W^X 不允许执行外置存储文件），
  用用户提供的编译器 + 用户自定义参数编译，捕获 stdout/stderr 与退出码；
- **运行**：在内置目录执行产物，带超时；判定只依据真实退出码；
- 结果一律标注"内部相对基线"，不得表述为任何官方成绩。

### 4. 仍未完成（诚实列出）
1. 工作区的 UI（路径展示 / 校验 / 编译 / 运行 / 输出回显）尚未接入；
2. 端到端验证：需要设备端编译器才能验证"现场编译"（当前三台设备都没有）；
3. tools/spec2017/ 流水线仍只能编译不能跑（缺输入暂存与运行配方处理）；
4. SoC 专属调优档（x1~x925 / oryon / ISA）仍在 UI 与产物中，尚未按"专属调优取消"移除。

---

## 2026-10-13 傍晚（第 14 轮）：SPEC2017 必交文件名单 + 反篡改校验

### 1. 新工具 tools/spec2017/required_files.py
- **生成名单**：从 SPEC 官方安装树枚举每个基准真正需要的文件 —— src/ 全树（含 spec_qsort/、sha-2/、liblzma/ 等子目录）、
  Spec/ 配方（object.pm、flags.xml）、data/all/input + data/<档位>/input；
- **校验模式**：required_files.py --verify <required.json> --root <目录>。

### 2. 名单规模（6 个基准，test 档）
| 基准 | 文件数 | 合计体积 |
|---|---|---|
| 519.lbm_r | 11 | 1.37 MB |
| 505.mcf_r | 33 | 1.27 MB |
| 508.namd_r | 43 | 8.37 MB |
| 557.xz_r | 188 | 93.00 MB |
| 510.parest_r | 2417 | 22.67 MB |
| 502.gcc_r | 711 | 38.07 MB |
| **合计** | **3403** | **164.75 MB** |

产物：.spec2017-req/required.json（含逐文件 SHA-256 与体积）、manifest.sha256（扁平清单，供设备端校验）、
USER_MUST_PROVIDE.md（面向使用者的必交名单与布局说明）。

### 3. 当前工作集缺口
仅缺 **Spec/flags.xml**（每个基准 1 个）；源码与输入均已齐（此前 505/557 缺的 src 子目录已补齐）。

### 4. 反篡改校验（需求：避免恶意文件或乱传）
规则（宿主工具与 App 端一致）：
1) 逐文件 SHA-256 比对；2) 扩展名白名单（18 类，数据驱动枚举得出）；
3) 拒绝可执行（ELF/MZ 头，.so/.a/.o/.exe/.dll/.bat/.ps1/.apk/.dex/.jar）；
4) 拒绝路径穿越（.. / 绝对路径 / 树外符号链接）；5) 单文件 ≤ 256 MB、单基准 ≤ 2 GB；
6) 名单外文件一律判未登记（不静默忽略）。

实测（宿主工具）：合法树「全部通过」；注入 1 字节篡改 + evil.sh + 伪装 ELF 的 backdoor.h 后，
三项全部被抓（体积不符 / 未登记×2）→ 「存在风险，拒绝使用」。

App 端：SpecWorkspace.kt 已并入同样规则（securityScan/checkFile），编译通过。

---

## 2026-10-13 晚（第 15 轮）：V3 规划 M0 完成 + M1/M2 主体落地

### M0（P0 紧急合规）✅ 全部完成
| 任务 | 结果 |
|---|---|
| M0.1 .gitignore | 补 SPEC2017/、.spec2017*/、*.iso → git status 未忽略条目 0（PASS） |
| M0.2 移除 org.json | 删除 org.json:json:20231013（非自由许可），改用 Android 内置 org.json，无需改代码 |
| M0.3 fio 硬断言 | 加入 scan_apk_allabi.py；判据收敛为高信号特征（Flexible I/O tester / fio_version / libfio.so 等），
避免裸词 fio 误报自有类名 FioEngineImpl；两个 flavor APK 均通过 |

### M1（双 Flavor + 安全底座）主体完成
- Flavor：standard（com.ufs.bench）与 lab（com.ufs.bench.lab），含 FEATURE_ROOT_LAB / FEATURE_EXTERNAL_SPEC 开关；
- **专属调优取消**：profiles.cmake 精简为 baseline 单档；CMakeLists 硬编码的 x1/x2/x3/x4/x925 移除；
  jniLibs 里 6 个残留变体 so 删除 → **APK 10.3 MB → 4.1 MB**；门禁复跑「全部通过」（16KB 对齐 / SVE=0 / 依赖与插桩）；
- **特权分离沙箱** sandboxed_runner.cpp（511 行，编译通过，JNI 符号已在 so 中确认）：
  fail-closed 六步 —— unshare(CLONE_NEWNET) 断网、setgroups/setgid/setuid 降权、capset 清能力、
  PR_SET_NO_NEW_PRIVS、Seccomp-BPF 白名单（52 项，逐项 ABI 守卫，默认 KILL_PROCESS）、
  setrlimit（AS 2GB / FSIZE 100MB / CPU 600s / NPROC 64）+ 墙钟强杀；
  另含 ReadElfNeeded() 解析 DT_NEEDED 供静态准入复用。

### M2（Kotlin 侧安全组件）完成并编译通过
- ElfValidator.kt（127 行）：ELF 魔数/64 位/小端、e_machine==0xB7、#! 脚本拒收、DT_NEEDED 白名单（Bionic）与 glibc 黑名单；
- BundleManager.kt（175 行）：zip 导入、SHA-256 校验、路径穿越拒绝、staging→校验通过才就位（失败关闭）、runner.json 解析；
- SandboxedExecutor.kt（104 行）：JNI 沙箱调用 + 结果如实回传 + PrecompiledBenchmarkSummary 数据模型（M3.3）。

### 本轮踩坑（都已在代码里规避）
1. Kotlin 块注释**可嵌套** —— 文档注释里写 data/input/* 会吞掉后续代码（已改写法）；
2. BF​​P 宏在 C++ 下窄化报错（改为显式构造 sock_filter + 数组长度静态断言 <255）；
3. 32 位 ABI 缺 __NR_newfstatat 等 —— 白名单改为逐项 #ifdef 守卫；
4. 重写文件头注释时误删 package 声明 → 跨包引用解析失败（已补回）。

### 顺带推进：SPEC2017 构建线
- build_bench.sh 新增「源码树子目录自动 -I」并**排除 win32/win64/msvc**（否则 Windows 专用 inttypes.h 覆盖系统头）；
  缺失的条件源文件改为告警跳过；
- 结果：**519 / 505 / 508 / 510 已可构建**（4/6），仅 557.xz_r（需 xz 专用包含集）与 502.gcc_r（需 gcc 专用配方）待办。

### 待办
1. SpecWorkspace.kt（手机端现场编译）按 V3「废除手机端重型编译」已过时 —— 待删除或改为纯校验用途；
2. 设备端负向 POC 三连测（MuMu 可 adb root）：网络外联→SIGSYS、特权写→EACCES、死循环→超时强杀；
3. M3 Oracle 容差裁判（floatcompare）与执行编排；M4 看板（待与「不做 UI、结果直出」的要求对齐）。

---

## 2026-10-13 深夜（第 16 轮）：沙箱真机验证（部分通过）+ 规划两处修正建议

### 已完成
- SPEC2017 卸载腾空间：删除 SPEC2017 目录（3.9 GB，仅剩 ISO 因 NTFS ACL 删不掉，需在 Windows 侧删）、
  /home/Speccpu2017Rate*（4.9 GB）、.tools/android-ndk-r28b（2.2 GB）、.tools/llvm23（12 GB）→
  工作区 31 GB → 17 GB，D 盘可用 35 GB → 50 GB。（合规：只保留 .spec2017-req 纯哈希清单）
- 沙箱负向验证驱动与三个 POC 已构建（x86_64/arm64，全静态），在 MuMu（adb root）上实跑。

### 真机实测结果（原样）
| 用例 | 结果 | 判定 |
|---|---|---|
| POC-C 死循环 | signal=9、8.05s、error=超过墙钟上限，已强杀 | **墙钟强杀生效 ✓** |
| POC-A 网络外联 | 目标程序启动即失败（stderr: mmap failed: Out of memory），父进程超时强杀 | 失败关闭（安全）但**未完成预期演示** |
| POC-B 特权删除 | 同上 | 同上 |

对照实验（判别是配额还是 seccomp）：
- 放宽配额到 8 GB 后现象不变 → **不是 rlimit 问题**；
- 关掉 seccomp 后三个 POC 全部 signal=6（SIGABRT）→ **子进程建立沙箱的过程本身有问题**，
  说明除 seccomp 白名单缺项外，setuid/capset/rlimit 序列中还有一步会 abort。

### 规划（V3）中建议修正的两处
1. **seccomp 白名单不能拦 execve**：沙箱自身必须用 execve 启动目标程序。若按规划把 execve 列入
   KILL_PROCESS，则任何外部二进制都无法被启动（我方实测正是此现象）。正确做法：放行 execve，
   "禁止创建进程"改由**拒绝 clone/fork/vfork**保证（这三项天然不在白名单里，已被默认拒绝+秒杀）。
2. **拒绝 clone 会连带禁止多线程基准**：508.namd_r、510.parest_r 等多线程基准需要 clone(CLONE_THREAD)。
   建议：默认拒绝 clone；对声明多线程的组件（runner.json 里加 threads>1）放行
   clone 但用 seccomp 参数过滤只允许 CLONE_THREAD|CLONE_VM 组合。此点需你确认取舍。

### 另需补充的白名单项（实测得出）
arch_prctl（x86_64 TLS，缺失会导致启动即 mmap 失败）、prctl、statx —— 已加入；
仍需继续定位 setuid/capset 之后那一步 abort 的原因（下一步加逐步骤 stderr 日志）。

---

## 2026-10-13 深夜（第 16 轮续）：沙箱真机验证 —— 已验证项 + 白名单方法论修正

### 实测结论（MuMu x86_64，adb root）
| 观测项 | 结果 | 判定 |
|---|---|---|
| 沙箱序列完整性 | 步骤日志完整：netns→setgroups→setgid→setuid→capset→rlimits→chdir→execve | 建立流程 OK |
| 目标程序启动 | child:9-about-execve 后成功进入目标程序 | execve 放行正确 |
| 网络外联 | POC-A：signal=31（SIGSYS）、seccomp_killed=1 | 内核级阻断 OK（比 netns 更硬） |
| 墙钟强杀 | 早期构建实测 POC-C：signal=9、8.05s、error=超过墙钟上限，已强杀 | OK |
| 特权删除 | 参考文件 /data/local/tmp/sbtest/should_not_delete 始终未被删除 | 未能破坏 OK |

### 关键修正：白名单不能靠人工枚举
本轮反复出现「改了白名单反而启动期就缺项」（POC-C 连第一条 printf 都到不了）。
正确做法是让内核报告它拦了哪条系统调用：
1. 先用 SECCOMP_RET_TRAP（或 SECCOMP_RET_LOG）跑一遍真实基准，从 SIGSYS 的 siginfo.si_syscall
   或内核日志里枚举出全部实际用到的系统调用；
2. 据此生成最小白名单（而不是手写猜），再切回 KILL_PROCESS；
3. 每个基准（519/505/508/510/557/502）各跑一次枚举，取并集。

### 两条实测教训（已写进代码注释）
- Android scudo 分配器预留巨量虚拟地址 → RLIMIT_AS 会把它挡死（表现为目标程序启动即
  mmap failed: Out of memory）。内存上限默认不再用 RLIMIT_AS 施加；需要时显式开启并自担风险。
- 模拟器内核普遍没有 netns（/proc/self/ns 无 net，unshare -n 返回 EINVAL）→ 断网保证改由
  seccomp 默认拒绝网络系统调用承担（实测 POC-A 收到 SIGSYS）。真机若内核支持 netns 则两层同时生效。

### 诊断开关（仅用于定位，正式构建不应带）
环境变量 DYNAMO_SANDBOX_SKIP_SECCOMP=1 可临时不装 seccomp 过滤器。

### 沙箱验证最终状态（第 16 轮收口）

**已用真机实测证明可用的部分**（MuMu x86_64，adb root）：
| 层 | 证据 | 状态 |
|---|---|---|
| 特权分离（降权） | POC-B 输出 `POC-B uid=10123 gid=10123`（非 0） | 已验证 ✓ |
| 破坏性文件操作被拒 | 参考文件 `/data/local/tmp/sbtest/should_not_delete` 始终未被删除 | 已验证 ✓ |
| 墙钟强杀 | POC-C 死循环：`signal=9`、`error=超过墙钟上限，已强杀` | 已验证 ✓ |
| 网络阻断 | 某版本过滤器下 POC-A `signal=31`（SIGSYS）、`seccomp_killed=1` | 曾验证 ✓，当前版本待复现 |
| 沙箱序列完整性 | 步骤日志走完 netns→setgroups→setgid→setuid→capset→rlimits→chdir→execve | 已验证 ✓ |

**未解决（诚实记录）**：seccomp 过滤器在 `KILL_PROCESS` 默认动作下，子进程会在极早期收到 SIGSYS；
而把默认动作换成 `SECCOMP_RET_LOG`（放行+记录）后三个 POC 全部正常运行。
因此问题**确定**在过滤器的判定逻辑或白名单内容，而不是降权/配额/execve 路径。
精确观察：严格模式 stderr 只到 `child:1-before-netns`，即死于 `unshare` 前后（该步在装过滤器之前），
需要下一步用 `SECCOMP_RET_TRAP` + SIGSYS 处理器打印 `siginfo.si_syscall` 精确定位调用号。

**本轮另外的实质收获**：
1. 构建错误会被 `| tail -2` 吞掉 → 中间几轮我实际在跑陈旧二进制（已改为显式 grep error）；
2. Android scudo 与 RLIMIT_AS 冲突（目标程序启动即 mmap failed）→ 内存上限默认不再用 RLIMIT_AS；
3. 模拟器内核无 netns → 断网改由 seccomp 承担，真机可两层叠加；
4. 规划里“拦 execve”与“拦 clone”两条需修正（execve 必须放行；拦 clone 会禁掉多线程基准）。

---

## 2026-10-13 夜（第 17 轮）：沙箱负向验证在红米真机上全部通过 ✓✓✓

设备：Redmi Note 12 Turbo（REDMI-NOTE12TURBO(已脱敏)，aarch64，**已 root**，内核支持 netns）

### 三连测原样结果
| 用例 | 实测输出 | 判定 |
|---|---|---|
| POC-A 网络外联 | `netns=1 signal=31 seccomp_killed=1 seconds=0.01` | **网络被内核级阻断**（netns + seccomp 双层）✓ |
| POC-B 特权删除 | `exit=0` + stdout `POC-B uid=10123 gid=10123` + `unlink(/system/bin/sh) = -1 errno=30 (Read-only file system)` | **特权分离生效**（uid 已降到 10123）+ 破坏性操作被拒 ✓ |
| POC-C 死循环 | `signal=9 seconds=8.05 error=超过墙钟上限，已强杀` | **墙钟强杀生效** ✓ |
| 参考文件 | `/data/local/tmp/sbtest/should_not_delete` 完好 | 未能破坏 ✓ |

### 定位过程（本轮最有价值的方法论）
1. 先排除配额因素：把 RLIMIT_AS 放宽到 8GB 现象不变 → 不是配额；
2. 再排除过滤器整体：跳过 seccomp 后三个 POC 全部正常 → 问题在判定逻辑/白名单；
3. **最终用内核审计日志直接枚举**：把默认动作设为 SECCOMP_RET_LOG 跑一遍，
   `dmesg | grep -oE 'syscall=[0-9]+'` 得到 120 / 198 / 203，
   查 asm-generic/unistd.h 得 120=sched_getscheduler（无害，误拦）、198=socket、203=connect（正是要拦的）；
4. 只需补入调度查询类即收敛 —— 不再逐个猜，全部由内核证据驱动。

### 附带确认
- 真机 netns 可用（netns=1），模拟器内核无 netns，故断网在模拟器上由 seccomp 单独承担；
- 构建脚本曾用 `| tail -2` 吞掉编译错误，导致中间数轮实际在跑陈旧二进制（已修正为显式检查）。

---

## 2026-10-13 夜（第 18 轮）：BYOB 全链路严格模式实测通过 ✓✓✓

设备：Redmi Note 12 Turbo（aarch64，已 root，内核支持 netns）

### 交付链路（全部跑通）
源码 → 交叉编译（NDK r30, aarch64, -O3）→ 组装 BYOB 包（bin/ + data/input/ + runner.json）
     → 设备投放 → 沙箱执行（netns + 降权 + seccomp 白名单 + rlimit + 墙钟）→ 原始结果直出

### 严格模式实测（原样输出）
| 基准 | 沙箱状态 | 指标 | 校验 |
|---|---|---|---|
| ntt_bench | netns=1 exit=0 seccomp_killed=0 | 583.65 M-Ops/s、0.725 GB/s（内核 0.514s） | verified=1 ✓ |
| fluid_bench | exit=0（峰值内存 102 MB） | 183.54 M-Ops/s、9.432 GB/s（0.236s） | verified=1 ✓ |
| graph_bench | exit=0（峰值内存 167 MB） | 52.36 M-Ops/s（BFS 15.59s） | verified=1 ✓ |
| sim_bench | exit=0 | 0.502s（输出哈希 7fb9d66340c483f） | verified=1 ✓ |

要点：四个基准的**内置 oracle 全部 verified=1**，证明沙箱约束没有污染计算结果。

### 规划冲突的实证与解决：clone
审计日志显示真实基准被拦的只有两条：44=fstatfs、220=clone。
- fstatfs：无害，直接放行 ✓；
- clone：**multithread 基准（fluid/graph）必须用它创建线程**。按 V3 规划「拒绝 clone」会导致
  直接 SIGSYS（实测如此）。当前实现：放行 clone，同时**继续拒绝 fork / vfork / clone3**
  （真正的进程创建仍被默认拒绝+秒杀）。若要更严格，下一步可在 BPF 里按 flags 只放行 CLONE_THREAD。

### 方法论沉淀（本轮最实用的两条）
1. **内核审计日志枚举法**：把 seccomp 默认动作设为 SECCOMP_RET_LOG 跑一遍真实负载，
   再 `dmesg > 文件` 拉回宿主解析调用号 —— 白名单由证据生成，不靠人工猜；
2. 设备端执行位会被 adb push 重置，且 `su -c` 的嵌套引号极易失效 —— 一律写成脚本文件执行。

---

## 2026-10-13 夜（第 19 轮）：跨架构对照 + M3 组件完成

### 跨架构实测（同一 BYOB 链路，严格模式）
| 基准 | MuMu x86_64 | 红米 arm64 | 沙箱状态 |
|---|---|---|---|
| ntt_bench | 707.63 M-Ops/s（0.424s） | 583.65 M-Ops/s（0.514s） | netns=0/1，exit=0，verified=1 |
| fluid_bench | 135.60 M-Ops/s（0.320s） | 183.54 M-Ops/s（0.236s） | netns=0/1，exit=0，verified=1 |

**设计验证**：MuMu 内核无 netns（netns=0），沙箱**依然正常阻断网络** —— 证明
「netns 尽力而为 + seccomp 默认拒绝兜底」的双层设计成立：缺 netns 时由 seccomp 承担。

### M3 组件（全部编译通过）
| 文件 | 职责 |
|---|---|
| OracleVerifier.kt（97 行） | 容差裁判：逐行 token 比对，数字按 abs+rel 容差，其余精确比对；不符处原样列出（不粉饰） |
| PrecompiledRunner.kt | 编排：读 runner.json → 复查 ELF 准入与二进制 SHA-256 → 沙箱执行 → Oracle 判分 → 结构化摘要 + 原始输出直出 |
| SandboxedExecutor.kt | JNI 沙箱调用 + PrecompiledBenchmarkSummary 数据模型（M3.3） |
| BundleManager.kt | 组件包导入、SHA-256、路径穿越/脚本/ELF 三重准入 |

### 仍未完成
1. 把 PrecompiledRunner 接到 App 界面/入口（当前只有库与 CLI 验证，无 UI 路径）；
2. runner.json 的 affinity/cooldown 字段尚未真正驱动 run_boost（绑核/锁频/降温门禁）；
3. M4 展示层口径待你确认（结果直出 vs A/B 看板）。

---

## 2026-10-13 深夜（第 20 轮）：执行档接入沙箱（绑核 / 降温门禁）+ 一处设备级重要发现

### 已接入（runner.json 的 execution_profile 真正驱动执行舱）
| 档位字段 | 实现 | 实测 |
|---|---|---|
| affinity_policy=prime_cores_only | 读 /sys 计算 prime 核（>=90%% 最高主频）并与当前 cpuset 取交集后 sched_setaffinity | 见下方发现 |
| cooldown_target_celsius | 读全部 thermal_zone 取最高温，高于目标则按 2s 步进等待（上限 60s） | temp_before=37.4，cooldown_wait=0.0（门禁生效且未触发等待） |
| max_execution_seconds | 墙钟强杀（已在前轮验证 signal=9/8.05s） | 保持 ✓ |
结果披露新增：pin_cpus / temp_before_c / temp_after_c / cooldown_waited_s / affinity_cpus（不粉饰）。

### 设备级重要发现：prime 核被 cpuset 排除
红米 Note 12 Turbo（已 root）实测：
- 频率表：cpu0-3=1.80GHz、cpu4-6=2.50GHz、cpu7=2.92GHz；
- 但 `taskset ff` 后 Cpus_allowed_list 仍为 **0-5**；`taskset 70/f0/80` 分别得到 4-5 / 4-5 / EINVAL。
结论：该设备的 cpuset 允许集是 **CPU 0-5**，**cpu6/cpu7（含唯一的 prime 核）不可用**，
因此在该进程上下文里**无法绑到 prime 核**（强行绑会 EINVAL）。
处理：改为「prime ∩ allowed」取交集，交集为空时**不做假绑定**并在结果里如实报 allowed_picked=0。
（这也意味着此前在一加 15 上测得的绑核收益，在本机复现不了 —— 设备调度域不同。）

### 仍未完成
1. PrecompiledRunner 尚无 App 入口（仅库 + CLI 验证）；
2. M4 展示层口径待确认（结果直出 vs A/B 看板）。

---

## 2026-10-13 23:20（第 21 轮）：标准读写进行中 = 四张独立图表（读写阶段彻底隔离）

### 问题（需求方口径，已在代码中逐条核实）
标准读写是「阶段 1 纯读 → 阶段 2 纯写」两段绝对串行的测量，此前进行中只有两张**混表**：
一张吞吐（读 + 写）、一张延迟（读 + 写），读写共用一条时间轴与一个纵轴量程。后果：

1. 两阶段样本追加进同一条 timeSec 列表，写阶段的时间跳回 0 与读阶段重叠；
2. 写阶段回调里注入读阶段最终成绩（metric.copy(readMbps = finalReadBw …)），读的样本混进写的表；
3. 读延迟（本机实测 ≈4000 μs）与写延迟（≈9700 μs）同图同量程，读被压成贴底一条线。

### 改动
| 层 | 文件 | 内容 |
|---|---|---|
| 数据 | BenchmarkViewModel.kt | 新增 PhaseCurve / StandardPhaseCurves（读、写各一份：阶段内时间 / 吞吐 / 时延 / 阶段内完成容量）；withRealtime(metric, phase, slot) 增加 CurveSlot（READ_PHASE / WRITE_PHASE / SINGLE），读阶段只取 readMbps + readAvgLatencyUs，写阶段只取 writeMbps + writeAvgLatencyUs |
| 界面 | RunningScreen.kt | 按 spec.mode 分流：标准读写 → 新 StandardPhaseScatterCard（① 读取带宽 / ② 写入带宽 / ③ 读取时延 / ④ 写入时延 + 未开始阶段的占位文案）；混合并发与内置算力内核 → 原 RealtimeScatterCard 不变 |
| 兼容 | ScatterChart.kt | **未改动**：空系列早退、纵轴只汇总本图系列（天然各自量程）、每系列可带自己的 xValues |

### 真机实测（Redmi Note 12 Turbo 33ed7dff，lab release，1 GB 数据集 / 每阶段 10 s，专业模式）
| 证据 | 实测值 |
|---|---|
| 读阶段 · ① 读取带宽 | 独立纵轴 1950–2050 MB/s，实时打点；进度「第 1/2 阶段 · 已进行 7s / 10s」 |
| 读阶段 · ④ 写入时延 | 占位文案「阶段 2 尚未开始（标准读写先跑完纯读）」——无读阶段样本混入 |
| 写阶段 · ① 读取带宽 | 冻结读阶段曲线（纵轴 1990–2020）；实时指标 读取带宽 1999.0 / 写入带宽 786.3 MB/s |
| 写阶段 · ③ 读取时延 | 冻结，独立纵轴 **3850–4050 μs**，最小 3859.35 / 最大 4024.07 / 平均 3999.57 |
| 写阶段 · ④ 写入时延 | 实时，独立纵轴 **8000–14000 μs**，最小 8110.20 / 最大 13111.63 / 平均 9768.71 |
| 数据完整性 | 通过：已校验 5131264 个 4KB 数据块，0 处错误 |

截图存档：.evidence/four_tables_verify/1..4*.png

### 已知微瑕（未改，待确认）
阶段切换瞬间（phaseIndex 已置 1、写曲线尚无正样本的极短窗口）④ 的占位文案仍是「阶段 2 尚未开始」，
与同屏「正在测试写入」措辞相左；修法一行：占位文案按 readDone 分支。

### 未完成
1. 容量终止准则（CAPACITY）路径的四张表未做真机跑测（仅代码路径支持：每表横轴取本阶段完成容量）；
2. 混合并发（MIXED_SIMULTANEOUS）保持原「同轴两表」未改造（按需求方口径：读写同刻并发才该同轴）。


---

## 2026-10-13 23:30（第 22 轮）：修复导出分享卡的动态范围 —— 纵轴下界不再钉死在 0

### 根因（代码级定位）
ResultCardRenderer.scatterSection（分享卡位图渲染）此前写死：
niceScale(0.0, P99, clampZero = true) —— **下界恒为 0**。
于是本机量级的曲线全被压成贴顶的一条直线：

| 图表 | 数据实际范围 | 旧纵轴 | 观感 |
|---|---|---|---|
| 读吞吐 | 1947.1 – 2034.3 MB/s | 0 / 1000 / 2000 / 3000 | 2000 处一条直线 |
| 写吞吐 | 539.0 – 1095.6 MB/s | 0 – 1200 | 贴顶平线 |
| 读响应延迟 | 3880.8 – 4218.6 μs | 0 – 4300 | 4000 处一条直线 |
| 写响应延迟 | 7827.7 – 13891.5 μs | 0 – 14.0k | 贴顶平线 |

页内 ScatterChart 一直是「下界取数据最小值」，所以同一个用例在页内看着正常、导出后像没测出东西。

### 改动：纵轴口径统一到 planYScale（页内 / 分享卡同源，杜绝两处漂移）
| 文件 | 内容 |
|---|---|
| ui/components/ScatterChart.kt | 新增 internal YScalePlan + planYScale(values, clampZero, targetTicks, robust)：上界 P99（样本 ≥ 20），**下界取真实最小值**（不下压到 0，也不用 P1，避免把真实低谷贴成假的底线）；返回 belowCount / aboveCount 供如实披露。ScatterChart 改为调用它，并新增「低于下界的点贴底 + 图下注明」 |
| util/ResultCardRenderer.kt | 分享卡改用同一个 planYScale（原来 import 的 niceScale / quantile 已移除）；散点绘制补上贴底分支；图下新增截断披露行（与页内 axisNote 同口径） |

### 真机验证（Redmi Note 12 Turbo 33ed7dff，lab release，1 GB / 每阶段 10 s，专业模式）
同一份导出卡在新旧构建下的对比（全图存档 .evidence/export_card_before.png / export_card_after.png）：

| 图表 | 旧纵轴（修复前） | 新纵轴（修复后） | 新图实测 |
|---|---|---|---|
| 读吞吐 | 0 – 3000（直线） | **1850 – 2000** | 最小 1899.6 · 最大 1998.5 · 平均 1968.2 |
| 写吞吐 | 0 – 1200（直线） | **400 – 1200** | 最小 539.0 · 最大 1095.6 · 平均 884.8 |
| 读响应延迟 | 0 – 4300（直线） | **3800 – 4200** | 最小 3880.8 · 最大 4218.6 · 平均 4062.3；上界 P99 截断 1 点，已注明 |
| 写响应延迟 | 0 – 14.0k（直线） | **6000 – 14.0k** | 最小 7827.7 · 最大 13891.5 · 平均 9142.4 |
| CPU 占用率 | 0 – 100 | 0 – 100（保持） | 数据本身跨 0 – 103.5，零基正确，未改 |

页内「测试中」曲线行为不变（本来就是数据最小值下界）：读延迟仍为 3850 – 4050 μs，仅新增贴底分支与截断披露。

### 顺带发现（未改，待确认）
分享卡上「写吞吐散点图」右下角的 footer 写的是「稳态 1968.8 MB/s」——那是**读**吞吐的稳态值
（footer 用 detail.steadyStateMbps，与图表系列无关）。外观正确、语义错位，建议只在读图标或按系列给值。


---

## 2026-10-13 23:35（第 23 轮）：修掉「稳态」数值错位 —— 写表不再印读的稳态

### 根因
分享卡 / 测试中页 / 报告页的吞吐图字幕都直接印 detail.steadyStateMbps。
原生该字段是「读阶段 detail 的读 + 写逐点和」的均值（dynamo_engine.cpp 汇总段），
标准读写下写阶段的曲线在 writeDetail 里，但字幕仍取主 detail —— 于是写吞吐图右下角
印着读的稳态值（实测：图上平均 884.8 MB/s，字幕写 1968.8 MB/s）。页内与报告页同一处病灶。

### 改动
| 文件 | 内容 |
|---|---|
| ui/components/CurveSeries.kt | 新增 internal steadyStateOf(values)：跳过前 10% 预热取均值（与原生同一算法），**只吃本图曲线**；样本 ≤5 返回 null（宁可不写也不编数） |
| util/ResultCardRenderer.kt | 卡片 footer 改为按本图曲线现算；「稳定性」段落标题改为「稳定性（读阶段）」（有 writeDetail 时） |
| ui/screens/RunningScreen.kt | 字幕稳态改为本图现算；稳定性段标题加「（读阶段）」 |
| ui/screens/ReportDetailScreen.kt | 字幕稳态改为本图现算 |

### 真机验证（同一份导出卡，Redmi 33ed7dff，lab release）
| 位置 | 修复前 | 修复后 |
|---|---|---|
| 写吞吐散点图 footer | 稳态 1968.8 MB/s（读的） | **稳态 796.3 MB/s**（本图平均 791.0，最小 597.4，最大 973.7） |
| 稳定性段标题 | 「稳定性」 | **「稳定性（读阶段）」** |
| 读吞吐图纵轴 | 0–3000（直线） | 1850–2050 量级，形状可见 |
| 写响应延迟图纵轴 | 0–14.0k（直线） | 8000–14.0k，形状可见 |

截图存档：.evidence/steady_3.png（写吞吐 footer）、.evidence/steady_4.png（稳定性（读阶段）+ 写延迟 + CPU）。


---

## 2026-10-13 23:42（第 24 轮）：稳定性 / 掉速 / 超时按读、写各出一套（三个界面同步）

### 为什么必须分方向
抖动 / 掉速事件 / I/O 超时 / 系统调用错误都是**分方向**的量。原生为读、写两阶段各产出一份
NativeDetailedStats（写阶段在 summary.writeDetail），但三个界面此前只渲染读阶段那一份，
写路径的问题被读的数字盖住。本轮实测差异（同一次标准读写）：

| 指标 | 读阶段 | 写阶段 |
|---|---|---|
| 抖动（变异系数） | 0.92% | **8.12%**（约 9 倍） |
| 稳态吞吐 | 2011.6 MB/s | 800.0 MB/s |
| 掉速 / 超时 / 系统调用错误 | 0 / 0 / 0 | 0 / 0 / 0 |

只印读阶段那一份时，分享卡上写路径 8.12% 的抖动是**看不见的**。

### 改动（只改展示层，原生无需改动）
| 文件 | 内容 |
|---|---|
| ui/screens/RunningScreen.kt | 新增 private StabilityGroup(title, src)；原「稳定性与掉速」+「控制器与错误」两段合并为按方向各一组：稳定性与控制器错误（读阶段）/（写阶段） |
| ui/screens/ReportDetailScreen.kt | 新增顶层 private StabilityRows(src)（读、写各调一次），卡片内出现「读阶段」「写阶段」分节标题 |
| util/ResultCardRenderer.kt | 抽出 private stabilityCard(c, p, y, dirLabel, d)：分享卡画两张（读阶段 / 写阶段），各自 4 列数值 |

### 真机验证（Redmi 33ed7dff，lab release，1 GB / 每阶段 10 s）
- 屏内（测试中页 uiautomator 文本）：稳定性与控制器错误（写阶段）→ 稳态吞吐 800.0 MB/s、抖动 8.12%
- 分享卡（.evidence/split_5.png）：读阶段 0.92% 与写阶段 8.12% 两张卡并列
- 报告页：稳定性与错误卡片内出现「读阶段 / 写阶段」两节

### 过程中的一次编译失败（如实记录）
第一版把 StabilityRows 插到了 StorageReportDetail 的函数体内（顶层才能带 private），
Kotlin 报 Modifier 'private' is not applicable to 'local function'；已挪到文件顶层（imports 之后），
重建 BUILD SUCCESSFUL。


---

## 2026-10-14 01:05（第 25 轮）：内置微基准**整体更换**为上游官方基准（含来源与强度挡位）

### 换掉了什么
旧的四项是**本项目自研实现**（821 编译器负载模拟 / 750 NTT 模乘 / 766 流体 DG / 854 图分析），
既不是 SPEC 套件，名字却带"官方档"字样。现按需求整体更换为从 AndroidBenech/upstream 裁剪的
**上游官方源码**；旧实现的分派分支保留但**预设入口已移除**（下一步连同源文件一起删除）。

### 新内核（id 刻意与 SPEC 编号空间分开）
| id | 名称 | 上游来源 | 正确性校验 |
|---|---|---|---|
| 1001 | CoreMark 1.0 · 整数与控制流 | eembc/coremark @1f483d5（Apache-2.0） | 与上游公开已知 CRC 逐一比对（crclist 0xe714 / crcmatrix 0x1fd7 / crcstate 0x8e3a） |
| 1002 | STREAM · 持续内存带宽 | jeffhammond/STREAM @6703f75（STREAM License） | 四内核终值与上游同款递推期望值逐元素比对（eps=1e-8） |
| 1003 | tinymembench · 内存延迟与带宽扫描 | ssvb/tinymembench @a2cf6d7（MIT） | 带宽自洽 + 延迟随工作集单调不降（双判据） |

落点：app/src/main/cpp/bench/（include/bench_api.h + bench_common.c + 各内核目录 + builtin_bench.cpp 桥接层）。
**上游算法文件逐字节未改**（CoreMark 用 -Dmain=coremark_cli_main 嵌入；tinymembench 的 .S 在构建期
生成去掉 .func/.endfunc 的派生副本以适配 clang 集成汇编器）。

### 强度挡位（每个基准**自己的**参数，不用全局缩放系数）
| 基准 | 低性能 | 常规 | 拓展 |
|---|---|---|---|
| CoreMark | 2000 次迭代（快速自检，**不构成标准成绩**） | 官方自动标定 ≥10 s（单线程标准口径） | 官方标定 ≥10 s × 全核并列 |
| STREAM | 64 MiB · NTIMES=10 | 320 MiB · NTIMES=10（上游标准规模） | 640 MiB · NTIMES=20 |
| tinymembench | 扫描到 32 MiB | 扫描到 64 MiB ×3 | 扫描到 256 MiB ×5 |

### 比值口径（不冒充官方分数）
bridge 里三个自定基线常量（kRefCoreMarkIterPerSec=120000、kRefStreamTriadMbps=34000、
kRefTinyReadMbps=31900，取本机 baseline 档 -O3 实测），比值 = 实测/基线 ≈ 1.0 表示与本机基线持平。
UI 同屏声明已改为：「相对本机自标定基线，非上游官方分数」；调优声明也改为同时覆盖 SPEC 与上游基准。

### 真机验证（Redmi 33ed7dff，lab release，2026-10-14 01:00）
- 首页「内置基准」区已列出三项，介绍里带来源（eembc/coremark、jeffhammond/STREAM、ssvb/tinymembench）；
- CoreMark 低性能档实跑通过：徽标显示「内置基准 · 强度：低性能」，结果「输出校验通过（比值 0.849）」，
  卡片标题「强度：低性能（自检档，不出分）」✓；
- 构建：CMake 新增 bench_kernels OBJECT 库（PRIVATE 注入 CoreMark 编译期宏，绝不外泄）+
  project(... ASM)（否则 .S 不参与编译 → 链接期 undefined symbol aligned_block_copy_*，已踩过）。

### 未完成
1. STREAM / tinymembench 的真机实跑验证（脚本已能选中 STREAM，运行按钮点击需重试）；
2. 删除旧内核源文件（builtin_compiler_sim.cpp / builtin_fluid.cpp / builtin_graph.cpp / builtin_ntt.cpp）
   与其分派分支；
3. 内置基准结果的分享入口（当前仍按"非官方成绩"不提供分享，可再议）。


---

## 2026-10-14 01:50（第 26 轮）：严格审阅与抓总 —— 分数口径修复 / 旧内核删除 / SPEC 二进制与组件包导入

### A. 内置基准"分数问题"（模拟器上复现并修复，模拟器实测复验）
复现（MuMu 127.0.0.1:16384，CoreMark 常规档）：CPU 算力 175676.2 M-Ops/s、内存层次吞吐 0.00 GB/s、比值 1.464。
三处根因与修法：
1. **单位错 10⁶**：CoreMark 上游口径是 iterations/s，被按 "M-Ops/s" 展示 → 新增
   model/BuiltinMetrics.kt（按内核给真实量纲与换算），结果卡/详情页/历史列表三处统一；
2. **零量纲照显**：内核不产出的量纲返回 null，界面直接隐藏（不再有 0.00 GB/s 噪声）；
3. **模拟器上不可比的比值**：isEmulator 时结果卡与完成语都不给比值，改为「只展示原始读数」。
复验：整数迭代速率 175509.2 iterations/s、无内存量纲行、比值 — + 模拟器说明 ✓

### B. 旧内核整体删除
builtin_compiler_sim.cpp / builtin_fluid.cpp / builtin_graph.cpp / builtin_ntt.cpp 四文件删除；
dynamo_engine 分派、builtin_kernels.h 声明、CMake 源列表、Kotlin 内存/耗时估算、首页标题、
以及原生的 821/766/854「综合跑分」加权公式全部清除；全库 grep 旧编号（除 bench/ 注释）为 0 ✓
双 flavor 构建：assembleLabRelease / assembleStandardRelease 均 BUILD SUCCESSFUL ✓

### C. 用户可见旧措辞审阅（发现 4 处并修正）
ReportDetailScreen「官方 test 档 / 官方 test 档不计时…」、DashboardScreen 模式行
「内置微基准（单任务口径 · 官方 X 档规模…）」、OnboardingScreen「并内置 内置微基准 的三个工作负载移植版」、
BenchmarkViewModel「内置微基准工作负载 / 内置微基准执行中」→ 全部改为上游官方基准口径；修后构建通过。

### D. SPEC2017（DVD 安装介质）→ 编译二进制 → 导入组件包（实测）
1. 介质：G: CPU2017v1.0.5 → 工作区，**MD5 与官方 .md5 一致**（29b59f7c…），解包出完整 benchspec（全部 5xx_r/6xx_s）；
2. NDK r30 交叉编译 519.lbm_r / 505.mcf_r：arm64 + x86_64，动态与静态各一套；
3. 模拟器实跑 519.lbm_r（x86_64，test 规模，1.73 s）：MLUPS 31.71，确定性字段（网格统计、
   minRho 1.0 / maxRho 1.040865 / Mass 1.300963e+06）与官方参考一致（差异仅计时行与 e+006 写法）；
4. 按 app 包格式组装 519.lbm_r 组件包（runner.json + bin + data/input + data/output，2.7 MB）；
5. **组件包导入实测通过**（终端输出）：
   [import] 包大小 2773601 字节 / ok=true 文件=5 字节=10343919 /
   二进制 sha256=117fe3c5… / 通过：已就位 .../files/byob/519.lbm_r，二进制「通过：arm64 ELF，依赖 0 项全部在白名单内」

### E. 审阅发现的阻塞（未解决，如实记录）
1. **arm64 真机离线**：红米 33ed7dff 已从 adb 掉线；模拟器为 x86_64（带 houdini），
   执行 arm64 静态二进制**直接段错误** → 无法用模拟器替代 arm64 真机验证 run；
2. ~~ELF 准入白名单是 glibc 风格，NDK 动态二进制会被拒，必须静态链接~~
   **【2026-10-14 更正，Lead 自查发现的误判】**：ElfValidator 的白名单**本来就是 bionic 风格**
   （ALLOWED_SONAMES = libc.so / libm.so / libdl.so / liblog.so / libstdc++.so / libc++_shared.so / libandroid.so / libatomic.so），
   glibc 名字（libc.so.6 / ld-linux-aarch64.so.1）出现在 **DENIED_SONAMES** 里——上一轮我把拒绝名单误读成白名单。
   结论更正：**NDK 动态二进制本来就通过准入**，无需静态链接（组件包可保持 20 KB 量级，而不是 9 MB）；
   静态包的实测结论（依赖 0 项、准入通过）依然成立，只是"必须静态"的说法不成立。
3. **终端命令注入未打通**（我的 uiautomator 自动化没找准输入框）→ list/verify/run 的命令级验证待补。

### F. 其它审阅发现（卫生项）
- AndroidBenech 仍是独立套件：内核 + CLI + Kotlin 规模策略齐备，但**没有 JNI/运行层**（未接任何 app）；
- 其树内混入构建产物 AndroidBenech/src/main/cpp/src/memory_guard.o（建议清理）；
- app 与 AndroidBenech 的 bench 内核**内容逐字节一致**（diff 校验通过），属"同一内核两处消费"，需在文档中显式声明，
  否则将来两边各改一份就会漂移；
- 本工作区无 git 提交基线（status 全是未跟踪），"还原"只能依赖文件级备份，建议尽快建立基线。


### G. 终端命令链实测（模拟器 127.0.0.1:16384，2026-10-14 08:2x）
导入后的三条命令全部走通（终端原始输出）：

    > list
    benchmark_id            bytes  runner   binary
    519.lbm_r             9029712  有        lbm_r

    > verify 519.lbm_r
    binary      : /data/user/0/com.ufs.bench.lab/files/byob/519.lbm_r/bin/lbm_r
    sha256      : 117fe3c59db233207b9b85e2c6567d279da9d287e9029fb6fd9064d643484329
    bytes       : 9029712
    elf.ok      : true
    e_machine   : 0xb7
    is64        : true
    message     : 通过：arm64 ELF，依赖 0 项全部在白名单内

    > run 519.lbm_r
    开始执行（沙箱）…
    exit/signal : -1 / 0
    wall_seconds: 0.000
    max_rss_kb  : 0
    oracle      : 未通过/未配置  被测输出文件缺失：lbm.out

解读：**执行链与判分链都是诚实失败的** —— 模拟器是 x86_64（宿主无 arm64 执行能力），
二进制根本没跑起来（0.000 s、exit -1），oracle 于是如实报「输出文件缺失」，
**没有产生任何虚假成绩**（正是需求方要的行为）。arm64 真机上的成功路径仍需红米解锁后验证。

### H. git 基线（本轮建立）
- 提交 c3c4c69：175 文件 / 34,992 行新增；工作区已 clean；
- .gitignore 补充：AndroidBenech/upstream/（1.2 GB 素材，按 SOURCES_MANIFEST.md 可重新拉取）、
  a.out / *.7z / *.fio / ref_zip/ / .buildlogs/；
- 复核：SPEC2017/（4.1 GB 许可内容）与 .tools/（13 GB SDK）均未入库。


### I. 动态二进制准入实测（更正后的验证，模拟器 08:3x）
用**动态链接**的 arm64 二进制重打组件包（57 KB，二进制 20 KB），导入与 verify 均通过：

    [import] 包大小：58435 字节
    [import] ok=true 文件=5 字节=1334343
    [import] 二进制 sha256=66ec58c8…
    [import] 依赖=libm.so, libdl.so, libc.so
    [import] 通过：已就位 .../files/byob/519.lbm_r，二进制 通过：arm64 ELF，依赖 3 项全部在白名单内

    > verify 519.lbm_r
    e_machine   : 0xb7
    is64        : true
    needed      : libm.so, libdl.so, libc.so
    message     : 通过：arm64 ELF，依赖 3 项全部在白名单内

结论：**G 节的更正得到设备端证实** —— bionic 白名单接受 NDK 动态二进制，
组件包无需静态链接（57 KB vs 2.7 MB），静态方案只在需要免依赖分发时才有意义。

### J. 本轮状态与唯一剩余阻塞
- 已完成：git 基线（c3c4c69 / c76346b，工作区 clean）、终端命令链实测（list / verify / run）、
  动态包准入实测、文档更正；
- **唯一剩余阻塞：红米 33ed7dff 处于锁屏状态**（dumpsys power: mWakefulness=Dozing，界面停在解锁页）。
  arm64 真机的 run 成功路径（沙箱执行 + oracle 判分 + 耗时）必须解锁后才能验证；
  在 x86_64 宿主上 run 会且应当诚实失败（exit -1 / 0.000 s / oracle 报输出缺失），不得据此推断真机行为。


### K. 判分缺口发现与修复：ignore_line_patterns（本轮新增能力）
**发现**：SPEC 类基准的官方输出里含**每次运行都不同**的行（LBM 的 usr/wct/MLUPS 计时行、
以及本实现多打印的 allocateGrid 行）。OracleVerifier 是逐行 token 比对，
这些行必然不符 → **正确实现也会被判"未通过"**（上一轮 run 在 x86_64 上没跑到判分环节，所以没暴露）。

**修法**（对齐 SPEC 官方 runrules 的排除口径）：
- runner.json 的 oracle_rule 新增可选字段 ignore_line_patterns；
- OracleVerifier.verify 增加 ignorePatterns 参数：命中任一子串的行**两侧同时过滤**，
  并在判分明细里如实写「按 ignore_line_patterns 忽略 N 行」（不隐藏忽略行为）；
- JobConfig / BundleManager.parseRunnerJson / PrecompiledRunner 调用点同步；
- 519.lbm_r 的包按此重打（patterns: usr: / wct: / MLUPS / allocateGrid）。

**验证状态**：代码编译通过（BUILD SUCCESSFUL）；**判分行为的设备端验证仍卡在同一处** ——
需要 arm64 真机执行成功后才有输出文件可比对；x86_64 模拟器上 run 连二进制都起不来（exit -1），
判分只会如实报「输出文件缺失」，无法用来验证过滤逻辑。

### L. 唯一剩余阻塞（重复强调，避免误判状态）
红米 33ed7dff **处于锁屏**（isKeyguardShowing=true / mDreamingLockscreen=true，通知栏压在最上层）。
解锁并保持亮屏后，一条脚本即可补完最后两件事：
1. 导入 519.lbm_r_dyn.zip（58490 字节，含 ignore_line_patterns）→ verify（应显示依赖 3 项 + 通过）；
2. run 519.lbm_r → 沙箱执行（预期 1~3 s）→ oracle 与 data/output/lbm.out 容差比对 → 给出耗时/退出码/判分明细。


---

## 2026-10-14 08:4x（第 27 轮）：SPEC2017 官方口径确认 + 组件流水线 + 仓库整理

### A. 关键发现：官方口径就是 -DSPEC
起初按"能编过就行"的方式编译，结果与官方参考输出对不上：
- lbm：多出 `LBM_allocateGrid: allocated 204.5 MByte` 两行（源码里那两行在 `#if !defined(SPEC)` 下）；
- mcf：输出 20 行、数值轨迹与官方 58 行参考完全不同（官方做了多轮细化，我们一轮就收敛）。

线索来自基准自带的 `Spec/flags.xml`（列出 SPEC 这个宏）。加 `-DSPEC` 后：
- **lbm 18/18 行与官方参考一致**（唯一差异是 `1.300000e+06` vs `1.300000e+006` 的指数写法，
  token 级数值比较等价）；
- **mcf 58/58 行完全一致** —— 还须补 `-DSPEC_HAVE_INTTYPES_H` 并链接基准自带的 `src/spec_qsort/*.c`
  （官方用 SPEC 自己的 qsort，排序稳定性不同会改变迭代轨迹）。

结论：**漏掉 -DSPEC 会得到"能跑但口径不同"的结果**；上一轮用 ignore_line_patterns 把多出来的行
过滤掉属于治标，本轮改成按官方口径重编（过滤能力保留，用于时间/分配等本质不确定的行）。

### B. 组件流水线（新增 tools/spec2017/build_components.py）
流程：**宿主口径自检 → NDK 交叉编译 → 打包 → 清单**。
- 宿主自检：用宿主 gcc 以同一套宏编译运行，与 `data/<scale>/output/<ref>` 逐行比对
  （token 级数值容差 1e-4，忽略空行与声明忽略的行）；只有 PASS 才出包 —— 这是"忠实复现官方口径"的证明；
- 交叉编译：arm64（动态 + 静态）与 x86_64（动态）；
- 打包：`bin/<exe>` + `data/input/*` + `data/output/<ref>` + `runner.json`
  （command_args / stdout_file / oracle_rule 容差 / ignore_line_patterns）；
- 清单：`MANIFEST.json`。

本轮产物：

    519.lbm_r   56 KB   宿主自检 PASS（与官方参考一致，15 行有效行）
    505.mcf_r  402 KB   宿主自检 PASS（与官方参考一致，56 行有效行）

判分侧同步两处：`ignore_line_patterns` 改为子串语义使用；**空行两侧不参与比对**
（官方参考与实测输出的空行位置可能不同，空行不承载信息）。

### C. 组件预置（解锁后即可直接跑）
两个组件包已推送到**红米 33ed7dff** 与**模拟器 127.0.0.1:16384** 的 `/sdcard/Download/`
并触发媒体扫描，应用内「终端 → 导入组件包(.zip)」即可选中。

### D. 仓库整理
- 乱码文件名修正：`UFSBenchmark_%E7%B3%BB%E7%BB%9F%E5%B7%A5%E7%A8%8B.md` → `UFSBenchmark_系统工程.md`；
- 清理构建垃圾：`a.out`（aarch64 残留）、`1`（误重定向产生的 awk 报错文件）；
- 新增 `tools/spec2017/README.md`（流水线用法与踩坑约定表）、`docs/README.md`（文档索引）；
- 根 `README.md` 增补第七节：仓库布局 + 组件流水线 + 官方口径说明；
- 提交：`6fc778c`（整理 + 组件）、`f567292`（修复 README 第七节）。

**整理时踩的坑（记录以免重犯）**：用 `printf "%s\n" "<含反引号的 Markdown>"` 写文件时，
反引号被 bash 当作命令替换执行，导致写入内容出现空洞（`-DSPEC: command not found`）。
正确做法是用**引号 heredoc**（`<<'EOF'`）写含反引号/`\$` 的文本。


---

## 2026-10-14 13:2x（第 28 轮）：二进制测试（BYOB）功能**真机跑通** —— 一路修掉 4 个真 bug

目标：用 SPEC2017 介质编出的组件包，在 arm64 真机（红米 33ed7dff）上走完
「导入 → 准入 → 沙箱执行 → 官方参考判分」。结果：

    519.lbm_r  exit/signal 0/0   wall 6.246 s   max_rss 422080 KB
               oracle 通过  参考 15 行 / 实测 15 行，比对 15 行，容差 abs=1e-7 rel=1e-4：一致
    505.mcf_r  exit/signal 0/0   wall 11.722 s  max_rss 378732 KB
               oracle 通过  参考 56 行 / 实测 56 行，比对 56 行，容差 abs=1e-7 rel=1e-4：一致
    沙箱九步（stderr 原样）：child:1-before-netns → 2-netns-done → 3-setgroups-done →
              4-setgid-done → 5-setuid-done → 6-capset-done → 7-rlimits-done →
              8-chdir-done → 9-about-execve

### 过程中定位并修掉的 4 个真 bug（都有实测证据，不是猜测）

1. **沙箱助手根本没落地** —— AGP 默认 `extractNativeLibs=false`，`libbyob_run.so` 留在 APK 内，
   设备 `lib/arm64/` 目录是**空的**，运行器找不到可执行文件 → 两台设备都表现为
   `exit -1 / wall 0.000 s / max_rss 0`（**不是 ABI 问题**：x86_64 模拟器与 arm64 真机同样失败）。
   修法：`packaging { jniLibs { useLegacyPackaging = true } }`（见 app/build.gradle.kts）。

2. **沙箱降权到固定 uid 导致 chdir 失败** —— 助手默认 `BYOB_UID/GID = 10123`（早期 bring-up
   把包放 `/data/local/tmp` 时可用）；而 BYOB 组件包在**应用私有目录**（0700，属主应用 uid），
   降权后 `chdir(workdir)` 失败 → 助手 `_exit(128)`。实测 stderr 停在 `child:7-rlimits-done` 即此因。
   修法：`PrecompiledRunner` 显式传 `BYOB_UID=<应用 uid> BYOB_GID=<Os.getegid()>`；
   netns/seccomp/capset/rlimits 四层隔离不受影响（仅进程身份改为调用方）。

3. **终端 run 阻塞主线程 → ANR** —— `doRun` 里 `PrecompiledRunner.run` 会
   `ProcessBuilder(...).waitFor(...)` 阻塞，而它由 `rememberCoroutineScope` 启动、默认在
   `Dispatchers.Main`；实测弹出「UFS Benchmark 没有响应」。
   修法：`withContext(Dispatchers.IO) { PrecompiledRunner.run(ctx, id) }`。

4. **mcf 判分拿错文件** —— 组件包 `stdout_file` 原为 `mcf.out`，而 mcf **自己**会写同名文件
   （仅 1 行目标值），运行器因此不落盘捕获的 stdout，判分拿 1 行数值去比官方 56 行报告 → 误判不符。
   修法：`stdout_file` 改为程序不使用的 `report.out`（流水线内已注释说明）；修后 56/56 行一致。

### 无界面运行入口（本轮实测可用）

    adb shell am force-stop com.ufs.bench.lab          # 必须先停：extra 只在 onCreate 处理
    adb shell am start -n com.ufs.bench.lab/com.ufs.bench.ui.MainActivity --es spec_run <id>
    adb shell cat /sdcard/Android/data/com.ufs.bench.lab/files/byob/<id>.result.txt

两个坑：① 包名是 `com.ufs.bench.lab` 而**类命名空间是 `com.ufs.bench`**（写错会 Error type 3）；
② Activity 在栈顶时 intent 只投递不重建，必须先 force-stop。

### 组件包参数约定（本轮固化）

| 组件 | 输入位置 | command_args | stdout_file | 说明 |
|---|---|---|---|---|
| 519.lbm_r | 包根目录 | `20 reference.dat 0 1 100_100_130_cf_a.of` | `lbm.out` | 程序会把文件名回显进输出，故用裸名 + 输入放运行目录根（SPEC 官方姿势） |
| 505.mcf_r | data/input/ | `data/input/inp.in` | `report.out` | 运行器会把 data/ 开头的参数映射为包内绝对路径 |


---

## 2026-10-14 13:4x（第 29 轮）：终端改为「只报已完成 + 产物位置」，并给出绑核建议（Scene）

需求：终端界面只显示「已完成」与产物位置；并提供绑核建议，由用户用 Scene 对**特定线程**操作。

### A. 终端输出（真机实测原样）

    > run 519.lbm_r
    开始执行（沙箱）…
    [完成] 519.lbm_r  退出码 0  耗时 1.211 s  峰值内存 412.3 MB  判分 通过
    产物位置：
      · 结果文件（完整输出）  /storage/emulated/0/Android/data/com.ufs.bench.lab/files/byob/519.lbm_r.result.txt
      · 组件包目录            /data/user/0/com.ufs.bench.lab/files/byob/519.lbm_r
      · 组件二进制            519_lbm_r_aarch64
      · 查看完整输出          输入 cat 519.lbm_r

改动：`doRun` 不再把 `toText()`（含几十行 stdout/stderr）糊在终端，而是
① 一行完成摘要；② 四个产物位置；③ 判分未过时附一行原因；
完整输出照旧落盘到 `Android/data/<包名>/files/byob/<id>.result.txt`（终端与无界面入口共用同一份产物），
用 `cat <id>` 查看。

### B. 绑核建议（新增 util/CpuAffinityAdvice.kt）

键点是**区分两套放行集合**，否则建议会误导（本轮先写了错版本、真机复验时发现并修正）：

    · 小核     CPU 0,1,2,3    最高 1.80 GHz
    · 大核     CPU 4,5,6      最高 2.50 GHz（不在**应用自身**放行集合内：绑应用自己的线程会被拒；绑基准进程可以）
    · 超大核    CPU 7          最高 2.92 GHz（同上）
    · 推荐绑定：7（超大核，最高频）
    · 放行集合：应用自身 0,1,2,3,4,6；基准进程（root 沙箱）0,1,2,3,4,5,6,7
    · Scene 操作：进程管理 → 找到进程 <二进制名>（按应用分组时它在 com.ufs.bench.lab 名下）
                  → CPU 亲和性/线程绑定 → 勾选推荐核心；只想处理基准线程时在 Scene 线程列表里按线程名挑

数据来源与依据：
- 簇划分：`/sys/devices/system/cpu/present` + 每核 `cpufreq/cpuinfo_max_freq`（同频归一簇）；
- **应用自身**放行集合：`/proc/self/status` 的 `Cpus_allowed_list`（普通应用可读；本机实测 `0-4,6`）；
- **基准进程**放行集合：`/sys/devices/system/cpu/online`（基准进程由 `su` 沙箱启动，实测 `0-7`，故绑 prime 核 7 有效）；
- 若推荐核不在应用自身集合内，正文明确写"绑应用自己的线程会被拒、绑基准进程可以"——避免上一版
  直接推荐 CPU 7 却不加说明的误导。

### C. 说明（诚实性）
- 应用**自身不改系统调度**（zygote cpuset 下无权限改亲和性），只输出建议；
- 同一组件两次运行耗时差异明显（lbm 首轮 6.247 s → 本轮 1.211 s），来自冷启动/页缓存与热状态，
  单次读数不足以作为结论，绑核与多轮对比才是有意义的用法——文本里也这样写了。

