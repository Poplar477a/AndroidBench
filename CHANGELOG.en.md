# Changelog

[中文](CHANGELOG.md) · **English**


Notable user-facing changes. The project is in **Alpha (pre-release)**; interfaces and data structures may
still change.

## [0.1.0-Alpha] - 2026-10-14 · first pre-release

### Storage benchmark
- Sequential / random reads and writes, read-write mix percentage, queue depth and worker threads are all configurable;
- Cache-free measurement uses O_DIRECT + fadvise(DONTNEED); professional mode (root) additionally performs drop_caches and sets a real-time I/O scheduling class;
- "Standard read/write" runs read and write as two independently timed phases, each starting from zero.

### Built-in compute / memory-hierarchy kernels (trimmed upstream ports)
- **CoreMark** (EEMBC sources, byte-identical upstream plus an Android porting layer), **STREAM** (variant kernel) and **tinymembench** (trimmed port);
- Three intensity tiers: low / regular / extended. The extended tier is parameterised at **PC-class scale** and capped at half of the device's available memory;
- Results are ratios against a **self-calibrated baseline** of the same device; they are **not official scores** and the UI shows the disclaimer on the same screen.

### BYOB binary testing (lab build)
- Import a .zip bundle or a **bare ELF**; admission check for arm64 ELF (bionic dependency allow-list);
- Root sandbox execution: netns / seccomp / capset / rlimits / privilege drop. Failures are reported honestly; no fake results are produced;
- Tolerance-based verification against the official reference output (configurable ignore_line_patterns); results are exported to the public **Documents/UFSBench/** folder (duplicate names are numbered automatically);
- Measured on real devices: 519.lbm_r passes (15/15 lines), 505.mcf_r passes (56/56 lines).

### SPEC CPU2017 bundle pipeline
- tools/spec2017: cross-compile with the **-DSPEC official convention**, host-side self-check (line-by-line comparison against the official reference output), packaging and a manifest. The conventions that matter (-DSPEC, spec_qsort, stdout name collisions) are documented in the script comments.

### Compliance
- The standard build requests **neither** MANAGE_EXTERNAL_STORAGE **nor** READ_EXTERNAL_STORAGE; stress targets are limited to app-private directories;
- **No network permission** whatsoever (no INTERNET in the manifest);
- Apache-2.0 license plus NOTICE and THIRD_PARTY_LICENSES.md; trademark non-affiliation statement on the in-app "Settings → About" page;
- arm64 native libraries are 16 KB page aligned (PT_LOAD = 16384); write workloads require free space ≥ 2× the target size before they start.

### Known limitations (honest list)
- The **su call chain is still compiled into the standard build** (blocked at runtime by FEATURE_ROOT_LAB); fully removing it is planned for a later version;
- BYOB requires root, and bundles must be arm64;
- Built-in ratios are "relative to this device's self-calibrated baseline" and must not be compared with published EEMBC / STREAM / SPEC results;
- Verified only on two real devices (SM7475 / SM8850) plus emulators; no fleet-wide device coverage;
- Released APKs are self-signed builds, not distributed through an app store.

