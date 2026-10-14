package com.ufs.bench.spec

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * ============================================================================
 *  运行结果导出到**公共 Documents**
 *
 *  为什么走 MediaStore：
 *    Android 10+（分区存储）下应用无法用 File 路径直接写公共目录；
 *    MediaStore 插入自己的文件不需要任何存储权限，且能被文件管理器/其他应用看到。
 *    落点：Documents/UFSBench/<文件名>。
 *
 *  重名处理（用户明确要求）：
 *    先探测同名是否已存在，存在则依次尝试 <名>-2 / -3 …（最多 99 次），
 *    绝不静默覆盖既有结果文件；最终名字与完整位置由调用方打印到终端。
 * ============================================================================
 */
object ResultExporter {

    private const val SUBDIR = "UFSBench"
    private const val MIME = "text/plain"

    private fun relativePath(): String = Environment.DIRECTORY_DOCUMENTS + "/" + SUBDIR

    /** 在 Documents/UFSBench 下探测该显示名是否已存在 */
    private fun exists(ctx: Context, display: String): Boolean {
        val cols = arrayOf(MediaStore.MediaColumns._ID)
        val sel = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " +
            MediaStore.MediaColumns.RELATIVE_PATH + "=?"
        val args = arrayOf(display, relativePath() + "/")
        return try {
            ctx.contentResolver.query(
                MediaStore.Files.getContentUri("external"), cols, sel, args, null
            )?.use { it.count > 0 } ?: false
        } catch (t: Throwable) {
            false
        }
    }

    /** 生成不冲突的名字：base.txt / base-2.txt / base-3.txt … */
    private fun uniqueName(ctx: Context, base: String, ext: String): String {
        if (!exists(ctx, base + ext)) return base + ext
        for (i in 2..99) {
            val cand = base + "-" + i + ext
            if (!exists(ctx, cand)) return cand
        }
        // 极端情况：99 个同名都不够，退化为带时间戳，保证不覆盖
        return base + "-" + System.currentTimeMillis() + ext
    }

    /**
     * @return 成功时返回 "Documents/UFSBench/<文件名>"；失败返回 null（调用方如实提示）
     */
    fun export(ctx: Context, baseName: String, text: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // API 28 及以下没有 RELATIVE_PATH，公共目录需要存储权限；此处直接如实返回失败，
            // 私有目录那份结果文件仍然可用（cat <id>）。
            return null
        }
        val base = baseName.substringBeforeLast('.')
        val ext = "." + baseName.substringAfterLast('.', "txt")
        val name = uniqueName(ctx, base, ext)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection: Uri = MediaStore.Files.getContentUri("external")
        var uri: Uri? = null
        return try {
            uri = ctx.contentResolver.insert(collection, values)
            if (uri == null) return null
            ctx.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(text.toByteArray(Charsets.UTF_8))
            } ?: return null
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
            relativePath() + "/" + name
        } catch (t: Throwable) {
            // 失败时清理半成品，避免留下 0 字节文件
            try { uri?.let { ctx.contentResolver.delete(it, null, null) } } catch (t2: Throwable) {}
            null
        }
    }
}

