# Android ARM64 原生基准套件架构设计说明书

## 一、 系统架构总览

针对现代 Android 移动端与 Android PC（ARM64 架构），本项目确立了以 **Kotlin / Jetpack Compose UI + JNI 桥接层 + C/C++ 原生高性能核心库** 为核心的工业级基准测试套件架构，彻底取代传统将 Linux ELF 可执行文件打包进 assets 并在设备端通过 `Runtime.getRuntime().exec()` / `fork` 执行的脆弱模式。

```
┌────────────────────────────────────────────────────────────────────────┐
│                   Android 用户交互层 (Jetpack Compose)                 │
│  - 实时仪表盘 DashboardScreen (主频、温控、实时吞吐、进度与阶段状态)    │
│  - 测试矩阵配置 BenchmarkMatrixScreen (P0/P1/P2 选择、模式与规模等级)    │
│  - 历史报告与对比 ReportScreen (严格区分 Standard / Adaptive / Smoke)   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ (Kotlin Coroutines / StateFlow)
┌───────────────────────────────────▼────────────────────────────────────┐
│                    调度管理层 (Benchmark Coordinator)                   │
│  - DeviceResourceProber: 设备资源安全无特权探测 (RAM, Heap, CPU拓扑)   │
│  - ScaleSelectionEngine: 确定性、可复现的规模选择与降级状态机           │
│  - ResourceGuard: 原生分配预检、超时看门狗、原子取消与重试熔断器        │
│  - CrashRecoveryJournal: 断电/LMK 强杀崩溃恢复记录器                    │
└─────────────────┬──────────────────────────────────┬───────────────────┘
                  │                                  │
                  ▼ (JNI Call)                       ▼ (JNI Call)
┌───────────────────────────────────┐ ┌──────────────────────────────────┐
│        C/C++ 原生计算内核         │ │       C/C++ 原生内存与系统内核    │
│  - CoreMark (EEMBC 官方 C 移植)   │ │  - STREAM (持续带宽, 4xLLC 击穿) │
│  - Zstandard (libzstd 原生压缩库) │ │  - tinymembench (NEON/指针追逐)  │
│  - NPB EP / BT / SP / CG / FT     │ │  - lmbench (精细微基准适配)      │
│  - HPL (FP64 OpenBLAS/LAPACK)     │ └──────────────────────────────────┘
│  - Graph500 (CSR 稀疏图遍历)      │                  │
└─────────────────┬─────────────────┘                  │
                  └─────────────────┬──────────────────┘
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        原生安全防护层 (Memory Guard)                    │
│  - SafeNativeAllocator: posix_memalign 内存对齐、预检查与硬上限拦截     │
│  - NativeCancellationHook: 原子取消标志与阶段性检查点轮询              │
│  - Android ARM64 裸机执行 (Cortex-A/X, Qualcomm Oryon, Samsung)        │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 二、 关键架构裁定：为什么放弃 Linux ELF 进程拉起模式？

在 Android 10 (API 29) 及以上系统中，将独立 Linux 二进制程序塞入 APK 并通过 `fork/exec` 运行存在根本性架构缺陷：
1. **W^X 与可执行权限拦截**：Android 严格限制私有目录文件赋予执行权限 (`chmod +x` 在 targetSdkVersion 29+ 场景下受到 SELinux 强力拦截甚至产生 `SELinux Avc Denied`)。
2. **生命周期与进程托管失控**：外部进程脱离 Android ActivityManager 与 Runtime 的托管，无法受到 Coroutine Cancellation 与 Job 调度约束，极易引发僵尸进程。
3. **主线程与 ANR 隐患**：通过 Process.waitFor() 阻塞主线程极易诱发系统 ANR。
4. **内存与资源审计黑盒**：外部进程直接向 Linux 内核申请内存，Android 应用进程无法预先感知其峰值虚拟内存与物理驻留集，导致直接触发系统 LMK (Low Memory Killer) 静默杀进程。

**本项目架构方案**：
全部基准均编译为原生动态链接库 (`.so`)，通过规范的 JNI 接口由 Kotlin 后台 Worker 协程驱动，在受控的 C/C++ 内存分配器守护下运行。
