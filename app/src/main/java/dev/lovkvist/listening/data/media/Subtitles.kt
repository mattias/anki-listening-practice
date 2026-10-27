package dev.lovkvist.listening.data.media

import org.json.JSONObject
import java.io.IOException

/** One subtitle chunk, kept exactly as the subtitle file splits it. */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * Parses SRT, WebVTT, ASS/SSA, LRC lyrics and YouTube json3 subtitles into cues.
 * Formatting (HTML tags, ASS override blocks, karaoke timestamps) is stripped; line breaks are
 * kept as "\n". Empty cues are dropped and identical back-to-back cues are merged.
 */
object Subtitles {

    fun parse(fileName: String, content: String): List<SubtitleCue> {
        val text = content.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val cues = when {
            ext == "lrc" -> parseLrc(text)
            ext == "ass" || ext == "ssa" || text.contains("[Events]") -> parseAss(text)
            ext == "json3" || ext == "json" || text.trimStart().startsWith("{") -> parseJson3(text)
            ext == "vtt" || text.startsWith("WEBVTT") -> parseTimed(text)
            else -> parseTimed(text) // SRT, and anything with "-->" timings
        }
        if (cues.isEmpty()) throw IOException("No subtitles found in $fileName")
        // Repeated lines of a song (a chorus) stay separate lines.
        return if (ext == "lrc") cues else tidy(cues)
    }

    /**
     * LRC lyrics: lines like `[01:23.45]text`, with several `[..]` stamps on a line when it
     * repeats, `<mm:ss.xx>` word stamps (dropped) and `[ar:..]` style tags (ignored). A line ends
     * where the next stamp starts; an empty line is such a marker too (an instrumental break), and
     * a line is never held longer than [MAX_LRC_LINE_MS], so a long silence isn't looped.
     */
    private fun parseLrc(text: String): List<SubtitleCue> {
        val stamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        val marks = mutableListOf<Pair<Long, String>>()
        for (line in text.lines()) {
            val stamps = stamp.findAll(line).toList()
            if (stamps.isEmpty()) continue
            val body = line.substring(stamps.last().range.last + 1).replace(Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>"""), "").trim()
            for (m in stamps) {
                val (min, sec, frac) = m.destructured
                val fraction = frac.padEnd(3, '0').take(3).toLong()
                marks += (min.toLong() * 60_000 + sec.toLong() * 1000 + fraction) to body
            }
        }
        marks.sortBy { it.first }
        val cues = mutableListOf<SubtitleCue>()
        for ((i, mark) in marks.withIndex()) {
            val (start, body) = mark
            if (body.isEmpty()) continue
            val next = marks.getOrNull(i + 1)?.first ?: (start + LAST_LRC_LINE_MS)
            cues += SubtitleCue(start, minOf(next, start + MAX_LRC_LINE_MS).coerceAtLeast(start + 500), body)
        }
        return cues
    }

    private const val MAX_LRC_LINE_MS = 10_000L
    private const val LAST_LRC_LINE_MS = 5_000L

    /** SRT and WebVTT share the "start --> end" line followed by text lines. */
    private fun parseTimed(text: String): List<SubtitleCue> {
        val out = mutableListOf<SubtitleCue>()
        for (block in text.split(Regex("\n\\s*\n"))) {
            val lines = block.lines()
            val timing = lines.indexOfFirst { "-->" in it }
            if (timing < 0) continue // header, NOTE, STYLE blocks
            val (start, end) = lines[timing].split("-->").map { it.trim().substringBefore(' ') }
            val body = lines.drop(timing + 1).joinToString("\n")
            out += SubtitleCue(parseTime(start), parseTime(end), cleanMarkup(body))
        }
        return out
    }

    private fun parseAss(text: String): List<SubtitleCue> {
        var format: List<String>? = null
        val out = mutableListOf<SubtitleCue>()
        var inEvents = false
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                inEvents = trimmed.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            when {
                trimmed.startsWith("Format:", ignoreCase = true) ->
                    format = trimmed.substringAfter(':').split(',').map { it.trim().lowercase() }
                trimmed.startsWith("Dialogue:", ignoreCase = true) -> {
                    val fields = format ?: DEFAULT_ASS_FORMAT
                    // Text is the last field and may itself contain commas.
                    val values = trimmed.substringAfter(':').trimStart().split(',', limit = fields.size)
                    fun value(name: String) = values.getOrNull(fields.indexOf(name))?.trim().orEmpty()
                    val body = value("text").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                    out += SubtitleCue(parseTime(value("start")), parseTime(value("end")), cleanMarkup(body))
                }
            }
        }
        return out
    }

    /** YouTube's json3 caption format: events with start/duration and text segments. */
    private fun parseJson3(text: String): List<SubtitleCue> {
        val events = JSONObject(text).optJSONArray("events") ?: return emptyList()
        val raw = mutableListOf<SubtitleCue>()
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            if (e.optInt("aAppend") == 1) continue
            val segs = e.optJSONArray("segs") ?: continue
            val body = (0 until segs.length()).joinToString("") { segs.getJSONObject(it).optString("utf8") }
            val start = e.optLong("tStartMs")
            raw += SubtitleCue(start, start + e.optLong("dDurationMs"), cleanMarkup(body))
        }
        // Auto captions overlap (each line stays up while the next appears); end at the next start.
        return raw.mapIndexed { i, cue ->
            val nextStart = raw.drop(i + 1).firstOrNull { it.text.isNotBlank() }?.startMs
            if (nextStart != null && nextStart in (cue.startMs + 1) until cue.endMs) cue.copy(endMs = nextStart) else cue
        }
    }

    private fun tidy(cues: List<SubtitleCue>): List<SubtitleCue> {
        val out = mutableListOf<SubtitleCue>()
        for (cue in cues.sortedBy { it.startMs }) {
            if (cue.text.isBlank() || cue.endMs <= cue.startMs) continue
            val last = out.lastOrNull()
            if (last != null && last.text == cue.text && cue.startMs - last.endMs <= MERGE_GAP_MS) {
                out[out.lastIndex] = last.copy(endMs = maxOf(last.endMs, cue.endMs))
            } else {
                out += cue
            }
        }
        return out
    }

    /**
     * Joins a cue's lines into one sentence. Spaced languages get a space at each break.
     * Unspaced ones (Japanese, Chinese, Taiwanese) are joined directly, except between two
     * Latin-script words, e.g. romanized Taiwanese, which would otherwise run together.
     */
    fun joinLines(text: String, unspaced: Boolean): String {
        if (!unspaced) return text.replace("\n", " ")
        val lines = text.split("\n")
        return buildString {
            lines.forEachIndexed { i, line ->
                if (i > 0 && isLatin(lines[i - 1].lastOrNull()) && isLatin(line.firstOrNull())) append(' ')
                append(line)
            }
        }
    }

    /**
     * Translations for [cues] taken from subtitles in another language, one per cue: each
     * translation line goes to the cue it overlaps most in time, and a cue's lines are joined
     * ([unspaced] is the translation's language). A cue that no line overlaps gets "".
     */
    fun align(cues: List<SubtitleCue>, translation: List<SubtitleCue>, unspaced: Boolean): List<String> {
        val parts = List(cues.size) { mutableListOf<String>() }
        for (t in translation) {
            var best = -1
            var bestOverlap = 0L
            for ((i, c) in cues.withIndex()) {
                if (c.startMs >= t.endMs) break // cues are sorted by start
                val overlap = minOf(c.endMs, t.endMs) - maxOf(c.startMs, t.startMs)
                if (overlap > bestOverlap) {
                    best = i
                    bestOverlap = overlap
                }
            }
            if (best >= 0) parts[best] += joinLines(t.text, unspaced)
        }
        return parts.map { it.joinToString(if (unspaced) "" else " ") }
    }

    private fun isLatin(c: Char?): Boolean =
        c != null && (c.isDigit() || Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN)

    /** Accepts h:mm:ss.cc (ASS), hh:mm:ss,mmm (SRT), hh:mm:ss.mmm and mm:ss.mmm (VTT). */
    internal fun parseTime(value: String): Long {
        val parts = value.trim().replace(',', '.').split(':')
        if (parts.size !in 2..3) throw IOException("Bad timestamp: $value")
        val seconds = parts.last().toDouble()
        val minutes = parts[parts.size - 2].toLong()
        val hours = if (parts.size == 3) parts[0].toLong() else 0
        return (hours * 3600 + minutes * 60) * 1000 + Math.round(seconds * 1000)
    }

    private val htmlTag = Regex("<[^>]*>")
    // Android's ICU regex engine requires the closing brace to be escaped too.
    private val assOverride = Regex("\\{[^}]*\\}")
    private val entities = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&nbsp;" to " ", "&quot;" to "\"", "&#39;" to "'", "&lrm;" to "", "&rlm;" to "")

    private fun cleanMarkup(text: String): String {
        var t = assOverride.replace(htmlTag.replace(text, ""), "")
        for ((k, v) in entities) t = t.replace(k, v)
        return t.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private val DEFAULT_ASS_FORMAT =
        listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
    private const val MERGE_GAP_MS = 50L
}
