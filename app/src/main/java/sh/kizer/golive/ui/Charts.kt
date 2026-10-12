package sh.kizer.golive.ui

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import sh.kizer.golive.Axis
import sh.kizer.golive.Bucket
import sh.kizer.golive.Smoothing
import sh.kizer.golive.Units
import java.time.ZoneId
import kotlin.math.abs

/** Runs of buckets split only at real outages (Smoothing.gapMs), so those show as breaks. */
private fun runs(buckets: List<Bucket>, bucketMs: Long) = Smoothing.runs(buckets, Smoothing.gapMs(bucketMs))

/**
 * Continues this path through [pts]: straight segments, or with [curved] a
 * monotone cubic (Fritsch–Carlson) that never overshoots the points, so a
 * curve can't invent peaks or dips. Starts with a move when [move].
 */
private fun Path.through(pts: List<Offset>, curved: Boolean, move: Boolean) {
    if (move) moveTo(pts[0].x, pts[0].y) else lineTo(pts[0].x, pts[0].y)
    val n = pts.size
    if (!curved || n < 3) {
        for (i in 1 until n) lineTo(pts[i].x, pts[i].y)
        return
    }
    val dx = FloatArray(n - 1) { pts[it + 1].x - pts[it].x }
    val slope = FloatArray(n - 1) { if (dx[it] == 0f) 0f else (pts[it + 1].y - pts[it].y) / dx[it] }
    val tangent = FloatArray(n)
    tangent[0] = slope[0]
    tangent[n - 1] = slope[n - 2]
    for (i in 1 until n - 1) {
        tangent[i] = if (slope[i - 1] * slope[i] <= 0f) {
            0f // a local peak or dip stays flat
        } else {
            val w1 = 2 * dx[i] + dx[i - 1]
            val w2 = dx[i] + 2 * dx[i - 1]
            (w1 + w2) / (w1 / slope[i - 1] + w2 / slope[i])
        }
    }
    for (i in 0 until n - 1) {
        val h = dx[i] / 3
        cubicTo(
            pts[i].x + h, pts[i].y + tangent[i] * h,
            pts[i + 1].x - h, pts[i + 1].y - tangent[i + 1] * h,
            pts[i + 1].x, pts[i + 1].y,
        )
    }
}

/** A tile's 24 h line: no axes, the latest point marked. */
@Composable
fun Sparkline(buckets: List<Bucket>, bucketMs: Long, from: Long, to: Long, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceContainer
    Canvas(modifier) {
        if (buckets.isEmpty()) return@Canvas
        val lo = buckets.minOf { it.avg }
        val hi = buckets.maxOf { it.avg }
        val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
        val pad = 3.dp.toPx()
        fun x(t: Long) = ((t + bucketMs / 2 - from).toFloat() / (to - from)) * size.width
        fun y(v: Double) = pad + (1 - ((v - lo) / span).toFloat()) * (size.height - 2 * pad)
        for (run in runs(buckets, bucketMs)) {
            if (run.size == 1) {
                drawCircle(color, 1.5.dp.toPx(), Offset(x(run[0].start), y(run[0].avg)))
                continue
            }
            val path = Path().apply { through(run.map { Offset(x(it.start), y(it.avg)) }, curved = true, move = true) }
            drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val last = buckets.last()
        val end = Offset(x(last.start), y(last.avg))
        drawCircle(surface, 5.dp.toPx(), end)
        drawCircle(color, 3.5.dp.toPx(), end)
    }
}

/**
 * The trend chart: smoothed bucket averages as a line over a smoothed min–max
 * band, value ticks on the left, time ticks below. Touch and drag to select a
 * bucket (an index into both trend.buckets and trend.smoothed).
 */
@Composable
fun TrendChart(
    trend: Trend,
    units: Units,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val use24h = DateFormat.is24HourFormat(LocalContext.current)
    val w = trend.window
    val buckets = trend.smoothed
    val convert = { v: Double -> trend.metric.display(v, units) }
    val timeTicks = remember(trend, use24h) { Axis.timeTicks(trend.range, w.from, w.to, ZoneId.systemDefault(), use24h) }
    val (lo, hi) = remember(trend, units) {
        if (buckets.isEmpty()) {
            0.0 to 1.0
        } else {
            val min = convert(buckets.minOf { it.min })
            val max = convert(buckets.maxOf { it.max })
            val pad = ((max - min) * 0.08).takeIf { it > 1e-9 } ?: 1.0
            (min - pad) to (max + pad)
        }
    }
    val valueTicks = remember(lo, hi) { Axis.valueTicks(lo, hi) }

    Canvas(
        modifier.pointerInput(trend) {
            val left = LEFT_GUTTER.toPx()
            val right = RIGHT_GUTTER.toPx()
            fun pick(px: Float) {
                if (buckets.isEmpty()) return
                val t = w.from + ((px - left) / (size.width - left - right)) * (w.to - w.from)
                onSelect(buckets.indices.minBy { abs(buckets[it].start + w.bucketMs / 2 - t) })
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                pick(down.position.x)
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull() ?: break
                    if (!change.pressed) break
                    change.consume()
                    pick(change.position.x)
                }
                onSelect(null)
            }
        },
    ) {
        val left = LEFT_GUTTER.toPx()
        val right = size.width - RIGHT_GUTTER.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - BOTTOM_GUTTER.toPx()
        fun x(t: Long) = left + ((t - w.from).toFloat() / (w.to - w.from)) * (right - left)
        fun xb(b: Bucket) = x(b.start + w.bucketMs / 2)
        fun y(v: Double) = top + (1 - ((convert(v) - lo) / (hi - lo)).toFloat()) * (bottom - top)
        fun yd(v: Double) = top + (1 - ((v - lo) / (hi - lo)).toFloat()) * (bottom - top)

        // Value grid and labels.
        for (tick in valueTicks) {
            val ty = yd(tick.value)
            drawLine(colors.outlineVariant, Offset(left, ty), Offset(right, ty), 1.dp.toPx())
            label(measurer, tick.label, labelStyle, Offset(left - 6.dp.toPx(), ty), alignRight = true)
        }
        // Time labels.
        for (tick in timeTicks) {
            val tx = x(tick.time)
            drawLine(colors.outlineVariant, Offset(tx, bottom), Offset(tx, bottom + 3.dp.toPx()), 1.dp.toPx())
            label(measurer, tick.label, labelStyle, Offset(tx, bottom + 4.dp.toPx()), center = true, maxRight = size.width)
        }
        drawLine(colors.outline, Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())

        for (run in runs(buckets, w.bucketMs)) {
            if (run.size == 1) {
                drawCircle(colors.primary, 2.5.dp.toPx(), Offset(xb(run[0]), y(run[0].avg)))
                continue
            }
            val band = Path().apply {
                through(run.map { Offset(xb(it), y(it.max)) }, curved = true, move = true)
                through(run.asReversed().map { Offset(xb(it), y(it.min)) }, curved = true, move = false)
                close()
            }
            drawPath(band, colors.primary.copy(alpha = 0.16f))
            val line = Path().apply { through(run.map { Offset(xb(it), y(it.avg)) }, curved = true, move = true) }
            drawPath(line, colors.primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        selected?.let { buckets.getOrNull(it) }?.let { b ->
            val sx = xb(b)
            drawLine(colors.onSurfaceVariant, Offset(sx, top), Offset(sx, bottom), 1.dp.toPx())
            drawCircle(colors.surfaceContainer, 6.dp.toPx(), Offset(sx, y(b.avg)))
            drawCircle(colors.primary, 4.5.dp.toPx(), Offset(sx, y(b.avg)))
        }
    }
}

private val LEFT_GUTTER = 40.dp
private val RIGHT_GUTTER = 8.dp
private val BOTTOM_GUTTER = 22.dp

private fun DrawScope.label(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    at: Offset,
    alignRight: Boolean = false,
    center: Boolean = false,
    maxRight: Float = Float.MAX_VALUE,
) {
    val layout = measurer.measure(text, style)
    val w = layout.size.width.toFloat()
    val h = layout.size.height.toFloat()
    val x = when {
        alignRight -> at.x - w
        center -> (at.x - w / 2).coerceIn(0f, maxRight - w)
        else -> at.x
    }
    val y = if (center) at.y else at.y - h / 2
    drawText(layout, topLeft = Offset(x, y), color = style.color.takeIf { it != Color.Unspecified } ?: Color.Gray)
}
