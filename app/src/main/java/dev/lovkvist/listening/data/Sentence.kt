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
    /** Sentence with readings in Anki bracket syntax (漢字[かんじ], 汉字[hàn zì]); empty if there are none. */
    val furigana: String,
    val translation: String,
    val audio: Uri,
    /** Play only this range of [audio]; null plays the whole file. */
    val clip: LongRange? = null,
)

/**
 * Where practice sentences come from. Each kind says how it behaves, so the rest of the app asks
 * the source instead of checking which one it is.
 */
sealed interface PracticeSource {
    data object Anki : PracticeSource
    data class Media(val id: String) : PracticeSource

    /** Sentences come in timeline order (a video, a song) rather than shuffled (Anki cards). */
    val ordered: Boolean get() = this !is Anki

    /** Neighboring sentences can be chained into one repeated stretch; needs [ordered]. */
    val supportsChain: Boolean get() = ordered

    fun key(): String = when (this) {
        Anki -> "anki"
        is Media -> "media:$id"
    }

    companion object {
        fun fromKey(key: String?): PracticeSource =
            if (key != null && key.startsWith("media:")) Media(key.removePrefix("media:")) else Anki
    }
}
