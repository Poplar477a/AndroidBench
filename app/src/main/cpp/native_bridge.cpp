#include <android/log.h>
#include <jni.h>
#include <string>
#include <cstdint>
#include <time.h>
#include "dynamo_engine.h"

static dynamo::DynamoEngine g_engine;

// 单调时钟（用于进度回调节流）：CLOCK_MONOTONIC 不受系统时间调整影响
static double MonotonicSeconds() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<double>(ts.tv_sec) + static_cast<double>(ts.tv_nsec) * 1e-9;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_ufs_bench_core_DynamoNativeBridge_nativePrepareFile(
    JNIEnv* env,
    jobject /* this */,
    jstring j_file_path,
    jlong j_size_bytes,
    jint j_cache_mode
) {
    const char* path = env->GetStringUTFChars(j_file_path, nullptr);
    std::string err;
    bool ok = g_engine.PrepareTargetFile(path, static_cast<uint64_t>(j_size_bytes), static_cast<uint32_t>(j_cache_mode), err);
    if (!ok) {
        __android_log_print(ANDROID_LOG_ERROR, "DynamoBridge", "PrepareTargetFile failed for %s: %s", path, err.c_str());
    }
    env->ReleaseStringUTFChars(j_file_path, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_ufs_bench_core_DynamoNativeBridge_nativeStop(
    JNIEnv* /* env */,
    jobject /* this */
) {
    g_engine.Stop();
}

// 本 .so 的档位自述：**编译期宏**，用于回答"实际加载进来的到底是哪一档"。
// Kotlin 侧只搬运、不硬编码映射，避免与真实构建漂移。
JNIEXPORT jstring JNICALL
Java_com_ufs_bench_core_DynamoNativeBridge_nativeEngineProfile(
    JNIEnv* env,
    jobject /* this */
) {
    return env->NewStringUTF(dynamo::EngineProfileId());
}

JNIEXPORT jstring JNICALL
Java_com_ufs_bench_core_DynamoNativeBridge_nativeEngineMcpu(
    JNIEnv* env,
    jobject /* this */
) {
    return env->NewStringUTF(dynamo::EngineMcpuName());
}

// 逐档目录（-mcpu 映射表）：由 CMake 配置期生成，UI 直接用它填"逐档元数据"。
JNIEXPORT jstring JNICALL
Java_com_ufs_bench_core_DynamoNativeBridge_nativeEngineProfileCatalog(
    JNIEnv* env,
    jobject /* this */
) {
    return env->NewStringUTF(dynamo::EngineProfileCatalog());
}

// 工具链来源（NDK 内置 Clang / 独立 LLVM 23.1.3）：用户必须能一眼区分
JNIEXPORT jstring JNICALL
Java_com_ufs_bench_core_DynamoNativeBridge_nativeEngineCcOrigin(
    JNIEnv* env,
    jobject /* this */
) {
    return env->NewStringUTF(dynamo::EngineCcOrigin());
}

// 本变体是否为实验性档位（独立 LLVM 工具链构建）
JNIEXPORT jboolean JNICALL
Java_com_ufs_bench_core_DynamoNativeBridge_nativeEngineProfileExperimental(
    JNIEnv* /* env */,
    jobject /* this */
) {
    return dynamo::EngineProfileIsExperimental() ? JNI_TRUE : JNI_FALSE;
}

// 实验性档位名列表（逗号分隔）
JNIEXPORT jstring JNICALL
Java_com_ufs_bench_core_DynamoNativeBridge_nativeEngineExperimentalProfiles(
    JNIEnv* env,
    jobject /* this */
) {
    return env->NewStringUTF(dynamo::EngineExperimentalProfiles());
}

// 完整编译串：UI 不跑测试也能直接展示（避免"新装应用先跑一次才有编译器行"）
JNIEXPORT jstring JNICALL
Java_com_ufs_bench_core_DynamoNativeBridge_nativeEngineToolchain(
    JNIEnv* env,
    jobject /* this */
) {
    return env->NewStringUTF(dynamo::CompilerToolchainInfo().c_str());
}

// 编译期目标 ABI：**权威**答案。可识别"abilist 与实际加载切片不一致"的情况
// （例如带 ARM 翻译的 x86 模拟器：abilist 以 x86_64 开头，系统却选了 arm64 切片）。
JNIEXPORT jstring JNICALL
Java_com_ufs_bench_core_DynamoNativeBridge_nativeEngineAbi(
    JNIEnv* env,
    jobject /* this */
) {
#if defined(__aarch64__)
    return env->NewStringUTF("arm64-v8a");
#elif defined(__arm__)
    return env->NewStringUTF("armeabi-v7a");
#elif defined(__x86_64__)
    return env->NewStringUTF("x86_64");
#elif defined(__i386__)
    return env->NewStringUTF("x86");
#else
    return env->NewStringUTF("unknown");
#endif
}

// 参数：filePath, reqSize, pctRead, pctRnd, qd, workers, fileSizeBytes,
//       durationSec, builtinKernelId, fullMode, specScale, availMemBytes, forceRun,
//       cacheMode, privilege, hasRoot, stopCriterion, stopAfterBytes,
//       maxDurationSec, sampleIntervalMs, professionalStats, callback
// 注意：JNI 参数顺序必须与 Kotlin 侧 external fun 的声明顺序逐字段一致。
JNIEXPORT jobject JNICALL
Java_com_ufs_bench_core_DynamoNativeBridge_nativeRunBenchmark(
    JNIEnv* env,
    jobject /* this */,
    jstring j_file_path,
    jint req_size_bytes,
    jint pct_read,
    jint pct_rnd,
    jint queue_depth,
    jint workers,
    jlong file_size_bytes,
    jint duration_sec,
    jint spec_workload_id,
    jboolean full_mode,
    jint spec_scale,
    jlong avail_mem_bytes,
    jboolean force_run,
    jint cache_mode,
    jint privilege,
    jboolean has_root,
    jint stop_criterion,
    jlong stop_after_bytes,
    jint max_duration_sec,
    jint sample_interval_ms,
    jboolean professional_stats,
    jobject callback
) {
    const char* path = env->GetStringUTFChars(j_file_path, nullptr);

    dynamo::AccessSpecification spec{};
    spec.request_size_bytes = static_cast<uint32_t>(req_size_bytes);
    spec.percent_read = static_cast<uint32_t>(pct_read);
    spec.percent_random = static_cast<uint32_t>(pct_rnd);
    spec.queue_depth = static_cast<uint32_t>(queue_depth);
    spec.worker_threads = static_cast<uint32_t>(workers);
    spec.file_size_bytes = static_cast<uint64_t>(file_size_bytes);
    spec.duration_seconds = static_cast<uint32_t>(duration_sec);
    spec.spec_workload_id = static_cast<uint32_t>(spec_workload_id);
    spec.full_version = (full_mode == JNI_TRUE);
    spec.spec_scale = static_cast<uint32_t>(spec_scale > 0 ? spec_scale : 1);
    spec.avail_mem_bytes = static_cast<uint64_t>(avail_mem_bytes > 0 ? avail_mem_bytes : 0);
    spec.force_run = (force_run == JNI_TRUE);
    spec.cache_mode = static_cast<uint32_t>(cache_mode);
    spec.privilege = static_cast<uint32_t>(privilege);
    spec.has_root = (has_root == JNI_TRUE);
    spec.stop_criterion = static_cast<uint32_t>(stop_criterion);
    spec.stop_after_bytes = static_cast<uint64_t>(stop_after_bytes > 0 ? stop_after_bytes : 0);
    // 安全上限与"按时间"的目标值分开传入，避免容量模式被残留时长掐断
    spec.max_duration_seconds = static_cast<uint32_t>(max_duration_sec > 0 ? max_duration_sec : 0);
    // 采样档位（5/10/25/50/75/100 ms）。非法/未传时退回契约默认档 100 ms；
    // 真正的档位合法性由 Kotlin 侧选择器保证，原生侧只兜底。
    spec.sample_interval_ms = static_cast<uint32_t>(sample_interval_ms > 0 ? sample_interval_ms : 100);
    spec.professional_stats = (professional_stats == JNI_TRUE);

    jmethodID on_progress_mid = nullptr;
    JavaVM* jvm = nullptr;
    env->GetJavaVM(&jvm);
    jobject global_cb = nullptr;

    if (callback != nullptr) {
        global_cb = env->NewGlobalRef(callback);
        jclass cb_class = env->GetObjectClass(callback);
        // onProgress(DDJJDDDIJDDDJJDD)V
        //          读 写 读IOPS 写IOPS 平均延迟 最大延迟 P99 已用秒 跑分 CPU 内存 比值
        //          + 容量模式进度：已完成字节 / 目标字节
        //          + 本区间分读写平均响应延迟（μs，末尾追加两个 D，见 docs/chart-data-contract.md §3）
        // 注意：这里的描述符必须与 Kotlin DynamoCallback.onProgress 完全一致，
        // 不一致时 GetMethodID 返回 null，下面的 lambda 会整体提前 return，
        // 表现为"测试中完全没有任何进度回调"——必须用 logcat 确认而非凭推理。
        on_progress_mid = env->GetMethodID(cb_class, "onProgress", "(DDJJDDDIJDDDJJDD)V");
        if (on_progress_mid == nullptr) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            __android_log_print(ANDROID_LOG_ERROR, "DynamoBridge",
                                "onProgress 签名不匹配：进度回调将完全不可用（UI 不会实时刷新）");
        }
    }

    // 进度回调节流（契约 §4.2）：采样档位最低 5 ms（200 Hz），原样投递给 Kotlin
    // 会把 UI 线程压住，用户看到的是"卡死不刷新"。此处合并到 ~10 Hz；
    // 曲线仍由原生侧按用户档位全速记录，不丢采样点，只是不逐帧推给 UI。
    constexpr double kProgressMinIntervalSec = 0.10;
    // 计数器仅用于 logcat 证据：引擎实际产出多少次、真正投递多少次
    uint64_t progress_produced = 0;
    uint64_t progress_delivered = 0;

    auto progress_fn = [&, last_delivered_at = -1.0e9](
                           const dynamo::ProgressMetric& pm) mutable {
        ++progress_produced;
        if (!jvm || !global_cb || !on_progress_mid) return;
        const double now_s = MonotonicSeconds();
        if (now_s - last_delivered_at < kProgressMinIntervalSec) {
            return;   // 节流：UI 展示的是"当前状态"，丢弃中间帧不影响语义
        }
        last_delivered_at = now_s;
        JNIEnv* thread_env = nullptr;
        bool need_detach = false;
        int get_env_res = jvm->GetEnv(reinterpret_cast<void**>(&thread_env), JNI_VERSION_1_6);
        if (get_env_res == JNI_EDETACHED) {
            if (jvm->AttachCurrentThread(&thread_env, nullptr) != JNI_OK) return;
            need_detach = true;
        }

        thread_env->CallVoidMethod(
            global_cb,
            on_progress_mid,
            pm.current_read_mbps,
            pm.current_write_mbps,
            static_cast<jlong>(pm.current_read_iops),
            static_cast<jlong>(pm.current_write_iops),
            pm.avg_latency_us,
            pm.max_latency_us,
            pm.p99_latency_us,
            static_cast<jint>(pm.elapsed_seconds),
            static_cast<jlong>(pm.live_spec_score),
            pm.live_cpu_metric,
            pm.live_mem_metric,
            pm.live_spec_ratio,
            static_cast<jlong>(pm.bytes_done),
            static_cast<jlong>(pm.target_bytes),
            // 末尾追加两个 D：本采样区间的分读写平均响应延迟（契约 §3）
            pm.read_avg_latency_us,
            pm.write_avg_latency_us
        );

        if (need_detach) {
            jvm->DetachCurrentThread();
        }

        ++progress_delivered;
        if (progress_delivered == 1 || (progress_delivered % 25) == 0) {
            __android_log_print(ANDROID_LOG_INFO, "DynamoBridge",
                "progress #%llu (engine produced %llu): read=%.1f write=%.1f MB/s "
                "readLat=%.1f writeLat=%.1f us elapsed=%us",
                (unsigned long long)progress_delivered,
                (unsigned long long)progress_produced,
                pm.current_read_mbps, pm.current_write_mbps,
                pm.read_avg_latency_us, pm.write_avg_latency_us,
                (unsigned)pm.elapsed_seconds);
        }
    };

    dynamo::BenchmarkResult res = g_engine.RunBenchmark(path, spec, progress_fn);

    // logcat 证据：引擎产出的采样次数 vs 真正投递给 Kotlin 的次数。
    // 两者差距即"节流合并"量；若 produced 为 0，说明回调根本没触发（签名不匹配等）。
    __android_log_print(ANDROID_LOG_INFO, "DynamoBridge",
                        "progress summary: engine produced %llu, delivered %llu "
                        "(professional=%d sample=%ums)",
                        (unsigned long long)progress_produced,
                        (unsigned long long)progress_delivered,
                        (int)spec.professional_stats, (unsigned)spec.sample_interval_ms);
    // CPU 口径证据（仅 benchmark 工作线程）：
    // cores_used = 各核累加（可 >100%），percent_of_cores = 再除以设备核数（0~100%）
    // 注意：SPEC CPU 移植版路径（full_version）当前**未计量**负载 CPU，
    // 此时不打这条日志，避免打印一个会被误读的 0.00。
    if (res.detail.present) {
        __android_log_print(ANDROID_LOG_INFO, "DynamoBridge",
                            "cpu(worker-thread only): cores_used=%.2f percent_of_cores=%.1f%% cores=%u "
                            "(professional=%d)",
                            res.cpu_cores_used, res.detail.cpu_percent_of_cores,
                            res.detail.cpu_core_count, (int)spec.professional_stats);
    }
    if (res.detail.present) {
        __android_log_print(ANDROID_LOG_INFO, "DynamoBridge",
                            "curves: readBw=%zu writeBw=%zu readLat=%zu writeLat=%zu cpu=%zu t=%zu pts",
                            res.detail.curve_read_bw_mbps.size(), res.detail.curve_write_bw_mbps.size(),
                            res.detail.curve_read_lat_us.size(), res.detail.curve_write_lat_us.size(),
                            res.detail.curve_cpu_percent.size(), res.detail.curve_time_s.size());
    }

    env->ReleaseStringUTFChars(j_file_path, path);

    if (global_cb != nullptr) {
        env->DeleteGlobalRef(global_cb);
    }

    // ---- 专业模式：先构造 NativeDetailedStats 子对象 ----
    jobject j_detail = nullptr;
    if (res.detail.present) {
        const dynamo::DetailedStats& d = res.detail;
        auto toArray = [&](const std::vector<double>& v) -> jdoubleArray {
            jdoubleArray arr = env->NewDoubleArray(static_cast<jsize>(v.size()));
            if (arr != nullptr && !v.empty()) {
                env->SetDoubleArrayRegion(arr, 0, static_cast<jsize>(v.size()), v.data());
            }
            return arr;
        };
        // 分读、分写共 6 条曲线，顺序必须与 Kotlin NativeDetailedStats 构造参数一致：
        // 读吞吐 / 写吞吐 / 读延迟 / 写延迟 / CPU / 时间轴
        jdoubleArray readBwArr   = toArray(d.curve_read_bw_mbps);
        jdoubleArray writeBwArr  = toArray(d.curve_write_bw_mbps);
        jdoubleArray readLatArr  = toArray(d.curve_read_lat_us);
        jdoubleArray writeLatArr = toArray(d.curve_write_lat_us);
        jdoubleArray cpuArr      = toArray(d.curve_cpu_percent);
        jdoubleArray tArr        = toArray(d.curve_time_s);

        jclass detCls = env->FindClass("com/ufs/bench/core/NativeDetailedStats");
        // 逐段核对：(p50,p95,p99,p999,max,cpuUtil,timeoutThreshold)=7D
        //           (timeoutCount,ioErrorCount)=2J
        //           (sampleInterval,jitter,worstDrop,steadyState)=4D
        //           (dropEvents)=1J
        //           (cpuPercentOfCores)=1D (cpuCoreCount)=1I
        //           + 6 条曲线 [D[D[D[D[D[D
        jmethodID detInit = env->GetMethodID(
            detCls, "<init>",
            "(DDDDDDDJJDDDDJDI[D[D[D[D[D[D)V");

        // 若签名不匹配，GetMethodID 会挂起异常：此处立即检测并清理，
        // 避免后续 NewObject 触发 CheckJNI 直接 abort 整个进程。
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            __android_log_print(ANDROID_LOG_ERROR, "DynamoBridge",
                                "NativeDetailedStats 构造函数签名不匹配，专业统计将被丢弃");
            detInit = nullptr;
        }
        if (detInit != nullptr) {
        j_detail = env->NewObject(
            detCls, detInit,
            d.p50_latency_us,           // 0
            d.p95_latency_us,           // 1
            d.p99_latency_us,           // 2
            d.p999_latency_us,          // 3
            d.max_latency_us,           // 4
            d.cpu_util_percent,         // 5
            d.timeout_threshold_us,     // 6
            static_cast<jlong>(d.timeout_count),   // 7
            static_cast<jlong>(d.io_error_count),  // 8
            d.sample_interval_ms,       // 9
            d.jitter_percent,           // 10
            d.worst_drop_percent,       // 11
            d.steady_state_mbps,        // 12
            static_cast<jlong>(d.drop_events),     // 13
            d.cpu_percent_of_cores,                // 14：归一化 0~100%
            static_cast<jint>(d.cpu_core_count),   // 15：设备核数
            readBwArr, writeBwArr,                 // 16..17
            readLatArr, writeLatArr,               // 18..19
            cpuArr, tArr);                         // 20..21
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            j_detail = nullptr;
        }
        }
    }

    // 构造 Java 返回对象
    // 签名: (ZLjava/lang/String;DDJJDDDDJDDZLjava/lang/String;DJDDDLjava/lang/String;Ljava/lang/String;JZLcom/ufs/bench/core/NativeDetailedStats;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
    //                                                                                                   ^^^^^^^^^^^^^^^^^^^^^^ 末尾依次是 compilerToolchain / engineProfile / engineMcpu
    jclass res_class = env->FindClass("com/ufs/bench/core/NativeBenchmarkResult");
    jmethodID res_init = env->GetMethodID(
        res_class,
        "<init>",
        "(ZLjava/lang/String;DDJJDDDDJDDZLjava/lang/String;DJDDDLjava/lang/String;Ljava/lang/String;JZLcom/ufs/bench/core/NativeDetailedStats;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"
    );

    jstring j_err = env->NewStringUTF(res.error_message.c_str());
    jstring j_verify = env->NewStringUTF(res.verify_detail.c_str());
    __android_log_print(ANDROID_LOG_INFO, "DynamoBridge",
                        "io_method=[%s] privilege_method=[%s] cache_mode=%u privilege=%u has_root=%d",
                        res.io_method.c_str(), res.privilege_method.c_str(),
                        spec.cache_mode, spec.privilege, (int)spec.has_root);
    // 编译工具链披露（编译期宏生成）：官方要求上报编译器与调优等级，
    // 本移植版是单一固定优化等级，必须如实带出，供结果页与历史报告展示。
    __android_log_print(ANDROID_LOG_INFO, "DynamoBridge",
                        "compiler_toolchain=[%s]", res.compiler_toolchain.c_str());
    jstring j_io = env->NewStringUTF(res.io_method.c_str());
    jstring j_priv = env->NewStringUTF(res.privilege_method.c_str());
    jstring j_toolchain = env->NewStringUTF(res.compiler_toolchain.c_str());
    // 本 .so 的档位自述（编译期宏）：每条结果自带，历史报告也能标注是哪一档跑出来的
    __android_log_print(ANDROID_LOG_INFO, "DynamoBridge",
                        "engine_profile=[%s] engine_mcpu=[%s]",
                        res.engine_profile.c_str(), res.engine_mcpu.c_str());
    jstring j_profile = env->NewStringUTF(res.engine_profile.c_str());
    jstring j_mcpu = env->NewStringUTF(res.engine_mcpu.c_str());
    jobject j_res = env->NewObject(
        res_class,
        res_init,
        res.success ? JNI_TRUE : JNI_FALSE,
        j_err,
        res.total_read_mbps,
        res.total_write_mbps,
        static_cast<jlong>(res.total_read_iops),
        static_cast<jlong>(res.total_write_iops),
        res.min_latency_us,
        res.avg_latency_us,
        res.max_latency_us,
        res.p99_latency_us,
        static_cast<jlong>(res.final_spec_score),
        res.final_cpu_metric,
        res.final_mem_metric,
        res.verified ? JNI_TRUE : JNI_FALSE,
        j_verify,
        res.spec_ratio,
        static_cast<jlong>(res.work_units),
        res.ops_per_sec,
        res.reference_seconds,
        res.measured_seconds,
        j_io,
        j_priv,
        static_cast<jlong>(res.total_bytes_done),
        res.stopped_by_capacity ? JNI_TRUE : JNI_FALSE,
        j_detail,
        j_toolchain,
        j_profile,
        j_mcpu
    );

    return j_res;
}


// ---------------------------------------------------------------------------
// PGO 采集：显式把插桩 profile 落盘
//
// 为什么必须显式调用：插桩库原本只在**进程 exit** 时由编译器运行时的 atexit 处理器
// 写 .profraw。在 Android 上这条路实测不可靠 —— 优雅 exit(0) 之后 files/pgo 依然为空，
// 只在 files/ 下留下一个 profileInstalled 标记（真机实测）。因此增加这个 JNI 钩子，
// 由 Kotlin 在"即将重启进程"这个明确时刻主动触发。
//
// 引用方式必须按构建类型区分（真机实测教训）：
//   · 插桩构建（脚本注入 -DDYNAMO_PGO_HOOK=1）：用**强引用**。弱引用在共享库里会因为
//     运行时符号未进 .dynsym 而在运行期解析成 nullptr，钩子静默返回 false、profile 一个都采不到
//     —— 实测 SDLLVM 运行时就是这样（NDK 那支恰好导出，才让问题看起来"时好时坏"）。
//     强引用会在静态链接期把运行时归档成员拉进来并就地调用，不依赖动态导出。
//   · 非插桩构建：整段被编译掉，函数直接返回 false，不产生未定义符号。
#ifdef DYNAMO_PGO_HOOK
extern "C" void __llvm_profile_write_file(void);
extern "C" int __llvm_profile_set_filename(const char*);
#endif

JNIEXPORT jboolean JNICALL
Java_com_ufs_bench_core_DynamoNativeBridge_nativePgoFlush(
    JNIEnv* env,
    jobject /* this */,
    jstring j_path
) {
#ifndef DYNAMO_PGO_HOOK
    (void)env; (void)j_path;
    return JNI_FALSE;   // 非插桩构建：如实返回"没有 profile 运行时"
#else
    if (j_path != nullptr) {
        const char* c = env->GetStringUTFChars(j_path, nullptr);
        if (c != nullptr) {
            __llvm_profile_set_filename(c);
            env->ReleaseStringUTFChars(j_path, c);
        }
    }
    __llvm_profile_write_file();
    return JNI_TRUE;
#endif
}

} // extern "C"