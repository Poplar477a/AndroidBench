# 建议纳入 APK 的上游基准仓库清单与移植规划 (对应第 3 节)

## 一、 基准项目完整清单

按测试能力与系统硬件维度分类，所有仓库链接指向上游官方开源项目或官方源码发布入口：

### A. 整数与综合 CPU 性能
| 项目 | 上游仓库 / 官方入口 | 授权许可 | 核心测试用途 | 关键移植考量 |
|:---|:---|:---|:---|:---|
| **CoreMark** | `eembc/coremark` | EEMBC CoreMark License | 整数计算、状态机控制逻辑、流水线性能 | 单/多线程纯 C 原生库封装；保持上游计分规则（运行时间 >= 10s） |
| **CoreMark-PRO** | `eembc/coremark-pro` | EEMBC License | 复杂多核应用、混合计算工作负载 | 包含 JPEG 压缩、ZIP、XML 解析与线性代数，体量较大 |
| **GCC** | `gcc-mirror/gcc` | GPLv3 with Runtime Exception | 真实编译器构建、整数与分支跳转综合工作负载 | 依赖较大磁盘空间（数百 MB~GB 临时产物），需裁剪独立单遍前端构建 |
| **Zstandard** | `facebook/zstd` | BSD-3-Clause / GPLv2 | 高吞吐数据压缩与解压缩性能 | libzstd C 原生库链接；使用固定、可复现的多版本数据集 |
| **Graph500** | `graph500/graph500` | BSD-style | 大规模图遍历、整数运算与不规则访存 | 采用 CSR (Compressed Sparse Row) 数据结构，限制 Scale 阶数 |

### B. 浮点与科学计算
| 项目 | 上游仓库 / 官方入口 | 授权许可 | 核心测试用途 | 关键移植考量 |
|:---|:---|:---|:---|:---|
| **NPB (NASA Parallel Benchmarks)** | NASA 官方源码 (NAS NPB 3.4.2) | NASA Open Source Agreement | EP, BT, SP, CG, FT 科学计算与数值求解 | 优先移植 OpenMP / C 语言参考实现，剥离 MPI 运行时 |
| **HPL** | `icl-utk-edu/hpl` | BSD-style | FP64 稠密矩阵求解、BLAS 吞吐量 (Top500 基准) | 依赖高效 BLAS 库（如 OpenBLAS ARM64 优化版）；限制矩阵阶数 N |
| **FEM / CFD (deal.II)** | `dealii/dealii` | LGPL-2.1+ | 有限元数值计算、偏微分方程网格求解 | 巨型 C++ 模板库，设备端编译极重，需提取精简求解器内核 |
| **NAMD** | NAMD 官方网站 (UIUC) | UIUC Open Source License | 分子动力学模拟与原子间作用力计算 | 依赖 Charm++ 运行时，需确认移动端授权与体积边界 |

### C. 内存带宽、延迟与缓存
| 项目 | 上游仓库 / 官方入口 | 授权许可 | 核心测试用途 | 关键移植考量 |
|:---|:---|:---|:---|:---|
| **STREAM** | `jeffhammond/STREAM` | STREAM License (John D. McCalpin) | 持续内存带宽 (Copy, Scale, Add, Triad) | 必须击穿移动端 4x LLC 缓存；严格区分 Cache 驻留与 DRAM 带宽 |
| **tinymembench** | `ssvb/tinymembench` | MIT | 连续带宽、NEON 向量指令带宽、随机访问延迟 | 针对 ARM64 NEON 优化；适配 Android 平台（剔除 hugepage 等受限项） |
| **lmbench** | `intel/lmbench` | GPLv2 | 细粒度缓存分层延迟、系统调用与上下文切换 | 剥离与 Linux 桌面强绑定的 shell 脚本，封装为纯 C 测量单元 |

### D. 测试管理框架参考
| 项目 | 上游仓库 | 授权许可 | 用途 |
|:---|:---|:---|:---|
| **Phoronix Test Suite** | `phoronix-test-suite/phoronix-test-suite` | GPLv3 | 统一测试定义、test-profiles 与 test-suites 组织模型参考 |

---

## 二、 阶段推进计划 (P0 -> P1 -> P2)

### P0：第一版必须完成 (基准核心)
1. **CoreMark**：确立 CPU 单核与多核整数计算基线。
2. **Zstandard**：评估现实高频应用数据压缩与解压缩吞吐量。
3. **STREAM**：严格测定 LPDDR 持续物理内存带宽。
4. **tinymembench**：精细测绘 L1/L2/L3/SLC 各级缓存延迟与内存随机访问曲线。
5. **NPB EP**：提供纯净、易并行的 FP64 密集型科学计算基线。

### P1：第二阶段扩展 (系统与算法深度)
- **NPB BT / SP / CG / FT**：覆盖非结构化三维网格、稀疏矩阵共轭梯度与 3D-FFT 变换。
- **HPL**：评估移动设备在极端 FP64 浮点矩阵负载下的持久性能与热稳定性。
- **GCC**：真实世界的重型编译型负载。
- **Graph500**：非规则内存访问与大图遍历（内存密集与分支预测压力）。
- **lmbench**：精确测算系统调用开销与缓存层级拐点。

### P2：复杂工作负载 (高阶计算)
- **NAMD**：分子动力学多体相互作用模拟。
- **deal.II / FEM**：有限元数值模拟与复杂物理场方程求解。
