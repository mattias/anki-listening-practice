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

    private fun isIgnorable(t: Token): Boolean {
        val pos = t.partOfSpeechLevel1
        // Punctuation/symbols and whitespace aren't words.
        return pos == "記号" || t.surface.isBlank() || t.surface.all { !it.isLetterOrDigit() }
    }

    private fun toHiragana(katakana: String): String = buildString(katakana.length) {
        for (c in katakana) append(if (c in 'ァ'..'ヶ') c - 0x60 else c)
    }
}
