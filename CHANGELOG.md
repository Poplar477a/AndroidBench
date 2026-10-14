# 更新日志

**中文** · [English](CHANGELOG.en.md)


本文件记录面向使用者的重要变更。项目处于 **Alpha（预发行）** 阶段，接口与数据结构仍可能变动。

## [0.1.0-Alpha] - 2026-10-14 · 首个预发行版

### 存储基准
- 顺序/随机读写、读写混合百分比、队列深度与并发线程均可配置；
- 无缓存口径用 O_DIRECT + fadvise(DONTNEED)，专业模式（root）走 drop_caches 与实时 I/O 调度类；
- 标准读写为"先读后写"两阶段独立测量，各自从 0 计时。

### 内置算力 / 内存层次基准（上游源码裁剪移植）
- **CoreMark**（EEMBC 源码，逐字节上游 + Android 移植层）、**STREAM**（变体内核）、**tinymembench**（裁剪移植）；
- 三档强度：低性能 / 常规 / 拓展；拓展档按 **PC 级规模**给参数，并按设备可用内存一半封顶；
- 结果与"本机自标定基线"比比值，**不代表任何官方成绩**，界面同屏展示免责声明。

### BYOB 二进制测试（实验室版）
- 导入 .zip 组件包或**裸 ELF**；arm64 ELF 准入核验（bionic 依赖白名单）；
- root 沙箱执行：netns / seccomp / capset / rlimits / 降权，失败即诚实报错，不产出假成绩；
- 与官方参考输出做容差判分（可配 ignore_line_patterns）；结果导出到公共 **Documents/UFSBench/**（重名自动加序号）；
- 真机实测：519.lbm_r 判分通过（15/15 行）、505.mcf_r 判分通过（56/56 行）。

### SPEC CPU2017 组件流水线
- tools/spec2017：按 **-DSPEC 官方口径**交叉编译 → 宿主自检（与官方参考输出逐行比对）→ 打包 → MANIFEST。
  官方口径的坑（-DSPEC、spec_qsort、stdout 重名）都写在脚本注释里。

### 合规
- standard（上架版）**不申请** MANAGE_EXTERNAL_STORAGE / READ_EXTERNAL_STORAGE，压测限应用私有目录；
- **零网络权限**（清单中没有 INTERNET）；
- Apache-2.0 许可 + NOTICE + THIRD_PARTY_LICENSES.md；商标非从属声明见「设置 → 关于」；
- arm64 原生库 16 KB 页对齐（PT_LOAD=16384）；写入类用例运行前要求可用空间 ≥ 目标体积 2 倍。

### 已知限制（如实）
- **su 调用链仍编译进 standard**（仅运行时由 FEATURE_ROOT_LAB 拦截），彻底剥离待后续版本；
- BYOB 需要 root；组件包必须是 arm64；
- 内置基准的比值是"相对本机自标定基线"，不可与 EEMBC / STREAM / SPEC 公布成绩比较；
- 仅在两台真机（SM7475 / SM8850）与模拟器上验证，未做全机型适配；
- 发布 APK 为自签名构建，非应用商店分发。

