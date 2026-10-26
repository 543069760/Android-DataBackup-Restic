#ifndef NATIVELIB_CALCULATE_SIZE_H
#define NATIVELIB_CALCULATE_SIZE_H

/**
 * 目录大小计算（符号链接安全版）。
 *
 * 历史 bug：这里原来用 ftw(3)。ftw(3) 没有 flags 参数，回调对每个条目一律 stat()（而非 lstat()），
 * 因此总是跟随符号链接，而且无法传 FTW_PHYS / FTW_MOUNT。在 proot / Debian rootfs 这类
 * 含有数万符号链接（含 `bin -> usr/bin`、指向绝对路径的链接）的目录上，遍历会反复进入同一批目录、
 * 路径无限增长，表现为 calculateSize 永不返回、Binder 线程 100% CPU、备份永久卡死
 * （同一目录 `find`（默认不跟随链接）只需数秒）。
 *
 * 现在改为 nftw(3) + FTW_PHYS（不再跟随符号链接，语义等同 lstat）+ FTW_MOUNT（不跨挂载点），
 * 并叠加深度 / 条目数 / 时长三重上限：一旦触限立即停止遍历并返回已累计的大小，
 * 也就是"降级给出部分值"而不是无限阻塞。
 *
 * 这一层刻意不依赖 JNI / Android 运行时，方便在主机上做单元测试。
 */

#include <errno.h>
#include <ftw.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>

#include <algorithm>
#include <string>

#ifdef NATIVELIB_UNIT_TEST
// 单元测试在主机上编译，不链接 liblog。
#include <cstdio>
#define NATIVELIB_LOGW(...)                                                         \
    do {                                                                            \
        fprintf(stderr, "[NativeLib] ");                                            \
        fprintf(stderr, __VA_ARGS__);                                               \
        fprintf(stderr, "\n");                                                      \
    } while (false)
#else
#include <android/log.h>
#define NATIVELIB_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "NativeLib", __VA_ARGS__)
#endif

// ── 遍历防护上限（单元测试可用宏覆盖成极小值，以确定性触发降级）──
#ifndef NATIVELIB_WALK_MAX_ENTRIES
#define NATIVELIB_WALK_MAX_ENTRIES 2000000ULL // 约 200k 条目的 rootfs 仍有 10 倍余量
#endif
#ifndef NATIVELIB_WALK_MAX_MILLIS
#define NATIVELIB_WALK_MAX_MILLIS 20000ULL // 20s，需小于 App 侧 RemoteRootService 的超时兜底
#endif
#ifndef NATIVELIB_WALK_MAX_DEPTH
#define NATIVELIB_WALK_MAX_DEPTH 256
#endif
#ifndef NATIVELIB_WALK_CHECK_INTERVAL
#define NATIVELIB_WALK_CHECK_INTERVAL 4096ULL // 每多少个条目检查一次上限（降低读时钟的开销）
#endif

namespace NativeNS {

// 单次遍历可同时保持打开的目录描述符数（仅影响性能，不影响结果）。
constexpr int kWalkFdLimit = 1024;
constexpr int kWalkFlags = FTW_PHYS | FTW_MOUNT;

constexpr uint64_t kMaxEntries = NATIVELIB_WALK_MAX_ENTRIES;
constexpr uint64_t kMaxDurationMillis = NATIVELIB_WALK_MAX_MILLIS;
constexpr int kMaxDepth = NATIVELIB_WALK_MAX_DEPTH;
constexpr uint64_t kCheckInterval = NATIVELIB_WALK_CHECK_INTERVAL;

// 回调返回非 0 即终止整个遍历。
constexpr int kWalkAbort = 1;

struct WalkLimits {
    uint64_t max_entries = kMaxEntries;
    uint64_t max_duration_millis = kMaxDurationMillis;
    int max_depth = kMaxDepth;
};

struct WalkResult {
    int64_t size = 0;       // 已累计字节数（降级时为部分值）
    uint64_t entries = 0;   // 已访问条目数
    bool completed = false; // true = 完整遍历；false = 触限降级 / nftw 失败
};

struct WalkState {
    WalkLimits limits{};
    int64_t size = 0;
    uint64_t entries = 0;
    uint64_t next_check_at = 0;
    uint64_t deadline_millis = 0;
    bool completed = true;
};

// nftw(3) 的回调不携带用户数据，故用 thread_local 保存状态；
// 这样多个 Binder 线程并发调用 calculateSize 时互不影响。
// 用函数内 thread_local + 引用的形式而非头文件里的命名空间级变量，避免多 TU 包含时的重复定义。
inline WalkState &walk_state() {
    thread_local WalkState state;
    return state;
}

inline uint64_t monotonic_millis() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000ULL + static_cast<uint64_t>(ts.tv_nsec) / 1000000ULL;
}

/**
 * nftw 回调：返回 0 继续遍历，返回非 0 立即终止整个遍历。
 */
inline int on_walking(const char *path, const struct stat *p_stat, int typeflag, struct FTW *p_ftw) {
    // FTW_NS / FTW_DNR：stat 失败（权限或竞态），此时 p_stat 可能为空，安全跳过并继续。
    if (p_stat == nullptr || typeflag == FTW_NS || typeflag == FTW_DNR) {
        return 0;
    }

    switch (typeflag) {
        case FTW_F:  // 普通文件
        case FTW_D:  // 目录：只计目录项自身大小（与历史 ftw 行为保持一致）
        case FTW_SL: // 符号链接：FTW_PHYS 下由 lstat 得到，只记链接自身大小，绝不递归进目标
        case FTW_SLN: // 悬空符号链接（FTW_PHYS 下不会出现，保留作防御）
            walk_state().size += static_cast<int64_t>(p_stat->st_size);
            break;
        case FTW_DP: // 仅在使用 FTW_DEPTH 时出现，忽略以免与 FTW_D 重复计数
        default:
            break;
    }

    walk_state().entries++;

    // 深度上限：不跟随符号链接后本已无环，这里只是防御极深嵌套 / 病态文件系统的兜底。
    if (p_ftw != nullptr && p_ftw->level >= walk_state().limits.max_depth) {
        NATIVELIB_LOGW("calculateSize aborted: depth limit (%d) reached at %s", walk_state().limits.max_depth, path);
        walk_state().completed = false;
        return kWalkAbort;
    }

    if (walk_state().entries < walk_state().next_check_at) return 0;
    walk_state().next_check_at = walk_state().entries + kCheckInterval;

    if (walk_state().entries >= walk_state().limits.max_entries) {
        NATIVELIB_LOGW("calculateSize aborted: entry limit (%llu) reached at %s, returning partial size",
                       static_cast<unsigned long long>(walk_state().limits.max_entries), path);
        walk_state().completed = false;
        return kWalkAbort;
    }
    if (monotonic_millis() >= walk_state().deadline_millis) {
        NATIVELIB_LOGW("calculateSize aborted: time limit (%llu ms) reached at %s, returning partial size",
                       static_cast<unsigned long long>(walk_state().limits.max_duration_millis), path);
        walk_state().completed = false;
        return kWalkAbort;
    }
    return 0;
}

/**
 * 以"不跟随符号链接"的方式累计 path 下所有条目的 st_size 之和。
 *
 * @param path   待统计路径（若其本身是符号链接，会先 realpath 展开，保证语义与旧 ftw 一致）
 * @param limits 遍历上限，触限即降级返回部分结果
 */
inline WalkResult calculate_tree_size(const std::string &path, const WalkLimits &limits = WalkLimits{}) {
    WalkState &state = walk_state();
    state = WalkState{};
    state.limits = limits;
    if (state.limits.max_entries == 0) state.limits.max_entries = 1;
    state.next_check_at = std::min(kCheckInterval, state.limits.max_entries);
    state.deadline_millis = monotonic_millis() + state.limits.max_duration_millis;

    // nftw 在 FTW_PHYS 下用 lstat 看待根路径：根本身若是符号链接（例如 /sdcard、/data/data）
    // 就不会被展开，遍历只会得到链接自身。这里先解析根，做到"跟随根、但不跟随内部链接"。
    std::string root = path;
    struct stat root_stat{};
    if (lstat(path.c_str(), &root_stat) == 0 && S_ISLNK(root_stat.st_mode)) {
        char *resolved = realpath(path.c_str(), nullptr);
        if (resolved != nullptr) {
            root = resolved;
            free(resolved);
        } else {
            NATIVELIB_LOGW("calculateSize: realpath failed for '%s' (errno: %d), walking it as-is", path.c_str(), errno);
        }
    }

    const int result = nftw(root.c_str(), &on_walking, kWalkFdLimit, kWalkFlags);
    if (result == -1) {
        NATIVELIB_LOGW("calculateSize: nftw failed for '%s' (errno: %d)", root.c_str(), errno);
    }

    WalkResult out{};
    out.size = walk_state().size;
    out.entries = walk_state().entries;
    out.completed = walk_state().completed && result == 0;
    return out;
}

} // namespace NativeNS

#endif // NATIVELIB_CALCULATE_SIZE_H
