# 实现进度记录（压测内核层）

> 记录时间：2026-10-13 深夜
> 范围：docs/ARCHITECTURE.md 中「原生基准内核层」第一批（P0 三个内核）+ 构建/验证链路
> 验证设备：Redmi Note 12 Turbo（Snapdragon 7+ Gen 2，Cortex-A715 2.91GHz，已 root）
> 验证方式：NDK r30 交叉编译 arm64-v8a → adb push /data/local/tmp → 真机执行
> 说明：androidbench_cli 只是**开发期验证驱动**；交付形态仍是 libandroidbench.so + JNI（尚未接）。

## 一、已落地

| 层 | 文件 | 说明 |
|---|---|---|
| 统一契约 | src/main/cpp/include/bench_api.h | 内核入口、参数、结果、正确性校验、取消、进度、内存预算 |
| 运行时 | src/main/cpp/src/bench_common.c | 预算预检 + 峰值统计（bench_alloc/free）、单调时钟、weak 符号内核注册表 |
| CoreMark | src/bench/coremark/{upstream,port,bench_coremark.c} | 上游算法文件**逐字节未改**；-Dmain=coremark_cli_main 嵌入；移植层仅改计时源与分配路径 |
| STREAM | src/bench/stream/bench_stream.c | Copy/Scale/Add/Triad + 上游同款终值校验（eps=1e-8） |
| tinymembench | src/bench/tinymembench/bench_tinymembench.c + aarch64-asm.S | NEON 拷贝/填充 + 指针追逐延迟阶梯；剔除 HugePages/性能计数器 |
| 构建 | src/main/cpp/CMakeLists.txt | 共享库 + 验证 CLI；-DBENCH_KERNELS 可裁剪内核；16KB 页对齐 |
| 验证驱动 | tools/bench_cli.c | list / 单内核执行 / 预算演示；宿主与设备通用 |

上游来源：upstream/coremark @1f483d5（Apache-2.0）、upstream/stream @6703f75、upstream/tinymembench @a2cf6d7（MIT）。

## 二、真机实测（2026-10-13 23:5x，Redmi 33ed7dff）

| 内核 | 主指标 | 其它实测 | 正确性 |
|---|---|---|---|
| CoreMark 单线程（官方自动标定 ≥10s） | **122,156 iterations/s** | 3,200,000 次迭代 / 26.2 s | PASS：crclist 0xe714 / crcmatrix 0x1fd7 / crcstate 0x8e3a，与上游官方已知值一致 |
| CoreMark 多线程（4 线程，宿主机对照） | 197,044 iterations/s（全线程总分口径） | 80,000 迭代 / 0.406 s | PASS |
| STREAM（64MB 工作集，NTIMES=10） | Triad **34,052 MB/s** | Copy 34,013 / Scale 33,306 / Add 33,540 MB/s | PASS：终值校验最大相对误差 0，失配元素 0 |
| tinymembench | 顺序读最大档 **31,911 MB/s** | 顺序写 45,782 / 拷贝 17,398 MB/s；延迟阶梯 L1 6.3 ns → DRAM **92.7 ns**（6 档单调） | PASS：带宽自洽 + 延迟单调双判据 |

内存预算拦截已实测：把预算压到 8 KB 时，CoreMark 返回 BENCH_ERR_ALLOC 并给出「需要 16000 字节」的说明，**不产生任何虚假成绩**。

## 三、本轮踩到并已修的坑（都写进了代码注释）

1. **HAS_PRINTF 陷阱**：上游 coremark.h 在 HAS_PRINTF=1 时会自己 #define ee_printf printf，
   分数与 CRC 直接打到 stdout，我们的捕获/解析层全废。必须 HAS_PRINTF=0（移植头再接管 ee_printf）。
2. **头文件守卫撞车**：我们的 core_portme.h 与上游 posix 头同名守卫 CORE_PORTME_H，
   导致上游头的类型定义整体被跳过（报 CORE_TICKS/ee_u16 未定义）。守卫已改名。
3. **FLAGS_STR / MEM_LOCATION**：上游要求构建系统提供；带引号带空格的 -D 在 Unix Makefiles 下会被拆坏
   （实测 "Unterminated quoted string"）。改为「纯数字宏 + 头文件里 # 字符串化」和「移植头内定义 C 字符串」。
4. **clang 集成汇编器不认 .func/.endfunc**：上游 aarch64-asm.S 按 GNU as 编写。
   构建期生成去掉这两行的派生副本，上游文件保持逐字节不变。
5. **上游不判 NULL**：core_main.c 用 portable_malloc 返回值不检查，预算不足会段错误 → 适配层加探针分配预检。
6. **语义区分**：上游在「时长 <10 s」时也会打印 Errors detected —— 那是成绩资格问题，不是 CRC 失败。
   现在 CRC 直接与上游官方已知值比对，独立判定。

## 四、与既有规范的偏差（需要回写 docs）

- **SCALE_MANAGEMENT 第 14.5 节 A 的 CoreMark 估算「每迭代约 0.2 ms」严重偏慢**：
  实测每迭代约 **8.2 µs**（122,156 iter/s），差约 25 倍。若按 0.2 ms 估算时间预算，
  Smoke(1000 迭代) 会被算成 200 ms、实际 8 ms，Standard(50000 迭代) 会被算成 10 s、实际 0.4 s ——
  规模选择与预算裁剪都会系统性失真。run_acceptance_tests.py 里的 CoreMarkPolicy 同源，
  建议一并按实测重标（或改为运行时标定）。
- tinymembench 顺写 45.8 GB/s > 顺序读 31.9 GB/s，需要复核口径（写是否走了流式/写合并路径）。
  本轮先如实记录，不下结论。

## 五、未完成（下一步）

1. **JNI + Kotlin 运行层**：把 bench_registry 暴露给 Kotlin（NativeBenchBridge），
   与已有 scale/guard/report 骨架对接（ResourceGuard 的预算 → bench_set_memory_budget）。
2. **P0 剩余两项**：Zstandard（upstream/zstd，需裁剪到 lib/common+compress+decompress）、NPB EP（upstream/npb-cpp）。
3. **UI**：BenchmarkMatrixScreen（P0/P1/P2 选择、规模等级）尚未开始。
4. 规模策略回写（见第四节第一条）。


---

## 内核双消费关系（防漂移声明，2026-10-14）

同一套裁剪内核目前被**两个消费方**使用，内容**逐字节一致**（已用 diff -rq 校验）：

| 消费方 | 路径 | 用途 |
|---|---|---|
| AndroidBench（本套件） | src/main/cpp/{include,src/bench,tools} | 独立基准套件的内核层 + 开发期 CLI |
| UFS Benchmark App | app/src/main/cpp/bench/（含 builtin_bench.cpp 桥接层） | 以「内置基准」形态接入，走 JNI 与三档强度挡位 |

约定：
1. **内核源码（bench_api.h / bench_common.c / coremark / stream / tinymembench）只有一份事实来源**，
   当前以 AndroidBench 侧为源；改动后必须同步 App 侧并重新 diff 校验，禁止两边各改一份；
2. 两边的差异只允许存在于**桥接/驱动层**（App 的 builtin_bench.cpp 负责把 RunConfig 映射成三档参数；
   AndroidBench 的 bench_cli.c 负责命令行驱动）；
3. App 侧构建脚本会为 tinymembench 的 aarch64 汇编生成一份去掉 .func/.endfunc 的派生副本
   （clang 集成汇编器不认这两条 GNU as 指示符），上游 .S 保持逐字节不变。
