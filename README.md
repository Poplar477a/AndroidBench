# UFS Storage Benchmark (Android Native)

> **基于 FIO 与 Iometer 架构移植的 Android 移动端存储性能基准测试工具**

---

## 一、 项目背景与架构理念

在 Android 移动平台上，随着 **UFS 3.1** 与 **UFS 4.0** 闪存的普及，连续读取带宽已突破 **4000 MB/s**，随机 IOPS 超越 **300K**。然而，现有的移动端测试工具（如 AndroBench 等）往往存在以下痛点：
1. **测试引擎老旧**：未能适配高版本 Linux 内核与 Android 10+ 分区存储架构；
2. **缺乏多维度存取规范**：无法自定义像 Iometer 那样的多队列、读写混合百分比、随机度等复杂业务模型；
3. **RAM 缓存污染（PageCache 干扰）**：未强制使用 `O_DIRECT`，测得的是运行内存速度而非真正的闪存裸速。

本项目结合了存储基准测试领域的两大行业标杆：
- **Iometer 模型参考**：移植其 **Access Specifications**（存取规范模型：Transfer Request Size、Percent Random/Sequential、Percent Read/Write、Queue Depth、Workers Concurrency），并在 C++ 层实现了基于 `O_DIRECT` 的内置原生压测核心（**Dynamo Native Engine**）。
- **FIO 调度引擎**：利用行业标准 **Flexible I/O Tester (FIO)**，通过交叉编译打包为 Android 可执行库，提供流式实时 JSON 输出与专业存储压测能力。

---

## 二、 系统架构设计

```
┌────────────────────────────────────────────────────────┐
│              Android UI (Jetpack Compose)              │
│  - DashboardScreen: 实时仪表盘 (MB/s, IOPS, 延迟)       │
│  - SpecificationScreen: Iometer 存取规范多维定制面板    │
│  - ReportScreen: 历史测试汇总与对比                    │
└──────────────────────────┬─────────────────────────────┘
                           │ (Coroutines / StateFlow)
┌──────────────────────────▼─────────────────────────────┐
│                 BenchmarkViewModel                     │
│  - 状态机管理 (IDLE -> PREPARING -> RUNNING -> DONE)    │
│  - 自动 drop_caches (Root 环境)                        │
└─────────────┬────────────────────────────┬─────────────┘
              │                            │
              ▼                            ▼
┌───────────────────────────┐ ┌──────────────────────────┐
│  Dynamo Native Engine     │ │  FIO Kernel Engine       │
│  - C++17 纯原生 (JNI)     │ │  - 交叉编译 libfio.so    │
│  - O_DIRECT 绕过 PageCache│ │  - POSIX AIO 多队列调度  │
│  - 4KB 内存对齐寻道       │ │  - 流式 JSON 状态解析    │
└───────────────────────────┘ └──────────────────────────┘
              │                            │
              └─────────────┬──────────────┘
                            ▼
              ┌──────────────────────────┐
              │  Android Flash (UFS 4/3) │
              │  - F2FS / EXT4 原生分区  │
              └──────────────────────────┘
```

---

## 三、 测试参数矩阵与规格

### 1. 块大小 (Transfer Request Size)
- **4 KB**：极小文件随机读写，直接决定系统流畅度与应用冷启动响应。
- **128 KB**：日常音视频流缓冲与典型文件读写。
- **1 MB / 2 MB**：超大文件连续吞吐，反映 UFS 多通道硬件极限带宽。

### 2. 队列深度与并发线程 (Concurrency)
- `Q1T1`：单线程单队列，真实单任务绝对延迟体现。
- `Q8T1 / Q8T4`：中重度后台并发负载。
- `Q32T16`：重度多线程与深度队列，跑满 UFS 4.0 主控多通道并发极限。

### 3. 访问模式 (Pattern)
- **SEQ (顺序读写)**：峰值吞吐量。
- **RND (随机寻道)**：IOPS 压测。
- **MIX (读写混合)**：支持 **0% ~ 100%** 自由调节（内置经典 70% 读 / 30% 写 Workstation 业务模型）。

---

## 四、 编译与运行说明

### 1. 编译 Android 应用
本工程为标准 Android Gradle 工程，在 Android Studio 中打开或通过命令行编译：

```bash
# Linux / macOS / WSL
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```

APK 产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

### 2. 交叉编译 FIO (可选)
如果需要启用 FIO 引擎，运行提供的编译脚本即可自动下载源码并通过 Android NDK 编译：

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk
bash tools/build_fio_arm64.sh
```
产物将自动放置在 `app/src/main/jniLibs/arm64-v8a/libfio.so`，安装时系统将自动赋予执行权限。

---

## 五、 关键性能保证（规避移动端陷阱）

1. **直接 I/O 绕过缓存**：
   所有读写句柄均开启 `O_DIRECT` 标志，强制所有读写 I/O 从闪存主控物理芯片经过，杜绝了 RAM 缓存导致的几万 MB/s 虚假成绩。
2. **规避 FUSE 损耗**：
   自动识别并首选内部私有存储挂载点（直接与底层的 `f2fs` 或 `ext4` 交互），避免 Android 10+ 分区存储通过 `/sdcard` (FUSE 用户态中转层) 带来的 15%~30% 性能衰减。
3. **空间安全预分配**：
   在正式压测计时前，先以块对齐方式连续写入预热数据（填充防透明压缩的伪随机字节），避免测试过程中的元数据分配导致延迟抖动。

---

## 六、 定向调优档位与构建开关（2026-10 更新）

### 1. 打包的 arm64 档位（共 10 支，唯一事实源 = app/src/main/cpp/profiles.cmake）
| 档位 | 工具链 | 调优方式 | 说明 |
|---|---|---|---|
| baseline | NDK r30 Clang 21 | 无（generic） | 公平基线，所有设备可用 |
| x1 / x2 / x3 / x4 / x925 | NDK r30 Clang 21 | -mtune=对应 Cortex 大核 | 各代 Cortex 大核定向 |
| c1ultra | 独立 LLVM 23.1.3 | -mtune=c1-ultra | 实验档（实验性 = 非 NDK 内置工具链） |
| oryon1 / oryon2 | Snapdragon LLVM 19 | -mtune=oryon-* | 实验档，骁龙 8 Elite 系列 |
| oryon2isa | Snapdragon LLVM 19 | -mcpu=oryon-2-v1 | 全大核 ISA 专用档（见第 2 节） |

-mtune 只调调度、不改变指令集要求，可安全运行于含小核的异构 SoC；
-mcpu 会启用该微架构完整 ISA（实测 oryon2isa 产物含 1526 条 SVE 指令，同源码 -mtune 版为 0），
只允许在**同构全大核**设备上加载。

### 2. 全大核运行期门禁（ISA 档的硬前提）
CoreTopologyDetector 读 /proc/cpuinfo 的 CPU part：所有核同构且非小核/中核才判为全大核（fail-closed）。
不满足时加载路径拒绝加载 ISA 档并回退 baseline，界面与 logcat 明说原因（降级不得静默）。
实测：一加 15（8×0x002）放行；红米 SM7475（1×0xd48 + 3×0xd47 + 4×0xd46）拒绝且不崩溃。

### 3. 构建开关（默认全关）
| 环境变量 | 作用 |
|---|---|
| DYNAMO_PGO_GENERATE=1 | 产出插桩库（-fprofile-generate），用于真机采集 |
| DYNAMO_PGO_PROFILE=<.profdata> | 产出应用档（-fprofile-use）；与上面互斥 |
| DYNAMO_LTO=thin 或 full | 追加 -flto=<mode> 与 -Wl,-O2（披露串记 LTO=） |
| DYNAMO_ISA=allbig | 实验性全局覆盖：-mtune 换 -mcpu（正式以 profiles.cmake 的 DYNAMO_ISA_* 为准） |
| DYNAMO_PGO_SCOPE=all | 允许非 SDLLVM 档位也吃 PGO 参数（试点用） |
| DYNAMO_EXPERIMENTAL_OVERRIDE=<档位> | 只构建指定档位（不改 catalog 与实验档名单） |
| DYNAMO_TOOLCHAIN_OVERRIDE=<tc> | 覆盖本次构建的工具链（试点用） |

打包注意：build.sh 在 assemble 任务里会重跑实验档脚本（不带 PGO 环境变量），会把刚生成的插桩库覆盖掉 ——
需要保留插桩/应用档时请用 .evidence/pack_only.sh。

### 4. 产物门禁与证据纪律
- .evidence/scan_apk_allabi.py：全 ABI × 全档位检查 16 KB 对齐、arm64 SVE=0、无 libc++_shared、无插桩残留；
  ISA 档的 SVE>0 属预期，仅在"运行期门禁四处前提自动核验通过"时豁免并醒目打印；把 ISA 库塞进常规档位仍判红（负样本已回归）。
- .evidence/check_isa_catalog.py：profiles.cmake 与 Kotlin ISA 白名单与归一化白名单三方一致。
- .evidence/check_jni_contract.py：JNI 契约（参数/符号/R8 keep）。
- 取设备二进制一律用 base64 或 adb pull（adb shell cat 会被 Windows PTY 注入 CRLF，曾把 47760 字节的 .profraw
  拉成 47902 字节、版本号读成 2573）；.evidence/ev.py 的 pull_bin() 已内置长度校验。
- 档位是否真的生效，只看 logcat 里 engine load 行的 active= 与 fallback=；"应用能跑完"不算验证。

---


---

## 七、 仓库布局与 SPEC 组件流水线（2026-10 整理）

### 1. 顶层布局

| 路径 | 作用 | 是否入库 |
|---|---|---|
| app/ | UFS Benchmark 应用（Kotlin/Compose + NDK C/C++/ASM） | 是 |
| app/src/main/cpp/bench/ | 内置基准内核层（CoreMark / STREAM / tinymembench，裁剪自上游） | 是 |
| AndroidBenech/ | 独立的 Android 基准套件（内核层 + CLI + Kotlin 规模策略） | 是（upstream/ 素材除外） |
| tools/spec2017/ | SPEC CPU2017 → Android 组件包 构建流水线 | 是 |
| docs/ | 需求/契约/证据文档（含 verified-facts.md 逐轮实测记录） | 是 |
| .evidence/ | 过程证据（截图、日志、构建产物、组件包） | 否（.gitignore） |
| SPEC2017/ | SPEC CPU2017 安装介质解包树（商业许可内容） | 否（.gitignore） |
| AndroidBenech/upstream/ | 上游源码采集区（1.2 GB，按 SOURCES_MANIFEST.md 可重建） | 否（.gitignore） |
| .tools/ | Android SDK / NDK | 否（.gitignore） |

### 2. SPEC2017 → 组件包（BYOB）流水线

    python3 tools/spec2017/build_components.py --spec-root SPEC2017 --out .evidence/byob_pkg

**官方口径（实测确认，详见 docs/verified-facts.md 第 27 轮）**：
必须加 `-DSPEC` 编译 —— 官方参考输出即在该宏下生成；不加会走 `#if !defined(SPEC)` 分支，
输出行数与数值都可能与官方参考不符。mcf 另需 `-DSPEC_HAVE_INTTYPES_H` 并链接基准自带的
`src/spec_qsort/`（官方用 SPEC 自己的 qsort，排序稳定性不同会改变迭代轨迹）。

产出的 `<id>.zip` 即组件包（runner.json + bin/ + data/），在应用「终端」页用
「导入组件包(.zip)」导入，然后：

    list                # 列出已就位组件
    verify <id>         # ELF 准入核验（arm64 + bionic 依赖白名单）
    run <id>            # 沙箱执行 + 与官方参考容差判分

注意：组件包内是 **arm64** 二进制，必须在 arm64 真机运行；x86_64（含带 ARM 翻译的模拟器）
上 `run` 会诚实失败（exit -1 / 无输出文件），不会给出虚假成绩。


---

## 八、 打包与产物（2026-10 整理）

### 1. 出包

    ./build.sh --max-workers=4 assembleStandardRelease assembleLabRelease

| flavor | applicationId | 说明 |
|---|---|---|
| standard | com.ufs.bench | 常规版（FEATURE_ROOT_LAB=false） |
| lab | com.ufs.bench.lab | 实验室版：BYOB 二进制测试、专业模式等（FEATURE_ROOT_LAB=true） |

两者 applicationId 不同、签名相同，可同时安装。minSdk 26 / targetSdk 34 → 用 APK Signature
Scheme **v2** 签名即可（v1 非必需）。

### 2. 签名

口令放在 `keystore.properties`（不入库，见 `.gitignore`）：

    storeFile=keystore/ufs-bench.jks
    keyAlias=ufsbench

证书：`CN=UFS Benchmark, OU=Storage Lab, O=UFSBench, L=Shenzhen, ST=Guangdong, C=CN`
SHA-256：`e79e1ba007e3978de51d71b9745ce940bbcd84c3a463fd8a89dcd5cc9b0acf44`

**换签名会导致已安装的旧包无法覆盖安装**（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`，需先卸载、会丢数据）——
真机上已出现过一次（一加 15 上旧的 standard 包）。

### 3. 产物目录 `dist/`（不入库）

    dist/UFSBenchmark-<版本>-standard.apk
    dist/UFSBenchmark-<版本>-lab.apk
    dist/SHA256SUMS.txt      # sha256sum -c 校验
    dist/README.md

### 4. 沙箱助手必须与源码一致（防漂移）

`app/src/main/jniLibs/<abi>/libbyob_run.so` 是**签入版本库的预编译可执行文件**（Android 只允许从
lib 目录执行原生二进制），源码在本仓库（`tools/sandbox_test/byob_run.cpp` +
`app/src/main/cpp/sandboxed_runner.cpp`）。规矩：

    tools/sandbox_test/build_byob_helper.sh            # 从源码构建并与签入产物比对
    tools/sandbox_test/build_byob_helper.sh --install  # 就地更新（之后必须重出 App 并在真机复验）

本轮就靠它抓到一次真实漂移：arm64 助手与源码一致，**x86_64 那份是旧的**（早期改源码未同步），
已就地更新为与源码一致。注意 AGP 打包时会 **strip** 原生库，所以 APK/设备里的助手字节数小于
jniLibs 里的（实测 935 KB → 139 KB），这属正常，**不能**用"字节相等"判定漂移，
要比对的是 jniLibs 里的文件本身。


### 5. 应用图标（2026-10 更新）

- 源图：tools/icon/source/lab.png（lab 版）、tools/icon/source/standard.jpg（standard 版），均 1280×1280；
- 重新生成：tools/icon/make_icons.sh（用 .tools/jdk-17 的 ImageIO，无需第三方图像库）；
- 每个 flavor 产出 mipmap-*/ic_launcher{,_round}.png、drawable-*/ic_launcher_foreground.png、
  mipmap-anydpi-v26/ic_launcher{,_round}.xml、values/ic_launcher_background.xml；
- 两个坑（已记档）：
  1) 圆形遮罩必须用 setClip(Ellipse) + drawImage —— AlphaComposite.SrcIn + fill 实测不生效；
  2) release APK 会缩短资源文件名，核验图标要看 aapt2 dump resources 并比对两包 PNG 体积，不能按名找。

