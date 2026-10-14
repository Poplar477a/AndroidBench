# 散点图数据契约（前后端共同遵循）

本次改动的唯一事实源。原生层与 UI 层分别实现，接口以此为准，不得单方面改动。

## 1. 采样档位可选

`AccessSpecification` 新增：
```cpp
uint32_t sample_interval_ms = 100;   // 允许值：5 / 10 / 25 / 50 / 75 / 100
```
Kotlin 侧 `IometerAccessSpec.sampleIntervalMs: Int = 100`，需持久化（SpecStore）。
JNI 新增参数 `sampleIntervalMs: Int`。

> 替换原先写死的 `kSampleInterval = professional ? 0.1 : 1.0`。
> 专业模式与常规模式都使用用户选定的间隔；未开启专业模式时曲线不产出，但进度回调仍按该间隔。

## 2. 原生层产出分读写的曲线

`DetailedStats` 中，**删除** `curve_bw_mbps` 与 `curve_lat_us`（合并口径），改为：
```cpp
std::vector<double> curve_read_bw_mbps;    // 每个采样区间的读吞吐 MB/s
std::vector<double> curve_write_bw_mbps;   // 每个采样区间的写吞吐 MB/s
std::vector<double> curve_read_lat_us;     // 区间内读请求的平均响应延迟
std::vector<double> curve_write_lat_us;    // 区间内写请求的平均响应延迟
std::vector<double> curve_time_s;          // 已有：采样点时刻（秒）
std::vector<double> curve_cpu_percent;     // 已有
double sample_interval_ms;                 // 已有
```

**实现要点**：worker 线程需分别累计读、写的延迟总和与完成次数（单调递增计数器），
监控线程对相邻两次采样取**增量**再求平均，得到"该区间的平均响应延迟"。
不要直接把全量延迟样本存进 vector（内存与拷贝开销过大）。

## 3. 进度回调新增分读写延迟

`ProgressMetric` 新增：
```cpp
double read_avg_latency_us;
double write_avg_latency_us;
```
JNI `onProgress` 签名相应追加两个 `D`。

## 4. 实时刷新（bug）

现象：专业模式测试过程中界面不刷新。
排查方向（按顺序）：
1. JNI 回调签名是否与实际 Kotlin 方法匹配——不匹配时 `GetMethodID` 失败会让整个
   lambda 提前 return，**完全没有任何进度回调**。用 logcat 打点确认回调是否真的在触发。
2. 采样间隔过密（100ms）× 每帧整屏重组，UI 线程被压住 → 表现为卡死而非不刷新。
   必要时对进度更新做节流（例如合并到 ~10Hz 并跳过相同状态）。
3. 确认 `collect` 所在协程未被取消或阻塞。

必须给出 logcat 证据证明回调频率与 UI 更新次数，不要只凭推理下结论。

## 5. CPU 占用口径（已定：C = 仅测试负载）

**定义**：只统计 benchmark 工作线程消耗的 CPU，**排除** UI 线程、Compose 渲染、JVM 与监控线程。

**实现**（app/src/main/cpp）：
- WorkerStats 增加 cpu_ns（单调递增，纳秒）；每个 worker 用 CLOCK_THREAD_CPUTIME_ID
  每 64 次 I/O 发布一次（__atomic 存取，兼容 armeabi-v7a 的 64 位非原子读），退出前再发布一次兜尾
- 监控线程对相邻采样取各 worker 增量求和 ÷ 墙钟间隔

**已彻底移除 CLOCK_PROCESS_CPUTIME_ID**（全仓 grep = 0）。旧口径会把界面算进去：
实测同一运行窗口内 RenderThread 占进程 CPU 5.3%、main 0.72 s，新口径已排除。

**字段**：
| 字段 | 含义 | 范围 |
|:---|:---|:---|
| cpu_util_percent / cpuUtilPercent | 各核累加 | 可 >100% |
| cpu_percent_of_cores / cpuPercentOfCores | 除以设备核数 | 0~100% |
| cpu_core_count / cpuCoreCount | 设备核数 | — |
| cpu_cores_used（BenchmarkResult） | 等价于各核累加 | 可 >100% |

**展示约定**：
- 汇总主数字用 cpuPercentOfCores（符合直觉）
- 曲线仍用各核累加口径（保留信息量）
- 文案：「测试负载 CPU 占用（仅统计 benchmark 工作线程，不含界面渲染；多线程时可超过 100%）」

**已知限制**：SPEC CPU 移植版路径不产出 CPU 占用（纯 CPU+内存负载，已用
M-Ops/s 算力指标表达计算强度）。如需为 SPEC 也加同口径，需在 gcc（1 处池）、
graph500（≥3 处池）、femflow（单线程）分别埋点，属独立改动，当前**刻意不做**，
以避免给出以偏概全的数字。
