package dev.lovkvist.listening.data.media

import android.content.Context
import android.net.Uri
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lovkvist.listening.R
import dev.lovkvist.listening.l10n.Strings
import dev.lovkvist.listening.l10n.displayName
import dev.lovkvist.listening.text.Language
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A video found by [YouTubeImporter.search]. */
data class VideoResult(
    val id: String,
    val title: String,
    val channel: String,
    /** Null for live streams and when YouTube gives none. */
    val durationSeconds: Long?,
    val url: String,
)

/** YouTube is limiting the requests, so asking for more now only makes it worse. */
class RateLimitedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Whether a video is something to practice listening with in the study language. YouTube
 * translates titles and subtitles and even dubs audio by machine, so none of those say what is
 * really spoken; the speech-recognition track ("<code>-orig", only made when someone speaks) does.
 */
enum class SubtitleStatus {
    /** Spoken in the study language, with subtitles made by people. */
    MANUAL,
    /** Spoken in the study language, with speech-recognition captions. */
    AUTO,
    /** Spoken in another language (any titles, dubs and subtitles in this one are translations). */
    OTHER_LANGUAGE,
    /** Subtitles, but nobody is recognized speaking: a music video with captions, say. */
    NO_SPEECH,
    /** No subtitles in the study language. */
    NONE,
    /** The check itself failed (e.g. offline); the video may still work. */
    UNKNOWN,
}

/**
 * Downloads a YouTube video's audio and subtitles in the study language with yt-dlp (via youtubedl-android)
 * and adds them to the [MediaLibrary]. Manual subtitles are preferred over auto-generated ones. Subtitles
 * in the translation language (or English) come along too, as each line's translation.
 */
@Singleton
class YouTubeImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: MediaLibrary,
    private val strings: Strings,
) {
    private val setupLock = Mutex()
    private var ready = false

    /**
     * Searches YouTube for [query], limited to videos that have captions (YouTube's "Subtitles/CC"
     * filter, which doesn't say in which language: see [subtitleStatus]). Nothing is downloaded.
     */
    suspend fun search(query: String, limit: Int = SEARCH_LIMIT): List<VideoResult> {
        setUp {}
        // The filter's parameter goes in double-encoded, as YouTube writes it in its own links.
        val url = "https://www.youtube.com/results?search_query=${Uri.encode(query.trim())}&sp=$CAPTIONS_FILTER"
        return parseSearchResults(runCancellable("search", YoutubeDLRequest(url).apply {
            addOption("--flat-playlist")
            addOption("--dump-single-json")
            addOption("--playlist-end", limit.toString())
        }))
    }

    /**
     * Whether the video at [url] has subtitles in [language] ([SubtitleStatus.UNKNOWN] if it can't
     * be told). Throws [RateLimitedException] when YouTube starts refusing requests, so the caller
     * can stop asking. Cancelling stops the yt-dlp process too.
     */
    suspend fun subtitleStatus(url: String, language: Language): SubtitleStatus =
        try {
            setUp {}
            val info = JSONObject(runCancellable("subs-${url.hashCode()}", YoutubeDLRequest(url).apply {
                addOption("--dump-single-json")
                addOption("--skip-download")
                addOption("--no-playlist")
            }))
            subtitleStatus(info, language)
        } catch (e: RateLimitedException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SubtitleStatus.UNKNOWN
        }

    suspend fun import(
        url: String,
        language: Language,
        translationLanguage: Language?,
        onStatus: (String) -> Unit,
    ): MediaEntry = withContext(Dispatchers.IO) {
        setUp(onStatus)

        onStatus(strings(R.string.yt_looking))
        val info = JSONObject(run(YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--skip-download")
            addOption("--no-playlist")
        }))
        val title = info.optString("title").ifBlank { "YouTube video" }
        fun keys(name: String) = trackNames(info, name)
        // Only subtitles of what is spoken: a machine translation is no use as the sentences to practice.
        val track = pickTrack(keys("subtitles"), keys("automatic_captions"), language, allowTranslated = false)
            ?: throw IOException(strings(R.string.yt_no_subtitles, title, language.displayName()))
        // The chosen translation language, else English; YouTube's machine translations count.
        val translation = listOfNotNull(translationLanguage, Language.ENGLISH.takeIf { translationLanguage != null })
            .filter { it != language }
            .firstNotNullOfOrNull { l -> pickTrack(keys("subtitles"), keys("automatic_captions"), l)?.let { l to it } }

        val dir = library.newDir()
        try {
            onStatus(strings(R.string.yt_downloading))
            run(YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                // A single audio-only stream needs no merging, so no ffmpeg.
                addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                addOption("-o", File(dir, "audio.%(ext)s").absolutePath)
                addOption(if (track.automatic) "--write-auto-subs" else "--write-subs")
                addOption("--sub-langs", track.lang)
                // Auto captions in VTT repeat each line while scrolling; json3 has clean events.
                addOption("--sub-format", if (track.automatic) "json3" else "vtt/srt/best")
            }) { progress -> onStatus(strings(R.string.yt_progress, progress.toInt().coerceIn(0, 100))) }

            val translated = translation?.let { (l, t) ->
                onStatus(strings(R.string.yt_translation))
                // A missing translation isn't worth failing the import for.
                runCatching { downloadSubtitles(url, t, File(dir, "translation.%(ext)s")) }.getOrNull()
                    ?.let { l to it }
            }

            val files = dir.listFiles().orEmpty()
            val subs = files.firstOrNull { it.name.startsWith("audio.${track.lang}.") }
                ?: throw IOException("yt-dlp didn't save the subtitles")
            val audio = files.firstOrNull { it.name.startsWith("audio.") && it != subs && !it.name.endsWith(".part") }
                ?: throw IOException("yt-dlp didn't save the audio")
            onStatus(strings(R.string.reading_subtitles))
            library.importDownloaded(
                dir, audio, subs, title + if (track.automatic) " (auto subs)" else "", url, language, translated,
            )
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    /** Downloads just [track]'s subtitles to [output] (a yt-dlp template) and returns the file. */
    private fun downloadSubtitles(url: String, track: Track, output: File): File {
        run(YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--skip-download")
            addOption("-o", output.absolutePath)
            addOption(if (track.automatic) "--write-auto-subs" else "--write-subs")
            addOption("--sub-langs", track.lang)
            addOption("--sub-format", if (track.automatic) "json3" else "vtt/srt/best")
        })
        val prefix = output.name.substringBefore("%") + track.lang + "."
        return output.parentFile?.listFiles().orEmpty().firstOrNull { it.name.startsWith(prefix) }
            ?: throw IOException("yt-dlp didn't save the ${track.lang} subtitles")
    }

    /** Unpacks Python/yt-dlp on first use and updates yt-dlp, since YouTube changes often. */
    private suspend fun setUp(onStatus: (String) -> Unit) = setupLock.withLock {
        if (ready) return@withLock
        onStatus(strings(R.string.yt_preparing))
        YoutubeDL.getInstance().init(context)
        onStatus(strings(R.string.yt_updating))
        // A failed update (e.g. offline) isn't fatal; the bundled version may still work.
        runCatching { YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE) }
        ready = true
    }

    companion object {
        const val SEARCH_LIMIT = 20

        /** YouTube refusing requests for a while: it wants a sign-in ("not a bot") or answers 429. */
        internal fun isRateLimit(message: String?): Boolean {
            val m = message.orEmpty().lowercase()
            return "not a bot" in m || "429" in m || "too many requests" in m || "rate-limit" in m || "rate limit" in m
        }
        private const val CAPTIONS_FILTER = "EgIoAQ%253D%253D"

        private fun trackNames(info: JSONObject, name: String) =
            info.optJSONObject(name)?.keys()?.asSequence()?.toList().orEmpty()

        /** The videos in yt-dlp's flat search result (a playlist with an "entries" array). */
        fun parseSearchResults(json: String): List<VideoResult> {
            val entries = JSONObject(json).optJSONArray("entries") ?: return emptyList()
            return (0 until entries.length()).mapNotNull { i ->
                val e = entries.optJSONObject(i) ?: return@mapNotNull null
                val id = e.optString("id")
                val title = e.optString("title")
                if (id.isBlank() || title.isBlank()) return@mapNotNull null
                VideoResult(
                    id = id,
                    title = title,
                    channel = e.optString("channel").ifBlank { e.optString("uploader") },
                    durationSeconds = e.optDouble("duration", 0.0).toLong().takeIf { it > 0 },
                    url = e.optString("url").ifBlank { "https://www.youtube.com/watch?v=$id" },
                )
            }
        }

        /**
         * Subtitle status from a video's yt-dlp info JSON. YouTube translates titles and
         * subtitles and even dubs audio by machine, so those say nothing about what is really
         * spoken. What does: the "-orig" speech-recognition track (it exists only where there is
         * speech, and is for the original audio), and the video's own "language" (its original
         * audio track). A dubbed video keeps both in its original language; a music video with
         * captions has neither, only lots of translated subtitle tracks.
         */
        internal fun subtitleStatus(info: JSONObject, language: Language): SubtitleStatus {
            val automatic = trackNames(info, "automatic_captions")
            val spoken = automatic.filter { it.endsWith("-orig", ignoreCase = true) }.map { it.dropLast(5) } +
                listOfNotNull(info.optString("language").takeIf { it.isNotBlank() && it != "null" })
            val studyManual = pickTrack(trackNames(info, "subtitles"), emptyList(), language)
            if (spoken.isNotEmpty()) {
                if (spoken.none { language.isCaptionCode(it) }) return SubtitleStatus.OTHER_LANGUAGE
                return if (studyManual != null) SubtitleStatus.MANUAL else SubtitleStatus.AUTO
            }
            return if (studyManual != null) SubtitleStatus.NO_SPEECH else SubtitleStatus.NONE
        }
    }

    /**
     * Runs [request] as process [processId] on the IO thread. A cancelled coroutine can't stop the
     * blocking yt-dlp call by itself, so cancelling also kills the process.
     */
    private suspend fun runCancellable(processId: String, request: YoutubeDLRequest): String = coroutineScope {
        val killer = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                // Harmless when the process has finished already.
                YoutubeDL.getInstance().destroyProcessById(processId)
            }
        }
        try {
            withContext(Dispatchers.IO) { run(request, processId = processId) }
        } finally {
            killer.cancel()
        }
    }

    private fun run(request: YoutubeDLRequest, processId: String? = null, onProgress: (Float) -> Unit = {}): String =
        try {
            YoutubeDL.getInstance().execute(request, processId) { progress, _, _ -> onProgress(progress) }.out
        } catch (e: Exception) {
            // yt-dlp's last error line is the useful part of its output.
            val reason = e.message.orEmpty().lines().lastOrNull { it.contains("ERROR") } ?: e.message
            if (isRateLimit(reason)) throw RateLimitedException(reason.orEmpty(), e)
            throw IOException(reason ?: "yt-dlp failed", e)
        }
}

