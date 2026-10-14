#pragma once

#include <cstddef>
#include <cstdint>
#include <atomic>
#include <string>

namespace androidbench {

enum class NativeStatus : int32_t {
    SUCCESS = 0,
    OUT_OF_MEMORY = 1,
    BUDGET_EXCEEDED = 2,
    CANCELLED = 3,
    VERIFICATION_FAILED = 4,
    INVALID_ARGUMENT = 5
};

struct AllocationBudget {
    size_t max_allowed_bytes;
    size_t current_allocated_bytes;
};

/**
 * 对应第 14.7 节：原生受控安全内存分配器
 * 在调用 malloc/mmap 之前完成预算检查，对分配返回值严格检验，并提供安全的释放防护
 */
class SafeNativeAllocator {
public:
    explicit SafeNativeAllocator(size_t max_bytes);
    ~SafeNativeAllocator();

    // 禁用拷贝
    SafeNativeAllocator(const SafeNativeAllocator&) = delete;
    SafeNativeAllocator& operator=(const SafeNativeAllocator&) = delete;

    void* allocate_aligned(size_t alignment, size_t size, NativeStatus& out_status);
    void free_aligned(void* ptr);

    size_t get_current_allocated() const { return current_allocated_.load(); }
    size_t get_max_allowed() const { return max_allowed_bytes_; }

private:
    size_t max_allowed_bytes_;
    std::atomic<size_t> current_allocated_{0};
};

/**
 * 原生取消标志桥接器
 */
class NativeCancellationHook {
public:
    static void set_cancelled(bool cancelled);
    static bool is_cancelled();
    static void reset();

private:
    static std::atomic<bool> cancelled_;
};

} // namespace androidbench
