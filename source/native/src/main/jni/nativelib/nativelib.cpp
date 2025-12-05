#include <jni.h>
#include <string>
#include <ftw.h>
#include <sys/stat.h>
#include <climits>
#include <android/log.h>  // 添加这行

#define LOG_TAG "NativeLib"  // 添加这行
#define ALOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)  // 添加这行
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)    // 添加这行
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)     // 添加这行
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)     // 添加这行
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)    // 添加这行

namespace NativeNS {
    thread_local size_t total_size{0};

    int on_walking(const char *path, const struct stat *p_stat, int flag) {
        total_size += p_stat->st_size;
        return 0;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_xayah_libnative_NativeLib_calculateSize(JNIEnv *env, jobject, jstring path) {
    NativeNS::total_size = 0;
    const char *p_path = env->GetStringUTFChars(path, JNI_FALSE);

    ALOGD("Native calculateSize starting for path: %s", p_path);

    // 检查路径是否存在
    struct stat path_stat;
    if (stat(p_path, &path_stat) == -1) {
        ALOGE("Path does not exist or cannot access: %s (errno: %d)", p_path, errno);
        env->ReleaseStringUTFChars(path, p_path);
        return 0;
    }

    ALOGD("Path exists, starting ftw traversal for: %s", p_path);

    int result = ftw(p_path, &NativeNS::on_walking, 1024);

    ALOGD("ftw completed with result: %d, total_size: %zu", result, NativeNS::total_size);

    env->ReleaseStringUTFChars(path, p_path);
    return NativeNS::total_size;
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
