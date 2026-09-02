package com.xayah.core.util.command

import com.xayah.core.common.util.trim
import com.xayah.core.util.model.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object Tar {
    private const val TMP_PREFIX = "tar_tmp_"
    private const val TMP_SUFFIX = ".tmp"

    /** 由 root 进程执行 GNU tar 的 JNI 调用：TarWrapper.callCli(stdOut, stdErr, argv) */
    fun interface CallTar {
        suspend operator fun invoke(stdOut: String, stdErr: String, argv: Array<String>): Int
    }

    /**
     * 落盘系：tar 直接把归档写进 argv 里的 -cpf <dst> 目标文件，
     * stdOut/stdErr 仅用 cache 里的普通临时文件承接 tar 自身输出，靠退出码判定。
     */
    private suspend fun runToFiles(
        cacheDir: String,
        argv: Array<String>,
        callTar: CallTar,
    ): ShellResult = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        val stdOut = File.createTempFile(TMP_PREFIX, TMP_SUFFIX, File(cacheDir))
        val stdErr = File.createTempFile(TMP_PREFIX, TMP_SUFFIX, File(cacheDir))
        val status = runCatching {
            callTar(stdOut.path, stdErr.path, argv)
        }.getOrElse {
            out.add("Failed to call tar cli: ${it.message}")
            -1
        }
        runCatching { out.addAll(stdErr.readLines()) }
        stdOut.delete()
        stdErr.delete()
        ShellResult(code = status, input = argv.toList(), out = out)
    }

    // 落盘压缩：产出真实 .tar 文件
    suspend fun compressToFile(
        cacheDir: String,
        callTar: CallTar,
        exclusionList: List<String>,
        h: String,
        srcDir: String,
        src: String,
        dst: String,
    ): ShellResult {
        val argv = mutableListOf("tar", "--xattrs", "--xattrs-include=*", "--acls", "--selinux", "--totals")
        exclusionList.trim().forEach { argv.add("--exclude=$it") }
        if (h.isNotEmpty()) argv.add(h)
        argv.addAll(listOf("-cpf", dst, "-C", srcDir, "--", src))
        return runToFiles(cacheDir, argv.toTypedArray(), callTar)
    }

    // 落盘压缩（多文件）：把 srcDir 下多个文件打进同一个 .tar（backupApk 用）
    suspend fun compressFilesToFile(
        cacheDir: String,
        callTar: CallTar,
        srcDir: String,
        files: List<String>,
        dst: String,
    ): ShellResult {
        val argv = mutableListOf("tar", "--xattrs", "--xattrs-include=*", "--acls", "--selinux", "--totals")
        argv.addAll(listOf("-cpf", dst, "-C", srcDir, "--"))
        argv.addAll(files)
        return runToFiles(cacheDir, argv.toTypedArray(), callTar)
    }

    // ---------------- compress（改为落盘，去掉 feedToRustic / -cpf -）----------------

    suspend fun compressInCur(
        cacheDir: String,
        callTar: CallTar,
        cur: String,
        srcFiles: List<String>,   // 已由调用方把 "./*.apk" 展开成真实文件名
        dst: String,
    ): ShellResult {
        val argv = mutableListOf(
            "tar",
            "--xattrs", "--xattrs-include=*", "--acls", "--selinux",
            "--totals",
            "-C", cur,
            "-cpf", dst,
            "--",
        )
        argv.addAll(srcFiles)
        return runToFiles(cacheDir, argv.toTypedArray(), callTar)
    }

    suspend fun compress(
        cacheDir: String,
        callTar: CallTar,
        exclusionList: List<String>,
        h: String,
        srcDir: String,
        src: String,
        dst: String,
    ): ShellResult {
        val argv = mutableListOf("tar", "--xattrs", "--xattrs-include=*", "--acls", "--selinux", "--totals")
        if (h.isNotEmpty()) argv.add(h)
        exclusionList.trim().forEach { argv.add("--exclude=$it") }
        argv.addAll(listOf("-cpf", dst, "-C", srcDir, "--", src))
        return runToFiles(cacheDir, argv.toTypedArray(), callTar)
    }

    // ---------------- test / decompress ----------------

    suspend fun test(cacheDir: String, callTar: CallTar, src: String): ShellResult {
        val argv = arrayOf("tar", "-tf", src)
        return runToFiles(cacheDir, argv, callTar)
    }

    suspend fun decompress(
        cacheDir: String,
        callTar: CallTar,
        src: String,
        dst: String,
    ): ShellResult {
        val argv = arrayOf(
            "tar", "--xattrs", "--xattrs-include=*", "--acls", "--selinux",
            "--totals", "-xmpf", src, "-C", dst,
        )
        return runToFiles(cacheDir, argv, callTar)
    }

    suspend fun decompress(
        cacheDir: String,
        callTar: CallTar,
        src: String,
        dst: String,
        stripComponents: Int,
    ): ShellResult {
        val argv = mutableListOf(
            "tar", "--xattrs", "--xattrs-include=*", "--acls", "--selinux",
            "--totals",
        )
        if (stripComponents > 0) argv.add("--strip-components=$stripComponents")
        argv.addAll(listOf("-xmpf", src, "-C", dst))
        return runToFiles(cacheDir, argv.toTypedArray(), callTar)
    }

    suspend fun decompress(
        cacheDir: String,
        callTar: CallTar,
        exclusionList: List<String>,
        clear: String,
        m: Boolean,
        src: String,
        dst: String,
    ): ShellResult {
        val argv = mutableListOf("tar", "--xattrs", "--xattrs-include=*", "--acls", "--selinux", "--totals")
        exclusionList.trim().forEach { argv.add("--exclude=$it") }
        if (clear.isNotEmpty()) argv.add(clear)
        argv.add(if (m) "-xmpf" else "-xpf")
        argv.addAll(listOf(src, "-C", dst))
        return runToFiles(cacheDir, argv.toTypedArray(), callTar)
    }
}