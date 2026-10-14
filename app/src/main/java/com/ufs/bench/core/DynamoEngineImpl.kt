package com.ufs.bench.core

import com.ufs.bench.model.BenchmarkSummary
import com.ufs.bench.model.IometerAccessSpec
import com.ufs.bench.model.RealtimeMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class DynamoEngineImpl : IBenchmarkEngine {
    // 名字不得写死单一通道：含缓存模式走缓冲 I/O，无缓存参与模式才走 O_DIRECT。
    // 实际生效通道由原生层在结果里如实披露（ioMethod）。
    override val name: String = "Iometer Dynamo Native（原生同步 I/O）"
    override val isAvailable: Boolean = true

    /** 由上层注入的设备可用内存读取器（用于 PC 级内置档位的内存预检） */
    var availableMemoryBytesProvider: (() -> Long)? = null

    /** 由上层注入的 root 状态读取器（决定采用 root 手段还是常规手段） */
    var rootStateProvider: (() -> Boolean)? = null

    private var lastSummary: BenchmarkSummary? = null

    /**
     * logcat 证据用计数器：原生 onProgress 实际投递到 Kotlin 的次数。
     * 专业模式"不实时刷新"的定位必须区分三种可能——回调没触发 / 触发了但 UI 不刷新 /
     * 面板本就要等测试结束；这里的打点是第一种可能的判据。
     */
    private var progressReceived: Long = 0L

    override suspend fun prepareTargetFile(filePath: String, sizeMb: Int, cacheMode: Int): Boolean = withContext(Dispatchers.IO) {
        val sizeBytes = sizeMb.toLong() * 1024L * 1024L
        DynamoNativeBridge.prepareFile(filePath, sizeBytes, cacheMode)
    }

    override fun runBenchmark(filePath: String, spec: IometerAccessSpec): Flow<RealtimeMetric> = callbackFlow {
        val callback = object : DynamoCallback {
            override fun onProgress(
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
                bytesDone: Long,
                targetBytes: Long,
                readAvgLatUs: Double,
                writeAvgLatUs: Double
            ) {
                progressReceived++
                if (progressReceived == 1L || progressReceived % 25L == 0L) {
                    android.util.Log.i(
                        "UFSBench",
                        "onProgress #" + progressReceived +
                            " read=" + "%.1f".format(readMbps) + "MB/s" +
                            " write=" + "%.1f".format(writeMbps) + "MB/s" +
                            " readLat=" + "%.1f".format(readAvgLatUs) + "us" +
                            " writeLat=" + "%.1f".format(writeAvgLatUs) + "us" +
                            " elapsed=" + elapsedSec + "s"
                    )
                }
                trySend(
                    RealtimeMetric(
                        readMbps = readMbps,
                        writeMbps = writeMbps,
                        readIops = readIops,
                        writeIops = writeIops,
                        avgLatencyUs = avgLatUs,
                        readAvgLatencyUs = readAvgLatUs,
                        writeAvgLatencyUs = writeAvgLatUs,
                        maxLatencyUs = maxLatUs,
                        p99LatencyUs = p99LatUs,
                        elapsedSeconds = elapsedSec,
                        totalSeconds = spec.durationSec,
                        specScore = specScore,
                        cpuMetric = cpuMetric,
                        memMetric = memMetric,
                        specRatio = specRatio,
                        bytesDone = bytesDone,
                        targetBytes = targetBytes
                    )
                )
            }
        }

        val fileSizeBytes = spec.fileSizeMb.toLong() * 1024L * 1024L

        // 终止准则互斥：
        //  · 按时间 → durationSec 为用户设定值，安全上限交给原生默认
        //  · 按容量 → 时长为 0（绝不复用用户时长！），安全上限按目标容量与
        //    一个悲观吞吐下限推算，避免目标不可达时无限跑下去
        val byCapacity = spec.stopCriterion == com.ufs.bench.model.StopCriterion.CAPACITY
        val effectiveDurationSec = if (byCapacity) 0 else spec.durationSec
        val safetyCeilingSec = if (!byCapacity) 0 else {
            // 按最慢 20 MB/s 估算，再留 1 分钟余量；下限 5 分钟，上限 6 小时
            val est = spec.targetCapacityMb.toLong() / 20L
            (est + 60L).coerceIn(300L, 21600L).toInt()
        }

        // 加载分档引擎库（若尚未加载）：懒加载入口在这里，UI 之前调用的 configure() 生效
        val loadState = DynamoNativeBridge.loadState
        val nativeRes = DynamoNativeBridge.runBenchmark(
            filePath = filePath,
            reqSizeBytes = spec.requestSizeBytes,
            pctRead = spec.percentRead,
            pctRnd = spec.percentRandom,
            queueDepth = spec.queueDepth,
            workers = spec.workerThreads,
            fileSizeBytes = fileSizeBytes,
            durationSec = effectiveDurationSec,
            builtinKernelId = spec.builtinKernelId,
            portedMode = spec.portedVersion,
            specScale = spec.specTier,
            // forceRun: 跳过 PC 级档位的内存预检，强制运行
            availMemBytes = if (spec.forceRun) 0L else (availableMemoryBytesProvider?.invoke() ?: 0L),
            forceRun = spec.forceRun,
            // 用显式 code 而非 ordinal：枚举顺序调整过一次，ordinal 会错位
            cacheMode = spec.cacheMode.code,
            privilege = spec.privilege.code,
            hasRoot = rootStateProvider?.invoke() ?: false,
            stopCriterion = spec.stopCriterion.ordinal,
            stopAfterBytes = if (spec.stopCriterion ==
                com.ufs.bench.model.StopCriterion.CAPACITY)
                spec.targetCapacityMb.toLong() * 1024L * 1024L else 0L,
            maxDurationSec = safetyCeilingSec,
            // 采样档位：契约六档之一（5/10/25/50/75/100 ms），原生侧按同一集合兜底
            sampleIntervalMs = com.ufs.bench.model.IometerAccessSpec
                .normalizeSampleIntervalMs(spec.sampleIntervalMs),
            professionalStats = spec.privilege.professional,
            callback = callback
        )

        lastSummary = BenchmarkSummary(
            spec = spec,
            engineName = name,
            success = nativeRes.success,
            errorMessage = nativeRes.errorMessage,
            readMbps = nativeRes.totalReadMbps,
            writeMbps = nativeRes.totalWriteMbps,
            readIops = nativeRes.totalReadIops,
            writeIops = nativeRes.totalWriteIops,
            avgLatencyUs = nativeRes.avgLatencyUs,
            p95LatencyUs = 0.0,
            p99LatencyUs = nativeRes.p99LatencyUs,
            maxLatencyUs = nativeRes.maxLatencyUs,
            specScore = nativeRes.finalSpecScore,
            cpuMetric = nativeRes.finalCpuMetric,
            memMetric = nativeRes.finalMemMetric,
            verified = nativeRes.verified,
            verifyDetail = nativeRes.verifyDetail ?: "",
            specRatio = nativeRes.specRatio,
            referenceSeconds = nativeRes.referenceSeconds,
            measuredSeconds = nativeRes.measuredSeconds,
            workUnits = nativeRes.workUnits,
            ioMethod = nativeRes.ioMethod ?: "",
            privilegeMethod = nativeRes.privilegeMethod ?: "",
            totalBytesDone = nativeRes.totalBytesDone,
            stoppedByCapacity = nativeRes.stoppedByCapacity,
            // 编译工具链：原生层编译期宏生成，原样搬运（绝不在 Kotlin 侧拼字符串）
            compilerToolchain = nativeRes.compilerToolchain ?: "",
            // -mcpu 分档：生效档取自原生编译期宏（权威）；请求档与降级原因来自加载器
            engineProfile = nativeRes.engineProfile ?: loadState.activeProfile,
            engineMcpu = nativeRes.engineMcpu ?: loadState.activeMcpu,
            engineProfileRequested = loadState.requestedProfile,
            engineProfileFallback = loadState.fallback,
            engineProfileNote = loadState.note,
            detail = nativeRes.detail
        )

        close()

        awaitClose {
            stopBenchmark()
        }
    }.flowOn(Dispatchers.IO)

    override fun stopBenchmark() {
        DynamoNativeBridge.stop()
    }

    override fun getLastSummary(): BenchmarkSummary? = lastSummary
}