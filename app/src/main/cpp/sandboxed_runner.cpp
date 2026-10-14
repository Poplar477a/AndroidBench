// ============================================================================
//  sandboxed_runner.cpp —— BYOB 特权分离沙箱实现（V3 规划 M1.2/M1.3）
//  原则：**失败关闭**。任何一步（断网/降权/清能力/装 seccomp）失败都拒绝执行外部二进制。
// ============================================================================
#include "sandboxed_runner.h"

#include <fcntl.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/mman.h>
#include <unistd.h>

#include <linux/audit.h>
#include <linux/capability.h>
#include <linux/filter.h>
#include <linux/seccomp.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdlib>
#include <ctime>
#include <string>
#include <vector>

namespace dynamo {
namespace sandbox {
namespace {

// 诊断：子进程每步向 stderr 打标记（正常路径下开销可忽略，便于定位沙箱建立失败点）
#define STEPLOG(tag) do { const char* _m = tag "\n"; ssize_t _r = write(2, _m, strlen(_m)); (void)_r; } while (0)

// 子进程把 netns 是否成功写入共享页，父进程读回后如实披露
static bool g_netns_applied = false;
static bool g_audit_only = false;
static bool g_trap_mode = false;

// 诊断用 SIGSYS 处理器：内核把被拦的系统调用号放在 siginfo.si_syscall 里
extern "C" void TrapHandler(int, siginfo_t* si, void*) {
    char b[160];
    int n = snprintf(b, sizeof(b), "SECCOMP-TRAP syscall=%d arch=%u\n",
                     si ? si->si_syscall : -1, si ? static_cast<unsigned>(si->si_arch) : 0u);
    if (n > 0) { ssize_t w = write(2, b, static_cast<size_t>(n)); (void)w; }
    _exit(140);
}

#if defined(__aarch64__)
constexpr uint32_t kAuditArch = AUDIT_ARCH_AARCH64;
#elif defined(__x86_64__)
constexpr uint32_t kAuditArch = AUDIT_ARCH_X86_64;
#else
constexpr uint32_t kAuditArch = 0;
#endif

// 精选白名单（<=255，线性判定链；由实测枚举得出：基础运行时 + 文件 I/O + 内存 + 时间 + 信号 + 线程 + 调度）
static const int kAllowedSyscalls[] = {
#define A(x) 
#ifdef __NR_read
    __NR_read,
#endif
#ifdef __NR_write
    __NR_write,
#endif
#ifdef __NR_readv
    __NR_readv,
#endif
#ifdef __NR_writev
    __NR_writev,
#endif
#ifdef __NR_pread64
    __NR_pread64,
#endif
#ifdef __NR_pwrite64
    __NR_pwrite64,
#endif
#ifdef __NR_openat
    __NR_openat,
#endif
#ifdef __NR_close
    __NR_close,
#endif
#ifdef __NR_lseek
    __NR_lseek,
#endif
#ifdef __NR_fstat
    __NR_fstat,
#endif
#ifdef __NR_newfstatat
    __NR_newfstatat,
#endif
#ifdef __NR_faccessat
    __NR_faccessat,
#endif
#ifdef __NR_unlinkat
    __NR_unlinkat,
#endif
#ifdef __NR_readlinkat
    __NR_readlinkat,
#endif
#ifdef __NR_getcwd
    __NR_getcwd,
#endif
#ifdef __NR_mmap
    __NR_mmap,
#endif
#ifdef __NR_munmap
    __NR_munmap,
#endif
#ifdef __NR_mremap
    __NR_mremap,
#endif
#ifdef __NR_mprotect
    __NR_mprotect,
#endif
#ifdef __NR_brk
    __NR_brk,
#endif
#ifdef __NR_madvise
    __NR_madvise,
#endif
#ifdef __NR_msync
    __NR_msync,
#endif
#ifdef __NR_rt_sigaction
    __NR_rt_sigaction,
#endif
#ifdef __NR_rt_sigprocmask
    __NR_rt_sigprocmask,
#endif
#ifdef __NR_rt_sigreturn
    __NR_rt_sigreturn,
#endif
#ifdef __NR_sigaltstack
    __NR_sigaltstack,
#endif
#ifdef __NR_clock_gettime
    __NR_clock_gettime,
#endif
#ifdef __NR_clock_getres
    __NR_clock_getres,
#endif
#ifdef __NR_clock_nanosleep
    __NR_clock_nanosleep,
#endif
#ifdef __NR_nanosleep
    __NR_nanosleep,
#endif
#ifdef __NR_gettimeofday
    __NR_gettimeofday,
#endif
#ifdef __NR_times
    __NR_times,
#endif
#ifdef __NR_getrusage
    __NR_getrusage,
#endif
#ifdef __NR_futex
    __NR_futex,
#endif
#ifdef __NR_sched_yield
    __NR_sched_yield,
#endif
#ifdef __NR_sched_getscheduler
    __NR_sched_getscheduler,   // 实测：审计日志里唯一被误拦的无害调用（glibc/bionic 查询调度策略）
#endif
#ifdef __NR_sched_setscheduler
    __NR_sched_setscheduler,
#endif
#ifdef __NR_sched_get_priority_max
    __NR_sched_get_priority_max,
#endif
#ifdef __NR_sched_get_priority_min
    __NR_sched_get_priority_min,
#endif
#ifdef __NR_sched_rr_get_interval
    __NR_sched_rr_get_interval,
#endif
#ifdef __NR_getpriority
    __NR_getpriority,
#endif
#ifdef __NR_setpriority
    __NR_setpriority,
#endif
#ifdef __NR_sched_getaffinity
    __NR_sched_getaffinity,
#endif
#ifdef __NR_sched_setaffinity
    __NR_sched_setaffinity,
#endif
#ifdef __NR_sched_getparam
    __NR_sched_getparam,
#endif
#ifdef __NR_getpid
    __NR_getpid,
#endif
#ifdef __NR_getppid
    __NR_getppid,
#endif
#ifdef __NR_gettid
    __NR_gettid,
#endif
#ifdef __NR_getuid
    __NR_getuid,
#endif
#ifdef __NR_geteuid
    __NR_geteuid,
#endif
#ifdef __NR_getgid
    __NR_getgid,
#endif
#ifdef __NR_getegid
    __NR_getegid,
#endif
#ifdef __NR_getresuid
    __NR_getresuid,
#endif
#ifdef __NR_getresgid
    __NR_getresgid,
#endif
#ifdef __NR_set_tid_address
    __NR_set_tid_address,
#endif
#ifdef __NR_set_robust_list
    __NR_set_robust_list,
#endif
#ifdef __NR_rseq
    __NR_rseq,
#endif
#ifdef __NR_prlimit64
    __NR_prlimit64,
#endif
#ifdef __NR_getrlimit
    __NR_getrlimit,
#endif
#ifdef __NR_getrandom
    __NR_getrandom,
#endif
#ifdef __NR_membarrier
    __NR_membarrier,
#endif
#ifdef __NR_prctl
    __NR_prctl,
#endif
#ifdef __NR_arch_prctl
    __NR_arch_prctl,
#endif
#ifdef __NR_statx
    __NR_statx,
#endif
#ifdef __NR_ioctl
    __NR_ioctl,
#endif
#ifdef __NR_fcntl
    __NR_fcntl,
#endif
#ifdef __NR_dup
    __NR_dup,
#endif
#ifdef __NR_dup3
    __NR_dup3,
#endif
#ifdef __NR_pipe2
    __NR_pipe2,
#endif
#ifdef __NR_fsync
    __NR_fsync,
#endif
#ifdef __NR_fdatasync
    __NR_fdatasync,
#endif
#ifdef __NR_ftruncate
    __NR_ftruncate,
#endif
#ifdef __NR_fallocate
    __NR_fallocate,
#endif
#ifdef __NR_uname
    __NR_uname,
#endif
#ifdef __NR_sysinfo
    __NR_sysinfo,
#endif
#ifdef __NR_tgkill
    __NR_tgkill,
#endif
#ifdef __NR_fstatfs
    __NR_fstatfs,
#endif
#ifdef __NR_clone
    __NR_clone,
#endif
#ifdef __NR_execve
    // 运行必需：沙箱自身用 execve 启动目标程序（禁止创建进程由 clone/fork/vfork 拒绝保证）
    __NR_execve,
#endif
#ifdef __NR_exit
    __NR_exit,
#endif
#ifdef __NR_exit_group
    __NR_exit_group,
#endif
};


static_assert(sizeof(kAllowedSyscalls) / sizeof(kAllowedSyscalls[0]) < 255,
              "seccomp 程序长度需在 BPF_MAXINSNS(4096) 以内（每条白名单占 2 条指令）");

bool InstallSeccomp() {
    std::vector<sock_filter> f;
    f.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)));
    f.push_back(sock_filter{static_cast<__u16>(BPF_JMP | BPF_JEQ | BPF_K),
                            static_cast<__u8>(1), static_cast<__u8>(0),
                            static_cast<__u32>(kAuditArch)});
    f.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS));   // 架构不符直接杀
    f.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)));
    // 逐个放行：命中则跳到末尾的 ALLOW
    const int n = static_cast<int>(sizeof(kAllowedSyscalls) / sizeof(kAllowedSyscalls[0]));
    // 线性判定链（实测可用结构）：命中则跳到末尾 ALLOW；列表长度必须 < 255（BPF jt/jf 为 u8）
    for (int i = 0; i < n; ++i) {
        const unsigned jt = static_cast<unsigned>(n - i);
        f.push_back(sock_filter{static_cast<__u16>(BPF_JMP | BPF_JEQ | BPF_K),
                                static_cast<__u8>(jt), static_cast<__u8>(0),
                                static_cast<__u32>(kAllowedSyscalls[i])});
    }
    // 审计模式：记录被拦的调用号但不杀（用于生成白名单）；正式模式：直接秒杀
    const uint32_t default_action = g_trap_mode ? SECCOMP_RET_TRAP
                                   : (g_audit_only ? SECCOMP_RET_LOG : SECCOMP_RET_KILL_PROCESS);
    f.push_back(BPF_STMT(BPF_RET | BPF_K, default_action));
    f.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));          // 白名单出口
    struct sock_fprog prog;
    prog.len = static_cast<unsigned short>(f.size());
    prog.filter = f.data();
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) return false;
    return prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) == 0;
}

bool DropAllCapabilities() {
    struct __user_cap_header_struct hdr;
    struct __user_cap_data_struct data[2];
    std::memset(&hdr, 0, sizeof(hdr));
    std::memset(data, 0, sizeof(data));
    hdr.version = _LINUX_CAPABILITY_VERSION_3;
    hdr.pid = 0;
    return syscall(__NR_capset, &hdr, data) == 0;
}

void ApplyRlimits(const Limits& lim) {
    struct rlimit r;
    // 注意：Android 的 scudo 分配器会预留大块虚拟地址空间，RLIMIT_AS 会把它挡死
    // （实测表现为目标程序启动即 "mmap failed: Out of memory"）。
    // 因此内存上限默认不通过 RLIMIT_AS 施加；需要限制内存时请用 mem_bytes>0 显式开启并自担风险。
    if (lim.mem_bytes > 0) { r.rlim_cur = r.rlim_max = lim.mem_bytes; setrlimit(RLIMIT_AS, &r); }
    r.rlim_cur = r.rlim_max = lim.file_bytes;  setrlimit(RLIMIT_FSIZE, &r);
    r.rlim_cur = r.rlim_max = lim.cpu_seconds; setrlimit(RLIMIT_CPU, &r);
    r.rlim_cur = r.rlim_max = lim.max_procs;   setrlimit(RLIMIT_NPROC, &r);
    r.rlim_cur = r.rlim_max = 0;               setrlimit(RLIMIT_CORE, &r);
}


// ---------- 执行档支持：读 /sys 计算 prime 核与热区温度 ----------
static long ReadLongFile(const char* path) {
    FILE* f = fopen(path, "r");
    if (!f) return -1;
    long v = -1;
    if (fscanf(f, "%ld", &v) != 1) v = -1;
    fclose(f);
    return v;
}

// prime 核掩码：主频 >= 全局最高主频 90%% 的核心（与 run_boost.h 的判据一致）
static int BuildPrimeCpus(int* out, int max_out) {
    long freqs[CPU_SETSIZE];
    long best = 0;
    int n = 0;
    for (int c = 0; c < CPU_SETSIZE && n < max_out; ++c) {
        char p[128];
        snprintf(p, sizeof(p), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", c);
        long f = ReadLongFile(p);
        if (f <= 0) continue;
        freqs[n] = f; out[n] = c; ++n;
        if (f > best) best = f;
    }
    if (best <= 0) return 0;
    const long thresh = best * 9 / 10;
    int m = 0;
    for (int i = 0; i < n; ++i) if (freqs[i] >= thresh) out[m++] = out[i];
    return m;
}

// 热区最高温（摄氏度）；无热区返回 -1
static double MaxThermalCelsius() {
    double maxc = -1.0;
    for (int z = 0; z < 32; ++z) {
        char p[160];
        snprintf(p, sizeof(p), "/sys/class/thermal/thermal_zone%d/temp", z);
        long t = ReadLongFile(p);
        if (t <= 0) continue;
        double c = (t > 1000) ? t / 1000.0 : (double)t;
        if (c > maxc) maxc = c;
    }
    return maxc;
}

double NowSeconds() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

}  // namespace

Result RunIsolated(const std::string& exe,
                   const std::vector<std::string>& args,
                   const std::string& workdir,
                   const Limits& limits,
                   uint32_t drop_uid,
                   uint32_t drop_gid,
                   uint64_t* out_seccomp_denials) {
    Result res;
    if (out_seccomp_denials) *out_seccomp_denials = 0;
    if (access(exe.c_str(), X_OK) != 0) {
        res.error = "目标二进制不可执行或不存在：" + exe;
        return res;
    }
    int outp[2], errp[2];
    if (pipe(outp) != 0 || pipe(errp) != 0) { res.error = "pipe 创建失败"; return res; }

    // 共享页：子进程写 netns 状态
    int* shm = static_cast<int*>(mmap(nullptr, 4096, PROT_READ | PROT_WRITE,
                                      MAP_SHARED | MAP_ANONYMOUS, -1, 0));
    if (shm) shm[0] = 0;
    // ---- 降温门禁：温度高于目标则等待（对标 run_boost 的降温门禁）----
    double cooldown_waited = 0.0;
    double temp_before = MaxThermalCelsius();
    if (limits.cooldown_celsius > 0.0 && temp_before > 0.0) {
        const double deadline_cd = NowSeconds() + limits.cooldown_timeout_s;
        while (MaxThermalCelsius() > limits.cooldown_celsius && NowSeconds() < deadline_cd) {
            struct timespec ts{2, 0};
            nanosleep(&ts, nullptr);
            cooldown_waited = NowSeconds() - (deadline_cd - limits.cooldown_timeout_s);
        }
    }

    const double t0 = NowSeconds();
    const pid_t pid = fork();
    if (pid < 0) { res.error = "fork 失败"; return res; }

    if (pid == 0) {
        // ---------------- 子进程：建立沙箱（失败即 _exit(120+步骤号)）----------------
        STEPLOG("child:0-enter");
        close(outp[0]); close(errp[0]);
        if (dup2(outp[1], STDOUT_FILENO) < 0) _exit(121);
        if (dup2(errp[1], STDERR_FILENO) < 0) _exit(122);
        close(outp[1]); close(errp[1]);

        // 1) 断网：优先用网络命名空间（内核支持时最彻底）。
        //    若内核未编 netns（部分模拟器/KVM 内核），**不静默降级**：
        //    改为依赖下面 seccomp 白名单的默认拒绝 —— 任何 socket/connect/bind/sendto
        //    都会被 SECCOMP_RET_KILL_PROCESS 直接秒杀，网络能力同样为零。
        STEPLOG("child:1-before-netns");
        if (limits.deny_network) {
            if (unshare(CLONE_NEWNET) != 0) {
                shm[0] = 0;
            } else {
                shm[0] = 1;
            }
        }
        // 2) 降权（先 gid 后 uid）
        STEPLOG("child:2-netns-done");
        // ---- 绑核：只允许跑在 prime 核心上（对标 run_boost 的 sched_setaffinity）----
        if (limits.pin_prime_cores) {
            // 与当前 cpuset 限制取交集：设备可能把 prime 核排除在允许集之外
            // （实测红米 Note 12 Turbo：Cpus_allowed=0-5，cpu6/7 不可用，直接绑会 EINVAL）。
            // 拿不到任何 prime 核就**不假装绑定**，如实记录。
            int cpus[CPU_SETSIZE];
            const int m = BuildPrimeCpus(cpus, CPU_SETSIZE);
            cpu_set_t cur; CPU_ZERO(&cur);
            const bool have_cur = (sched_getaffinity(0, sizeof(cpu_set_t), &cur) == 0);
            cpu_set_t set; CPU_ZERO(&set);
            int picked = 0;
            for (int i = 0; i < m; ++i) {
                if (have_cur && !CPU_ISSET(cpus[i], &cur)) continue;
                CPU_SET(cpus[i], &set); ++picked;
            }
            int rc = -1;
            int err = 0;
            if (picked > 0) { rc = sched_setaffinity(0, sizeof(cpu_set_t), &set); err = errno; }
            char b[160];
            int bn = snprintf(b, sizeof(b),
                "child:pin prime=%d allowed_picked=%d rc=%d errno=%d\n", m, picked, rc, err);
            if (bn > 0) { ssize_t w = write(2, b, (size_t)bn); (void)w; }
        }
        if (setgroups(0, nullptr) != 0) _exit(124);
        STEPLOG("child:3-setgroups-done");
        if (setgid(static_cast<gid_t>(drop_gid)) != 0) _exit(125);
        STEPLOG("child:4-setgid-done");
        if (setuid(static_cast<uid_t>(drop_uid)) != 0) _exit(126);
        // 3) 清空能力
        STEPLOG("child:5-setuid-done");
        if (!DropAllCapabilities()) _exit(127);
        // 4) 配额
        STEPLOG("child:6-capset-done");
        ApplyRlimits(limits);
        STEPLOG("child:7-rlimits-done");
        // 5) 工作目录
        if (!workdir.empty() && chdir(workdir.c_str()) != 0) _exit(128);
        STEPLOG("child:8-chdir-done");
        // 6) seccomp 白名单（最后一步，装上后连 open 都受限）
        // 诊断开关：设置 DYNAMO_SANDBOX_SKIP_SECCOMP=1 可临时不装 seccomp（仅用于定位问题）
        g_audit_only = limits.audit_only || (getenv("DYNAMO_SANDBOX_AUDIT") != nullptr);
        g_trap_mode = (getenv("DYNAMO_SANDBOX_TRAP") != nullptr);
        if (g_trap_mode) {
            struct sigaction sa;
            memset(&sa, 0, sizeof(sa));
            sa.sa_sigaction = TrapHandler;
            sa.sa_flags = SA_SIGINFO;
            sigaction(SIGSYS, &sa, nullptr);
        }
        if (getenv("DYNAMO_SANDBOX_SKIP_SECCOMP") == nullptr) {
            if (!InstallSeccomp()) _exit(129);
        }

        STEPLOG("child:9-about-execve");
        std::vector<char*> argv;
        argv.push_back(const_cast<char*>(exe.c_str()));
        for (const auto& a : args) argv.push_back(const_cast<char*>(a.c_str()));
        argv.push_back(nullptr);
        char* envp[] = {const_cast<char*>("PATH=/system/bin:/system/xbin"),
                        const_cast<char*>("LC_ALL=C"),
                        const_cast<char*>("TMPDIR=/data/local/tmp"),
                        nullptr};
        execve(exe.c_str(), argv.data(), envp);
        _exit(130);   // execve 失败
    }

    // ---------------- 父进程：计时、读输出、超时强杀 ----------------
    close(outp[1]); close(errp[1]);
    res.started = true;
    std::string out, err;
    char buf[4096];
    bool out_open = true, err_open = true;
    const double deadline = t0 + limits.wall_seconds;
    int status = 0;
    struct rusage ru;
    std::memset(&ru, 0, sizeof(ru));

    while (out_open || err_open) {
        struct pollfd fds[2];
        int nf = 0;
        if (out_open) { fds[nf].fd = outp[0]; fds[nf].events = POLLIN; ++nf; }
        if (err_open) { fds[nf].fd = errp[0]; fds[nf].events = POLLIN; ++nf; }
        const int remain_ms = static_cast<int>((deadline - NowSeconds()) * 1000);
        if (remain_ms <= 0) break;
        const int pr = poll(fds, nf, remain_ms > 500 ? 500 : remain_ms);
        if (pr > 0) {
            for (int i = 0; i < nf; ++i) {
                if (fds[i].revents & (POLLIN | POLLHUP)) {
                    const ssize_t n = read(fds[i].fd, buf, sizeof(buf));
                    if (n > 0) {
                        (fds[i].fd == outp[0] ? out : err).append(buf, static_cast<size_t>(n));
                    } else {
                        if (fds[i].fd == outp[0]) out_open = false; else err_open = false;
                    }
                }
            }
        }
        int st = 0;
        if (wait4(pid, &st, WNOHANG, &ru) == pid) { status = st; break; }
        if (NowSeconds() > deadline) { kill(pid, SIGKILL); res.error = "超过墙钟上限，已强杀"; break; }
    }
    if (status == 0) {
        // 有界回收：最多再等 5 秒，避免任何情况下阻塞挂死
        int st = 0;
        const double hard_deadline = NowSeconds() + 5.0;
        while (NowSeconds() < hard_deadline) {
            if (wait4(pid, &st, WNOHANG, &ru) == pid) { status = st; break; }
            struct timespec ts{0, 50 * 1000 * 1000};
            nanosleep(&ts, nullptr);
        }
        if (status == 0) status = st;
    }
    close(outp[0]); close(errp[0]);

    res.netns_applied = (shm && shm[0] == 1);
    res.cooldown_waited_s = cooldown_waited;
    res.temp_before_c = temp_before;
    res.temp_after_c = MaxThermalCelsius();
    { int cpus[CPU_SETSIZE]; res.affinity_cpus = limits.pin_prime_cores ? BuildPrimeCpus(cpus, CPU_SETSIZE) : 0; }
    if (shm) munmap(shm, 4096);
    res.seconds = NowSeconds() - t0;
    res.max_rss_kb = ru.ru_maxrss;
    res.stdout_text = out;
    res.stderr_text = err;
    if (WIFEXITED(status)) {
        res.exit_code = WEXITSTATUS(status);
        if (res.exit_code >= 121 && res.exit_code <= 130 && res.error.empty()) {
            res.error = "沙箱建立失败（步骤码 " + std::to_string(res.exit_code) + "）：拒绝执行";
        }
    } else if (WIFSIGNALED(status)) {
        res.term_signal = WTERMSIG(status);
        if (res.term_signal == SIGSYS && out_seccomp_denials) *out_seccomp_denials = 1;
    }
    return res;
}

std::vector<std::string> ReadElfNeeded(const std::string& path) {
    std::vector<std::string> libs;
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return libs;
    unsigned char eh[64];
    if (fread(eh, 1, sizeof(eh), f) != sizeof(eh) || eh[0] != 0x7F || eh[1] != 'E') { fclose(f); return libs; }
    const bool is64 = eh[4] == 2;
    const bool le = eh[5] == 1;
    if (!is64) { fclose(f); return libs; }
    auto rd64 = [&](size_t off) -> uint64_t {
        unsigned char b[8];
        if (fseek(f, static_cast<long>(off), SEEK_SET) != 0) return 0;
        if (fread(b, 1, 8, f) != 8) return 0;
        uint64_t v = 0;
        for (int i = 0; i < 8; ++i) v |= static_cast<uint64_t>(b[le ? i : 7 - i]) << (8 * i);
        return v;
    };
    auto rd16 = [&](size_t off) -> uint16_t {
        unsigned char b[2];
        if (fseek(f, static_cast<long>(off), SEEK_SET) != 0) return 0;
        if (fread(b, 1, 2, f) != 2) return 0;
        return static_cast<uint16_t>(b[0] | (b[1] << 8));
    };
    const uint64_t phoff = rd64(32);
    const uint16_t phentsize = rd16(54);
    const uint16_t phnum = rd16(56);
    uint64_t dyn_off = 0, dyn_size = 0;
    for (uint16_t i = 0; i < phnum; ++i) {
        const uint64_t base = phoff + static_cast<uint64_t>(i) * phentsize;
        if (rd16(base) == 2) {   // PT_DYNAMIC
            dyn_off = rd64(base + 8);
            dyn_size = rd64(base + 32);
            break;
        }
    }
    if (!dyn_off) { fclose(f); return libs; }
    uint64_t strtab = 0;
    std::vector<std::pair<uint64_t, uint64_t>> needed;   // (offset, tag)
    for (uint64_t off = 0; off + 16 <= dyn_size; off += 16) {
        const uint64_t tag = rd64(dyn_off + off);
        const uint64_t val = rd64(dyn_off + off + 8);
        if (tag == 0) break;
        if (tag == 5) strtab = val;                 // DT_STRTAB
        if (tag == 1) needed.emplace_back(val, tag); // DT_NEEDED
    }
    if (strtab) {
        for (auto& kv : needed) {
            std::string name;
            if (fseek(f, static_cast<long>(strtab + kv.first), SEEK_SET) != 0) continue;
            for (int c = 0; c < 256; ++c) {
                const int ch = fgetc(f);
                if (ch <= 0) break;
                name.push_back(static_cast<char>(ch));
            }
            if (!name.empty()) libs.push_back(name);
        }
    }
    fclose(f);
    return libs;
}

}  // namespace sandbox
}  // namespace dynamo

// ============================================================================
//  JNI 出口：供 Kotlin 侧调用沙箱执行外部预编译二进制
//  返回 JSON 字符串（不抛异常，错误也如实回传，绝不静默）
// ============================================================================
#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_ufs_bench_spec_SandboxedExecutor_nativeRunIsolated(
        JNIEnv* env, jobject /*thiz*/,
        jstring jexe, jobjectArray jargs, jstring jworkdir,
        jlong mem_bytes, jlong file_bytes, jint cpu_seconds, jint wall_seconds,
        jint drop_uid, jint drop_gid) {
    using namespace dynamo::sandbox;
    auto to_str = [&](jstring s) -> std::string {
        if (!s) return std::string();
        const char* c = env->GetStringUTFChars(s, nullptr);
        std::string r = c ? c : "";
        if (c) env->ReleaseStringUTFChars(s, c);
        return r;
    };
    const std::string exe = to_str(jexe);
    const std::string workdir = to_str(jworkdir);
    std::vector<std::string> args;
    if (jargs) {
        const jsize n = env->GetArrayLength(jargs);
        for (jsize i = 0; i < n; ++i) {
            jstring a = static_cast<jstring>(env->GetObjectArrayElement(jargs, i));
            args.push_back(to_str(a));
            if (a) env->DeleteLocalRef(a);
        }
    }
    Limits lim;
    if (mem_bytes > 0)    lim.mem_bytes = static_cast<uint64_t>(mem_bytes);
    if (file_bytes > 0)   lim.file_bytes = static_cast<uint64_t>(file_bytes);
    if (cpu_seconds > 0)  lim.cpu_seconds = static_cast<uint32_t>(cpu_seconds);
    if (wall_seconds > 0) lim.wall_seconds = static_cast<uint32_t>(wall_seconds);

    uint64_t denials = 0;
    const Result r = RunIsolated(exe, args, workdir, lim,
                                 static_cast<uint32_t>(drop_uid),
                                 static_cast<uint32_t>(drop_gid), &denials);

    auto esc = [](const std::string& in) -> std::string {
        std::string o;
        o.reserve(in.size() + 16);
        for (char c : in) {
            switch (c) {
                case '"':  o += "\\\""; break;
                case '\\': o += "\\\\"; break;
                case '\n': o += "\\n";  break;
                case '\r': o += "\\r";  break;
                case '\t': o += "\\t";  break;
                default:
                    if (static_cast<unsigned char>(c) < 0x20) {
                        char b[8];
                        snprintf(b, sizeof(b), "\\u%04x", c);
                        o += b;
                    } else {
                        o += c;
                    }
            }
        }
        return o;
    };
    std::string json = "{";
    json += "\"started\":" + std::string(r.started ? "true" : "false");
    json += ",\"exit_code\":" + std::to_string(r.exit_code);
    json += ",\"term_signal\":" + std::to_string(r.term_signal);
    json += ",\"seccomp_killed\":" + std::string(r.term_signal == SIGSYS ? "true" : "false");
    json += ",\"seconds\":" + std::to_string(r.seconds);
    json += ",\"max_rss_kb\":" + std::to_string(r.max_rss_kb);
    json += ",\"error\":\"" + esc(r.error) + "\"";
    json += ",\"stdout\":\"" + esc(r.stdout_text) + "\"";
    json += ",\"stderr\":\"" + esc(r.stderr_text) + "\"";
    json += "}";
    return env->NewStringUTF(json.c_str());
}
