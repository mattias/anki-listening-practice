package dev.lovkvist.listening.data.stats

import dev.lovkvist.listening.data.local.ListeningStat
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** The span the statistics are grouped by. */
enum class Period { DAY, WEEK, MONTH, YEAR, ALL }

/** What was practiced in some span. */
data class Totals(
    /** Time the audio was heard, ms. */
    val listenMs: Long = 0,
    /** Time playback was going, pauses between repeats included, ms. */
    val activeMs: Long = 0,
    val sentences: Int = 0,
    val plays: Int = 0,
    val clear: Int = 0,
    /** Days with any practice. */
    val days: Int = 0,
) {
    operator fun plus(o: Totals) = Totals(listenMs + o.listenMs, activeMs + o.activeMs, sentences + o.sentences, plays + o.plays, clear + o.clear, days + o.days)
}

/** One group of days: [start] to [end] inclusive. */
data class Bucket(val start: LocalDate, val end: LocalDate, val totals: Totals)

/** Sums and groups [ListeningStat] rows. Pure, so it is tested without a database. */
object Statistics {
    private fun ListeningStat.isActive() = sentences > 0 || plays > 0 || listenMs > 0

    private fun date(row: ListeningStat): LocalDate = LocalDate.parse(row.day)

    /** [rows] added up; the days are the different days with practice. */
    fun totals(rows: List<ListeningStat>): Totals {
        var t = Totals()
        for (r in rows) t += Totals(r.listenMs, r.activeMs, r.sentences, r.plays, r.clear)
        return t.copy(days = rows.filter { it.isActive() }.map { it.day }.distinct().size)
    }

    fun inLanguage(rows: List<ListeningStat>, language: String?) = if (language == null) rows else rows.filter { it.language == language }

    /** The first day of the group [day] falls in; Monday starts a week. */
    fun bucketStart(day: LocalDate, period: Period): LocalDate = when (period) {
        Period.DAY -> day
        Period.WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        Period.MONTH -> day.withDayOfMonth(1)
        Period.YEAR -> day.withDayOfYear(1)
        Period.ALL -> LocalDate.of(1970, 1, 1)
    }

    private fun bucketEnd(start: LocalDate, period: Period): LocalDate = when (period) {
        Period.DAY -> start
        Period.WEEK -> start.plusDays(6)
        Period.MONTH -> start.with(TemporalAdjusters.lastDayOfMonth())
        Period.YEAR -> start.with(TemporalAdjusters.lastDayOfYear())
        Period.ALL -> LocalDate.of(9999, 12, 31)
    }

    private fun previous(start: LocalDate, period: Period): LocalDate = when (period) {
        Period.DAY -> start.minusDays(1)
        Period.WEEK -> start.minusWeeks(1)
        Period.MONTH -> start.minusMonths(1)
        Period.YEAR -> start.minusYears(1)
        Period.ALL -> start
    }

    /**
     * The last [count] groups up to the one [today] is in, oldest first, empty ones included, for a chart. Years go back
     * to the first year with practice (at most [count]); [Period.ALL] is one group of everything.
     */
    fun buckets(rows: List<ListeningStat>, period: Period, today: LocalDate, count: Int): List<Bucket> {
        if (period == Period.ALL) return listOf(Bucket(rows.minOfOrNull { date(it) } ?: today, today, totals(rows)))
        var start = bucketStart(today, period)
        val firstYear = if (period == Period.YEAR) rows.minOfOrNull { date(it).year } else null
        val starts = ArrayList<LocalDate>()
        for (i in 0 until count) {
            starts += start
            if (firstYear != null && start.year <= firstYear) break
            start = previous(start, period)
        }
        return starts.reversed().map { s ->
            val e = bucketEnd(s, period)
            Bucket(s, e, totals(rows.filter { date(it) in s..e }))
        }
    }

    /** What has been practiced in the group [today] is in (today, this week, this month, this year, or ever). */
    fun current(rows: List<ListeningStat>, period: Period, today: LocalDate): Totals {
        if (period == Period.ALL) return totals(rows)
        val s = bucketStart(today, period)
        val e = bucketEnd(s, period)
        return totals(rows.filter { date(it) in s..e })
    }

    /** Languages with practice, the one with most listening time first. */
    fun languages(rows: List<ListeningStat>): List<String> =
        rows.groupBy { it.language }.entries.sortedByDescending { e -> totals(e.value).listenMs + e.value.sumOf { it.sentences } }.map { it.key }

    fun byLanguage(rows: List<ListeningStat>): List<Pair<String, Totals>> =
        rows.groupBy { it.language }.map { it.key to totals(it.value) }.sortedByDescending { it.second.listenMs }

    fun byKind(rows: List<ListeningStat>): List<Pair<String, Totals>> =
        rows.groupBy { it.kind }.map { it.key to totals(it.value) }.sortedByDescending { it.second.listenMs }

    /** Rows between [from] and [to] inclusive. */
    fun between(rows: List<ListeningStat>, from: LocalDate, to: LocalDate) = rows.filter { date(it) in from..to }
}

/** "1 h 23 min", "12 min", "45 s": a time for people. */
fun formatListenTime(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds >= 3600 -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
        seconds >= 60 -> "${seconds / 60} min"
        else -> "$seconds s"
    }
}

/**
 * The statistics for other programs: one entry per local day, language and kind, with totals (not increments). As a file
 * ([toJson], saved by Export) and as rows for the content provider the Immersion Dashboard's app reads ([rows]).
 */
object StatsExport {
    const val APP = "listening-practice"
    const val VERSION = 1

    /** The columns of the provider's rows and the names of an entry's fields in the file. */
    val COLUMNS = arrayOf("date", "language", "kind", "sentences", "plays", "clear", "seconds", "active")

    private fun seconds(ms: Long) = Math.round(ms / 100.0) / 10.0

    /** The rows with something counted, from day `since` (ISO date) on if it is given, in [COLUMNS] order. */
    fun rows(rows: List<ListeningStat>, since: String? = null): List<Array<Any?>> =
        rows.filter { (it.sentences > 0 || it.plays > 0 || it.listenMs > 0) && (since == null || it.day >= since) }
            .map { arrayOf(it.day, it.language, it.kind, it.sentences, it.plays, it.clear, seconds(it.listenMs), seconds(it.activeMs)) }

    fun toJson(rows: List<ListeningStat>, updated: Instant, device: String): String {
        val entries = JSONArray()
        for (row in rows(rows)) {
            val o = JSONObject()
            COLUMNS.forEachIndexed { i, name -> o.put(name, row[i]) }
            entries.put(o)
        }
        return JSONObject().put("app", APP).put("version", VERSION).put("updated", updated.toString()).put("device", device)
            .put("entries", entries).toString()
    }
}

/** A language's name for a chart or list, from its code. */
fun languageLabel(code: String): String = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).ifBlank { code }
