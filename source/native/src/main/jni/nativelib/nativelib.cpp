#include <jni.h>
#include <string>
#include <ftw.h>
#include <sys/stat.h>
#include <climits>
#include <android/log.h>

#include "calculate_size.h"

#define LOG_TAG "NativeLib"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_com_xayah_libnative_NativeLib_calculateSize(JNIEnv *env, jobject, jstring path) {
    const char *p_path = env->GetStringUTFChars(path, JNI_FALSE);
    if (p_path == nullptr) {
        ALOGE("Path is null");
        return 0;
    }
    // 立即拷出并释放：遍历可能持续数秒，期间不再持有 Java 字符串。
    const std::string root(p_path);
    env->ReleaseStringUTFChars(path, p_path);

    ALOGD("Native calculateSize starting for path: %s", root.c_str());

    struct stat path_stat{};
    if (lstat(root.c_str(), &path_stat) == -1) {
        ALOGE("Path does not exist or cannot access: %s (errno: %d)", root.c_str(), errno);
        return 0;
    }

    // 不跟随符号链接（FTW_PHYS）+ 不跨挂载点（FTW_MOUNT）。
    // 旧实现的 ftw(3) 总是 stat() 跟随符号链接，在符号链接密集的 rootfs 上会反复进入
    // 同一批目录而永不返回；现在还有条目数 / 时长 / 深度兜底，最坏情况也是降级返回部分值。
    const NativeNS::WalkResult result = NativeNS::calculate_tree_size(root);

    if (result.completed) {
        ALOGD("calculateSize completed: %s, size: %lld, entries: %llu", root.c_str(),
              static_cast<long long>(result.size), static_cast<unsigned long long>(result.entries));
    } else {
        ALOGW("calculateSize degraded (partial): %s, size: %lld, entries: %llu", root.c_str(),
              static_cast<long long>(result.size), static_cast<unsigned long long>(result.entries));
    }

    return static_cast<jlong>(result.size);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_xayah_libnative_NativeLib_getUidGid(JNIEnv *env, jobject, jstring path) {
    struct stat file_stat{};
    jintArray result = env->NewIntArray(2); // result[0] - uid, result[1] - gid
    jint *p_result = env->GetIntArrayElements(result, nullptr);
    const char *p_path = env->GetStringUTFChars(path, JNI_FALSE);
    if (stat(p_path, &file_stat) == -1) {
        p_result[0] = UINT_MAX;
        p_result[1] = UINT_MAX;
    } else {
        p_result[0] = (int) file_stat.st_uid;
        p_result[1] = (int) file_stat.st_gid;
    }
    env->ReleaseIntArrayElements(result, p_result, 0);
    return result;
}
