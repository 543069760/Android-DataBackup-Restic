package com.xayah.core.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import com.xayah.core.common.util.BuildConfigUtil
import com.xayah.core.ui.R
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.theme.withState
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.BaseUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

@ExperimentalFoundationApi
@Composable
fun PackageIconImage(
    icon: ImageVector? = null,
    packageName: String,
    shape: Shape? = null,
    inCircleShape: Boolean = false,
    size: Dp = SizeTokens.Level32,
    accountId: String? = null,
    iconVersion: Int = 0
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val sizeForeground = if (inCircleShape) size.div(sqrt(2.2F)) else size
    val sizePx = with(density) { sizeForeground.roundToPx() }
    // 缓存键包含尺寸与图标版本：位图按目标像素尺寸光栅化，尺寸不同不能复用
    val cacheKey = remember(packageName, accountId, iconVersion, sizePx, inCircleShape) {
        "$packageName|${accountId.orEmpty()}|$iconVersion|$sizePx|$inCircleShape"
    }
    // L2 存的是 WebP 字节，取用需要解码（约 1~2ms），不能在 composition 里同步做。
    // 因此先同步查 L1（已解码位图，零成本），未命中再交给 LaunchedEffect 的 IO 线程。
    var bitmaps by remember(cacheKey) {
        mutableStateOf(PackageIconCache.getDecoded(cacheKey))
    }

    LaunchedEffect(cacheKey, icon) {
        if (icon != null || bitmaps != null) return@LaunchedEffect
        val startedAt = SystemClock.elapsedRealtime()
        val loaded = withContext(Dispatchers.IO) {
            val cached = PackageIconCache.get(cacheKey, packageName, sizePx)
            if (cached != null) return@withContext cached
            val fresh = loadPackageIconBitmaps(context, packageName, accountId, sizePx, inCircleShape)
            PackageIconCache.put(cacheKey, fresh)
            if (BuildConfigUtil.DEBUG) {
                Log.d(
                    PackageIconCache.TAG,
                    "load pkg=$packageName size=${sizePx}px " +
                            "fg=${fresh.foreground?.let { "${it.width}x${it.height}" } ?: "none"} " +
                            "bg=${fresh.background?.let { "${it.width}x${it.height}" } ?: "none"} " +
                            "ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
            }
            fresh
        }
        bitmaps = loaded
    }

    Box(modifier = if (shape != null) Modifier.clip(shape) else Modifier, contentAlignment = Alignment.Center) {
        with(density) {
            val background = bitmaps?.background
            if (background != null) {
                Image(
                    modifier = Modifier
                        .size(size)
                        .clip(CircleShape)
                        .scale(1.4f)
                        .background(ThemedColorSchemeKeyTokens.PrimaryContainer.value),
                    bitmap = background,
                    contentDescription = null,
                )
            } else {
                // 占位：与旧 AsyncImage 空数据时一致，仅保留底色
                Box(
                    modifier = Modifier
                        .size(size)
                        .clip(CircleShape)
                        .scale(1.4f)
                        .background(ThemedColorSchemeKeyTokens.PrimaryContainer.value)
                )
            }
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    modifier = Modifier
                        .size(sizeForeground),
                    contentDescription = null,
                    tint = ThemedColorSchemeKeyTokens.Primary.value
                )
            } else {
                val foreground = bitmaps?.foreground
                if (foreground != null) {
                    Image(
                        modifier = Modifier
                            .size(sizeForeground),
                        bitmap = foreground,
                        contentDescription = null,
                    )
                }
            }
        }
    }
}

/**
 * 单个包图标的两层位图（自适应图标才有 background）。
 */
private data class PackageIconBitmaps(
    val foreground: ImageBitmap? = null,
    val background: ImageBitmap? = null,
)

/**
 * 缓存条目：位图以 WebP 字节存储，而不是常驻 [ImageBitmap]。
 *
 * 112px 的 ARGB_8888 位图占 50,176 字节，WebP 无损压缩后约 8~20KB，
 * 同样的内存预算能多存 3~6 倍条目，从而避免「应用数超过容量上限后
 * 每次滚动都淘汰-重载」的 LRU 抖动。解码成本约 1~2ms，远低于
 * PackageManager binder 的 4~30ms。
 */
private class PackageIconEntry(
    val foreground: ByteArray?,
    val background: ByteArray?,
) {
    /** 计入 LRU 的字节数：压缩后体积 + 少量对象头开销。 */
    val bytes: Int = (foreground?.size ?: 0) + (background?.size ?: 0) + ENTRY_OVERHEAD

    /** 两层都没编出字节，说明编码失败，不值得入缓存。 */
    val isEmpty: Boolean = foreground == null && background == null

    /**
     * 解码为可直接绘制的位图。调用方负责放到 IO 线程。
     *
     * 返回 null 表示字节存在但解码失败（数据损坏），调用方应视为未命中
     * 重新加载，否则会留下永久空白的图标。
     */
    fun decode(): PackageIconBitmaps? {
        val fg = foreground?.decodeToImageBitmap()
        val bg = background?.decodeToImageBitmap()
        if (foreground != null && fg == null) return null
        if (background != null && bg == null) return null
        return PackageIconBitmaps(foreground = fg, background = bg)
    }

    companion object {
        private const val ENTRY_OVERHEAD = 64
    }
}

/**
 * 进程内图标缓存。列表滚动、勾选、筛选、排序都会让 item 重组或重建，
 * 没有跨 item 缓存时每次都要重新走 PackageManager binder + 光栅化。
 *
 * 存储形态为 WebP 字节（见 [PackageIconEntry]），容量按压缩后体积计算。
 */
private object PackageIconCache {
    /** logcat 过滤用：`adb logcat -s IconCache:V`。仅 [BuildConfigUtil.DEBUG] 下输出。 */
    const val TAG = "IconCache"

    /** 压缩比统计用：每 N 次 put 打一行汇总，避免刷屏。 */
    private const val STATS_INTERVAL = 50

    /**
     * 容量上限。压缩存储后单条约 7~20KB（实测 112px 图标约 7.2KB），
     * 72MB 可容纳约 3600~10000 个图标，远超任何真实设备的应用总数。
     *
     * 注意：LruCache 按需分配，上限只是天花板，不会预占内存。
     * 实测 558 个图标仅占 4MB，因此这个上限实际永远不会被触及。
     */
    private val maxBytes = minOf(Runtime.getRuntime().maxMemory() / 8, 72L * 1024 * 1024).toInt()

    private val cache = object : LruCache<String, PackageIconEntry>(maxBytes) {
        override fun sizeOf(key: String, value: PackageIconEntry): Int = value.bytes

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: PackageIconEntry, newValue: PackageIconEntry?) {
            // 只有 evicted=true 才是被 LRU 挤掉；put 覆盖同键时 evicted=false，不算淘汰。
            // 注意：回调时 size 已减去 oldValue，所以这里打 oldValue.bytes 才有信息量。
            if (evicted && BuildConfigUtil.DEBUG) {
                val n = evictions.incrementAndGet()
                Log.d(
                    TAG,
                    "evict pkg=${key.substringBefore('|')} freed=${oldValue.bytes}B " +
                            "n=$n used=${size()}/$maxBytes"
                )
            }
        }
    }

    /**
     * L1：已解码位图缓存，**按字节计**。
     *
     * 按条目数计是错的：图标像素尺寸随 DPI 变化（112px 在 3x 屏上是 168px，
     * 单条内存翻 2.25 倍），固定条目数会让高 DPI 设备悄悄吃掉数倍内存，
     * 而日志里完全看不出来。按字节计让容量自解释。
     *
     * 取 maxMemory/16（256MB 堆约 16MB，容纳约 160 个 112px 图标）。
     * 这个容量只够覆盖可见窗口 + 预取余量，**不足以覆盖整份列表**：
     * 全量回滚时仍会走 L2 解码，这是压缩存储的固有代价，不是缺陷。
     * 见 [get] 的说明。
     */
    private val l1MaxBytes = (Runtime.getRuntime().maxMemory() / 16).toInt()
    private val decodedCache = object : LruCache<String, PackageIconBitmaps>(l1MaxBytes) {
        // 0 字节条目（前景背景都为空）按 1 计，避免 LruCache 认为它不占空间而无限堆积。
        override fun sizeOf(key: String, value: PackageIconBitmaps): Int =
            maxOf(1, rawBytesOf(value))
    }

    private val hits = AtomicLong()
    private val misses = AtomicLong()
    private val l1Hits = AtomicLong()
    private val puts = AtomicLong()
    private val evictions = AtomicLong()
    /** 解码耗时累计，单位微秒。用微秒是因为单次解码常低于 1ms，毫秒会全截断成 0。 */
    private val decodeTotalUs = AtomicLong()
    private val decodeCount = AtomicLong()
    private val rawBytesTotal = AtomicLong()
    private val packedBytesTotal = AtomicLong()

    /**
     * 同步取已解码位图，只查 L1。可在 composition 里调用（无解码、无 IO）。
     * 返回 null 表示 L1 未命中，调用方应走 [get] 的异步路径。
     *
     * 这是 L1 的主要命中路径：命中后调用方直接拿到位图，不会再进 [get]。
     * 因此计数必须在这里做，否则日志里 L1 永远是 0。
     */
    fun getDecoded(key: String): PackageIconBitmaps? {
        val value = decodedCache.get(key) ?: return null
        if (BuildConfigUtil.DEBUG) {
            val n = l1Hits.incrementAndGet()
            // 每 50 次打一行，避免逐条刷屏。
            if (n % STATS_INTERVAL == 0L) {
                Log.d(
                    TAG,
                    "l1-hit n=$n l1ratio=${l1Ratio()}% " +
                            "l1used=${decodedCache.size()}/$l1MaxBytes " +
                            "h=${hits.get()} m=${misses.get()} used=${cache.size()}/$maxBytes"
                )
            }
        }
        return value
    }

    /**
     * 取缓存条目并解码。**必须在 IO 线程调用**（解码约 1~2ms）。
     * 返回 null 表示未命中，调用方应走 [loadPackageIconBitmaps] 重新加载。
     */
    fun get(key: String, packageName: String, sizePx: Int): PackageIconBitmaps? {
        // 兜底：正常流程下 composition 已通过 getDecoded 查过 L1，这里极少命中。
        decodedCache.get(key)?.let { return it }
        val entry = cache.get(key)
        if (entry == null) {
            if (BuildConfigUtil.DEBUG) {
                val m = misses.incrementAndGet()
                Log.d(
                    TAG,
                    "miss pkg=$packageName size=${sizePx}px " +
                            "h=${hits.get()} m=$m ratio=${ratio()}% " +
                            "used=${cache.size()}/$maxBytes evict=${evictions.get()}"
                )
            }
            return null
        }
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val decoded = entry.decode()
        val costUs = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000
        if (decoded == null) {
            // 字节损坏：剔除该条目并按未命中处理，让调用方重新加载。
            cache.remove(key)
            if (BuildConfigUtil.DEBUG) {
                Log.w(TAG, "decode-failed pkg=$packageName size=${sizePx}px, dropped")
            }
            return null
        }
        // 刻意**不**回填 L1。
        //
        // L1 容量小于工作集时，若每次 L2 命中都回填，L1 的填充顺序会与遍历顺序
        // 完全一致：第二遍从头滚时，每命中一条就把尾部本该命中的条目挤掉，
        // L1 永远比查询慢一步，命中率恒为 0（实测 l1=0 即此因）。
        //
        // 只让首次加载（[put]）填充 L1，L1 就稳定保留「第一遍的尾部」，
        // 第二遍滚到尾部时即可命中，形成稳定的部分命中率。
        if (BuildConfigUtil.DEBUG) {
            val h = hits.incrementAndGet()
            decodeTotalUs.addAndGet(costUs)
            decodeCount.incrementAndGet()
            Log.d(
                TAG,
                "hit pkg=$packageName size=${sizePx}px " +
                        "h=$h m=${misses.get()} ratio=${ratio()}% " +
                        "used=${cache.size()}/$maxBytes evict=${evictions.get()} " +
                        "decode=${costUs}us avg=${avgDecodeUs()}us l1=${l1Hits.get()}"
            )
        }
        return decoded
    }

    fun put(key: String, value: PackageIconBitmaps) {
        val entry = PackageIconEntry(
            foreground = value.foreground?.encodeToWebp(),
            background = value.background?.encodeToWebp(),
        )
        // 编码全失败时不入 L2，避免存入空条目后每次命中都解码失败。
        if (!entry.isEmpty) cache.put(key, entry)
        decodedCache.put(key, value)
        if (BuildConfigUtil.DEBUG) {
            val raw = rawBytesOf(value)
            rawBytesTotal.addAndGet(raw.toLong())
            packedBytesTotal.addAndGet(entry.bytes.toLong())
            val count = puts.incrementAndGet()
            if (count % STATS_INTERVAL == 0L) {
                Log.d(
                    TAG,
                    "stats puts=$count entries=${cache.size()} used=${cache.size()}/$maxBytes " +
                            "raw=${rawBytesTotal.get()} packed=${packedBytesTotal.get()} " +
                            "pack=${packedRatio()}% evict=${evictions.get()} " +
                            "l1=${l1Hits.get()} l1ratio=${l1Ratio()}% " +
                            "l1used=${decodedCache.size()}/$l1MaxBytes"
                )
            }
        }
    }

    private fun ratio(): Long {
        val h = hits.get()
        val m = misses.get()
        val total = h + m
        return if (total > 0) h * 100 / total else 0
    }

    private fun avgDecodeUs(): Long {
        val n = decodeCount.get()
        return if (n > 0) decodeTotalUs.get() / n else 0
    }

    private fun packedRatio(): Long {
        val raw = rawBytesTotal.get()
        return if (raw > 0) packedBytesTotal.get() * 100 / raw else 0
    }

    /** L1 命中占全部取用次数的比例。L1 命中意味着零解码、零 IO。 */
    private fun l1Ratio(): Long {
        val l1 = l1Hits.get()
        val total = l1 + hits.get() + misses.get()
        return if (total > 0) l1 * 100 / total else 0
    }

    private fun rawBytesOf(value: PackageIconBitmaps): Int =
        (value.foreground?.let { it.width * it.height * 4 } ?: 0) +
                (value.background?.let { it.width * it.height * 4 } ?: 0)
}

/**
 * 位图编码为 WebP 字节；失败时返回 null（该层退化为不缓存）。
 *
 * [Bitmap.CompressFormat.WEBP_LOSSLESS] 需要 API 30，minSdk 28 上回退到
 * 已废弃的 [Bitmap.CompressFormat.WEBP]（quality=100 即无损）。
 */
private fun ImageBitmap.encodeToWebp(): ByteArray? = runCatching {
    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSLESS
    } else {
        @Suppress("DEPRECATION")
        Bitmap.CompressFormat.WEBP
    }
    ByteArrayOutputStream().use { out ->
        asAndroidBitmap().compress(format, 100, out)
        out.toByteArray()
    }
}.getOrNull()

/** WebP 字节解码为位图；失败时返回 null。 */
private fun ByteArray.decodeToImageBitmap(): ImageBitmap? = runCatching {
    BitmapFactory.decodeByteArray(this, 0, size)?.asImageBitmap()
}.getOrNull()

private fun Drawable.toIconBitmap(sizePx: Int): ImageBitmap =
    toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()

/**
 * 在 IO 线程完成取图与光栅化，返回可直接绘制的位图。
 */
private suspend fun loadPackageIconBitmaps(
    context: Context,
    packageName: String,
    accountId: String?,
    sizePx: Int,
    inCircleShape: Boolean,
): PackageIconBitmaps {
    val iconDrawable = runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
    if (iconDrawable != null) {
        return if (inCircleShape) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && iconDrawable is AdaptiveIconDrawable) {
                val layers = LayerDrawable(arrayOf(iconDrawable.background, iconDrawable.foreground))
                PackageIconBitmaps(background = layers.toIconBitmap(sizePx))
            } else {
                PackageIconBitmaps(foreground = iconDrawable.toIconBitmap(sizePx))
            }
        } else {
            PackageIconBitmaps(foreground = iconDrawable.toIconBitmap(sizePx))
        }
    }

    val adaptivePath = if (accountId != null)
        PathUtil.getPackageIconPath(context, packageName, true, accountId)
    else
        PathUtil.getPackageIconPath(context, packageName, true)
    val adaptive = BaseUtil.readIcon(context, adaptivePath)
    if (adaptive != null) return PackageIconBitmaps(background = adaptive.toIconBitmap(sizePx))

    val normalPath = if (accountId != null)
        PathUtil.getPackageIconPath(context, packageName, false, accountId)
    else
        PathUtil.getPackageIconPath(context, packageName, false)
    val normal = BaseUtil.readIcon(context, normalPath)
    if (normal != null) return PackageIconBitmaps(foreground = normal.toIconBitmap(sizePx))

    val placeholder = AppCompatResources.getDrawable(context, android.R.drawable.sym_def_app_icon)
    return PackageIconBitmaps(foreground = placeholder?.toIconBitmap(sizePx))
}

@ExperimentalFoundationApi
@Composable
fun MediaIconImage(enabled: Boolean = true, name: String, textStyle: TextStyle = MaterialTheme.typography.labelMedium, size: Dp = SizeTokens.Level32) {
    Surface(modifier = Modifier.size(size), indication = null, shape = CircleShape, color = ThemedColorSchemeKeyTokens.PrimaryContainer.value.withState(), enabled = enabled) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = name,
                style = textStyle,
                color = ThemedColorSchemeKeyTokens.OnPrimaryContainer.value.withState(enabled),
            )
        }
    }
}

@Composable
fun AppIcon(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(SizeTokens.Level128)
            .clip(CircleShape)
            .background(colorResource(id = R.color.ic_launcher_background)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            modifier = Modifier.size(SizeTokens.Level100),
            imageVector = ImageVector.vectorResource(id = R.drawable.ic_launcher_foreground_tonal),
            contentDescription = null
        )
    }
}
