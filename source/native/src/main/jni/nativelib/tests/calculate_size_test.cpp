// 主机侧单元测试：验证 NativeNS::calculate_tree_size 在符号链接环、指向外部绝对路径的软链、
// 悬空链接、条目上限等场景下都能终止且结果合理。
//
// Windows 无法廉价创建符号链接，请在 Linux / macOS / WSL / CI 上执行：
//   g++ -std=c++17 -DNATIVELIB_UNIT_TEST -I<jni/nativelib> \
//       <jni/nativelib>/tests/calculate_size_test.cpp -o /tmp/calculate_size_test \
//       && /tmp/calculate_size_test
//
// 也可用 CMake（nativelib/CMakeLists.txt 中的 NATIVELIB_BUILD_HOST_TESTS 选项）。

#include "calculate_size.h"

#include <cstdio>
#include <cstdlib>
#include <string>
#include <unistd.h>

static int g_failures = 0;

#define CHECK(cond)                                                                            \
    do {                                                                                       \
        if (!(cond)) {                                                                         \
            std::fprintf(stderr, "FAIL %s:%d: `%s`\n", __FILE__, __LINE__, #cond);              \
            g_failures++;                                                                      \
        }                                                                                      \
    } while (false)

#define CHECK_EQ(a, b)                                                                         \
    do {                                                                                       \
        long long _a = static_cast<long long>(a);                                              \
        long long _b = static_cast<long long>(b);                                              \
        if (_a != _b) {                                                                        \
            std::fprintf(stderr, "FAIL %s:%d: `%s` == `%s` (%lld vs %lld)\n", __FILE__,         \
                         __LINE__, #a, #b, _a, _b);                                            \
            g_failures++;                                                                      \
        }                                                                                      \
    } while (false)

// ── 文件系统辅助 ──────────────────────────────────────────────────
static bool mkdirOne(const std::string &path) {
    return mkdir(path.c_str(), 0755) == 0 || errno == EEXIST;
}

static bool writeFile(const std::string &path, size_t bytes) {
    FILE *fp = std::fopen(path.c_str(), "wb");
    if (fp == nullptr) return false;
    if (bytes > 0) {
        char zero = 0;
        std::fseek(fp, static_cast<long>(bytes - 1), SEEK_SET);
        std::fwrite(&zero, 1, 1, fp);
    }
    std::fclose(fp);
    return true;
}

static int64_t lstatSize(const std::string &path) {
    struct stat st{};
    if (lstat(path.c_str(), &st) != 0) return -1;
    return static_cast<int64_t>(st.st_size);
}

static void removeTree(const std::string &path);

static std::string makeTempRoot(const char *name) {
    const std::string root =
        "/tmp/" + std::string(name) + "_" + std::to_string(static_cast<long>(getpid()));
    removeTree(root); // 复用同名目录时先清干净
    return mkdirOne(root) ? root : std::string("/tmp");
}

static int removeNode(const char *path, const struct stat *, int typeflag, struct FTW *) {
    if (typeflag == FTW_DP) return remove(path);
    if (typeflag == FTW_SL || typeflag == FTW_SLN || typeflag == FTW_F) return remove(path);
    return 0;
}

static void removeTree(const std::string &path) {
    nftw(path.c_str(), &removeNode, 64, FTW_PHYS | FTW_DEPTH | FTW_MOUNT);
}

// ── 用例 ─────────────────────────────────────────────────────────

// 1) 普通文件树：结果 == 文件大小之和 + 各目录项自身大小（与旧 ftw 口径一致）。
static void test_regular_tree() {
    const std::string root = makeTempRoot("regular_tree");
    CHECK(mkdirOne(root + "/sub"));
    CHECK(writeFile(root + "/a.bin", 1024));
    CHECK(writeFile(root + "/b.bin", 2048));
    CHECK(writeFile(root + "/sub/c.bin", 4096));

    const auto result = NativeNS::calculate_tree_size(root);
    const int64_t expected =
        1024 + 2048 + 4096 + lstatSize(root) + lstatSize(root + "/sub");

    CHECK(result.completed);
    CHECK_EQ(result.size, expected);
    CHECK(result.entries >= 5); // 根 + sub + 3 个文件
    removeTree(root);
}

// 2) 自引用符号链接（rootfs 里 `bin -> usr/bin` 这种形状的极端版）：
//    旧实现跟随链接会无限递归，FTW_PHYS 下应当一次遍历就结束。
static void test_symlink_cycle() {
    const std::string root = makeTempRoot("symlink_cycle");
    CHECK(mkdirOne(root + "/usr"));
    CHECK(mkdirOne(root + "/usr/bin"));
    CHECK(writeFile(root + "/usr/bin/tool", 512));
    CHECK(symlink((root + "/usr/bin").c_str(), (root + "/bin").c_str()) == 0);
    CHECK(symlink((root + "/usr").c_str(), (root + "/usr/self").c_str()) == 0);

    const uint64_t begin = NativeNS::monotonic_millis();
    const auto result = NativeNS::calculate_tree_size(root);
    const uint64_t cost = NativeNS::monotonic_millis() - begin;

    CHECK(result.completed);
    CHECK(result.entries < 64);
    CHECK(cost < 5000); // 必须秒级返回，而不是像旧实现那样永不返回
    // 链接自身大小计入，不递归进目标
    CHECK(result.size >= 512);
    removeTree(root);
}

// 3) 两个目录互相用绝对路径软链成环，同样必须终止。
static void test_mutual_absolute_symlinks() {
    const std::string root = makeTempRoot("mutual_links");
    CHECK(mkdirOne(root + "/a"));
    CHECK(mkdirOne(root + "/b"));
    CHECK(writeFile(root + "/a/f1", 128));
    CHECK(writeFile(root + "/b/f2", 256));
    CHECK(symlink((root + "/b").c_str(), (root + "/a/to_b").c_str()) == 0);
    CHECK(symlink((root + "/a").c_str(), (root + "/b/to_a").c_str()) == 0);

    const auto result = NativeNS::calculate_tree_size(root);
    CHECK(result.completed);
    CHECK(result.entries < 64);
    CHECK(result.size >= 128 + 256);
    removeTree(root);
}

// 4) 悬空符号链接：不崩溃、能跳过，且只记链接自身大小。
static void test_dangling_symlink() {
    const std::string root = makeTempRoot("dangling");
    CHECK(mkdirOne(root + "/d"));
    CHECK(writeFile(root + "/d/f", 64));
    CHECK(symlink("/definitely/not/exist/x", (root + "/d/broken").c_str()) == 0);

    const std::string link = root + "/d/broken";
    CHECK_EQ(lstatSize(link), static_cast<int64_t>(std::string("/definitely/not/exist/x").size()));

    const auto result = NativeNS::calculate_tree_size(root);
    CHECK(result.completed);
    CHECK_EQ(result.size, 64 + lstatSize(root) + lstatSize(root + "/d") + lstatSize(link));
    removeTree(root);
}

// 5) 根路径本身是符号链接：需要像旧 ftw 那样展开成真实目录后再统计。
static void test_root_is_symlink() {
    const std::string root = makeTempRoot("root_link");
    const std::string target = root + "/target";
    CHECK(mkdirOne(target));
    CHECK(writeFile(target + "/payload", 8192));
    const std::string link = root + "/link";
    CHECK(symlink(target.c_str(), link.c_str()) == 0);

    const auto via_link = NativeNS::calculate_tree_size(link);
    const auto via_target = NativeNS::calculate_tree_size(target);
    CHECK(via_link.completed);
    CHECK(via_target.completed);
    // 展开根之后，两者结果必须一致（而不是只剩一个链接的大小）
    CHECK_EQ(via_link.size, via_target.size);
    CHECK(via_link.size >= 8192);
    removeTree(root);
}

// 6) 条目上限：触限即降级返回部分值，绝不无限阻塞。
static void test_entry_limit_degradation() {
    const std::string root = makeTempRoot("entry_limit");
    CHECK(mkdirOne(root + "/many"));
    for (int i = 0; i < 200; i++) {
        CHECK(writeFile(root + "/many/f" + std::to_string(i), 16));
    }

    const auto full = NativeNS::calculate_tree_size(root);
    CHECK(full.completed);

    NativeNS::WalkLimits limits{};
    limits.max_entries = 8;
    const auto partial = NativeNS::calculate_tree_size(root, limits);
    CHECK(!partial.completed);
    CHECK(partial.entries <= 8);
    CHECK(partial.size >= 0);
    CHECK(partial.size < full.size);
    removeTree(root);
}

// 7) 不存在的路径：返回 0，不抛异常。
static void test_missing_path() {
    const auto result = NativeNS::calculate_tree_size("/definitely/not/exist/calculate_size_test");
    CHECK(!result.completed);
    CHECK_EQ(result.size, 0);
}

int main() {
    test_regular_tree();
    test_symlink_cycle();
    test_mutual_absolute_symlinks();
    test_dangling_symlink();
    test_root_is_symlink();
    test_entry_limit_degradation();
    test_missing_path();

    if (g_failures == 0) {
        std::printf("calculate_size_test: all cases passed\n");
        return 0;
    }
    std::printf("calculate_size_test: %d check(s) failed\n", g_failures);
    return 1;
}
