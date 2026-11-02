package com.xayah.core.service.util

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import com.xayah.core.model.BenchmarkReport
import com.xayah.core.model.BenchmarkSample
import com.xayah.core.restic.COMPRESSION_KEY
import com.xayah.core.restic.resticExtraVerifyOptions
import com.xayah.core.rootservice.ICallback
import com.xayah.core.rootservice.service.RemoteRootService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 压缩档位基准：在设备上真跑 5 次"tar → rustic 建库写快照"，量出各档位的
 * 吞吐 / 压缩比 / 体积/时间收益比，最后给一个推荐档位。
 *
 * 为什么不直接复用生产备份任务：
 * 生产链路夹杂数据库写入、进度入库、云上传等无关变量，且不同用户的包集合不可控，
 * 无法横向比较档位。这里用固定合成语料（可复现、语义独立）+ 与生产完全相同的
 * tar argv 前缀（[PackagesBackupUtil.TAR_HEADER]）与 backupTarToRustic 入口，
 * 只把 rustic options 里的压缩级别替换成被测值。
 *
 * 隔离性：语料与临时仓库都放在 app 私有目录的 `files/bench/` 下，档间删库重建、
 * 结束时整目录删除，绝不触碰用户真实仓库。所有 IO 都经 root 服务，与生产同一条路径。
 */
/** 被测档位：OFF / 快速两档 / 默认 / 高压缩，覆盖 rustic v2 的 zstd 语义。 */
val BENCHMARK_LEVELS = listOf(0, 1, 3, 6, 19)

class CompressionBenchmarkUtil @Inject constructor(
    @ApplicationContext val context: Context,
    private val rootService: RemoteRootService,
) {
    companion object {
        private const val TAG = "CompressionBenchmark"

        /** 基准仓库密码。只用于临时语料仓库，与用户仓库密码无关。 */
        const val BENCH_PASSWORD = "databackup-benchmark"

        /** 同 [BENCHMARK_LEVELS]，类内引用用短名。 */
        val LEVELS = BENCHMARK_LEVELS

        /** 语料总量 128 MiB：足以摊薄约 600 ms 的固定开销，又不至于让低端机停留数分钟。 */
        private const val CORPUS_TOTAL = 128L * 1024 * 1024

        /**
         * 磁盘门槛 320 MiB。串行执行且档间立即删库，峰值只有「语料 128 MiB + 单档仓库 ≈128 MiB」，
         * 320 MiB 已含安全垫；低于此值直接拒绝，避免测到一半因 ENOSPC 得到无效样本。
         */
        private const val REQUIRED_FREE = 320L * 1024 * 1024

        /** 有效样本的耗时下限。只用于防 0 除与明显异常，旗舰机 128 MiB 约 260 ms 也可通过。 */
        private const val MIN_ELAPSED_MS = 250L

        /** 单档硬上限。zstd -19 在低端机上压 128 MiB 约需 1–2 分钟，240 s 足够宽松。 */
        private const val TIMEOUT_MS = 240_000L

        /** 协作式取消后等待 native 收尾的宽限期。 */
        private const val GRACE_MS = 15_000L

        /** fedBytes 与语料实际字节的允许偏差（tar 尾部块会补零，正常偏差远小于 1%）。 */
        private const val FED_TOLERANCE = 0.01

        private const val MIB = 1024L * 1024L
        private const val BUFFER_SIZE = MIB.toInt()
        private const val DIR_NAME = "bench"

        /** 语料文件清单。argv 与生成逻辑共用，避免两边漂移导致 fedBytes 对不上。 */
        private val CORPUS_FILES = listOf("media.bin", "text.log", "dex.bin", "misc.bin")

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 基准用的空进度回调。
         *
         * 必须非 null：Rust 侧 `createSnapshotFromFd` 对 `callback.is_null()` 直接早退报错，
         * 生产链路（[PackagesBackupUtil] / [MediumBackupUtil]）也始终自建 stub。
         * 传 null 会让每一档都在建快照阶段 9 ms 内失败，表现为「所有档位不可靠」。
         * 刻意不打日志、不做任何计算，避免给被测窗口引入额外抖动。
         */
        private val noopCallback = object : ICallback.Stub() {
            override fun onProgress(readBytes: Long, readTotal: Long, readProgress: Float, writtenBytes: Long, writtenSpeed: Long) = Unit

            override fun onRestorePlan(filesTotal: Long, bytesTotal: Long, filesSkipped: Long, bytesSkipped: Long) = Unit
        }
    }

    /** 基准过程中的阶段事件，仅供 UI 展示。 */
    data class Progress(
        val phase: Phase,
        val level: Int = -1,
        val index: Int = 0,
        val total: Int = LEVELS.size,
        val samples: List<BenchmarkSample> = emptyList(),
        val detail: String = "",
    ) {
        enum class Phase { PREPARING, RUNNING, FINISHED }
    }

    /** 独立 scope：即使用户退出页面，native 调用也需要能跑完/被显式取消，不能随 UI 一起消失。 */
    private val utilScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前正在跑的档位的 cancelId，0 表示空闲。 */
    @Volatile
    private var activeCancelId = 0L

    /** 用户取消：只请求 root 侧停下，协程取消由调用方负责。 */
    fun cancel() {
        val id = activeCancelId
        if (id != 0L) {
            Log.i(TAG, "cancel requested, cancelId=$id")
            utilScope.launch { runCatching { rootService.cancelRusticBackup(id) } }
        }
    }

    /**
     * 执行完整基准。串行 5 档，每档：删旧库 → 建库（带被测压缩级别）→ validate →
     * backupTarToRustic → 统计 data 体积 → 删库。
     *
     * @throws IllegalStateException 磁盘不足，或 native 调用在取消后仍不返回（后续档必然卡死）
     */
    suspend fun run(onProgress: (Progress) -> Unit): BenchmarkReport = withContext(Dispatchers.IO) {
        val free = StatFs(context.filesDir.absolutePath).availableBytes
        require(free >= REQUIRED_FREE) {
            "insufficient storage: $free < $REQUIRED_FREE"
        }

        val root = File(context.filesDir, DIR_NAME)
        val corpus = File(root, "corpus")

        // 上次被强杀/断电时 finally 可能没跑完，留下 100+ MiB 的语料与仓库。
        // 这里整目录重清（方案 §13「下次启动扫描 bench/ 重清」），避免残留占空间。
        runCatching { rootService.deleteRecursively(root.absolutePath) }
            .onFailure { Log.w(TAG, "stale bench cleanup failed: ${it.message}") }

        onProgress(Progress(Progress.Phase.PREPARING, detail = "generating corpus"))
        val corpusBytes = prepareCorpus(corpus)
        Log.i(TAG, "corpus ready: ${corpus.absolutePath}, $corpusBytes bytes")

        val samples = mutableListOf<BenchmarkSample>()
        try {
            // 全局预热：native 首次加载、JIT、zstd 上下文、页缓存都是一次性成本，
            // 先用 L0 在 repo_warm 跑一遍并丢弃，否则第一个正式档位会被系统性低估。
            onProgress(Progress(Progress.Phase.PREPARING, detail = "warming up"))
            val warmRepo = File(root, "repo_warm")
            runCatching { runOnce(0, warmRepo, corpus, corpusBytes) }
                .onSuccess { Log.i(TAG, "warmup done: ${it.elapsedMs}ms") }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "warmup failed: ${it.message}")
                }
            runCatching { rootService.deleteRecursively(warmRepo.absolutePath) }

            LEVELS.forEachIndexed { index, level ->
                ensureActive()
                // 每档独立仓库：rustic 写前按内容 ID 去重，同库跑第二遍会全命中，
                // 数字是假的（§2.2）；且压缩级别只在建库时写入仓库 config。
                val repo = File(root, "repo_l$level")
                val sample = try {
                    runOnce(level, repo, corpus, corpusBytes) { detail ->
                        onProgress(
                            Progress(
                                phase = Progress.Phase.RUNNING,
                                level = level,
                                index = index,
                                total = LEVELS.size,
                                samples = samples.toList(),
                                detail = detail,
                            )
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 单档建库/validate/写入抛异常：该档记 unreliable 并继续下一档，
                    // 避免一次偶发失败把整轮结果作废（方案 §13）。
                    Log.w(TAG, "level=$level failed: ${e.message}")
                    BenchmarkSample(
                        level = level,
                        reliable = false,
                        note = "setup failed: ${e.message ?: e.toString()}",
                    )
                }
                samples += sample
                Log.i(
                    TAG,
                    "level=$level fed=${sample.fedBytes} data=${sample.dataBytes} " +
                        "elapsed=${sample.elapsedMs}ms throughput=${sample.throughputMBps} " +
                        "ratio=${sample.ratio} reliable=${sample.reliable} note=${sample.note}",
                )
                runCatching { rootService.deleteRecursively(repo.absolutePath) }
            }

            val checked = markMonotonicity(samples.toList())
            val report = BenchmarkReport(
                measuredAt = System.currentTimeMillis(),
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                socModel = socModel(),
                appVersionName = appVersion(),
                corpusBytes = corpusBytes,
                charging = isCharging(),
                batteryPercent = batteryPercent(),
                samples = checked,
                recommendedLevel = recommend(checked),
            )
            onProgress(Progress(Progress.Phase.FINISHED, samples = checked))
            Log.i(TAG, "finished, recommended=${report.recommendedLevel}")
            report
        } catch (e: CancellationException) {
            // 协程已经取消，普通 suspend 调用会立刻抛 CancellationException，必须 NonCancellable
            withContext(NonCancellable) {
                val id = activeCancelId
                if (id != 0L) runCatching { rootService.cancelRusticBackup(id) }
            }
            throw e
        } finally {
            activeCancelId = 0L
            // 语料与临时仓库一并清理；失败不影响用户数据，只记日志。
            // NonCancellable：取消路径下也必须真的把 128 MiB 语料删掉，否则会长期占用空间。
            withContext(NonCancellable) {
                runCatching { rootService.deleteRecursively(root.absolutePath) }
                    .onFailure { Log.w(TAG, "cleanup failed: ${it.message}") }
            }
        }
    }

    /**
     * 单档完整流程：删旧库 → 建库（写入被测压缩级别）→ validate（在计时窗口外打上
     * extra_verify 已关闭的标记）→ 计时写入。三件事都不计时，只有 [measure] 内部计。
     */
    private suspend fun runOnce(
        level: Int,
        repo: File,
        corpus: File,
        corpusBytes: Long,
        onPhase: (String) -> Unit = {},
    ): BenchmarkSample {
        rootService.deleteRecursively(repo.absolutePath)
        repo.mkdirs()
        val options = benchmarkOptions(level)
        onPhase("init repository")
        rootService.initRusticRepository(repo.absolutePath, BENCH_PASSWORD, options).getOrThrow()
        rootService.validateRusticRepository(repo.absolutePath, BENCH_PASSWORD, options).getOrThrow()
        onPhase("writing snapshot")
        return measure(level, corpus, repo, options, corpusBytes)
    }

    /** 单档测量。tar 与 rustic 都跑在 root 进程，这里只负责计时与结果解析。 */
    private suspend fun measure(
        level: Int,
        corpus: File,
        repo: File,
        options: Map<String, String>,
        corpusBytes: Long,
    ): BenchmarkSample {
        val cancelId = SystemClock.elapsedRealtimeNanos()
        activeCancelId = cancelId

        // 与生产一致的前缀 + 显式文件清单（不打包目录条目，fedBytes 只含文件头）。
        val argv = PackagesBackupUtil.TAR_HEADER +
            listOf("-cpf", "-", "-C", corpus.absolutePath, "--") + CORPUS_FILES
        val dataDir = File(repo, "data").absolutePath

        var payload: String? = null
        var failure: String? = null
        var timedOut = false
        val started = SystemClock.elapsedRealtime()
        // 放到独立 scope：withTimeoutOrNull 只放弃等待，不会打断阻塞的 Binder 调用，
        // 这里必须先 cancelRusticBackup 让 native 侧协作退出，再等它真正返回。
        val call = utilScope.async {
            runCatching {
                rootService.backupTarToRustic(
                    repositoryPath = repo.absolutePath,
                    password = BENCH_PASSWORD,
                    filename = "bench-level-$level.tar",
                    argv = argv,
                    tags = listOf("benchmark"),
                    options = options,
                    callback = noopCallback,
                    cancelId = cancelId,
                )
            }
        }
        try {
            val immediate = withTimeoutOrNull(TIMEOUT_MS) { call.await() }
            if (immediate == null) {
                timedOut = true
                Log.w(TAG, "level=$level timeout after ${TIMEOUT_MS}ms, cancelling cancelId=$cancelId")
                withContext(NonCancellable) { runCatching { rootService.cancelRusticBackup(cancelId) } }
                val salvaged = withTimeoutOrNull(GRACE_MS) { call.await() }
                if (salvaged == null) {
                    // 连取消都唤不醒：native 锁被永久占用，继续跑下一档只会一起卡死
                    call.cancel()
                    throw IllegalStateException("benchmark aborted: native call did not return after cancel")
                }
                salvaged.onSuccess { payload = it }.onFailure { failure = it.message?.takeIf { m -> m.isNotBlank() } ?: it.javaClass.name }
            } else {
                immediate.onSuccess { payload = it }.onFailure { failure = it.message?.takeIf { m -> m.isNotBlank() } ?: it.javaClass.name }
            }
        } finally {
            if (activeCancelId == cancelId) activeCancelId = 0L
        }
        val elapsedMs = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)

        val parsed = payload?.let { raw ->
            runCatching {
                val obj = json.parseToJsonElement(raw).jsonObject
                Triple(
                    obj["fedBytes"]?.jsonPrimitive?.longOrNull ?: -1L,
                    obj["tarCode"]?.jsonPrimitive?.intOrNull ?: -1,
                    obj["snapshotId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }.getOrNull()
        }
        val fedBytes = parsed?.first ?: -1L
        val tarCode = parsed?.second ?: -1
        val snapshotId = parsed?.third.orEmpty()
        val dataBytes = rootService.calculateSize(dataDir)

        val notes = mutableListOf<String>()
        if (timedOut) notes += "timeout"
        if (failure != null) notes += "call failed: $failure"
        if (parsed == null && failure == null) notes += "unparsable result"
        if (snapshotId.isEmpty()) notes += "no snapshot"
        // tar 退出码 1 是 "some files differ"（xattrs 在合成语料上可能告警），仍算有效。
        if (tarCode != 0 && tarCode != 1) notes += "tar exit=$tarCode"
        if (elapsedMs < MIN_ELAPSED_MS) notes += "suspiciously fast ${elapsedMs}ms"
        if (fedBytes <= 0) {
            notes += "no fed bytes"
        } else if (corpusBytes > 0) {
            val drift = kotlin.math.abs(fedBytes - corpusBytes).toDouble() / corpusBytes
            if (drift > FED_TOLERANCE) notes += "fed bytes drift ${"%.1f".format(drift * 100)}%"
        }
        if (dataBytes <= 0) notes += "empty data dir"

        return BenchmarkSample(
            level = level,
            fedBytes = fedBytes,
            dataBytes = dataBytes,
            elapsedMs = elapsedMs,
            tarCode = tarCode,
            reliable = notes.isEmpty(),
            note = notes.joinToString("; "),
        )
    }

    /**
     * 单调性校验（只校验压缩比，速度仅记日志）。
     *
     * - 速度**不再**作为可靠性门禁：zstd 各档位并不总是线性递减 —— 不同级别会切换到不同的
     *   匹配策略族（fast / dfast / lazy / bt），L1→L3→L6 之间出现 ±20% 的交叉在真机上很常见，
     *   再叠加调频、后台 IO 抖动就更容易触发。把它升级成「整轮 unreliable」会让 5 个档位
     *   全部标红、表格全变 `-`，用户拿不到任何结论，误伤远大于收益，故只在倒挂超过 50%
     *   （明显不合常理）时记一条日志备查。
     * - 压缩比才是纯算法属性，破坏 10% 以上只把相邻两档标灰，不会波及整轮。
     */
    private fun markMonotonicity(samples: List<BenchmarkSample>): List<BenchmarkSample> {
        val result = samples.toMutableList()
        for (i in 0 until result.size - 1) {
            val current = result[i]
            val next = result[i + 1]
            if (!current.reliable || !next.reliable) continue
            if (next.throughputMBps > current.throughputMBps * 1.5) {
                Log.w(
                    TAG,
                    "speed not monotonic (ignored): L${current.level}=${current.throughputMBps} MiB/s " +
                        "< L${next.level}=${next.throughputMBps} MiB/s",
                )
            }
            if (current.ratio < next.ratio * 0.9) {
                result[i] = current.withNote("ratio not monotonic")
                result[i + 1] = next.withNote("ratio not monotonic")
            }
        }
        return result
    }

    private fun BenchmarkSample.withNote(note: String): BenchmarkSample = copy(
        reliable = false,
        note = listOf(this.note, note).filter { it.isNotEmpty() }.joinToString("; "),
    )

    /**
     * 推荐算法：先要求相对 OFF 的体积收益 ≥ 10%，再在够格的档位里取「每增加 1 秒
     * 换到的体积收益」最大者。收益不到 10% 时直接推荐 OFF——省 CPU、省电、还更快。
     *
     * 耗时差有下限兜底（OFF 的 2%）：真机上 OFF/L1/L3/L6 的端到端吞吐经常完全相同
     * （链路被 IO / tar 管道限死，压缩根本不是瓶颈，见 OnePlus PKX110 实测 4 档同为
     * 80.4 MiB/s）。若按 0 处理会除零，并退化成「谁先遍历到就选谁」；兜底后这几档
     * 改按体积竞争，自动选到压得更小的那一档。
     */
    private fun recommend(samples: List<BenchmarkSample>): Int {
        val valid = samples.filter { it.reliable }
        val off = valid.firstOrNull { it.level == 0 } ?: return -1
        if (off.throughputMBps <= 0.0) return -1

        val offSeconds = 1.0 / off.throughputMBps
        val minExtraSeconds = offSeconds * 0.02
        var bestLevel = -1
        var bestScore = 0.0
        valid.filter { it.level != 0 }.forEach { sample ->
            if (sample.throughputMBps <= 0.0) return@forEach
            val ratioGain = off.ratio - sample.ratio
            if (ratioGain < 0.10) return@forEach
            // 更快（含抖动内同速）的档位按 2% 计，既避免除零，也让它稳拿高排名
            val extraSeconds = (1.0 / sample.throughputMBps - offSeconds).coerceAtLeast(minExtraSeconds)
            val score = ratioGain / extraSeconds
            if (score > bestScore) {
                bestScore = score
                bestLevel = sample.level
            }
        }
        return bestLevel
    }

    /** 被测档位的 rustic options：压缩级别 + 关闭写端自校验（与生产一致，避免自校验干扰档位对比）。 */
    private fun benchmarkOptions(level: Int): Map<String, String> =
        mapOf(COMPRESSION_KEY to level.toString()) + resticExtraVerifyOptions()

    // ==================== 语料 ====================

    /**
     * 生成 128 MiB 固定语料，四段语义：
     * - media.bin 48 MiB 高熵随机（对应照片/视频，压不动）
     * - text.log  32 MiB 重复日志行（对应日志/文本，压得动）
     * - dex.bin   28 MiB 每 64 KiB 混 8 KiB 随机（对应 dex/so，半压得动）
     * - misc.bin  20 MiB 每 4 KiB 仅 32 B 随机（对应各类稀疏/对齐数据）
     */
    private fun prepareCorpus(dir: File): Long {
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        val rnd = Random(0x5EED2026)
        writeRandom(File(dir, "media.bin"), 48L * MIB, rnd)
        writeTextLog(File(dir, "text.log"), 32L * MIB, rnd)
        writeDexLike(File(dir, "dex.bin"), 28L * MIB, rnd)
        writeBlocky(File(dir, "misc.bin"), 20L * MIB, rnd)
        require(CORPUS_FILES.all { File(dir, it).isFile }) { "corpus incomplete" }
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun writeRandom(file: File, size: Long, rnd: Random) {
        val buffer = ByteArray(BUFFER_SIZE)
        file.outputStream().buffered(BUFFER_SIZE).use { out ->
            var left = size
            while (left > 0) {
                val chunk = minOf(left, buffer.size.toLong()).toInt()
                rnd.nextBytes(buffer, 0, chunk)
                out.write(buffer, 0, chunk)
                left -= chunk
            }
        }
    }

    private fun writeTextLog(file: File, size: Long, rnd: Random) {
        val line = "2026-11-02 12:34:56.789  12456  12503 I ActivityManager: Process com.example.app started with pid 12345, state {cached}\n".toByteArray()
        val block = ByteArray(BUFFER_SIZE)
        var pos = 0
        while (pos < block.size) {
            val n = minOf(line.size, block.size - pos)
            System.arraycopy(line, 0, block, pos, n)
            pos += n
        }
        file.outputStream().buffered(BUFFER_SIZE).use { out ->
            var left = size
            while (left > 0) {
                // 每块打一点随机噪声，避免 32 MiB 完全同构导致压缩比虚高。
                // 注意 kotlin.random.Random.nextBytes(array, fromIndex, toIndex) 的第 3 参是
                // 区间上界而不是长度，写成 (start, 64) 会在 start > 64 时抛 IllegalArgumentException。
                val noiseStart = rnd.nextInt(block.size - 64)
                rnd.nextBytes(block, noiseStart, noiseStart + 64)
                val chunk = minOf(left, block.size.toLong()).toInt()
                out.write(block, 0, chunk)
                left -= chunk
            }
        }
    }

    private fun writeDexLike(file: File, size: Long, rnd: Random) {
        val block = ByteArray(BUFFER_SIZE)
        val segment = 64 * 1024
        file.outputStream().buffered(BUFFER_SIZE).use { out ->
            var left = size
            while (left > 0) {
                var offset = 0
                while (offset < block.size && offset < left) {
                    val head = minOf(8 * 1024, block.size - offset)
                    rnd.nextBytes(block, offset, offset + head)
                    val tailStart = offset + head
                    val tailEnd = minOf(offset + segment, block.size)
                    if (tailStart < tailEnd) block.fill(0x4D.toByte(), tailStart, tailEnd)
                    offset += segment
                }
                val chunk = minOf(left, block.size.toLong()).toInt()
                out.write(block, 0, chunk)
                left -= chunk
            }
        }
    }

    private fun writeBlocky(file: File, size: Long, rnd: Random) {
        val block = ByteArray(BUFFER_SIZE)
        file.outputStream().buffered(BUFFER_SIZE).use { out ->
            var left = size
            while (left > 0) {
                var offset = 0
                while (offset < block.size && offset < left) {
                    rnd.nextBytes(block, offset, offset + 32)
                    offset += 4096
                }
                val chunk = minOf(left, block.size.toLong()).toInt()
                out.write(block, 0, chunk)
                left -= chunk
            }
        }
    }

    // ==================== 设备指纹 ====================

    private fun socModel(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL
    } else {
        Build.HARDWARE
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun batteryPercent(): Int = runCatching {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }.getOrDefault(-1)

    private fun isCharging(): Boolean = runCatching {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        manager?.isCharging ?: false
    }.getOrDefault(false)
}
