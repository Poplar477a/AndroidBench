package com.ufs.bench.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.ufs.bench.BuildConfig
import com.ufs.bench.R
import com.ufs.bench.ui.screens.TerminalScreen
import com.ufs.bench.model.BenchmarkMode
import com.ufs.bench.ui.screens.DashboardScreen
import com.ufs.bench.ui.screens.OnboardingScreen
import com.ufs.bench.ui.screens.ReportScreen
import com.ufs.bench.ui.screens.RunningScreen
import com.ufs.bench.ui.screens.SettingsScreen
import com.ufs.bench.ui.screens.SpecificationScreen
import com.ufs.bench.ui.theme.UfsBenchTheme

/** 首启引导是否已完成的持久化键（与 SPEC/用例持久化分开命名空间，互不干扰） */
private const val KEY_ONBOARDING_DONE = "onboarding_done_v1"

class MainActivity : ComponentActivity() {

    private val viewModel: BenchmarkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ---- 档位偏好迁移：专属调优已取消，历史遗留的 SoC 档位一律回到 baseline ----
        try {
            val sp = getSharedPreferences("ufs_bench_spec", MODE_PRIVATE)
            if (sp.getString("cpu_profile_preference", "baseline") != "baseline") {
                sp.edit().putString("cpu_profile_preference", "baseline").commit()
                android.util.Log.i("UFSBench", "legacy cpu profile reset to baseline")
            }
        } catch (t: Throwable) {
            android.util.Log.w("UFSBench", "profile migrate skip: " + (t.message ?: "?"))
        }

        // ---- BYOB 无界面入口（不做 UI，结果直出）----
        // 用法：adb shell am start -n <包名>/.ui.MainActivity --es spec_run <benchmark_id>
        // 行为：沙箱执行已导入的组件包 → Oracle 判分 → 结果写入
        //       /sdcard/Android/data/<包名>/files/byob/<id>.result.txt，并同步 logcat。
        intent?.getStringExtra("spec_run")?.let { benchId ->
            Thread {
                try {
                    val outcome = com.ufs.bench.spec.PrecompiledRunner.run(this, benchId)
                    val dir = java.io.File(getExternalFilesDir(null), "byob").apply { mkdirs() }
                    java.io.File(dir, benchId + ".result.txt").writeText(outcome.toText())
                    android.util.Log.i("UFSBench", "byob done id=" + benchId +
                        " exit=" + outcome.summary.exitCode +
                        " verified=" + outcome.summary.oracleVerified)
                } catch (t: Throwable) {
                    android.util.Log.e("UFSBench", "byob failed: " + (t.message ?: "?"))
                }
            }.start()
        }

        setContent {
            // 配色全部交给主题层：Android 12+（API 31）走壁纸动态取色（Monet），
            // 低版本回退到 ui/theme/Color.kt 的固定浅色/深色两套方案。
            UfsBenchTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }
    }
}

/**
 * 改档位后**自动重启进程**（需求：更改调优自动保存并重启软件，不再要求用户手动重启）。
 *
 * 为什么必须重启：同一 ABI 的定向调优 .so 一旦 dlopen 就无法可靠卸载/替换，
 * 原生侧只认「进程启动时的那一份」。
 *
 * 实现：由**独立进程里的跳板 Activity**（[RestartActivity]，android:process=":relaunch"）重启。
 *
 * 曾经的做法与实测结果（务必别再改回去）：
 *   · AlarmManager + PendingIntent「定时拉起自己再自杀」→ 在 Android 17（一加 15）上**直接闪退且不再起来**。
 *     原因：Android 10+ 的「后台启动 Activity」限制会拦掉已死进程发出的启动请求。
 *   · 「先 startActivity 再 exit」→ 新 Activity 会落进**同一个正在退出的进程**里，等于没重启。
 *   跳板方案同时避开这两点：跳板自己可见地跑在另一个进程里，由它拉起 MainActivity；
 *   主进程这时已经退干净，新实例必然落在新的干净进程 —— 这正是"换档必须重启进程"所需要的。
 *
 * 为什么用 exit(0) 而不是 Process.killProcess：exit(0) 是**优雅退出**，会跑 atexit/shutdown hook。
 * 插桩库（-fprofile-generate）的 profile 运行时正是在 atexit 里把 .profraw 落盘 ——
 * 用强杀方式（am force-stop / kill -9）会静默丢掉整轮 PGO 采集数据（已实测：files/pgo 一直为空）。
 */
/**
 * 改档位后**自动重启进程**（需求：更改调优自动保存并重启软件，不再要求用户手动重启）。
 *
 * 为什么必须重启：同一 ABI 的定向调优 .so 一旦 dlopen 就无法可靠卸载/替换，
 * 原生侧只认「进程启动时的那一份」。
 *
 * 实现：由**独立进程里的跳板 Activity**（RestartActivity，android:process=":relaunch"）重启。
 * 曾经的做法与实测结果（务必别再改回去）：
 *   · AlarmManager + PendingIntent「定时拉起自己再自杀」：在 Android 17（一加 15）上直接闪退且不再起来。
 *     原因：Android 10+ 的「后台启动 Activity」限制会拦掉已死进程发出的启动请求。
 *   · 「先 startActivity 再 exit」：新 Activity 会落进**同一个正在退出的进程**里，等于没重启。
 *
 * 为什么用 exit(0) 而不是 killProcess：exit(0) 是**优雅退出**，会跑 atexit/shutdown hook。
 * 插桩库（-fprofile-generate）的 profile 运行时正是在 atexit 里把 .profraw 落盘。
 *
 * @return true = 重启已请求（本进程随后优雅退出）；false = 跳板起不来，
 *         调用方必须退化为「下次启动生效」并如实告知 —— **绝不能在这种情况下自杀**。
 */
private fun restartProcessForProfileChange(ctx: Context, target: String): Boolean {
    val started = runCatching {
        ctx.startActivity(
            Intent(ctx, RestartActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                // 把目标档位带过去：跳板那一屏要如实显示正在切到哪一档
                .putExtra(RestartActivity.EXTRA_TARGET_LABEL, target)
        )
    }.isSuccess
    if (!started) {
        android.util.Log.w("UFSBench", "自重启跳板启动失败：档位已保存，将于下次启动生效")
        return false
    }
    android.util.Log.i("UFSBench", "档位已保存，自动重启以生效")
    // 插桩构建：这是退出的最后机会，主动把 PGO profile 落盘。
    // 只靠 atexit 在 Android 上不可靠（真机实测：优雅 exit(0) 后 files/pgo 仍为空）。
    runCatching {
        com.ufs.bench.core.DynamoNativeBridge.flushPgoProfile(java.io.File(ctx.filesDir, "pgo"))
    }
    // 用**系统 Toast** 告知"这是主动重启，不是崩溃"：
    //  · Toast 由 system_server 渲染，本进程 exit(0) 之后仍然留在屏幕上，
    //    正好覆盖"旧进程已退出、新进程还在起"的那段空窗；
    //  · 跳板里自绘的那一屏在部分模拟器上子视图不上屏（实测只剩纯背景色，观感仍是黑一下），
    //    系统 Toast 不依赖应用窗口合成，稳定得多。
    runCatching {
        android.widget.Toast.makeText(
            ctx,
            "正在重启应用以应用档位：" + target + "（正常重启，不是崩溃）",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
    // 稍等跳板进程真正起来再退出；exit(0) 的 atexit 也会把插桩 .profraw 落盘。
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        Runtime.getRuntime().exit(0)
    }, 250)
    return true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(viewModel: BenchmarkViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current

    // ---------- 首次启动新手引导 ----------
    // 只需读一次：用 remember 缓存"是否已完成"，避免每次重组都读 SharedPreferences。
    val prefs = remember {
        ctx.getSharedPreferences("ufs_bench_ui", android.content.Context.MODE_PRIVATE)
    }
    var onboardingDone by remember {
        mutableStateOf(prefs.getBoolean(KEY_ONBOARDING_DONE, false))
    }
    val finishOnboarding: () -> Unit = {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
        onboardingDone = true
    }
    if (!onboardingDone) {
        // 引导是**整屏**呈现且带「跳过」：不阻塞后续任何功能，
        // 用户跳过与读完走同一条落盘路径，之后不再自动弹出。
        OnboardingScreen(onFinish = finishOnboarding, onSkip = finishOnboarding)
        return
    }

    // root / 权限类失败一律在**应用壳层**弹窗（需求 C1/C2）。
    // 只挂在设置页时，从首页触发的拦截（例如「专业模式 + 无缓存参与」启动被拦）用户根本看不到，
    // 实测这正是「无 root 执行没有明显报错」的根因之一。
    uiState.rootError?.let { msg ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissRootError() },
            title = { androidx.compose.material3.Text("专业模式 / Root 权限") },
            text = { androidx.compose.material3.Text(msg) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.dismissRootError() }) {
                    androidx.compose.material3.Text("我知道了")
                }
            }
        )
    }
    // 分享一律走「独立渲染的结果卡」：抓视口截图会带导航栏且被滚动位置截断。
    // 设置页原本的「截图并分享当前页面」入口已按需求移除，故不再需要页面截图路径。
    // 内置微基准结果不提供分享：比值为自标定基线、非 spec.org 官方成绩，分享出去易被误读。
    // ResultCardRenderer.render 对 SPEC 返回 null，这里再判一次并安全跳过。
    val shareResult: () -> Unit = {
        val last = uiState.history.firstOrNull()
        if (last != null && !last.spec.portedVersion) {
            try {
                val bmp = com.ufs.bench.util.ResultCardRenderer.render(
                    ctx, last, uiState.hardwareInfo, uiState.professionalMode
                )
                if (bmp != null) {
                    com.ufs.bench.util.ScreenshotShare.shareBitmap(ctx, bmp, "report")
                }
            } catch (_: Exception) {
            }
        }
    }

    // 改档位统一入口（需求 F1/F2）：保存 -> 去重 -> 重启；重启不可用则如实降级，绝不自杀。
    val applyCpuProfileSwitch: (String) -> Unit = { profile ->
        val restartNeeded = viewModel.setCpuProfile(profile)
        if (restartNeeded && !viewModel.isBusy() && viewModel.shouldRequestRestart(profile)) {
            if (!restartProcessForProfileChange(ctx, profile)) {
                viewModel.onRestartUnavailable("跳板 Activity 启动失败")
            }
        }
    }

    // 测试开始时自动跳转到「测试中」页面
    LaunchedEffect(uiState.benchmarkState) {
        if (uiState.benchmarkState == BenchmarkState.PREPARING ||
            uiState.benchmarkState == BenchmarkState.RUNNING) {
            selectedTab = 1
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            // MD3 顶栏只承载应用品牌；各页面自己的标题仍在页面内渲染，避免标题重复。
            CenterAlignedTopAppBar(
                title = { Text(text = stringResource(R.string.app_bar_title), fontWeight = FontWeight.Bold) }
            )
        },
        bottomBar = {
            // 容器色交给主题默认值（surfaceContainer），跟随动态取色变化。
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Speed, contentDescription = stringResource(R.string.tab_home)) },
                    label = { Text(stringResource(R.string.tab_home)) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.PlayCircle, contentDescription = stringResource(R.string.tab_running)) },
                    label = { Text(stringResource(R.string.tab_running)) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.tab_custom_test)) },
                    label = { Text(stringResource(R.string.tab_custom_test)) }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Assessment, contentDescription = stringResource(R.string.tab_reports)) },
                    label = { Text(stringResource(R.string.tab_reports)) }
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.tab_settings)) },
                    label = { Text(stringResource(R.string.tab_settings)) }
                )
                // BYOB 终端是实验室版专属能力（免 Root 标准版不出现）
                if (BuildConfig.FEATURE_EXTERNAL_SPEC) {
                    NavigationBarItem(
                        selected = selectedTab == 5,
                        onClick = { selectedTab = 5 },
                        icon = { Icon(Icons.Default.Tune, contentDescription = "BYOB 终端") },
                        label = { Text("终端") }
                    )
                }
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.surface
        ) {
            // PC / 服务器级内置档位的启动前确认
            uiState.pendingConfirmSpec?.let { pending ->
                AlertDialog(
                    onDismissRequest = { viewModel.cancelPendingStart() },
                    title = { Text("PC 级内置档位确认") },
                    text = { Text(pending.description) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.confirmPendingStart() }) {
                            Text(
                                text = "强制运行（跳过预检）",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.cancelPendingStart() }) {
                            Text("改用 test 档")
                        }
                    }
                )
            }

            when (selectedTab) {
                0 -> DashboardScreen(
                    state = uiState,
                    onStartTest = { viewModel.startBenchmark() },
                    onStopTest = { viewModel.stopBenchmark() },
                    onSelectPreset = { preset ->
                        // 仅选中该用例为当前测试选项，不立即触发压测；启动由用户点按「开始基准测试」统一触发
                        val tier = if (preset.mode == BenchmarkMode.BUILTIN_KERNEL) uiState.currentSpec.specTier else preset.specTier
                        val merged = preset.copy(specTier = tier)
                        viewModel.selectSpec(merged)
                    },
                    onSelectEngine = { viewModel.selectEngine(it) },
                    onSelectTier = { viewModel.setSpecTier(it) }
                )
                1 -> RunningScreen(
                    state = uiState,
                    onStopTest = { viewModel.stopBenchmark() },
                    onGoDashboard = { selectedTab = 0 },
                    onShare = shareResult
                )
                2 -> SpecificationScreen(
                    currentSpec = uiState.currentSpec,
                    onApplyAndRun = { spec ->
                        viewModel.selectSpec(spec)
                        selectedTab = 1
                        viewModel.startBenchmark(spec)
                    },
                    onApplyOnly = { spec ->
                        viewModel.selectSpec(spec)
                        selectedTab = 0
                    }
                )
                3 -> ReportScreen(
                    history = uiState.history,
                    hardwareInfo = uiState.hardwareInfo,
                    onClearHistory = { viewModel.clearHistory() },
                    onShare = shareResult
                )
                5 -> if (BuildConfig.FEATURE_EXTERNAL_SPEC) TerminalScreen()
                4 -> SettingsScreen(
                    professionalMode = uiState.professionalMode,
                    cacheParticipates = uiState.cacheParticipates,
                    sampleIntervalMs = uiState.sampleIntervalMs,
                    cpuProfilePreference = uiState.cpuProfilePreference,
                    cpuProfileActive = uiState.cpuProfileActive,
                    cpuProfileActiveMcpu = uiState.cpuProfileActiveMcpu,
                    cpuProfileRequested = uiState.cpuProfileRequested,
                    cpuProfileAbi = uiState.cpuProfileAbi,
                    cpuProfileFallback = uiState.cpuProfileFallback,
                    // 编译工具链串：优先本次运行，其次历史最近一条（同一次构建的原生库）；
                    // 两条都没有时为空，页面如实显示「—（原生未提供）」，不做任何硬编码兜底。
                    compilerToolchain = uiState.currentRunSummary?.compilerToolchain
                        ?.takeIf { it.isNotEmpty() }
                        ?: uiState.history.firstOrNull()?.compilerToolchain.orEmpty(),
                    cpuProfileNote = uiState.cpuProfileNote,
                    cpuProfilePendingRestart = uiState.cpuProfilePendingRestart,
                    cpuProfileAvailable = uiState.cpuProfileAvailable,
                    cpuProfileCatalog = uiState.cpuProfileCatalog,
                    cpuProfileCcOrigin = uiState.cpuProfileCcOrigin,
                    hasRoot = uiState.hardwareInfo.hasRootAccess,
                    hardwareInfo = uiState.hardwareInfo,
                    // Debug 构建全解锁；Release 走正常门禁
                    debugUnlockAll = com.ufs.bench.BuildConfig.DEBUG,
                    onToggleProfessional = { viewModel.setProfessionalMode(it) },
                    onToggleCache = { viewModel.setCacheParticipates(it) },
                    onSampleIntervalChange = { viewModel.setSampleInterval(it) },
                    onSelectCpuProfile = { profile -> applyCpuProfileSwitch(profile) },
                    // 档位状态机（需求 F1/A3）：保存值 / 待应用 / 上次已验证 分开呈现
                    cpuProfileSaved = uiState.cpuProfileSaved,
                    cpuProfilePending = uiState.cpuProfilePending,
                    cpuProfileLastVerified = uiState.cpuProfileLastVerified,
                    cpuProfileSwitchStage = uiState.cpuProfileSwitchStage,
                    onRollbackCpuProfile = {
                        // 回退同样要**真的生效**：走与切换一致的「去重 + 重启」路径
                        val target = viewModel.rollbackToLastVerified()
                        if (target != null && !viewModel.isBusy() && viewModel.shouldRequestRestart(target)) {
                            if (!restartProcessForProfileChange(ctx, target)) {
                                viewModel.onRestartUnavailable("跳板 Activity 启动失败")
                            }
                        }
                    }
                )
            }
        }
    }
}