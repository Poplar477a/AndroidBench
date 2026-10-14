package com.ufs.bench.util

import java.io.File

/**
 * ============================================================================
 *  绑核建议（只给建议，不改系统调度）
 *
 *  为什么只给建议：
 *    · 应用自身处于 zygote 的 cpuset 里，**没有权限**修改自己或他人的 CPU 亲和性；
 *    · 真机实测部分机型 cpuset 只放行一部分核（例如 Redmi SM7475 只允许 0-5，
 *      把线程绑到 prime 核 7 会被系统直接拒绝，实测 allowed_picked=0）。
 *    因此本模块只输出"该绑到哪些核、绑哪个线程、用 Scene 怎么点"，
 *    由用户用 Scene 之类的外部工具对自己设备上的特定线程操作。
 * ============================================================================
 */
object CpuAffinityAdvice {

    private fun read(p: String): String? = try {
        val f = File(p)
        if (f.isFile) f.readText().trim() else null
    } catch (t: Throwable) {
        null
    }

    /** 解析 "0-3,7" 形式 */
    private fun parseCpuList(s: String): List<Int> = s.split(',').flatMap { part ->
        val t = part.trim()
        if (t.contains('-')) {
            val ends = t.split('-').mapNotNull { it.toIntOrNull() }
            if (ends.size == 2) (ends[0]..ends[1]).toList() else emptyList()
        } else listOfNotNull(t.toIntOrNull())
    }

    private fun maxFreqKhz(cpu: Int): Long =
        read("/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/cpuinfo_max_freq")?.toLongOrNull()
            ?: read("/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/scaling_max_freq")?.toLongOrNull()
            ?: 0L

    private fun clusterName(index: Int, total: Int): String = when {
        total <= 1 -> "统一核"
        index == 0 -> "小核"
        index == total - 1 -> if (total >= 3) "超大核" else "大核"
        else -> "大核"
    }

    /** 应用自身进程的放行集合（读 /proc/self/status 的 Cpus_allowed_list，普通应用可读） */
    private fun selfAllowed(): List<Int>? {
        val txt = read("/proc/self/status") ?: return null
        val line = txt.lineSequence().firstOrNull { it.startsWith("Cpus_allowed_list:") } ?: return null
        return parseCpuList(line.substringAfter(':').trim()).takeIf { it.isNotEmpty() }
    }

    /** 全机在线核（/sys/devices/system/cpu/online）：经 root 沙箱启动的基准进程可用全核 */
    private fun onlineAll(): List<Int>? =
        read("/sys/devices/system/cpu/online")?.let { parseCpuList(it).takeIf { c -> c.isNotEmpty() } }

    /**
     * @param binName 组件包内的可执行文件名（= 进程名 = 单线程内核的主线程名）
     */
    fun advice(binName: String): List<String> {
        val out = ArrayList<String>()
        val cpus = parseCpuList(read("/sys/devices/system/cpu/present") ?: "0-7")
        if (cpus.isEmpty()) return out

        // 按最高频聚类（同频视为同一簇）
        val grouped = cpus.map { it to maxFreqKhz(it) }.filter { it.second > 0 }
            .groupBy({ it.second }, { it.first }).toSortedMap()
        val clusters = grouped.map { (khz, list) -> Triple(list.sorted(), khz, 0) }
            .mapIndexed { i, t -> Triple(t.first, t.second, i) }

        val selfSet = selfAllowed()
        val allSet = onlineAll() ?: cpus
        out.add("绑核建议（本应用不改系统调度，用 Scene 等外部工具对特定线程操作）：")
        out.add("  · 目标进程：" + binName + "（组件包内的可执行文件；经 root 沙箱启动）")
        out.add("  · 目标线程：主线程（同名）；多线程内核可只绑它的 worker 线程，避免绑到 UI/系统线程")

        clusters.forEachIndexed { i, (list, khz, _) ->
            val name = clusterName(i, clusters.size)
            val selfOk = selfSet == null || list.all { it in selfSet }
            out.add(String.format("  · %-6s CPU %-10s 最高 %s GHz%s",
                name, list.joinToString(","), String.format("%.2f", khz / 1e6),
                if (selfOk) "" else "（不在**应用自身**放行集合内：绑应用自己的线程会被拒；绑基准进程可以）"))
        }

        val best = clusters.lastOrNull() ?: return out
        out.add("  · 推荐绑定：" + best.first.joinToString(",") +
            "（" + clusterName(clusters.indexOf(best), clusters.size) + "，最高频）")
        if (selfSet != null) {
            out.add("  · 放行集合：应用自身 " + selfSet.joinToString(",") +
                "；基准进程（root 沙箱）" + allSet.joinToString(","))
            val primeOut = best.first.filter { it !in selfSet }
            if (primeOut.isNotEmpty()) {
                out.add("    注：CPU " + primeOut.joinToString(",") +
                    " 不在应用自身的集合里（实测该机为 0-4,6），所以绑应用自己的线程无效；" +
                    "基准进程由 su 沙箱启动（集合为全核），用 Scene 绑它是有效的")
            }
        }
        out.add("  · Scene 操作：进程管理 → 找到进程 " + binName +
            "（按应用分组时它在 com.ufs.bench.lab 名下）→ CPU 亲和性/线程绑定 → 勾选上面的推荐核心；" +
            "只想处理基准线程时，在 Scene 的线程列表里按上面的线程名挑")
        out.add("  · 说明：单次运行受热与调度抖动影响更大；绑核收益主要体现在连续多轮对比测试")
        return out
    }
}

