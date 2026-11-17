package org.openhealth.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

/** A padded value range for a chart axis; never zero-width, so flat data still draws. */
internal fun chartRange(values: List<Double>, minSpan: Double = 1.0, pad: Double = 0.12): ClosedFloatingPointRange<Double> {
    val lo = values.minOrNull() ?: return 0.0..1.0
    val hi = values.maxOrNull() ?: return 0.0..1.0
    val mid = (lo + hi) / 2
    val span = maxOf(hi - lo, minSpan)
    val half = span / 2 * (1 + pad)
    return (mid - half)..(mid + half)
}

/** Maps [value] to a y pixel within [height], with the range's top at y = 0. */
internal fun yFor(value: Double, range: ClosedFloatingPointRange<Double>, height: Float): Float {
    val span = range.endInclusive - range.start
    val fraction = if (span <= 0) 0.5 else (value - range.start) / span
    return (height * (1 - fraction)).toFloat()
}

/** Three-letter weekday for an ISO date such as 2026-11-17, or the raw text if unparseable. */
internal fun weekdayLabel(isoDate: String, locale: Locale = Locale.getDefault()): String =
    try {
        LocalDate.parse(isoDate).dayOfWeek.getDisplayName(DateTextStyle.SHORT, locale)
    } catch (e: Exception) {
        isoDate.takeLast(5)
    }

private val axisStyle = TextStyle(color = Palette.TextSecondary, fontSize = 10.sp)

/** Line with a soft gradient fill; draws nothing for fewer than two points. */
@Composable
internal fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    if (values.size < 2) return
    Canvas(modifier) {
        val range = chartRange(values)
        val step = size.width / (values.size - 1)
        val line = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = yFor(v, range, size.height)
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = values.last()
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(size.width, yFor(last, range, size.height)))
    }
}

/** Heart rate per hour: 5th-95th percentile band with the median as a connected line. */
@Composable
internal fun HeartRateDayChart(hours: List<HourBand>, color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val low = hours.minOfOrNull { it.low }
    val high = hours.maxOfOrNull { it.high }
    val description = if (low != null && high != null) {
        "Heart rate over the last ${hours.size} hours with data, ranging from ${low.toInt()} to ${high.toInt()} bpm"
    } else {
        "No heart rate data yet"
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        if (hours.isEmpty()) return@Canvas
        val labelWidth = 28.dp.toPx()
        val labelHeight = 16.dp.toPx()
        val plotWidth = size.width - labelWidth
        val plotHeight = size.height - labelHeight
        val range = chartRange(hours.flatMap { listOf(it.low, it.high) }, minSpan = 20.0, pad = 0.05)
        drawGrid(range, plotWidth, plotHeight, measurer)
        val slot = plotWidth / hours.size
        val barWidth = (slot * 0.55f).coerceAtMost(10.dp.toPx())
        val medians = Path()
        hours.forEachIndexed { i, h ->
            val cx = slot * i + slot / 2
            val top = yFor(h.high, range, plotHeight)
            val bottom = yFor(h.low, range, plotHeight)
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.18f)), top, bottom),
                topLeft = Offset(cx - barWidth / 2, top),
                size = Size(barWidth, (bottom - top).coerceAtLeast(2f)),
                cornerRadius = CornerRadius(barWidth / 2),
            )
            val my = yFor(h.median, range, plotHeight)
            if (i == 0) medians.moveTo(cx, my) else medians.lineTo(cx, my)
        }
        drawPath(medians, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        listOf(0, hours.size / 2, hours.size - 1).distinct().forEach { i ->
            val label = "%02d:00".format(hours[i].hour)
            val layout = measurer.measure(label, axisStyle)
            val x = (slot * i + slot / 2 - layout.size.width / 2).coerceIn(0f, plotWidth - layout.size.width)
            drawText(layout, topLeft = Offset(x, plotHeight + 2.dp.toPx()))
        }
    }
}

private fun DrawScope.drawGrid(
    range: ClosedFloatingPointRange<Double>,
    plotWidth: Float,
    plotHeight: Float,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val ticks = listOf(range.start, (range.start + range.endInclusive) / 2, range.endInclusive)
    ticks.forEach { value ->
        val y = yFor(value, range, plotHeight)
        drawLine(Palette.Outline, Offset(0f, y), Offset(plotWidth, y), strokeWidth = 1.dp.toPx())
        val layout = measurer.measure("${Math.round(value)}", axisStyle)
        drawText(
            layout,
            topLeft = Offset(plotWidth + 4.dp.toPx(), (y - layout.size.height / 2).coerceIn(0f, plotHeight - layout.size.height)),
        )
    }
}

/** Stacked horizontal bar of sleep stages with a labelled legend (not color-only). */
@Composable
internal fun SleepStageBar(stages: SleepStages, modifier: Modifier = Modifier) {
    val parts = listOf(
        Triple("Deep", stages.deep, MetricColors.SleepDeep),
        Triple("Light", stages.light, MetricColors.SleepLight),
        Triple("REM", stages.rem, MetricColors.SleepRem),
        Triple("Awake", stages.awake, MetricColors.SleepAwake),
    )
    val total = parts.sumOf { it.second }.takeIf { it > 0 } ?: return
    val description = parts.joinToString(", ") { "${it.first} ${Math.round(it.second)} percent" }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.fillMaxWidth().height(14.dp).semantics { contentDescription = "Sleep stages: $description" }) {
            val gap = 2.dp.toPx()
            var x = 0f
            parts.forEach { (_, value, color) ->
                val w = (size.width * (value / total)).toFloat()
                if (w > gap) {
                    drawRoundRect(color, Offset(x, 0f), Size(w - gap, size.height), CornerRadius(size.height / 2))
                }
                x += w
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            parts.forEach { (label, value, color) -> LegendItem(label, "${Math.round(value)}%", color) }
        }
    }
}

@Composable
internal fun LegendItem(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Daily step bars, most recent day highlighted; days without data are left empty. */
@Composable
internal fun ActivityBars(days: List<DayActivity>, color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val description = days.joinToString(", ") { d ->
        "${weekdayLabel(d.date)} ${d.steps?.let { "${it.toLong()} steps" } ?: "no data"}"
    }
    Canvas(modifier.semantics { contentDescription = "Steps per day: $description" }) {
        if (days.isEmpty()) return@Canvas
        val labelHeight = 16.dp.toPx()
        val plotHeight = size.height - labelHeight
        val max = days.mapNotNull { it.steps }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val slot = size.width / days.size
        val barWidth = (slot * 0.5f).coerceAtMost(22.dp.toPx())
        days.forEachIndexed { i, d ->
            val cx = slot * i + slot / 2
            val isLatest = i == days.lastIndex
            d.steps?.let { steps ->
                val h = (plotHeight * (steps / max)).toFloat().coerceAtLeast(3.dp.toPx())
                drawRoundRect(
                    color.copy(alpha = if (isLatest) 1f else 0.45f),
                    Offset(cx - barWidth / 2, plotHeight - h),
                    Size(barWidth, h),
                    CornerRadius(6.dp.toPx()),
                )
            }
            val layout = measurer.measure(weekdayLabel(d.date), axisStyle)
            drawText(layout, topLeft = Offset(cx - layout.size.width / 2, plotHeight + 2.dp.toPx()))
        }
    }
}

/** Circular battery gauge with the percentage in the center. */
@Composable
internal fun BatteryRing(percent: Int?, color: Color, diameter: Dp = 44.dp) {
    Box(
        Modifier.size(diameter).semantics {
            contentDescription = percent?.let { "Battery $it percent" } ?: "Battery unknown"
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(diameter)) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Palette.Outline, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (percent != null) {
                drawArc(
                    color, -90f, 360f * percent.coerceIn(0, 100) / 100f, false,
                    Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Text(percent?.let { "$it" } ?: "-", style = MaterialTheme.typography.labelMedium, color = Palette.TextPrimary)
    }
}
