package org.openhealth.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** A rounded dark card with an optional title (PRD VIS-03). */
@Composable
internal fun Panel(
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.Surface),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary) }
                }
            }
            content()
        }
    }
}

/** Ring status: battery gauge, last sync age, and the primary sync action. */
@Composable
internal fun StatusHeader(
    serial: String,
    dashboard: Dashboard?,
    busy: Boolean,
    onSync: () -> Unit,
) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BatteryRing(dashboard?.batteryPct, MetricColors.Battery)
            Column(Modifier.weight(1f)) {
                Text("Ring ${serial.takeLast(6)}", style = MaterialTheme.typography.titleMedium)
                val age = dashboard?.syncAgeHours
                Text(
                    when {
                        age != null -> "Synced ${formatAge(age)} ago"
                        dashboard?.lastSync != null -> "Synced ${dashboard.lastSync}"
                        else -> "Not synced yet"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                )
            }
            Button(enabled = !busy, onClick = onSync) { Text("Sync") }
        }
    }
}

/** A metric with its value, unit, change versus baseline, and recent history. */
@Composable
internal fun MetricTile(
    label: String,
    value: Double?,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
    trend: Trend? = null,
    caption: String? = null,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.Surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = color)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(value?.let { "${it.roundToInt()}" } ?: "--", style = MetricValueStyle)
                Text(unit, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                    modifier = Modifier.padding(bottom = 5.dp))
            }
            val change = trend?.let { baselineChange(it) }
            Text(
                change ?: caption ?: " ",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
            )
            Sparkline(trend?.series.orEmpty(), color, Modifier.fillMaxWidth().height(36.dp))
        }
    }
}

/** "+7% vs baseline" from the core's delta, or null without a baseline. Neutral wording. */
internal fun baselineChange(trend: Trend): String? {
    val delta = trend.deltaPct ?: return null
    val rounded = delta.roundToInt()
    return when {
        rounded == 0 -> "At your baseline"
        else -> "${if (rounded > 0) "+" else "-"}${abs(rounded)}% vs baseline"
    }
}

/** The data part of the paired screen; actions live in the caller. */
@Composable
internal fun DashboardContent(dashboard: Dashboard) {
    dashboard.error?.let {
        Panel("Could not build the dashboard") { Text(it, color = Palette.TextSecondary) }
        return
    }
    val today = dashboard.activity.lastOrNull()
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricTile(
            "Heart rate", dashboard.latestHr, "bpm", MetricColors.HeartRate, Modifier.weight(1f).fillMaxHeight(),
            caption = dashboard.latestHrAt?.let { "at $it" } ?: "No reading yet",
        )
        MetricTile(
            "HRV", dashboard.hrv?.latest, "ms", MetricColors.Hrv, Modifier.weight(1f).fillMaxHeight(),
            trend = dashboard.hrv, caption = "Builds after a few nights",
        )
    }
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricTile(
            "Resting HR", dashboard.restingHr?.latest, "bpm", MetricColors.RestingHr, Modifier.weight(1f).fillMaxHeight(),
            trend = dashboard.restingHr, caption = "Measured during sleep",
        )
        MetricTile(
            "Steps", today?.steps, "", MetricColors.Activity, Modifier.weight(1f).fillMaxHeight(),
            caption = today?.let { t -> t.activeKcal?.let { "${it.roundToInt()} active kcal" } ?: weekdayLabel(t.date) }
                ?: "No activity yet",
        )
    }
    Panel("Heart rate", "Last 24 hours with data: typical range and median") {
        if (dashboard.hours.isEmpty()) {
            EmptyNote("Wear the ring and sync again to see your day.")
        } else {
            HeartRateDayChart(dashboard.hours, MetricColors.HeartRate, Modifier.fillMaxWidth().height(170.dp))
            if (dashboard.hours.size < 3) EmptyNote("More hours fill in as you wear the ring.")
        }
    }
    dashboard.lastNight.let { night ->
        Panel("Last night", night?.date) {
            if (night == null) {
                EmptyNote("Sleep with the ring on, then sync in the morning.")
            } else {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Stat("In bed", night.inBedHours?.let(::formatDuration))
                    Stat("HRV", night.hrv?.let { "${it.roundToInt()} ms" })
                    Stat("Resting HR", night.restingHr?.let { "${it.roundToInt()} bpm" })
                    Stat("Efficiency", night.efficiency?.let { "${it.roundToInt()}%" })
                }
                night.stages?.let { SleepStageBar(it) }
                if (night.heartRate.size >= 2) {
                    Text("Overnight heart rate", style = MaterialTheme.typography.labelMedium)
                    Sparkline(night.heartRate, MetricColors.HeartRate, Modifier.fillMaxWidth().height(56.dp))
                }
            }
        }
    }
    Panel("Activity", "Steps over the last ${dashboard.activity.size.coerceAtLeast(1)} days") {
        if (dashboard.activity.isEmpty()) {
            EmptyNote("Steps appear after the ring records movement.")
        } else {
            ActivityBars(dashboard.activity, MetricColors.Activity, Modifier.fillMaxWidth().height(130.dp))
        }
    }
    if (dashboard.warnings.isNotEmpty() || dashboard.totalEvents != null) {
        Panel("Data quality") {
            dashboard.totalEvents?.let { events ->
                val days = dashboard.observedDays?.let { " over $it days" } ?: ""
                Text("$events ring events$days", color = Palette.TextSecondary)
            }
            dashboard.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary) }
        }
    }
    Text(
        "Estimates computed on this phone from ring data. Not Oura scores and not medical advice.",
        style = MaterialTheme.typography.bodySmall,
        color = Palette.TextSecondary,
    )
}

@Composable
private fun Stat(label: String, value: String?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
        Text(value ?: "--", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun EmptyNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
}
