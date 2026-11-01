package dev.lovkvist.listening.data.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import dev.lovkvist.listening.text.Language
import java.io.IOException
import java.nio.ByteBuffer

/** A subtitle track inside a video file, as the Android media extractor sees it. */
data class EmbeddedTrack(
    val index: Int,
    /** The track's language as a short code ("ja"), or null if the file doesn't say. */
    val language: String?,
    val mime: String,
)

/**
 * Subtitles that are part of the video file (MP4 `mov_text`, Matroska SRT and ASS), found and read
 * with [MediaExtractor]. A file can hold several, one per language.
 */
object EmbeddedSubtitles {
    /** The text tracks of [uri]; empty if there are none or the file can't be read. */
    fun tracks(context: Context, uri: Uri): List<EmbeddedTrack> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).mapNotNull { i ->
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return@mapNotNull null
                if (!isText(mime)) return@mapNotNull null
                EmbeddedTrack(i, languageOf(format), mime)
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            extractor.release()
        }
    }

    /** The first of [tracks] in [language], or null. */
    fun inLanguage(tracks: List<EmbeddedTrack>, language: Language): EmbeddedTrack? =
        tracks.firstOrNull { t -> t.language?.let(language::isCaptionCode) == true }

    /** Reads every line of [track] with its time. */
    fun read(context: Context, uri: Uri, track: EmbeddedTrack): List<SubtitleCue> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            extractor.selectTrack(track.index)
            val samples = ArrayList<Pair<Long, String>>()
            var buffer = ByteBuffer.allocate(SAMPLE_BYTES)
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val bytes = ByteArray(size).also { buffer.position(0); buffer.get(it) }
                samples += extractor.sampleTime / 1000 to textOf(track.mime, bytes)
                extractor.advance()
                if (samples.size > MAX_SAMPLES) throw IOException("Too many subtitle lines")
            }
            buffer = ByteBuffer.allocate(0)
            return Subtitles.tidy(cuesFrom(samples))
        } finally {
            extractor.release()
        }
    }

    /**
     * Lines from samples of (start ms, text): a line lasts until the next sample, which may be an empty
     * one that clears it, and never longer than [MAX_LINE_MS] (Matroska doesn't tell how long a line is).
     */
    internal fun cuesFrom(samples: List<Pair<Long, String>>): List<SubtitleCue> {
        val sorted = samples.sortedBy { it.first }
        return sorted.mapIndexedNotNull { i, (start, text) ->
            if (text.isBlank()) return@mapIndexedNotNull null
            val next = sorted.getOrNull(i + 1)?.first ?: (start + MAX_LINE_MS)
            SubtitleCue(start, minOf(next, start + MAX_LINE_MS).coerceAtLeast(start + MIN_LINE_MS), text.trim())
        }
    }

    /** The text of one sample, by how its format stores it. */
    internal fun textOf(mime: String, bytes: ByteArray): String = when {
        // 3GPP timed text: a 16-bit length, then the text (styles may follow).
        mime == "text/3gpp-tt" -> {
            if (bytes.size < 2) "" else {
                val length = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                String(bytes, 2, minOf(length, bytes.size - 2), Charsets.UTF_8)
            }
        }
        // Matroska ASS: "ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text".
        mime == "text/x-ssa" -> {
            String(bytes, Charsets.UTF_8).split(",", limit = 9).getOrElse(8) { "" }
                .replace(ASS_TAGS, "").replace("\\N", "\n").replace("\\n", "\n")
        }
        else -> String(bytes, Charsets.UTF_8).replace(HTML_TAGS, "")
    }

    private fun isText(mime: String) = mime.startsWith("text/") || mime == "application/x-subrip"

    @OptIn(UnstableApi::class)
    private fun languageOf(format: MediaFormat): String? {
        val tag = (if (format.containsKey(MediaFormat.KEY_LANGUAGE)) format.getString(MediaFormat.KEY_LANGUAGE) else null)
            ?.takeIf { it.isNotBlank() && it != "und" } ?: return null
        // "jpn" and "ja" are the same language; the player and the app use the short code.
        return Util.normalizeLanguageCode(tag)
    }

    // Android's regex engine wants the closing brace escaped too.
    private val ASS_TAGS = Regex("""\{[^}]*\}""")
    private val HTML_TAGS = Regex("""<[^>]+>""")
    private const val SAMPLE_BYTES = 1 shl 16
    private const val MAX_SAMPLES = 100_000
    private const val MAX_LINE_MS = 10_000L
    private const val MIN_LINE_MS = 500L
}
