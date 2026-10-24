package dev.lovkvist.listening.data.dict

import dev.lovkvist.listening.text.Language

/**
 * A dictionary offered for one-tap install. The Japanese set is Hachidori's default set
 * (github.com/bee-san/hachidori, extension/recommended-dictionaries.js): words, names, kanji,
 * frequency and grammar. All of them publish an index URL, so they update automatically.
 */
data class RecommendedDictionary(
    val sourceId: String,
    val name: String,
    /** What it covers, in English; shown under the name. */
    val description: String,
    val downloadUrl: String,
    val language: Language,
    /** Rough download size in MB, to show before installing. */
    val sizeMb: Int,
    val role: String? = null,
) {
    companion object {
        val all = listOf(
            RecommendedDictionary(
                "jitendex", "Jitendex", "Japanese–English words",
                "https://github.com/stephenmk/stephenmk.github.io/releases/latest/download/jitendex-yomitan.zip",
                Language.JAPANESE, 39,
            ),
            RecommendedDictionary(
                "jmnedict", "JMnedict", "Japanese names",
                "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMnedict.zip",
                Language.JAPANESE, 11,
            ),
            RecommendedDictionary(
                "bees-ultimate-kanji-dictionary", "Bee's Ultimate Kanji Dictionary", "Kanji",
                "https://github.com/bee-san/bees-ultimate-kanji-dictionary/releases/latest/download/bees-ultimate-kanji-dictionary.zip",
                Language.JAPANESE, 14, role = DictionaryRepository.ROLE_KANJI,
            ),
            RecommendedDictionary(
                "jiten", "Jiten frequency", "How common words are",
                "https://api.jiten.moe/api/frequency-list/download?downloadType=yomitan",
                Language.JAPANESE, 8,
            ),
            RecommendedDictionary(
                "bees-ultimate-grammar-dictionary", "Bee's Ultimate Grammar Dictionary", "Grammar points",
                "https://github.com/bee-san/bees-ultimate-grammar-dictionary/releases/latest/download/bees-ultimate-grammar-dictionary.zip",
                Language.JAPANESE, 17,
            ),
        )

        fun forLanguage(language: Language) = all.filter { it.language == language }
    }
}
