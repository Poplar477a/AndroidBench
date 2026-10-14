package com.ufs.bench.core

import android.content.Context
import com.ufs.bench.model.BenchmarkSummary
import com.ufs.bench.model.IometerAccessSpec
import com.ufs.bench.model.RealtimeMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class FioEngineImpl(private val context: Context) : IBenchmarkEngine {
    override val name: String = "FIO Linux Kernel Engine (libfio)"

    private var currentProcess: Process? = null
    private var lastSummary: BenchmarkSummary? = null

    // 寻找可用的 fio 二进制
    val executablePath: String?
        get() {
            val appLibFio = File(context.applicationInfo.nativeLibraryDir, "libfio.so")
            if (appLibFio.exists() && appLibFio.canExecute()) return appLibFio.absolutePath

            val localTmpFio = File("/data/local/tmp/fio")
            if (localTmpFio.exists() && localTmpFio.canExecute()) return localTmpFio.absolutePath

            val paths = System.getenv("PATH")?.split(":") ?: listOf("/system/bin", "/vendor/bin")
            for (p in paths) {
                val f = File(p, "fio")
                if (f.exists() && f.canExecute()) return f.absolutePath
            }
            return null
        }

    override val isAvailable: Boolean
        get() = executablePath != null

    override suspend fun prepareTargetFile(filePath: String, sizeMb: Int, cacheMode: Int): Boolean = withContext(Dispatchers.IO) {
        val exec = executablePath ?: return@withContext false
        val cmd = listOf(
            exec,
            "--name=fio_prep",
            "--filename=$filePath",
            "--size=${sizeMb}M",
            "--direct=1",
            "--ioengine=sync",
            "--rw=write",
            "--bs=1m",
            "--minimal"
        )
        try {
            val p = ProcessBuilder(cmd).start()
            p.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    override fun runBenchmark(filePath: String, spec: IometerAccessSpec): Flow<RealtimeMetric> = callbackFlow {
        val exec = executablePath
        if (exec == null) {
            lastSummary = BenchmarkSummary(
                spec = spec,
                engineName = name,
                success = false,
                errorMessage = "未找到可执行的 FIO 引擎 (可部署 libfio.so 或 /data/local/tmp/fio)"
            )
            close()
            return@callbackFlow
        }

        val fioRw = when {
            spec.percentRandom == 0 && spec.percentRead == 100 -> "read"
            spec.percentRandom == 0 && spec.percentRead == 0 -> "write"
            spec.percentRandom == 100 && spec.percentRead == 100 -> "randread"
            spec.percentRandom == 100 && spec.percentRead == 0 -> "randwrite"
            spec.percentRandom == 100 -> "randrw"
            else -> "rw"
        }

        val cmd = mutableListOf(
            exec,
            "--name=ufs_bench",
            "--filename=$filePath",
            "--size=${spec.fileSizeMb}M",
            "--direct=1",
            "--ioengine=posixaio",
            "--iodepth=${spec.queueDepth}",
            "--numjobs=${spec.workerThreads}",
            "--thread=1",
            "--bs=${spec.blockSizeLabel.lowercase()}",
            "--rw=$fioRw",
            "--runtime=${spec.durationSec}",
            "--time_based=1",
            "--group_reporting=1",
            "--output-format=json+",
            "--status-interval=1"
        )

        if (fioRw.contains("rw")) {
            cmd.add("--rwmixread=${spec.percentRead}")
        }

        try {
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            currentProcess = process

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            var elapsed = 0
            var finalReadBw = 0.0
            var finalWriteBw = 0.0
            var finalReadIops = 0L
            var finalWriteIops = 0L

            while (reader.readLine().also { line = it } != null) {
                line?.let { raw ->
                    val trimmed = raw.trim()
                    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                        try {
                            val json = JSONObject(trimmed)
                            val jobArray = json.optJSONArray("jobs")
                            if (jobArray != null && jobArray.length() > 0) {
                                val job = jobArray.getJSONObject(0)
                                val readObj = job.optJSONObject("read")
                                val writeObj = job.optJSONObject("write")

                                val rBw = (readObj?.optDouble("bw", 0.0) ?: 0.0) / 1024.0
                                val wBw = (writeObj?.optDouble("bw", 0.0) ?: 0.0) / 1024.0
                                val rIops = readObj?.optLong("iops", 0L) ?: 0L
                                val wIops = writeObj?.optLong("iops", 0L) ?: 0L

                                finalReadBw = rBw
                                finalWriteBw = wBw
                                finalReadIops = rIops
                                finalWriteIops = wIops

                                elapsed++
                                trySend(
                                    RealtimeMetric(
                                        readMbps = rBw,
                                        writeMbps = wBw,
                                        readIops = rIops,
                                        writeIops = wIops,
                                        elapsedSeconds = elapsed,
                                        totalSeconds = spec.durationSec
                                    )
                                )
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            process.waitFor()

            lastSummary = BenchmarkSummary(
                spec = spec,
                engineName = name,
                success = true,
                readMbps = finalReadBw,
                writeMbps = finalWriteBw,
                readIops = finalReadIops,
                writeIops = finalWriteIops
            )
            close()
        } catch (e: Exception) {
            lastSummary = BenchmarkSummary(
                spec = spec,
                engineName = name,
                success = false,
                errorMessage = e.message
            )
            close(e)
        }

        awaitClose {
            stopBenchmark()
        }
    }.flowOn(Dispatchers.IO)

    override fun stopBenchmark() {
        currentProcess?.destroy()
        currentProcess = null
    }

    override fun getLastSummary(): BenchmarkSummary? = lastSummary
}
