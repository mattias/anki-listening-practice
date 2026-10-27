package dev.lovkvist.listening.data.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lovkvist.listening.text.Language
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the app has already found out about videos' subtitles, per video and language, kept for
 * a month. Looking a video up costs several requests to YouTube, and a video's subtitles seldom
 * change, so the same video is never looked up again and again (and YouTube is asked less).
 * Only certain answers are kept, never [SubtitleStatus.UNKNOWN]. (The file name carries a version:
 * when the way statuses are worked out changes, the old answers are left behind.)
 */
@Singleton
class SubtitleCache internal constructor(private val file: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "subtitle-status-4.json"))

    private var entries: MutableMap<String, Pair<SubtitleStatus, Long>>? = null

    @Synchronized
    fun get(videoId: String, language: Language, now: Long = System.currentTimeMillis()): SubtitleStatus? {
        val (status, at) = load()[key(videoId, language)] ?: return null
        return status.takeIf { now - at < MAX_AGE_MS }
    }

    @Synchronized
    fun put(videoId: String, language: Language, status: SubtitleStatus, now: Long = System.currentTimeMillis()) {
        if (status == SubtitleStatus.UNKNOWN) return
        val all = load()
        all[key(videoId, language)] = status to now
        // Old entries go whenever the file is written, so it doesn't grow without end.
        all.entries.removeAll { now - it.value.second >= MAX_AGE_MS }
        runCatching {
            val json = JSONObject()
            for ((k, v) in all) json.put(k, JSONObject().put("s", v.first.name).put("t", v.second))
            file.writeText(json.toString())
        }
    }

    private fun key(videoId: String, language: Language) = "$videoId:${language.code}"

    private fun load(): MutableMap<String, Pair<SubtitleStatus, Long>> =
        entries ?: HashMap<String, Pair<SubtitleStatus, Long>>().also { map ->
            runCatching {
                if (!file.isFile) return@runCatching
                val json = JSONObject(file.readText())
                for (k in json.keys()) {
                    val e = json.getJSONObject(k)
                    val status = runCatching { SubtitleStatus.valueOf(e.getString("s")) }.getOrNull() ?: continue
                    map[k] = status to e.getLong("t")
                }
            }
            entries = map
        }

    private companion object {
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}
