package dev.lovkvist.listening.data.anki

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class AnkiSentence(
    val noteId: Long,
    val text: String,
    val furigana: String,
    val translation: String,
    val audioFile: File,
)

data class ImportedDeck(
    val id: String,
    val name: String,
    val importedAt: Long,
    val noteCount: Int,
    val mediaCount: Int,
)

class AnkiReadException(message: String) : Exception(message)

/**
 * Anki exports (.apkg / .colpkg) imported into app storage, one folder per import:
 * `decks/<id>/collection.db`, `decks/<id>/media/<file>`, `decks/<id>/info.json`.
 * The original AnkiDroid collection is never touched.
 */
@Singleton
class DeckLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val root = File(context.filesDir, "decks").apply { mkdirs() }

    private val _decks = MutableStateFlow(loadDecks())
    val decks: StateFlow<List<ImportedDeck>> = _decks.asStateFlow()

    /** Number of zip entries processed by the running import, or null when idle. */
    private val _importProgress = MutableStateFlow<Int?>(null)
    val importProgress: StateFlow<Int?> = _importProgress.asStateFlow()

    suspend fun import(uri: Uri): ImportedDeck = withContext(Dispatchers.IO) {
        val id = System.currentTimeMillis().toString()
        val dir = File(root, id)
        _importProgress.value = 0
        try {
            val result = context.contentResolver.openInputStream(uri)?.use { input ->
                ApkgUnpacker().unpack(input, dir) { _importProgress.value = it }
            } ?: throw IOException("Could not open the selected file")

            val noteCount = openCollection(result.collection) { db ->
                db.rawQuery("SELECT COUNT(*) FROM notes", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            }
            val deck = ImportedDeck(id, displayName(uri), id.toLong(), noteCount, result.mediaCount)
            File(dir, INFO_FILE).writeText(
                JSONObject()
                    .put("name", deck.name)
                    .put("importedAt", deck.importedAt)
                    .put("noteCount", deck.noteCount)
                    .put("mediaCount", deck.mediaCount)
                    .toString(),
            )
            _decks.value = loadDecks()
            deck
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        } finally {
            _importProgress.value = null
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        _decks.value = loadDecks()
    }

    suspend fun readSentences(mapping: FieldMapping): List<AnkiSentence> = withContext(Dispatchers.IO) {
        val decks = _decks.value
        if (decks.isEmpty()) throw AnkiReadException("Import an Anki deck (.apkg) or collection (.colpkg) in Settings")
        val sentences = decks.flatMap { deck ->
            val dir = File(root, deck.id)
            openCollection(File(dir, ApkgUnpacker.COLLECTION_FILE)) { db ->
                loadNoteTypes(db)
                    .filter { it.name.contains(mapping.noteTypeMatch.trim(), ignoreCase = true) }
                    .flatMap { readNotes(db, it, mapping, File(dir, "media")) }
            }
        }
        if (sentences.isEmpty()) {
            throw AnkiReadException(
                "No notes with sentence audio found for note type \"${mapping.noteTypeMatch}\". " +
                    "Check the preset, and that the export included media.",
            )
        }
        // The same note can appear in several imports; keep one.
        sentences.distinctBy { it.noteId }
    }

    private fun loadDecks(): List<ImportedDeck> =
        root.listFiles().orEmpty()
            .mapNotNull { dir ->
                val info = File(dir, INFO_FILE).takeIf { it.isFile } ?: return@mapNotNull null
                runCatching {
                    val j = JSONObject(info.readText())
                    ImportedDeck(
                        id = dir.name,
                        name = j.getString("name"),
                        importedAt = j.getLong("importedAt"),
                        noteCount = j.optInt("noteCount"),
                        mediaCount = j.optInt("mediaCount"),
                    )
                }.getOrNull()
            }
            .sortedBy { it.importedAt }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "Anki export"

    private fun <T> openCollection(file: File, block: (SQLiteDatabase) -> T): T {
        // Read-write only so a WAL-mode file can open; it's our private copy.
        // NO_LOCALIZED_COLLATORS stops Android from adding its android_metadata table.
        val db = SQLiteDatabase.openDatabase(
            file.path,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        return try {
            block(db)
        } finally {
            db.close()
        }
    }

    private data class NoteType(val id: Long, val name: String, val fields: List<String>)

    private fun loadNoteTypes(db: SQLiteDatabase): List<NoteType> =
        if (hasTable(db, "notetypes")) loadModernNoteTypes(db) else loadLegacyNoteTypes(db)

    /**
     * Newer schema: note types and fields have their own tables. Their name columns use Anki's
     * custom "unicase" collation, which Android's SQLite lacks, so avoid the indexes built on it.
     */
    private fun loadModernNoteTypes(db: SQLiteDatabase): List<NoteType> {
        val fields = mutableMapOf<Long, MutableList<Pair<Int, String>>>()
        db.rawQuery("SELECT ntid, ord, name FROM fields NOT INDEXED", null).use { c ->
            while (c.moveToNext()) {
                fields.getOrPut(c.getLong(0)) { mutableListOf() } += c.getInt(1) to c.getString(2)
            }
        }
        val result = mutableListOf<NoteType>()
        db.rawQuery("SELECT id, name FROM notetypes NOT INDEXED", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val ordered = fields[id].orEmpty().sortedBy { it.first }.map { it.second }
                result += NoteType(id, c.getString(1), ordered)
            }
        }
        return result
    }

    /** Older schema: note types are a JSON blob in col.models. */
    private fun loadLegacyNoteTypes(db: SQLiteDatabase): List<NoteType> {
        val json = db.rawQuery("SELECT models FROM col", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: return emptyList()
        val models = JSONObject(json)
        return models.keys().asSequence().map { key ->
            val m = models.getJSONObject(key)
            val flds = m.getJSONArray("flds")
            val names = (0 until flds.length())
                .map { flds.getJSONObject(it) }
                .sortedBy { it.getInt("ord") }
                .map { it.getString("name") }
            NoteType(m.getLong("id"), m.getString("name"), names)
        }.toList()
    }

    private fun readNotes(
        db: SQLiteDatabase,
        noteType: NoteType,
        mapping: FieldMapping,
        mediaDir: File,
    ): List<AnkiSentence> {
        fun indexOf(name: String) =
            if (name.isBlank()) -1 else noteType.fields.indexOfFirst { it.equals(name.trim(), ignoreCase = true) }

        val sentenceIdx = indexOf(mapping.sentence)
        val audioIdx = indexOf(mapping.audio)
        if (sentenceIdx < 0 || audioIdx < 0) return emptyList()
        val furiganaIdx = indexOf(mapping.furigana)
        val translationIdx = indexOf(mapping.translation)

        val out = mutableListOf<AnkiSentence>()
        db.rawQuery("SELECT id, flds FROM notes WHERE mid = ?", arrayOf(noteType.id.toString())).use { c ->
            while (c.moveToNext()) {
                val values = c.getString(1).split('\u001f')
                fun field(i: Int) = values.getOrNull(i).orEmpty()

                val audioName = AnkiText.soundFile(field(audioIdx)) ?: continue
                val audio = File(mediaDir, audioName)
                val text = AnkiText.stripFurigana(AnkiText.plain(field(sentenceIdx)))
                if (text.isBlank() || !audio.isFile) continue

                out += AnkiSentence(
                    noteId = c.getLong(0),
                    text = text,
                    furigana = AnkiText.plain(field(furiganaIdx)),
                    translation = AnkiText.plain(field(translationIdx)),
                    audioFile = audio,
                )
            }
        }
        return out
    }

    private fun hasTable(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name))
            .use { it.moveToFirst() }

    private companion object {
        const val INFO_FILE = "info.json"
    }
}
