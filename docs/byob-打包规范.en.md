# BYOB bundle packaging specification

[中文](byob-打包规范.md) · **English**


> Same source as the text printed by the in-app "Terminal → spec" command (change one, change both).
> Usage: the "Import bundle (.zip)" button accepts either a .zip bundle or a bare ELF (which is packed into
> a minimal bundle automatically).

~~~
Bundle packaging specification (BYOB)
========================================
[Option 1] Simplest: import a bare ELF
  - Requirements: aarch64 (e_machine = 0xB7) executable; dependencies must be on the bionic allow-list
    (libc.so / libm.so / libdl.so / liblog.so / libz.so / libstdc++.so /
     libc++_shared.so / libandroid.so / libatomic.so);
    glibc names (libc.so.6, ld-linux-aarch64.so.1, ...) are rejected outright.
  - Behaviour: the app generates a minimal bundle (no arguments, no reference-output verification);
    the benchmark id is taken from the file name.
  - Suitable for: your own small programs and single-file microbenchmarks.

[Option 2] Full: a .zip bundle (arguments + verification against a reference output)
  Layout inside the zip (root level, do not nest a folder):
    runner.json             required, see below
    bin/<executable>        required, arm64 ELF
    data/input/...          optional, input data sets
    data/output/<ref>       optional, reference output used for verification

  runner.json fields:
    benchmark_id            string, unique case id
    binary_path             relative path, e.g. bin/lbm
    command_args            array of strings; entries starting with "data/" are rewritten to
                            absolute paths inside the bundle
    stdout_file             file name the captured stdout is written to
                            (do NOT reuse a name the program itself writes)
    oracle_rule {
      type                  "none" disables verification; any other value verifies with the
                            tolerances below
      reference_file        relative path, e.g. data/output/lbm.out
      abs_tolerance         absolute tolerance, e.g. 1e-7
      rel_tolerance         relative tolerance, e.g. 1e-4
      ignore_line_patterns  array of strings: a line matching any substring is dropped from
                            BOTH sides before comparison
                            (timing / allocation lines that differ on every run must be excluded)
    }
    execution_profile {
      affinity_policy           reserved, currently "none"
      cooldown_target_celsius   cooldown target; 0 means no cooldown wait
      max_execution_seconds     wall-clock limit
    }

[Verification semantics] Line by line: tokens are split on whitespace; when both sides parse as
  numbers the check is |a-b| <= abs_tol + rel_tol*|b|, otherwise it is an exact string comparison;
  blank lines are ignored on both sides.
  This only answers "does the output match the reference" - it is not any official score.

[Example bundle] inside 519.lbm_r.zip:
    runner.json  benchmark_id=519.lbm_r / binary_path=bin/lbm
                 command_args=[20, reference.dat, 0, 1, 100_100_130_cf_a.of]
                 stdout_file=lbm.out
                 oracle_rule={type=numeric_tolerance, reference_file=data/output/lbm.out,
                              abs_tolerance=1e-7, rel_tolerance=1e-4}
  The input file 100_100_130_cf_a.of sits in the bundle root (the program opens it by bare
  file name, so it must be inside the working directory).

[SPEC-related: rigorous statement]
  - This terminal is NOT the same as "the usual practice of adb-pushing official SPEC binaries to a
    phone": the official methodology requires the SPEC toolchain, official flags (base/peak tuning),
    official input sets and the official run rules. This channel is "user-supplied sources + local
    compilation + consistency check against a reference output". The two are not interchangeable and
    must not be compared.
  - The verdict has exactly two meanings: output matches the reference, or it does not. This channel
    produces NO SPEC metric of any kind and must not be called a SPEC score, SPECratio, SPECspeed or
    SPECrate, nor compared with anything published on spec.org.
  - Compiling with -DSPEC exists purely for output fidelity (so the program output matches the official
    reference and can be compared at all); it does not confer official build settings or official results.
  - This project distributes no SPEC sources, input sets, reference outputs or official toolchain;
    bundles must be produced by users from media they legally hold.

[Common pitfalls]
  - Bundles must be arm64: x86_64 binaries (including emulators with ARM translation) are rejected by the
    ELF admission check;
  - Reusing a file name the program itself writes as stdout_file makes verification read the wrong file
    (this actually happened with mcf);
  - If timing / allocation lines are not excluded, even a correct implementation is reported as a mismatch;
  - Binaries that need glibc must be linked with -static before packaging.
~~~

