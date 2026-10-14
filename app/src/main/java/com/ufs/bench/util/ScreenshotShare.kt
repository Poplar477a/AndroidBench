package com.ufs.bench.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 页面截图与分享。
 *
 * 采用「重绘根视图到 Bitmap」的方式，不申请 MediaProjection 权限，
 * 因此不会给用户弹录屏授权，也拿不到状态栏等系统内容——对分享测试结果刚好够用。
 */
object ScreenshotShare {

    private const val DIR = "reports"

    /** 把 view 截图、写入缓存目录，并拉起系统分享面板 */
    fun captureAndShare(context: Context, view: View, tag: String = "result") {
        val bitmap = try {
            renderToBitmap(view)
        } catch (_: Exception) {
            null
        } ?: return

        val uri = try {
            saveToCache(context, bitmap, tag)
        } catch (_: Exception) {
            null
        } ?: return

        dispatch(context, uri)
    }

    /** 直接分享一张已渲染好的 Bitmap（用于独立渲染的结果卡） */
    fun shareBitmap(context: Context, bitmap: Bitmap, tag: String = "report") {
        val uri = try {
            saveToCache(context, bitmap, tag)
        } catch (_: Exception) {
            null
        } ?: return
        dispatch(context, uri)
    }

    private fun dispatch(context: Context, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "存储测试结果")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "分享测试结果").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun renderToBitmap(view: View): Bitmap {
        val w = if (view.width > 0) view.width else 1080
        val h = if (view.height > 0) view.height else 1920
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // 深色底：截图分享出去在浅色背景里也能看清
        canvas.drawColor(android.graphics.Color.parseColor("#121216"))
        view.draw(canvas)
        return bitmap
    }

    /**
     * 保存报告图。
     *
     * 落点顺序：
     *   1) Android 10+ ：MediaStore.Downloads → 直接出现在「下载」目录，
     *      返回的 content URI 可被其它应用直接读取，无需 FileProvider 中转
     *   2) Android 9-  ：写公共 Download 目录后经 FileProvider 授权分享
     *   3) 兜底        ：应用外部目录，再不行用私有缓存
     */
    private fun saveToCache(context: Context, bitmap: Bitmap, tag: String): Uri {
        val name = "${tag}_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        }.png"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "image/png")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    return uri
                }
            } catch (_: Exception) {
                // 落到下面的兼容路径
            }
        }

        // Android 9 及以下：直接写公共 Download 目录
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (dir != null && (dir.exists() || dir.mkdirs())) {
                    val file = File(dir, name)
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    return FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                }
            } catch (_: Exception) {
            }
        }

        // 兜底
        val dir = (context.getExternalFilesDir(DIR) ?: File(context.cacheDir, DIR)).apply { mkdirs() }
        val file = File(dir, name)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}