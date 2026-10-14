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
   spec              显示组件包打包规范（自定义 ELF / 自备基准怎么打包）
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
        append("[import] 文件大小：" + zip.length() + " 字节")
        // 按魔数分流：zip（PK）走组件包导入；ELF（0x7F 'E' 'L' 'F'）走裸 ELF 导入
        val magic = withContext(Dispatchers.IO) {
            try { val h = ByteArray(4); zip.inputStream().use { it.read(h) }; h } catch (t: Throwable) { null }
        }
        val isZip = magic != null && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
        val isElf = magic != null && magic[0] == 0x7F.toByte() && magic[1] == 0x45.toByte()
        if (isElf) {
            append("[import] 识别为裸 ELF：按最小组件包处理（无参数、不判分）")
            append("[import] 需要传参或与官方参考输出比对时，请改用 .zip 组件包，规范见命令 spec")
        }
        val res = withContext(Dispatchers.IO) {
            if (isElf) BundleManager.importElf(ctx, zip, expectedSha256)
            else BundleManager.importBundle(ctx, zip, expectedSha256)
        }
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
            "spec" -> appendBlock(PACK_SPEC)
            "import" -> append("请在界面上方的导入按钮选择 .zip 组件包或裸 ELF（或先运行 import 触发文件选择器）")
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
        // 必须在 IO 线程执行：PrecompiledRunner 会 ProcessBuilder(...).waitFor(...) 阻塞等结果，
        // 而本函数由 rememberCoroutineScope 启动、默认跑在 Dispatchers.Main ——
        // 直接调用会让主线程卡住，测试规模稍大即触发 ANR（2026-10-14 真机实测）。
        val outcome = withContext(Dispatchers.IO) { PrecompiledRunner.run(ctx, id) }
        val s = outcome.summary

        // 结果落盘：终端与无界面入口（--es spec_run）共用同一份产物，便于 cat 与外部取证
        val resultFile = withContext(Dispatchers.IO) {
            val dir = File(ctx.getExternalFilesDir(null), "byob").apply { mkdirs() }
            File(dir, id + ".result.txt").apply { writeText(outcome.toText()) }
        }
        val bundleDir = File(bundlesDir(ctx), id)
        val binName = Regex("\"binary_path\"\\s*:\\s*\"([^\"]+)\"")
            .find(File(bundleDir, "runner.json").takeIf { it.isFile }?.readText() ?: "")
            ?.groupValues?.get(1)?.substringAfterLast('/') ?: id

        // 终端只报「已完成 + 产物位置」：完整 stdout/stderr 一律进结果文件（cat <id> 查看），
        // 避免把几十行原始输出糊在终端上。
        append("[完成] " + id +
            "  退出码 " + s.exitCode + (if (s.seccompKilled) "（被 seccomp 拦截）" else "") +
            "  耗时 " + String.format("%.3f", s.wallSeconds) + " s" +
            "  峰值内存 " + String.format("%.1f", s.maxRssKb / 1024.0) + " MB" +
            "  判分 " + (if (s.oracleVerified) "通过" else "未通过"))
        append("产物位置：")
        append("  · 结果文件（完整输出）  " + resultFile.absolutePath)
        append("  · 组件包目录            " + bundleDir.absolutePath)
        append("  · 组件二进制            " + binName)
        append("  · 查看完整输出          输入 cat " + id)
        if (!s.oracleVerified) {
            append("  · 判分说明              " + outcome.oracleDetail.replace('\n', ' ').take(200))
        }
        append("")
        com.ufs.bench.util.CpuAffinityAdvice.advice(binName).forEach { append(it) }
    }

    private fun doCat(ctx: Context, id: String) {
        val f = File(File(ctx.getExternalFilesDir(null), "byob"), id + ".result.txt")
        if (!f.isFile) { append("无结果文件：" + f.absolutePath); return }
        appendBlock(f.readText())
    }

    /** 打包规范（终端 spec 命令打印；与 docs/byob-打包规范.md 同步维护） */
    private const val PACK_SPEC = """组件包打包规范（BYOB）
========================================
【方式一】最简：直接导入一个裸 ELF
  · 要求：aarch64（e_machine=0xB7）可执行文件；依赖只能是 bionic 白名单
    （libc.so / libm.so / libdl.so / liblog.so / libz.so / libstdc++.so /
      libc++_shared.so / libandroid.so / libatomic.so）；
    glibc 名字（libc.so.6、ld-linux-aarch64.so.1 等）会被直接拒绝。
  · 行为：应用自动生成最小组件包（无参数、不做参考输出判分），id 取文件名。
  · 适用：自己写的小程序、单文件微基准。

【方式二】完整：.zip 组件包（支持传参 + 与官方参考输出比对判分）
  目录结构（zip 内即根目录，不要再套一层）：
    runner.json             必需，见下
    bin/<可执行文件>         必需，arm64 ELF
    data/input/...          可选，输入数据集
    data/output/<参考输出>   可选，判分用的参考输出

  runner.json 字段：
    benchmark_id            字符串，唯一用例 id
    binary_path             相对路径，如 bin/lbm
    command_args            字符串数组；以 data/ 开头的元素会被映射为包内绝对路径
    stdout_file             标准输出落盘的文件名（**不要**用程序自己会写的名字）
    oracle_rule {
      type                  none 表示不判分；其它值按下面两个容差判分
      reference_file        相对路径，如 data/output/lbm.out
      abs_tolerance         绝对容差，如 1e-7
      rel_tolerance         相对容差，如 1e-4
      ignore_line_patterns  命中任一子串的行两侧都不参与比对
                            （时间/内存分配这类每次运行都不同的行必须排除）
    }
    execution_profile {
      affinity_policy          预留，当前用 none
      cooldown_target_celsius  冷却目标温度，0 = 不等冷却
      max_execution_seconds    墙钟上限
    }

【判分口径】逐行比对：行内按空白切 token；两侧都能解析为数字时用
  |a-b| <= abs_tol + rel_tol*|b|，否则按字符串精确比对；空行两侧都忽略。
  这是"与参考输出一致"的一致性判定，不代表任何官方成绩。

【打包示例】519.lbm_r.zip 内：
    runner.json  benchmark_id=519.lbm_r / binary_path=bin/lbm
                 command_args=[20, reference.dat, 0, 1, 100_100_130_cf_a.of]
                 stdout_file=lbm.out
                 oracle_rule={type=numeric_tolerance, reference_file=data/output/lbm.out,
                              abs_tolerance=1e-7, rel_tolerance=1e-4}
  输入文件 100_100_130_cf_a.of 放包根（程序按裸文件名打开，需位于运行目录）"""

    private const val HELP = """命令：
  help              显示本帮助
  list              列出组件包（benchmark_id / 大小 / runner.json / 二进制）
  verify <id>       ELF 准入核验 + SHA-256 + 依赖白名单
  run <id>          沙箱执行并判分（断网/降权/能力清零/seccomp/rlimit/墙钟）
  cat <id>          显示上次执行结果
  clear             清屏
说明：本终端只做"内部相对基线"的自我一致性判定，不代表任何官方成绩。"""
}
