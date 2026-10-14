#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SPEC CPU2017 -> Android BYOB bundle build pipeline
============================================================================
Cross-compiles SPEC2017 sources from the user's own installation media into Android bundles
(bin/ + data/ + runner.json) that the UFS Benchmark "binary testing (BYOB)" feature can import.

Official-convention notes (verified on this project, see docs/verified-facts.md round 27):
  - Compiling with -DSPEC is mandatory: the official reference outputs were produced with that macro.
    Without it the code takes #if !defined(SPEC) branches and both line counts and values may differ.
  - mcf additionally needs -DSPEC_HAVE_INTTYPES_H and the benchmark's own src/spec_qsort/
    (the official run uses SPEC's own qsort; different sort stability changes the iteration path).
  - The official reference outputs are used as-is; this pipeline never redistributes SPEC content.

SPEC CPU2017 → Android BYOB 组件包 构建流水线
============================================================================
把 DVD 安装介质里的 SPEC2017 源码，按**官方口径**交叉编译成 Android 可执行的
组件包（bin/ + data/ + runner.json），供 UFS Benchmark 的「二进制测试（BYOB）」
功能导入运行。

官方口径（本项目实测确认，见 docs/verified-facts.md 第 27 轮）：
  · 必须加 -DSPEC 编译 —— 官方的参考输出就是在该宏下生成的；
    不加会走 #if !defined(SPEC) 分支，输出行数与数值都可能与官方参考不符。
  · mcf 还需 -DSPEC_HAVE_INTTYPES_H 并链接基准自带的 src/spec_qsort/
    （官方用 SPEC 自己的 qsort，排序稳定性不同会改变迭代轨迹）。
  · 参考输出取基准自带的 data/<scale>/output/<file>，判分用容差比对。

用法：
  python3 tools/spec2017/build_components.py --spec-root SPEC2017 \
      --out .evidence/byob_pkg [--only 519.lbm_r] [--scale test] [--no-host-check]
"""
import argparse, hashlib, io, json, os, shutil, subprocess, sys, tempfile, zipfile

NDK_DEFAULT = "/mnt/d/Beta/.tools/android-sdk/ndk/30.0.16248370"
API = 26

# 组件描述表：每个组件声明源码、编译开关、运行参数、参考输出与判分过滤
BENCHES = [
    {
        "id": "519.lbm_r",
        "title": "LBM（格子玻尔兹曼流体动力学）",
        "src_glob": ["src/lbm.c", "src/main.c"],
        "includes": ["src"],
        "extra_src": [],
        "defines": ["SPEC"],
        "libs": ["m"],
        # 输入放运行目录根、参数用裸名：与 SPEC 官方姿势一致（输入被复制进运行目录）。
        # 该程序会把文件名回显进输出，若用 data/input/ 前缀会与官方参考的文本行不一致。
        "args": ["20", "reference.dat", "0", "1", "100_100_130_cf_a.of"],
        "input_dir": "root",
        "inputs": ["100_100_130_cf_a.of"],
        "stdout_file": "lbm.out",
        "reference": "output/lbm.out",
        "ignore": [],
        "note": "单线程；工作集 2×204.5 MB，衡量内存带宽与流场计算",
    },
    {
        "id": "505.mcf_r",
        "title": "MCF（网络单纯形 车辆调度）",
        "src_glob": ["src/*.c"],
        "includes": ["src", "src/spec_qsort"],
        "extra_src": ["src/spec_qsort/*.c"],
        "defines": ["SPEC", "SPEC_HAVE_INTTYPES_H"],
        "libs": ["m"],
        "args": ["data/input/inp.in"],
        "input_dir": "data/input",
        "inputs": ["inp.in"],   # control 实测非必需（SPEC 运行器辅助文件，mcf 不读）
        # stdout_file 必须用程序自己**不会写**的文件名：
        # mcf 会把目标值写进它自己的 mcf.out，若同名则运行器不会落盘捕获的 stdout，
        # 判分就会拿 mcf.out（仅 1 行数值）去比官方 56 行报告 -> 误判不符（2026-10-14 真机实测）
        "stdout_file": "report.out",
        "reference": "output/inp.out",
        "ignore": ["runtime = "],   # 判分按子串匹配（含计时行）
        "note": "整数 + 指针追逐（链表/隐式弧），衡量分支预测与访存延迟",
    },
]


def sh(cmd, cwd=None, check=True):
    r = subprocess.run(cmd, cwd=cwd, shell=isinstance(cmd, str),
                       capture_output=True, text=True)
    if check and r.returncode != 0:
        sys.stderr.write("命令失败(%d): %s\n%s\n" % (r.returncode, cmd, r.stderr[-1500:]))
        raise SystemExit(1)
    return r


def sha256(path):
    h = hashlib.sha256()
    with io.open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def filtered(text, patterns):
    out = []
    for line in text.splitlines():
        if any(p and p in line for p in patterns):
            continue
        if line.strip() == "":       # 官方参考里不含空行，判分对空行不敏感
            continue
        out.append(line.rstrip())
    return out


def host_check(b, srcdir, scale, workdir):
    """宿主 gcc 编译并按官方参数运行，与官方参考逐行比对（数值容差 1e-4）"""
    exe = os.path.join(workdir, "host_" + b["id"].replace(".", "_"))
    import glob as _glob
    srcs = []
    for g in b["src_glob"]:
        srcs += sorted(_glob.glob(os.path.join(srcdir, g)))
    for g in b["extra_src"]:
        srcs += sorted(_glob.glob(os.path.join(srcdir, g)))
    cmd = ["gcc", "-O2", "-std=gnu99", "-w"]
    cmd += ["-D" + d for d in b["defines"]]
    for inc in b["includes"]:
        cmd += ["-I", os.path.join(srcdir, inc)]
    cmd += srcs + ["-o", exe]
    for lib in b["libs"]:
        cmd += ["-l" + lib]
    sh(cmd)
    rundir = tempfile.mkdtemp(prefix="specrun_")
    # 与设备端布局保持一致：按描述符声明的位置放置输入
    indir = rundir if b.get("input_dir", "data/input") == "root"         else os.path.join(rundir, "data", "input")
    os.makedirs(indir, exist_ok=True)
    for f in b["inputs"]:
        shutil.copy(os.path.join(srcdir, "data", scale, "input", f), indir)
    r = subprocess.run([os.path.abspath(exe)] + b["args"], cwd=rundir, capture_output=True, text=True)
    got = filtered(r.stdout, b["ignore"])
    ref_path = os.path.join(srcdir, "data", scale, b["reference"])
    ref = filtered(io.open(ref_path, encoding="utf-8", errors="replace").read(), b["ignore"])
    shutil.rmtree(rundir, ignore_errors=True)
    if len(got) != len(ref):
        return False, "行数不同：实测 %d / 参考 %d" % (len(got), len(ref))
    for i, (a, e) in enumerate(zip(got, ref)):
        ta, te = a.split(), e.split()
        if len(ta) != len(te):
            return False, "第 %d 行 token 数不同" % (i + 1)
        for x, y in zip(ta, te):
            try:
                fx, fy = float(x), float(y)
                if abs(fx - fy) > 1e-4 + 1e-4 * abs(fy):
                    return False, "第 %d 行数值超出容差：%s vs %s" % (i + 1, x, y)
            except ValueError:
                if x != y:
                    return False, "第 %d 行文本不同：%s vs %s" % (i + 1, x, y)
    return True, "与官方参考一致（%d 行）" % len(ref)


def ndk_cc(ndk, triple):
    return os.path.join(ndk, "toolchains", "llvm", "prebuilt", "linux-x86_64", "bin",
                        "%s%d-clang" % (triple, API))


def build_android(b, srcdir, ndk, triple, static, outdir):
    import glob
    tag = triple.split("-")[0] + ("_static" if static else "")
    exe = os.path.join(outdir, "%s_%s" % (b["id"].replace(".", "_"), tag))
    srcs = []
    for g in b["src_glob"]:
        srcs += sorted(glob.glob(os.path.join(srcdir, g)))
    for g in b["extra_src"]:
        srcs += sorted(glob.glob(os.path.join(srcdir, g)))
    cmd = [ndk_cc(ndk, triple), "-O2", "-std=gnu99", "-w"]
    if static:
        cmd.append("-static")
    cmd += ["-D" + d for d in b["defines"]]
    for inc in b["includes"]:
        cmd += ["-I", os.path.join(srcdir, inc)]
    cmd += srcs + ["-o", exe]
    for lib in b["libs"]:
        cmd += ["-l" + lib]
    sh(cmd)
    return exe


def package(b, srcdir, scale, exe, outroot):
    pkg = os.path.join(outroot, b["id"])
    shutil.rmtree(pkg, ignore_errors=True)
    os.makedirs(os.path.join(pkg, "bin"))
    os.makedirs(os.path.join(pkg, "data", "input"))
    os.makedirs(os.path.join(pkg, "data", "output"))
    shutil.copy(exe, os.path.join(pkg, "bin", os.path.basename(exe)))
    indir = pkg if b.get("input_dir", "data/input") == "root" \
        else os.path.join(pkg, "data", "input")
    for f in b["inputs"]:
        shutil.copy(os.path.join(srcdir, "data", scale, "input", f),
                    os.path.join(indir, f))
    ref = os.path.join(srcdir, "data", scale, b["reference"])
    shutil.copy(ref, os.path.join(pkg, "data", "output", os.path.basename(ref)))
    runner = {
        "benchmark_id": b["id"],
        "binary_path": "bin/" + os.path.basename(exe),
        "command_args": b["args"],
        "stdout_file": b["stdout_file"],
        "oracle_rule": {
            "type": "numeric_tolerance",
            "reference_file": "data/output/" + os.path.basename(ref),
            "abs_tolerance": 1e-7,
            "rel_tolerance": 1e-4,
            "ignore_line_patterns": b["ignore"],
        },
        "execution_profile": {"affinity_policy": "none",
                              "cooldown_target_celsius": 0.0,
                              "max_execution_seconds": 600},
    }
    io.open(os.path.join(pkg, "runner.json"), "w", encoding="utf-8").write(
        json.dumps(runner, ensure_ascii=False, indent=2) + "\n")
    zpath = os.path.join(outroot, b["id"] + ".zip")
    if os.path.exists(zpath):
        os.remove(zpath)
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(pkg):
            for f in files:
                p = os.path.join(root, f)
                z.write(p, os.path.relpath(p, pkg))
    return zpath


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--spec-root", default="SPEC2017")
    ap.add_argument("--ndk", default=NDK_DEFAULT)
    ap.add_argument("--out", default=".evidence/byob_pkg")
    ap.add_argument("--scale", default="test")
    ap.add_argument("--only", default=None)
    ap.add_argument("--no-host-check", action="store_true")
    a = ap.parse_args()

    os.makedirs(a.out, exist_ok=True)
    scratch = os.path.join(a.out, ".bin")
    os.makedirs(scratch, exist_ok=True)
    manifest = []
    for b in BENCHES:
        if a.only and b["id"] != a.only:
            continue
        srcdir = os.path.join(a.spec_root, "benchspec", "CPU", b["id"])
        print("\n=== %s  %s ===" % (b["id"], b["title"]))
        rec = {"id": b["id"], "title": b["title"], "note": b["note"]}
        if not a.no_host_check:
            ok, msg = host_check(b, srcdir, a.scale, scratch)
            rec["host_check"] = ("PASS: " + msg) if ok else ("FAIL: " + msg)
            print("   宿主口径校验：", rec["host_check"])
        else:
            rec["host_check"] = "skipped"
        built = {}
        for triple, static in (("aarch64-linux-android", False),
                               ("aarch64-linux-android", True),
                               ("x86_64-linux-android", False)):
            exe = build_android(b, srcdir, a.ndk, triple, static, scratch)
            built[os.path.basename(exe)] = exe
            print("   编译 %-28s %8d 字节  sha256=%s" %
                  (os.path.basename(exe), os.path.getsize(exe), sha256(exe)[:16]))
        arm = built[[k for k in built if k.endswith("_aarch64")][0]]
        z = package(b, srcdir, a.scale, arm, a.out)
        rec["bundle"] = os.path.basename(z)
        rec["bundle_bytes"] = os.path.getsize(z)
        rec["binary_sha256"] = sha256(arm)
        rec["args"] = b["args"]
        print("   组件包 %s  %.1f KB" % (rec["bundle"], rec["bundle_bytes"] / 1024.0))
        manifest.append(rec)
    io.open(os.path.join(a.out, "MANIFEST.json"), "w", encoding="utf-8").write(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print("\n清单：", os.path.join(a.out, "MANIFEST.json"))
    for r in manifest:
        print("  %-12s %-8s %s" % (r["id"], "%.0f KB" % (r["bundle_bytes"] / 1024.0),
                                   r["host_check"][:60]))


if __name__ == "__main__":
    main()

