package com.androidbench.guard

import com.androidbench.core.BenchmarkExecutionStatus
import com.androidbench.core.BenchmarkId
import java.io.File

/**
 * 崩溃与重启恢复记录
 */
data class InterruptedTaskRecord(
    val benchmarkId: BenchmarkId,
    val lastStage: ExecutionStage,
    val startedTimestampMs: Long,
    val lastHeartbeatMs: Long,
    val details: String,
    val isRecognizedAsCrashOrLmk: Boolean
)

/**
 * 对应第 14.7 节：持久化执行状态日志与进程被杀恢复识别器
 */
class CrashRecoveryJournal(
    private val journalDir: File
) {
    private val activeTaskFile = File(journalDir, "active_benchmark_task.journal")
    private val crashLogFile = File(journalDir, "crash_recovery_history.log")

    init {
        if (!journalDir.exists()) {
            journalDir.mkdirs()
        }
    }

    /**
     * 记录新测试启动
     */
    fun startTask(benchmarkId: BenchmarkId, initialDetails: String = "") {
        val payload = "${benchmarkId.key}|${ExecutionStage.INIT.name}|${System.currentTimeMillis()}|${System.currentTimeMillis()}|$initialDetails"
        activeTaskFile.writeText(payload)
    }

    /**
     * 更新阶段检查点与心跳
     */
    fun recordStage(benchmarkId: BenchmarkId, stage: ExecutionStage, detail: String = "") {
        val lines = if (activeTaskFile.exists()) activeTaskFile.readText().split("|") else emptyList()
        val startTs = if (lines.size >= 3) lines[2] else System.currentTimeMillis().toString()
        val payload = "${benchmarkId.key}|${stage.name}|$startTs|${System.currentTimeMillis()}|$detail"
        activeTaskFile.writeText(payload)
    }

    /**
     * 测试安全正常结束 (完成或正常捕获处理)，清除活动状态
     */
    fun finishTask() {
        if (activeTaskFile.exists()) {
            activeTaskFile.delete()
        }
    }

    /**
     * 应用重新启动时执行：识别未完成的任务 (被 LMK 强杀或异常崩溃)
     * @return 若存在被异常杀死的任务，返回识别记录；否则返回 null
     */
    fun checkAndRecoverInterruptedTask(): InterruptedTaskRecord? {
        if (!activeTaskFile.exists()) {
            return null
        }

        try {
            val content = activeTaskFile.readText().trim()
            val parts = content.split("|")
            if (parts.size >= 4) {
                val bIdKey = parts[0]
                val stageName = parts[1]
                val startTs = parts[2].toLongOrNull() ?: 0L
                val heartbeatTs = parts[3].toLongOrNull() ?: 0L
                val detail = if (parts.size >= 5) parts[4] else ""

                val benchmarkId = BenchmarkId.entries.firstOrNull { it.key == bIdKey } ?: BenchmarkId.COREMARK
                val stage = ExecutionStage.entries.firstOrNull { it.name == stageName } ?: ExecutionStage.KERNEL_EXECUTION

                val record = InterruptedTaskRecord(
                    benchmarkId = benchmarkId,
                    lastStage = stage,
                    startedTimestampMs = startTs,
                    lastHeartbeatMs = heartbeatTs,
                    details = "Detected crash or LMK termination during stage $stage. Details: $detail",
                    isRecognizedAsCrashOrLmk = true
                )

                // 归档至崩溃日志
                crashLogFile.appendText("${System.currentTimeMillis()} [LMK/CRASH RECOVERED] ${record.details}\n")

                // 清理活动标记，防止重复报警
                activeTaskFile.delete()

                return record
            }
        } catch (e: Exception) {
            activeTaskFile.delete()
        }
        return null
    }
}
