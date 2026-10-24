package dev.lovkvist.listening.text

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Splits a language's sentences into words and counts the ones that aren't known. */
interface SentenceTokenizer {
    /** The form words are compared in. Known words go through it too, so both sides match. */
    fun normalize(word: String): String

    /**
     * How many different words in [sentence] aren't in [known] (which holds [normalize]d
     * words), or null if the sentence has no words at all.
     */
    fun unknownWords(sentence: String, known: Set<String>): Int?

    /** True only if every word in [sentence] is in [known]. */
    fun isFullyKnown(sentence: String, known: Set<String>): Boolean = unknownWords(sentence, known) == 0
}

/** Kuromoji for Japanese, word boundaries (with dictionary segmentation where needed) for the rest. */
@Singleton
class Tokenizers @Inject constructor(private val japanese: JapaneseTokenizer) {
    private val others = ConcurrentHashMap<Language, WordTokenizer>()

    fun forLanguage(language: Language): SentenceTokenizer =
        if (language == Language.JAPANESE) japanese else others.getOrPut(language) { WordTokenizer(language) }
}
