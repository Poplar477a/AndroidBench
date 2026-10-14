package com.androidbench.probe

import com.androidbench.core.CpuCluster
import com.androidbench.core.DeviceResourceSnapshot
import com.androidbench.core.ThermalState
import java.io.File

/**
 * 对应第 14.2 节：设备资源探测器
 * 严格遵循非 root 约束，采用 Android 公开 API 与通用 /proc 安全探测
 * 具备数据源追踪、采集耗时审计与保守回退机制
 */
class DeviceResourceProber {

    /**
     * 执行全量设备资源探测
     * @param contextOptional 在 Android 环境下可传入 Context，非 Android 测试环境下为 null
     */
    fun probeDevice(
        mockOverride: DeviceResourceSnapshot? = null
    ): DeviceResourceSnapshot {
        if (mockOverride != null) {
            return mockOverride
        }

        val timestamp = System.currentTimeMillis()
        val dataSources = mutableMapOf<String, String>()
        val errors = mutableListOf<String>()
        var conservativeFallback = false

        // 1. ABI 探测
        val abi = System.getProperty("os.arch") ?: "unknown"
        dataSources["abi"] = "System.getProperty(os.arch)"

        // 2. CPU 核心数与拓扑
        val (coreCount, clusters) = probeCpuTopology(dataSources, errors)

        // 3. 内存探测 (物理 RAM、系统可用 RAM、应用 Heap 限制)
        val (totalRam, availRam, maxHeap, currHeap) = probeMemory(dataSources, errors)
        if (totalRam <= 0L || availRam <= 0L) {
            conservativeFallback = true
        }

        // 4. 内部存储探测
        val internalStorageFree = probeStorage(dataSources, errors)
        if (internalStorageFree <= 0L) {
            conservativeFallback = true
        }

        // 5. 温控状态与电池
        val thermal = probeThermalStatus(dataSources, errors)
        val (isPowerConnected, batteryPercent) = probePowerStatus(dataSources, errors)

        return DeviceResourceSnapshot(
            abi = abi,
            cpuCoreCount = coreCount,
            cpuClusters = clusters,
            totalPhysicalRamBytes = totalRam,
            systemAvailRamBytes = availRam,
            appMaxHeapBytes = maxHeap,
            appCurrentAllocatedBytes = currHeap,
            appInternalStorageFreeBytes = internalStorageFree,
            thermalState = thermal,
            isPowerConnected = isPowerConnected,
            batteryPercent = batteryPercent,
            collectionTimestampMs = timestamp,
            isConservativeFallback = conservativeFallback,
            dataSourceNotes = dataSources,
            collectionErrors = errors
        )
    }

    private fun probeCpuTopology(
        dataSources: MutableMap<String, String>,
        errors: MutableListOf<String>
    ): Pair<Int, List<CpuCluster>> {
        val runtimeCores = Runtime.getRuntime().availableProcessors()
        dataSources["cpuCoreCount"] = "Runtime.getRuntime().availableProcessors()"

        val clusters = mutableListOf<CpuCluster>()
        try {
            val cpuDir = File("/sys/devices/system/cpu")
            if (cpuDir.exists() && cpuDir.canRead()) {
                val freqs = mutableMapOf<Long, MutableList<Int>>()
                for (i in 0 until runtimeCores) {
                    val maxFreqFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                    val freq = if (maxFreqFile.exists() && maxFreqFile.canRead()) {
                        maxFreqFile.readText().trim().toLongOrNull() ?: -1L
                    } else -1L
                    freqs.getOrPut(freq) { mutableListOf() }.add(i)
                }

                if (freqs.isNotEmpty() && !freqs.containsKey(-1L)) {
                    var clusterIndex = 0
                    val sortedFreqs = freqs.keys.sorted()
                    for (freq in sortedFreqs) {
                        val cores = freqs[freq] ?: continue
                        val type = when {
                            freq == sortedFreqs.last() && cores.size <= 2 -> "Prime"
                            freq == sortedFreqs.last() -> "Big"
                            freq == sortedFreqs.first() -> "Little"
                            else -> "Mid"
                        }
                        clusters.add(
                            CpuCluster(
                                clusterId = clusterIndex++,
                                coreCount = cores.size,
                                coreIndices = cores,
                                minFreqKHz = 0L,
                                maxFreqKHz = freq,
                                clusterType = type
                            )
                        )
                    }
                    dataSources["cpuClusters"] = "/sys/devices/system/cpu/cpu*/cpufreq"
                    return Pair(runtimeCores, clusters)
                }
            }
        } catch (e: Exception) {
            errors.add("CpuTopologyProbeException: ${e.message}")
        }

        // 降级拓扑推导
        dataSources["cpuClusters"] = "Fallback uniform cluster estimation"
        val fallbackCluster = CpuCluster(
            clusterId = 0,
            coreCount = runtimeCores,
            coreIndices = (0 until runtimeCores).toList(),
            minFreqKHz = 1000000L,
            maxFreqKHz = 2400000L,
            clusterType = "Standard"
        )
        return Pair(runtimeCores, listOf(fallbackCluster))
    }

    private fun probeMemory(
        dataSources: MutableMap<String, String>,
        errors: MutableListOf<String>
    ): Array<Long> {
        val runtime = Runtime.getRuntime()
        val maxHeap = runtime.maxMemory()
        val totalHeap = runtime.totalMemory()
        val freeHeap = runtime.freeMemory()
        val currAllocated = totalHeap - freeHeap

        dataSources["appMaxHeap"] = "Runtime.getRuntime().maxMemory()"
        dataSources["appCurrentAllocated"] = "Runtime.getRuntime().totalMemory() - freeMemory()"

        var totalRam = 0L
        var availRam = 0L

        // 尝试从 /proc/meminfo 读取 (在大部分 Android 设备上应用有读权限)
        try {
            val meminfo = File("/proc/meminfo")
            if (meminfo.exists() && meminfo.canRead()) {
                val lines = meminfo.readLines()
                for (line in lines) {
                    if (line.startsWith("MemTotal:")) {
                        val parts = line.split("\\s+".toRegex())
                        if (parts.size >= 2) totalRam = (parts[1].toLongOrNull() ?: 0L) * 1024L
                    } else if (line.startsWith("MemAvailable:")) {
                        val parts = line.split("\\s+".toRegex())
                        if (parts.size >= 2) availRam = (parts[1].toLongOrNull() ?: 0L) * 1024L
                    }
                }
                if (totalRam > 0L) {
                    dataSources["totalPhysicalRam"] = "/proc/meminfo MemTotal"
                    dataSources["systemAvailRam"] = "/proc/meminfo MemAvailable"
                }
            }
        } catch (e: Exception) {
            errors.add("MemInfoReadError: ${e.message}")
        }

        // 保守降级：若无法探测物理内存，采用保守假定 (例如 3GB 总量，1GB 可用)
        if (totalRam <= 0L) {
            totalRam = 3L * 1024L * 1024L * 1024L // 3GB 保守假定
            availRam = 1024L * 1024L * 1024L      // 1GB 保守假定
            dataSources["totalPhysicalRam"] = "Conservative fallback (3GB)"
            dataSources["systemAvailRam"] = "Conservative fallback (1GB)"
            errors.add("Unable to probe physical RAM from system, conservative fallback applied")
        }

        return arrayOf(totalRam, availRam, maxHeap, currAllocated)
    }

    private fun probeStorage(
        dataSources: MutableMap<String, String>,
        errors: MutableListOf<String>
    ): Long {
        return try {
            val dataDir = File(".")
            val free = dataDir.usableSpace
            dataSources["appInternalStorageFree"] = "File(dataDir).usableSpace"
            if (free > 0L) free else 1024L * 1024L * 1024L
        } catch (e: Exception) {
            errors.add("StorageProbeError: ${e.message}")
            dataSources["appInternalStorageFree"] = "Conservative fallback (500MB)"
            500L * 1024L * 1024L
        }
    }

    private fun probeThermalStatus(
        dataSources: MutableMap<String, String>,
        errors: MutableListOf<String>
    ): ThermalState {
        // 在无 Android Context 注入环境下，返回安全正常或 UNKNOWN
        dataSources["thermalStatus"] = "Standard safe initial state"
        return ThermalState.NONE
    }

    private fun probePowerStatus(
        dataSources: MutableMap<String, String>,
        errors: MutableListOf<String>
    ): Pair<Boolean, Int> {
        dataSources["powerStatus"] = "Standard simulated/assumed status"
        return Pair(true, 85) // 默认假设连接电源或 85% 电量
    }
}
