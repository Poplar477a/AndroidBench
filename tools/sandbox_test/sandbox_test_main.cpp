// 沙箱负向验证驱动：在设备上以 root 运行，把三个 POC 交给 RunIsolated
#include "sandboxed_runner.h"
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
using namespace dynamo::sandbox;
static void run_one(const char* name, const char* exe, uint32_t wall, uint64_t mem) {
    Limits lim;
    lim.wall_seconds = wall;
    lim.mem_bytes = mem;
    lim.cpu_seconds = wall + 5;
    uint64_t denials = 0;
    Result r = RunIsolated(exe, std::vector<std::string>{}, "/data/local/tmp/sbtest", lim, 10123, 10123, &denials);
    printf("===== %s =====\n", name);
    printf("started=%d netns=%d exit=%d signal=%d seccomp_killed=%d seconds=%.2f rss_kb=%ld\n",
           (int)r.started, (int)r.netns_applied, r.exit_code, r.term_signal,
           (int)(r.term_signal == 31), r.seconds, r.max_rss_kb);
    if (!r.error.empty()) printf("error=%s\n", r.error.c_str());
    if (!r.stdout_text.empty()) printf("stdout: %s\n", r.stdout_text.c_str());
    if (!r.stderr_text.empty()) printf("stderr: %s\n", r.stderr_text.c_str());
    printf("\n");
}
int main(int argc, char** argv) {
    const std::string dir = (argc > 1) ? argv[1] : "/data/local/tmp/sbtest";
    run_one("POC-A 网络外联", (dir + "/poc_a_socket").c_str(), 20, 0ull);
    run_one("POC-B 特权删除系统文件", (dir + "/poc_b_unlink").c_str(), 20, 0ull);
    run_one("POC-C 死循环/资源超限", (dir + "/poc_c_loop").c_str(), 8, 0ull);
    return 0;
}
