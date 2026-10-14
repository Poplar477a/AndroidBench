<p align="center">
  <img src="docs/assets/icon.png" width="112" alt="AndroidBench icon">
</p>

# UFS Storage Benchmark · AndroidBench

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/badge/release-0.1.0--Alpha-orange.svg)](../../releases)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B%20(API%2026)-green.svg)](#)
[![Network](https://img.shields.io/badge/network-none-brightgreen.svg)](#9-license-trademarks-and-compliance)

[中文](README.md) · **English**

> **A storage performance benchmark for Android**: an **independent implementation** that references the
> test models of FIO and Iometer, ships compute / memory-hierarchy kernels trimmed from upstream sources
> (CoreMark / STREAM / tinymembench), and supports user-supplied bundles (BYOB) for binary-level testing
> and verification.

This is an independently implemented measurement tool. It is **not affiliated with, sponsored by, or
endorsed by** JEDEC / SPEC / EEMBC / Google. Licensed under Apache-2.0 (see LICENSE); third-party
copyrights and licenses are listed in NOTICE and THIRD_PARTY_LICENSES.md.

---

## 1. Background and design philosophy

On Android, with **UFS 3.1 / UFS 4.0** storage, sequential read bandwidth exceeds **4000 MB/s** and random
IOPS goes beyond **300K**. Existing mobile tools (e.g. AndroBench) typically suffer from:

1. **Outdated engine** — not adapted to modern Linux kernels nor to Android 10+ scoped storage;
2. **No multi-dimensional access model** — no way to express Iometer-style queue depth, read/write mix
   ratios or randomness;
3. **Page-cache pollution** — without `O_DIRECT` the measured number is RAM speed, not raw flash speed.

This project combines two industry reference points:

- **Iometer access model** — its **Access Specifications** (transfer request size, percent
  random/sequential, percent read/write, queue depth, workers concurrency) are re-implemented in a C++17
  native core (**Dynamo Native Engine**) built on `O_DIRECT`.
- **FIO scheduling engine** — the industry-standard **Flexible I/O Tester** can be cross-compiled into an
  Android library for streaming JSON output and professional storage stress workloads. (fio is **not**
  bundled; see §9.)

---

## 2. Architecture

    ┌────────────────────────────────────────────────────────┐
    │              Android UI (Jetpack Compose)              │
    │  - DashboardScreen: live dashboard (MB/s, IOPS, latency)│
    │  - SpecificationScreen: Iometer access-spec builder    │
    │  - ReportScreen: history and comparison                │
    └──────────────────────────┬─────────────────────────────┘
                               │ (Coroutines / StateFlow)
    ┌──────────────────────────▼─────────────────────────────┐
    │                 BenchmarkViewModel                     │
    │  - state machine (IDLE -> PREPARING -> RUNNING -> DONE) │
    │  - automatic drop_caches (root)                        │
    └─────────────┬────────────────────────────┬─────────────┘
                  │                            │
                  ▼                            ▼
    ┌───────────────────────────┐ ┌──────────────────────────┐
    │  Dynamo Native Engine     │ │  FIO Kernel Engine       │
    │  - C++17 native (JNI)     │ │  - cross-compiled libfio │
    │  - O_DIRECT bypasses cache│ │  - POSIX AIO multi-queue │
    │  - 4 KB aligned access    │ │  - streaming JSON parse  │
    └───────────────────────────┘ └──────────────────────────┘
                  │                            │
                  └─────────────┬──────────────┘
                                ▼
                  ┌──────────────────────────┐
                  │  Android Flash (UFS 4/3) │
                  │  - F2FS / EXT4 partitions│
                  └──────────────────────────┘

---

## 3. Test parameter matrix

### 3.1 Transfer request size
- **4 KB** — tiny random I/O, which dominates UI smoothness and cold-start latency.
- **128 KB** — everyday media streaming and typical file I/O.
- **1 MB / 2 MB** — large sequential throughput, i.e. the multi-channel bandwidth ceiling of the UFS device.

### 3.2 Concurrency
- `Q1T1` — single thread, single queue: true single-task latency.
- `Q8T1 / Q8T4` — moderate background concurrency.
- `Q32T16` — heavy multi-threaded deep-queue load, saturating UFS 4.0 channels.

### 3.3 Pattern
- **SEQ** — peak throughput.
- **RND** — IOPS stress.
- **MIX** — read/write mix freely adjustable from **0% to 100%**
  (a classic 70% read / 30% write workstation model is included).

---

## 4. Build and run

### 4.1 Build the Android app

    # Linux / macOS / WSL
    ./gradlew assembleDebug

    # Windows
    gradlew.bat assembleDebug

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

### 4.2 Cross-compile fio (optional)

To enable the fio engine, run the provided script; it fetches the sources and builds them with the NDK:

    export ANDROID_NDK_HOME=/path/to/android-ndk
    bash tools/build_fio_arm64.sh

The artifact is placed in `app/src/main/jniLibs/arm64-v8a/libfio.so`. **Read the GPL-2.0 warning at the top
of that script first**: shipping the fio binary inside a distributed APK makes the whole APK subject to
GPL-2.0.

---

## 5. Key performance guarantees (avoiding mobile pitfalls)

1. **Direct I/O bypasses the cache** — every read/write handle uses `O_DIRECT`, so the numbers come from the
   flash controller rather than from RAM.
2. **Avoiding the FUSE penalty** — internal private mount points (talking directly to `f2fs` / `ext4`) are
   detected and preferred, avoiding the 15–30% penalty of Android 10+ scoped storage through `/sdcard`.
3. **Safe space pre-allocation** — before timing starts, the target file is pre-allocated in aligned blocks
   filled with incompressible pseudo-random bytes, so metadata allocation does not add latency jitter.

---

## 6. Packaging and artifacts

### 6.1 Build

    ./build.sh --max-workers=4 assembleStandardRelease assembleLabRelease

| flavor | applicationId | notes |
|---|---|---|
| standard | com.ufs.bench | normal build (`FEATURE_ROOT_LAB=false`) |
| lab | com.ufs.bench.lab | lab build: BYOB binary testing, professional mode (`FEATURE_ROOT_LAB=true`) |

Different applicationIds, same signing key, so both can be installed side by side. minSdk 26 / targetSdk 34
→ APK Signature Scheme **v2** is sufficient (v1 is not required).

### 6.2 Signing

Credentials live in `keystore.properties` (not committed, see `.gitignore`):

    storeFile=keystore/ufs-bench.jks
    keyAlias=ufsbench

Certificate: `CN=UFS Benchmark, OU=Storage Lab, O=UFSBench, L=Shenzhen, ST=Guangdong, C=CN`
SHA-256: `e79e1ba007e3978de51d71b9745ce940bbcd84c3a463fd8a89dcd5cc9b0acf44`

**Changing the key breaks updates over an installed build** (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`;
uninstalling loses data).

### 6.3 Output directory `dist/` (not committed)

    dist/UFSBenchmark-<version>-standard.apk
    dist/UFSBenchmark-<version>-lab.apk
    dist/SHA256SUMS.txt      # verify with sha256sum -c
    dist/README.md

### 6.4 The sandbox helper must match its source (drift guard)

`app/src/lab/jniLibs/<abi>/libbyob_run.so` is a **prebuilt executable checked into the repository**
(Android only allows executing native binaries from a lib directory). Its source is also here
(`tools/sandbox_test/byob_run.cpp` + `app/src/main/cpp/sandboxed_runner.cpp`). Rules:

    tools/sandbox_test/build_byob_helper.sh            # build from source and compare with the committed binary
    tools/sandbox_test/build_byob_helper.sh --install  # update in place (then rebuild the app and re-verify on a device)

This guard already caught a real drift: the arm64 helper matched its source while the **x86_64 one was
stale**. Note that AGP **strips** native libraries when packaging, so the helper inside the APK is smaller
than the one in jniLibs (935 KB → 139 KB measured) — that is expected, and "byte equality" is **not** a
valid drift test; compare the jniLibs file itself.

### 6.5 App icon

- Sources: `tools/icon/source/lab.png` (lab), `tools/icon/source/standard.jpg` (standard), both 1280×1280;
- Regenerate with `tools/icon/make_icons.sh` (uses ImageIO from `.tools/jdk-17`, no third-party imaging
  library required);
- Per flavor it produces `mipmap-*/ic_launcher{,_round}.png`, `drawable-*/ic_launcher_foreground.png`,
  `mipmap-anydpi-v26/ic_launcher{,_round}.xml` and `values/ic_launcher_background.xml`;
- Two gotchas worth knowing:
  1. the circular mask must use `setClip(Ellipse) + drawImage` — `AlphaComposite.SrcIn + fill` silently
     does nothing (measured: corner alpha stayed 255);
  2. release APKs shorten resource file names, so verify icons through `aapt2 dump resources` and by
     comparing PNG sizes between the two flavors — never by file name.

---

## 7. Custom ELF and bundle packaging rules

The terminal (lab build) can run **user-supplied arm64 executables**, in two ways.

### 7.1 Bare ELF (simplest)

Put an arm64 executable on the device (your own microbenchmark, or a single-file program you obtained
elsewhere) and pick it via "Terminal → Import bundle (.zip)". The app then creates a **minimal bundle**
automatically: no command-line arguments, no reference-output verification, `benchmark_id` taken from the
file name.

| Admission item | Requirement |
|---|---|
| Architecture | **aarch64** (ELF `e_machine = 0xB7`); x86_64 / arm32 are rejected |
| Dependencies | bionic allow-list only: `libc.so` `libm.so` `libdl.so` `liblog.so` `libz.so` `libstdc++.so` `libc++_shared.so` `libandroid.so` `libatomic.so` |
| Rejected | glibc names such as `libc.so.6` `libm.so.6` `libpthread.so.0` `libgcc_s.so.1` `ld-linux-aarch64.so.1` — if you need glibc, link **statically** first |
| Permissions | executable bit; the import flow places it into app-private storage and runs the ELF admission check |

### 7.2 .zip bundle (arguments + reference-output verification)

The zip contains the layout **at its root** (do not nest a folder):

    runner.json            required
    bin/<executable>       required, arm64 ELF
    data/input/…           optional, input data
    data/output/<ref>      optional, reference output for verification

`runner.json` fields:

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

- Arguments starting with `data/` are rewritten to **absolute paths inside the bundle**;
- `oracle_rule.type = "none"` means **no verification**, the run is only recorded;
- `ignore_line_patterns` is a **substring** filter: matching lines are dropped from both sides
  (timing, memory addresses and similar per-run noise must be excluded);
- Do **not** reuse a file name that the program itself writes as `stdout_file` — otherwise the runner will
  not persist the captured stdout and verification reads the wrong file.

### 7.3 Verification semantics

Line by line: tokens are split on whitespace; when both sides parse as numbers the comparison is
`|a-b| <= abs_tol + rel_tol*|b|`, otherwise it is an exact string comparison; **blank lines are ignored on
both sides**. This only answers "does the output match the reference" and is **not** any official score.

### 7.4 Common pitfalls

1. Bundles must be **arm64**; on x86_64 (including emulators with ARM translation) the run fails honestly
   instead of reporting a fake result;
2. When a program opens its input by **bare file name** (e.g. `100_100_130_cf_a.of`), that input must sit
   in the **bundle root**, because the working directory is the bundle root;
3. Timing / allocation lines that are not excluded will make even a correct implementation look "mismatched";
4. Binaries that depend on glibc must be linked with `-static` first.

Full specification: [docs/byob-打包规范.md](docs/byob-打包规范.md) (Chinese) — the in-app terminal prints
the same text for the `spec` command.

---

## 8. Rigorous statement about SPEC-related capabilities

This project's relationship with SPEC® is exactly one thing: it **provides a channel**. It neither ships
any SPEC content nor produces SPEC results.

### 8.1 Why this is not "the usual adb-pushed SPEC run"

The common practice is to build the official binaries on a PC with the official SPEC toolchain
(`runspec` / `specperl` plus official flags), `adb push` them to a phone and treat the printed numbers as
"this phone's SPEC result". **This project does not do that and is not equivalent to it**:

| Dimension | Official methodology (runspec + SPEC toolchain) | This project's BYOB channel |
|---|---|---|
| Compiler / toolchain | compiler and options mandated by SPEC flags.xml, base / peak tuning levels | user-supplied sources + Android NDK/Clang, **one fixed optimization level**, no base/peak split |
| Input sets | official test / train / ref, sizes fixed by SPEC | user-supplied (this repository ships **no** data sets) |
| Run rules | official run rules: iteration control, reporting format, reproducibility, result submission | none: it only "runs once and checks whether the output matches the reference" |
| Produced metrics | SPECspeed / SPECrate / SPECratio and other official metrics | **no SPEC metric of any kind**; only wall-clock time and a match/mismatch verdict |
| Permitted use | official results suitable for publication | self-verification and regression comparison on your own device, **never comparable with published SPEC results** |

One frequently misread detail: the bundle pipeline compiles with `-DSPEC`. That exists purely for
**output fidelity** (so the program's output matches the official reference output and can be compared at
all). It does **not** confer official build settings or official results.

### 8.2 Prohibited wording and usage

- Do not call these results SPEC scores, SPEC points, SPECratio, SPECspeed or SPECrate;
- Do not compare, rank or convert them against any number published on spec.org;
- Do not describe this project as a "SPEC port", "SPEC for Android", or imply any affiliation,
  sponsorship or certification by SPEC;
- The terminal verdict has exactly two meanings here: **output matches the reference** / **it does not**.

### 8.3 Distribution boundary

This project **does not distribute** SPEC sources, input sets (test/train/ref), reference outputs or the
official toolchain — all of these are commercially licensed content, and `.gitignore` excludes
`SPEC2017/`. Bundles can only be produced by users from media **they legally hold**. The scripts under
`tools/spec2017/` merely compile user-supplied sources into Android executables and compare outputs;
they do not redistribute any SPEC content.

---

## 9. License, trademarks and compliance

### 9.1 License

- This project: **Apache License 2.0** (see LICENSE);
- Third-party components and upstream benchmark kernels are listed in **NOTICE** and
  **THIRD_PARTY_LICENSES.md**;
- **Neither this repository nor the released APKs contain the fio binary** (GPL-2.0). If you build it in,
  read the GPL-2.0 warning at the top of `tools/build_fio_arm64.sh`: distributing an APK that embeds fio
  makes the entire APK subject to GPL-2.0.
- **This repository contains no SPEC sources, input sets or reference outputs** (commercially licensed;
  excluded by `.gitignore`). Only the BYOB channel is provided.

### 9.2 How the upstream kernels are used

| Built-in kernel | Upstream | How it is used here |
|---|---|---|
| CoreMark (1001) | EEMBC | upstream algorithm kept byte-identical plus an Android porting layer; the EEMBC agreement forbids using the CoreMark trademark on modified copies, so results are described as "a local measurement based on EEMBC CoreMark sources" — never an official score, no certification logo |
| STREAM (1002) | John D. McCalpin | a **variant kernel** (bench_alloc / pad sentinels / decimal MB/s); upstream requires modified code not to be called "STREAM benchmark results", so the UI consistently says "sustained bandwidth measured with a STREAM variant kernel" |
| tinymembench (1003) | Siarhei Siamashka | MIT, trimmed port; full MIT text in THIRD_PARTY_LICENSES.md |

All built-in ratios are defined as "relative to **this device's self-calibrated baseline**"; the UI shows
the disclaimer on the same screen and never compares with any published score.

### 9.3 Trademarks

UFS® belongs to JEDEC; CoreMark® to EEMBC; SPEC® / SPEC CPU® to SPEC; Android™ to Google LLC;
CrystalDiskMark to Noriyuki Miyazaki. This project is an independent implementation, **not affiliated
with, sponsored by or endorsed by** any of them, and reuses no official logos; names appear only as
factual references. See NOTICE and the in-app "Settings → About" page for the full statement.

