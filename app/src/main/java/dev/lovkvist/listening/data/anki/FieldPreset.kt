package dev.lovkvist.listening.data.anki

import dev.lovkvist.listening.text.Language

/**
 * Which note fields hold the sentence data. Every note type with the sentence and audio fields is
 * used, whatever its name. Field names are matched case-insensitively; a blank one means "not available".
 */
data class FieldMapping(
    val sentence: String,
    /** Sentence with readings in Anki's bracket syntax: furigana, pinyin or jyutping. */
    val furigana: String,
    val translation: String,
    val audio: String,
    /** The card's target word; every card's word counts as known. */
    val word: String,
)

/**
 * Field names of common note types, as a starting point: any note type with those fields works.
 * Kiku, Lapis and Senren are Japanese note types, offered only while studying Japanese; Anki Miner
 * Note works for every language Anki Miner mines.
 */
enum class Preset(val label: String, val everyLanguage: Boolean = false) {
    KIKU("Kiku"),
    LAPIS("Lapis"),
    SENREN("Senren"),
    ANKI_MINER_NOTE("Anki Miner Note", everyLanguage = true),
    ;

    fun availableFor(language: Language) = everyLanguage || language == Language.JAPANESE

    fun defaults(language: Language): FieldMapping = when (this) {
        KIKU -> FieldMapping(
            sentence = "Sentence",
            furigana = "SentenceFurigana",
            translation = "SentenceTranslation",
            audio = "SentenceAudio",
            word = "Expression",
        )
        LAPIS -> FieldMapping(
            sentence = "Sentence",
            furigana = "SentenceFurigana",
            // Lapis ships without a translation field; set one here if you added it.
            translation = "",
            audio = "SentenceAudio",
            word = "Expression",
        )
        SENREN -> FieldMapping(
            sentence = "sentence",
            furigana = "sentenceFurigana",
            translation = "sentenceTranslation",
            audio = "sentenceAudio",
            word = "word",
        )
        // Fields from github.com/0xzerolight/anki_miner_note. Anki Miner fills SentenceFurigana
        // only for Japanese; its Pinyin, Jyutping, Hanja etc. fields describe the word, not the sentence.
        ANKI_MINER_NOTE -> FieldMapping(
            sentence = "Sentence",
            furigana = if (language == Language.JAPANESE) "SentenceFurigana" else "",
            translation = "SentenceTranslation",
            audio = "SentenceAudio",
            word = "Expression",
        )
    }

    companion object {
        fun availableFor(language: Language): List<Preset> = entries.filter { it.availableFor(language) }
    }
}
