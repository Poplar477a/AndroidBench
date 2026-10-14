# 第三方组件与许可清单

本文件集中列示 UFS Storage Benchmark（AndroidBench）中使用、参考或明确**未**打包的第三方组件。
项目自身许可见 LICENSE（Apache License 2.0）；总体声明见 NOTICE。

---

## 1. 直接编入应用的内核

### 1.1 EEMBC CoreMark®（内置基准 1001）
- 来源：https://github.com/eembc/coremark
- 许可：EEMBC 的 CoreMark 许可（见上游 LICENSE.md / Acceptable Use Agreement）
- 本项目用法：`app/src/main/cpp/bench/coremark/upstream/` 为**逐字节上游**源码；
  `coremark/port/` 为 Android 移植层（入口改名、内存改用 `bench_alloc`、FLAGS_STR 编译期注入）
- **合规要点**：EEMBC 协议禁止在**修改/衍生副本**上使用 CoreMark 商标。本项目因此：
  - 结果一律表述为"基于 EEMBC CoreMark 源码的本地测量"，**不称为** CoreMark 官方成绩；
  - 不声称经 EEMBC 认证，不使用其认证徽标；
  - 单次有效运行的规则（≥10 s、三组 CRC 0xe714/0x1fd7/0x8e3a）在代码与界面中如实标注。
- 商标：CoreMark® 为 EEMBC 的注册商标。

### 1.2 STREAM（内置基准 1002）
- 来源：https://www.cs.virginia.edu/stream/  （John D. McCalpin）
- 许可：上游自定义宽松许可；**若修改代码或未严格按运行规则执行，发布结果时不得直接称
  "STREAM benchmark results"**
- 本项目用法：`bench/stream/bench_stream.c` 是**变体内核**（改用 `bench_alloc`、加 `pad[]` 越界哨兵、
  十进制 MB/s 口径、工作集由强度档位决定）。文件头逐条记录了与上游的差异。
- **合规要点**：界面与文档统一表述为"基于 STREAM 变体内核的持续带宽测量"，不称官方 STREAM 成绩。

### 1.3 tinymembench（内置基准 1003）
- 来源：https://github.com/ssvb/tinymembench
- 许可：MIT
- 本项目用法：裁剪移植（保留 aarch64 NEON 汇编例程与 C 回退路径），文件头标注来源与改动。
- MIT 许可全文：

      MIT License

      Copyright (c) 2011-2014 Siarhei Siamashka

      Permission is hereby granted, free of charge, to any person obtaining a copy
      of this software and associated documentation files (the "Software"), to deal
      in the Software without restriction, including without limitation the rights
      to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
      copies of the Software, and to permit persons to whom the Software is
      furnished to do so, subject to the following conditions:

      The above copyright notice and this permission notice shall be included in all
      copies or substantial portions of the Software.

      THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
      IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
      FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
      AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
      LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
      OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
      SOFTWARE.

---

## 2. 明确**未**打包、但仓库内提供构建脚本的组件

### 2.1 fio（GPL-2.0）
- 来源：https://github.com/axboe/fio
- **本仓库与发布 APK 均不含 fio 二进制**；`FioEngineImpl` 在找不到外部二进制时不会加载该引擎。
- `tools/build_fio_arm64.sh` 会把编译产物放到 `app/src/main/jniLibs/`。**若你自行把 fio 二进制
  打进 APK 并对外分发，该分发行为将触发 GPL-2.0 义务**（须随包提供完整对应源码或三年期书面
  索取承诺，并附 GPLv2 全文）。脚本顶部已加同样警示。

### 2.2 SPEC CPU® 2017（仅 BYOB 通路）
- SPEC 的源码、输入集（test/train/ref）、参考输出与官方工具链均为**商业许可内容**，
  **严禁随开源仓库或 APK 再分发**。本仓库不含上述任何内容（`.gitignore` 已排除 `SPEC2017/`）。
- 本项目只提供 BYOB（Bring Your Own Benchmark）：用户用自备介质编译组件包后自行导入。
- **许可与商标要点**（SPEC Fair Use Rules / Trademarks Policy）：
  - 非标准运行、自编译或修改版产出的结果属于**非合规结果**，不得使用 `SPECspeed`/`SPECrate`/`SPECratio`
    等官方指标名，更不得自造近似名称；
  - 不得把 SPEC 商标用于自研移植工作负载的宣传；
  - 展示此类结果时必须显著声明"内部相对测算值、与 SPEC 无从属关系"。
- 本项目据此把内置内核的比值统一定义为"相对**本机自标定基线**"，并在界面同屏展示免责声明。

---

## 3. 仅作架构/口径参考，未拷贝代码

### 3.1 Iometer / Dynamo
- `dynamo_engine.cpp` 是按 Iometer 的**存取规范模型**自行实现的 C++17 代码，未拷贝 Iometer 源码。
- Iometer 早期版本采用 Intel Open Source License；本项目在文档中注明其架构参考来源，
  并明确**不受 Intel 官方支持或背书**。

---

## 4. 运行时/构建期工具（不随 APK 分发）

| 组件 | 许可 | 说明 |
|---|---|---|
| Android NDK / Clang / libc++ | Apache-2.0 with LLVM exception | 编译原生内核；静态链接 libc++ 的部分随 .so 分发 |
| AndroidX / Jetpack Compose | Apache-2.0 | 界面层依赖 |
| Gradle / AGP | Apache-2.0 | 构建系统 |

---

## 5. 商标与品牌（汇总声明见 NOTICE）

| 名称 | 权利人 | 本项目用法 |
|---|---|---|
| UFS® | JEDEC | 项目名中的事实性指代；README 与「关于」页声明非从属 |
| Android™ | Google LLC | 事实性指代；非从属 |
| CoreMark® | EEMBC | 事实性指代上游内核；结果不称官方成绩 |
| SPEC® / SPEC CPU® | SPEC | 仅说明"用户自备的 SPEC 组件包"这一事实；无代码/数据分发 |
| CrystalDiskMark® | Noriyuki Miyazaki | 预设套件的**事实性参照**；界面已改为"标准访问模式（参考 CrystalDiskMark 模型预设）" |

