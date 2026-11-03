package com.xayah.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * 语义状态色。
 *
 * M3 色板只有 error 角色、没有 success，所以「正常」这一档必须自己定义。
 * 这里不写死 hex，而是取 [ThemedColorScheme] 里已按明暗切好的绿（浅色用 tone 90、深色用 tone 30），
 * 再把色相朝当前 primary 靠拢——做法等同 material-color-utilities 的 `Blend.harmonize`（默认 15°）。
 * 结果仍然是绿色，但会跟着壁纸取色一起偏向主题色相，不会在紫/蓝主题下突兀地跳出来。
 *
 * 只动色相、不动亮度与饱和度，因此深色模式不会出现浅绿底配浅绿字的对比度事故。
 */
@Immutable
data class StatusColors(
    val okContainer: Color,
    val onOkContainer: Color,
)

/** 取当前主题下的语义状态色。错误档直接用 M3 的 [androidx.compose.material3.ColorScheme.errorContainer]。 */
@Composable
@ReadOnlyComposable
fun statusColors(): StatusColors {
    val green = MaterialTheme.themedColorScheme.green
    val anchor = MaterialTheme.colorScheme.primary
    return StatusColors(
        okContainer = green.primaryContainer.harmonizedHue(anchor),
        onOkContainer = green.onPrimaryContainer.harmonizedHue(anchor),
    )
}

/** 与 M3 的 `Blend.harmonize` 默认值一致。 */
private const val HARMONIZE_DEGREES = 15f
private const val HALF_TURN = 180f
private const val FULL_TURN = 360f

/**
 * 把当前颜色的色相朝 [anchor] 移动，最多 [HARMONIZE_DEGREES] 度。
 *
 * 只修改色相，饱和度与亮度原样保留：语义（绿 = 正常）与明暗对比都不会被破坏。
 */
internal fun Color.harmonizedHue(anchor: Color): Color {
    val hsl = toHsl()
    val anchorHue = anchor.toHsl().hue
    // 灰阶没有有意义的色相，动它只会引入脏色。
    if (hsl.saturation == 0f || anchorHue == 0f) return this
    // 折到 -180..180：避免 350° → 10° 这种跨零点的情况被算成长路径的反向位移。
    val diff = (anchorHue - hsl.hue + FULL_TURN + HALF_TURN) % FULL_TURN - HALF_TURN
    val shifted = (hsl.hue + diff.coerceIn(-HARMONIZE_DEGREES, HARMONIZE_DEGREES) + FULL_TURN) % FULL_TURN
    return hslToColor(shifted, hsl.saturation, hsl.lightness).copy(alpha = alpha)
}

private data class Hsl(val hue: Float, val saturation: Float, val lightness: Float)

private fun Color.toHsl(): Hsl {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val lightness = (max + min) / 2f
    if (max == min) return Hsl(0f, 0f, lightness)

    val delta = max - min
    val saturation = if (lightness > 0.5f) delta / (2f - max - min) else delta / (max + min)
    val hue = if (max == red) {
        (green - blue) / delta + if (green < blue) 6f else 0f
    } else if (max == green) {
        (blue - red) / delta + 2f
    } else {
        (red - green) / delta + 4f
    } * 60f
    return Hsl(hue, saturation, lightness)
}

private fun hslToColor(hue: Float, saturation: Float, lightness: Float): Color {
    if (saturation == 0f) return Color(lightness, lightness, lightness)

    val q = if (lightness < 0.5f) lightness * (1f + saturation) else lightness + saturation - lightness * saturation
    val p = 2f * lightness - q

    fun channel(raw: Float): Float {
        val t = when {
            raw < 0f -> raw + 1f
            raw > 1f -> raw - 1f
            else -> raw
        }
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    val h = hue / FULL_TURN
    return Color(channel(h + 1f / 3f), channel(h), channel(h - 1f / 3f))
}
