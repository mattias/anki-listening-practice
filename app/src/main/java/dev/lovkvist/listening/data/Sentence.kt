package dev.lovkvist.listening.data

import android.net.Uri

/**
 * One practice item: an Anki card's sentence, or one subtitle chunk of a video/audio file.
 * [id] is the Anki note id for cards and a negative id for media chunks, so cool-downs and
 * the blacklist can share one table.
 */
data class Sentence(
    val id: Long,
    val text: String,
    /** Anki bracket furigana (漢字[かんじ]); empty if there is none. */
    val furigana: String,
    val translation: String,
    val audio: Uri,
    /** Play only this range of [audio]; null plays the whole file. */
    val clip: LongRange? = null,
)

/** Where practice sentences come from. */
sealed interface PracticeSource {
    data object Anki : PracticeSource
    data class Media(val id: String) : PracticeSource

    fun key(): String = when (this) {
        Anki -> "anki"
        is Media -> "media:$id"
    }

    companion object {
        fun fromKey(key: String?): PracticeSource =
            if (key != null && key.startsWith("media:")) Media(key.removePrefix("media:")) else Anki
    }
}
