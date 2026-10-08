package com.xayah.libnative

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class FdSmokeTest {
    private lateinit var root: File
    private lateinit var repoPath: String

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = context.filesDir.resolve("fd-smoke-${System.nanoTime()}").apply { mkdirs() }
        repoPath = root.resolve("repo").absolutePath
        Rustic.initRepository(repoPath, PASSWORD)
        Log.i(TAG, "repo initialized at $repoPath")
    }

    @After
    fun tearDown() {
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test
    fun fdSnapshotRoundTrip() {
        // 1. 生成 10MB 确定性输入
        val input = root.resolve("input.bin")
        input.writeBytes(Random(42).nextBytes(INPUT_SIZE))
        Log.i(TAG, "input written: ${input.length()} bytes")

        // 2. createPipe → 后台线程写输入文件 → 主线程 createSnapshotFromFd
        val pipe = ParcelFileDescriptor.createPipe()
        val rfd = pipe[0]
        val wfd = pipe[1]

        val writer = Thread {
            FileInputStream(input).use { ins ->
                FileOutputStream(wfd.fileDescriptor).use { out ->
                    ins.copyTo(out, bufferSize = 1 shl 20)  // 1MB 块
                }
            }
            wfd.close()  // EOF：写端必须关闭
        }
        writer.start()

        val json = Rustic.createSnapshotFromFd(
            repositoryPath = repoPath,
            password = PASSWORD,
            fd = rfd.fd,
            filename = "smoke.tar",
            tags = listOf("fd-smoke"),
            callback = progressCallback,
            cancelId = 0L,
        )
        writer.join()
        rfd.close()

        Log.i(TAG, "snapshot result: $json")

        // 3. fedBytes 对账
        val result = JSONObject(json)
        val fedBytes = result.getLong("fedBytes")
        val snapshotId = result.getString("snapshotId")
        assertEquals("fedBytes must equal input size", INPUT_SIZE.toLong(), fedBytes)
        assertTrue(snapshotId.isNotBlank())

        // 4. restore 逐字节比对
        val restoreDir = root.resolve("restore").absolutePath
        Rustic.restoreSnapshot(repoPath, PASSWORD, snapshotId, restoreDir)

        val restored = File(restoreDir).walkTopDown()
            .filter { it.isFile && it.name == "smoke.tar" }
            .single()
        assertTrue("restored bytes must match input", restored.readBytes().contentEquals(input.readBytes()))
        Log.i(TAG, "round-trip OK, snapshot=$snapshotId")
    }

    @Test
    fun fdSnapshotTruncatedStream() {
        // 截断场景：写一半就关写端，fedBytes 应小于预期
        val pipe = ParcelFileDescriptor.createPipe()
        val rfd = pipe[0]
        val wfd = pipe[1]
        val half = INPUT_SIZE / 2

        val writer = Thread {
            FileOutputStream(wfd.fileDescriptor).use { out ->
                out.write(Random(42).nextBytes(half))
            }
            wfd.close()
        }
        writer.start()

        val json = Rustic.createSnapshotFromFd(
            repositoryPath = repoPath,
            password = PASSWORD,
            fd = rfd.fd,
            filename = "truncated.tar",
            tags = listOf("fd-smoke-truncated"),
            callback = progressCallback,
            cancelId = 0L,
        )
        writer.join()
        rfd.close()

        val fedBytes = JSONObject(json).getLong("fedBytes")
        Log.i(TAG, "truncated stream fedBytes=$fedBytes (expected $half)")
        assertEquals(half.toLong(), fedBytes)  // 上层据此判 abort
    }

    private companion object {
        init {
            System.loadLibrary("rustic")
            Rustic.initLogger()
        }

        const val TAG = "FdSmokeTest"
        const val PASSWORD = "fd-smoke-password"
        const val INPUT_SIZE = 10 * 1024 * 1024

        // JniProgressCallback 要求的方法签名: onProgress(JJFJJ)V
        val progressCallback = object {
            @Suppress("unused")
            fun onProgress(
                readBytes: Long,
                readTotal: Long,
                readProgress: Float,
                writtenBytes: Long,
                writtenSpeed: Long,
            ) {
                Log.d(TAG, "progress read=$readBytes/$readTotal written=$writtenBytes @${writtenSpeed}B/s")
            }
        }
    }
}