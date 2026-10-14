package com.ufs.bench.core

import android.os.Build
import com.ufs.bench.hardware.AllBigCoreOnlyProfiles
import com.ufs.bench.hardware.CoreTopologyDetector
import java.io.File

/**
 * 引擎库的微架构分档加载状态（档位为 **-mtune 调度调优**，非 -mcpu）。
 *
 * 设计要点（task-15）：
 *  - 只有 arm64-v8a 出非基线变体；armeabi-v7a 与 x86_64 只有 baseline。
 *  - **绝不加载比本机核心更新的档**（例如给骁龙 888 加载 cortex-x4 的库会导致 SIGILL）。
 *  - 降级**不得静默**：requestedProfile 与 activeProfile 分开暴露，并给出中文 note。
 *  - 同 ABI 的 .so 一旦加载无法可靠卸载/重载 → 改档位需要重启进程，由 [pendingRestart] 显式告知。
 */
data class EngineLoadState(
    /** 生效 ABI（Build.SUPPORTED_ABIS 首个；无法判定为 "unknown"） */
    val abi: String,
    /** 本进程**实际生效**的档（来自原生编译期宏，非 Kotlin 推断） */
    val activeProfile: String,
    /** 生效档对应的 **-mtune** 目标（来自原生编译期宏；不启用该核独有 ISA 扩展） */
    val activeMcpu: String,
    /** 本进程加载时**实际尝试**的档（可能因红线裁剪或加载失败而与 active 不同） */
    val requestedProfile: String,
    /** 本机检测到的**最高安全档**（不受用户选择影响；认不出核心时 = baseline） */
    val deviceMaxProfile: String,
    /** 设置里的值："auto" 或六档之一 */
    val configuredPreference: String,
    /** activeProfile != requestedProfile 时为 true */
    val fallback: Boolean,
    /** 中文原因说明；无需提示时为空串 */
    val note: String,
    /** 已加载且当前设置与加载时不同 → 需重启进程才会生效 */
    val pendingRestart: Boolean,
    /**
     * 请求的档在**本进程里根本无法加载**（ABI 切片没有它 / 处在 ARM 翻译层）。
     * 与 fallback 的区别：fallback 可能只是"本次没生效、重启就能生效"，
     * 而 requestedUnavailable=true 表示**重启也没用**，界面必须这样显示。
     */
    val requestedUnavailable: Boolean = false,
    /**
     * 本变体是否为实验性档位（由**独立 LLVM 23.1.3** 构建，非 NDK 内置 Clang）。
     * 来自原生编译期宏，UI 必须据此标注"实验性"，不得与 NDK 档位混为一谈。
     */
    val experimental: Boolean = false,
    /** 工具链来源（编译期宏）："NDK 30.0.16248370 内置 Clang" / "独立 LLVM 23.1.3（非 NDK 内置，实验性）" */
    val ccOrigin: String = ""
)

/**
 * 单档引擎库的元数据（-mtune 调度调优目标目录项）。
 * 编译器 / 优化等级 / 目标 ABI 对所有档位按构造相同，故不在此逐档重复，见 [DynamoNativeBridge.abi]。
 */
data class EngineProfileSpec(
    /** "baseline" / "x1" / "x2" / "x3" / "x4" / "x925" / "c1ultra" */
    val profile: String,
    /** 该档对应的 **-mtune** 目标（baseline = "generic"，表示未显式指定）；本字段名沿用 mcpu 以兼容既有调用点 */
    val mcpu: String,
    /** 本进程所在 ABI 的包里是否带了这一档（baseline 恒 true；其余仅 arm64-v8a 为 true） */
    val packagedForThisAbi: Boolean,
    /**
     * 是否实验性档位（独立 LLVM 23.1.3 构建，非 NDK 内置 Clang）。
     * UI 必须显著标注；这类档位当前还**无法被自动检测选中**（未收录 C1 系列 MIDR），
     * 因此 [DynamoNativeBridge.isProfileSupported] 对它恒为 false，属于预期行为。
     */
    val experimental: Boolean = false
)

/**
 * JNI 桥。
 *
 * 库不再固定为 libdynamo_engine.so，而是按 CPU 微架构分档：
 *   libdynamo_engine_baseline.so / _x1 / _x2 / _x3 / _x4 / _x925
 * （非 arm64 ABI 仅提供 baseline）。加载策略见 [loadState]。
 *
 * 加载是**懒执行**的，由 [prepareFile] / [runBenchmark] / [stop] 触发；
 * 因此 UI 可以在首次使用引擎前调用 [configure] 指定档位。
 * 已加载后再 [configure] 不会改变本进程行为（返回 false），需重启进程。
 */
object DynamoNativeBridge {

    /**
     * **已废弃**的自动档（保留常量仅为兼容旧版本持久化值）。
     *
     * 需求方裁定：**取消自动识别，改为纯手动选择**。
     * 原因：自动识别会在同一台设备上给出难以预期的选择（实测 8E5 上选任意 Oryon 档都报回退），
     * 用户既看不懂、也无法控制；调优必须完全由用户显式决定。
     *
     * 现在：该值在校验时按"非法"处理，归一化后落到 [PROFILE_BASELINE]；
     * 设备能力检测（[detectDeviceMaxProfile]）**仅保留为安全红线**，
     * 不再参与档位选择 —— 它只用来阻止加载比本机更新的变体（旧核不支持会 SIGILL）。
     */
    const val PROFILE_AUTO: String = "auto"

    /**
     * 默认档：**公平基线**。
     *
     * 调优是 opt-in：新安装、读不到偏好、偏好非法，三种情况一律 baseline。
     * 用户要定向调优必须自己去设置页显式选择具体档位。
     */
    const val PROFILE_BASELINE: String = "baseline"

    /** 契约里的六档（NDK r30 内置 Clang 21 构建），顺序即代际顺序 */
    val PROFILES: List<String> = listOf("baseline")

    /**
     * 实验性档位名（**独立 LLVM 23.1.3** 构建，非 NDK 内置）。
     *
     * 这里只用于 [configure] 的输入归一化与兜底显示；
     * 权威元数据（-mtune 目标、是否实验性、是否随本 ABI 出包）一律来自原生
     * [nativeEngineProfileCatalog] 与 [nativeEngineExperimentalProfiles]。
     * 这些档位当前**无法被自动检测选中**（未收录 C1 系列 MIDR），
     * 且 rank 高于全部 Cortex 档，因此任何设备上都会先被红线裁剪。
     */
    val EXPERIMENTAL_PROFILES: List<String> =
        // 注意：这份名单是"输入归一化"的白名单，**新增档位必须同步加在这里**，
        // 否则持久化的偏好会在校验阶段被静默归一化成 baseline，连加载门禁都轮不到执行
        // （真机实测：prefs 写着 oryon2isa，日志却是 requested=baseline）。
        // oryon2isa = 全大核 ISA 专用档，运行期由 CoreTopologyDetector 门禁把关。
        listOf("baseline")

    private val LOCK = Any()

    @Volatile private var state: EngineLoadState? = null
    // 初始偏好：联调通道 > **默认公平基线**（opt-in 调优，见 PROFILE_BASELINE）。
    // 联调通道（debug 属性 / /data/local/tmp 文件）优先，便于无 UI 时验证档位与降级路径。
    // 另外：即使持久化里存着一个本机跑不了的档位，加载时也会被红线裁剪并回退 baseline，
    // 不会因为一个坏偏好导致应用打不开。
    @Volatile private var preference: String = debugPropertyPreference() ?: PROFILE_BASELINE
    // 是否被 UI/联调显式配置过：仅用于把"默认基线"与"用户主动选基线"在提示语里区分开
    @Volatile private var explicitlyConfigured: Boolean = false
    @Volatile private var preferenceAtLoad: String? = null

    /**
     * 应用 Context（由 BenchApplication 在任何 dlopen 之前注入）。
     * **只**用于读取 applicationInfo.nativeLibraryDir —— 判定本进程实际加载的 ABI 切片；
     * 不能用 java.library.path（Android 上是系统路径，与应用切片无关，实测踩过）。
     */
    var appContext: android.content.Context? = null

    /**
     * 仅供联调的调试入口：系统属性 debug.ufsbench.profile（非公开 API，正式 UI 走 [configure]）。
     *
     * 实现说明：**不能用反射调 android.os.SystemProperties** —— Android 12+ 的
     * 隐藏 API 限制会直接拒绝，实测表现为"属性设了却读不到、静默退回默认档"。
     * 这里改用 /system/bin/getprop 子进程读取，可靠且与 API 级别无关。
     */
    private const val DEBUG_PREF_FILE = "/data/local/tmp/ufsbench_profile"

    // 联调通道（本 APK 未启用 buildConfig 生成，故不做 BuildConfig.DEBUG 门禁）：
    // 安全性由**红线本身**保证 —— 联调档位同样要过 rankOf 裁剪，
    // 因此它只能把档位往低处压，绝不可能加载比本机更新的变体（不存在 SIGILL 风险）；
    // 且一旦生效，profileNote 会明确写出"联调覆盖档位"，不会静默。
    private fun debugPreferenceFromFile(): String? = runCatching {
        val f = java.io.File(DEBUG_PREF_FILE)
        if (!f.exists()) null
        else f.readText().trim().takeIf { isKnownPreference(it) }
    }.getOrNull()

    private fun debugPreferenceFromProp(): String? = runCatching {
        // 绝对路径，排除 app 进程 PATH 不含 /system/bin 的可能
        val p = ProcessBuilder("/system/bin/getprop", "debug.ufsbench.profile")
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
        p.waitFor()
        out.takeIf { isKnownPreference(it) }
    }.getOrNull()

    /** 两个联调通道都试；并把结果打到 logcat，避免"钩子静默失效"再次浪费排查时间 */
    private fun debugPropertyPreference(): String? {
        val fromFile = debugPreferenceFromFile()
        val fromProp = if (fromFile == null) debugPreferenceFromProp() else null
        android.util.Log.i(
            "UFSBench",
            "debug profile hook: file=" + (fromFile ?: "-") + " prop=" + (fromProp ?: "-") +
                " fileExists=" + java.io.File(DEBUG_PREF_FILE).exists()
        )
        return fromFile ?: fromProp
    }

    // ------------------------------------------------------------------
    // 配置（UI 在首次使用引擎前调用）
    // ------------------------------------------------------------------

    /**
     * 设定档位偏好："auto" 或六档之一；非法值按 "auto" 处理。
     *
     * @return true  = 已记录；引擎尚未加载，下次加载（本进程内）即生效
     *         false = 引擎已加载，本次调用**不改变**本进程行为，需重启进程才生效
     */
    fun configure(profilePreference: String): Boolean {
        val normalized = normalizePreference(profilePreference)
        synchronized(LOCK) {
            preference = normalized
            explicitlyConfigured = true
            if (state != null) return false
        }
        return true
    }

    /**
     * 合法档位取值：契约六档 / 实验档。
     *
     * **不再接受 "auto"** —— 需求方裁定取消自动识别，改为纯手动选择。
     * 旧版本存下的 "auto" 会因不属于合法值而被归一化到 baseline（安全且行为可预期）。
     * 联调通道与 [configure] 共用同一判定，避免两处口径漂移。
     */
    fun isKnownPreference(value: String): Boolean =
        value in PROFILES || value in EXPERIMENTAL_PROFILES

    private fun normalizePreference(value: String): String =
        if (isKnownPreference(value)) value else PROFILE_BASELINE

    // ------------------------------------------------------------------
    // 只读状态
    // ------------------------------------------------------------------

    /** 全量加载状态；首次访问会触发加载。pendingRestart 用当前设置实时计算，不是加载时的快照 */
    val loadState: EngineLoadState
        get() {
            val s = ensureLoaded()
            val pending = isPendingRestart()
            return if (s.pendingRestart == pending) s else s.copy(pendingRestart = pending)
        }

    val activeProfile: String get() = ensureLoaded().activeProfile
    val activeMcpu: String get() = ensureLoaded().activeMcpu
    val requestedProfile: String get() = ensureLoaded().requestedProfile

    /** 本机最高安全档（判定不出来时 = "baseline"） */
    val deviceMaxProfile: String get() = ensureLoaded().deviceMaxProfile

    val configuredPreference: String get() = ensureLoaded().configuredPreference
    val profileFallback: Boolean get() = ensureLoaded().fallback
    val profileNote: String get() = ensureLoaded().note
    val abi: String get() = ensureLoaded().abi
    /** 显式落盘插桩 profile；返回 false = 当前库不是插桩构建 */
    fun flushPgoProfile(dir: java.io.File): Boolean {
        val ok = runCatching {
            dir.mkdirs()
            val target = java.io.File(dir, "bench_" + android.os.Process.myPid() + ".profraw")
            ensureLoaded()
            nativePgoFlush(target.absolutePath)
        }.getOrDefault(false)
        // 用 AppLogger 而不是只写 logcat：真机上 logcat 缓冲区会被系统日志挤掉，
        // 抓不到钩子返回值就无法区分"没被调用"与"调用了但没写成"。
        com.ufs.bench.util.AppLogger.i(
            "PGO", "flush -> " + ok + " path=" + java.io.File(dir, "bench_" + android.os.Process.myPid() + ".profraw").absolutePath
        )
        return ok
    }
    /** 请求档在本进程是否根本无法加载（重启也没用） */
    val requestedUnavailable: Boolean get() = ensureLoaded().requestedUnavailable

    /** 本机核心拓扑摘要（全大核判定的可读结果，供界面/披露显示）。 */
    val coreTopologySummary: String get() = CoreTopologyDetector.detect().summary

    /** 本机是否满足"全大核 ISA 档"的加载条件（同构且非小核/中核）。 */
    val isAllBigCore: Boolean get() = CoreTopologyDetector.detect().isAllBigCore

    /**
     * 已加载且当前设置与加载时不同 → 需重启进程才生效。
     * 实时计算（不是加载瞬间的快照），UI 直接用它写「将在下次启动生效」。
     */
    val pendingRestart: Boolean get() = isPendingRestart()

    private fun isPendingRestart(): Boolean {
        if (state == null) return false
        val atLoad = preferenceAtLoad ?: return false
        return preference != atLoad
    }

    /**
     * 本机可安全选择的档（rank ≤ deviceMaxProfile；非 arm64 只有 baseline）。
     *
     * 注意：**必须同时遍历 EXPERIMENTAL_PROFILES**。
     * 早期版本只过滤 PROFILES（六档），导致实验档在本机明明可用、auto 也能识别，
     * 却在设置页选择器里完全看不到 —— 用户在自己设备上选不到专为该设备编的档位。
     * （实例：一加 15 / SM8850，deviceMax=oryon2，但 UI 只列六档。）
     */
    fun availableProfiles(): List<String> {
        val s = ensureLoaded()
        return (PROFILES + EXPERIMENTAL_PROFILES)
            .filter { isSupportedOn(s.abi, s.deviceMaxProfile, it) }
    }

    /** 该档在本机是否安全可用（供 UI 置灰） */
    fun isProfileSupported(profile: String): Boolean {
        val s = ensureLoaded()
        return isSupportedOn(s.abi, s.deviceMaxProfile, profile)
    }

    /**
     * 逐档元数据目录（profile / -mtune 目标 / 本 ABI 是否随包提供）。
     *
     * 数据源是**原生编译期宏**（CMake 配置期从同一套变量生成），
     * 因此 UI 展示的逐档 -mtune 目标永远不会与真实构建漂移 —— 不要在 UI 里再抄一份表。
     *
     * 说明：编译器版本 / 优化等级 / 目标 ABI 对**所有档位按构造相同**
     * （同一次 CMake 配置、同一组 flags，只有 -mtune 目标不同），
     * 需要这些值时请取 [activeProfile] 的 compilerToolchain 或 [abi]，不要按档去猜。
     * [packagedForThisAbi] 与 CMake 的出档规则一致：baseline 全 ABI 都有，其余只有 arm64-v8a。
     */
    fun profileCatalog(): List<EngineProfileSpec> {
        val s = ensureLoaded()
        val raw = runCatching { nativeEngineProfileCatalog() }.getOrNull().orEmpty()
        val parsed = raw.split(',')
            .map { it.trim() }
            .filter { it.contains('=') }
            .map { val (p, m) = it.split('=', limit = 2); p to m }
            .filter { it.first.isNotBlank() }
        val source = if (parsed.isNotEmpty()) parsed else listOf("baseline" to "generic")
        // 实验性名单同样取原生宏；读不到才用兜底，避免把实验性档误标成常规档
        val experimentalNames = runCatching { nativeEngineExperimentalProfiles() }
            .getOrNull().orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { EXPERIMENTAL_PROFILES }
        return source.map { (profile, mcpu) ->
            EngineProfileSpec(
                profile = profile,
                mcpu = mcpu,
                packagedForThisAbi = profile == "baseline" || s.abi == "arm64-v8a",
                experimental = profile in experimentalNames,
            )
        }
    }

    /**
     * 本 ABI 的包里**是否真的带了这个档的 .so**（权威判定，供 UI 与档位状态机共用）。
     *
     * 语义要点：目录里**没有**这个档 = 本 ABI 没有它（例如 x86_64 只出 baseline，
     * 而 UI 的档位清单是恒定 9 档）—— 不能把「查不到」当成「未知」，
     * 否则界面会把「本机根本没有这个库」误报成「等重启就会生效」。
     */
    fun isProfilePackagedForThisAbi(profile: String): Boolean =
        profileCatalog().any { it.profile == profile && it.packagedForThisAbi }

    /**
     * 完整编译串（编译器版本 / 优化等级 / 编译参数 / 目标 ABI / 生效 profile / 工具链来源）。
     * 直接取自原生编译期宏，**不跑测试也能拿到**；与结果里的 compilerToolchain 是同一份实现。
     */
    val compilerToolchain: String
        get() {
            ensureLoaded()
            return runCatching { nativeEngineToolchain() }.getOrDefault("")
        }

    // ------------------------------------------------------------------
    // 公共入口（先确保加载，再调 native）
    // ------------------------------------------------------------------

    fun prepareFile(filePath: String, sizeBytes: Long, cacheMode: Int = 0): Boolean {
        ensureLoaded()
        return nativePrepareFile(filePath, sizeBytes, cacheMode)
    }

    fun runBenchmark(
        filePath: String,
        reqSizeBytes: Int,
        pctRead: Int,
        pctRnd: Int,
        queueDepth: Int,
        workers: Int,
        fileSizeBytes: Long,
        durationSec: Int,
        builtinKernelId: Int,
        portedMode: Boolean,
        specScale: Int,
        availMemBytes: Long,
        forceRun: Boolean,
        cacheMode: Int,
        privilege: Int,
        hasRoot: Boolean,
        stopCriterion: Int,
        stopAfterBytes: Long,
        maxDurationSec: Int,
        sampleIntervalMs: Int,
        professionalStats: Boolean,
        callback: DynamoCallback?
    ): NativeBenchmarkResult {
        ensureLoaded()
        return nativeRunBenchmark(
            filePath, reqSizeBytes, pctRead, pctRnd, queueDepth, workers, fileSizeBytes,
            durationSec, builtinKernelId, portedMode, specScale, availMemBytes, forceRun,
            cacheMode, privilege, hasRoot, stopCriterion, stopAfterBytes, maxDurationSec,
            sampleIntervalMs, professionalStats, callback
        )
    }

    fun stop() {
        ensureLoaded()
        nativeStop()
    }

    // ------------------------------------------------------------------
    // 加载与档位判定
    // ------------------------------------------------------------------

    private fun ensureLoaded(): EngineLoadState {
        state?.let { return it }
        synchronized(LOCK) {
            state?.let { return it }
            val s = doLoad()
            state = s
            preferenceAtLoad = s.configuredPreference
            return s
        }
    }

    private fun doLoad(): EngineLoadState {
        // 预判 ABI：**只用于决定"敢不敢加载非 baseline"**（保守）。
        // 注意：Build.SUPPORTED_ABIS 不能完全代表本进程实际跑的切片 ——
        // 带 ARM 翻译的 x86 模拟器（MuMu：abilist=x86_64,arm64-v8a,…）会被系统
        // 选成 primaryCpuAbi=arm64-v8a，实际加载的是 arm64 库。因此加载后再用
        // 原生编译期 ABI 宏修正对外报告，避免"报 x86_64、实际跑 arm64"的自相矛盾。
        // 本进程**实际加载的切片**：以应用程序的 nativeLibraryDir 为准（形如 .../lib/arm64）。
        // 两个坑都实测过：
        //  · 不能用 java.library.path —— Android 上它是 /system/lib64:... 这类系统路径，
        //    与应用实际加载的切片无关（曾据此得到错误证据，还把无关路径写进了用户可见提示）；
        //  · 也不能只看 Build.SUPPORTED_ABIS 首项 —— 带 ARM 翻译层的 x86 模拟器上
        //    abilist 以 x86_64 开头，进程却实际跑 arm64 切片。
        val nativeDir = runCatching { appContext?.applicationInfo?.nativeLibraryDir }.getOrNull().orEmpty()
        val sliceArm64 = when {
            nativeDir.contains("/arm64") -> true
            nativeDir.contains("/x86_64") || nativeDir.contains("/x86") -> false
            else -> Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
        }
        val sliceLabel = when {
            nativeDir.isEmpty() -> "切片未知（按硬件声明判断）"
            sliceArm64 -> "arm64 切片"
            else -> "非 arm64 切片"
        }
        android.util.Log.i("UFSBench", "native slice: dir=" + nativeDir + " arm64=" + sliceArm64 +
            " abilist=" + Build.SUPPORTED_ABIS.joinToString(","))
        val hwArm64 = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
        // 翻译层：进程跑 arm64 切片、但硬件不是 arm64。此时定向调优既无意义也不安全
        // （翻译器不保证实现全部指令，加载调优档有 SIGILL 风险），必须回退基线并如实说明。
        val translated = sliceArm64 && !hwArm64
        val guessAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val guessArm64 = sliceArm64
        val detected = if (guessArm64 && !translated) detectDeviceMaxProfile() else "baseline"
        // 加载时**再读一次**联调通道并让其优先：UI 的 configure() 可能在类初始化之后调用，
        // 若不在这里重读，联调档位会被 UI 的默认值覆盖掉（实测踩过）。
        val debugPref = debugPreferenceFromFile() ?: debugPreferenceFromProp()
        val pref = debugPref ?: preference
        val notes = mutableListOf<String>()
        if (debugPref != null) {
            notes += "联调覆盖：$debugPref（debug 通道，正式包不会出现）"
            android.util.Log.i("UFSBench", "debug profile override at load: $debugPref (ui preference=$preference)")
        }

        // 1) 期望档（**裁剪前**的原始诉求）＝ 用户显式选择值。
        //    已取消自动识别：不再有任何"由系统替用户挑档"的路径。
        //    对外暴露的 requestedProfile 就是它；fallback = active != requested，
        //    因此"用户选了 x4 但设备不支持"会如实报成降级，而不是被裁剪后自证清白。
        val requested = pref

        // 2) 先判定"本进程到底能不能加载这个档"（ABI 切片 / 翻译层）。
        //    必须在红线提示之前：旧顺序会先把「已按你的选择加载」写进提示，
        //    随后又被静默改成 baseline —— 提示与实际自相矛盾（实测被当成编译出问题）。
        var candidate = requested
        var requestedUnavailable = false
        if (candidate != "baseline" && translated) {
            requestedUnavailable = true
            notes += "本机以 ARM 翻译层运行 arm64 库（硬件 ABI=$guessAbi）：已使用基线"
        } else if (candidate != "baseline" && !guessArm64) {
            requestedUnavailable = true
            notes += "本进程未加载 arm64 切片（$sliceLabel，硬件 ABI=$guessAbi）：只提供基线变体，已回退"
        }
        if (requestedUnavailable) {
            candidate = "baseline"
        }

        // 2b) 全大核 ISA 门禁（**fail-closed**）
        //     ISA 档用 -mcpu 启用了目标微架构的完整 ISA（实测 oryon2isa 含 1526 条 SVE 指令）。
        //     只要本机还存在缺少该 ISA 的核心（小核/中核/不同代核心），Android 调度器就可能把
        //     线程扔过去 → SIGILL（本项目已在 SM7475 复现同类崩溃）。因此只有「所有核心同构且
        //     非小核/中核」的设备允许加载 ISA 档，其余**拒绝加载**并如实播报（降级不得静默）。
        //     该门禁只作用于 ISA 档；常规 -mtune 档位不受任何影响。
        if (candidate in AllBigCoreOnlyProfiles.IDS) {
            val topo = CoreTopologyDetector.detect()
            if (!topo.isAllBigCore) {
                notes += "档位 " + candidate + " 已下架（专属调优取消），本机为" +
                    topo.summary + "：拒绝加载，已回退基线" +
                    "（避免线程被调度到不支持该 ISA 的核心上 SIGILL）"
                candidate = "baseline"
                requestedUnavailable = true
            } else {
                notes += "" + candidate + " 已通过门禁：本机 " + topo.summary
            }
        }

        // 3) 红线：绝不加载比本机更新的档（Oryon 家族内 oryon1/oryon2 互通调度调优，不互相裁剪）
        val isOryonMatch = (detected.startsWith("oryon") || isOryonDevice()) && requested.startsWith("oryon")
        // 需求「解除检测限制」：**用户显式选择优先**，不再由检测结果悄悄裁剪。
        //   · 只有"没人显式选过"（默认基线路径）时，检测才作为兜底裁剪；
        //   · 显式选择即使高于本机检测档也照用户的意思加载，但风险必须写在明面上 ——
        //     旧核执行到新档指令会直接 SIGILL（本项目已在 SM7475 实测复现）。
        val manualChoice = explicitlyConfigured || debugPref != null
        val exceedsDevice = !isOryonMatch && rankOf(requested) > rankOf(detected)
        if (exceedsDevice && !manualChoice) {
            candidate = detected
            notes += "已使用基线（专属调优已取消）"
        } else if (exceedsDevice && candidate == requested) {
            notes += "⚠ 你显式选择的 $requested 高于本机判定最高档 $detected（已按你的选择加载）：" +
                "若本机核心不支持该档指令，压测会以 SIGILL 崩溃（本项目已在 SM7475 复现）"
        }

        // 4) 真正加载：候选 → 失败则 baseline
        var active = candidate
        var loadErr = tryLoadLibrary(candidate)
        if (loadErr != null && candidate != "baseline") {
            notes += "加载 libdynamo_engine_$candidate.so 失败（$loadErr），已回退 baseline"
            active = "baseline"
            loadErr = tryLoadLibrary("baseline")
        }
        if (loadErr != null) {
            notes += "加载 libdynamo_engine_baseline.so 失败（$loadErr）"
        }

        // 5) 生效值与 ABI 都以原生编译期宏为准（权威），读不到才回落本地推断
        val nativeProfile = runCatching { nativeEngineProfile() }.getOrNull()?.takeIf { it.isNotBlank() }
        val nativeMcpu = runCatching { nativeEngineMcpu() }.getOrNull()?.takeIf { it.isNotBlank() }
        val nativeAbi = runCatching { nativeEngineAbi() }.getOrNull()?.takeIf { it.isNotBlank() }
        val effective = nativeProfile ?: active
        val effectiveMcpu = nativeMcpu ?: mcpuOf(effective)
        val actualAbi = nativeAbi ?: guessAbi
        val actualArm64 = actualAbi == "arm64-v8a"

        // 6) 用真实 ABI 修正对外报告（并补齐提示文案）
        // 设备能力仅作为**安全红线**保留，不再参与档位选择（已取消自动识别）
        val deviceMax = if (actualArm64) detectDeviceMaxProfile() else "baseline"
        if (!actualArm64 && candidate != "baseline") {
            notes += "当前 ABI（$actualAbi）只提供 baseline 变体，已回退"
        }
        // 只有"真的被显式选择过"（UI configure 或联调通道）才说"按用户选择"，
        // 否则默认基线会同时命中两条提示，自相矛盾。
        if ((explicitlyConfigured || debugPref != null) &&
            effective == pref && rankOf(pref) < rankOf(deviceMax)) {
            notes += "按用户选择使用 $pref（本机历史最高档 $deviceMax）"
        }
        // 默认档（既非联调覆盖、也非用户显式选择）说清"为什么是基线"，
        // 避免把"默认公平基线"读成"用户自己选了基线"。
        if (!explicitlyConfigured && debugPref == null && pref == PROFILE_BASELINE &&
            effective == "baseline" && actualArm64) {
            notes += "使用 generic 基线（专属调优已取消，统一 generic 基线）"
        }

        // 实验性标记与工具链来源：都取原生编译期宏（权威），读不到才回落
        val experimental = runCatching { nativeEngineProfileExperimental() }.getOrDefault(false)
        val ccOrigin = runCatching { nativeEngineCcOrigin() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: ""

        val st = EngineLoadState(
            abi = actualAbi,
            activeProfile = effective,
            activeMcpu = effectiveMcpu,
            // 注意：这里给的是**裁剪前**的原始诉求，所以"选了 x4 却只有 baseline"会 fallback=true
            requestedProfile = requested,
            deviceMaxProfile = deviceMax,
            // configuredPreference 始终是"设置里的值"（UI 真相）；联调覆盖只影响本进程实际加载
            configuredPreference = preference,
            fallback = if (isOryonMatch && effective.startsWith("oryon")) false else (effective != requested),
            note = notes.joinToString("；"),
            requestedUnavailable = requestedUnavailable,
            pendingRestart = false,   // 由 pendingRestart 属性实时计算，不在这里算快照
            experimental = experimental,
            ccOrigin = ccOrigin
        )
        // logcat 证据：一眼可见实际加载了哪一档、是否降级、为什么
        android.util.Log.i(
            "UFSBench",
            "engine load: abi=" + st.abi +
                " active=" + st.activeProfile +
                (if (st.activeProfile in AllBigCoreOnlyProfiles.IDS) "(-mcpu=" else "(-mtune=") +
                st.activeMcpu + ")" +
                " requested=" + st.requestedProfile +
                " deviceMax=" + st.deviceMaxProfile +
                " fallback=" + st.fallback +
                " preference=" + st.configuredPreference +
                " experimental=" + st.experimental +
                " cc=" + st.ccOrigin +
                (if (st.note.isEmpty()) "" else " note=" + st.note)
        )
        return st
    }

    private fun tryLoadLibrary(profile: String): String? = try {
        System.loadLibrary("dynamo_engine_$profile")
        null
    } catch (e: UnsatisfiedLinkError) {
        (e.message ?: "UnsatisfiedLinkError").lineSequence().firstOrNull() ?: "UnsatisfiedLinkError"
    }

    /**
     * 代际序号：用于"绝不加载更新档"的红线比较。
     *
     * C1 系列（实验性）排在全部 Cortex 档之后：由于自动检测**不收录 C1 的 MIDR**，
     * 任何设备的 deviceMax 最高只到 x925(6)，因此 c1*(7~10) 在任何设备上都会被裁剪 ——
     * 这是有意为之：宁可不给用，也绝不冒险加载设备不支持的指令集（SIGILL）。
     */
    private fun rankOf(profile: String): Int = when (profile) {
        "c1nano" -> 9
        "c1pro" -> 10
        "c1premium" -> 11
        "c1ultra" -> 12
        "oryon2" -> 8
        "oryon1" -> 7
        "x925" -> 6
        "x4" -> 5
        "x3" -> 4
        "x2" -> 3
        "x1" -> 2
        else -> 1   // baseline / 未知
    }

    private fun mcpuOf(profile: String): String = when (profile) {
        "oryon2" -> "oryon-2-v1"
        "oryon1" -> "oryon-1"
        "c1ultra" -> "c1-ultra"
        "c1premium" -> "c1-premium"
        "c1pro" -> "c1-pro"
        "c1nano" -> "c1-nano"
        "x1" -> "cortex-x1"
        "x2" -> "cortex-x2"
        "x3" -> "cortex-x3"
        "x4" -> "cortex-x4"
        "x925" -> "cortex-x925"
        else -> "generic"
    }

    private fun isSupportedOn(abi: String, deviceMax: String, profile: String): Boolean {
        if (profile !in PROFILES && profile !in EXPERIMENTAL_PROFILES) return false
        if (profile != "baseline" && abi != "arm64-v8a") return false
        // 只要是高通 Oryon 机器（8E 或 8E5），oryon1 与 oryon2 都视为本机安全可用（同家族调度调优，无非法指令）
        if ((deviceMax.startsWith("oryon") || isOryonDevice()) && profile.startsWith("oryon")) return true
        return rankOf(profile) <= rankOf(deviceMax)
    }

    /** 判定是否为高通 Oryon 平台（8E 或 8E5） */
    fun isOryonDevice(): Boolean {
        val dalvik = dalvikArm64Variant()
        if (dalvik == "oryon") return true
        val soc = socDescriptor()
        return soc.contains("sm8850", ignoreCase = true) || soc.contains("sm8750", ignoreCase = true) ||
            soc.contains("canoe", ignoreCase = true) || soc.contains("sun", ignoreCase = true) ||
            soc.contains("kaanapali", ignoreCase = true) || soc.contains("oryon", ignoreCase = true)
    }

    /**
     * 从 /proc/cpuinfo 的 "CPU part"（MIDR）判定本机最高安全档。
     * 只认 Arm 官方公布、且本项目确实出库的核心；认不出（含 0x0/缺失）一律 baseline。
     *
     * MIDR 对照（Arm 官方 PartNum）：
     *   0xD44 Cortex-X1 / 0xD48 Cortex-X2 / 0xD4E Cortex-X3 / 0xD82 Cortex-X4 / 0xD85 Cortex-X925
     *   其余（A55/A76/A77/A78/A510/A520/A710/A715/A720…）没有对应档，按基线处理。
     */
    private fun detectDeviceMaxProfile(): String {
        val text = runCatching { File("/proc/cpuinfo").readText() }.getOrNull() ?: return "baseline"
        var bestRank = 1
        val parts = mutableListOf<Int>()
        val implementers = mutableSetOf<Int>()
        for (line in text.lineSequence()) {
            val lower = line.lowercase()
            val raw = if (lower.contains("cpu part")) line.substringAfter(':').trim()
                      else if (lower.contains("cpu implementer")) line.substringAfter(':').trim()
                      else continue
            val v = runCatching {
                if (raw.startsWith("0x", ignoreCase = true)) raw.substring(2).toInt(16) else raw.toInt()
            }.getOrNull() ?: continue
            if (lower.contains("cpu implementer")) { implementers += v; continue }
            parts += v
            val rank = when (v) {
                0xD85 -> 6   // Cortex-X925
                0xD82 -> 5   // Cortex-X4
                0xD4E -> 4   // Cortex-X3
                0xD48 -> 3   // Cortex-X2
                0xD44 -> 2   // Cortex-X1
                else -> 1
            }
            if (rank > bestRank) bestRank = rank
        }
        // 高通 Oryon：非 ARM 公版 PartNum（一加 15 / SM8850 实测 0x002），
        // 必须**同时**满足高通身份门禁才认，避免把任意 0x002 的杂牌实现误判成 Oryon。
        val oryon = detectOryon(parts, implementers)
        if (oryon != null && rankOf(oryon) > bestRank) return oryon
        return when (bestRank) {
            6 -> "x925"
            5 -> "x4"
            4 -> "x3"
            3 -> "x2"
            2 -> "x1"
            else -> "baseline"
        }
    }

    /**
     * 高通 Oryon 判定（骁龙 8 Elite 系列）。
     *
     * 依据与门禁：
     *  · Oryon 用非 ARM 公版的 PartNum（SM8850 实测 0x002），单看数字**不足以**定性；
     *  · 因此要求"高通身份"证据之一：CPU implementer=0x51(Qualcomm)、
     *    Build.SOC_MANUFACTURER 含 qti/qcom/qualcomm、SoC 型号以 SM8 开头，
     *    或 dalvik.vm.isa.arm64.variant == "oryon"（厂商直接声明；一加 15 实测即此值）；
     *  · 分代用**设备自报**的型号/平台名（不加猜测）：
     *    SM8850（实测 board.platform = "canoe"）→ oryon2；SM8750/"sun" → oryon1；
     *    分代判不出来时取**更保守的旧档 oryon1**（绝不猜成更新的一代）。
     *
     *  实测更正（verify-pack 提供）：一加 15 与 Redmi 的 ro.soc.manufacturer 都是 **"QTI"**，
     *  不含 "qualcomm" —— 只匹配 qualcomm 必然失配，故一并接受 qti/qcom。
     */
    private fun getSystemProp(key: String): String? = runCatching {
        val p = ProcessBuilder("/system/bin/getprop", key)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
        p.waitFor()
        out.ifEmpty { null }
    }.getOrNull()

    private fun detectOryon(parts: List<Int>, implementers: Set<Int>): String? {
        val dalvikVariant = dalvikArm64Variant() ?: ""
        val soc = socDescriptor()
        val hasOryonPart = parts.any { it == 0x001 || it == 0x002 }
        val qualcomm = implementers.contains(0x51) ||
            soc.contains("qualcomm", ignoreCase = true) ||
            soc.contains("qti", ignoreCase = true) ||
            soc.contains("qcom", ignoreCase = true) ||
            soc.contains("sm8", ignoreCase = true) ||
            dalvikVariant == "oryon"

        val isOryon = dalvikVariant == "oryon" ||
            (hasOryonPart && qualcomm) ||
            soc.contains("sm8850", ignoreCase = true) ||
            soc.contains("sm8750", ignoreCase = true) ||
            soc.contains("canoe", ignoreCase = true) ||
            soc.contains("sun", ignoreCase = true) ||
            soc.contains("kaanapali", ignoreCase = true) ||
            soc.contains("oryon", ignoreCase = true)

        if (!isOryon) return null

        return when {
            soc.contains("sm8850", ignoreCase = true) ||
                soc.contains("canoe", ignoreCase = true) ||
                soc.contains("kaanapali", ignoreCase = true) ||
                parts.contains(0x002) -> "oryon2"
            soc.contains("sm8750", ignoreCase = true) ||
                soc.contains("sun", ignoreCase = true) ||
                parts.contains(0x001) -> "oryon1"
            else -> "oryon2"   // 8E5/8E 系列高通机器，默认给 oryon2
        }
    }

    /**
     * dalvik.vm.isa.arm64.variant：ART 运行时的 ARM64 变体，**厂商直接声明**（Oryon 机为 "oryon"）。
     */
    private fun dalvikArm64Variant(): String? = getSystemProp("dalvik.vm.isa.arm64.variant")

    /** SoC 描述串：综合公开 API 与底层 getprop（ro.soc.model / ro.board.platform 等） */
    private fun socDescriptor(): String {
        val sb = StringBuilder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { sb.append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL).append(' ') }
        }
        sb.append(getSystemProp("ro.soc.model")).append(' ')
        sb.append(getSystemProp("ro.board.platform")).append(' ')
        sb.append(getSystemProp("ro.soc.manufacturer")).append(' ')
        sb.append(getSystemProp("ro.hardware")).append(' ')
        sb.append(getSystemProp("ro.product.board")).append(' ')
        return sb.append(' ')
            .append(Build.HARDWARE).append(' ')
            .append(Build.BOARD).append(' ')
            .append(Build.DEVICE)
            .toString()
    }

    // ------------------------------------------------------------------
    // native（符号名必须与 native_bridge.cpp 的 Java_... 一致）
    // ------------------------------------------------------------------

    private external fun nativePrepareFile(filePath: String, sizeBytes: Long, cacheMode: Int): Boolean
    private external fun nativeStop()
    private external fun nativeRunBenchmark(
        filePath: String,
        reqSizeBytes: Int,
        pctRead: Int,
        pctRnd: Int,
        queueDepth: Int,
        workers: Int,
        fileSizeBytes: Long,
        durationSec: Int,
        builtinKernelId: Int,
        portedMode: Boolean,
        specScale: Int,
        availMemBytes: Long,
        forceRun: Boolean,
        cacheMode: Int,
        privilege: Int,
        hasRoot: Boolean,
        stopCriterion: Int,
        stopAfterBytes: Long,
        /** 独立安全上限（秒）；与 durationSec 分开，避免容量模式被残留时长掐断。0=不限 */
        maxDurationSec: Int,
        /**
         * 采样档位（毫秒），契约允许值：5 / 10 / 25 / 50 / 75 / 100。
         * 替换原先写死的 kSampleInterval；专业模式与常规模式的进度回调都按该间隔。
         */
        sampleIntervalMs: Int,
        professionalStats: Boolean,
        callback: DynamoCallback?
    ): NativeBenchmarkResult

    /** 本 .so 的档位自述（编译期宏 DYNAMO_PROFILE_ID） */
    private external fun nativeEngineProfile(): String

    /** 本 .so 的 -mtune 目标（编译期宏 DYNAMO_PROFILE_MCPU；语义为调度调优，非 -mcpu） */
    private external fun nativeEngineMcpu(): String

    /** 本 .so 的编译期目标 ABI（权威：可识别 ARM 翻译等"abilist 与实际切片不一致"的情况） */
    private external fun nativeEngineAbi(): String

    /** 逐档目录串（编译期由 CMake 生成）："baseline=generic,x1=cortex-x1,…"（逗号分隔） */
    private external fun nativeEngineProfileCatalog(): String

    /** 工具链来源（编译期宏）：NDK 内置 Clang 还是独立 LLVM 23.1.3 */
    private external fun nativeEngineCcOrigin(): String

    /** 本变体是否实验性档位（独立 LLVM 工具链构建） */
    private external fun nativeEngineProfileExperimental(): Boolean

    /** 实验性档位名列表（逗号分隔，编译期宏） */
    private external fun nativeEngineExperimentalProfiles(): String

    /** 完整编译串（不跑测试即可展示） */
    private external fun nativeEngineToolchain(): String
    /**
     * PGO 采集钩子（实现见 native_bridge.cpp）：显式把插桩 profile 落盘。
     * 非插桩构建里原生符号不存在，返回 false —— 调用方据此如实说明，不假装采集成功。
     */
    private external fun nativePgoFlush(path: String): Boolean

}

/** 原生 onProgress 回调（字段顺序与 native_bridge.cpp 的 CallVoidMethod 严格一致） */
interface DynamoCallback {
    fun onProgress(
        readMbps: Double,
        writeMbps: Double,
        readIops: Long,
        writeIops: Long,
        avgLatUs: Double,
        maxLatUs: Double,
        p99LatUs: Double,
        elapsedSec: Int,
        specScore: Long,
        cpuMetric: Double,
        memMetric: Double,
        specRatio: Double,
        /** 容量模式下已完成的读写总字节；按时间模式为 0 */
        bytesDone: Long,
        /** 容量模式的目标总字节；按时间模式为 0 */
        targetBytes: Long,
        /**
         * 本采样区间内读请求的平均响应延迟（μs）。
         * 由原生 worker 的单调计数器取增量求得，是"该区间"而非"全程"的平均；
         * 该区间没有读请求时为 0（原样上报，不伪造数值）。
         */
        readAvgLatencyUs: Double,
        /** 本采样区间内写请求的平均响应延迟（μs）；同上 */
        writeAvgLatencyUs: Double
    )
}
