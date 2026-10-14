package com.ufs.bench.spec

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * ============================================================================
 *  用户自带源码工作区（合规设计）
 *
 *  合规底线：
 *   · 本 App 不内置任何第三方基准源码；源码由使用者自行放入设备目录，
 *     并附 manifest.sha256 供校验（校验不通过一律拒绝编译/运行）。
 *   · 测试结果只能表述为「内部相对基线」，不得称为任何官方成绩。
 *
 *  目录约定（外置私有目录，用户可直接用数据线/文件管理器放入）：
 *     <外部私有目录>/spec/
 *        manifest.sha256        每行：<64 位 sha256>  <相对路径>
 *        src/<名称>/            该测试项目的源码树（用户提供）
 *        inputs/<名称>/         运行输入文件（含参数文件）
 *        toolchain/cc           用户提供的设备端编译器（chmod +x）
 *
 *  Android W^X：应用不能执行外置存储上的文件，因此编译产物与运行一律在
 *  内置私有目录（filesDir）内进行 —— 源码从外置目录复制进来再编译。
 * ============================================================================
 */
object SpecWorkspace {

    const val APP_DIR = "spec"

    fun workspaceDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), APP_DIR).apply { mkdirs() }

    fun execDir(ctx: Context): File =
        File(ctx.filesDir, APP_DIR).apply { mkdirs() }

    data class HashRow(val path: String, val expected: String, val actual: String) {
        val ok: Boolean get() = expected.equals(actual, ignoreCase = true)
    }

    data class VerifyResult(
        val manifestFound: Boolean,
        val rows: List<HashRow>,
        val untracked: List<String>,
        val message: String
    ) {
        val allOk: Boolean
            get() = manifestFound && rows.isNotEmpty() && rows.all { it.ok } && untracked.isEmpty()
    }

// ==========================================================================
    //  安全校验（需求：校验避免恶意文件或乱传）
    //   1) 类型白名单：只接受源码/头/构建配方/输入数据；
    //   2) 拒绝可执行文件（ELF / MZ 头，扩展名 .so/.a/.o/.exe/.dll/.bat/.ps1/.apk/.dex/.jar）；
    //   3) 拒绝路径穿越（清单里含 .. 或绝对路径的条目、指向树外的符号链接）；
    //   4) 体积上限：单文件 256 MB、单基准 2 GB；
    //   5) 清单外文件一律判为未登记（不静默忽略）。
    // ==========================================================================
    private val ALLOW_EXT = setOf(
        ".hpp", ".h", ".c", ".cc", ".def", ".in", ".inst", ".C", ".cpp", ".xml", ".pm",
        ".sh", ".xz", ".inl", ".of", ".input", ".prm", ".inc", ".tar", ".gz", ".txt", ".dat", ".f", ".F"
    )
    private val DENY_EXT = setOf(
        ".so", ".a", ".o", ".exe", ".dll", ".bat", ".cmd", ".ps1", ".apk", ".dex", ".jar", ".bin"
    )
    private const val MAX_FILE_BYTES = 256L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024

    data class SecurityIssue(val path: String, val reason: String)

    /** 对单个文件做类型/内容/体积检查；返回 null 表示通过 */
    fun checkFile(root: File, f: File): SecurityIssue? {
        val rel = try {
            f.canonicalFile.relativeTo(root.canonicalFile).path.replace('\\', '/')
        } catch (e: Exception) {
            return SecurityIssue(f.name, "无法解析路径")
        }
        if (rel.startsWith("..") || rel.startsWith("/")) return SecurityIssue(rel, "路径穿越")
        val lower = rel.lowercase()
        val ext = lower.substringAfterLast('.', "")
        val dotExt = if (ext.isEmpty()) "" else "." + ext
        if (dotExt in DENY_EXT) return SecurityIssue(rel, "禁止的扩展名")
        if (dotExt.isNotEmpty() && dotExt !in ALLOW_EXT) return SecurityIssue(rel, "扩展名不在白名单")
        if (f.length() > MAX_FILE_BYTES) return SecurityIssue(rel, "超过单文件上限")
        return try {
            val magic = ByteArray(4)
            f.inputStream().use { it.read(magic) }
            val isElf = magic[0] == 0x7F.toByte() && magic[1] == 'E'.code.toByte() &&
                magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
            val isMz = magic[0] == 'M'.code.toByte() && magic[1] == 'Z'.code.toByte()
            if (isElf || isMz) SecurityIssue(rel, "可执行文件（ELF/MZ）") else null
        } catch (e: Exception) {
            SecurityIssue(rel, "读取失败")
        }
    }

    /** 汇总安全校验：返回问题清单与总体积 */
    fun securityScan(root: File): Pair<List<SecurityIssue>, Long> {
        val issues = ArrayList<SecurityIssue>()
        var total = 0L
        if (!root.isDirectory) return Pair(issues, 0L)
        for (f in root.walkTopDown()) {
            if (!f.isFile) continue
            total += f.length()
            val issue = checkFile(root, f)
            if (issue != null) issues.add(issue)
            if (total > MAX_TOTAL_BYTES) {
                issues.add(SecurityIssue("(整体)", "超过单基准总量上限"))
                break
            }
        }
        return Pair(issues, total)
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { String.format("%02x", it) }
    }

    fun verify(ctx: Context, name: String): VerifyResult {
        val root = File(workspaceDir(ctx), "src/" + name)
        val manifest = File(workspaceDir(ctx), "manifest.sha256")
        if (!manifest.isFile) {
            return VerifyResult(false, emptyList(), emptyList(),
                "未找到 manifest.sha256：请把「<sha256>  <相对路径>」逐行写入 " + manifest.absolutePath)
        }
        if (!root.isDirectory) {
            return VerifyResult(true, emptyList(), emptyList(), "未找到源码目录：" + root.absolutePath)
        }
        val expected = LinkedHashMap<String, String>()
        manifest.readLines().forEach { line ->
            val t = line.trim()
            if (t.isNotEmpty() && !t.startsWith("#")) {
                val parts = t.split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) expected[parts[1].trim()] = parts[0].trim()
            }
        }
        val rows = ArrayList<HashRow>()
        val seen = HashSet<String>()
        for ((rel, exp) in expected) {
            val f = File(root, rel)
            if (!f.isFile) {
                rows.add(HashRow(rel, exp, "(缺失)"))
            } else {
                seen.add(rel)
                rows.add(HashRow(rel, exp, sha256(f)))
            }
        }
        val (secIssues, totalBytes) = securityScan(root)
        val untracked = root.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).path.replace('\\', '/') }
            .filter { !seen.contains(it) && !expected.containsKey(it) }
            .take(50).toList()
        val bad = rows.count { !it.ok }
        val msg = StringBuilder().apply {
            append("清单条目 ").append(expected.size).append("，不符 ").append(bad)
            if (untracked.isNotEmpty()) append("，未登记文件 ").append(untracked.size)
            if (bad == 0 && untracked.isEmpty()) append("：全部一致")
        }.toString()
        val secMsg = if (secIssues.isEmpty()) "" else "；安全校验问题 " + secIssues.size + " 项（首个：" +
            secIssues[0].path + " -> " + secIssues[0].reason + "）"
        return VerifyResult(true, rows, untracked, msg + secMsg + "；总体积 " + (totalBytes / 1048576) + " MB")
    }

    data class CmdResult(
        val ok: Boolean, val code: Int, val cmdLine: String,
        val out: String, val err: String, val seconds: Double
    )

    fun compilerPath(ctx: Context, override: String?): File? {
        if (override != null && override.isNotBlank()) {
            val f = File(override)
            if (f.canExecute()) return f
        }
        val f = File(workspaceDir(ctx), "toolchain/cc")
        return if (f.canExecute()) f else null
    }

    fun sourceFiles(dir: File): List<File> =
        dir.walkTopDown().filter {
            it.isFile && (it.extension == "c" || it.extension == "cc" || it.extension == "cpp")
        }.sorted().toList()

    fun compile(ctx: Context, name: String, compilerOverride: String?, flags: String): CmdResult {
        val v = verify(ctx, name)
        if (!v.allOk) {
            return CmdResult(false, -1, "", "", "拒绝编译：源码校验未通过（" + v.message + "）", 0.0)
        }
        val cc = compilerPath(ctx, compilerOverride)
        if (cc == null) {
            val want = File(workspaceDir(ctx), "toolchain/cc").absolutePath
            return CmdResult(false, -1, "", "",
                "未找到设备端编译器：请把可执行编译器放到 " + want + "（chmod +x），或在设置中填绝对路径", 0.0)
        }
        val src = File(workspaceDir(ctx), "src/" + name)
        val work = File(execDir(ctx), "build/" + name)
        work.deleteRecursively(); work.mkdirs()
        src.copyRecursively(work, overwrite = true)
        val sources = sourceFiles(work)
        if (sources.isEmpty()) {
            return CmdResult(false, -1, "", "", "源码目录内没有 .c/.cc/.cpp 文件", 0.0)
        }
        val bin = File(work, "a.out")
        val cmd = ArrayList<String>()
        cmd.add(cc.absolutePath)
        cmd.addAll(splitFlags(flags))
        sources.forEach { cmd.add(it.absolutePath) }
        cmd.add("-o"); cmd.add(bin.absolutePath)
        return exec(cmd, work, 900)
    }

    fun run(ctx: Context, name: String, args: List<String>, timeoutSec: Long): CmdResult {
        val work = File(execDir(ctx), "build/" + name)
        val bin = File(work, "a.out")
        if (!bin.isFile) return CmdResult(false, -1, "", "", "尚未编译：" + bin.absolutePath + " 不存在", 0.0)
        val cmd = ArrayList<String>()
        cmd.add(bin.absolutePath)
        cmd.addAll(args)
        return exec(cmd, work, timeoutSec)
    }

    fun splitFlags(s: String): List<String> =
        s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun exec(cmd: List<String>, dir: File, timeoutSec: Long): CmdResult {
        val t0 = System.nanoTime()
        val line = cmd.joinToString(" ")
        return try {
            val p = ProcessBuilder(cmd).directory(dir).redirectErrorStream(false).start()
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val done = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            val secs = (System.nanoTime() - t0) / 1e9
            if (!done) {
                p.destroyForcibly()
                CmdResult(false, -2, line, out, err + "\n[超时 " + timeoutSec + "s，已终止]", secs)
            } else {
                CmdResult(p.exitValue() == 0, p.exitValue(), line, out, err, secs)
            }
        } catch (e: Exception) {
            CmdResult(false, -3, line, "", "无法执行：" + (e.message ?: "?"), (System.nanoTime() - t0) / 1e9)
        }
    }
}
