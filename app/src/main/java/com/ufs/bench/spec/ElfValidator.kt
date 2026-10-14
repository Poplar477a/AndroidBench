package com.ufs.bench.spec

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ============================================================================
 *  ELF 静态准入核验（V3 规划 M2.2）
 *  在外部预编译二进制被放入执行目录之前做前置阻断：
 *   1) ELF 魔数 + 64 位 + 小端；
 *   2) e_machine == 0xB7（EM_AARCH64）—— 非 arm64 直接拒收；
 *   3) 拒绝脚本与包装体（首行 #! 一律判违规，禁止借道解释器）；
 *   4) PT_DYNAMIC 的 DT_NEEDED 白名单：仅限 Android Bionic 核心库；
 *   5) 路径穿越防御（由 BundleManager 统一负责，这里只判内容）。
 * ============================================================================
 */
object ElfValidator {

    const val EM_AARCH64 = 0xB7

    /** Bionic 核心库白名单（按 AOSP 命名） */
    val ALLOWED_SONAMES = setOf(
        "libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so",
        "libstdc++.so", "libc++_shared.so", "libandroid.so", "libatomic.so"
    )

    /** glibc / 非 Android 运行时的特征库名：一旦出现直接拒绝 */
    val DENIED_SONAMES = setOf(
        "libc.so.6", "libm.so.6", "libpthread.so.0", "libgcc_s.so.1", "ld-linux-aarch64.so.1"
    )

    data class Result(
        val ok: Boolean,
        val machine: Int,
        val is64: Boolean,
        val needed: List<String>,
        val rejected: List<String>,
        val message: String
    )

    fun validate(file: File): Result {
        if (!file.isFile) return Result(false, 0, false, emptyList(), emptyList(), "文件不存在：" + file.name)
        val head = ByteArray(20)
        file.inputStream().use { it.read(head) }
        // 3) 脚本/包装体
        if (head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()) {
            return Result(false, 0, false, emptyList(), listOf("shebang"), "拒绝：这是脚本（#!），禁止借道解释器")
        }
        // 1) ELF 魔数
        if (!(head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() &&
              head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte())) {
            return Result(false, 0, false, emptyList(), listOf("not-elf"), "拒绝：不是 ELF 文件")
        }
        val is64 = head[4] == 2.toByte()
        val le = head[5] == 1.toByte()
        if (!is64) return Result(false, 0, false, emptyList(), listOf("not-64bit"), "拒绝：不是 64 位 ELF")
        if (!le)  return Result(false, 0, false, emptyList(), listOf("not-le"), "拒绝：不是小端 ELF")

        val bytes = file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val machine = bb.getShort(18).toInt() and 0xFFFF
        // 2) 架构断言
        if (machine != EM_AARCH64) {
            return Result(false, machine, true, emptyList(), listOf("machine-0x" + Integer.toHexString(machine)),
                "拒绝：e_machine=0x" + Integer.toHexString(machine) + "，仅接受 arm64（0xb7）")
        }
        // 4) 依赖白名单
        val needed = readNeeded(bb)
        val bad = needed.filter { soname ->
            val n = soname.substringAfterLast('/')
            n in DENIED_SONAMES || (n !in ALLOWED_SONAMES && !n.startsWith("libclang_rt"))
        }
        val ok = bad.isEmpty()
        val msg = if (ok) "通过：arm64 ELF，依赖 " + needed.size + " 项全部在白名单内"
                  else "拒绝：依赖不在白名单 -> " + bad.joinToString(", ")
        return Result(ok, machine, true, needed, bad, msg)
    }

    private fun readNeeded(bb: ByteBuffer): List<String> {
        return try {
            val phoff = bb.getLong(32)
            val phentsize = bb.getShort(54).toInt() and 0xFFFF
            val phnum = bb.getShort(56).toInt() and 0xFFFF
            var dynOff = 0L; var dynSize = 0L
            for (i in 0 until phnum) {
                val base = (phoff + i.toLong() * phentsize).toInt()
                if (base + 56 > bb.capacity()) break
                if (bb.getInt(base) == 2) {           // PT_DYNAMIC
                    dynOff = bb.getLong(base + 8)
                    dynSize = bb.getLong(base + 32)
                    break
                }
            }
            if (dynOff <= 0L) return emptyList()
            var strtab = 0L
            val neededOffsets = ArrayList<Long>()
            var off = 0L
            while (off + 16 <= dynSize) {
                val p = (dynOff + off).toInt()
                if (p + 16 > bb.capacity()) break
                val tag = bb.getLong(p)
                val value = bb.getLong(p + 8)
                if (tag == 0L) break
                if (tag == 5L) strtab = value
                if (tag == 1L) neededOffsets.add(value)
                off += 16
            }
            if (strtab <= 0L) return emptyList()
            neededOffsets.mapNotNull { o ->
                val s = (strtab + o).toInt()
                if (s < 0 || s >= bb.capacity()) return@mapNotNull null
                val sb = StringBuilder()
                var i = s
                while (i < bb.capacity() && sb.length < 256) {
                    val c = bb.get(i)
                    if (c.toInt() == 0) break
                    sb.append(c.toInt().toChar())
                    i++
                }
                sb.toString().takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
