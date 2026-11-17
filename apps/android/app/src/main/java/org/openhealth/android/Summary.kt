package org.openhealth.android

import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A vital with its per-night history (oldest first) and personal baseline. */
internal data class Trend(
    val series: List<Double>,
    val latest: Double?,
    val baseline: Double?,
    val deltaPct: Double?,
)

internal data class Night(
    val date: String?,
    val inBedHours: Double?,
    val hrv: Double?,
    val restingHr: Double?,
    val efficiency: Double?,
    val stages: SleepStages?,
    val heartRate: List<Double>,
)

/** Shares of the night in each stage, in percent. */
internal data class SleepStages(val deep: Double, val light: Double, val rem: Double, val awake: Double)

internal data class DayActivity(val date: String, val steps: Double?, val activeKcal: Double?)

/** One local hour of heart rate: 5th-95th percentile band and median, in bpm. */
internal data class HourBand(val unix: Long, val hour: Int, val low: Double, val high: Double, val median: Double)

/** Everything the dashboard draws, parsed from the shared-core summary JSON. */
internal data class Dashboard(
    val error: String? = null,
    val lastSync: String? = null,
    val syncAgeHours: Double? = null,
    val batteryPct: Int? = null,
    val latestHr: Double? = null,
    val latestHrAt: String? = null,
    val hrv: Trend? = null,
    val restingHr: Trend? = null,
    val lastNight: Night? = null,
    val activity: List<DayActivity> = emptyList(),
    val totalEvents: Long? = null,
    val observedDays: Int? = null,
    val digest: String? = null,
    val warnings: List<String> = emptyList(),
    val hours: List<HourBand> = emptyList(),
)

/** The phone's current UTC offset in hours, keeping fractional zones such as +5:30. */
internal fun currentUtcOffsetHours(): Double =
    TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000.0

/**
 * Converts the shared-core summary JSON into dashboard data. Missing values stay null
 * rather than becoming zero, so an unknown never looks like a measurement.
 */
internal fun parseSummary(json: String): Dashboard {
    val root = try {
        JSONObject(json)
    } catch (e: JSONException) {
        return Dashboard(error = "The summary could not be read")
    }
    root.text("error")?.let { return Dashboard(error = it) }
    val device = root.optJSONObject("device")
    val vitals = root.optJSONObject("vitals")
    val hr = vitals?.optJSONObject("hr")
    return Dashboard(
        lastSync = device?.let { d -> listOfNotNull(d.text("synced"), d.text("synced_hm")).joinToString(" ") }
            ?.takeIf { it.isNotEmpty() },
        syncAgeHours = device?.number("fresh_hours"),
        batteryPct = device?.number("battery_pct")?.toInt(),
        latestHr = hr?.number("latest"),
        latestHrAt = hr?.let { listOfNotNull(it.text("date"), it.text("hm")).joinToString(" ") }
            ?.takeIf { it.isNotEmpty() },
        hrv = vitals?.optJSONObject("hrv")?.toTrend(),
        restingHr = vitals?.optJSONObject("rhr")?.toTrend(),
        lastNight = latestNight(root),
        activity = recentActivity(root, days = 7),
        totalEvents = device?.number("total_events")?.toLong(),
        observedDays = device?.number("observed_days")?.toInt(),
        digest = root.text("digest"),
        warnings = root.optJSONObject("clock")?.optJSONArray("warnings").strings(),
    )
}

/** Parses `hourly_hr_json_hours` output, keeping the most recent [limit] hours. */
internal fun parseHourly(json: String, limit: Int = 24): List<HourBand> {
    val root = try {
        JSONObject(json)
    } catch (e: JSONException) {
        return emptyList()
    }
    val hours = root.optJSONArray("hours") ?: return emptyList()
    return (0 until hours.length()).mapNotNull { hours.optJSONObject(it) }.mapNotNull { h ->
        val low = h.number("low") ?: return@mapNotNull null
        val high = h.number("high") ?: return@mapNotNull null
        val median = h.number("median") ?: return@mapNotNull null
        HourBand(h.optLong("unix"), h.optInt("hour"), low, high, median)
    }.sortedBy { it.unix }.takeLast(limit)
}

private fun JSONObject.toTrend(): Trend? {
    val series = optJSONArray("series").numbers()
    val latest = number("latest")
    if (latest == null && series.isEmpty()) return null
    return Trend(series, latest, number("baseline"), number("delta_pct"))
}

private fun latestNight(root: JSONObject): Night? {
    val nights = root.optJSONArray("nights") ?: return null
    val night = (0 until nights.length()).mapNotNull { nights.optJSONObject(it) }
        .maxByOrNull { it.number("end_unix") ?: Double.NEGATIVE_INFINITY } ?: return null
    val deep = night.number("deep_pct")
    val light = night.number("light_pct")
    val rem = night.number("rem_pct")
    val awake = night.number("wake_pct")
    val stages = if (deep != null && light != null && rem != null && awake != null) {
        SleepStages(deep, light, rem, awake)
    } else {
        null
    }
    return Night(
        date = night.text("wake_ymd") ?: night.text("ymd"),
        inBedHours = night.number("in_bed_h"),
        hrv = night.number("hrv_ms"),
        restingHr = night.number("rhr"),
        efficiency = night.number("efficiency"),
        stages = stages,
        heartRate = night.optJSONObject("series")?.optJSONArray("hr").numbers(),
    )
}

private fun recentActivity(root: JSONObject, days: Int): List<DayActivity> {
    val daily = root.optJSONObject("activity_daily") ?: return emptyList()
    return daily.keys().asSequence().sorted().toList().takeLast(days).mapNotNull { day ->
        daily.optJSONObject(day)?.let { DayActivity(day, it.number("steps"), it.number("active_kcal")) }
    }
}

private fun JSONArray?.numbers(): List<Double> =
    if (this == null) emptyList()
    else (0 until length()).mapNotNull { i -> if (isNull(i)) null else optDouble(i).takeIf { it.isFinite() } }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotEmpty) }

private fun JSONObject.number(key: String): Double? =
    if (isNull(key) || !has(key)) null else optDouble(key).takeIf { it.isFinite() }

private fun JSONObject.text(key: String): String? =
    if (isNull(key) || !has(key)) null else optString(key).takeIf { it.isNotEmpty() }

internal fun formatDuration(hours: Double): String {
    val minutes = Math.round(hours * 60)
    return "${minutes / 60}h ${minutes % 60}m"
}

internal fun formatAge(hours: Double): String = when {
    hours < 1 -> "${Math.round(hours * 60)} min"
    hours < 48 -> "${"%.1f".format(hours)} h"
    else -> "${Math.round(hours / 24)} days"
}
