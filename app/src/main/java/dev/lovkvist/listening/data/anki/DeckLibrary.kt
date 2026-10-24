package dev.lovkvist.listening.data.anki

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lovkvist.listening.R
import dev.lovkvist.listening.data.Sentence
import dev.lovkvist.listening.data.settings.SettingsRepository
import dev.lovkvist.listening.l10n.Strings
import dev.lovkvist.listening.l10n.displayName
import dev.lovkvist.listening.text.Language
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

data class ImportedDeck(
    val id: String,
    val name: String,
    val importedAt: Long,
    val noteCount: Int,
    val mediaCount: Int,
    /** Unused media (images, word audio, fonts...) already deleted; see [DeckLibrary.prune]. */
    val pruned: Boolean = false,
    val freedBytes: Long = 0,
    /** Practiced while this language is selected; decks are imported for the selected language. */
    val language: Language = Language.JAPANESE,
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
    private val settings: SettingsRepository,
    private val strings: Strings,
) {
    private val root = File(context.filesDir, "decks").apply { mkdirs() }

    private val _decks = MutableStateFlow(loadDecks())
    val decks: StateFlow<List<ImportedDeck>> = _decks.asStateFlow()

    /** Number of zip entries processed by the running import, or null when idle. */
    private val _importProgress = MutableStateFlow<Int?>(null)
    val importProgress: StateFlow<Int?> = _importProgress.asStateFlow()

    suspend fun import(uri: Uri, language: Language): ImportedDeck = withContext(Dispatchers.IO) {
        val id = System.currentTimeMillis().toString()
        val dir = File(root, id)
        _importProgress.value = 0
        try {
            val result = context.contentResolver.openInputStream(uri)?.use { input ->
                ApkgUnpacker().unpack(input, dir) { _importProgress.value = it }
            } ?: throw IOException(strings(R.string.could_not_open_file))

            val noteCount = openCollection(result.collection) { db ->
                db.rawQuery("SELECT COUNT(*) FROM notes", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            }
            val unpruned = ImportedDeck(id, displayName(uri), id.toLong(), noteCount, result.mediaCount, language = language)
            val deck = prune(dir, unpruned)
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

    /**
     * The target word of every card in the decks of [language] (the preset's word field, e.g.
     * Expression), with HTML and furigana removed. Used as extra known words.
     */
    suspend fun readWords(mapping: FieldMapping, language: Language): Set<String> = withContext(Dispatchers.IO) {
        if (mapping.word.isBlank()) return@withContext emptySet()
        val words = HashSet<String>()
        for (deck in _decks.value.filter { it.language == language }) {
            openCollection(File(File(root, deck.id), ApkgUnpacker.COLLECTION_FILE)) { db ->
                for (type in loadNoteTypes(db)) {
                    if (!type.name.contains(mapping.noteTypeMatch.trim(), ignoreCase = true)) continue
                    val idx = type.indexOf(mapping.word)
                    if (idx < 0) continue
                    db.rawQuery("SELECT flds FROM notes WHERE mid = ?", arrayOf(type.id.toString())).use { c ->
                        while (c.moveToNext()) {
                            val values = c.getString(0).split('\u001f')
                            val field = values.getOrNull(idx) ?: continue
                            plainText(field, language).trim().takeIf { it.isNotEmpty() }?.let(words::add)
                        }
                    }
                }
            }
        }
        words
    }

    suspend fun readSentences(mapping: FieldMapping, language: Language): List<Sentence> = withContext(Dispatchers.IO) {
        if (_decks.value.none { it.language == language }) {
            throw AnkiReadException(strings(R.string.no_deck, language.displayName()))
        }
        // Imports made before pruning existed get cleaned up once.
        if (_decks.value.any { !it.pruned }) {
            _decks.value.filterNot { it.pruned }.forEach { prune(File(root, it.id), it) }
            _decks.value = loadDecks()
        }
        val decks = _decks.value.filter { it.language == language }
        val sentences = decks.flatMap { deck ->
            val dir = File(root, deck.id)
            openCollection(File(dir, ApkgUnpacker.COLLECTION_FILE)) { db ->
                loadNoteTypes(db)
                    .filter { it.name.contains(mapping.noteTypeMatch.trim(), ignoreCase = true) }
                    .flatMap { readNotes(db, it, mapping, language, File(dir, "media")) }
            }
        }
        if (sentences.isEmpty()) {
            throw AnkiReadException(strings(R.string.no_notes, mapping.noteTypeMatch))
        }
        // The same note can appear in several imports; keep one.
        sentences.distinctBy { it.id }
    }

    /**
     * Deletes every media file that isn't sentence audio, i.e. not referenced by a [sound:...]
     * tag in a field that any preset maps as its audio field, in any language. Images, word
     * audio, fonts and unused files go. If no note type has such a field, nothing is deleted, so an unusual
     * note type never loses its audio. Saves the result in info.json.
     */
    private suspend fun prune(dir: File, deck: ImportedDeck): ImportedDeck {
        val audioFields = settings.allMappings()
            .map { it.audio.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        val mediaDir = File(dir, "media")

        val keep = openCollection(File(dir, ApkgUnpacker.COLLECTION_FILE)) { db ->
            val types = loadNoteTypes(db).mapNotNull { type ->
                val idx = type.fields.indices.filter { type.fields[it].lowercase() in audioFields }
                if (idx.isEmpty()) null else type to idx
            }
            if (types.isEmpty()) return@openCollection null
            val names = HashSet<String>()
            for ((type, idx) in types) {
                db.rawQuery("SELECT flds FROM notes WHERE mid = ?", arrayOf(type.id.toString())).use { c ->
                    while (c.moveToNext()) {
                        val values = c.getString(0).split('\u001f')
                        idx.forEach { i -> values.getOrNull(i)?.let { names += AnkiText.soundFiles(it) } }
                    }
                }
            }
            names
        }

        var freed = 0L
        var kept = deck.mediaCount
        if (keep != null) {
            kept = 0
            mediaDir.listFiles().orEmpty().forEach { f ->
                if (f.name in keep) {
                    kept++
                } else {
                    freed += f.length()
                    f.delete()
                }
            }
        }
        val result = deck.copy(mediaCount = kept, pruned = true, freedBytes = deck.freedBytes + freed)
        writeInfo(dir, result)
        return result
    }

    private fun writeInfo(dir: File, deck: ImportedDeck) {
        File(dir, INFO_FILE).writeText(
            JSONObject()
                .put("name", deck.name)
                .put("importedAt", deck.importedAt)
                .put("noteCount", deck.noteCount)
                .put("mediaCount", deck.mediaCount)
                .put("pruned", deck.pruned)
                .put("freedBytes", deck.freedBytes)
                .put("language", deck.language.code)
                .toString(),
        )
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
                        pruned = j.optBoolean("pruned"),
                        freedBytes = j.optLong("freedBytes"),
                    ).let { deck ->
                        val code = j.optString("language")
                        if (code.isNotEmpty()) {
                            deck.copy(language = Language.fromCode(code))
                        } else {
                            // Imported before decks had a language: decide once and remember it.
                            deck.copy(language = guessLanguage(dir)).also { writeInfo(dir, it) }
                        }
                    }
                }.getOrNull()
            }
            .sortedBy { it.importedAt }

    /**
     * The language most of a deck's cards name in a field called Language (Anki Miner Note's),
     * or Japanese, the only language before there were others.
     */
    private fun guessLanguage(dir: File): Language {
        val counts = HashMap<Language, Int>()
        runCatching {
            openCollection(File(dir, ApkgUnpacker.COLLECTION_FILE)) { db ->
                for (type in loadNoteTypes(db)) {
                    val idx = type.indexOf("Language")
                    if (idx < 0) continue
                    db.rawQuery("SELECT flds FROM notes WHERE mid = ?", arrayOf(type.id.toString())).use { c ->
                        while (c.moveToNext()) {
                            val tag = AnkiText.plain(c.getString(0).split('\u001f').getOrNull(idx).orEmpty())
                            if (tag.isBlank()) continue
                            Language.entries.firstOrNull { it.matchesTag(tag) }?.let { counts.merge(it, 1, Int::plus) }
                        }
                    }
                }
            }
        }
        return counts.maxByOrNull { it.value }?.key ?: Language.JAPANESE
    }

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

    private data class NoteType(val id: Long, val name: String, val fields: List<String>) {
        /** Index of the field named [name] (case-insensitive), or -1 if blank or missing. */
        fun indexOf(name: String) =
            if (name.isBlank()) -1 else fields.indexOfFirst { it.equals(name.trim(), ignoreCase = true) }
    }

    /** Field text without HTML, and without bracket readings in languages that use them. */
    private fun plainText(field: String, language: Language): String =
        AnkiText.plain(field).let { if (language.reading != null) AnkiText.stripFurigana(it) else it }

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
        language: Language,
        mediaDir: File,
    ): List<Sentence> {
        val sentenceIdx = noteType.indexOf(mapping.sentence)
        val audioIdx = noteType.indexOf(mapping.audio)
        if (sentenceIdx < 0 || audioIdx < 0) return emptyList()
        val furiganaIdx = noteType.indexOf(mapping.furigana)
        val translationIdx = noteType.indexOf(mapping.translation)

        val out = mutableListOf<Sentence>()
        db.rawQuery("SELECT id, flds FROM notes WHERE mid = ?", arrayOf(noteType.id.toString())).use { c ->
            while (c.moveToNext()) {
                val values = c.getString(1).split('\u001f')
                fun field(i: Int) = values.getOrNull(i).orEmpty()
                val audioName = AnkiText.soundFile(field(audioIdx)) ?: continue
                val audio = File(mediaDir, audioName)
                val text = plainText(field(sentenceIdx), language)
                if (text.isBlank() || !audio.isFile) continue

                out += Sentence(
                    id = c.getLong(0),
                    text = text,
                    furigana = AnkiText.plain(field(furiganaIdx)),
                    translation = AnkiText.plain(field(translationIdx)),
                    audio = Uri.fromFile(audio),
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
