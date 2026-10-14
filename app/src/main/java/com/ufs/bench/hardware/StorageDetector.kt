package com.ufs.bench.hardware

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File

data class StorageTarget(
    val name: String,
    val path: String,
    val isAppPrivate: Boolean,
    val fileSystemType: String,
    val backingDevice: String,
    val totalBytes: Long,
    val freeBytes: Long,
    val validity: MeasurementValidity
) {
    val totalGb: Double get() = totalBytes / (1024.0 * 1024.0 * 1024.0)
    val freeGb: Double get() = freeBytes / (1024.0 * 1024.0 * 1024.0)
}

/** 测量有效性的可信等级 */
enum class ValidityLevel { OK, CAUTION, INVALID }

data class MeasurementValidity(
    val level: ValidityLevel,
    val message: String? = null
) {
    val isTrustworthy: Boolean get() = level == ValidityLevel.OK
}

data class HardwareStorageInfo(
    val storageType: String,
    val deviceModel: String,
    val blockDevice: String,
    val ioScheduler: String,
    val queueDepthLimit: Int,
    val hasRootAccess: Boolean,
    val isEmulator: Boolean,
    /** sysfs 是否可读：Android 13 起 sysfs 的块设备节点对普通应用一律 SELinux 拒绝 */
    val sysfsReadable: Boolean,
    /** 存储类型是否为按 SoC 推断而非实测 */
    val storageTypeInferred: Boolean,
    val socModel: String,
    /** 市场名（如「一加 15」）；读不到时回落到 Build.MODEL */
    val deviceName: String,
    /** 型号代码（如 PLK110） */
    val deviceCode: String,
    /** 系统版本文本（Build.VERSION.RELEASE 原文，如 "17"） */
    val androidRelease: String,
    /** 存储器件厂商（sysfs vendor 原文，如 SAMSUNG） */
    val storageVendor: String,
    val environmentWarning: String?
)

data class MountInfo(val mountPoint: String, val fsType: String, val device: String)

object StorageDetector {

    /** 由 Activity/Application 注入，供包名探测使用 */
    @Volatile
    var appContext: android.content.Context? = null

    fun detectStorageTargets(context: Context): List<StorageTarget> {
        val targets = mutableListOf<StorageTarget>()
        val mounts = readMounts()
        val emulator = isEmulator()

        context.filesDir?.let { dir ->
            targets.add(createTarget("应用私有存储 (直连推荐)", dir, true, mounts, emulator))
        }
        context.getExternalFilesDir(null)?.let { dir ->
            targets.add(createTarget("应用外部存储目录", dir, true, mounts, emulator))
        }
        // 公共存储只在上架版**不做**为压测目标：
        // standard 构建不声明 MANAGE_EXTERNAL_STORAGE，写入 /sdcard 会被分区存储直接拒绝；
        // 私有目录同样挂在 UFS 分区且支持 O_DIRECT，测量口径不受影响。
        // （lab 构建 FEATURE_PUBLIC_STORAGE=true，保留公共目录/裸盘等进阶目标。）
        if (com.ufs.bench.BuildConfig.FEATURE_PUBLIC_STORAGE) {
            val ext = Environment.getExternalStorageDirectory()
            if (ext != null && ext.exists()) {
                targets.add(createTarget("公共存储 (/sdcard, FUSE封装)", ext, false, mounts, emulator))
            }
        }
        return targets
    }

    // ------------------------- 挂载表解析 -------------------------

    /** 读取 /proc/mounts 并解码八进制转义（\040 空格等） */
    private fun readMounts(): List<MountInfo> {
        val out = mutableListOf<MountInfo>()
        try {
            val f = File("/proc/mounts")
            if (!f.exists() || !f.canRead()) return out
            f.useLines { lines ->
                for (line in lines) {
                    val t = line.split(Regex("""\s+"""))
                    if (t.size >= 3) {
                        out.add(
                            MountInfo(
                                mountPoint = decodeMount(t[1]),
                                fsType = t[2],
                                device = decodeMount(t[0])
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun decodeMount(s: String): String {
        return s.replace("\\040", " ").replace("\\011", "\t").replace("\\012", "\n")
    }

    /**
     * 取「最长前缀匹配」的挂载项。
     * 原实现返回首个匹配项，而 /proc/mounts 首行是 "/ tmpfs"，导致任意路径都被误判为 tmpfs。
     */
    private fun resolveMount(path: String, mounts: List<MountInfo>): MountInfo? {
        var best: MountInfo? = null
        for (m in mounts) {
            if (path == m.mountPoint || path.startsWith(m.mountPoint.trimEnd('/') + "/")) {
                if (best == null || m.mountPoint.length > best.mountPoint.length) best = m
            }
        }
        return best
    }

    // ------------------------- 环境识别 -------------------------

    /**
     * 模拟器识别：现代安卓模拟器（如 MuMu / 雷电）会把 Build.MODEL、
     * ro.product.board、ro.hardware、fingerprint 全部伪装成真实机型，
     * 因此不能只看 Build 字段，必须用多路互相独立的信号交叉判定。
     */
    fun isEmulator(): Boolean {
        // 信号 1：模拟器专有文件（MuMu 的 houdini ARM 转译器与 mumu-configs）
        val artifacts = listOf(
            "/dev/qemu_pipe", "/dev/socket/qemud", "/dev/goldfish_pipe",
            "/system/lib64/libhoudini.so", "/system/lib/libhoudini.so",
            "/system/etc/mumu-configs", "/system/bin/mumu-prop",
            "/system/lib64/libnb.so", "/system/bin/nemuinit"
        )
        for (p in artifacts) if (File(p).exists()) return true

        // 信号 2：qemu 属性
        if (readProp("ro.kernel.qemu") == "1" || readProp("ro.boot.qemu") == "1") return true
        if (readProp("ro.hardware").lowercase().let { it.contains("vbox") || it.contains("qemu") }) return true

        // 信号 3：系统盘厂商字符串为虚拟磁盘（最硬的证据，VBOX/WMware/QEMU 虚拟盘）
        val vendorModel = (readFileFirstLine("/sys/block/sda/device/vendor") + " " +
                readFileFirstLine("/sys/block/sda/device/model") +
                " " + readFileFirstLine("/sys/block/vda/device/model")).lowercase()
        if (vendorModel.contains("vbox") || vendorModel.contains("virtual") ||
            vendorModel.contains("vmware") || vendorModel.contains("qemu") ||
            vendorModel.contains("goldfish") || vendorModel.contains("msft"))
            return true

        // 信号 4：主 ABI 为 x86 —— 现代安卓手机清一色 ARM，x86 只出现在模拟器/容器
        for (abi in Build.SUPPORTED_ABIS) {
            if (abi.lowercase().startsWith("x86")) return true
        }

        // 信号 5：常规 Build 特征（对不改机型的模拟器有效）
        val fp = Build.FINGERPRINT.lowercase()
        val hw = Build.HARDWARE.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        val board = Build.BOARD.lowercase()
        return fp.contains("generic") || fp.contains("vbox") || fp.contains("emulator") ||
                hw.contains("vbox") || hw.contains("goldfish") || hw.contains("ranchu") ||
                hw.contains("qemu") || model.contains("sdk") || model.contains("vbox") ||
                model.contains("emulator") || product.contains("sdk") ||
                board.contains("vbox") || board.contains("goldfish")
    }

    private fun readProp(key: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", key))
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            p.waitFor()
            out
        } catch (_: Exception) {
            ""
        }
    }

    // ------------------------- 目录取样 -------------------------

    private fun createTarget(
        name: String,
        dir: File,
        isPrivate: Boolean,
        mounts: List<MountInfo>,
        emulator: Boolean
    ): StorageTarget {
        val stat = StatFs(dir.absolutePath)
        val m = resolveMount(dir.absolutePath, mounts)
        val fsType = m?.fsType ?: "unknown"
        val device = m?.device ?: "unknown"
        return StorageTarget(
            name = name,
            path = dir.absolutePath,
            isAppPrivate = isPrivate,
            fileSystemType = fsType,
            backingDevice = device,
            totalBytes = stat.totalBytes,
            freeBytes = stat.availableBytes,
            validity = assessValidity(fsType, device, emulator)
        )
    }

    /**
     * 评估该目标上的成绩是否可信：
     *   tmpfs / ramfs  → 直接测的是内存，成绩无效
     *   fuse / sdcardfs → 用户态中转，成绩偏低
     *   模拟器          → 虚拟磁盘由宿主页缓存与宿主机 NVMe 承载，不代表任何手机闪存
     */
    private fun assessValidity(fsType: String, device: String, emulator: Boolean): MeasurementValidity {
        val fs = fsType.lowercase()
        val dev = device.lowercase()
        if (fs == "tmpfs" || fs == "ramfs") {
            return MeasurementValidity(
                ValidityLevel.INVALID,
                "目标位于 \"$fsType\"（内存文件系统），测到的是内存带宽而非闪存速度，成绩无效。"
            )
        }
        if (emulator) {
            return MeasurementValidity(
                ValidityLevel.INVALID,
                "当前为模拟器环境：\"$device\" 是宿主机的虚拟磁盘文件，" +
                    "读写实际由宿主机页缓存与宿主 NVMe 承载，测出的 GB/s 级成绩与任何手机闪存无关，不可作为真机成绩。"
            )
        }
        if (dev.contains("loop")) {
            return MeasurementValidity(
                ValidityLevel.CAUTION,
                "目标位于 loop 设备（\"$device\"），为镜像文件映射，成绩低于裸分区。"
            )
        }
        if (fs == "fuse" || fs == "sdcardfs" || fs == "fuseblk") {
            return MeasurementValidity(
                ValidityLevel.CAUTION,
                "目标经过 \"$fsType\" 用户态文件系统中转，通常比直连分区低 15%~30%。"
            )
        }
        return MeasurementValidity(ValidityLevel.OK, null)
    }

    // ------------------------- 硬件信息 -------------------------

    /** SoC 型号 → 存储规格推断表（仅用于在无权限读取 sysfs 时给出量级参考） */
    private val SOC_STORAGE_HINT: Map<String, String> = mapOf(
        // 高通 8 系 / 7+ 系：普遍 UFS 4.0 / 3.1
        "SM8650" to "UFS 4.0（按 SoC 推断）", "SM8550" to "UFS 4.0（按 SoC 推断）",
        "SM8475" to "UFS 3.1（按 SoC 推断）", "SM8450" to "UFS 3.1（按 SoC 推断）",
        "SM7475" to "UFS 3.1（按 SoC 推断）", "SM7435" to "UFS 2.2/3.1（按 SoC 推断）",
        "SM7325" to "UFS 2.2（按 SoC 推断）", "SM6375" to "UFS 2.2（按 SoC 推断）",
        "SM8250" to "UFS 3.1（按 SoC 推断）", "SM8150" to "UFS 3.0（按 SoC 推断）",
        // 联发科
        "MT6989" to "UFS 4.0（按 SoC 推断）", "MT6985" to "UFS 4.0（按 SoC 推断）",
        "MT6895" to "UFS 3.1（按 SoC 推断）", "MT6877" to "UFS 2.2（按 SoC 推断）",
        // 三星 Exynos
        "EXYNOS2200" to "UFS 3.1（按 SoC 推断）", "EXYNOS2400" to "UFS 4.0（按 SoC 推断）"
    )

    fun detectHardwareInfo(useRoot: Boolean = false): HardwareStorageInfo {
        if (useRoot) {
            val hw = detectHardwareInfoViaRoot()
            if (hw != null) return hw
            // root 读取失败则继续走常规路径
        }
        var storageType = "未知存储介质"
        var deviceModel = "通用 Flash 存储"
        var blockDev = "/dev/block/sda"
        var scheduler = "无权限读取"
        var queueDepth = 32
        var sysfsReadable = false
        var inferred = false

        val sdaDir = File("/sys/block/sda")
        val mmcDir = File("/sys/block/mmcblk0")
        val nvmeDir = File("/sys/block/nvme0n1")

        when {
            nvmeDir.exists() && nvmeDir.canRead() -> {
                sysfsReadable = true
                storageType = "NVMe 固态存储"
                blockDev = "/dev/block/nvme0n1"
                deviceModel = readFileFirstLine("/sys/block/nvme0n1/device/model").trim().ifEmpty { "NVMe 设备" }
                scheduler = readFileFirstLine("/sys/block/nvme0n1/queue/scheduler").trim()
                queueDepth = readFileFirstLine("/sys/block/nvme0n1/queue/nr_requests").trim().toIntOrNull() ?: 1024
            }
            sdaDir.exists() && sdaDir.canRead() -> {
                sysfsReadable = true
                val vendor = readFileFirstLine("/sys/block/sda/device/vendor").trim()
                val model = readFileFirstLine("/sys/block/sda/device/model").trim()
                deviceModel = if (vendor.isNotEmpty() || model.isNotEmpty()) "$vendor $model".trim() else "UFS 闪存芯片"
                storageType = if (File("/sys/bus/platform/drivers/ufshcd").exists()) "UFS 存储（通用闪存）" else "SCSI/UFS 设备"
                blockDev = "/dev/block/sda"
                scheduler = readFileFirstLine("/sys/block/sda/queue/scheduler").trim()
                queueDepth = readFileFirstLine("/sys/block/sda/queue/nr_requests").trim().toIntOrNull() ?: 1024
            }
            mmcDir.exists() && mmcDir.canRead() -> {
                sysfsReadable = true
                storageType = "eMMC 存储"
                blockDev = "/dev/block/mmcblk0"
                deviceModel = readFileFirstLine("/sys/block/mmcblk0/device/name").trim().ifEmpty { "eMMC 设备" }
                scheduler = readFileFirstLine("/sys/block/mmcblk0/queue/scheduler").trim()
                queueDepth = readFileFirstLine("/sys/block/mmcblk0/queue/nr_requests").trim().toIntOrNull() ?: 32
            }
        }

        // sysfs 不可读时（Android 13+ 普通应用常态）按 SoC 推断，并如实标注为推断
        if (!sysfsReadable) {
            val soc = Build.SOC_MODEL.ifBlank { Build.HARDWARE }.uppercase()
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}（SoC ${Build.SOC_MODEL.ifBlank { "未知" }}）"
            val hint = SOC_STORAGE_HINT.entries.firstOrNull { soc.contains(it.key) }?.value
            storageType = hint ?: "无法读取（sysfs 被 SELinux 拒绝）"
            inferred = hint != null
            scheduler = "无权限读取（Android 13+ SELinux 限制）"
        }

        val emu = isEmulator()
        val warn = if (emu) {
            "检测到模拟器环境（${Build.MODEL} / ${Build.HARDWARE}）：存储成绩由宿主机虚拟磁盘给出，" +
                "不代表真机闪存性能，仅供功能验证。"
        } else null

        return HardwareStorageInfo(
            storageType = storageType,
            deviceModel = deviceModel,
            blockDevice = blockDev,
            ioScheduler = scheduler.ifEmpty { "无权限读取" },
            queueDepthLimit = queueDepth,
            hasRootAccess = checkRootAccess(),
            isEmulator = emu,
            sysfsReadable = sysfsReadable,
            storageTypeInferred = inferred,
            socModel = Build.SOC_MODEL.ifBlank { "未知" },
            deviceName = marketName(),
            deviceCode = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE,
            storageVendor = "",
            environmentWarning = warn
        )
    }

    /**
     * 经 su 读取 sysfs。
     * 普通应用（untrusted_app）在 Android 13+ 读不到 /sys/block 下任何节点，
     * 而专业模式本来就持有 root，理应用它把真实硬件信息取回来，
     * 否则"读取真实调度器与队列深度"就只是句空话。
     */
    /**
     * 读取厂商市场名。
     * Build.MODEL 给的是型号代码（PLK110），用户认不得；
     * 各厂商把可读的市场名放在不同属性里，逐个尝试后回落。
     */
    fun marketName(): String {
        val candidates = listOf(
            "ro.vendor.oplus.market.name",   // OPPO / OnePlus / realme
            "ro.product.marketname",         // 小米 / Redmi / POCO、vivo / iQOO
            "ro.product.vendor.marketname",
            "ro.config.marketing_name",      // 三星
            "ro.vendor.product.display"
        )
        for (key in candidates) {
            val v = readProp(key)
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    /** 经 su 执行任意命令并取回输出（专业模式专用） */
    private fun runViaRoot(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            p.waitFor()
            out
        } catch (_: Exception) {
            ""
        }
    }

    private fun readViaRoot(path: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $path"))
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            p.waitFor()
            out
        } catch (_: Exception) {
            ""
        }
    }

    /** 专业模式：经 root 读取真实的存储型号、调度器与队列深度 */
    private fun detectHardwareInfoViaRoot(): HardwareStorageInfo? {
        val model = readViaRoot("/sys/block/sda/device/model")
        val vendor = readViaRoot("/sys/block/sda/device/vendor")
        if (model.isEmpty() && vendor.isEmpty()) return null

        val schedulerRaw = readViaRoot("/sys/block/sda/queue/scheduler")
        // 调度器文件形如 "[mq-deadline] kyber none"，取方括号里的当前值
        val scheduler = Regex("""\[(.+?)\]""").find(schedulerRaw)?.groupValues?.get(1)
            ?: schedulerRaw.ifEmpty { "未知" }
        val queueDepth = readViaRoot("/sys/block/sda/queue/nr_requests").toIntOrNull() ?: 0
        // 只取实测值，不做任何"这是 UFS 还是 SSD"的判定：
        // 型号字符串本身就是事实，再包一层推断反而引入不确定性。
        // 两个文件各自是独立事实，分别原样展示，不拼接
        val modelLine = model.trim().ifEmpty { vendor.trim() }

        return HardwareStorageInfo(
            // 直接显示型号本身，不归类
            storageType = modelLine,
            deviceModel = modelLine,
            blockDevice = "/dev/block/sda",
            ioScheduler = scheduler,
            queueDepthLimit = if (queueDepth > 0) queueDepth else 32,
            hasRootAccess = true,
            isEmulator = isEmulator(),
            sysfsReadable = true,
            storageTypeInferred = false,
            socModel = Build.SOC_MODEL.ifBlank { "未知" },
            deviceName = marketName(),
            deviceCode = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE,
            storageVendor = vendor.trim(),
            environmentWarning = null
        )
    }

    private fun readFileFirstLine(path: String): String {
        return try {
            val f = File(path)
            if (f.exists() && f.canRead()) f.bufferedReader().use { it.readLine() ?: "" } else ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * root 探测结果**四态**（需求 C1/C2：不可用、被拒绝、超时、成功必须分别表示）。
     * 旧实现把它们全压成一个 false，用户看到的原因常常是错的，也无法追踪。
     */
    enum class RootProbe { OK, NO_SU, DENIED, TIMEOUT }

    /** 授权等待上限：su 弹窗是异步的，不设上限就会无限等（实测表现成「没有任何校验反应」）。 */
    private const val ROOT_PROBE_TIMEOUT_MS = 4000L

    /**
     * root 可用性探测（**带超时**、**可追踪**）。
     *
     * 以 root 管理器（采用按应用授权）为例：root 只对白名单内的应用生效，
     * adb shell 往往拿不到 su，因此不能以 shell 探测结果代替应用侧结果。
     * 这里只看「本应用调用 su 是否成功」。
     *
     * 注意：本函数会阻塞至多 timeoutMs，**不要在主线程调用**；UI 路径请用 probeRootAsync。
     */
    fun probeRoot(timeoutMs: Long = ROOT_PROBE_TIMEOUT_MS): RootProbe {
        val started = System.currentTimeMillis()
        var proc: Process? = null
        return try {
            proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            when {
                !finished -> {
                    com.ufs.bench.util.AppLogger.w(
                        "Root",
                        "root 探测超时（" + (System.currentTimeMillis() - started) + " ms）：" +
                            "授权弹窗可能未被处理，按未授权处理，绝不当作已获权限"
                    )
                    RootProbe.TIMEOUT
                }
                proc.exitValue() != 0 -> {
                    com.ufs.bench.util.AppLogger.w("Root", "root 探测被拒绝：su 退出码=" + proc.exitValue())
                    RootProbe.DENIED
                }
                else -> {
                    val out = proc.inputStream.bufferedReader().use { it.readText() }
                    if (out.contains("uid=0")) {
                        hasRootIdentity = true
                        com.ufs.bench.util.AppLogger.i(
                            "Root", "root 可用（uid=0，用时 " + (System.currentTimeMillis() - started) + " ms）"
                        )
                        RootProbe.OK
                    } else {
                        com.ufs.bench.util.AppLogger.w("Root", "su 成功但输出无 uid=0，判为未获授权")
                        RootProbe.DENIED
                    }
                }
            }
        } catch (e: java.io.IOException) {
            // 设备上根本没有 su 二进制（未 root / 系统无 su）：这是「不可用」，不是「被拒绝」
            com.ufs.bench.util.AppLogger.i("Root", "本机没有可用的 su：" + (e.message ?: "IOException"))
            RootProbe.NO_SU
        } catch (e: Exception) {
            com.ufs.bench.util.AppLogger.w("Root", "root 探测异常：" + (e.message ?: e.toString()))
            RootProbe.DENIED
        } finally {
            runCatching { proc?.destroy() }
        }
    }

    /** UI 路径专用：把可能阻塞数秒的 su 探测移出主线程 */
    suspend fun probeRootAsync(timeoutMs: Long = ROOT_PROBE_TIMEOUT_MS): RootProbe =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { probeRoot(timeoutMs) }

    /**
     * 兼容既有调用点的布尔包装（**阻塞**，最长 ROOT_PROBE_TIMEOUT_MS）。
     * 新代码请优先用 probeRoot / probeRootAsync 以拿到失败原因。
     */
    fun checkRootAccess(): Boolean = probeRoot() == RootProbe.OK

    @Volatile
    private var hasRootIdentity = false

    fun tryDropCaches(): Boolean {
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "sync && echo 3 > /proc/sys/vm/drop_caches")).waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}