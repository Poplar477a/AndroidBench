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
                affinityPolicy = ep?.optString("affinity_policy", "none") ?: "none",
                cooldownCelsius = ep?.optDouble("cooldown_target_celsius", 0.0) ?: 0.0,
                maxSeconds = ep?.optInt("max_execution_seconds", 600) ?: 600
            )
        } catch (e: Exception) {
            null
        }
    }
}
