#include "memory_guard.h"
#include <cstdlib>
#include <cstring>
#include <new>

namespace androidbench {

std::atomic<bool> NativeCancellationHook::cancelled_{false};

void NativeCancellationHook::set_cancelled(bool cancelled) {
    cancelled_.store(cancelled, std::memory_order_release);
}

bool NativeCancellationHook::is_cancelled() {
    return cancelled_.load(std::memory_order_acquire);
}

void NativeCancellationHook::reset() {
    cancelled_.store(false, std::memory_order_release);
}

SafeNativeAllocator::SafeNativeAllocator(size_t max_bytes)
    : max_allowed_bytes_(max_bytes) {}

SafeNativeAllocator::~SafeNativeAllocator() {
    // 析构时若还有未释放内存，由具体调用侧或跟踪表释放
}

void* SafeNativeAllocator::allocate_aligned(size_t alignment, size_t size, NativeStatus& out_status) {
    out_status = NativeStatus::SUCCESS;

    if (NativeCancellationHook::is_cancelled()) {
        out_status = NativeStatus::CANCELLED;
        return nullptr;
    }

    if (size == 0) {
        out_status = NativeStatus::INVALID_ARGUMENT;
        return nullptr;
    }

    // 预算检查：当前已分配 + 本次申请 是否超出硬上限
    size_t prev = current_allocated_.load(std::memory_order_relaxed);
    if (prev + size > max_allowed_bytes_) {
        out_status = NativeStatus::BUDGET_EXCEEDED;
        return nullptr;
    }

    // 原生对齐分配
    void* ptr = nullptr;
    int res = posix_memalign(&ptr, alignment, size);
    if (res != 0 || ptr == nullptr) {
        out_status = NativeStatus::OUT_OF_MEMORY;
        return nullptr;
    }

    current_allocated_.fetch_add(size, std::memory_order_relaxed);
    return ptr;
}

void SafeNativeAllocator::free_aligned(void* ptr) {
    if (ptr != nullptr) {
        free(ptr);
    }
}

} // namespace androidbench
