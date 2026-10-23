package dev.lovkvist.listening.text

import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer
import javax.inject.Inject
import javax.inject.Singleton

/** A content word in a sentence: its dictionary form plus the forms we accept as "known". */
data class Word(val baseForm: String, val candidates: Set<String>)

@Singleton
class JapaneseTokenizer @Inject constructor() {
    // Loading the IPADIC dictionary takes a moment, so do it on first use (off the main thread).
    private val tokenizer by lazy { Tokenizer() }

    fun words(sentence: String): List<Word> =
        tokenizer.tokenize(sentence)
            .filterNot(::isIgnorable)
            .map { t ->
                val base = t.baseForm.takeUnless { it.isNullOrBlank() || it == "*" } ?: t.surface
                val reading = t.reading.takeUnless { it.isNullOrBlank() || it == "*" }?.let(::toHiragana)
                Word(base, setOfNotNull(base, t.surface, reading))
            }

    /**
     * True only if every word in [sentence] is in [known].
     * A word counts as known if its base form, its surface form or its kana reading is listed,
     * since Jiten exports may spell words in kana where IPADIC uses kanji (or vice versa).
     */
    fun isFullyKnown(sentence: String, known: Set<String>): Boolean {
        val words = words(sentence)
        return words.isNotEmpty() && words.all { w -> w.candidates.any { it in known } }
    }

    /**
     * Generates Anki bracket furigana (`食[た]べる`) from Kuromoji's readings, for text that
     * has none, such as subtitles. Kana before and after the kanji (okurigana) stays outside
     * the brackets. Line breaks are preserved.
     */
    fun furigana(text: String): String = text.lines().joinToString("\n") { line ->
        tokenizer.tokenize(line).joinToString("") { t -> annotate(t.surface, t.reading) }
    }

    private fun annotate(surface: String, katakanaReading: String?): String {
        if (surface.none(::isKanji) || katakanaReading.isNullOrBlank() || katakanaReading == "*") return surface
        val reading = toHiragana(katakanaReading)
        val kana = toHiragana(surface)
        if (kana == reading) return surface

        // Peel off matching kana at both ends; what remains is the kanji core and its reading.
        var head = 0
        while (head < surface.length && head < reading.length && !isKanji(surface[head]) && kana[head] == reading[head]) head++
        var tail = 0
        while (tail < surface.length - head && tail < reading.length - head &&
            !isKanji(surface[surface.length - 1 - tail]) &&
            kana[kana.length - 1 - tail] == reading[reading.length - 1 - tail]
        ) tail++

        val core = surface.substring(head, surface.length - tail)
        val coreReading = reading.substring(head, reading.length - tail)
        if (core.isEmpty() || coreReading.isEmpty()) return surface
        // The leading space marks where the annotated word starts, as in Anki.
        return surface.substring(0, head) + " $core[$coreReading]" + surface.substring(surface.length - tail)
    }

    private fun isKanji(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿' || c == '々'

    private fun isIgnorable(t: Token): Boolean {
        val pos = t.partOfSpeechLevel1
        // Punctuation/symbols and whitespace aren't words.
        return pos == "記号" || t.surface.isBlank() || t.surface.all { !it.isLetterOrDigit() }
    }

    private fun toHiragana(katakana: String): String = buildString(katakana.length) {
        for (c in katakana) append(if (c in 'ァ'..'ヶ') c - 0x60 else c)
    }
}
