package dev.lovkvist.listening.data.stats

import dev.lovkvist.listening.data.local.ListeningStat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class StatisticsTest {
    private fun row(day: String, language: String = "ja", kind: String = "anki", sentences: Int = 0, plays: Int = 0, clear: Int = 0, listen: Long = 0, active: Long = 0) =
        ListeningStat(day, language, kind, sentences, plays, clear, listen, active)

    // Wednesday 2026-11-04.
    private val today = LocalDate.of(2026, 11, 4)
    private val rows = listOf(
        row("2025-12-30", sentences = 5, listen = 60_000),
        row("2026-01-02", language = "sv", kind = "video", sentences = 3, plays = 9, listen = 120_000, active = 180_000),
        row("2026-10-31", sentences = 10, plays = 40, clear = 4, listen = 600_000, active = 900_000),
        row("2026-11-02", sentences = 20, plays = 80, clear = 8, listen = 1_200_000, active = 1_500_000),
        row("2026-11-02", kind = "sound", sentences = 2, plays = 6, listen = 30_000, active = 40_000),
        row("2026-11-04", language = "sv", sentences = 1, plays = 2, listen = 10_000, active = 12_000),
    )

    @Test
    fun totalsAddUpAndCountTheDaysWithPractice() {
        val t = Statistics.totals(rows)
        assertEquals(41, t.sentences)
        assertEquals(2_020_000, t.listenMs)
        // 2026-11-02 has two rows but is one day.
        assertEquals(5, t.days)
    }

    @Test
    fun aLanguageFilter() {
        val sv = Statistics.inLanguage(rows, "sv")
        assertEquals(4, Statistics.totals(sv).sentences)
        assertEquals(rows, Statistics.inLanguage(rows, null))
    }

    @Test
    fun weeksStartOnMonday() {
        assertEquals(LocalDate.of(2026, 11, 2), Statistics.bucketStart(today, Period.WEEK))
        assertEquals(LocalDate.of(2026, 11, 2), Statistics.bucketStart(LocalDate.of(2026, 11, 8), Period.WEEK))
        assertEquals(LocalDate.of(2026, 11, 1), Statistics.bucketStart(today, Period.MONTH))
        assertEquals(LocalDate.of(2026, 1, 1), Statistics.bucketStart(today, Period.YEAR))
    }

    @Test
    fun whatIsInThePeriodWeAreIn() {
        assertEquals(1, Statistics.current(rows, Period.DAY, today).sentences)
        // This week is 2 to 8 November: the 2nd (two rows) and the 4th.
        assertEquals(23, Statistics.current(rows, Period.WEEK, today).sentences)
        // The month leaves out 31 October.
        assertEquals(23, Statistics.current(rows, Period.MONTH, today).sentences)
        assertEquals(36, Statistics.current(rows, Period.YEAR, today).sentences)
        assertEquals(41, Statistics.current(rows, Period.ALL, today).sentences)
    }

    @Test
    fun aChartOfDaysHasEmptyDaysToo() {
        val days = Statistics.buckets(rows, Period.DAY, today, 5)
        assertEquals(listOf("2026-10-31", "2026-11-01", "2026-11-02", "2026-11-03", "2026-11-04"), days.map { it.start.toString() })
        assertEquals(listOf(10, 0, 22, 0, 1), days.map { it.totals.sentences })
    }

    @Test
    fun weeksAndMonthsCrossTheYear() {
        val weeks = Statistics.buckets(rows, Period.WEEK, today, 2)
        assertEquals(listOf(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 2)), weeks.map { it.start })
        assertEquals(listOf(10, 23), weeks.map { it.totals.sentences })
        val months = Statistics.buckets(rows, Period.MONTH, today, 12)
        assertEquals(LocalDate.of(2025, 12, 1), months.first().start)
        assertEquals(5, months.first().totals.sentences)
        assertEquals(LocalDate.of(2026, 11, 1), months.last().start)
    }

    @Test
    fun yearsGoBackToTheFirstYearWithPractice() {
        val years = Statistics.buckets(rows, Period.YEAR, today, 10)
        assertEquals(listOf(2025, 2026), years.map { it.start.year })
        assertEquals(listOf(5, 36), years.map { it.totals.sentences })
    }

    @Test
    fun everythingIsOneBucket() {
        val all = Statistics.buckets(rows, Period.ALL, today, 99).single()
        assertEquals(LocalDate.of(2025, 12, 30), all.start)
        assertEquals(41, all.totals.sentences)
    }

    @Test
    fun languagesAndKindsMostListenedFirst() {
        assertEquals(listOf("ja", "sv"), Statistics.languages(rows))
        assertEquals("anki", Statistics.byKind(rows).first().first)
        assertEquals("sound", Statistics.byKind(rows).last().first)
    }

    @Test
    fun theFileForTheDashboard() {
        val json = JSONObject(StatsExport.toJson(rows + row("2026-11-03"), Instant.parse("2026-11-04T10:00:00Z"), "Pixel"))
        assertEquals("listening-practice", json.getString("app"))
        assertEquals(1, json.getInt("version"))
        assertEquals("Pixel", json.getString("device"))
        val entries = json.getJSONArray("entries")
        // The empty row is left out.
        assertEquals(6, entries.length())
        val e = entries.getJSONObject(3)
        assertEquals("2026-11-02", e.getString("date"))
        assertEquals("ja", e.getString("language"))
        assertEquals("anki", e.getString("kind"))
        assertEquals(20, e.getInt("sentences"))
        assertEquals(1200.0, e.getDouble("seconds"), 0.001)
        assertEquals(1500.0, e.getDouble("active"), 0.001)
    }

    @Test
    fun rowsForTheProviderFromADayOn() {
        val all = StatsExport.rows(rows + row("2026-11-03"))
        // The empty row is left out; the columns are in the documented order.
        assertEquals(6, all.size)
        assertEquals(listOf("date", "language", "kind", "sentences", "plays", "clear", "seconds", "active"), StatsExport.COLUMNS.toList())
        assertEquals(listOf<Any?>("2026-11-02", "ja", "anki", 20, 80, 8, 1200.0, 1500.0), all[3].toList())
        val since = StatsExport.rows(rows, "2026-11-01")
        assertEquals(listOf("2026-11-02", "2026-11-02", "2026-11-04"), since.map { it[0] })
    }

    @Test
    fun timesForPeople() {
        assertEquals("45 s", formatListenTime(45_000))
        assertEquals("12 min", formatListenTime(12 * 60_000 + 30_000))
        assertEquals("1 h 23 min", formatListenTime((83 * 60_000).toLong()))
        assertTrue(formatListenTime(0).startsWith("0"))
    }
}
