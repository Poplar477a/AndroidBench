#include <iostream>
#include <iomanip>
#include <string>
#include <cstring>
#include <chrono>
#include <thread>
#include <unistd.h>
#include "dynamo_engine.h"

void PrintUsage(const char* prog) {
    std::cout << "用法: " << prog << " [选项]\n"
              << "选项:\n"
              << "  --file <路径>       测试目标文件路径 (默认: ./dynamo_test.dat)\n"
              << "  --bs <大小>         块大小 (例如 4k, 8k, 128k, 1m, 2m, 默认: 4k)\n"
              << "  --read <百分比>     读取百分比 0~100 (100: 纯读, 0: 纯写, 70: 混合读, 默认: 100)\n"
              << "  --rnd <百分比>      随机百分比 0~100 (100: 随机, 0: 顺序, 默认: 100)\n"
              << "  --qd <队列深度>     队列深度 Queue Depth (默认: 1)\n"
              << "  --threads <线程数>  并发工作线程数 (默认: 1)\n"
              << "  --size <MB>         测试文件大小 (MB, 默认: 256)\n"
              << "  --time <秒>         持续测试时长 (秒, 默认: 5)\n"
              << "  --help              显示本帮助信息\n";
}

uint32_t ParseSize(const std::string& str) {
    if (str.empty()) return 4096;
    char unit = str.back();
    std::string num = str;
    uint32_t multiplier = 1;
    if (unit == 'k' || unit == 'K') {
        multiplier = 1024;
        num = str.substr(0, str.size() - 1);
    } else if (unit == 'm' || unit == 'M') {
        multiplier = 1024 * 1024;
        num = str.substr(0, str.size() - 1);
    }
    return std::stoul(num) * multiplier;
}

int main(int argc, char* argv[]) {
    std::string file_path = "./dynamo_test.dat";
    dynamo::AccessSpecification spec{};
    spec.request_size_bytes = 4096;
    spec.percent_read = 100;
    spec.percent_random = 100;
    spec.queue_depth = 1;
    spec.worker_threads = 1;
    spec.file_size_bytes = 256ULL * 1024ULL * 1024ULL; // 256MB
    spec.duration_seconds = 5;

    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--file" && i + 1 < argc) {
            file_path = argv[++i];
        } else if (arg == "--bs" && i + 1 < argc) {
            spec.request_size_bytes = ParseSize(argv[++i]);
        } else if (arg == "--read" && i + 1 < argc) {
            spec.percent_read = std::stoul(argv[++i]);
        } else if (arg == "--rnd" && i + 1 < argc) {
            spec.percent_random = std::stoul(argv[++i]);
        } else if (arg == "--qd" && i + 1 < argc) {
            spec.queue_depth = std::stoul(argv[++i]);
        } else if (arg == "--threads" && i + 1 < argc) {
            spec.worker_threads = std::stoul(argv[++i]);
        } else if (arg == "--size" && i + 1 < argc) {
            spec.file_size_bytes = std::stoull(argv[++i]) * 1024ULL * 1024ULL;
        } else if (arg == "--time" && i + 1 < argc) {
            spec.duration_seconds = std::stoul(argv[++i]);
        } else if (arg == "--help" || arg == "-h") {
            PrintUsage(argv[0]);
            return 0;
        }
    }

    std::cout << "====================================================\n"
              << "       Iometer Dynamo 存储基准测试内核 (Native)     \n"
              << "====================================================\n"
              << "目标文件: " << file_path << "\n"
              << "文件大小: " << (spec.file_size_bytes / (1024 * 1024)) << " MB\n"
              << "块大小:   " << spec.request_size_bytes << " 字节\n"
              << "分布模式: 读 " << spec.percent_read << "% / 随机 " << spec.percent_random << "%\n"
              << "并发配置: QueueDepth=" << spec.queue_depth << ", Threads=" << spec.worker_threads << "\n"
              << "测试时间: " << spec.duration_seconds << " 秒\n"
              << "----------------------------------------------------\n";

    dynamo::DynamoEngine engine;
    std::string err;

    std::cout << "[*] 正在预分配测试数据空间 (O_DIRECT)... " << std::flush;
    if (!engine.PrepareTargetFile(file_path, spec.file_size_bytes, err)) {
        std::cerr << "\n[错误] 预分配失败: " << err << "\n";
        return 1;
    }
    std::cout << "完成\n";

    std::cout << "[*] 开始基准压测...\n\n";
    std::cout << std::setw(6) << "时间" << " | "
              << std::setw(12) << "读带宽 (MB/s)" << " | "
              << std::setw(10) << "读 IOPS" << " | "
              << std::setw(12) << "写带宽 (MB/s)" << " | "
              << std::setw(10) << "写 IOPS" << " | "
              << std::setw(12) << "平均延迟 (μs)" << "\n";
    std::cout << "-----------------------------------------------------------------------\n";

    auto progress_cb = [](const dynamo::ProgressMetric& pm) {
        std::cout << std::setw(4) << pm.elapsed_seconds << "s | "
                  << std::setw(12) << std::fixed << std::setprecision(2) << pm.current_read_mbps << " | "
                  << std::setw(10) << pm.current_read_iops << " | "
                  << std::setw(12) << std::fixed << std::setprecision(2) << pm.current_write_mbps << " | "
                  << std::setw(10) << pm.current_write_iops << " | "
                  << std::setw(12) << std::fixed << std::setprecision(1) << pm.avg_latency_us << "\n";
    };

    dynamo::BenchmarkResult res = engine.RunBenchmark(file_path, spec, progress_cb);

    // 清理测试文件
    unlink(file_path.c_str());

    std::cout << "-----------------------------------------------------------------------\n";
    if (!res.success) {
        std::cerr << "[错误] 测试失败: " << res.error_message << "\n";
        return 1;
    }

    std::cout << ">>> 基准测试汇总报告 <<<\n"
              << "读取吞吐量: " << std::fixed << std::setprecision(2) << res.total_read_mbps << " MB/s (" << res.total_read_iops << " IOPS)\n"
              << "写入吞吐量: " << std::fixed << std::setprecision(2) << res.total_write_mbps << " MB/s (" << res.total_write_iops << " IOPS)\n"
              << "延迟统计:\n"
              << "  最小延迟: " << std::fixed << std::setprecision(1) << res.min_latency_us << " μs\n"
              << "  平均延迟: " << std::fixed << std::setprecision(1) << res.avg_latency_us << " μs\n"
              << "  P95 延迟: " << std::fixed << std::setprecision(1) << res.p95_latency_us << " μs\n"
              << "  P99 延迟: " << std::fixed << std::setprecision(1) << res.p99_latency_us << " μs\n"
              << "  最大延迟: " << std::fixed << std::setprecision(1) << res.max_latency_us << " μs\n"
              << "====================================================\n";

    return 0;
}
