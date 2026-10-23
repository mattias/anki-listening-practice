package dev.lovkvist.listening.data.media

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * Downloads a YouTube video's audio and Japanese subtitles with yt-dlp (via youtubedl-android)
 * and adds them to the [MediaLibrary]. Manual subtitles are preferred over auto-generated ones.
 */
@Singleton
class YouTubeImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: MediaLibrary,
) {
    private val setupLock = Mutex()
    private var ready = false

    /** Japanese subtitle track chosen for a video. */
    private data class Track(val lang: String, val automatic: Boolean)

    suspend fun import(url: String, onStatus: (String) -> Unit): MediaEntry = withContext(Dispatchers.IO) {
        setUp(onStatus)

        onStatus("Looking for Japanese subtitles…")
        val info = JSONObject(run(YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--skip-download")
            addOption("--no-playlist")
        }))
        val title = info.optString("title").ifBlank { "YouTube video" }
        val track = pickTrack(info) ?: throw IOException("\"$title\" has no Japanese subtitles.")

        val dir = library.newDir()
        try {
            onStatus("Downloading audio and subtitles…")
            run(YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                // A single audio-only stream needs no merging, so no ffmpeg.
                addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                addOption("-o", File(dir, "audio.%(ext)s").absolutePath)
                addOption(if (track.automatic) "--write-auto-subs" else "--write-subs")
                addOption("--sub-langs", track.lang)
                // Auto captions in VTT repeat each line while scrolling; json3 has clean events.
                addOption("--sub-format", if (track.automatic) "json3" else "vtt/srt/best")
            }) { progress -> onStatus("Downloading… ${progress.toInt().coerceIn(0, 100)}%") }

            val files = dir.listFiles().orEmpty()
            val subs = files.firstOrNull { it.name.startsWith("audio.${track.lang}.") }
                ?: throw IOException("yt-dlp didn't save the subtitles")
            val audio = files.firstOrNull { it.name.startsWith("audio.") && it != subs && !it.name.endsWith(".part") }
                ?: throw IOException("yt-dlp didn't save the audio")
            onStatus("Reading subtitles…")
            library.importDownloaded(dir, audio, subs, title + if (track.automatic) " (auto subs)" else "", url)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    /** Unpacks Python/yt-dlp on first use and updates yt-dlp, since YouTube changes often. */
    private suspend fun setUp(onStatus: (String) -> Unit) = setupLock.withLock {
        if (ready) return@withLock
        onStatus("Preparing yt-dlp (first time only)…")
        YoutubeDL.getInstance().init(context)
        onStatus("Updating yt-dlp…")
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

    private fun pickTrack(info: JSONObject): Track? {
        fun japanese(key: String) = info.optJSONObject(key)?.keys()?.asSequence()
            ?.filter { it == "ja" || it.startsWith("ja-") }?.toList().orEmpty()

        japanese("subtitles").let { manual ->
            if (manual.isNotEmpty()) return Track(manual.firstOrNull { it == "ja" } ?: manual.first(), automatic = false)
        }
        // For auto captions, "ja-orig" is speech recognition on the original audio; others are translations.
        val auto = japanese("automatic_captions")
        val lang = auto.firstOrNull { it == "ja-orig" } ?: auto.firstOrNull { it == "ja" } ?: return null
        return Track(lang, automatic = true)
    }
}
