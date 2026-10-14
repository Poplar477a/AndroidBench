// 内置微基准独立驱动（与 App 共用同一份 builtin_*.cpp；指标口径一致）
// 用法：builtin_bench --kernel ntt|fluid|graph|sim [--scale 1..3] [--threads N]
#include "builtin_kernels.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>

int main(int argc, char** argv) {
    const char* kernel = "ntt";
    unsigned scale = 1, threads = 1;
    for (int i = 1; i < argc; ++i) {
        if (!strcmp(argv[i], "--kernel") && i + 1 < argc) kernel = argv[++i];
        else if (!strcmp(argv[i], "--scale") && i + 1 < argc) scale = (unsigned)atoi(argv[++i]);
        else if (!strcmp(argv[i], "--threads") && i + 1 < argc) threads = (unsigned)atoi(argv[++i]);
    }
    dynamo::builtin::RunConfig cfg;
    cfg.threads = threads; cfg.scale = scale; cfg.avail_mem_bytes = 0; cfg.stop = nullptr;
    dynamo::builtin::LiveCallback live = [](const dynamo::builtin::LiveStats&) {};
    dynamo::builtin::RunResult r;
    if (!strcmp(kernel, "ntt"))        r = dynamo::builtin::RunNttKernel(cfg, live);
    else if (!strcmp(kernel, "fluid")) r = dynamo::builtin::RunFluidKernel(cfg, live);
    else if (!strcmp(kernel, "graph")) r = dynamo::builtin::RunGraphKernel(cfg, live);
    else                               r = dynamo::builtin::RunCompilerSimKernel(cfg, live);

    printf("RESULT kernel=%s scale=%u threads=%u success=%d verified=%d seconds=%.3f "
           "ops_per_sec=%.2f mem_gbps=%.3f ratio=%.4f\n",
           kernel, scale, threads, (int)r.success, (int)r.verified,
           r.seconds, r.ops_per_sec, r.mem_gbps, r.ratio);
    if (!r.verify_detail.empty()) printf("VERIFY %s\n", r.verify_detail.c_str());
    if (!r.error.empty())         printf("ERROR %s\n", r.error.c_str());
    return r.success ? 0 : 1;
}
