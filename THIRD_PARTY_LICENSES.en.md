# Third-party components and licenses

[中文](THIRD_PARTY_LICENSES.md) · **English**

This file lists every third-party component that this project uses, references, or explicitly does **not**
bundle. The project's own license is in LICENSE (Apache License 2.0); the umbrella statement is in NOTICE.

---

## 1. Kernels compiled into the app

### 1.1 EEMBC CoreMark® (built-in kernel 1001)
- Source: https://github.com/eembc/coremark
- License: EEMBC's CoreMark license (see the upstream LICENSE.md / Acceptable Use Agreement)
- Usage here: `app/src/main/cpp/bench/coremark/upstream/` is the **byte-identical upstream** source;
  `coremark/port/` is an Android porting layer (renamed entry point, `bench_alloc` for memory, FLAGS_STR
  injected at compile time)
- **Compliance note**: the EEMBC agreement forbids using the CoreMark trademark on modified or derivative
  copies. Therefore this project:
  - always describes results as "a local measurement based on EEMBC CoreMark sources" and never as an
    official CoreMark score;
  - never claims EEMBC certification and never uses its certification marks;
  - states the validity rules (a single run ≥ 10 s, the three CRCs 0xe714 / 0x1fd7 / 0x8e3a) in both code
    and UI.
- Trademark: CoreMark® is a registered trademark of EEMBC.

### 1.2 STREAM (built-in kernel 1002)
- Source: https://www.cs.virginia.edu/stream/  (John D. McCalpin)
- License: a permissive custom license; if the code is modified, or if it is not run strictly by the
  upstream rules, the published results **must not** be called "STREAM benchmark results"
- Usage here: `bench/stream/bench_stream.c` is a **variant kernel** (`bench_alloc`, `pad[]` guard sentinels,
  decimal MB/s, working set driven by the intensity tier). The file header documents every difference from
  upstream.
- **Compliance note**: the UI and documentation consistently say "sustained bandwidth measured with a
  STREAM variant kernel" and never claim an official STREAM score.

### 1.3 tinymembench (built-in kernel 1003)
- Source: https://github.com/ssvb/tinymembench
- License: MIT
- Usage here: trimmed port (aarch64 NEON assembly routines plus a portable C fallback), with provenance and
  changes documented in the file header.
- Full MIT text:

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

## 2. Not bundled, but build scripts are provided

### 2.1 fio (GPL-2.0)
- Source: https://github.com/axboe/fio
- **Neither this repository nor the released APKs contain the fio binary**; `FioEngineImpl` does not load
  the engine unless an external binary is present.
- `tools/build_fio_arm64.sh` places its output in `app/src/main/jniLibs/`. **If you embed that binary in a
  distributed APK, the distribution of the entire APK becomes subject to GPL-2.0** (you must ship the
  complete corresponding source or a written three-year offer, plus the full GPLv2 text). The same warning
  is at the top of the script.

### 2.2 SPEC CPU® 2017 (BYOB channel only)
- SPEC sources, input sets (test/train/ref), reference outputs and the official toolchain are
  **commercially licensed content** and **must not be redistributed** with an open-source repository or an
  APK. This repository contains none of it (`.gitignore` excludes `SPEC2017/`).
- This project only provides BYOB (Bring Your Own Benchmark): users compile bundles from media they hold
  and import them themselves.
- **License and trademark points** (SPEC Fair Use Rules / Trademarks Policy):
  - results from non-standard runs, self-compiled or modified builds are **non-compliant** and must not use
    `SPECspeed` / `SPECrate` / `SPECratio` or any confusingly similar name;
  - the SPEC trademark must not be used to promote self-made ported workloads;
  - such results must be presented with a prominent statement that they are internal relative figures with
    no affiliation to SPEC.
- Accordingly, all built-in ratios here are defined as "relative to **this device's self-calibrated
  baseline**", with the disclaimer shown on the same screen.

---

## 3. Architecture / methodology references only, no code copied

### 3.1 Iometer / Dynamo
- `dynamo_engine.cpp` is a C++17 implementation of Iometer's **access specification model**, written from
  scratch; no Iometer source was copied.
- Early Iometer releases used the Intel Open Source License. This project documents that architectural
  reference and states clearly that it is **not supported or endorsed by Intel**.

---

## 4. Toolchain (not redistributed as part of an APK)

| Component | License | Notes |
|---|---|---|
| Android NDK / Clang / libc++ | Apache-2.0 with LLVM exception | compiles the native kernels; statically linked libc++ portions ship inside the .so |
| AndroidX / Jetpack Compose | Apache-2.0 | UI layer |
| Gradle / AGP | Apache-2.0 | build system |

---

## 5. Trademarks (umbrella statement in NOTICE)

| Name | Owner | How it is used here |
|---|---|---|
| UFS® | JEDEC | factual reference in the project name; non-affiliation stated in the README and the in-app About page |
| Android™ | Google LLC | factual reference; no affiliation |
| CoreMark® | EEMBC | factual reference to the upstream kernel; results are never official scores |
| SPEC® / SPEC CPU® | SPEC | only states the fact that "SPEC bundles are user-supplied"; no code or data is distributed |
| CrystalDiskMark® | Noriyuki Miyazaki | **factual reference** for a preset; the UI now says "standard access pattern (modelled on CrystalDiskMark presets, not its official test)" |

