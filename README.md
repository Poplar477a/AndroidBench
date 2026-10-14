<p align="center">
  <img src="docs/assets/icon.png" width="112" alt="AndroidBench icon">
</p>

# UFS Storage Benchmark · AndroidBench

**中文** · [English](README.en.md)

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/badge/release-0.1.0--Alpha-orange.svg)](../../releases)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B%20(API%2026)-green.svg)](#)
[![Network](https://img.shields.io/badge/network-none-brightgreen.svg)](#九-许可商标与合规)

> **Android 移动端存储性能基准工具**：参考 FIO 与 Iometer 的测试模型**独立实现**，
> 内置上游源码裁剪的算力/内存层次基准（CoreMark / STREAM / tinymembench），
> 并支持用户自备组件包（BYOB）做二进制级测试与判分。

本项目为独立实现的测量工具，与 JEDEC / SPEC / EEMBC / Google 等组织**无隶属、赞助或背书关系**；
采用 Apache-2.0 许可（见 LICENSE），第三方组件版权与许可见 NOTICE 与 THIRD_PARTY_LICENSES.md。

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

## 六、 打包与产物

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


---

## 七、 自定义 ELF 与组件包 打包规则

终端（实验室版）可运行**用户自备的 arm64 可执行文件**，两种投喂方式。

### 1. 裸 ELF（最简）

把 arm64 可执行文件（自己写的微基准、或从别处拿到的单文件程序）放进手机，用
「终端 → 导入组件包(.zip)」选中它即可：应用会自动生成**最小组件包** —— 无命令行参数、
不做参考输出判分，`benchmark_id` 取文件名。

| 准入项 | 要求 |
|---|---|
| 架构 | **aarch64**（ELF `e_machine = 0xB7`）；x86_64 / arm32 一律拒绝 |
| 依赖 | 只能是 bionic 白名单：`libc.so` `libm.so` `libdl.so` `liblog.so` `libz.so` `libstdc++.so` `libc++_shared.so` `libandroid.so` `libatomic.so` |
| 禁止 | glibc 名字：`libc.so.6` `libm.so.6` `libpthread.so.0` `libgcc_s.so.1` `ld-linux-aarch64.so.1` 等 —— 需要 glibc 的请 **静态链接**后再打包 |
| 权限 | 可执行位；导入流程会自动就位到应用私有目录并做 ELF 准入核验 |

### 2. .zip 组件包（支持传参 + 与参考输出比对判分）

zip 内**即根目录，不要再套一层**：

    runner.json            必需
    bin/<可执行文件>        必需，arm64 ELF
    data/input/…           可选，输入数据集
    data/output/<参考输出>  可选，判分用的参考输出

`runner.json` 字段：

    {
      "benchmark_id": "mybench",
      "binary_path": "bin/mybench",
      "command_args": ["--size", "1024", "data/input/in.dat"],
      "stdout_file": "mybench.out",
      "oracle_rule": {
        "type": "numeric_tolerance",
        "reference_file": "data/output/ref.out",
        "abs_tolerance": 1e-7,
        "rel_tolerance": 1e-4,
        "ignore_line_patterns": ["runtime = ", "elapsed"]
      },
      "execution_profile": {
        "affinity_policy": "none",
        "cooldown_target_celsius": 0.0,
        "max_execution_seconds": 600
      }
    }

- `command_args` 中以 `data/` 开头的元素会被替换为**包内绝对路径**（输入放 `data/input/` 时用它）；
- `oracle_rule.type = "none"` 表示**不判分**，只记录运行结果；
- `ignore_line_patterns` 是**子串匹配**：命中的行两侧都不参与比对（耗时/内存地址这类每次运行都不同的行必须排除）；
- `stdout_file` **不要用程序自己会写的文件名** —— 否则运行器不会落盘捕获到的 stdout，判分会拿错文件。

### 3. 判分口径

逐行比对：行内按空白切 token；两侧都能解析为数字时用 `|a-b| <= abs_tol + rel_tol*|b|`，
否则按字符串精确比对；**空行两侧都忽略**。这是"与参考输出一致"的一致性判定，**不代表任何官方成绩**。

### 4. 常见坑

1. 组件包必须是 **arm64**；x86_64（含带 ARM 翻译的模拟器）上运行会诚实失败，不会给出虚假成绩；
2. 程序按**裸文件名**打开输入文件时（例如 `100_100_130_cf_a.of`），输入要放**包根目录** —— 运行目录就是包根；
3. 耗时/分配类行不排除，正确实现也会被判"不符"；
4. 依赖 glibc 的二进制必须先 `-static` 静态链接。

规范全文见 [docs/byob-打包规范.md](docs/byob-打包规范.md)；应用内「终端」输入 `spec` 会原样打印同一份内容。

---

## 八、 SPEC 相关能力的严谨声明

本项目与 SPEC® 的关系只有一条：**提供通路**，既不提供任何 SPEC 内容，也不产出 SPEC 成绩。

### 1. 与"常规用 adb 把 SPEC 推进手机跑"的本质区别

常见做法是：在 PC 上用 SPEC 官方工具链（`runspec` / `specperl` + 官方 flags）编译出官方二进制，
再用 `adb push` 推进手机运行，把屏幕上的数字当作"这台手机跑 SPEC 的结果"。
**本项目不这样做，也不等价于这样做**：

| 维度 | 官方口径（runspec + SPEC 工具链） | 本项目 BYOB 通路 |
|---|---|---|
| 编译器 / 工具链 | SPEC 官方 flags.xml 指定的编译器与选项，分 base / peak 两档 | 用户自备介质 + Android NDK/Clang，**单一固定优化等级**，不做 base/peak 分档 |
| 输入集 | 官方 test / train / ref 三档，规模与内容由官方给定 | 用户自备（本仓库**不附带**任何数据集） |
| 运行规则 | 官方 run rules：迭代控制、报告格式、可复现性、结果上报流程 | 无。只做"跑一次 + 与参考输出比对是否一致" |
| 产出指标 | SPECspeed / SPECrate / SPECratio 等官方指标 | **不产出任何 SPEC 指标**；只记录墙钟耗时与"输出是否与参考一致" |
| 结果用途 | 可对外公布的官方成绩 | 仅供本机自证与回归对比，**不得与任何公布的 SPEC 成绩比较** |

补充说明一个容易被误读的点：组件流水线里使用 `-DSPEC` 编译，目的是让**程序的输出**与官方
参考输出一致（否则校验对不上），这属于"输出保真"，**不意味着**获得了官方编译口径或官方成绩。

### 2. 明确禁止的表述与用法

- 不得把本项目的结果称为 SPEC 成绩、SPEC 分数、SPECratio、SPECspeed、SPECrate；
- 不得用本项目的结果与 spec.org 公布的任何数值做比较、排名或折算；
- 不得把本项目描述为"SPEC 移植版""SPEC for Android"，或暗示与 SPEC 存在隶属、赞助、认证关系；
- 本项目的终端判分只有两种含义：**输出与参考一致** / **不一致**，与分数无关。

### 3. 分发边界

本项目**不分发** SPEC 的源码、输入集（test/train/ref）、参考输出与官方工具链 —— 它们都是商业许可内容，
仓库 `.gitignore` 已排除 `SPEC2017/`。组件包只能由用户用**自己合法持有的介质**编译生成。
`tools/spec2017/` 里的脚本只是"把用户自备源码编成 Android 可执行文件、并与参考输出做一致性比对"的
工具链，不构成对 SPEC 任何内容的再分发。

---

## 九、 许可、商标与合规

### 1. 许可

- 项目自身：**Apache License 2.0**（见 LICENSE）；
- 第三方组件与上游基准的版权/许可集中列示在 **NOTICE** 与 **THIRD_PARTY_LICENSES.md**；
- **本仓库与发布 APK 均不含 fio 二进制**（GPL-2.0）。如需自行编入，请先读 `tools/build_fio_arm64.sh`
  顶部的 GPL-2.0 义务警示：把 fio 打进 APK 对外分发会要求整个 APK 满足 GPL-2.0。
- **本仓库不含 SPEC 的任何源码、输入集与参考输出**（商业许可内容，.gitignore 已排除）。
  只提供 BYOB 通路：用户用自备介质编译组件包后导入。

### 2. 上游基准的使用口径

| 内置基准 | 上游 | 本项目用法与口径 |
|---|---|---|
| CoreMark（1001） | EEMBC | 上游算法逐字节保留 + Android 移植包装；EEMBC 协议禁止在衍生副本上使用 CoreMark 商标，故结果表述为"基于 EEMBC CoreMark 源码的本地测量"，不称官方成绩、不使用认证徽标 |
| STREAM（1002） | John D. McCalpin | **变体内核**（bench_alloc / pad 哨兵 / 十进制 MB/s）；上游要求修改后不得直称 "STREAM benchmark results"，故界面统一写"基于 STREAM 变体内核的持续带宽测量" |
| tinymembench（1003） | Siarhei Siamashka | MIT，裁剪移植；MIT 全文见 THIRD_PARTY_LICENSES.md |

内置基准的比值统一为"相对**本机自标定基线**"，界面同屏展示免责声明，不与任何官方公布分数比较。

### 3. 商标

UFS® 属 JEDEC；CoreMark® 属 EEMBC；SPEC® / SPEC CPU® 属 SPEC；Android™ 属 Google LLC；
CrystalDiskMark 属 Noriyuki Miyazaki。本项目为独立实现，**与上述组织无隶属、赞助或背书关系**；
不复用任何官方徽标，界面中的相关名称仅作事实性指代。完整声明见 NOTICE 与 App 内「设置 → 关于」。
