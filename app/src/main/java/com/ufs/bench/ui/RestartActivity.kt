package com.ufs.bench.ui

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 自重启**跳板**（必须跑在独立进程 [PROCESS] 里，见 AndroidManifest 的 android:process）。
 *
 * 为什么需要它 —— 两条都实测过，别再改回去：
 *  1) AlarmManager + PendingIntent「定时拉起自己再自杀」：
 *     在 Android 17（一加 15 / SM8850）上表现为**直接闪退且不再起来**。
 *     原因是 Android 10+ 的「后台启动 Activity」限制会拦掉已死进程发出的启动请求。
 *  2) 「先 startActivity 再 exit」：新 Activity 会落进**同一个正在退出的进程**里，
 *     进程一没，新界面跟着没 —— 等于没重启。
 *
 * 跳板方案同时避开这两点：本 Activity 在**自己的进程**里可见地跑起来
 * （前台进程启动 Activity 是合法路径），主进程这时已经 exit(0) 退干净了，
 * 由它再拉起 [MainActivity]，新实例必然建在一个全新的干净进程里 ——
 * 这正是「同 ABI 的定向调优 .so 一旦 dlopen 就无法原地替换，换档必须重启进程」所需要的。
 *
 * **为什么要有一屏可见提示**（真机反馈修出来的）：
 * 没有它时，用户看到的是"点了档位 → 应用凭空消失约 1 秒 → 又回来"，
 * 与崩溃闪退在观感上完全一致 —— 实测用户直接判定为"又闪退了"。
 * 所以在跳板存活期间显示这一屏，把"正常重启"与"崩溃"区分开。
 */
class RestartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getStringExtra(EXTRA_TARGET_LABEL).orEmpty()
        val delayMs = intent.getLongExtra(EXTRA_DELAY_MS, DEFAULT_DELAY_MS)
        showRestartingScreen(target)
        Handler(Looper.getMainLooper()).postDelayed({
            Log.i(TAG, "自重启跳板：拉起 MainActivity（旧进程应已退出）")
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
            // 本进程使命完成，主动退掉，不留空进程
            Handler(Looper.getMainLooper()).postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 300)
        }, delayMs)
    }

    /** 一屏可见的「正在重启」；没有它，重启与闪退在观感上无法区分（真机实测反馈）。 */
    private fun showRestartingScreen(target: String) {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        // 深色底 + 居中说明：明确告诉用户这是主动重启
        window.setBackgroundDrawable(ColorDrawable(0xFF101418.toInt()))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(px(24), px(24), px(24), px(24))
        }
        col.addView(ProgressBar(this))
        col.addView(TextView(this).apply {
            text = "正在重启应用以应用档位"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, px(20), 0, 0)
        })
        if (target.isNotEmpty()) {
            col.addView(TextView(this).apply {
                text = "目标档位：" + target
                textSize = 14f
                setTextColor(0xFFB9C2CC.toInt())
                gravity = Gravity.CENTER
                setPadding(0, px(8), 0, 0)
            })
        }
        col.addView(TextView(this).apply {
            text = "这是正常重启（换档必须重启进程），不是崩溃；完成后会自动回到应用。"
            textSize = 12f
            setTextColor(0xFF8A939C.toInt())
            gravity = Gravity.CENTER
            setPadding(0, px(16), 0, 0)
        })
        // 内容视图**自己**带不透明背景 + 显式 MATCH_PARENT：
        // 半透明主题只保证窗口背景，实测真机/模拟器上截到的那一帧是纯背景色、
        // 文字与进度条都没画出来（用户看到的仍是"黑一下"）。把背景与尺寸都放到内容视图上，
        // 就不依赖主题的 windowBackground 行为。
        col.setBackgroundColor(0xFF101418.toInt())
        setContentView(
            col,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    companion object {
        const val TAG = "UFSBench"
        const val PROCESS = ":relaunch"
        const val EXTRA_DELAY_MS = "delay_ms"
        const val EXTRA_TARGET_LABEL = "target_label"

        /** 默认延迟：足够旧进程 exit(0)（含插桩库 atexit 落盘）走完。 */
        const val DEFAULT_DELAY_MS = 900L
    }
}
