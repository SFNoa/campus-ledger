package com.morchid.ecardledger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp

data class BarDatum(val label: String, val value: Float)
data class Slice(val label: String, val value: Float, val color: Color)

/** 柱状图（日/月支出）。用原生 Canvas 画文字，避免依赖 TextMeasurer 的版本差异。 */
@Composable
fun BarChart(
    data: List<BarDatum>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
) {
    if (data.isEmpty()) {
        EmptyChartHint(modifier)
        return
    }
    val maxValue = data.maxOf { it.value }.coerceAtLeast(0.01f)
    Canvas(modifier) {
        val labelHeight = 16.dp.toPx()
        val chartHeight = (size.height - labelHeight).coerceAtLeast(1f)
        val slot = size.width / data.size
        val barWidth = (slot * 0.62f).coerceAtLeast(2f)

        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 10.dp.toPx()
            color = android.graphics.Color.GRAY
            textAlign = android.graphics.Paint.Align.CENTER
        }

        data.forEachIndexed { index, datum ->
            val ratio = datum.value / maxValue
            val barHeight = (chartHeight * ratio).coerceAtLeast(if (datum.value > 0f) 2f else 0f)
            val left = index * slot + (slot - barWidth) / 2f
            if (barHeight > 0f) {
                drawRect(
                    color = barColor,
                    topLeft = Offset(left, chartHeight - barHeight),
                    size = Size(barWidth, barHeight),
                )
            }
            // 标签太密时隔几个画一个
            val step = ((data.size + 6) / 7).coerceAtLeast(1)
            if (index % step == 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    datum.label,
                    left + barWidth / 2f,
                    size.height - 4.dp.toPx(),
                    paint,
                )
            }
        }
    }
}

/** 分类占比环形图 */
@Composable
fun DonutChart(
    slices: List<Slice>,
    modifier: Modifier = Modifier,
) {
    val total = slices.sumOf { it.value.toDouble() }.toFloat()
    if (slices.isEmpty() || total <= 0f) {
        EmptyChartHint(modifier)
        return
    }
    Canvas(modifier) {
        val strokeWidth = 26.dp.toPx()
        val inset = strokeWidth / 2f + 2.dp.toPx()
        val diameter = minOf(size.width, size.height) - inset * 2
        val topLeft = Offset(
            (size.width - diameter) / 2f,
            (size.height - diameter) / 2f,
        )
        var startAngle = -90f
        for (slice in slices) {
            val sweep = 360f * (slice.value / total)
            drawArc(
                color = slice.color,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(width = strokeWidth),
            )
            startAngle += sweep
        }
    }
}

/** 余额趋势折线 */
@Composable
fun LineChart(
    points: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.secondary,
) {
    if (points.size < 2) {
        EmptyChartHint(modifier)
        return
    }
    val minValue = points.min()
    val maxValue = points.max()
    val range = (maxValue - minValue).coerceAtLeast(0.01f)
    Canvas(modifier) {
        val pad = 4.dp.toPx()
        val usableHeight = (size.height - pad * 2).coerceAtLeast(1f)
        val path = Path()
        points.forEachIndexed { index, value ->
            val x = size.width * index / (points.size - 1).toFloat()
            val y = pad + usableHeight * (1f - (value - minValue) / range)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun EmptyChartHint(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            "暂无数据",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
