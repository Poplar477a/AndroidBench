package com.ufs.bench.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ufs.bench.util.AppLogger
import com.ufs.bench.core.DynamoEngineImpl
import com.ufs.bench.core.EngineProfileSpec
import com.ufs.bench.core.FioEngineImpl
import com.ufs.bench.core.IBenchmarkEngine
import com.ufs.bench.hardware.HardwareStorageInfo
import com.ufs.bench.hardware.StorageDetector
import com.ufs.bench.hardware.StorageTarget
import com.ufs.bench.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class BenchmarkState {
    IDLE,
    PREPARING,
    RUNNING,
    COMPLETED,
    ERROR
}

/**
 * 测试进行中的实时散点缓存（仅专业模式累积）。
 *
 * 原生层只在测试结束后给出完整曲线，因此进行中先按进度回调攒点，
 * 结束后由 [com.ufs.bench.core.NativeDetailedStats] 的完整曲线覆盖。
 */
data class RealtimeCurves(
    val timeSec: List<Double> = emptyList(),
    val readBwMbps: List<Double> = emptyList(),
    val writeBwMbps: List<Double> = emptyList(),
    val readLatUs: List<Double> = emptyList(),
    val writeLatUs: List<Double> = emptyList(),
    val capacityMb: List<Double> = emptyList()
) {
    /** 已有多少采样点（各系列等长，取最大即可） */
    val sampleCount: Int
        get() = maxOf(readBwMbps.size, writeBwMbps.size, readLatUs.size, writeLatUs.size)

    /**
     * 追加一个采样点。
     * 按 [MIN_APPEND_INTERVAL_SEC] 节流（20 Hz 采样足够观察且抗频繁重绘）。
     * 超过 [MAX_POINTS] 时采用均匀抽稀（保留偶数点），永不丢弃原点与整体时间轴，
     * 彻底根除超过 200s 时截断头部导致 X 轴不断跳移抖动的抽搐现象。
     */
    fun append(metric: RealtimeMetric, tSec: Double): RealtimeCurves {
        val last = timeSec.lastOrNull()
        if (last != null && tSec - last < MIN_APPEND_INTERVAL_SEC) return this
        val capMb = metric.bytesDone / (1024.0 * 1024.0)

        var t = timeSec + tSec
        var rBw = readBwMbps + metric.readMbps
        var wBw = writeBwMbps + metric.writeMbps
        var rLat = readLatUs + metric.readAvgLatencyUs
        var wLat = writeLatUs + metric.writeAvgLatencyUs
        var cap = capacityMb + capMb

        if (t.size > MAX_POINTS) {
            t = t.filterIndexed { i, _ -> i % 2 == 0 }
            rBw = rBw.filterIndexed { i, _ -> i % 2 == 0 }
            wBw = wBw.filterIndexed { i, _ -> i % 2 == 0 }
            rLat = rLat.filterIndexed { i, _ -> i % 2 == 0 }
            wLat = wLat.filterIndexed { i, _ -> i % 2 == 0 }
            cap = cap.filterIndexed { i, _ -> i % 2 == 0 }
        }

        return RealtimeCurves(
            timeSec = t,
            readBwMbps = rBw,
            writeBwMbps = wBw,
            readLatUs = rLat,
            writeLatUs = wLat,
            capacityMb = cap
        )
    }

    companion object {
        const val MIN_APPEND_INTERVAL_SEC = 0.05
        const val MAX_POINTS = 2000
        val EMPTY = RealtimeCurves()
    }
}

/**
 * 标准读写（[BenchmarkMode.STANDARD_READ_WRITE]）**单个阶段**的采样缓存。
 *
 * 标准读写是「阶段 1 纯读 → 阶段 2 纯写」两段绝对串行的测量：每个阶段都有自己的时间原点
 * （从 0 s 起算）与自己的目标时长 / 容量。若把两阶段样本塞进同一条时间轴，写阶段的时间会
 * 跳回 0 与读阶段重叠，曲线互相穿插而不可读；因此两阶段各存一份，横轴永不相交。
 */
data class PhaseCurve(
    /** 阶段内时间（s），从 0.0 起算 */
    val timeSec: List<Double> = emptyList(),
    /** 该阶段吞吐（MB/s） */
    val bwMbps: List<Double> = emptyList(),
    /** 该阶段响应延迟（μs） */
    val latUs: List<Double> = emptyList(),
    /** 该阶段累计完成容量（MB）；容量模式用作横轴 */
    val capacityMb: List<Double> = emptyList()
) {
    val sampleCount: Int get() = timeSec.size

    /** 追加一个采样点：节流与抽稀规则与 [RealtimeCurves] 完全一致 */
    fun append(tSec: Double, bw: Double, lat: Double, capMb: Double): PhaseCurve {
        val last = timeSec.lastOrNull()
        if (last != null && tSec - last < RealtimeCurves.MIN_APPEND_INTERVAL_SEC) return this
        var t = timeSec + tSec
        var b = bwMbps + bw
        var l = latUs + lat
        var c = capacityMb + capMb
        if (t.size > RealtimeCurves.MAX_POINTS) {
            t = t.filterIndexed { i, _ -> i % 2 == 0 }
            b = b.filterIndexed { i, _ -> i % 2 == 0 }
            l = l.filterIndexed { i, _ -> i % 2 == 0 }
            c = c.filterIndexed { i, _ -> i % 2 == 0 }
        }
        return PhaseCurve(timeSec = t, bwMbps = b, latUs = l, capacityMb = c)
    }

    companion object {
        val EMPTY = PhaseCurve()
    }
}

/**
 * 标准读写的两阶段实时曲线：读阶段与写阶段**彻底隔离**。
 *
 * 界面据此渲染四张互不干扰的独立图表（读带宽 / 写带宽 / 读时延 / 写时延）。
 * 各阶段只取自己的字段：读阶段只吃 [RealtimeMetric.readMbps] 与
 * [RealtimeMetric.readAvgLatencyUs]，写阶段只吃 [RealtimeMetric.writeMbps] 与
 * [RealtimeMetric.writeAvgLatencyUs]，因此读的样本永远不会混进写的表（反之亦然）。
 */
data class StandardPhaseCurves(
    val readPhase: PhaseCurve = PhaseCurve.EMPTY,
    val writePhase: PhaseCurve = PhaseCurve.EMPTY
) {
    val sampleCount: Int get() = readPhase.sampleCount + writePhase.sampleCount

    fun appendRead(metric: RealtimeMetric, tSec: Double): StandardPhaseCurves = copy(
        readPhase = readPhase.append(
            tSec, metric.readMbps, metric.readAvgLatencyUs, metric.bytesDone / (1024.0 * 1024.0)
        )
    )

    fun appendWrite(metric: RealtimeMetric, tSec: Double): StandardPhaseCurves = copy(
        writePhase = writePhase.append(
            tSec, metric.writeMbps, metric.writeAvgLatencyUs, metric.bytesDone / (1024.0 * 1024.0)
        )
    )

    companion object {
        val EMPTY = StandardPhaseCurves()
    }
}

/**
 * 实时样本落到哪条曲线上。
 *
 * 标准读写按阶段分流到 [StandardPhaseCurves] 的读 / 写缓冲；
 * 混合并发（读写同刻发生、共享同一条时间轴）与内置算力内核沿用单一缓存 [RealtimeCurves]。
 */
private enum class CurveSlot { SINGLE, READ_PHASE, WRITE_PHASE }

/**
 * 档位切换阶段（需求 F1/A3：保存成功、待应用、重启中、已验证生效、回退必须是不同的状态，
 * 不允许用一个布尔值代表全流程）。
 *
 * 关键语义边界：SAVED_PENDING_RESTART 只代表「已落盘、等重启」，**不代表新档已生效**；
 * 只有 VERIFIED 才代表新进程确实按新档加载成功。
 */
enum class CpuProfileSwitchStage {
    /** 无切换请求 */
    IDLE,
    /** 引擎尚未加载：已记录，下次加载即生效（无需重启） */
    SAVED_NEXT_LOAD,
    /** 已保存，需要重启才能生效（重启尚未发出） */
    SAVED_PENDING_RESTART,
    /** 重启请求已发出，等待新进程验证 */
    RESTART_REQUESTED,
    /** 新进程已按新档加载并确认一致 */
    VERIFIED,
    /** 加载值与落盘值不一致（本 ABI 无该产物 / 被裁剪）：待应用保留，可一键回退 */
    FALLBACK,
    /** 落盘失败：保持旧档，不重启 */
    SAVE_FAILED
}

/** 上一个「已验证生效」的档位（一键回退目标）持久化键 */
private const val KEY_CPU_PROFILE_VERIFIED = "cpu_profile_verified_v1"

/** 改档位后的重启去重窗口（需求 F2：防抖/去重，防连点重启风暴） */
private const val RESTART_DEBOUNCE_MS = 500L

data class UiState(
    val hardwareInfo: HardwareStorageInfo,
    val storageTargets: List<StorageTarget>,
    val selectedTarget: StorageTarget?,
    val availableEngines: List<String>,
    val selectedEngine: String,
    val currentSpec: IometerAccessSpec,
    /** 全局开关：专业模式（决定是否走 root 手段与硬件详情） */
    val professionalMode: Boolean = false,
    /** 全局开关：缓存是否参与（true=含缓存，false=无缓存参与） */
    val cacheParticipates: Boolean = true,
    /** 全局设置：性能曲线采样档位（ms），合法值见 IometerAccessSpec.SAMPLE_INTERVAL_OPTIONS_MS */
    val sampleIntervalMs: Int = IometerAccessSpec.DEFAULT_SAMPLE_INTERVAL_MS,
    /**
     * 全局设置：CPU 定向调优偏好（传给原生 configure 的 profilePreference 原文）。
     * 合法值来自 ui/components/CpuProfileSelector.kt 的档位目录；默认 "baseline"。
     * 已取消自动识别（需求方裁定）：不存在 "auto" 取值，纯手动选择。
     */
    val cpuProfilePreference: String = "baseline",
    /** 以下均为**原生权威值**（DynamoNativeBridge），UI 只展示、不做任何推断 */
    val cpuProfileActive: String = "",
    val cpuProfileActiveMcpu: String = "",
    /** 本次加载**实际尝试**的档（可能是被红线裁剪后的候选），与 active 不同即降级 */
    val cpuProfileRequested: String = "",
    val cpuProfileAbi: String = "",
    val cpuProfileDeviceMax: String = "",
    val cpuProfileFallback: Boolean = false,
    val cpuProfileNote: String = "",
    /** 已加载的档位与设置不一致 → 设置需重启进程才生效（原生判定） */
    val cpuProfilePendingRestart: Boolean = false,
    /** 档位切换阶段（见 CpuProfileSwitchStage）：保存 / 待应用 / 重启中 / 已验证 分开表达 */
    val cpuProfileSwitchStage: CpuProfileSwitchStage = CpuProfileSwitchStage.IDLE,
    /** 已落盘的档位（用户意图） */
    val cpuProfileSaved: String = "baseline",
    /** 已保存但**本进程未生效**的档位（null = 无待应用） */
    val cpuProfilePending: String? = null,
    /** 上一次「加载后确认一致」的档位，用于一键回退 */
    val cpuProfileLastVerified: String? = null,
    /** 原生判定「本机可安全选择」的档位；不在其中的 UI 置灰禁用 */
    val cpuProfileAvailable: List<String> = emptyList(),
    /**
     * 原生逐档元数据（编译期宏生成，杜绝手工表漂移）：档位 → -mtune 目标 + 本 ABI 是否随包提供。
     * 编译器/优化等级/目标 ABI 对所有档按构造相同（同一次 CMake 配置），故不逐档。
     */
    val cpuProfileCatalog: List<EngineProfileSpec> = emptyList(),
    /** 生效变体的**工具链来源**（原生编译期宏 ccOrigin）：如「NDK 30.0.16248370 内置 Clang」 */
    val cpuProfileCcOrigin: String = "",
    val benchmarkState: BenchmarkState = BenchmarkState.IDLE,
    /** 需要用户二次确认的待启动用例（PC 级内置档位） */
    val pendingConfirmSpec: IometerAccessSpec? = null,
    val realtimeMetric: RealtimeMetric = RealtimeMetric(),
    /** 测试进行中的实时散点缓存（专业模式；结束后由 detail 的完整曲线覆盖） */
    val realtimeCurves: RealtimeCurves = RealtimeCurves.EMPTY,
    /** 标准读写两阶段的独立实时曲线（读 / 写彻底隔离，界面渲染四张独立图表） */
    val standardCurves: StandardPhaseCurves = StandardPhaseCurves.EMPTY,
    /**
     * **本次**运行的最终 summary（开始置 null，结束写入）。
     *
     * 界面判断「结束后面板」必须用这一条，而不是 `history.firstOrNull()`：
     * history 是累积的、测试开始时不清理，上一轮的 summary 会让次轮一开始就
     * 命中 detail != null 分支，于是显示旧结果、实时散点卡也不再出现。
     * history 只服务「历史报告」页。
     */
    val currentRunSummary: BenchmarkSummary? = null,
    /**
     * 整轮进度：当前阶段序号（0 起）与阶段总数。
     * 标准读写是「先读后写」两次独立测量，每阶段的 elapsedSeconds 都从 0 重算，
     * 进度条要按 (phaseIndex + 当前阶段比例) / phaseCount 折算才不会走到底又回退。
     */
    val phaseIndex: Int = 0,
    val phaseCount: Int = 1,

    val statusMessage: String = "就绪",
    /**
     * 需要**弹窗强提示**的错误（当前仅用于专业模式/root 相关失败）。
     *
     * 用独立字段而不是只写 [statusMessage]：状态栏文字在长页面上经常被滚出视野，
     * 实测"专业模式 + 绕过缓存但无 root"就被当成"没有任何报错"。
     * UI 消费后必须置回 null（一次性提示）。
     */
    val rootError: String? = null,
    val history: List<BenchmarkSummary> = emptyList()
)

class BenchmarkViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * CPU 定向调优偏好必须在**引擎（原生库）加载之前**告知原生：
     * 同 ABI 的 .so 一旦加载就无法可靠卸载/替换，之后只能重启进程才换档。
     *
     * Kotlin 属性按声明顺序求值，所以这里刻意放在引擎对象之前 —— 这是 UI 侧能做到的最早时机。
     * 返回值语义（原生契约）：true = 已记录，下次加载即生效；false = 引擎已加载，本次不改变本进程行为。
     *
     * 持久化：与 SpecStore 用同一个 SharedPreferences 文件（`ufs_bench_spec`）里的独立 key，
     * 不动 model 层；若后续由 SpecStore 统一收编，迁移时读同一个 key 即可。
     */
    private val initialCpuProfile = run {
        val prefs = try {
            application.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }
        val stored = try { prefs?.getString(KEY_CPU_PROFILE, null) } catch (_: Exception) { null }
        // 默认「公平基线」：最保守、对所有设备（含异构 SoC 小核）安全，
        // 定向调优是进阶选项，必须由用户显式选择。
        // 新安装或读不到偏好时一律按 baseline 展示，与原生默认保持一致。
        val pref = stored ?: "baseline"
        // 校验必须用**原生契约**，不能用 UI 的 CPU_PROFILE_OPTIONS：
        // 后者只有六个 NDK 档（baseline/x1…x925），实验档在**另一个列表**
        // EXPERIMENTAL_PROFILE_OPTIONS（oryon2/oryon1/c1ultra）里，
        // 于是持久化下来的 "oryon2" 会在这里被归一化成 baseline ——
        // 实测现象：设置页明明选了 Oryon-2、prefs 里也确实写着 cpu_profile_preference=oryon2，
        // 但重启后原生自述串仍是 engine_profile=[baseline]（mcpu=generic），
        // 定向调优库/插桩库永远加载不到（PGO 采集因此一个 .profraw 都拿不到）。
        // isKnownPreference 是 configure() 与联调通道共用的同一判定，是档位合法性的单一事实源。
        val safe = if (com.ufs.bench.core.DynamoNativeBridge.isKnownPreference(pref)) pref else "baseline"
        // 只有用户**做过明确选择**（prefs 里存在 key）时才 configure：
        //  · 未选择时不覆盖原生默认，原生的联调通道（debug.ufsbench.profile 等）仍然有效；
        //  · 已选择时必须 configure，否则会出现「设置页显示 X、实际加载 Y」的错位。
        if (stored != null) {
            try {
                com.ufs.bench.core.DynamoNativeBridge.configure(safe)
            } catch (_: Throwable) {
                // 原生库异常不应阻断 UI：档位按原样展示，读取属性时再兜底
            }
        }
        safe
    }

    private val dynamoEngine = DynamoEngineImpl().apply {
        availableMemoryBytesProvider = { readAvailableMemoryBytes() }
        rootStateProvider = { com.ufs.bench.hardware.StorageDetector.checkRootAccess() }
    }
    private val fioEngine = FioEngineImpl(application)

    /** 设备物理内存总量（字节） */
    private fun readTotalMemoryBytes(): Long {
        return try {
            val am = getApplication<Application>().getSystemService(android.content.Context.ACTIVITY_SERVICE)
                    as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem
        } catch (_: Exception) {
            0L
        }
    }

    /** 设备当前可用内存（字节），供 SPEC PC 级内置档位做内存预检 */
    private fun readAvailableMemoryBytes(): Long {
        return try {
            val am = getApplication<Application>().getSystemService(android.content.Context.ACTIVITY_SERVICE)
                    as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.availMem
        } catch (_: Exception) {
            0L
        }
    }

    /** 当前存储目标目录剩余可用空间（字节），供大容量纯读动态决定准备文件大小 */
    private fun readAvailableDiskBytes(path: String): Long {
        return try {
            val stat = android.os.StatFs(path)
            stat.availableBytes
        } catch (_: Exception) {
            1024L * 1024L * 1024L
        }
    }

    private val _uiState = MutableStateFlow(
        run {
            StorageDetector.appContext = application
            sweepStaleProbeFiles()
            val prof = com.ufs.bench.model.SpecStore.loadProfessionalMode(application)
            val cachePart = com.ufs.bench.model.SpecStore.loadCacheParticipates(application)
            // 采样档位随用例一起持久化，退出重进要恢复上次选择
            val restoredSpec = com.ufs.bench.model.SpecStore.load(application)
            val initialSpec = restoredSpec ?: PresetSuites.CDM_PRESETS.first()
            val hw = StorageDetector.detectHardwareInfo(useRoot = prof)
            val targets = StorageDetector.detectStorageTargets(application)
            val engines = mutableListOf("Iometer Dynamo（内置引擎）")
            if (fioEngine.isAvailable) {
                engines.add("FIO Engine")
            }
            UiState(
                hardwareInfo = hw,
                storageTargets = targets,
                selectedTarget = targets.firstOrNull(),
                availableEngines = engines,
                selectedEngine = engines.first(),
                // 恢复上次使用的配置（含缓存模式 / 测试手段 / 终止准则等），
                // 否则退出重进会退回默认的"含缓存 + 常规模式"
                currentSpec = initialSpec,
                professionalMode = prof,
                cacheParticipates = cachePart,
                sampleIntervalMs = initialSpec.sampleIntervalMs,
                cpuProfilePreference = initialCpuProfile,
                cpuProfileActive = nativeActiveProfile(),
                cpuProfileActiveMcpu = nativeActiveMcpu(),
                cpuProfileRequested = nativeRequestedProfile(),
                cpuProfileAbi = nativeAbi(),
                cpuProfileDeviceMax = nativeDeviceMaxProfile(),
                cpuProfileFallback = nativeProfileFallback(),
                cpuProfileNote = nativeProfileNote(),
                cpuProfilePendingRestart = nativePendingRestart(),
                cpuProfileAvailable = nativeAvailableProfiles(),
                cpuProfileCatalog = nativeProfileCatalog(),
                cpuProfileCcOrigin = nativeCcOrigin()
            )
        }
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // 需求 F1/A3：启动即结算一次档位状态机（落盘值 vs 原生实际加载值 vs 上次已验证），
        // 让「保存了但没生效」和「本 ABI 没这个档」成为两种不同状态，而不是笼统一句「待重启」。
        runCatching { refreshCpuProfileState() }
            .onFailure { AppLogger.w("CpuProfile", "档位状态刷新失败：" + (it.message ?: it.toString())) }
    }

    private var benchmarkJob: Job? = null

    private fun getCurrentEngine(): IBenchmarkEngine {
        return if (_uiState.value.selectedEngine.contains("FIO")) {
            fioEngine
        } else {
            dynamoEngine
        }
    }

    fun selectTarget(target: StorageTarget) {
        _uiState.update { it.copy(selectedTarget = target) }
    }

    fun selectEngine(engine: String) {
        _uiState.update { it.copy(selectedEngine = engine) }
    }

    /** 快速切换当前 内置微基准用例的内置档位 */
    fun setSpecTier(level: Int) {
        _uiState.update { it.copy(currentSpec = it.currentSpec.copy(specTier = level)) }
        persistSpec(_uiState.value.currentSpec)
    }

    fun selectSpec(spec: IometerAccessSpec) {
        _uiState.update { it.copy(currentSpec = spec) }
        persistSpec(spec)
    }

    /** 切换全局缓存参与：同步写入当前用例的 cacheMode 并落盘 */
    fun setCacheParticipates(participates: Boolean) {
        val mode = if (participates) com.ufs.bench.model.CacheMode.WITH_CACHE
                   else com.ufs.bench.model.CacheMode.NO_CACHE
        _uiState.update {
            it.copy(
                cacheParticipates = participates,
                currentSpec = it.currentSpec.copy(cacheMode = mode)
            )
        }
        com.ufs.bench.model.SpecStore.saveCacheParticipates(getApplication(), participates)
        persistSpec(_uiState.value.currentSpec)
    }

    /**
     * 切换全局采样档位（5/10/25/50/75/100 ms）。
     * 非法值回落到契约默认档，避免脏值传进原生层；同时写入当前用例并落盘。
     */
    fun setSampleInterval(ms: Int) {
        val safe = IometerAccessSpec.normalizeSampleIntervalMs(ms)
        val spec = _uiState.value.currentSpec.copy(sampleIntervalMs = safe)
        _uiState.update { it.copy(sampleIntervalMs = safe, currentSpec = spec) }
        persistSpec(spec)
    }

    // ---------- CPU 定向调优（原生权威值：只读展示，异常一律兜底，不阻断 UI） ----------

    private fun nativeActiveProfile(): String =
        try { com.ufs.bench.core.DynamoNativeBridge.activeProfile } catch (_: Throwable) { "" }

    private fun nativeActiveMcpu(): String =
        try { com.ufs.bench.core.DynamoNativeBridge.activeMcpu } catch (_: Throwable) { "" }

    private fun nativeRequestedProfile(): String =
        try { com.ufs.bench.core.DynamoNativeBridge.requestedProfile } catch (_: Throwable) { "" }

    private fun nativeAbi(): String =
        try { com.ufs.bench.core.DynamoNativeBridge.abi } catch (_: Throwable) { "" }

    private fun nativeDeviceMaxProfile(): String =
        try { com.ufs.bench.core.DynamoNativeBridge.deviceMaxProfile } catch (_: Throwable) { "" }

    private fun nativeProfileFallback(): Boolean =
        try { com.ufs.bench.core.DynamoNativeBridge.profileFallback } catch (_: Throwable) { false }

    private fun nativeProfileNote(): String =
        try { com.ufs.bench.core.DynamoNativeBridge.profileNote } catch (_: Throwable) { "" }

    private fun nativePendingRestart(): Boolean =
        try { com.ufs.bench.core.DynamoNativeBridge.pendingRestart } catch (_: Throwable) { false }

    private fun nativeAvailableProfiles(): List<String> =
        try { com.ufs.bench.core.DynamoNativeBridge.availableProfiles() } catch (_: Throwable) { emptyList() }

    /** 原生逐档元数据（-mtune 目标、本 ABI 是否随包提供） */
    private fun nativeProfileCatalog(): List<EngineProfileSpec> =
        try { com.ufs.bench.core.DynamoNativeBridge.profileCatalog() } catch (_: Throwable) { emptyList() }

    /** 生效变体的工具链来源（原生权威值；读不到就空串，UI 显示「—」，不猜） */
    private fun nativeCcOrigin(): String =
        try { com.ufs.bench.core.DynamoNativeBridge.loadState.ccOrigin } catch (_: Throwable) { "" }

    /**
     * 切换 CPU 定向调优档位（全局设置）。
     *
     * 只改「请求」：是否本进程立刻生效由原生决定——库若已加载，需重启进程才切换，
     * 界面上以 `cpuProfilePendingRestart` / 「已请求 vs 生效中」如实呈现，绝不静默。
     * 合法取值只认档位目录（与原生 PROFILES 一致），非法值直接忽略。
     */
    fun setCpuProfile(preference: String): Boolean {
        if (!com.ufs.bench.core.DynamoNativeBridge.isKnownPreference(preference)) return false
        // 原生契约：true = 引擎尚未加载，下次加载即生效；false = 引擎已加载，换档必须重启进程。
        // 需求「更改调优自动保存并重启」：这里先把档位**保存**下来，返回值作为「需要重启」的信号，
        // 由 UI 侧触发自动重启（不再提示用户手动重启）。
        val takesEffectAtNextLoad = try {
            AppLogger.i("CpuProfile", "请求档位 -> " + preference)
            com.ufs.bench.core.DynamoNativeBridge.configure(preference)
        } catch (_: Throwable) {
            true
        }
        // 必须 **commit()（同步落盘）**，不能用 apply()：本函数返回后 UI 会立刻 exit(0) 重启，
        // apply() 的异步写在进程消失时可能还没落盘 —— 实测现象就是「档位点了、重启了，但没生效」。
        val persisted = try {
            profilePrefs().edit().putString(KEY_CPU_PROFILE, preference).commit()
        } catch (_: Exception) {
            false
        }
        // 落盘失败：**不得**进入重启流程，也不得显示「已保存」（任务书 §4.5 不变量 8）
        if (!persisted) {
            AppLogger.e("CpuProfile", "档位落盘失败（commit=false）：保持旧档，不重启")
            _uiState.update {
                it.copy(
                    cpuProfileSwitchStage = CpuProfileSwitchStage.SAVE_FAILED,
                    statusMessage = "档位保存失败：请检查存储空间后重试（当前仍为 " + it.cpuProfileActive + "）"
                )
            }
            return false
        }
        AppLogger.i("CpuProfile", "档位已保存=" + preference + " 需重启=" + !takesEffectAtNextLoad)
        _uiState.update {
            it.copy(
                cpuProfileSaved = preference,
                cpuProfilePending = preference,
                cpuProfileSwitchStage = if (takesEffectAtNextLoad) CpuProfileSwitchStage.SAVED_NEXT_LOAD
                                        else CpuProfileSwitchStage.SAVED_PENDING_RESTART,

                cpuProfileActive = nativeActiveProfile(),
                cpuProfileActiveMcpu = nativeActiveMcpu(),
                cpuProfileRequested = nativeRequestedProfile(),
                cpuProfileAbi = nativeAbi(),
                cpuProfileDeviceMax = nativeDeviceMaxProfile(),
                cpuProfileFallback = nativeProfileFallback(),
                cpuProfileNote = nativeProfileNote(),
                cpuProfilePendingRestart = nativePendingRestart(),
                cpuProfileAvailable = nativeAvailableProfiles(),
                cpuProfileCatalog = nativeProfileCatalog(),
                cpuProfileCcOrigin = nativeCcOrigin()
            )
        }
        return !takesEffectAtNextLoad
    }

    /**
     * 是否正在跑一轮测试。
     * 自动重启（改档位生效）必须避开压测过程：半路重启会把这一轮打断并丢结果，
     * 因此测试进行中只保存档位、不重启，留待本轮结束后随下次启动生效。
     */
    fun isBusy(): Boolean {
        val s = _uiState.value.benchmarkState
        return s == BenchmarkState.PREPARING || s == BenchmarkState.RUNNING
    }

    // ================= 档位切换状态机（需求 F1/A3/F2） =================

    /** 档位持久化：与 SpecStore 同一文件、独立 key（既有口径不变） */
    private fun profilePrefs() = getApplication<Application>()
        .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    /** 上次重启请求（目标 + 时间）：同一目标在窗口内重复点击只重启一次（需求 F2 去重） */
    private var lastRestartTarget: String? = null
    private var lastRestartRequestAt = 0L

    /**
     * 是否应当为这次切换发起重启（防抖/去重）。
     * 同一目标窗口内重复点击 → false；换目标立即放行（用户改主意应当马上响应）。
     */
    fun shouldRequestRestart(target: String): Boolean {
        val now = System.currentTimeMillis()
        val dup = target == lastRestartTarget && (now - lastRestartRequestAt) < RESTART_DEBOUNCE_MS
        if (dup) {
            AppLogger.i("CpuProfile", "重启请求被去重（" + target + "，窗口内重复点击）")
            return false
        }
        lastRestartTarget = target
        lastRestartRequestAt = now
        _uiState.update { it.copy(cpuProfileSwitchStage = CpuProfileSwitchStage.RESTART_REQUESTED) }
        return true
    }

    /** 自重启跳板不可用：**不自杀**，退化为「下次启动生效」并如实回写状态（需求 F1 失败路径） */
    fun onRestartUnavailable(reason: String) {
        AppLogger.w("CpuProfile", "自动重启不可用：" + reason)
        _uiState.update {
            it.copy(
                cpuProfileSwitchStage = CpuProfileSwitchStage.SAVED_PENDING_RESTART,
                statusMessage = "档位已保存，但自动重启未成功：请手动重启一次应用以生效"
            )
        }
    }

    /**
     * 刷新档位状态机。
     * saved  = prefs 落盘值（用户意图）
     * active = 原生**实际加载**的档位（每个 .so 的编译期自述，权威）
     * pending = saved ≠ active 时的待应用；lastVerified = 上次「加载值与落盘值一致」的档位
     */
    private fun refreshCpuProfileState() {
        val stored = try { profilePrefs().getString(KEY_CPU_PROFILE, null) } catch (_: Exception) { null }
        val active = nativeActiveProfile()
        val saved = stored ?: active.ifEmpty { "baseline" }
        val consistent = stored != null && active.isNotEmpty() && stored == active
        if (consistent) {
            try {
                profilePrefs().edit().putString(KEY_CPU_PROFILE_VERIFIED, active).commit()
            } catch (_: Exception) {
            }
        }
        val verified = if (consistent) active else try {
            profilePrefs().getString(KEY_CPU_PROFILE_VERIFIED, null)
        } catch (_: Exception) {
            null
        }
        val pending = if (active.isNotEmpty() && saved != active) saved else null
        // 本 ABI 是否随包提供该档：没有这个 .so 时重启也没用，属「降级」而不是「等重启」
        // 本 ABI 是否随包提供该档：**目录里没有 = 没有这个 .so**（x86_64 只出 baseline），
        // 此时重启也不会生效，必须报「降级」而不是「等重启」，否则界面在骗人。
        // 只有目录整体读不到（原生异常）才返回 null，表示"无法判定"，保持保守的待重启语义。
        val packaged: Boolean? = try {
            val catalog = nativeProfileCatalog()
            if (catalog.isEmpty()) null
            else com.ufs.bench.core.DynamoNativeBridge.isProfilePackagedForThisAbi(saved)
        } catch (_: Throwable) {
            null
        }
        // 本进程根本加载不了这个档（ABI 切片没有 / ARM 翻译层）→ 重启也不会变，必须报降级。
        // 只有"目录与切片都支持、只是本进程还没换过来"才是等重启。
        val unavailable = try {
            com.ufs.bench.core.DynamoNativeBridge.requestedUnavailable
        } catch (_: Throwable) {
            false
        }
        val stage = when {
            consistent -> CpuProfileSwitchStage.VERIFIED
            pending == null -> CpuProfileSwitchStage.IDLE
            unavailable -> CpuProfileSwitchStage.FALLBACK
            packaged == false -> CpuProfileSwitchStage.FALLBACK
            else -> CpuProfileSwitchStage.SAVED_PENDING_RESTART
        }
        _uiState.update {
            val inFlight = it.cpuProfileSwitchStage == CpuProfileSwitchStage.RESTART_REQUESTED
            it.copy(
                cpuProfileSaved = saved,
                cpuProfilePending = pending,
                cpuProfileLastVerified = verified,
                cpuProfileSwitchStage = if (inFlight) it.cpuProfileSwitchStage else stage
            )
        }
    }

    /** 一键回退到上一已验证档位（需求 F1：回退路径必须存在且可用）；返回目标档位供 UI 触发重启 */
    fun rollbackToLastVerified(): String? {
        val target = _uiState.value.cpuProfileLastVerified ?: return null
        AppLogger.i("CpuProfile", "回退到上一已验证档位=" + target)
        setCpuProfile(target)
        return target
    }

    /** 把 root 探测四态翻译成用户能行动的原因（需求 C2：失败原因必须可区分、可追踪） */
    private fun rootCauseText(p: StorageDetector.RootProbe): String = when (p) {
        StorageDetector.RootProbe.NO_SU -> "· 本机系统里没有可用的 su（设备未 root）"
        StorageDetector.RootProbe.DENIED -> "· su 已存在但被拒绝：请在 Root 管理器中允许本应用，然后重试"
        StorageDetector.RootProbe.TIMEOUT -> "· su 授权超时（弹窗可能未被处理）：请在 Root 管理器中授权后重试"
        StorageDetector.RootProbe.OK -> "· （探测已通过）"
    }

    /**
     * 切换全局专业模式。
     *
     * root 校验在**后台线程**执行且带超时（需求 C1/C2）；结果通过 uiState 反馈，
     * 主线程不再被 su 授权弹窗阻塞 —— 旧写法在授权弹窗未响应时会假死，用户当成「没有校验」。
     */
    fun setProfessionalMode(enabled: Boolean) {
        if (!enabled) {
            viewModelScope.launch { applyProfessionalMode(false) }
            return
        }
        _uiState.update { it.copy(statusMessage = "正在校验 Root 权限…") }
        viewModelScope.launch {
            val probe = StorageDetector.probeRootAsync()
            if (probe == StorageDetector.RootProbe.OK) {
                applyProfessionalMode(true)
            } else {
                AppLogger.w("ProfMode", "开启专业模式被拒：probe=" + probe)
                _uiState.update {
                    it.copy(
                        professionalMode = false,
                        statusMessage = "开启专业模式失败：未获得 Root 权限",
                        rootError = "无法开启专业模式。\n\n" +
                            "专业模式需要 Root 权限：一是调用 AOSP PerformanceBoost 语义" +
                            "（提升进程优先级 + 实时 I/O 调度类），二是在「无缓存参与」时执行 drop_caches " +
                            "清空内核页缓存。\n\n" +
                            "本次 su 调用未通过，原因：\n" + rootCauseText(probe) + "\n\n" +
                            "不使用专业模式也可以正常测试：普通模式会用 O_DIRECT 直接 I/O 并输出吞吐与延迟指标。"
                    )
                }
            }
        }
    }

    /** 真正落地专业模式开关（root 校验通过后调用；硬件重探是 I/O，放 IO 线程） */
    private suspend fun applyProfessionalMode(enabled: Boolean) {
        AppLogger.i("ProfMode", "专业模式 -> " + enabled)
        val priv = if (enabled) com.ufs.bench.model.RunPrivilege.PROFESSIONAL
                   else com.ufs.bench.model.RunPrivilege.NORMAL
        // 开启专业模式时用 root 重新探测硬件：普通身份读不到 /sys/block，
        // 之前缓存的「无法读取」会一直挂着，与专业模式的承诺不符。
        val hw = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            StorageDetector.detectHardwareInfo(useRoot = enabled)
        }
        _uiState.update {
            it.copy(
                professionalMode = enabled,
                hardwareInfo = hw,
                currentSpec = it.currentSpec.copy(privilege = priv)
            )
        }
        com.ufs.bench.model.SpecStore.saveProfessionalMode(getApplication(), enabled)
        persistSpec(_uiState.value.currentSpec)
    }

    /** UI 消费完 root 错误弹窗后清除，避免旋转/切页时重复弹出 */
    fun dismissRootError() {
        _uiState.update { it.copy(rootError = null) }
    }

    /** 把当前配置写入持久化存储 */
    private fun persistSpec(spec: IometerAccessSpec) {
        try {
            com.ufs.bench.model.SpecStore.save(getApplication(), spec)
        } catch (_: Exception) {
        }
    }

    // ---------- 测试进行中的实时散点累积（专业模式） ----------

    /** 当前阶段的标识；阶段切换时重置 X 轴原点，避免时间轴回退 */
    private var curvePhase: String = ""
    private var curvePhaseStartMs: Long = 0L

    /**
     * 把一条进度回调并入实时散点缓存。
     *
     * 原生层只在测试结束后给出完整曲线，所以进行中先按进度回调攒点；
     * 非专业模式不产出曲线，也就不必累积（省内存与重组开销）。
     */
    private fun UiState.withRealtime(
        metric: RealtimeMetric,
        phase: String,
        slot: CurveSlot = CurveSlot.SINGLE
    ): UiState {
        val timed = metric.copy(currentPhase = phase)
        if (!professionalMode) return copy(realtimeMetric = timed)
        val now = android.os.SystemClock.elapsedRealtime()
        if (phase != curvePhase) {
            curvePhase = phase
            curvePhaseStartMs = now
        }
        val tSec = (now - curvePhaseStartMs) / 1000.0
        return when (slot) {
            // 标准读写：读、写各进各的缓冲，各自从 0 s 起算，绝不共表共轴
            CurveSlot.READ_PHASE -> copy(
                realtimeMetric = timed,
                standardCurves = standardCurves.appendRead(timed, tSec)
            )
            CurveSlot.WRITE_PHASE -> copy(
                realtimeMetric = timed,
                standardCurves = standardCurves.appendWrite(timed, tSec)
            )
            // 混合并发与内置算力内核：读写同刻发生，共享一条时间轴
            CurveSlot.SINGLE -> copy(
                realtimeMetric = timed,
                realtimeCurves = realtimeCurves.append(timed, tSec)
            )
        }
    }

    /** 开始新一轮测量：清空实时散点与阶段计时 */
    private fun resetCurves(state: UiState): UiState {
        curvePhase = ""
        curvePhaseStartMs = 0L
        return state.copy(
            realtimeCurves = RealtimeCurves.EMPTY,
            standardCurves = StandardPhaseCurves.EMPTY,
            currentRunSummary = null,
            phaseIndex = 0,
            phaseCount = 1
        )
    }

    /** 选择预设但保留当前内置档位与全局采样档位（二者由独立控件控制，不被预设覆盖） */
    fun selectPresetKeepTier(preset: IometerAccessSpec): IometerAccessSpec {
        val merged = preset.copy(
            specTier = _uiState.value.currentSpec.specTier,
            sampleIntervalMs = _uiState.value.sampleIntervalMs
        )
        _uiState.update { it.copy(currentSpec = merged) }
        persistSpec(merged)
        return merged
    }

    fun startBenchmark(customSpec: IometerAccessSpec? = null) {
        // 专业模式是全局开关：启动前统一覆盖，避免残留旧的 privilege 值
        val base = customSpec ?: _uiState.value.currentSpec
        val spec = base.copy(
            privilege = if (_uiState.value.professionalMode)
                com.ufs.bench.model.RunPrivilege.PROFESSIONAL
            else com.ufs.bench.model.RunPrivilege.NORMAL,
            cacheMode = if (_uiState.value.cacheParticipates)
                com.ufs.bench.model.CacheMode.WITH_CACHE
            else com.ufs.bench.model.CacheMode.NO_CACHE,
            // 采样档位同为全局设置：换预设也不能把它带回默认档
            sampleIntervalMs = _uiState.value.sampleIntervalMs
        )
        persistSpec(spec)
        // 把生效后的配置同步回界面状态：否则 currentSpec 仍留着旧的 privilege/cacheMode，
        // 结果页与硬件卡片会据此判断"非专业模式"，从而隐藏整个专业面板。
        _uiState.update { it.copy(currentSpec = spec) }

        // PC / 服务器级内置档位：内存预检 + 二次确认（预检不通过也允许强制运行）
        if (spec.portedVersion && spec.tier.pcClass && !spec.forceRun) {
            val totalMb = readTotalMemoryBytes() / (1024 * 1024)
            val availMb = readAvailableMemoryBytes() / (1024 * 1024)
            val needMb = com.ufs.bench.model.SpecMemoryEstimator.estimateMb(spec)
            // needMb == 0：该档工作集由内核按可用内存一半自适应（拓展档的 PC 级规模），
            // 因此**不会**因内存不足中断 —— 不能再用 availMb > needMb 判成不足（那会误报警告）
            val fits = needMb <= 0 || availMb > needMb
            // 时长预估：作为主要告知项（内存充足时唯一的提示）
            val estMinutes = com.ufs.bench.model.SpecMemoryEstimator
                .estimateMinutes(spec, readAvailableMemoryBytes())
            val verdict = buildString {
                append(if (spec.portedVersion && spec.builtinKernelId >= 1000)
                    "强度 ${spec.officialTierName} 档（本基准非 SPEC 套件，档位只表示负载强度）。\n\n"
                else "官方 ${spec.officialTierName} 档是 SPEC 正式上报规模。\n\n")
                append("预计耗时：约 ${estMinutes} 分钟（真机估算，仅供参考）\n")
                if (!fits) {
                    // 仅在确实可能爆内存时才给内存溢出警告
                    append("本档预估需要内存：约 ${needMb} MB\n")
                    append("本机物理内存：约 ${totalMb} MB，当前可用：约 ${availMb} MB\n\n")
                    append("⚠️ 可用内存不足以容纳预估工作集，本档大概率无法完成")
                    append("（可能 OOM 或被系统强杀）。\n\n")
                    append("你可以：\n· 选「强制运行」跳过预检直接尝试（风险自负）\n· 或点「改用 低性能 档」")
                } else {
                    append(if (needMb <= 0)
                        "内存判定：本档工作集按可用内存一半自适应（PC 级规模受限后取上限），不会因内存不足中断。\n\n"
                    else "内存判定：可用内存足以容纳预估工作集，不会因内存不足中断。\n\n")
                    append("建议：测试期间保持应用在前台、连接充电器、关闭其他占用存储与 CPU 的应用。\n\n")
                    append("确认开始？")
                }
            }
            _uiState.update {
                it.copy(
                    pendingConfirmSpec = spec.copy(description = verdict),
                    statusMessage = if (fits) "等待确认：PC 级内置档位（内存充足）" else "等待确认：PC 级内置档位（内存不足，可强制）"
                )
            }
            return
        }

        AppLogger.i(
            "Run",
            "请求启动用例=" + spec.name + " mode=" + spec.mode +
                " block=" + spec.requestSizeBytes + " Q=" + spec.queueDepth +
                " T=" + spec.workerThreads + " cache=" + spec.cacheMode.code +
                " priv=" + spec.privilege.code + " sample=" + spec.sampleIntervalMs + "ms"
        )

        // 专业模式下绕过缓存必须有真实 root 权限（执行 drop_caches）；无 root 时直接报错拦截，不静默运行
        val target = _uiState.value.selectedTarget ?: return
        val engine = getCurrentEngine()

        benchmarkJob?.cancel()
        benchmarkJob = viewModelScope.launch {
            // 专业模式缺 root：在**执行层**拦下；探测带超时且不阻塞主线程（需求 C1/C2）。
            // 旧写法在主线程同步 su，授权弹窗未响应时界面假死，用户当成「没有校验、没有报错」。
            if (_uiState.value.professionalMode) {
                val probe = StorageDetector.probeRootAsync()
                if (probe != StorageDetector.RootProbe.OK) {
                    val bypassCache = !_uiState.value.cacheParticipates
                    _uiState.update {
                        it.copy(
                            benchmarkState = BenchmarkState.ERROR,
                            statusMessage = if (bypassCache)
                                "执行失败：专业模式 + 无缓存参与需要 Root 权限（drop_caches）"
                            else
                                "执行失败：专业模式需要 Root 权限",
                            rootError = if (bypassCache)
                                "测试未开始：本机未获得 Root 权限。\n\n" +
                                    "你选择了「专业模式 + 无缓存参与」：这个组合要求执行 drop_caches " +
                                    "清空内核页缓存，否则读到的可能是缓存命中数据，结果不能代表闪存裸速。\n\n" +
                                    "本次 su 调用未通过，原因：\n" + rootCauseText(probe) + "\n\n" +
                                    "可选处理：\n" +
                                    "· 在 Root 管理器中授权本应用后重试\n" +
                                    "· 或关闭「专业模式」（普通模式用 O_DIRECT + fadvise(DONTNEED) 逼近裸速）\n" +
                                    "· 或打开「缓存参与」"
                            else
                                "测试未开始：本机未获得 Root 权限，无法启用专业模式。\n\n" +
                                    "本次 su 调用未通过，原因：\n" + rootCauseText(probe) + "\n\n" +
                                    "请先在 Root 管理器中授权本应用，或关闭设置页的「专业模式」。"
                        )
                    }
                    AppLogger.e("Run", "拦截启动：专业模式缺少 root（probe=" + probe + "）")
                    return@launch
                }
            }
            val testFile = File(target.path, PROBE_FILE_NAME).absolutePath

            try {
                // 1. 完整版 SPEC 工作负载为纯 CPU + 内存测量，不需要测试文件
                if (spec.portedVersion) {
                    _uiState.update {
                        resetCurves(
                            it.copy(
                                benchmarkState = BenchmarkState.RUNNING,
                                statusMessage = "正在启动内置基准：${spec.name}（强度 ${spec.officialTierName}）",
                                realtimeMetric = RealtimeMetric()
                            )
                        )
                    }
                } else if (spec.mode != BenchmarkMode.READ_ONLY) {
                    // 纯读走独立分支（见下方 READ_ONLY）：它自己会先写文件再读，
                    // 这里跳过以免重复预分配。
                    _uiState.update {
                        resetCurves(
                            it.copy(
                                benchmarkState = BenchmarkState.PREPARING,
                                statusMessage = "正在预分配测试数据空间（${spec.fileSizeMb} MB）…",
                                realtimeMetric = RealtimeMetric()
                            )
                        )
                    }

                    val prepOk = engine.prepareTargetFile(testFile, spec.fileSizeMb, spec.cacheMode.code)
                    if (!prepOk) {
                        _uiState.update {
                            it.copy(
                                benchmarkState = BenchmarkState.ERROR,
                                statusMessage = "文件预分配失败：" + testFile + "（目标 " + spec.fileSizeMb + " MB，可用空间与目录见终端日志）"
                            )
                        }
                        return@launch
                    }
                }

                when (spec.mode) {
                    BenchmarkMode.STANDARD_READ_WRITE -> {
                        // 标准模式: 依次执行读、写两个阶段
                        // 阶段 1: 读取测试
                        _uiState.update {
                            it.copy(
                                benchmarkState = BenchmarkState.RUNNING,
                                statusMessage = "正在测试读取（阶段 1/2）…",
                                phaseIndex = 0,
                                phaseCount = 2
                            )
                        }

                        val readSpec = spec.copy(percentRead = 100)
                        var readMetric = RealtimeMetric()
                        engine.runBenchmark(testFile, readSpec)
                            .catch { e ->
                                android.util.Log.e("UFSBench", "读测试异常: " + (e.message ?: "?"), e)
                                _uiState.update { it.copy(benchmarkState = BenchmarkState.ERROR, statusMessage = "读测试异常: ${e.message}") }
                            }
                            .collect { metric ->
                                readMetric = metric
                                _uiState.update {
                                    it.withRealtime(metric, "读取测试中", CurveSlot.READ_PHASE)
                                }
                            }

                        val readSummary = engine.getLastSummary()
                        if (readSummary != null && !readSummary.success) {
                            _uiState.update {
                                it.copy(
                                    benchmarkState = BenchmarkState.ERROR,
                                    statusMessage = "读取阶段失败：" + (readSummary.errorMessage ?: "执行异常"),
                                    currentRunSummary = readSummary
                                )
                            }
                            return@launch
                        }
                        val finalReadBw = readSummary?.readMbps ?: readMetric.readMbps
                        val finalReadIops = readSummary?.readIops ?: readMetric.readIops
                        val finalReadLat = readSummary?.avgLatencyUs ?: readMetric.avgLatencyUs

                        // 阶段 2: 写入测试
                        _uiState.update {
                            it.copy(
                                benchmarkState = BenchmarkState.RUNNING,
                                statusMessage = "正在测试写入（阶段 2/2）…",
                                phaseIndex = 1,
                                phaseCount = 2
                            )
                        }

                        val writeSpec = spec.copy(percentRead = 0)
                        var writeMetric = RealtimeMetric()
                        engine.runBenchmark(testFile, writeSpec)
                            .catch { e ->
                                android.util.Log.e("UFSBench", "写测试异常: " + (e.message ?: "?"), e)
                                _uiState.update { it.copy(benchmarkState = BenchmarkState.ERROR, statusMessage = "写测试异常: ${e.message}") }
                            }
                            .collect { metric ->
                                writeMetric = metric
                                _uiState.update { st ->
                                    st.withRealtime(
                                        // 读阶段最终成绩只回填到「实时指标」展示；曲线按阶段各进各的缓冲，
                                        // 写阶段的表只吃 writeMbps / writeAvgLatencyUs，读的样本不会混进来。
                                        metric.copy(readMbps = finalReadBw, readIops = finalReadIops),
                                        "写入测试中",
                                        CurveSlot.WRITE_PHASE
                                    )
                                }
                            }

                        val writeSummary = engine.getLastSummary()
                        if (writeSummary != null && !writeSummary.success) {
                            _uiState.update {
                                it.copy(
                                    benchmarkState = BenchmarkState.ERROR,
                                    statusMessage = "写入阶段失败：" + (writeSummary.errorMessage ?: "执行异常"),
                                    currentRunSummary = writeSummary
                                )
                            }
                            return@launch
                        }
                        val finalWriteBw = writeSummary?.writeMbps ?: writeMetric.writeMbps
                        val finalWriteIops = writeSummary?.writeIops ?: writeMetric.writeIops
                        val finalWriteLat = writeSummary?.avgLatencyUs ?: writeMetric.avgLatencyUs

                        // 汇总读写两阶段总成绩
                        val combinedSummary = BenchmarkSummary(
                            spec = spec,
                            engineName = engine.name,
                            success = (readSummary?.success ?: true) && (writeSummary?.success ?: true),
                            readMbps = finalReadBw,
                            writeMbps = finalWriteBw,
                            readIops = finalReadIops,
                            writeIops = finalWriteIops,
                            avgLatencyUs = if (finalReadLat > 0 && finalWriteLat > 0) (finalReadLat + finalWriteLat) / 2.0 else maxOf(finalReadLat, finalWriteLat),
                            readAvgLatencyUs = finalReadLat,
                            writeAvgLatencyUs = finalWriteLat,
                            p99LatencyUs = maxOf(readSummary?.p99LatencyUs ?: 0.0, writeSummary?.p99LatencyUs ?: 0.0),
                            maxLatencyUs = maxOf(readSummary?.maxLatencyUs ?: 0.0, writeSummary?.maxLatencyUs ?: 0.0),
                            // 手段披露必须从两个阶段汇总带出，否则前端拿不到实际 I/O 通道
                            ioMethod = readSummary?.ioMethod?.takeIf { it.isNotEmpty() }
                                ?: writeSummary?.ioMethod ?: "",
                            privilegeMethod = readSummary?.privilegeMethod?.takeIf { it.isNotEmpty() }
                                ?: writeSummary?.privilegeMethod ?: "",
                            verifyDetail = listOfNotNull(
                                readSummary?.verifyDetail, writeSummary?.verifyDetail
                            ).filter { it.isNotEmpty() }.distinct().joinToString(" / "),
                            // 校验**结论**必须与校验明细一起从两阶段汇总带出。
                            // 之前只带了 verifyDetail、漏了 verified，于是它保持数据类默认值 false ——
                            // 真机实测同一屏上出现"数据完整性校验：未通过"与"数据校验通过：…0 处错误"
                            // 自相矛盾（读+写两阶段合并路径独有）。
                            // 语义：只有**确实做了校验**的阶段参与判定；任一做了校验的阶段失败即判失败；
                            // 两阶段都没做校验（如纯写）则不为"通过"，如实留 false。
                            verified = listOfNotNull(readSummary, writeSummary)
                                .filter { it.verifyDetail.isNotEmpty() }
                                .let { phases -> phases.isNotEmpty() && phases.all { it.verified } },
                            // 专业统计同样必须从两阶段汇总带出
                            totalBytesDone = (readSummary?.totalBytesDone ?: 0L) +
                                (writeSummary?.totalBytesDone ?: 0L),
                            stoppedByCapacity = readSummary?.stoppedByCapacity == true ||
                                writeSummary?.stoppedByCapacity == true,
                            // 读阶段耗时更短，取两阶段中信息更完整的一份详情
                            detail = readSummary?.detail ?: writeSummary?.detail,
                            // 写阶段是第二次独立测量，曲线从 0 秒起算；
                            // 带出这一份后，散点图才能把读 / 写画进同一张图（历史页同样适用）
                            writeDetail = writeSummary?.detail,
                            // CPU 档位（原生权威值）也必须从两阶段带出，否则标准读写的结果页 / 分享图
                            // 看不到「本结果由哪一档执行」，降级也会被吞掉 —— 两阶段用同一个已加载的库，
                            // 取值应当一致，按「先读后写」兜底即可。
                            engineProfile = readSummary?.engineProfile ?: writeSummary?.engineProfile ?: "",
                            engineMcpu = readSummary?.engineMcpu ?: writeSummary?.engineMcpu ?: "",
                            engineProfileRequested = readSummary?.engineProfileRequested
                                ?: writeSummary?.engineProfileRequested ?: "",
                            engineProfileFallback = (readSummary?.engineProfileFallback ?: false) ||
                                (writeSummary?.engineProfileFallback ?: false),
                            engineProfileNote = listOfNotNull(
                                readSummary?.engineProfileNote?.takeIf { it.isNotEmpty() },
                                writeSummary?.engineProfileNote?.takeIf { it.isNotEmpty() }
                            ).distinct().joinToString("；")
                        )

                        _uiState.update {
                            it.copy(
                                benchmarkState = BenchmarkState.COMPLETED,
                                statusMessage = "读写双向测试完成！",
                                realtimeMetric = RealtimeMetric(
                                    readMbps = finalReadBw,
                                    writeMbps = finalWriteBw,
                                    readIops = finalReadIops,
                                    writeIops = finalWriteIops
                                ),
                                history = listOf(combinedSummary) + it.history,
                                // 本次结果单独留一份：界面只认它，不再从累积的 history 里取
                                currentRunSummary = combinedSummary
                            )
                        }
                    }

                    BenchmarkMode.MIXED_SIMULTANEOUS, BenchmarkMode.BUILTIN_KERNEL -> {
                        val isSpec = spec.mode == BenchmarkMode.BUILTIN_KERNEL
                        val specLabel = "内置基准工作负载"
                        _uiState.update {
                            it.copy(
                                benchmarkState = BenchmarkState.RUNNING,
                                statusMessage = if (isSpec) "正在执行 $specLabel: ${spec.name}" else "正在执行独立读写混合压测 (${spec.percentRead}%读 / ${100 - spec.percentRead}%写)...",
                                phaseIndex = 0,
                                phaseCount = 1
                            )
                        }

                        engine.runBenchmark(testFile, spec)
                            .catch { e ->
                                android.util.Log.e("UFSBench", "混合/SPEC测试异常: " + (e.message ?: "?"), e)
                                _uiState.update { it.copy(benchmarkState = BenchmarkState.ERROR, statusMessage = "混合/SPEC测试异常: ${e.message}") }
                            }
                            .collect { metric ->
                                _uiState.update {
                                    it.withRealtime(
                                        metric,
                                        if (isSpec) "内置基准执行中" else "读写混合压测中"
                                    )
                                }
                            }

                        val summary = engine.getLastSummary()
                        if (summary != null) {
                            val methodNote = buildString {
                                if (summary.ioMethod.isNotEmpty()) append("｜I/O: ${summary.ioMethod}")
                                if (summary.privilegeMethod.isNotEmpty()) append("｜手段: ${summary.privilegeMethod}")
                            }
                            // 模拟器/虚拟机：跨设备比值不可比，完成语里也不报比值（与结果卡口径一致）
                            val onEmulator = _uiState.value.hardwareInfo.isEmulator
                            val doneMsg = when {
                                !isSpec -> "混合压测完成！$methodNote"
                                !summary.verified -> "内置基准完成，但输出校验未通过"
                                onEmulator -> "内置基准完成，输出校验通过（模拟器/虚拟机环境，不给出比值）"
                                else -> "内置基准完成，输出校验通过（比值 ${String.format("%.3f", summary.specRatio)}）"
                            }
                            _uiState.update {
                                it.copy(
                                    benchmarkState = if (isSpec && !summary.verified) BenchmarkState.ERROR else BenchmarkState.COMPLETED,
                                    statusMessage = doneMsg,
                                    history = listOf(summary) + it.history,
                                    currentRunSummary = summary
                                )
                            }
                        }
                    }

                    BenchmarkMode.READ_ONLY -> {
                        // 阶段 1/2: 写入测试数据文件（按大容量或充裕规格填充，打上可校验标记）
                        val availDiskMb = ((readAvailableDiskBytes(target.path) / (1024 * 1024)) - 512).coerceAtLeast(64L)
                        val targetMb = if (spec.stopCriterion == StopCriterion.CAPACITY) {
                            minOf(spec.targetCapacityMb.toLong(), availDiskMb).toInt().coerceAtLeast(64)
                        } else {
                            minOf(spec.fileSizeMb.toLong().coerceAtLeast(1024L), availDiskMb).toInt().coerceAtLeast(64)
                        }

                        _uiState.update {
                            resetCurves(
                                it.copy(
                                    benchmarkState = BenchmarkState.RUNNING,
                                    phaseIndex = 0,
                                    phaseCount = 2,
                                    statusMessage = "正在写入测试数据文件（${targetMb} MB，阶段 1/2）…",
                                    realtimeMetric = RealtimeMetric()
                                )
                            )
                        }

                        val prepOk = engine.prepareTargetFile(testFile, targetMb, spec.cacheMode.code)
                        if (!prepOk) {
                            _uiState.update {
                                it.copy(
                                    benchmarkState = BenchmarkState.ERROR,
                                    statusMessage = "文件写入失败，请检查剩余磁盘空间"
                                )
                            }
                            return@launch
                        }

                        // 阶段 2/2: 纯读测试与数据完整性校验
                        _uiState.update {
                            it.copy(
                                phaseIndex = 1,
                                phaseCount = 2,
                                statusMessage = "正在执行纯读测试与数据校验（阶段 2/2）…"
                            )
                        }
                        val readSpec = spec.copy(percentRead = 100, fileSizeMb = targetMb)
                        engine.runBenchmark(testFile, readSpec).collect { m ->
                            _uiState.update { it.withRealtime(m, "纯读测试与校验中") }
                        }
                        engine.getLastSummary()?.let { s ->
                            val doneMsg = if (s.verified) {
                                "纯读测试完成！${s.verifyDetail}"
                            } else if (s.verifyDetail.isNotEmpty()) {
                                "纯读测试完成，${s.verifyDetail}"
                            } else {
                                "纯读测试完成！"
                            }
                            _uiState.update {
                                it.copy(
                                    benchmarkState = if (s.verified || s.verifyDetail.isEmpty()) BenchmarkState.COMPLETED else BenchmarkState.ERROR,
                                    statusMessage = doneMsg,
                                    history = listOf(s) + it.history,
                                    currentRunSummary = s
                                )
                            }
                        }
                    }

                    BenchmarkMode.WRITE_ONLY -> {
                        _uiState.update { it.copy(phaseIndex = 0, phaseCount = 1) }
                        val writeSpec = spec.copy(percentRead = 0)
                        engine.runBenchmark(testFile, writeSpec).collect { m ->
                            _uiState.update { it.withRealtime(m, "写入测试中") }
                        }
                        engine.getLastSummary()?.let { s ->
                            _uiState.update { it.copy(benchmarkState = BenchmarkState.COMPLETED, history = listOf(s) + it.history, currentRunSummary = s) }
                        }
                    }
                }

            } finally {
                // 必须用 NonCancellable：job 被 cancel 之后，withContext 会在进入时
                // 就抛 CancellationException，清理代码根本不会执行
                // —— 实测手动终止后残留 67MB 探测文件。
                withContext(NonCancellable + Dispatchers.IO) {
                    // 删除结果**必须记录**（需求 ADD-LIFE-001：删除失败必须记录，避免残留持续占用空间）。
                    // 旧写法是 try { delete() } catch (_) {} —— 静默吞掉：删除失败时既没有日志也没有提示，
                    // 只能等下次启动清扫，而"下次启动"未必发生（实测手动终止后曾残留 67 MB 探测文件）。
                    try {
                        val f = File(testFile)
                        val existed = f.exists()
                        val ok = if (existed) f.delete() else true
                        AppLogger.i("Run", "清理探测文件 存在=" + existed + " 删除=" + ok + " 路径=" + testFile)
                        if (!ok) {
                            AppLogger.w("Run", "探测文件删除失败（将在下次启动清扫）：" + testFile)
                        }
                    } catch (e: Exception) {
                        AppLogger.w("Run", "清理探测文件异常：" + (e.message ?: e.toString()), e)
                    }
                }
                // PGO 采集：**一轮测试结束就把插桩 profile 落盘**。
                // 为什么不只挂"退出/重启"路径（真机实测教训）：
                //   · atexit 在 Android 上不可靠 —— 优雅 exit(0) 之后 files/pgo 依然是空的；
                //   · "重启"路径会被 isBusy() 守卫跳过（压测进行中只保存档位、不重启），
                //     于是采集脚本带着压测去切档时 flush 永远不会被调用，profile 一直采不到。
                // 放在 finally 里与 UI/重启时序完全解耦，且取消（手动终止）同样会走到这里。
                runCatching {
                    val ok = com.ufs.bench.core.DynamoNativeBridge.flushPgoProfile(
                        java.io.File(getApplication<Application>().filesDir, "pgo")
                    )
                    if (!ok) {
                        AppLogger.i("PGO", "本轮结束，但当前库不是插桩构建：未产出 profile（如实记录）")
                    }
                }
            }
        }
    }

    /**
     * 清扫历史残留的探测文件。
     *
     * finally 只能覆盖正常结束与主动取消；应用被强杀或崩溃时文件会留在磁盘上。
     * 启动时扫一遍所有可用存储目标，避免探测文件长期占用用户空间。
     */
    private fun sweepStaleProbeFiles() {
        try {
            for (t in StorageDetector.detectStorageTargets(getApplication())) {
                val f = File(t.path, PROBE_FILE_NAME)
                if (f.exists()) {
                    val ok = f.delete()
                    android.util.Log.i(
                        "UFSBench",
                        "清扫残留探测文件 ${f.absolutePath} -> ${if (ok) "已删除" else "删除失败"}"
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    /** 用户确认 PC 级档位后真正启动（跳过预检） */
    fun confirmPendingStart() {
        val spec = _uiState.value.pendingConfirmSpec ?: return
        _uiState.update { it.copy(pendingConfirmSpec = null) }
        startBenchmark(spec.copy(forceRun = true))
    }

    /** 用户取消 PC 级档位启动 */
    fun cancelPendingStart() {
        _uiState.update {
            it.copy(
                pendingConfirmSpec = null,
                statusMessage = "已取消：PC 级内置档位未启动"
            )
        }
    }

    fun stopBenchmark() {
        getCurrentEngine().stopBenchmark()
        benchmarkJob?.cancel()
        _uiState.update {
            resetCurves(
                it.copy(
                    benchmarkState = BenchmarkState.IDLE,
                    statusMessage = "测试已手动终止"
                )
            )
        }
    }

    private companion object {
        /** 探测文件名：创建与清扫共用同一常量，避免两处写死不一致 */
        const val PROBE_FILE_NAME = "ufs_bench_probe.dat"

        /** 与 SpecStore 同一个 SharedPreferences 文件，仅用独立 key 存 CPU 档位偏好 */
        const val PREFS_NAME = "ufs_bench_spec"
        const val KEY_CPU_PROFILE = "cpu_profile_preference"
    }

    fun clearHistory() {
        _uiState.update { it.copy(history = emptyList()) }
    }
}