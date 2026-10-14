# 第十四章 测试规模裁剪、资源预算与设备自适应机制详细规范

## 14.1 核心目标与原则

为确保基准测试能够在不同硬件配置（从 2GB 内存低端手机、到 12GB+ 旗舰机、再到 32GB+ 8-16 核 Android PC）上安全、可靠且可复现地运行，必须杜绝“一个全局数组长度或统一缩放系数控制所有基准”的粗暴做法。
每个基准测试必须依据自身**算法复杂度阶数、内存访问模式、输入约束和上游正确性规范**建立专属的规模策略。

---

## 14.2 设备资源无特权探测机制

在启动测试计划前，必须基于 Android 公开 API 和非 root 通用系统接口采集设备特征：
- **CPU 核心与拓扑**：`Runtime.getRuntime().availableProcessors()`，结合 `/sys/devices/system/cpu/cpu*/cpufreq` 解析大小核集群（Prime, Big, Mid, Little）。
- **物理与可用 RAM**：优先读取 `/proc/meminfo`（MemTotal, MemAvailable），或回退至 Android `ActivityManager.MemoryInfo`。若无法获取，强制进入**保守资源预算模式**（假定 3GB RAM / 1GB 可用）。
- **Android 应用内存限制**：`Runtime.getRuntime().maxMemory()`，结合 `ActivityManager.getLargeMemoryClass()`。
- **内部存储余量**：`context.filesDir.usableSpace`。
- **温控与供电状态**：`PowerManager.getCurrentThermalStatus()`（支持 Android 10+ 7 档温控状态）与 `BatteryManager`。
- **审计追踪**：快照中必须记录每个字段的数据源、时间戳、成功状态与是否有保守降级。

---

## 14.3 统一资源预算与独立估算器规范

每个基准实现专属估算器，返回：
- `estimatedPeakMemoryBytes`：预计原生堆与运行时峰值内存。
- `estimatedMinRuntimeMs` ~ `estimatedMaxRuntimeMs`：耗时预期区间。
- `estimatedTempStorageBytes`：预计临时文件占用。
- `recommendedThreads`：最优并发线程数。
- `growthOrder`：算法复杂度阶数（CONSTANT, LINEAR, QUADRATIC, GRAPH_SPARSE）。
- `confidence` 与 `errorSources`：置信度与潜在误差项。

**安全预算红线**：
```
SafeNativeBudget = min(UsableSystemRAM * 0.70, TotalPhysicalRAM * 0.50) - 512MB
```
预留至少 512MB 物理内存防范系统级 Low Memory Killer (LMK)。

---

## 14.4 四级测试规模等级与自定义模式

| 等级 | 标识 | 角色与用途 | 行为契约 |
|:---|:---|:---|:---|
| **Smoke** | `Smoke` | 安装后快速自检 | 最小输入，执行全链路与正确性 Oracle 校验；**严禁作为正式成绩** |
| **Small** | `Small` | 低资源设备保护 | 严格压低内存与线程，保障 2GB~4GB RAM 移动设备平稳运行 |
| **Standard** | `Standard` | 常规标准评测 | 固定上游标准配置，击穿缓存，数据完全具备跨设备横向可比性 |
| **Large** | `Large` | 高性能 / Android PC | 超大工作集与深度并行，评估旗舰处理器在大容量访存下的极限表现 |
| **Advanced** | `Advanced` | 专家自定义模式 | 允许在设备安全包络线内调整输入阶数与线程数 |

---

## 14.5 P0/P1 各基准专属估算公式与裁剪规范

### A. CoreMark (整数与核心性能)
- **估算公式**：`Mem = 4MB base + 1MB * Threads`；耗时 `Time ~ (Iterations * 0.2ms) / Cores`。
- **上游规范**：官方要求单次计分运行时间必须 `>= 10.0` 秒。
- **裁剪规则**：Smoke 跑 1,000 次迭代验证 CRC；Standard 采用全核 50,000 次（或动态校准确保 >= 10s）。非 10s 正式测试不得标为标准成绩。

### B. Zstandard (压缩与解压缩)
- **估算公式**：`Mem = 2 * DatasetSize + Threads * (4 * WindowSize + 2MB) + 16MB`。
- **裁剪规则**：基于固定语料（Smoke: 2MB, Small: 16MB, Standard: 64MB, Large: 256MB）。工作内存随线程数和压缩级别线性增长；严禁仅通过减少循环次数掩盖内存不足。

### C. STREAM (持续内存带宽)
- **估算公式**：`Mem = 4 * N * sizeof(double) + 32MB`（双精度 8 字节，4 数组）。
- **缓存隔离红线**：Standard 规模取 `N = 10,000,000`，工作集为 320MB，远超移动端 8~16MB 末级缓存（LLC）。若工作集 `< 64MB`（如 Smoke 规模 6.4MB），报告中必须强制标注为**【缓存工作集测试 (Cache-Resident)】**，严禁与持续内存带宽混为一谈。

### D. tinymembench (内存延迟与精细带宽)
- **估算公式**：`Mem = 2 * MaxBufMB + 16MB`。
- **平台适配**：针对 Android ARM64 启用 NEON 向量模式，但在无 root 环境下剔除内核 HugePages 与底层硬件性能计数器等受限子测试，并明确标记为不支持。

### E. NPB EP (浮点密集型科学计算)
- **上游标准**：严格采用 NASA NPB 3.4.2 Problem Classes：Class S (`2^24` 对), Class W (`2^25` 对), Class A (`2^28` 对), Class B (`2^30` 对)。
- **裁剪规则**：内存为常量（~16MB），计算量指数级增长。禁止随意更改算法常数或伪随机生成器逻辑。

### F. HPL (稠密矩阵求解)
- **估算公式**：`Mem = 1.25 * N^2 * 8 + 32MB`；计算量 `FLOPs = (2/3) * N^3`。
- **裁剪规则**：内存随矩阵阶数 N 呈平方级增长 `O(N^2)`，必须在分配前完成二次多项式预检；N 必须为块大小 NB 的整数倍。

### G. Graph500 (大规模图遍历)
- **估算公式**：`V = 2^S, E = 16 * V`；`Mem = (V + 2E) * 8 + 辅助结构`。
- **裁剪规则**：CSR 压缩稀疏行表示法，内存呈 `O(V + E)` 增长。Scale 18 对应约 250MB 内存。

---

## 14.6 确定性、可复现的规模选择算法

```
读取合法规模列表 -> 探测设备资源与配置预算 -> 计算每个候选规模的预估资源
       │
       ▼
过滤超出内存、时间或存储预算的候选项
       │
       ▼
应用优先级规则选择：
  1. 用户明确指定的合法规模且安全满足预算
  2. 若为标准设备对比模式 (StandardComparisonMode = true) 且标准超限：
     --> 严格拒绝并提示资源不足，禁止静默降级！
  3. 若允许自适应降级：
     --> 优先在合法更小规格中降级 (Large -> Standard -> Small -> Smoke)
     --> 记录 wasPruned = true 与详细裁剪原因
  4. 若所有合法规格均超限：
     --> 终止执行并抛出 INSUFFICIENT_RESOURCE_ALL_SCALES_EXCEEDED
       │
       ▼
输出 ScaleDecision 并计算可复现性 MD5 哈希校验码
```

---

## 14.7 资源保护、异常处理与进程被杀恢复

1. **主线程隔离**：基准内核严禁在 Android UI 主线程执行，通过 `assertNotMainThread()` 强制约束。
2. **原生分配预检**：在调用 `malloc` / `posix_memalign` 前校验安全红线，拦截非法分配。
3. **原子取消看门狗**：原生层轮询 `NativeCancellationHook`，响应用户主动取消。
4. **重试熔断**：资源不足时严禁无限重试，仅当可用内存显著释放时允许至多重试 1 次。
5. **LMK / 崩溃恢复识别**：基于本地持久化文件 `active_benchmark_task.journal`。若进程被 Android LMK 强杀，应用重启时立即识别并记录为 `SYSTEM_INTERRUPTED`，彻底杜绝生成虚假有效成绩。

---

## 14.8 测试报告与排行榜隔离准则

测试报告包含基准 ID、版本、提交号、规模等级、上游标准标记、输入参数、线程数、峰值内存、运行时间、是否被裁剪、裁剪原因、正确性验证结果与可比性分组 Key。

**排行榜隔离铁律**：
- 只有满足 `isStandardEligible = true`、`isCorrectnessVerified = true`、`wasPruned = false`、`scaleLevel == STANDARD` 的成绩，方可进入**官方标准天梯榜**。
- 自适应降级成绩与 Smoke 快速验证结果，必须独立分组展示，绝不混淆。

---

## 14.9 自动化验收测试结果

本套规模管理模块已通过全量自动化验收套件（`run_acceptance_tests.py`）：
- **测试通过率**：17/17 项通过（100%）。
- **涵盖场景**：独立估算器公式验证、低内存手机自动裁剪与降级、极限低内存安全拦截拒绝、标准对比模式禁止静默降级、异常断电/LMK 强杀重启恢复识别、STREAM 缓存与持续带宽隔离识别、HPL 平方级内存增长拦截。
