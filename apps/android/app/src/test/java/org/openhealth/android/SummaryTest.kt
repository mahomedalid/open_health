package org.openhealth.android

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryTest {
    private val sample = """
        {"device":{"synced":"Mon 17 Nov","synced_hm":"10:15","fresh_hours":2.5,"battery_pct":81,
                   "total_events":1200,"observed_days":3},
         "vitals":{"hr":{"latest":62,"date":"Mon 17 Nov","hm":"09:58"},
                   "hrv":{"series":[50,48,null,53],"latest":49,"baseline":52.1,"delta_pct":-6.0},
                   "rhr":{"series":[],"latest":null,"baseline":null}},
         "nights":[{"wake_ymd":"2026-11-16","in_bed_h":7.0,"end_unix":100},
                   {"wake_ymd":"2026-11-17","in_bed_h":7.5,"hrv_ms":51,"rhr":55,"efficiency":91,
                    "deep_pct":18,"light_pct":52,"rem_pct":22,"wake_pct":8,"end_unix":200,
                    "series":{"hr":[58,55,54]}}],
         "activity_daily":{"2026-11-08":{"steps":1},"2026-11-09":{"steps":2},"2026-11-10":{"steps":3},
                           "2026-11-11":{"steps":4},"2026-11-12":{"steps":5},"2026-11-13":{"steps":6},
                           "2026-11-16":{"steps":8000},"2026-11-17":{"steps":3200,"active_kcal":140}},
         "digest":"HRV -6%",
         "clock":{"warnings":["About 3 hours of history has no time anchor yet."]}}
    """.trimIndent()

    @Test
    fun parsesDashboardValuesAndKeepsUnknownsNull() {
        val d = parseSummary(sample)

        assertEquals("Mon 17 Nov 10:15", d.lastSync)
        assertEquals(81, d.batteryPct)
        assertEquals(62.0, d.latestHr!!, 0.0)
        assertEquals("Mon 17 Nov 09:58", d.latestHrAt)
        assertEquals(listOf(50.0, 48.0, 53.0), d.hrv!!.series)
        assertEquals(-6.0, d.hrv!!.deltaPct!!, 0.0)
        assertNull("no history and no latest value means no trend", d.restingHr)
        assertEquals(1200L, d.totalEvents)
        assertEquals(listOf("About 3 hours of history has no time anchor yet."), d.warnings)
    }

    @Test
    fun picksTheLatestNightWithStagesAndOvernightCurve() {
        val night = parseSummary(sample).lastNight!!

        assertEquals("2026-11-17", night.date)
        assertEquals(SleepStages(18.0, 52.0, 22.0, 8.0), night.stages)
        assertEquals(listOf(58.0, 55.0, 54.0), night.heartRate)
        assertEquals(91.0, night.efficiency!!, 0.0)
    }

    @Test
    fun keepsTheLastSevenDaysOfActivityInOrder() {
        val days = parseSummary(sample).activity

        assertEquals(7, days.size)
        assertEquals("2026-11-09", days.first().date)
        assertEquals(DayActivity("2026-11-17", 3200.0, 140.0), days.last())
    }

    @Test
    fun coreErrorsAndBadJsonBecomeErrors() {
        assertEquals("no such table", parseSummary("""{"error":"no such table"}""").error)
        assertTrue(parseSummary("not json").error!!.isNotEmpty())
    }

    @Test
    fun hourlyBandsAreOrderedTrimmedAndSkipIncompleteRows() {
        val json = """{"hours":[
            {"unix":300,"hour":3,"low":50,"high":70,"median":58},
            {"unix":100,"hour":1,"low":48,"high":65,"median":55},
            {"unix":200,"hour":2,"low":null,"high":66,"median":56}]}"""

        val hours = parseHourly(json, limit = 5)

        assertEquals(listOf(1, 3), hours.map { it.hour })
        assertEquals(listOf(3), parseHourly(json, limit = 1).map { it.hour })
        assertTrue(parseHourly("""{"error":"x"}""").isEmpty())
    }

    @Test
    fun baselineChangeIsNeutralAndRounded() {
        assertEquals("-6% vs baseline", baselineChange(Trend(emptyList(), 49.0, 52.0, -6.4)))
        assertEquals("+12% vs baseline", baselineChange(Trend(emptyList(), 60.0, 53.0, 12.0)))
        assertEquals("At your baseline", baselineChange(Trend(emptyList(), 50.0, 50.0, 0.2)))
        assertNull(baselineChange(Trend(emptyList(), 50.0, null, null)))
    }

    @Test
    fun chartRangeNeverCollapsesAndPadsData() {
        val flat = chartRange(listOf(60.0, 60.0), minSpan = 20.0, pad = 0.0)
        assertEquals(50.0, flat.start, 1e-9)
        assertEquals(70.0, flat.endInclusive, 1e-9)

        val padded = chartRange(listOf(40.0, 80.0), pad = 0.1)
        assertTrue(padded.start < 40.0 && padded.endInclusive > 80.0)
    }

    @Test
    fun yAxisMapsTopToZeroAndBottomToHeight() {
        val range = 40.0..80.0
        assertEquals(0f, yFor(80.0, range, 100f), 1e-4f)
        assertEquals(100f, yFor(40.0, range, 100f), 1e-4f)
        assertEquals(50f, yFor(60.0, range, 100f), 1e-4f)
    }

    @Test
    fun weekdayLabelsUseTheDateAndFallBackOnBadInput() {
        assertEquals("Tue", weekdayLabel("2026-11-17", Locale.US))
        assertEquals("13-45", weekdayLabel("2026-13-45", Locale.US))
    }

    @Test
    fun syncProgressIsReadable() {
        assertEquals("Syncing history: 40 events, 2 KB left", syncStageMessage("sync", 1500u, 40u))
        assertEquals("Syncing history: 0 events", syncStageMessage("sync", 0u, 0u))
        assertEquals("Authenticating...", syncStageMessage("auth", 0u, 0u))
    }

    @Test
    fun formatsDurationsAndAges() {
        assertEquals("7h 5m", formatDuration(7 + 5 / 60.0))
        assertEquals("30 min", formatAge(0.5))
        assertEquals("3 days", formatAge(72.0))
    }
}
