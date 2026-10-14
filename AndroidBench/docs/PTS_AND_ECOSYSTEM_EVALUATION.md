# Phoronix Test Suite 综合研判与 Android ARM64 开源基准扩展评估报告

> **编制日期**：2026-10-13  
> **研究对象**：Phoronix Test Suite (PTS) 核心测试库 (1650+ profiles / 60+ suites) 及开源 ARM64 / Android PC 高性能基准生态  
> **存放路径**：`docs/PTS_AND_ECOSYSTEM_EVALUATION.md`

---

## 一、 研判背景与方法论

在构建面向 Android ARM64 手机、平板乃至 Android PC（如骁龙 X Elite / 联发科天玑 / 瑞芯微 RK3588 工作站）的原生基准套件时，既有的 P0/P1 清单（CoreMark、STREAM、Zstandard、tinymembench、NPB、HPL、Graph500 等）奠定了坚实的**底层硬件与微架构基线**。

然而，现代移动操作系统与 PC 级工作负载呈现出高度综合化的特征（如端侧 AI、现代多媒体编解码、复杂光线追踪、深层逻辑搜索分支与嵌入式数据库事务）。

为此，我们深入解构了 **Phoronix Test Suite (PTS)** 官方套件定义，并对开源生态进行了系统性横向比对，旨在甄选出**最具代表性、算法自洽、易于通过 JNI 原生链接、且完全符合第十四章规模管理机制**的优质开源项目。

---

## 二、 Phoronix Test Suite 架构研判与 Android 移植边界

### 1. PTS 核心优势
- **测试模型标准化**：PTS 采用严格的 XML 定义（`test-definition.xml` 与 `downloads.xml`），将测试分为 Processor、Memory、System、Graphics 四大类，测试维度极其清晰。
- **丰富的测试组合（Suites）**：如 `cpu-1.2.4`、`memory-1.3.0`、`compression-1.1.4`、`cryptography-1.2.6`、`llm-1.0.1`、`workstation-1.2.1`，为跨平台评测提供了成熟的组合参照。

### 2. PTS 对 Android 的关键局限（为什么不能直接将 PTS 塞进 APK）
1. **运行时依赖过重**：PTS 核心依赖 PHP 7.3+ 解释器与 Bash 脚本环境，在 Android 原生 APK 中引入 PHP 运行时会导致体积暴增且难以管理。
2. **动态网络下载机制冲突**：PTS 依赖在测试机器上现场 `wget` 下载源码并现场 `make` 编译，这违反 Android 应用离线、防投毒和安全审计原则。
3. **最佳工程实践**：**“复用 PTS 的测试定义、参数组合与结果组织规范，直接提取上游 C/C++ 纯原生内核，封装为 JNI 动态库集成进 APK”。**

---

## 三、 从 PTS 内部甄选的高价值新增推荐项目 (8 项)

经深度扫描 PTS 内置的 1,659 个 profiles，以下 8 个项目具备极高的移植价值和工业界公信力：

### 1. 7-Zip 压缩基准 (`pts/compress-7zip`)
- **上游仓库**：`ip7z/7zip` (已拉取至 `upstream/7zip`)
- **测试能力**：整数运算、复杂分支预测、LZMA 字典查找、多核多线程调度。
- **行业地位**：PC 硬件评测（Intel/AMD/ARM）中的绝对金标准基准之一，自带 `7z b` 算法内建评分（MIPS），全球可比。
- **移植难度**：★☆☆ (极低，纯 C/C++，无复杂依赖)。
- **第十四章裁剪策略**：
  - Smoke: 字典 1MB，1 线程，快速验证；
  - Small: 字典 16MB，限制 2 线程；
  - Standard: 字典 32MB，全核并发，正式上榜；
  - Large: 字典 64MB~128MB，深度工作集。

### 2. Stockfish 国际象棋引擎 (`pts/stockfish`)
- **上游仓库**：`official-stockfish/Stockfish` (已拉取至 `upstream/stockfish`)
- **测试能力**：纯整数逻辑密集度、64位位棋盘（Bitboard）运算、Alpha-Beta 剪枝搜索树、CPU 指令流水线 IPC 压测。
- **行业地位**：世界排名第一的开源国际象棋引擎，拥有成熟内建的 `stockfish bench` 命令行基准模式（NPS: Nodes Per Second）。
- **移植难度**：★☆☆ (现代 C++17，对 ARM64 NEON 与 popcount 指令优化极佳)。
- **第十四章裁剪策略**：通过搜索深度与限制节点数（Nodes）进行毫秒级精确控制。

### 3. C-Ray 多线程浮点光追 (`pts/c-ray`)
- **上游仓库**：`jtsiomb/c-ray` (已拉取至 `upstream/c-ray`)
- **测试能力**：纯粹的双精度/单精度浮点运算、光线与球体求交测试、SIMD 向量吞吐。
- **行业地位**：Linux 与跨平台极度青睐的经典微基准，纯内存与寄存器计算，完全隔离磁盘 I/O 干扰。
- **移植难度**：★☆☆ (仅单个 C 源码文件，零外部依赖)。
- **第十四章裁剪策略**：分辨率从 400x300 (Smoke) 到 1920x1080 (Standard)，抗锯齿采样可控。

### 4. dav1d AV1 视频解码器 (`pts/dav1d`)
- **上游仓库**：`videolan/dav1d` (已拉取至 `upstream/dav1d`)
- **测试能力**：新一代开源 AV1 视频解码吞吐量。
- **技术亮点**：包含 VideoLAN 团队编写的极致手工 ARM64 NEON 汇编内核，直接反映移动处理器在真实高负荷多媒体播放中的向量计算与功耗表现。
- **移植难度**：★★☆ (C + ARM64 汇编，Meson 构建，需转接 CMake)。
- **第十四章裁剪策略**：不同分辨率测试片段（720p Smoke -> 1080p Standard -> 4K Large）。

### 5. OpenSSL 密码学性能 (`pts/openssl`)
- **测试能力**：对称加密（AES-128/256-GCM、ChaCha20）、哈希（SHA256、SHA512）、非对称加密（RSA4096、ECDSA）。
- **技术亮点**：直接检验处理器是否具备并启用了 **ARMv8.2-A Crypto Extensions (CE)** 硬件加密指令。有无硬件 CE 指令吞吐量相差达 10~20 倍，具有不可替代的硬件鉴别价值。
- **第十四章裁剪策略**：标准 10 秒测试周期，不同数据块（1KB~64KB）矩阵化上报。

### 6. SQLite Speedtest1 数据库事务 (`pts/sqlite-speedtest`)
- **上游来源**：SQLite 官方单源码文件 (`sqlite3.c` + `speedtest1.c`，已就绪至 `upstream/sqlite`)
- **测试能力**：B-Tree 树遍历、索引构建、内存表查询、事务回滚与写入日志。
- **技术亮点**：Android 系统核心依赖 SQLite（Room/ContentProvider/系统服务），真实模拟 App 本地数据持久化性能。
- **移植难度**：★☆☆ (公共领域单 C 源码文件，极易嵌入)。
- **第十四章裁剪策略**：记录条数从 1,000 (Smoke) 到 100,000 (Standard)。

### 7. Himeno CFD 泊松求解器 (`pts/himeno`)
- **测试能力**：流体力学压力泊松方程雅可比点迭代（Point-Jacobi），测试高强度访存与三维网格浮点计算（MFLOPS）。
- **移植难度**：★☆☆ (纯 C 实现，极为精简)。
- **第十四章裁剪策略**：网格尺寸（XS / S / M / L）。

### 8. FFTW 快速离散傅里叶变换 (`pts/fftw`)
- **测试能力**：一维与二维离散傅里叶变换（DFT/FFT），信号处理核心算子。
- **技术亮点**：包含 Codelet 运行时代码生成与自适应算法选择规划器。

---

## 四、 联网研判：从现代 ARM64 边缘计算生态甄选的 3 大项目

在 2024~2026 年的现代移动端与 Android PC 场景中，**端侧 AI 大模型与现代计算机视觉**已成为决定设备综合算力感知最重要的指标。以下 3 个项目强烈建议纳入套件：

### 1. llama.cpp / llama-bench (端侧 LLM 大模型推理)
- **上游仓库**：`ggml-org/llama.cpp` (已拉取至 `upstream/llama.cpp`)
- **测试能力**：
  - **Prompt Processing (PP, 提示词吞吐)**：长上下文批量矩阵乘法吞吐（Tokens/s）。
  - **Token Generation (TG, 文本生成速度)**：自回归单 Token 延迟与内存带宽瓶颈测试（Tokens/s）。
- **微架构覆盖**：ARM64 NEON、ARMv8.2 FP16、ARMv8.4 Dot-Product、ARMv8.6 I8MM，以及 Arm KleidiAI 加速核。
- **第十四章规模策略**：
  - Smoke: 极小量化模型 (如 0.5B Q4_0, 工作集 < 350MB)；
  - Standard: 1B~3B 量化模型 (Q4_K_M, 工作集 ~1.5GB)；
  - Large: 7B~8B 量化模型 (面向 16GB+ Android PC)。

### 2. Tencent ncnn (移动视觉神经网络推理)
- **上游仓库**：`Tencent/ncnn` (已拉取至 `upstream/ncnn`)
- **测试能力**：CNN/Transformer 移动端神经网络推理（MobileNet-V2, ResNet-50, YOLOv8）。
- **技术亮点**：腾讯开源标杆，汇编级打磨 ARM64 NEON，且无缝支持 Vulkan GPU 跨平台计算，非常适合作为 CPU 与 GPU 异构算力对比基准。
- **移植难度**：★★☆ (专为 Android/iOS 深度优化，集成极为顺畅)。

### 3. libjpeg-turbo (图像 SIMD 编解码)
- **上游仓库**：`libjpeg-turbo/libjpeg-turbo` (已拉取至 `upstream/libjpeg-turbo`)
- **测试能力**：SIMD 加速 JPEG 压缩与解压缩吞吐量（Mpixels/s）。
- **技术亮点**：Android 系统底层标配，直接体现真实相册、网页与图库浏览性能。

---

## 五、 全量候选基准分级集成规划 (P0 -> P1 -> P2 -> P3)

结合原有规划与本轮研判，形成完整的工程落地矩阵：

| 阶段 | 涵盖基准项目 | 核心测试领域 | 推荐配置/规模参数 |
|:---|:---|:---|:---|
| **P0 核心基线 (首发必成)** | CoreMark, Zstandard, STREAM, tinymembench, NPB EP | 整数基线、数据压缩、持续内存带宽、缓存延迟、纯浮点科学计算 | Standard 严控超限防强杀 |
| **P1 实战应用与 PC 扩展** | **7-Zip**, **Stockfish**, **C-Ray**, **SQLite**, HPL, Graph500, GCC, NPB BT/SP/CG/FT | 真实 PC 压缩天梯、分支密集博弈、浮点光追、数据库事务、矩阵运算、稀疏图、代码编译 | 覆盖主流专业工作负载 |
| **P2 现代前沿与向量智能** | **llama-bench (llama.cpp)**, **dav1d**, **OpenSSL**, **ncnn** | 端侧大模型推理、AV1 视频向量解码、ARM 硬件加密加速、视觉 NPU/Vulkan 异构 | 凸显 ARM64 最新指令集优势 |
| **P3 复杂工作流与重型计算** | NAMD, deal.II/FEM, CoreMark-PRO, Himeno, FFTW | 巨型分子动力学、偏微分有限元、多套件混合负载、高维频域变换 | 面向高端移动工作站与超算模拟 |
