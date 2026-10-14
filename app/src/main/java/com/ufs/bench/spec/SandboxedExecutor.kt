package com.ufs.bench.spec

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * ============================================================================
 *  沙箱执行器（V3 规划 M1.3 / M3.1 / M3.3）
 *  · 外部二进制一律经 nativeRunIsolated 执行：断网 + 降权 + 清能力 + NO_NEW_PRIVS + seccomp 白名单 + rlimit；
 *  · 返回结果如实回传（退出码/信号/是否被 seccomp 秒杀/墙钟/峰值内存/输出），绝不修饰；
 *  · 结果模型 PrecompiledBenchmarkSummary 记录二进制 SHA-256、耗时、热状态与比对状态。
 * ============================================================================
 */
object SandboxedExecutor {

    init {
        System.loadLibrary("dynamo_engine_baseline")
    }

    data class RunOutcome(
        val started: Boolean,
        val exitCode: Int,
        val termSignal: Int,
        val seccompKilled: Boolean,
        val seconds: Double,
        val maxRssKb: Long,
        val error: String,
        val stdout: String,
        val stderr: String
    ) {
        val ok: Boolean get() = started && exitCode == 0 && termSignal == 0 && error.isEmpty()
    }

    /** M3.3：预编译组件的测试摘要（与内置基准的摘要分开，避免混淆口径） */
    data class PrecompiledBenchmarkSummary(
        val benchmarkId: String,
        val binarySha256: String,
        val exitCode: Int,
        val termSignal: Int,
        val seccompKilled: Boolean,
        val wallSeconds: Double,
        val maxRssKb: Long,
        val oracleVerified: Boolean,
        val oracleDetail: String,
        val startedAtEpochMs: Long,
        val deviceThermalNote: String
    )

    private external fun nativeRunIsolated(
        exe: String, args: Array<String>, workdir: String,
        memBytes: Long, fileBytes: Long, cpuSeconds: Int, wallSeconds: Int,
        dropUid: Int, dropGid: Int
    ): String

    fun run(
        ctx: Context,
        cfg: BundleManager.JobConfig,
        limits: SandboxedRunnerLimits = SandboxedRunnerLimits()
    ): RunOutcome {
        val dir = File(BundleManager.bundlesDir(ctx), cfg.benchmarkId)
        val bin = File(dir, cfg.binaryPath)
        if (!bin.isFile) {
            return RunOutcome(false, -1, 0, false, 0.0, 0, "组件包不存在：" + bin.absolutePath, "", "")
        }
        // 解析后的输入路径：把 runner.json 里的相对参数映射到包内实际文件
        val args = cfg.args.map { a ->
            val f = File(dir, a)
            if (a.startsWith("data/") && f.isFile) f.absolutePath else a
        }.toTypedArray()
        val json = try {
            nativeRunIsolated(bin.absolutePath, args, dir.absolutePath,
                limits.memBytes, limits.fileBytes, limits.cpuSeconds, cfg.maxSeconds,
                limits.dropUid, limits.dropGid)
        } catch (e: Throwable) {
            return RunOutcome(false, -1, 0, false, 0.0, 0, "沙箱调用失败：" + (e.message ?: "?"), "", "")
        }
        return try {
            val o = JSONObject(json)
            RunOutcome(
                started = o.optBoolean("started", false),
                exitCode = o.optInt("exit_code", -1),
                termSignal = o.optInt("term_signal", 0),
                seccompKilled = o.optBoolean("seccomp_killed", false),
                seconds = o.optDouble("seconds", 0.0),
                maxRssKb = o.optLong("max_rss_kb", 0L),
                error = o.optString("error", ""),
                stdout = o.optString("stdout", ""),
                stderr = o.optString("stderr", "")
            )
        } catch (e: Exception) {
            RunOutcome(false, -1, 0, false, 0.0, 0, "沙箱返回解析失败：" + json.take(200), "", "")
        }
    }

    data class SandboxedRunnerLimits(
        val memBytes: Long = 2L * 1024 * 1024 * 1024,
        val fileBytes: Long = 100L * 1024 * 1024,
        val cpuSeconds: Int = 600,
        /** 降到非特权身份：Android 应用 uid 区间（u0_aXXX >= 10000） */
        val dropUid: Int = 10123,
        val dropGid: Int = 10123
    )
}
