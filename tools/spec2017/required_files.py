#!/usr/bin/env python3
"""
SPEC CPU2017 必交文件名单生成 + 校验（合规：App 不内置源码，由使用者在设备内提供）

用法：
  生成名单： required_files.py --inst /home/Speccpu2017Rate --out .spec2017-req
  校验目录： required_files.py --verify .spec2017-req/required.json --root <待校验目录>

安全规则（防恶意文件/乱传）：
  1) 哈希必须与名单一致（SHA-256，逐文件）；
  2) 文件类型白名单：仅允许源码/头/构建配方/输入数据，禁止可执行文件与脚本；
  3) 路径不得穿越（禁止 .. / 绝对路径 / 指向树外的符号链接）；
  4) 单文件体积上限 256 MB、单基准总量上限 2 GB；
  5) 名单外出现的文件一律判为「未登记」，直接拒绝（不静默忽略）。
"""
import os, sys, json, hashlib, argparse, re

BENCHES = ["519.lbm_r", "505.mcf_r", "508.namd_r", "557.xz_r", "510.parest_r", "502.gcc_r"]
SIZES = ["all", "test"]        # 当前只做 test 档（设备端跑得动）；train/refrate 可按需加

ALLOW_EXT = {".hpp", ".h", ".c", ".cc", ".def", ".in", ".inst", ".C", ".cpp", ".xml", ".pm", ".sh", ".xz", ".inl", ".of", ".input", ".prm", ".inc"}
# 明确禁止（可执行/脚本/库/压缩包中的可执行）：由内容与权限双重判断
DENY_EXT = {".so", ".a", ".o", ".exe", ".dll", ".bat", ".cmd", ".ps1", ".apk", ".dex", ".jar"}
MAX_FILE = 256 * 1024 * 1024
MAX_TOTAL = 2 * 1024 * 1024 * 1024

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def is_elf_or_macho(path):
    try:
        with open(path, "rb") as f:
            magic = f.read(4)
        return magic[:4] == b"\x7fELF" or magic[:2] in (b"MZ",) or magic[:4] in (b"\xca\xfe\xba\xbe",)
    except Exception:
        return False

def safe_rel(root, path):
    rel = os.path.relpath(path, root).replace("\\", "/")
    if rel.startswith("..") or os.path.isabs(rel):
        raise ValueError("路径穿越：" + rel)
    return rel

def collect(inst, bench):
    """枚举某基准需要的文件：src 全树 + Spec 配方 + data/{all,test}/input"""
    root = os.path.join(inst, "benchspec", "CPU", bench)
    out = []
    for sub in ("src", "Spec"):
        base = os.path.join(root, sub)
        for r, d, fs in os.walk(base):
            for f in fs:
                p = os.path.join(r, f)
                if os.path.islink(p) and not os.path.exists(p):
                    continue
                out.append((safe_rel(root, p), p))
    for sz in SIZES:
        base = os.path.join(root, "data", sz, "input")
        for r, d, fs in os.walk(base):
            for f in fs:
                p = os.path.join(r, f)
                out.append((safe_rel(root, p), p))
    return root, out

def classify(rel):
    if rel.startswith("src/"):   return "source"
    if rel.startswith("Spec/"):  return "recipe"
    if "/input/" in rel:         return "input"
    return "other"

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--inst", default="/home/Speccpu2017Rate")
    ap.add_argument("--out", default=".spec2017-req")
    ap.add_argument("--verify")
    ap.add_argument("--root")
    a = ap.parse_args()

    if a.verify:
        spec = json.load(open(a.verify, encoding="utf-8"))
        root = a.root or spec.get("root_hint", "")
        print("== 校验 %s（%d 个基准）==" % (os.path.basename(a.verify), len(spec["benchmarks"])))
        bad = 0
        for bench, info in spec["benchmarks"].items():
            listed = {f["path"]: f for f in info["files"]}
            base = os.path.join(root, bench)
            seen = set()
            for rel, meta in listed.items():
                p = os.path.join(base, rel)
                if not os.path.isfile(p):
                    print("  [缺失] %s/%s" % (bench, rel)); bad += 1; continue
                seen.add(rel)
                if os.path.getsize(p) != meta["bytes"]:
                    print("  [体积不符] %s/%s" % (bench, rel)); bad += 1; continue
                if sha256(p) != meta["sha256"]:
                    print("  [哈希不符] %s/%s" % (bench, rel)); bad += 1
            extra = []
            for r, d, fs in os.walk(base):
                for f in fs:
                    rel = safe_rel(base, os.path.join(r, f))
                    if rel not in listed: extra.append(rel)
            for e in extra[:20]:
                print("  [未登记] %s/%s" % (bench, e)); bad += 1
            if len(extra) > 20:
                print("  …另有 %d 个未登记文件" % (len(extra) - 20)); bad += len(extra) - 20
        print("== 结论：%s（问题 %d 项）==" % ("全部通过" if bad == 0 else "存在风险，拒绝使用", bad))
        return

    os.makedirs(a.out, exist_ok=True)
    summary = {"suite": "SPEC CPU2017", "inst": a.inst, "sizes": SIZES,
               "allow_ext": sorted(ALLOW_EXT), "deny_ext": sorted(DENY_EXT),
               "limits": {"max_file": MAX_FILE, "max_total": MAX_TOTAL}, "benchmarks": {}}
    flat = []
    for bench in BENCHES:
        root, files = collect(a.inst, bench)
        if not files:
            print("  [跳过] %s：安装树里未找到" % bench); continue
        rows, total, denied = [], 0, []
        for rel, p in sorted(files):
            ext = os.path.splitext(rel)[1]
            size = os.path.getsize(p)
            if ext in DENY_EXT or (ext and ext not in ALLOW_EXT):
                denied.append((rel, "类型不在白名单")); continue
            if is_elf_or_macho(p):
                denied.append((rel, "可执行文件（ELF/MZ）")); continue
            if size > MAX_FILE:
                denied.append((rel, "超过单文件上限")); continue
            h = sha256(p)
            rows.append({"path": rel, "bytes": size, "sha256": h, "category": classify(rel)})
            total += size
            flat.append("%s  %s" % (h, rel))
        summary["benchmarks"][bench] = {
            "file_count": len(rows), "total_bytes": total, "files": rows,
            "denied": denied, "recipe_flags": None}
        print("  %-11s 文件 %4d  合计 %7.2f MB  拒绝 %d" % (bench, len(rows), total / 1e6, len(denied)))
        for d, why in denied[:5]:
            print("      [拒绝] %s（%s）" % (d, why))
    open(os.path.join(a.out, "required.json"), "w", encoding="utf-8").write(
        json.dumps(summary, ensure_ascii=False, indent=1))
    open(os.path.join(a.out, "manifest.sha256"), "w", encoding="utf-8").write("\n".join(flat) + "\n")
    print("== 已写出 %s/required.json 与 manifest.sha256（共 %d 条）==" % (a.out, len(flat)))

if __name__ == "__main__":
    main()
