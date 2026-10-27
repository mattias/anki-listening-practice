package dev.lovkvist.listening.data.media

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** No synced lyrics fit the video: the song may be in LRCLIB under another spelling. */
class NoLyricsException(message: String) : IOException(message)

/** Lyrics with line times (LRC), for one recording. */
data class Lyrics(
    val trackName: String,
    val artistName: String,
    /** The recording's length, to tell versions apart; null if LRCLIB doesn't say. */
    val durationSeconds: Double?,
    /** In LRC format: `[mm:ss.xx]` before each line. */
    val synced: String,
    /** How much longer the video is than this recording (0 when they fit); lyrics of a shorter cut are off by about that much. */
    val lengthDiffSeconds: Double = 0.0,
)

/**
 * Finds synced lyrics at LRCLIB (lrclib.net: a free, open lyrics database; no account or key).
 * A song is looked up by the title of its video, and the match must be about as long as the video,
 * so a live version isn't paired with the studio version's times.
 */
@Singleton
class LrcLib @Inject constructor() {
    /** The best lyrics for a video called [title] by [channel] that lasts [durationSeconds], or null. */
    suspend fun find(title: String, channel: String, durationSeconds: Long?, track: String = "", artist: String = "", query: String = ""): Lyrics? {
        var failure: IOException? = null
        var answered = false
        var loose: Lyrics? = null
        // A search the user wrote goes first: LRCLIB often has a song under another spelling (romaji, say).
        val all = (listOf(query.trim()).filter { it.isNotEmpty() } + queries(title, channel, track, artist)).distinct()
        for (q in all) {
            val results = try {
                searchWithRetry(q)
            } catch (e: IOException) {
                // One query failing doesn't end the lookup; the next may get through.
                failure = e
                continue
            }
            answered = true
            val hint = "$title $channel $track $artist"
            pickBest(results, durationSeconds, hint)?.let { return it }
            if (loose == null) loose = pickLoose(results, durationSeconds, hint)
        }
        // No recording is as long as the video: a music video often has an intro. The song is right but
        // its times are off by about the difference, which the lyrics timing can put right.
        loose?.let { return it }
        // Only blame the service if it never answered: otherwise there simply are no lyrics.
        if (!answered && failure != null) throw failure
        return null
    }

    /** LRCLIB now and then answers 502/503/504 or 429 when busy: wait a little and ask again. */
    private suspend fun searchWithRetry(query: String): List<Lyrics> {
        var attempt = 0
        while (true) {
            try {
                return search(query)
            } catch (e: BusyException) {
                if (++attempt >= MAX_ATTEMPTS) throw IOException("LRCLIB is busy (HTTP ${e.code}); try again in a minute")
                delay(RETRY_PAUSE_MS * attempt)
            }
        }
    }

    private class BusyException(val code: Int) : IOException("LRCLIB: HTTP $code")

    private suspend fun search(query: String): List<Lyrics> = withContext(Dispatchers.IO) {
        val connection = URL("$BASE_URL/search?q=${Uri.encode(query)}").openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        try {
            val code = connection.responseCode
            if (code == 429 || code in 500..599) throw BusyException(code)
            if (code !in 200..299) throw IOException("LRCLIB: HTTP $code")
            parseResults(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val BASE_URL = "https://lrclib.net/api"
        private const val MAX_QUERIES = 6
        private const val MAX_ATTEMPTS = 4
        private const val RETRY_PAUSE_MS = 1500L
        private const val USER_AGENT = "anki-listening-practice (https://github.com/mattias/anki-listening-practice)"

        /** How far a recording's length may differ from the video's. */
        private const val DURATION_TOLERANCE_SECONDS = 7.0

        /** For music videos that are longer than the song (an intro, a skit). */
        private const val LOOSE_TOLERANCE_SECONDS = 90.0

        private val quoted = Regex("""[「『"“](.+?)[」』"”]""")
        private val inBrackets = Regex("""\(([^)]*)\)|\[([^]]*)]|【([^】]*)】|（([^）]*)）""")
        private val partSeparator = Regex("""\s*[-–—|｜/／]\s*""")
        private val punctuation = Regex("""[!?！？。、,.:;"'“”「」『』]""")
        private val brackets = Regex("""\([^)]*\)|\[[^]]*]|【[^】]*】|（[^）]*）""")
        private val noise = Regex(
            """\b(official|music\s*video|lyrics?|lyric\s*video|audio|video|mv|hd|4k|visuali[sz]er|full\s*version)\b""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * What to search for, best guess first: the music metadata, a title in quotation marks (Japanese titles are
         * often `Artist「Song」`), the title without brackets and "official video" noise, and the
         * title as it is.
         */
        fun queries(title: String, channel: String, track: String = "", artist: String = ""): List<String> {
            // Official uploads carry the song and artist as music metadata: the surest search.
            val metadata = if (track.isNotBlank()) "$track $artist".trim() else null
            val inQuotes = quoted.find(title)?.groupValues?.get(1)?.trim()
            val cleaned = title.replace(brackets, " ").replace(noise, " ").replace(Regex("""\s+-\s+|\s*[|｜/]\s*"""), " ")
                .replace(Regex("""\s+"""), " ").trim()
            val withArtist = if (cleaned.isNotBlank() && channel.isNotBlank() && !cleaned.contains(channel, ignoreCase = true)) "$cleaned $channel" else null
            // The search matches poorly when a Japanese title comes with an artist, and a romanized name is often
            // in brackets: "Creepy Nuts - 二度寝(Nidone)" is in LRCLIB as "Nidone". So the bracketed text and each
            // part of the title are searched too; the recording's length decides which hit is right.
            val bracketed = inBrackets.findAll(title).flatMap { m -> m.groupValues.drop(1).filter { it.isNotBlank() } }
                .map { it.replace(noise, " ").replace(Regex("""\s+"""), " ").trim() }.filter { it.length > 1 }.toList()
            val parts = title.replace(brackets, " ").replace(noise, " ").split(partSeparator)
                .map { it.replace(punctuation, " ").replace(Regex("""\s+"""), " ").trim() }
                .filter { it.isNotBlank() && !it.equals(channel, ignoreCase = true) }.reversed()
            return (listOfNotNull(metadata, inQuotes, cleaned) + bracketed + parts + listOfNotNull(withArtist, title.trim()))
                .filter { it.isNotBlank() }.distinct().take(MAX_QUERIES)
        }

        /**
         * The match with synced lyrics whose length is within the tolerance of [durationSeconds]; among those,
         * one whose artist or title is named in [hint] (the video's title and channel) wins, then the
         * closest length. A search can find other songs with the same name, so the length alone isn't enough.
         */
        fun pickBest(results: List<Lyrics>, durationSeconds: Long?, hint: String = ""): Lyrics? {
            val withTimes = results.filter { it.synced.isNotBlank() }
            val fits = if (durationSeconds == null) {
                withTimes.map { it to 0.0 }
            } else {
                withTimes
                    .mapNotNull { r -> r.durationSeconds?.let { r to abs(it - durationSeconds) } }
                    .filter { it.second <= DURATION_TOLERANCE_SECONDS }
            }
            return fits.minWithOrNull(compareBy({ !artistNamed(it.first, hint) }, { !named(it.first, hint) }, { it.second }))?.first
        }

        private fun artistNamed(r: Lyrics, hint: String) = r.artistName.length > 1 && r.artistName.lowercase() in hint.lowercase()

        private fun named(r: Lyrics, hint: String) =
            artistNamed(r, hint) || (r.trackName.length > 1 && r.trackName.lowercase() in hint.lowercase())

        /**
         * When nothing fits the video's length: a recording named in [hint] (the video's title and channel) that
         * is up to [LOOSE_TOLERANCE_SECONDS] shorter or longer, the closest first, with the difference noted.
         */
        fun pickLoose(results: List<Lyrics>, durationSeconds: Long?, hint: String): Lyrics? {
            if (durationSeconds == null) return null
            return results
                .filter { it.synced.isNotBlank() && it.durationSeconds != null && named(it, hint) }
                .filter { abs(it.durationSeconds!! - durationSeconds) <= LOOSE_TOLERANCE_SECONDS }
                .minWithOrNull(compareBy({ !artistNamed(it, hint) }, { abs(it.durationSeconds!! - durationSeconds) }))
                ?.let { it.copy(lengthDiffSeconds = durationSeconds - it.durationSeconds!!) }
        }

        /** LRCLIB's search answer: an array of tracks; instrumentals and tracks without synced lyrics have none. */
        fun parseResults(json: String): List<Lyrics> {
            val array = JSONArray(json)
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                if (o.optBoolean("instrumental")) return@mapNotNull null
                val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
                Lyrics(
                    trackName = o.optString("trackName").ifBlank { o.optString("name") },
                    artistName = o.optString("artistName"),
                    durationSeconds = o.optDouble("duration", Double.NaN).takeIf { !it.isNaN() && it > 0 },
                    synced = synced,
                )
            }
        }
    }
}
