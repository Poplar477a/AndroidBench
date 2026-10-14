package com.ufs.bench

import android.app.Application
import com.ufs.bench.util.AppLogger

/**
 * 进程入口：只做"必须在任何 UI/引擎之前完成"的初始化。
 *
 * 目前只有日志系统 —— 它要在原生库加载、档位选择、root 探测之前就位，
 * 否则恰恰是排障最需要的那几条早期日志会丢。
 */
class BenchApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // 跳板进程（:relaunch）只负责把主进程重新拉起来，不需要日志/采样通道初始化：
        // 不做这个判断，日志里会出现两份一模一样的启动记录（真机实测已发生），
        // 排障时容易被误读成双重启动/重启循环。
        if (currentProcessName().endsWith(RELAUNCH_PROCESS_SUFFIX)) return

        // 原生层判定"本进程实际加载的 ABI 切片"需要 applicationInfo.nativeLibraryDir，
        // 必须在任何 dlopen 之前注入（档位选择依赖它，晚了就会用错证据）。
        runCatching {
            com.ufs.bench.core.DynamoNativeBridge.appContext = this
            com.ufs.bench.hardware.StorageDetector.appContext = this
        }

        // ---------- PGO 采样通道（仅 Debug） ----------
        // 插桩库（-fprofile-generate）在**动态库加载时**读取 LLVM_PROFILE_FILE 决定
        // .profraw 落盘位置；应用进程的默认 cwd 是 "/"，不可写，不设就会静默丢数据。
        // android.system.Os.setenv 是公开 API（API 21+），且必须在 dlopen 之前调用 ——
        // Application.onCreate 正是这个时机（原生库要到首次测试才加载）。
        // Release 不做这件事：正式包不带插桩库，设了也没有意义。
        if (BuildConfig.DEBUG) {
            runCatching {
                val dir = java.io.File(filesDir, "pgo")
                if (!dir.exists()) dir.mkdirs()
                android.system.Os.setenv(
                    "LLVM_PROFILE_FILE",
                    java.io.File(dir, "bench_%m_%p.profraw").absolutePath,
                    true
                )
            }
        }

        AppLogger.init(this)
        AppLogger.i("App", "启动：versionName=" + BuildConfig.VERSION_NAME +
            " versionCode=" + BuildConfig.VERSION_CODE +
            " debug=" + BuildConfig.DEBUG)
    }

    /** 当前进程名（API 28+ 有公开接口；低版本读 /proc/self/cmdline） */
    private fun currentProcessName(): String = if (android.os.Build.VERSION.SDK_INT >= 28) {
        getProcessName()
    } else {
        runCatching {
            java.io.File("/proc/self/cmdline").readText().trimEnd(0.toChar())
        }.getOrDefault(packageName)
    }

    private companion object {
        const val RELAUNCH_PROCESS_SUFFIX = ":relaunch"
    }
}
