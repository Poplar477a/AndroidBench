package com.ufs.bench.spec

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * ============================================================================
 *  BYOB 终端引擎（终端样式，纯文本直出，不做任何修饰）
 *
 *  可用命令：
 *    help              显示帮助
 *    list              列出已导入的组件包（含 bin / runner.json / 大小）
 *    verify <id>       ELF 准入核验 + 二进制 SHA-256 + 依赖白名单
 *    run <id>          沙箱执行（断网/降权/能力清零/seccomp/rlimit/墙钟）+ Oracle 判分
 *    cat <id>          显示上次执行结果文件
 *    import            打开系统文件选择器，导入 .zip 组件包（校验后落入私有目录）
 *    log on|off        开关应用日志流（默认开：平时就把日志打在终端里）
 *    clear             清屏

 *  日志流：默认开启，轮询本进程 logcat（仅本应用日志），新行实时追加到终端。 *
 *  输出一律为原始文本行；执行结果与 PrecompiledRunner 的 toText() 完全一致。
 * ============================================================================
 */
object ByobTerminal {

    private val _lines = MutableStateFlow<List<String>>(listOf(
        "UFS Benchmark BYOB 终端（实验版）",
        "输入 help 查看命令。组件包目录：" + "(见 list 输出)",
        ""
    ))
    val lines: StateFlow<List<String>> = _lines

    fun append(vararg text: String) {
        _lines.value = _lines.value + text.toList()
    }

    fun appendBlock(text: String) {
        if (text.isEmpty()) return
        _lines.value = _lines.value + text.split('\n')
    }

    fun clear() {
        _lines.value = emptyList()
    }

    // ---------------- 应用日志流（默认开启，平时就把日志打在终端里）----------------
    @Volatile private var logEnabled = true
    private var lastLogSignature = ""

    fun isLogEnabled(): Boolean = logEnabled

    fun setLog(on: Boolean) {
        logEnabled = on
        append(if (on) "[log] 日志流已开启" else "[log] 日志流已关闭")
    }

    /** 轮询本进程 logcat（仅本应用 tag），把新行追加到终端；由界面侧定时调用。 */
    suspend fun pollLogs(): Boolean {
        if (!logEnabled) return false
        val pid = android.os.Process.myPid().toString()
        val text = withContext(Dispatchers.IO) {
            try {
                val p = ProcessBuilder("logcat", "-d", "-v", "brief", "--pid=" + pid, "-s", "UFSBench,DynamoBridge").start()
                val t = p.inputStream.bufferedReader().readText()
                p.waitFor()
                t
            } catch (t: Throwable) {
                ""
            }
        }
        if (text.isEmpty()) return false
        val all = text.split('\n').filter { it.isNotBlank() }
        val start = all.indexOfLast { it == lastLogSignature }
        val fresh = if (start >= 0) all.drop(start + 1) else all
        if (fresh.isEmpty()) return false
        lastLogSignature = all.last()
        _lines.value = _lines.value + fresh.map { "[log] " + it.trim() }
        return true
    }

    /** 由界面在 SAF 选择完成后调用：导入 .zip 组件包（含 SHA-256/ELF/路径穿越三重校验）。 */
    suspend fun importFromUri(ctx: Context, uri: android.net.Uri, expectedSha256: String? = null) {
        append("[import] 读取：" + uri)
        val zip = withContext(Dispatchers.IO) {
            try {
                val tmp = File(ctx.cacheDir, "byob-import.zip")
                ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    tmp.outputStream().use { os -> ins.copyTo(os) }
                }
                tmp
            } catch (t: Throwable) {
                null
            }
        }
        if (zip == null || !zip.isFile) { append("[import] 读取失败"); return }
        append("[import] 包大小：" + zip.length() + " 字节")
        val res = withContext(Dispatchers.IO) { BundleManager.importBundle(ctx, zip, expectedSha256) }
        append("[import] ok=" + res.ok + " 文件=" + res.files + " 字节=" + res.totalBytes)
        append("[import] 二进制 sha256=" + res.binarySha256)
        if (res.needed.isNotEmpty()) append("[import] 依赖=" + res.needed.joinToString(", "))
        if (res.rejected.isNotEmpty()) append("[import] 拒绝项=" + res.rejected.joinToString(", "))
        append("[import] " + res.message)
        if (res.ok) append("[import] 可用命令：list / verify <id> / run <id>")
    }

    private fun bundlesDir(ctx: Context): File = BundleManager.bundlesDir(ctx)

    suspend fun exec(ctx: Context, raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return
        append("> " + cmd)
        val parts = cmd.split(Regex("\\s+"))
        when (parts[0].lowercase()) {
            "help" -> appendBlock(HELP)
            "list" -> doList(ctx)
            "verify" -> if (parts.size < 2) append("用法：verify <benchmark_id>") else doVerify(ctx, parts[1])
            "run" -> if (parts.size < 2) append("用法：run <benchmark_id>") else doRun(ctx, parts[1])
            "cat" -> if (parts.size < 2) append("用法：cat <benchmark_id>") else doCat(ctx, parts[1])
            "log" -> {
                val v = parts.getOrNull(1)?.lowercase()
                setLog(v != "off")
            }
            "import" -> append("请在界面上方的导入按钮选择 .zip 组件包（或先运行 import 触发文件选择器）")
            "clear" -> clear()
            else -> append("未知命令：" + parts[0] + "（输入 help）")
        }
        append("")
    }

    private fun doList(ctx: Context) {
        val dir = bundlesDir(ctx)
        val items = dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        if (items.isEmpty()) {
            append("（暂无组件包）")
            append("投放目录：" + dir.absolutePath)
            append("每个包需要包含：runner.json + bin/<可执行> + data/input/…")
            return
        }
        append(String.format("%-18s %10s  %-8s %s", "benchmark_id", "bytes", "runner", "binary"))
        items.forEach { b ->
            val runner = File(b, "runner.json")
            val binMatch = if (runner.isFile)
                Regex("\"binary_path\"\\s*:\\s*\"([^\"]+)\"").find(runner.readText())?.groupValues?.get(1)
            else null
            val bin = binMatch?.let { File(b, it) }
            append(String.format("%-18s %10d  %-8s %s",
                b.name, bin?.length() ?: 0L,
                if (runner.isFile) "有" else "缺",
                bin?.name ?: "(未在 runner.json 中声明)"))
        }
    }

    private fun doVerify(ctx: Context, id: String) {
        val dir = File(bundlesDir(ctx), id)
        val runner = File(dir, "runner.json")
        if (!runner.isFile) { append("找不到组件包：" + dir.absolutePath); return }
        val binPath = Regex("\"binary_path\"\\s*:\\s*\"([^\"]+)\"").find(runner.readText())?.groupValues?.get(1)
        if (binPath == null) { append("runner.json 未声明 binary_path"); return }
        val bin = File(dir, binPath)
        if (!bin.isFile) { append("二进制缺失：" + bin.absolutePath); return }
        val elf = ElfValidator.validate(bin)
        append("binary      : " + bin.absolutePath)
        append("sha256      : " + BundleManager.sha256(bin))
        append("bytes       : " + bin.length())
        append("elf.ok      : " + elf.ok)
        append("e_machine   : 0x" + Integer.toHexString(elf.machine))
        append("is64        : " + elf.is64)
        append("needed      : " + elf.needed.joinToString(", "))
        append("message     : " + elf.message)
    }

    private suspend fun doRun(ctx: Context, id: String) {
        append("开始执行（沙箱）…")
        val outcome = PrecompiledRunner.run(ctx, id)
        appendBlock(outcome.toText())
    }

    private fun doCat(ctx: Context, id: String) {
        val f = File(File(ctx.getExternalFilesDir(null), "byob"), id + ".result.txt")
        if (!f.isFile) { append("无结果文件：" + f.absolutePath); return }
        appendBlock(f.readText())
    }

    private const val HELP = """命令：
  help              显示本帮助
  list              列出组件包（benchmark_id / 大小 / runner.json / 二进制）
  verify <id>       ELF 准入核验 + SHA-256 + 依赖白名单
  run <id>          沙箱执行并判分（断网/降权/能力清零/seccomp/rlimit/墙钟）
  cat <id>          显示上次执行结果
  clear             清屏
说明：本终端只做"内部相对基线"的自我一致性判定，不代表任何官方成绩。"""
}
