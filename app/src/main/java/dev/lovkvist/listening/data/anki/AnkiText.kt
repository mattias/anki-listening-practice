package dev.lovkvist.listening.data.anki

import androidx.core.text.HtmlCompat

object AnkiText {
    private val soundTag = Regex("""\[sound:([^\]]+)]""")
    private val lineBreak = Regex("""(?i)<br\s*/?>""")

    fun soundFile(field: String): String? = soundTag.find(field)?.groupValues?.get(1)?.trim()

    /** All sound files referenced in a field (a field can hold several [sound:...] tags). */
    fun soundFiles(field: String): Sequence<String> =
        soundTag.findAll(field).map { it.groupValues[1].trim() }

    /**
     * Strips HTML, sound tags and entities. Furigana comes out in Anki's bracket form
     * (漢字[かんじ]) whether the field used brackets or HTML `<ruby>` markup.
     */
    fun plain(field: String): String {
        if (field.isBlank()) return ""
        val withBreaks = lineBreak.replace(rubyHtmlToBrackets(soundTag.replace(field, "")), "\n")
        return HtmlCompat.fromHtml(withBreaks, HtmlCompat.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace(' ', ' ')
            .trim()
    }

    private val rubyBlock = Regex("""(?is)<ruby\b[^>]*>(.*?)</ruby>""")
    private val rpTag = Regex("""(?is)<rp\b[^>]*>.*?</rp>""")
    private val rtPair = Regex("""(?is)(.*?)<rt\b[^>]*>(.*?)</rt>""")
    private val anyTag = Regex("""<[^>]+>""")

    /**
     * Rewrites `<ruby>漢字<rt>かんじ</rt></ruby>` (also with `<rb>`/`<rp>`, or several
     * base/reading pairs in one ruby) as ` 漢字[かんじ]`. Other HTML is left untouched.
     */
    fun rubyHtmlToBrackets(html: String): String = rubyBlock.replace(html) { block ->
        val inner = rpTag.replace(block.groupValues[1], "")
        val out = StringBuilder()
        var last = 0
        for (pair in rtPair.findAll(inner)) {
            val base = anyTag.replace(pair.groupValues[1], "").trim()
            val reading = anyTag.replace(pair.groupValues[2], "").trim()
            out.append(
                when {
                    base.isEmpty() -> ""
                    reading.isEmpty() -> base
                    // The leading space marks where the annotated word starts, as in Anki.
                    else -> " $base[$reading]"
                },
            )
            last = pair.range.last + 1
        }
        out.append(anyTag.replace(inner.substring(last), ""))
        out.toString()
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
