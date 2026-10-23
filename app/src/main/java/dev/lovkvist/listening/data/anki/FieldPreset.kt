package dev.lovkvist.listening.data.anki

/**
 * Which note fields hold the sentence data for a given note type.
 * Field names are matched case-insensitively. A blank field name means "not available".
 */
data class FieldMapping(
    val noteTypeMatch: String,
    val sentence: String,
    val furigana: String,
    val translation: String,
    val audio: String,
    /** The card's target word; every card's word counts as known. */
    val word: String,
)

enum class Preset(val label: String, val defaults: FieldMapping) {
    KIKU(
        "Kiku",
        FieldMapping(
            noteTypeMatch = "Kiku",
            sentence = "Sentence",
            furigana = "SentenceFurigana",
            translation = "SentenceTranslation",
            audio = "SentenceAudio",
            word = "Expression",
        ),
    ),
    LAPIS(
        "Lapis",
        FieldMapping(
            noteTypeMatch = "Lapis",
            sentence = "Sentence",
            furigana = "SentenceFurigana",
            // Lapis ships without a translation field; set one here if you added it.
            translation = "",
            audio = "SentenceAudio",
            word = "Expression",
        ),
    ),
    SENREN(
        "Senren",
        FieldMapping(
            noteTypeMatch = "Senren",
            sentence = "sentence",
            furigana = "sentenceFurigana",
            translation = "sentenceTranslation",
            audio = "sentenceAudio",
            word = "word",
        ),
    ),
}
