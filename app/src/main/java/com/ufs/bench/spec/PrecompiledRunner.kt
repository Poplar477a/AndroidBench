package com.ufs.bench.spec

import android.content.Context
import java.io.File

/**
 * ============================================================================
 *  预编译组件执行编排（V3 规划 M3.1 / M3.3）
 *
 *  一次调用完成：读配置 → 沙箱执行 → Oracle 判分 → 产出结构化摘要 + 原始输出
 *  原始输出不做任何修饰（结果直出），便于直接比对与留档。
 * ============================================================================
 */
object PrecompiledRunner {

    data class Outcome(
        val summary: SandboxedExecutor.PrecompiledBenchmarkSummary,
        val rawStdout: String,
        val rawStderr: String,
        val oracleDetail: String,
        val config: BundleManager.JobConfig?
    ) {
        fun toText(): String = StringBuilder().apply {
            append("benchmark   : ").append(summary.benchmarkId).append('\n')
            append("binary_sha256: ").append(summary.binarySha256).append('\n')
            append("exit/signal : ").append(summary.exitCode).append(" / ").append(summary.termSignal)
            append(if (summary.seccompKilled) "（被 seccomp 拦截）" else "").append('\n')
            append("wall_seconds: ").append(String.format("%.3f", summary.wallSeconds)).append('\n')
            append("max_rss_kb  : ").append(summary.maxRssKb).append('\n')
            append("oracle      : ").append(if (summary.oracleVerified) "通过" else "未通过/未配置")
            append("  ").append(oracleDetail).append('\n')
            append("--- stdout ---\n").append(rawStdout)
            if (rawStderr.isNotEmpty()) append("\n--- stderr ---\n").append(rawStderr)
        }.toString()
    }


    /**
     * 经 root 助手执行（正确姿势）：
     *   Android 应用进程自带 zygote 的 seccomp 过滤器，**自身无法** unshare/setgroups/setuid，
     *   因此沙箱必须在**独立进程**里以 root 建立，再降权运行目标二进制。
     *   助手二进制随包分发（jniLibs/libbyob_run.so，Android 会以可执行权限解包）。
     */
    private fun runViaSu(ctx: Context, dir: File, cfg: BundleManager.JobConfig, binPath: String): Pair<SandboxedExecutor.RunOutcome, String> {
        val helper = File(ctx.applicationInfo.nativeLibraryDir, "libbyob_run.so")
        if (!helper.isFile) return Pair(SandboxedExecutor.RunOutcome(false, -1, 0, false, 0.0, 0,
            "未找到沙箱助手：" + helper.absolutePath, "", ""), "")
        val args = cfg.args.map { a ->
            val f = File(dir, a)
            if (a.startsWith("data/") && f.isFile) f.absolutePath else a
        }
        val inner = "BYOB_WORKDIR=" + dir.absolutePath + " BYOB_WALL=" + cfg.maxSeconds +
            " " + helper.absolutePath + " " + binPath + " " + args.joinToString(" ")
        val t0 = System.currentTimeMillis()
        return try {
            val p = ProcessBuilder("su", "-c", inner).directory(dir).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            val finished = p.waitFor(cfg.maxSeconds + 60L, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) p.destroyForcibly()
            val secs = (System.currentTimeMillis() - t0) / 1000.0
            val m = Regex("RESULT started=(\\d+) netns=(\\d+) pin_cpus=(\\d+) temp_before=([0-9.]+) cooldown_wait=([0-9.]+) exit=(-?\\d+) signal=(\\d+) seccomp_killed=(\\d+) seconds=([0-9.]+) rss_kb=(\\d+)").find(text)
            if (m == null) {
                Pair(SandboxedExecutor.RunOutcome(false, -1, 0, false, secs, 0, "助手未返回结果（su 是否可用？）", text, ""), text)
            } else {
                val g = m.groupValues
                val out = text.substringAfter("--- stdout ---", "").substringBefore("--- stderr ---").trim()
                val err = text.substringAfter("--- stderr ---", "").trim()
                Pair(SandboxedExecutor.RunOutcome(g[1] == "1", g[6].toInt(), g[7].toInt(), g[8] == "1", g[9].toDouble(), g[10].toLong(), "", out, err), text)
            }
        } catch (t: Throwable) {
            Pair(SandboxedExecutor.RunOutcome(false, -1, 0, false, 0.0, 0, "su 调用失败：" + (t.message ?: "?"), "", ""), "")
        }
    }

    fun run(ctx: Context, benchmarkId: String, expectedBundleSha256: String? = null): Outcome {
        val dir = File(BundleManager.bundlesDir(ctx), benchmarkId)
        val runnerJson = File(dir, "runner.json")
        if (!runnerJson.isFile) {
            val empty = SandboxedExecutor.PrecompiledBenchmarkSummary(
                benchmarkId, "", -1, 0, false, 0.0, 0L, false,
                "组件包未导入或缺少 runner.json：" + runnerJson.absolutePath,
                System.currentTimeMillis(), "")
            return Outcome(empty, "", "", "未执行", null)
        }
        // 复核组件包完整性（哈希在导入时已校验，这里再核一次，避免磁盘被替换）
        val bin = File(dir, "runner.json").readText().let { txt ->
            val m = Regex("\"binary_path\"\\s*:\\s*\"([^\"]+)\"").find(txt)
            m?.groupValues?.get(1)?.let { File(dir, it) }
        }
        val binSha = if (bin != null && bin.isFile) BundleManager.sha256(bin) else ""
        val elfCheck = if (bin != null && bin.isFile) ElfValidator.validate(bin)
                       else ElfValidator.Result(false, 0, false, emptyList(), listOf("missing"), "二进制缺失")
        if (!elfCheck.ok) {
            val s = SandboxedExecutor.PrecompiledBenchmarkSummary(
                benchmarkId, binSha, -1, 0, false, 0.0, 0L, false,
                "准入核验未通过：" + elfCheck.message, System.currentTimeMillis(), "")
            return Outcome(s, "", "", elfCheck.message, null)
        }
        val cfg = BundleManager.parseRunnerJson(runnerJson.readText())
            ?: return Outcome(SandboxedExecutor.PrecompiledBenchmarkSummary(
                benchmarkId, binSha, -1, 0, false, 0.0, 0L, false,
                "runner.json 解析失败", System.currentTimeMillis(), ""), "", "", "解析失败", null)

        val started = System.currentTimeMillis()
        val (outcome, _raw) = runViaSu(ctx, dir, cfg, bin!!.absolutePath)
        // Oracle 判分：参考输出在包内（runner.json 指定）
        val ref = cfg.oracleReference?.let { File(dir, it) }
        val outFile = File(dir, cfg.stdoutFile ?: (cfg.benchmarkId + ".out"))
        if (outcome.stdout.isNotEmpty() && !outFile.isFile) outFile.writeText(outcome.stdout)
        val oracle = OracleVerifier.verify(
            outFile, ref, cfg.oracleType, cfg.absTolerance, cfg.relTolerance,
            ignorePatterns = cfg.ignoreLinePatterns
        )

        val summary = SandboxedExecutor.PrecompiledBenchmarkSummary(
            benchmarkId = benchmarkId,
            binarySha256 = binSha,
            exitCode = outcome.exitCode,
            termSignal = outcome.termSignal,
            seccompKilled = outcome.seccompKilled,
            wallSeconds = outcome.seconds,
            maxRssKb = outcome.maxRssKb,
            oracleVerified = oracle.verified,
            oracleDetail = oracle.detail,
            startedAtEpochMs = started,
            deviceThermalNote = ""
        )
        return Outcome(summary, outcome.stdout, outcome.stderr, oracle.detail, cfg)
    }
}
