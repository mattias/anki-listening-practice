package dev.lovkvist.listening.data.anki

import androidx.core.text.HtmlCompat

object AnkiText {
    private val soundTag = Regex("""\[sound:([^\]]+)]""")
    private val lineBreak = Regex("""(?i)<br\s*/?>""")

    fun soundFile(field: String): String? = soundTag.find(field)?.groupValues?.get(1)?.trim()

    /** Strips HTML, sound tags and entities, keeping furigana brackets like 漢字[かんじ] intact. */
    fun plain(field: String): String {
        if (field.isBlank()) return ""
        val withBreaks = lineBreak.replace(soundTag.replace(field, ""), "\n")
        return HtmlCompat.fromHtml(withBreaks, HtmlCompat.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace(' ', ' ')
            .trim()
    }

    /** Removes furigana readings, e.g. "日本語[にほんご]を" -> "日本語を". */
    fun stripFurigana(text: String): String = ruby(text).joinToString("") { it.base }

    /** One furigana segment: base text, optionally annotated with a reading. */
    data class RubySegment(val base: String, val reading: String?)

    private val rubyPattern = Regex("""\s?([^\s\[\]]+?)\[([^\]]+)]""")

    /**
     * Splits Anki furigana syntax (`日本語[にほんご]を 勉強[べんきょう]`) into segments.
     * A leading space marks where the annotated word starts; Anki drops it on render.
     */
    fun ruby(furigana: String): List<RubySegment> {
        val out = mutableListOf<RubySegment>()
        var last = 0
        for (m in rubyPattern.findAll(furigana)) {
            if (m.range.first > last) out += RubySegment(furigana.substring(last, m.range.first), null)
            out += RubySegment(m.groupValues[1], m.groupValues[2])
            last = m.range.last + 1
        }
        if (last < furigana.length) out += RubySegment(furigana.substring(last), null)
        return out
    }
}
