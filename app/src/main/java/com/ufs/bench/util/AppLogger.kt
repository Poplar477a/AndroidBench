package com.ufs.bench.util

import android.content.Context
import android.util.Log
import com.ufs.bench.BuildConfig
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用级日志记录器。
 * - 每三天自动清理一次历史日志（保留 3 天以内的日志）
 * - Debug 模式：日志级别为 DEBUG（全量输出），所有高级选项解锁
 * - Release 模式：日志级别为 INFO，抑制调试细节并保持安全门禁
 */
object AppLogger {

    private const val TAG = "UFSBench"
    private const val RETENTION_MS = 3 * 24 * 60 * 60 * 1000L // 3 天自动清除
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private var logDir: File? = null
    val isDebug: Boolean get() = BuildConfig.DEBUG

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        logDir = dir
        cleanOldLogs()
        i(TAG, "AppLogger 初始化完成（Debug=$isDebug，日志保留 3 天）")
    }

    /** 每三天自动清除一次历史日志（保留 3 天以内的日志文件） */
    fun cleanOldLogs() {
        val dir = logDir ?: return
        try {
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { file ->
                if (file.isFile && (now - file.lastModified() > RETENTION_MS)) {
                    file.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    fun d(tag: String = TAG, message: String) {
        if (!isDebug) return // Release 模式抑制 DEBUG 级别
        Log.d(tag, message)
        write("DEBUG", tag, message)
    }

    fun i(tag: String = TAG, message: String) {
        Log.i(tag, message)
        write("INFO", tag, message)
    }

    fun w(tag: String = TAG, message: String, tr: Throwable? = null) {
        if (tr != null) Log.w(tag, message, tr) else Log.w(tag, message)
        val fullMsg = if (tr != null) "$message: ${tr.message}" else message
        write("WARN", tag, fullMsg)
    }

    fun e(tag: String = TAG, message: String, tr: Throwable? = null) {
        if (tr != null) Log.e(tag, message, tr) else Log.e(tag, message)
        val fullMsg = if (tr != null) "$message: ${tr.message}" else message
        write("ERROR", tag, fullMsg)
    }

    private fun write(level: String, tag: String, message: String) {
        val dir = logDir ?: return
        try {
            val today = fileDateFormat.format(Date())
            val file = File(dir, "bench_$today.log")
            val time = dateFormat.format(Date())
            val line = "[$time] [$level] [$tag] $message\n"
            FileWriter(file, true).use { it.write(line) }
        } catch (_: Exception) {
        }
    }

    /** 读取最近日志（最多 200 行） */
    fun getRecentLogs(maxLines: Int = 200): List<String> {
        val dir = logDir ?: return emptyList()
        return try {
            val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
            val lines = mutableListOf<String>()
            for (f in files) {
                val fileLines = f.readLines()
                lines.addAll(fileLines)
                if (lines.size >= maxLines) break
            }
            lines.takeLast(maxLines)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
