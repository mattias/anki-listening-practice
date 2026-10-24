package dev.lovkvist.listening.text

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/**
 * Tokenizer for every language except Japanese. Words are found with the platform's word
 * break rules; on Android these come from ICU, which also segments Chinese and Thai with a
 * dictionary. Without a lemmatizer, a word counts as known only in the form it's listed in,
 * compared case-insensitively.
 */
class WordTokenizer(private val language: Language) : SentenceTokenizer {
    private val locale = Locale.forLanguageTag(language.code)

    /** The words of [sentence] in the order they appear, without punctuation, spaces or numbers. */
    fun words(sentence: String): List<String> {
        val text = clean(sentence)
        val breaker = BreakIterator.getWordInstance(locale)
        breaker.setText(text)
        val out = mutableListOf<String>()
        var start = breaker.first()
        var end = breaker.next()
        while (end != BreakIterator.DONE) {
            val word = text.substring(start, end)
            if (word.any { it.isLetter() }) out += word
            start = end
            end = breaker.next()
        }
        return out
    }

    override fun normalize(word: String): String = clean(word).trim().lowercase(locale)

    override fun unknownWords(sentence: String, known: Set<String>): Int? {
        val text = clean(sentence)
        val words = words(withoutKnownCompounds(text, known)).map(::normalize)
        if (words.isEmpty() && !hasKnownCompound(text, known)) return null
        return words.filterNot { isKnown(it, known) }.distinct().size
    }

    override fun wordSpans(sentence: String): List<WordSpan> {
        val text = sentence.replace('’', '\'')
        val breaker = BreakIterator.getWordInstance(locale)
        breaker.setText(text)
        val out = mutableListOf<WordSpan>()
        var start = breaker.first()
        var end = breaker.next()
        while (end != BreakIterator.DONE) {
            val word = text.substring(start, end)
            if (word.any { it.isLetter() }) {
                val key = normalize(word)
                out += WordSpan(start until end, key, listOf(word.lowercase(locale), key).distinct())
            }
            start = end
            end = breaker.next()
        }
        return out
    }

    override fun unknownRanges(sentence: String, known: Set<String>): List<IntRange> {
        // Same length as the sentence, so positions carry over; each word is cleaned on its own.
        val text = sentence.replace('’', '\'')
        val compounds = HYPHENATED.findAll(text).filter { normalize(it.value) in known }.map { it.range }.toList()
        val breaker = BreakIterator.getWordInstance(locale)
        breaker.setText(text)
        val out = mutableListOf<IntRange>()
        var start = breaker.first()
        var end = breaker.next()
        while (end != BreakIterator.DONE) {
            val word = text.substring(start, end)
            val inCompound = compounds.any { start in it }
            if (word.any { it.isLetter() } && !inCompound && !isKnown(normalize(word), known)) out += start until end
            start = end
            end = breaker.next()
        }
        return out
    }

    /**
     * The word breaker splits at hyphens, so "mag-aral" or "well-known" become two words. A
     * hyphenated compound that is listed as a whole is taken out before splitting.
     */
    private fun withoutKnownCompounds(text: String, known: Set<String>): String =
        HYPHENATED.replace(text) { if (normalize(it.value) in known) " " else it.value }

    private fun hasKnownCompound(text: String, known: Set<String>): Boolean =
        HYPHENATED.findAll(text).any { normalize(it.value) in known }

    private fun isKnown(word: String, known: Set<String>): Boolean =
        word in known ||
            ('\'' in word && elisionsKnown(word, known)) ||
            (language.unspaced && segmentable(word, known, MAX_WORD_LENGTH)) ||
            (language == Language.KOREAN && koreanStemKnown(word, known)) ||
            (language == Language.TAGALOG && tagalogLinkerKnown(word, known))

    /** "l'homme" is known if "l'" (or "l") and "homme" are; Tagalog "ako'y" if "ako" and "ay" are. */
    private fun elisionsKnown(word: String, known: Set<String>): Boolean =
        word.split('\'').filter { it.isNotEmpty() }.all { part ->
            part in known || "$part'" in known || "'$part" in known ||
                (language == Language.TAGALOG && TAGALOG_CONTRACTIONS[part]?.let { it in known } == true)
        }

    /**
     * Tagalog joins the linker "na" to a word ending in a vowel as "-ng" (maganda → magandang)
     * and to a word ending in "n" as "-g" (ulan → ulang); word lists hold the bare word.
     */
    private fun tagalogLinkerKnown(word: String, known: Set<String>): Boolean =
        (word.length > 3 && word.endsWith("ng") && word.dropLast(2) in known) ||
            (word.length > 2 && word.endsWith("ng") && word.dropLast(1).endsWith('n') && word.dropLast(1) in known)

    /**
     * Korean attaches particles to the word they follow (학교에서 = 학교 + 에서), and word lists
     * hold the bare word, so a known word followed only by particles counts as known.
     */
    private fun koreanStemKnown(word: String, known: Set<String>): Boolean =
        (1 until word.length).any { i -> word.substring(0, i) in known && segmentable(word.substring(i), KOREAN_PARTICLES, 3) }

    private fun clean(text: String): String {
        val unified = text.replace('’', '\'')
        val stripped = language.ignoredMarks?.replace(unified, "") ?: unified
        return Normalizer.normalize(stripped, Normalizer.Form.NFC)
    }

    private companion object {
        const val MAX_WORD_LENGTH = 8

        val HYPHENATED = Regex("\\p{L}+(?:-\\p{L}+)+")

        /** Tagalog contractions after an apostrophe: ako'y = ako ay, siya't = siya at. */
        val TAGALOG_CONTRACTIONS = mapOf("y" to "ay", "t" to "at")

        val KOREAN_PARTICLES = setOf(
            "은", "는", "이", "가", "을", "를", "에", "에서", "에게", "께", "께서", "한테", "와", "과", "랑", "이랑",
            "하고", "도", "만", "로", "으로", "의", "까지", "부터", "처럼", "보다", "마다", "나", "이나", "라도",
            "이라도", "조차", "밖에", "서", "요", "야", "이야", "예요", "이에요", "입니다", "이다",
        )

        /**
         * True if [word] splits entirely into entries of [dictionary]. Covers words that the word
         * breaker segments differently from the word list, e.g. a compound listed as its parts.
         */
        fun segmentable(word: String, dictionary: Set<String>, maxLength: Int): Boolean {
            if (word.isEmpty()) return false
            val ok = BooleanArray(word.length + 1).also { it[0] = true }
            for (end in 1..word.length) {
                for (start in maxOf(0, end - maxLength) until end) {
                    if (ok[start] && word.substring(start, end) in dictionary) {
                        ok[end] = true
                        break
                    }
                }
            }
            return ok[word.length]
        }
    }
}
