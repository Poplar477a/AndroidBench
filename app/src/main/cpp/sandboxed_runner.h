// ============================================================================
//  sandboxed_runner.h —— 外部预编译二进制的特权分离执行舱（BYOB 安全底座）
//
//  设计目标（V3 规划 M1.2/M1.3）：即便 App 以 root 运行，外部二进制也**不得**获得任何特权：
//    1) unshare(CLONE_NEWNET)      物理断网（失败则拒绝执行 —— 失败关闭，不放行）
//    2) setgroups/setgid/setuid    降到非特权 uid/gid（先 gid 后 uid）
//    3) capset(全部清空)           清空 Linux Capabilities
//    4) PR_SET_NO_NEW_PRIVS        封死 SUID 等二次提权
//    5) PR_SET_SECCOMP(BPF)        系统调用白名单，违规 SECCOMP_RET_KILL_PROCESS
//    6) setrlimit                  内存/文件/CPU 时间/进程数配额
//  ============================================================================
#ifndef DYNAMO_SANDBOXED_RUNNER_H
#define DYNAMO_SANDBOXED_RUNNER_H

#include <cstdint>
#include <string>
#include <vector>

namespace dynamo {
namespace sandbox {

struct Limits {
    uint64_t mem_bytes   = 2ull * 1024 * 1024 * 1024;   // RLIMIT_AS
    uint64_t file_bytes  = 100ull * 1024 * 1024;        // RLIMIT_FSIZE
    uint32_t cpu_seconds = 600;                         // RLIMIT_CPU
    uint32_t max_procs   = 64;                          // RLIMIT_NPROC
    uint32_t wall_seconds = 600;                        // 墙钟上限（父进程强杀）
    bool     deny_network = true;                       // 必须成功断网
    bool     pin_prime_cores = false;      // 绑到最高频（prime）核心（对标 run_boost 的绑核）
    double   cooldown_celsius = 0.0;       // >0 时：热区温度高于该值则等待降温
    uint32_t cooldown_timeout_s = 60;      // 降温等待上限
    bool     audit_only = false;  // 审计模式：默认动作改为 LOG（记录但不拦），用于枚举真实用到的系统调用
};

struct Result {
    bool        started = false;      // 是否成功进入沙箱并 exec
    bool        netns_applied = false;
    int         affinity_cpus = 0;     // 实际绑定的核心数（0=未绑）
    double      cooldown_waited_s = 0.0; // 降温等待耗时
    double      temp_before_c = 0.0;   // 执行前热区最高温
    double      temp_after_c = 0.0;    // 执行后热区最高温 // 是否成功应用网络命名空间隔离（内核不支持时为 false，改由 seccomp 兜底）
    int         exit_code = -1;       // 正常退出码
    int         term_signal = 0;      // 被杀信号（SIGSYS=31 表示 seccomp 拦截）
    double      seconds = 0.0;        // 墙钟耗时
    long        max_rss_kb = 0;       // 峰值内存
    std::string stdout_text;
    std::string stderr_text;
    std::string error;                // 沙箱建立失败原因（此时不执行）
};

// 以沙箱方式运行外部二进制；drop_uid/gid 为目标非特权身份（如 untrusted_app）。
Result RunIsolated(const std::string& exe,
                   const std::vector<std::string>& args,
                   const std::string& workdir,
                   const Limits& limits,
                   uint32_t drop_uid,
                   uint32_t drop_gid,
                   uint64_t* out_seccomp_denials);

// 供 JNI/Kotlin 侧做静态准入时复用：解析 ELF 的 DT_NEEDED（返回依赖库名）
std::vector<std::string> ReadElfNeeded(const std::string& path);

}  // namespace sandbox
}  // namespace dynamo

#endif  // DYNAMO_SANDBOXED_RUNNER_H
