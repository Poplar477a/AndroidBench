// BYOB 沙箱运行器：以沙箱方式执行外部预编译二进制并原样回传结果
// 用法：byob_run <exe> [args...]   环境变量：BYOB_WORKDIR / BYOB_WALL / BYOB_UID / BYOB_GID
#include "sandboxed_runner.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
using namespace dynamo::sandbox;

int main(int argc, char** argv) {
    if (argc < 2) { fprintf(stderr, "用法: byob_run <exe> [args...]\n"); return 2; }
    const char* wd = getenv("BYOB_WORKDIR");
    Limits lim;
    lim.wall_seconds = getenv("BYOB_WALL") ? (uint32_t)atoi(getenv("BYOB_WALL")) : 300;
    lim.cpu_seconds = lim.wall_seconds + 10;
    lim.mem_bytes = 0;
    lim.pin_prime_cores = (getenv("BYOB_PIN") != nullptr);
    lim.cooldown_celsius = getenv("BYOB_COOLDOWN") ? atof(getenv("BYOB_COOLDOWN")) : 0.0;
    lim.cooldown_timeout_s = getenv("BYOB_COOLDOWN_WAIT") ? (uint32_t)atoi(getenv("BYOB_COOLDOWN_WAIT")) : 60;                       // 0 = 不设 RLIMIT_AS（scudo 冲突，见代码注释）
    const uint32_t uid = getenv("BYOB_UID") ? (uint32_t)atoi(getenv("BYOB_UID")) : 10123;
    const uint32_t gid = getenv("BYOB_GID") ? (uint32_t)atoi(getenv("BYOB_GID")) : 10123;

    std::vector<std::string> args;
    for (int i = 2; i < argc; ++i) args.push_back(argv[i]);
    uint64_t denials = 0;
    Result r = RunIsolated(argv[1], args, wd ? wd : "", lim, uid, gid, &denials);
    printf("RESULT started=%d netns=%d pin_cpus=%d temp_before=%.1f cooldown_wait=%.1f exit=%d signal=%d seccomp_killed=%d seconds=%.3f rss_kb=%ld\n",
           (int)r.started, (int)r.netns_applied, r.affinity_cpus, r.temp_before_c, r.cooldown_waited_s, r.exit_code, r.term_signal,
           (int)(r.term_signal == 31), r.seconds, r.max_rss_kb);
    if (!r.error.empty()) printf("ERROR %s\n", r.error.c_str());
    if (!r.stdout_text.empty()) { printf("--- stdout ---\n"); fputs(r.stdout_text.c_str(), stdout); printf("\n"); }
    if (!r.stderr_text.empty()) { printf("--- stderr ---\n"); fputs(r.stderr_text.c_str(), stdout); printf("\n"); }
    return r.exit_code;
}
