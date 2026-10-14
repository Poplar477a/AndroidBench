package com.ufs.bench.spec

/**
 * BYOB 组件包管理（V3 规划 M2.1 / M2.3）
 *
 * 交付包格式（PC 端交叉编译后打包）：
 *   <benchmark_id>_bundle.zip
 *     runner.json          运行时元数据与裁判描述符（必需）
 *     bin/<exe>            纯 arm64 ELF（必需）
 *     data/input/…         输入数据集（必需）
 *     data/output/…        官方参考输出（用于 Oracle 判分）
 *     meta/…               构建日志、编译器标识（可选）
 *
 * 安全约束（与宿主端 required_files.py 一致）：
 *   路径穿越（.. 或绝对路径）一律拒绝；解包先落 staging，
 *   SHA-256 与 ELF 准入核验全部通过后才就位；脚本、非 arm64、
 *   非白名单依赖直接拒绝；任何一步失败都不留下可执行产物（失败关闭）。
 */
import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object BundleManager {

    const val MAX_ENTRIES = 20000
    const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024

    data class JobConfig(
        val benchmarkId: String,
        val binaryPath: String,
        val args: List<String>,
        val stdoutFile: String?,
        val oracleType: String,
        val oracleReference: String?,
        val absTolerance: Double,
        val relTolerance: Double,
        /** 判分时忽略的行模式（SPEC 类基准的时间/分配行） */
        val ignoreLinePatterns: List<String> = emptyList(),
        val affinityPolicy: String,
        val cooldownCelsius: Double,
        val maxSeconds: Int
    )

    data class ImportResult(
        val ok: Boolean,
        val config: JobConfig?,
        val files: Int,
        val totalBytes: Long,
        val binarySha256: String,
        val needed: List<String>,
        val rejected: List<String>,
        val message: String
    )

    fun bundlesDir(ctx: Context): File = File(ctx.filesDir, "byob").apply { mkdirs() }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf); if (n <= 0) break; md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { String.format("%02x", it) }
    }

    private fun safeName(name: String): Boolean {
        val n = name.replace('\\', '/')
        if (n.startsWith("/") || n.contains("..")) return false
        if (n.contains(":")) return false
        return true
    }

    /** 解包 + 校验 + 就位。zipFile 由调用方通过 SAF 复制到缓存目录后传入。 */
    fun importBundle(ctx: Context, zipFile: File, expectedSha256: String?): ImportResult {
        val rejected = ArrayList<String>()
        if (zipFile.length() > MAX_TOTAL_BYTES) {
            return ImportResult(false, null, 0, 0, "", emptyList(), listOf("zip-too-big"), "拒绝：压缩包超过 2 GB")
        }
        if (expectedSha256 != null && expectedSha256.isNotBlank()) {
            val actual = sha256(zipFile)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                return ImportResult(false, null, 0, 0, "", emptyList(), listOf("sha256-mismatch"),
                    "拒绝：压缩包 SHA-256 不符（期望 " + expectedSha256.take(16) + "…，实际 " + actual.take(16) + "…）")
            }
        }
        val staging = File(ctx.cacheDir, "byob-staging").apply { deleteRecursively(); mkdirs() }
        var entries = 0
        var total = 0L
        ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                entries++
                if (entries > MAX_ENTRIES) {
                    return ImportResult(false, null, entries, total, "", emptyList(), listOf("too-many-entries"),
                        "拒绝：压缩包条目过多")
                }
                if (!safeName(e.name)) {
                    return ImportResult(false, null, entries, total, "", emptyList(), listOf("traversal:" + e.name),
                        "拒绝：压缩包内存在路径穿越 -> " + e.name)
                }
                val out = File(staging, e.name)
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                out.outputStream().use { os -> zin.copyTo(os) }
                total += out.length()
                if (total > MAX_TOTAL_BYTES) {
                    return ImportResult(false, null, entries, total, "", emptyList(), listOf("total-too-big"),
                        "拒绝：解包后超过 2 GB")
                }
                // 脚本一律拒绝
                if (out.length() >= 2) {
                    val h = ByteArray(2); out.inputStream().use { it.read(h) }
                    if (h[0] == '#'.code.toByte() && h[1] == '!'.code.toByte()) {
                        return ImportResult(false, null, entries, total, "", emptyList(), listOf("script:" + e.name),
                            "拒绝：包内含脚本 -> " + e.name)
                    }
                }
            }
        }
        val runner = File(staging, "runner.json")
        if (!runner.isFile) {
            return ImportResult(false, null, entries, total, "", emptyList(), listOf("no-runner-json"),
                "拒绝：缺少 runner.json")
        }
        val cfg = parseRunnerJson(runner.readText()) ?: return ImportResult(false, null, entries, total, "",
            emptyList(), listOf("bad-runner-json"), "拒绝：runner.json 解析失败或字段不合法")
        val bin = File(staging, cfg.binaryPath)
        if (!bin.isFile) {
            return ImportResult(false, null, entries, total, "", emptyList(), listOf("no-binary"),
                "拒绝：runner.json 指向的二进制不存在 -> " + cfg.binaryPath)
        }
        val elf = ElfValidator.validate(bin)
        if (!elf.ok) {
            return ImportResult(false, null, entries, total, "", elf.needed, elf.rejected,
                "拒绝：ELF 准入核验未通过（" + elf.message + "）")
        }
        // 全部通过 → 就位（原子替换）
        val dst = File(bundlesDir(ctx), cfg.benchmarkId)
        dst.deleteRecursively()
        staging.copyRecursively(dst, overwrite = true)
        staging.deleteRecursively()
        val placed = File(dst, cfg.binaryPath)
        placed.setExecutable(true, true)
        return ImportResult(true, cfg, entries, total, sha256(placed), elf.needed, rejected,
            "通过：已就位 " + dst.absolutePath + "，二进制 " + elf.message)
    }

    /**
     * 裸 ELF 导入：把用户自备的 arm64 可执行文件直接打成**最小组件包**。
     *
     * 适用：不想写 runner.json 的场景（无命令行参数、不做参考输出判分），
     *       例如用户自己写的微基准 / 从别处拿到的单文件可执行程序。
     * 不适用：需要传参、需要与官方参考输出比对判分的场景 —— 那要用 .zip 组件包，
     *       规范见终端 'spec' 命令与 docs/byob-打包规范.md。
     */
    fun importElf(ctx: Context, elf: File, expectedSha256: String?): ImportResult {
        if (expectedSha256 != null && !sha256(elf).equals(expectedSha256, ignoreCase = true)) {
            return ImportResult(false, null, 1, elf.length(), "", emptyList(), listOf("sha256-mismatch"),
                "拒绝：SHA-256 与预期不符")
        }
        if (elf.length() <= 0) {
            return ImportResult(false, null, 1, 0, "", emptyList(), listOf("empty"), "拒绝：文件为空")
        }
        // benchmark_id 由文件名推导并做白名单化（目录名/命令行都会用到，不能带路径分隔符）
        val id = elf.name.substringBeforeLast('.').ifBlank { "custom_elf" }
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val staging = File(ctx.cacheDir, "byob-elf-staging").apply { deleteRecursively(); mkdirs() }
        val binDir = File(staging, "bin").apply { mkdirs() }
        val placed = File(binDir, id)
        elf.copyTo(placed, overwrite = true)
        placed.setExecutable(true, false)
        val elfCheck = ElfValidator.validate(placed)
        if (!elfCheck.ok) {
            staging.deleteRecursively()
            return ImportResult(false, null, 1, placed.length(), "", elfCheck.needed, elfCheck.rejected,
                "拒绝：ELF 准入核验未通过（" + elfCheck.message + "）")
        }
        val cfg = JobConfig(
            benchmarkId = id,
            binaryPath = "bin/" + id,
            args = emptyList(),
            stdoutFile = id + ".out",
            oracleType = "none",
            oracleReference = null,
            absTolerance = 1e-7,
            relTolerance = 1e-4,
            ignoreLinePatterns = emptyList(),
            affinityPolicy = "none",
            cooldownCelsius = 0.0,
            maxSeconds = 600
        )
        // 用 JSONObject 组装（手写字符串极易因转义/换行写坏 —— 2026-10-14 就踩过）
        val json = org.json.JSONObject().apply {
            put("benchmark_id", cfg.benchmarkId)
            put("binary_path", cfg.binaryPath)
            put("command_args", org.json.JSONArray())
            put("stdout_file", cfg.stdoutFile)
            put("oracle_rule", org.json.JSONObject().put("type", "none"))
            put("execution_profile", org.json.JSONObject()
                .put("affinity_policy", "none")
                .put("cooldown_target_celsius", 0.0)
                .put("max_execution_seconds", cfg.maxSeconds))
        }.toString(2)
        File(staging, "runner.json").writeText(json + '\n')
        val dst = File(bundlesDir(ctx), cfg.benchmarkId)
        dst.deleteRecursively()
        staging.copyRecursively(dst, overwrite = true)
        staging.deleteRecursively()
        val finalBin = File(dst, cfg.binaryPath)
        finalBin.setExecutable(true, true)
        val files = dst.walkTopDown().count { it.isFile }
        return ImportResult(true, cfg, files, finalBin.length(), sha256(finalBin), elfCheck.needed, emptyList(),
            "通过：已按裸 ELF 打成最小组件包（无参数、不判分）→ " + dst.absolutePath)
    }

    internal fun parseRunnerJson(text: String): JobConfig? {
        return try {
            val o = org.json.JSONObject(text)
            val id = o.optString("benchmark_id", "")
            val bin = o.optString("binary_path", "")
            if (id.isEmpty() || bin.isEmpty()) return null
            val argsArr = o.optJSONArray("command_args")
            val args = ArrayList<String>()
            if (argsArr != null) for (i in 0 until argsArr.length()) args.add(argsArr.getString(i))
            val oc = o.optJSONObject("oracle_rule")
            val ignoreArr = oc?.optJSONArray("ignore_line_patterns")
            val ignore = ArrayList<String>()
            if (ignoreArr != null) for (i in 0 until ignoreArr.length()) ignore.add(ignoreArr.getString(i))
            val ep = o.optJSONObject("execution_profile")
            JobConfig(
                benchmarkId = id,
                binaryPath = bin,
                args = args,
                stdoutFile = o.optString("stdout_file", "").ifEmpty { null },
                oracleType = oc?.optString("type", "none") ?: "none",
                oracleReference = oc?.optString("reference_file", "")?.ifEmpty { null },
                absTolerance = oc?.optDouble("abs_tolerance", 1e-7) ?: 1e-7,
                relTolerance = oc?.optDouble("rel_tolerance", 1e-4) ?: 1e-4,
                ignoreLinePatterns = ignore,
                affinityPolicy = ep?.optString("affinity_policy", "none") ?: "none",
                cooldownCelsius = ep?.optDouble("cooldown_target_celsius", 0.0) ?: 0.0,
                maxSeconds = ep?.optInt("max_execution_seconds", 600) ?: 600
            )
        } catch (e: Exception) {
            null
        }
    }
}
