#include <jni.h>
#include <string>
#include <ftw.h>
#include <sys/stat.h>
#include <climits>
#include <fts.h>
#include <android/log.h>
#include <unistd.h>
#include <sys/wait.h>
#include <iostream>
#include <fstream>
#include <asm-generic/fcntl.h>
#include <fcntl.h>
#include <sys/prctl.h>
#include <poll.h>
#include <signal.h>

#define LOG_TAG "Tar-Wrapper"
#define ALOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern int main(int argc, char **argv);
using std::string;

extern "C" JNIEXPORT jint JNICALL
Java_com_xayah_libnative_TarWrapper_callCli(JNIEnv *env, jclass clazz, jstring _out_path, jstring _err_path, jobjectArray _argv) {
    int argc = env->GetArrayLength(_argv);
    char **argv = new char *[argc + 1];   // +1 留给 null 结尾
    for (int i = 0; i < argc; i++) {
        auto *current = reinterpret_cast<jstring>(env->GetObjectArrayElement(_argv, i));
        const char *str = env->GetStringUTFChars(current, nullptr);
        argv[i] = new char[env->GetStringUTFLength(current) + 1];
        sprintf(argv[i], "%s", str);
        env->ReleaseStringUTFChars(current, str);
        env->DeleteLocalRef(current);
    }
    argv[argc] = nullptr;   // main()/getopt 依赖 null 结尾

    ALOGI("callCli: argc=%d", argc);
    for (int i = 0; i < argc; i++) {
        ALOGI("callCli: %s", argv[i]);
    }

    const char *out_path = env->GetStringUTFChars(_out_path, nullptr);
    const char *err_path = env->GetStringUTFChars(_err_path, nullptr);

    // Duplicate the stdout and stderr descriptors
    int saved_stdout = dup(STDOUT_FILENO);
    int saved_stderr = dup(STDERR_FILENO);

    int out_fd = open(out_path, O_WRONLY | O_CREAT | O_TRUNC, S_IRUSR | S_IWUSR);
    if (out_fd == -1) {
        ALOGE("Failed to open file: %s", out_path);
        env->ReleaseStringUTFChars(_out_path, out_path);
        env->ReleaseStringUTFChars(_err_path, err_path);
        close(saved_stdout);
        close(saved_stderr);
        return -1;
    }
    int err_fd = open(err_path, O_WRONLY | O_CREAT | O_TRUNC, S_IRUSR | S_IWUSR);
    if (err_fd == -1) {
        ALOGE("Failed to open file: %s", err_path);
        close(out_fd);
        env->ReleaseStringUTFChars(_out_path, out_path);
        env->ReleaseStringUTFChars(_err_path, err_path);
        close(saved_stdout);
        close(saved_stderr);
        return -1;
    }

    env->ReleaseStringUTFChars(_out_path, out_path);
    env->ReleaseStringUTFChars(_err_path, err_path);

    pid_t pid = fork();

    if (pid == 0) {
        // Child
        ALOGV("callCli: set PDEATHSIG to SIGKILL");
        prctl(PR_SET_PDEATHSIG, SIGKILL);
        if (getppid() == 1) {
            ALOGW("callCli: parent exited before prctl");
            _exit(-1);
        }

        dup2(out_fd, STDOUT_FILENO);
        dup2(err_fd, STDERR_FILENO);
        close(out_fd);
        close(err_fd);
        close(saved_stdout);
        close(saved_stderr);

        ALOGV("callCli: calling tar...");
        int result = main(argc, argv);
        ALOGV("callCli: child exited rc=%d", result);
        _exit(result);
    } else if (pid > 0) {
        close(out_fd);
        close(err_fd);
        close(saved_stdout);
        close(saved_stderr);
        int exit_status;
        if (waitpid(pid, &exit_status, 0) == -1) {
            ALOGE("callCli: waitpid failed pid=%d", pid);
            return -1;
        }
        int code = WIFEXITED(exit_status) ? WEXITSTATUS(exit_status) : -1;
        ALOGI("callCli: child=%d exit=%d (signaled=%d)", pid, code, WIFSIGNALED(exit_status) ? WTERMSIG(exit_status) : 0);
        return code;
    } else {
        ALOGE("Failed to fork.");
        close(out_fd);
        close(err_fd);
        close(saved_stdout);
        close(saved_stderr);
        return -1;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xayah_libnative_TarWrapper_callCliFd(JNIEnv *env, jclass clazz, jint out_fd_param, jstring _err_path, jobjectArray _argv, jint cancel_fd, jint cancel_write_fd) {
    int argc = env->GetArrayLength(_argv);
    char **argv = new char *[argc + 1];   // +1 留给 null 结尾
    for (int i = 0; i < argc; i++) {
        auto *current = reinterpret_cast<jstring>(env->GetObjectArrayElement(_argv, i));
        const char *str = env->GetStringUTFChars(current, nullptr);
        argv[i] = new char[env->GetStringUTFLength(current) + 1];
        sprintf(argv[i], "%s", str);
        env->ReleaseStringUTFChars(current, str);
        env->DeleteLocalRef(current);
    }
    argv[argc] = nullptr;

    ALOGI("callCliFd: argc=%d out_fd=%d cancel_fd=%d cancel_write_fd=%d", argc, out_fd_param, cancel_fd, cancel_write_fd);
    for (int i = 0; i < argc; i++) {
        ALOGI("callCliFd: %s", argv[i]);
    }

    const char *err_path = env->GetStringUTFChars(_err_path, nullptr);

    // Duplicate the stdout and stderr descriptors
    int saved_stdout = dup(STDOUT_FILENO);
    int saved_stderr = dup(STDERR_FILENO);

    // out_fd_param / cancel_fd are owned by the caller (Kotlin ParcelFileDescriptor)
    if (out_fd_param < 0) {
        ALOGE("callCliFd: invalid out fd=%d", out_fd_param);
        close(saved_stdout);
        close(saved_stderr);
        env->ReleaseStringUTFChars(_err_path, err_path);
        return -1;
    }

    int err_fd = open(err_path, O_WRONLY | O_CREAT | O_TRUNC, S_IRUSR | S_IWUSR);
    if (err_fd == -1) {
        ALOGE("callCliFd: Failed to open file: %s", err_path);
        close(saved_stdout);
        close(saved_stderr);
        env->ReleaseStringUTFChars(_err_path, err_path);
        return -1;
    }

    env->ReleaseStringUTFChars(_err_path, err_path);

    pid_t pid = fork();

    if (pid == 0) {
        // Child
        ALOGV("callCliFd: set PDEATHSIG to SIGKILL");
        prctl(PR_SET_PDEATHSIG, SIGKILL);
        if (getppid() == 1) {
            ALOGW("callCliFd: parent exited before prctl");
            _exit(-1);
        }

        dup2(out_fd_param, STDOUT_FILENO);
        dup2(err_fd, STDERR_FILENO);
        close(out_fd_param);
        close(err_fd);
        close(saved_stdout);
        close(saved_stderr);
        // cancel 管道两端都不能留在子进程：
        //  - cancel_write_fd 是写端继承副本，不关掉则父进程 cancelWrite.close() 后内核写端
        //    引用计数仍 ≥1，POLLHUP 永远不到 → abort 失效
        //  - cancel_fd 是读端，子进程用不到
        if (cancel_write_fd >= 0) close(cancel_write_fd);
        if (cancel_fd >= 0) close(cancel_fd);

        ALOGV("callCliFd: calling tar...");
        int result = main(argc, argv);
        ALOGV("callCliFd: child exited rc=%d", result);
        _exit(result);
    } else if (pid > 0) {
        close(err_fd);
        close(saved_stdout);
        close(saved_stderr);
        int exit_status;
        for (;;) {
            pid_t w = waitpid(pid, &exit_status, WNOHANG);
            if (w == pid) {
                int code = WIFEXITED(exit_status) ? WEXITSTATUS(exit_status) : -1;
                ALOGI("callCliFd: child=%d exit=%d (signaled=%d)", pid, code,
                      WIFSIGNALED(exit_status) ? WTERMSIG(exit_status) : 0);
                return code;
            }
            if (w == -1) {
                ALOGE("callCliFd: waitpid failed pid=%d errno=%d", pid, errno);
                return -1;
            }
            if (cancel_fd >= 0) {
                // poll 监听取消令牌：写端被关 → POLLIN|POLLHUP
                struct pollfd pfd;
                pfd.fd = cancel_fd;
                pfd.events = POLLIN | POLLHUP;
                pfd.revents = 0;
                int pr = poll(&pfd, 1, 200);
                if (pr > 0) {
                    ALOGE("callCliFd: cancel requested, killing child=%d", pid);
                    kill(pid, SIGKILL);
                    waitpid(pid, &exit_status, 0);
                    return -3;
                } else if (pr < 0) {
                    ALOGE("callCliFd: poll error errno=%d, killing child=%d", pid, errno);
                    kill(pid, SIGKILL);
                    waitpid(pid, &exit_status, 0);
                    return -1;
                }
            } else {
                usleep(200 * 1000);
            }
        }
    } else {
        ALOGE("callCliFd: fork failed");
        close(err_fd);
        close(saved_stdout);
        close(saved_stderr);
        return -1;
    }
}