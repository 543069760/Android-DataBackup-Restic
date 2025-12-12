package com.xayah.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

@Composable
fun ProgressButton(
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    currentIndex: Int = 0,
    totalCount: Int = 0,
    speed: String = "0 B/s",
    progressSize: String = "0 B/0 B",
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val backgroundColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainer
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    Button(
        modifier = modifier,
        enabled = enabled,
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = trackColor,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            // 背景进度条（斜向条纹效果）
            if (progress > 0f) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .matchParentSize()
                ) {
                    val strokeWidth = size.height
                    drawLinearIndicatorTrack(trackColor, strokeWidth, StrokeCap.Round)

                    // 绘制带条纹的进度条
                    drawStripedProgress(
                        startFraction = 0f,
                        endFraction = progress,
                        color = backgroundColor,
                        strokeWidth = strokeWidth,
                        strokeCap = StrokeCap.Round,
                        density = density,
                        layoutDirection = layoutDirection
                    )
                }
            }

            // 按钮文字
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (progress > 0f) {
                        "[$currentIndex/$totalCount][$speed][$progressSize]"
                    } else {
                        "恢复快照备份"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (progress > 0f) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }
}

private fun DrawScope.drawLinearIndicatorTrack(
    color: Color,
    strokeWidth: Float,
    strokeCap: StrokeCap
) {
    val width = size.width
    val height = size.height
    val yOffset = height / 2

    val isLtr = layoutDirection == LayoutDirection.Ltr
    val barStart = if (isLtr) 0f else width
    val barEnd = if (isLtr) width else 0f

    drawLine(
        color = color,
        start = Offset(barStart, yOffset),
        end = Offset(barEnd, yOffset),
        strokeWidth = strokeWidth,
        cap = strokeCap
    )
}

private fun DrawScope.drawStripedProgress(
    startFraction: Float,
    endFraction: Float,
    color: Color,
    strokeWidth: Float,
    strokeCap: StrokeCap,
    density: androidx.compose.ui.unit.Density,
    layoutDirection: LayoutDirection
) {
    val width = size.width
    val height = size.height

    val isLtr = layoutDirection == LayoutDirection.Ltr
    val barStart = (if (isLtr) startFraction else 1f - endFraction) * width
    val barEnd = (if (isLtr) endFraction else 1f - startFraction) * width

    // 绘制条纹背景
    drawRect(
        color = color,
        topLeft = Offset(barStart, 0f),
        size = Size(barEnd - barStart, height)
    )

    // 绘制斜向条纹
    val stripeWidth = with(density) { 8.dp.toPx() }
    val stripeSpacing = with(density) { 8.dp.toPx() }

    for (i in -height.toInt()..width.toInt() step (stripeWidth + stripeSpacing).toInt()) {
        drawLine(
            color = color.copy(alpha = 0.3f),
            start = Offset(i.toFloat(), 0f),
            end = Offset(i.toFloat() + height, height),
            strokeWidth = stripeWidth,
            cap = strokeCap
        )
    }
}