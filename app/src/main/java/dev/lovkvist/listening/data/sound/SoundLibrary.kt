package dev.lovkvist.listening.data.sound

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lovkvist.listening.data.Sentence
import dev.lovkvist.listening.text.JapaneseTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The sound training sentences the app has fetched from the service: each with its audio file in app
 * storage, so practicing works without the computer.
 */
@Singleton
class SoundLibrary @Inject constructor(
    @ApplicationContext context: Context,
    private val tokenizer: JapaneseTokenizer,
) {
    private val root = File(context.filesDir, "sound").apply { mkdirs() }
    private val index = File(root, "index.json")

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<SoundItem>> = _items.asStateFlow()

    fun audioFile(id: String) = File(root, "$id.mp3")

    /** The audio of an item that was already fetched, or null. */
    fun hasAudio(id: String) = audioFile(id).let { it.isFile && it.length() > 0 }

    /** The file an item's audio is downloaded into; [add] registers the item once it is there. */
    fun partFile(id: String) = File(root, "$id.part")

    suspend fun add(item: SoundItem) = withContext(Dispatchers.IO) {
        val part = partFile(item.id)
        if (part.isFile) part.renameTo(audioFile(item.id))
        if (_items.value.none { it.id == item.id }) {
            _items.value = _items.value + item.copy(createdAt = System.currentTimeMillis())
            save()
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        audioFile(id).delete()
        _items.value = _items.value.filterNot { it.id == id }
        save()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        _items.value.forEach { audioFile(it.id).delete() }
        _items.value = emptyList()
        save()
    }

    /** The items as practice sentences, with readings for the furigana. */
    suspend fun sentences(): List<Sentence> = withContext(Dispatchers.IO) {
        _items.value.filter { hasAudio(it.id) }.map { item ->
            Sentence(
                id = sentenceId(item.id),
                text = item.text,
                furigana = tokenizer.furigana(item.text),
                translation = item.translation,
                audio = Uri.fromFile(audioFile(item.id)),
                spoken = item.spoken.takeIf { it != item.text },
                tags = (listOf(item.target) + item.phenomena).toSet(),
            )
        }
    }

    private fun save() {
        val array = JSONArray()
        for (it in _items.value) {
            array.put(
                JSONObject()
                    .put("id", it.id).put("text", it.text).put("spoken", it.spoken).put("translation", it.translation)
                    .put("target", it.target).put("phenomena", JSONArray(it.phenomena)).put("matches", JSONArray(it.matches))
                    .put("voice", it.voice).put("createdAt", it.createdAt),
            )
        }
        index.writeText(array.toString())
    }

    private fun load(): List<SoundItem> = runCatching {
        val array = JSONArray(index.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            SoundClient.parseItem(o.put("audio_ready", true)).copy(createdAt = o.optLong("createdAt"))
        }
    }.getOrDefault(emptyList())

    companion object {
        /**
         * A negative id from the item's id, so cooldowns and the blacklist can share their table with Anki
         * notes (positive) and subtitle lines of videos (negative, far larger in size).
         */
        fun sentenceId(itemId: String): Long = -(SOUND_ID_BASE + itemId.take(11).toLong(16))

        private const val SOUND_ID_BASE = 1_000_000_000_000_000L
    }
}
