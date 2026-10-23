package dev.lovkvist.listening.data.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lovkvist.listening.data.Sentence
import dev.lovkvist.listening.text.JapaneseTokenizer
import dev.lovkvist.listening.text.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import javax.inject.Singleton

data class MediaEntry(
    val id: String,
    val name: String,
    val importedAt: Long,
    val chunkCount: Int,
    /** The video/audio: a file in app storage (downloads) or a document the user picked. */
    val media: Uri,
    val sourceUrl: String? = null,
    /** The language of the subtitles; items are practiced while that language is selected. */
    val language: Language = Language.JAPANESE,
)

/**
 * Videos and audio files paired with subtitles, one folder per item:
 * `media/<id>/info.json` and `media/<id>/chunks.json` (plus the audio file for downloads).
 * Picked files stay where they are; the app keeps read access to them.
 */
@Singleton
class MediaLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenizer: JapaneseTokenizer,
) {
    private val root = File(context.filesDir, "media").apply { mkdirs() }

    private val _items = MutableStateFlow(loadItems())
    val items: StateFlow<List<MediaEntry>> = _items.asStateFlow()

    /** Imports a video/audio document the user picked together with a subtitle document. */
    suspend fun importLocal(media: Uri, subtitles: Uri, language: Language): MediaEntry = withContext(Dispatchers.IO) {
        val subName = displayName(subtitles)
        val cues = Subtitles.parse(subName, readText(context.contentResolver.openInputStream(subtitles)?.use { it.readBytes() }
            ?: throw IOException("Could not open $subName"), language))
        // Keep access to the media after restarts; subtitles are copied into chunks.json.
        context.contentResolver.takePersistableUriPermission(media, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        save(newDir(), displayName(media).substringBeforeLast('.'), media, cues, sourceUrl = null, language)
    }

    /** Registers a downloaded audio file (already inside [dir]) with its subtitle file. */
    suspend fun importDownloaded(dir: File, audio: File, subtitles: File, title: String, url: String, language: Language): MediaEntry =
        withContext(Dispatchers.IO) {
            val cues = Subtitles.parse(subtitles.name, readText(subtitles.readBytes(), language))
            save(dir, title, Uri.fromFile(audio), cues, sourceUrl = url, language)
        }

    /** A fresh folder for a new item; also used as the download target. */
    fun newDir(): File = File(root, System.currentTimeMillis().toString()).apply { mkdirs() }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val entry = _items.value.firstOrNull { it.id == id }
        if (entry != null && entry.media.scheme == "content") {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(entry.media, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        File(root, id).deleteRecursively()
        _items.value = loadItems()
    }

    /** All chunks of [id] in playback order; Japanese gets furigana generated from its readings. */
    suspend fun sentences(id: String): List<Sentence> = withContext(Dispatchers.IO) {
        val entry = _items.value.firstOrNull { it.id == id } ?: throw IOException("That video is no longer imported")
        val chunks = JSONArray(File(File(root, id), CHUNKS_FILE).readText())
        val base = id.toLong() * MAX_CHUNKS
        (0 until chunks.length()).map { i ->
            val c = chunks.getJSONObject(i)
            val text = c.getString("t")
            Sentence(
                // Negative so media ids never collide with Anki note ids.
                id = -(base + i),
                text = Subtitles.joinLines(text, entry.language.unspaced),
                furigana = if (entry.language == Language.JAPANESE) tokenizer.furigana(text) else "",
                translation = "",
                audio = entry.media,
                clip = maxOf(0, c.getLong("s") - PAD_BEFORE_MS)..(c.getLong("e") + PAD_AFTER_MS),
            )
        }
    }

    private fun save(
        dir: File,
        name: String,
        media: Uri,
        cues: List<SubtitleCue>,
        sourceUrl: String?,
        language: Language,
    ): MediaEntry {
        if (cues.size >= MAX_CHUNKS) throw IOException("Too many subtitle lines (${cues.size})")
        val chunks = JSONArray()
        cues.forEach { chunks.put(JSONObject().put("s", it.startMs).put("e", it.endMs).put("t", it.text)) }
        File(dir, CHUNKS_FILE).writeText(chunks.toString())
        val entry = MediaEntry(dir.name, name, dir.name.toLong(), cues.size, media, sourceUrl, language)
        File(dir, INFO_FILE).writeText(
            JSONObject()
                .put("name", entry.name)
                .put("importedAt", entry.importedAt)
                .put("chunkCount", entry.chunkCount)
                .put("media", entry.media.toString())
                .put("sourceUrl", entry.sourceUrl)
                .put("language", entry.language.code)
                .toString(),
        )
        _items.value = loadItems()
        return entry
    }

    private fun loadItems(): List<MediaEntry> =
        root.listFiles().orEmpty().mapNotNull { dir ->
            val info = File(dir, INFO_FILE).takeIf { it.isFile } ?: return@mapNotNull null
            runCatching {
                val j = JSONObject(info.readText())
                MediaEntry(
                    id = dir.name,
                    name = j.getString("name"),
                    importedAt = j.getLong("importedAt"),
                    chunkCount = j.getInt("chunkCount"),
                    media = Uri.parse(j.getString("media")),
                    sourceUrl = j.optString("sourceUrl").takeIf { it.isNotEmpty() && it != "null" },
                    // Items from before other languages existed are Japanese.
                    language = Language.fromCode(j.optString("language").ifEmpty { null }),
                )
            }.getOrNull()
        }.sortedByDescending { it.importedAt }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "media"

    /**
     * Subtitles come as UTF-8, UTF-16 (with BOM, common for .ass) or the language's legacy
     * encoding, e.g. Shift_JIS for older Japanese fansubs.
     */
    private fun readText(bytes: ByteArray, language: Language): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE).drop(1)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE).drop(1)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            val legacy = runCatching { Charset.forName(language.legacyCharset) }.getOrDefault(Charsets.ISO_8859_1)
            String(bytes, legacy)
        }
    }

    companion object {
        private const val INFO_FILE = "info.json"
        private const val CHUNKS_FILE = "chunks.json"
        private const val MAX_CHUNKS = 100_000L

        // Subtitle timings are often tight; a little padding avoids clipped first/last syllables.
        private const val PAD_BEFORE_MS = 150L
        private const val PAD_AFTER_MS = 250L
    }
}
