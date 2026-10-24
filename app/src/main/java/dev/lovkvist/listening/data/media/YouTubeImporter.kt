package dev.lovkvist.listening.data.media

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lovkvist.listening.R
import dev.lovkvist.listening.l10n.Strings
import dev.lovkvist.listening.l10n.displayName
import dev.lovkvist.listening.text.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

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
        fun keys(name: String) = info.optJSONObject(name)?.keys()?.asSequence()?.toList().orEmpty()
        val track = pickTrack(keys("subtitles"), keys("automatic_captions"), language)
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

    private fun run(request: YoutubeDLRequest, onProgress: (Float) -> Unit = {}): String =
        try {
            YoutubeDL.getInstance().execute(request, null) { progress, _, _ -> onProgress(progress) }.out
        } catch (e: Exception) {
            // yt-dlp's last error line is the useful part of its output.
            val reason = e.message.orEmpty().lines().lastOrNull { it.contains("ERROR") } ?: e.message
            throw IOException(reason ?: "yt-dlp failed", e)
        }
}

