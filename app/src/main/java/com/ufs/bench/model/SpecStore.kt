package com.ufs.bench.model

import android.content.Context

/**
 * 测试配置持久化。
 *
 * 此前配置只存在于 ViewModel 的内存状态里，进程退出即丢失，
 * 表现为「选了专业模式，退出重进又回到常规模式」。
 *
 * 枚举一律按 name 存取而非 ordinal —— ordinal 会随枚举顺序调整而错位，
 * 这正是之前 JNI 参数映射踩过的坑。
 */
object SpecStore {

    private const val PREF = "ufs_bench_spec"
    private const val KEY_SPEC = "current_spec"

    // ---------- 全局设置（与应用生命周期同寿，不随单次测试变化） ----------

    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    private const val KEY_PROFESSIONAL = "professional_mode"
    private const val KEY_CACHE_PARTICIPATES = "cache_participates"
    private const val KEY_SAMPLE_INTERVAL_MS = "sample_interval_ms"

    /** 新手引导完成状态（首次开启给出引导，支持跳过） */
    fun loadOnboardingCompleted(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean(KEY_ONBOARDING_COMPLETED, false)
        } catch (_: Exception) {
            false
        }
    }

    fun saveOnboardingCompleted(context: Context, completed: Boolean = true) {
        try {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
        } catch (_: Exception) {
        }
    }

    /** 专业模式开关（默认关闭 = 常规模式） */
    fun loadProfessionalMode(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean(KEY_PROFESSIONAL, false)
        } catch (_: Exception) {
            false
        }
    }

    fun saveProfessionalMode(context: Context, enabled: Boolean) {
        try {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PROFESSIONAL, enabled).apply()
        } catch (_: Exception) {
        }
    }

    /**
     * 缓存是否参与（全局设置）。
     * true  = 含缓存（默认）：缓冲 I/O，数值含页缓存命中收益
     * false = 无缓存参与：O_DIRECT + fadvise(DONTNEED)，尽量逼近闪存裸速
     */
    fun loadCacheParticipates(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean(KEY_CACHE_PARTICIPATES, true)
        } catch (_: Exception) {
            true
        }
    }

    fun saveCacheParticipates(context: Context, participates: Boolean) {
        try {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_CACHE_PARTICIPATES, participates).apply()
        } catch (_: Exception) {
        }
    }

    // ---------- 采样档位（契约 §1：5/10/25/50/75/100 ms） ----------

    /**
     * 采样档位（毫秒）。单独存一份，供选择器在恢复配置之前也能读到用户上次的选择；
     * 读取时一律经 [IometerAccessSpec.normalizeSampleIntervalMs] 钳制到合法六档。
     */
    fun loadSampleIntervalMs(context: Context): Int {
        return try {
            val v = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getInt(KEY_SAMPLE_INTERVAL_MS, IometerAccessSpec.DEFAULT_SAMPLE_INTERVAL_MS)
            IometerAccessSpec.normalizeSampleIntervalMs(v)
        } catch (_: Exception) {
            IometerAccessSpec.DEFAULT_SAMPLE_INTERVAL_MS
        }
    }

    fun saveSampleIntervalMs(context: Context, ms: Int) {
        try {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_SAMPLE_INTERVAL_MS, IometerAccessSpec.normalizeSampleIntervalMs(ms))
                .apply()
        } catch (_: Exception) {
        }
    }

    // ---------- 上次使用的测试配置 ----------

    fun save(context: Context, spec: IometerAccessSpec) {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val e = p.edit()
        e.putString(KEY_SPEC, encode(spec))
        e.putString("mode", spec.mode.name)
        e.putString("cache_mode", spec.cacheMode.name)
        e.putString("privilege", spec.privilege.name)
        e.putString("stop_criterion", spec.stopCriterion.name)
        e.putString("tier", spec.tier.name)
        // 采样档位是全局设置，单独存一份便于选择器独立读取
        e.putInt(KEY_SAMPLE_INTERVAL_MS, IometerAccessSpec.normalizeSampleIntervalMs(spec.sampleIntervalMs))
        e.apply()
    }

    /** 读取上次配置；无记录或解析失败时返回 null，由调用方回退到默认预设 */
    fun load(context: Context): IometerAccessSpec? {
        return try {
            val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val blob = p.getString(KEY_SPEC, null) ?: return null
            decode(context, blob)
        } catch (_: Exception) {
            null
        }
    }

    private fun encode(s: IometerAccessSpec): String {
        return listOf(
            s.name.replace("|", " "),
            s.mode.name,
            s.requestSizeBytes.toString(),
            s.percentRead.toString(),
            s.percentRandom.toString(),
            s.queueDepth.toString(),
            s.workerThreads.toString(),
            s.fileSizeMb.toString(),
            s.durationSec.toString(),
            s.builtinKernelId.toString(),
            if (s.portedVersion) "1" else "0",
            s.tier.name,
            s.cacheMode.name,
            s.privilege.name,
            s.stopCriterion.name,
            s.targetCapacityMb.toString(),
            // 字段 16：采样档位（旧记录没有该字段，decode 时按默认档兜底）
            IometerAccessSpec.normalizeSampleIntervalMs(s.sampleIntervalMs).toString()
        ).joinToString("|")
    }

    private fun decode(context: Context, blob: String): IometerAccessSpec? {
        val f = blob.split("|")
        if (f.size < 16) return null
        return try {
            IometerAccessSpec(
                name = f[0].ifBlank { "自定义存储测试" },
                description = "已恢复上次配置",
                mode = runCatching { BenchmarkMode.valueOf(f[1]) }.getOrDefault(BenchmarkMode.STANDARD_READ_WRITE),
                requestSizeBytes = f[2].toIntOrNull() ?: 4096,
                percentRead = f[3].toIntOrNull() ?: 70,
                percentRandom = f[4].toIntOrNull() ?: 100,
                queueDepth = f[5].toIntOrNull() ?: 8,
                workerThreads = f[6].toIntOrNull() ?: 1,
                fileSizeMb = f[7].toIntOrNull() ?: 256,
                durationSec = f[8].toIntOrNull() ?: 5,
                builtinKernelId = f[9].toIntOrNull() ?: 0,
                portedVersion = f[10] == "1",
                specTier = runCatching { SpecTier.valueOf(f[11]) }.getOrDefault(SpecTier.TEST).let {
                    // 兼容旧记录：name 不匹配时回落到 TEST
                    it
                }.level,
                cacheMode = runCatching { CacheMode.valueOf(f[12]) }.getOrDefault(CacheMode.WITH_CACHE),
                privilege = runCatching { RunPrivilege.valueOf(f[13]) }.getOrDefault(RunPrivilege.NORMAL),
                stopCriterion = runCatching { StopCriterion.valueOf(f[14]) }.getOrDefault(StopCriterion.TIME),
                targetCapacityMb = f[15].toIntOrNull() ?: 1024,
                // 兼容旧记录：16 字段的旧 blob 没有采样档位，回退到全局设置/默认档
                sampleIntervalMs = if (f.size > 16) {
                    IometerAccessSpec.normalizeSampleIntervalMs(
                        f[16].toIntOrNull() ?: IometerAccessSpec.DEFAULT_SAMPLE_INTERVAL_MS
                    )
                } else {
                    loadSampleIntervalMs(context)
                }
            )
        } catch (_: Exception) {
            null
        }
    }
}