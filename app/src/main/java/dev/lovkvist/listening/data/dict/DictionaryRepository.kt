package dev.lovkvist.listening.data.dict

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lovkvist.listening.text.Language
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** An installed dictionary. */
data class InstalledDictionary(
    val id: Long,
    val title: String,
    val revision: String,
    val sourceId: String?,
    val language: Language,
    val updatable: Boolean,
    val autoUpdate: Boolean,
    val enabled: Boolean,
    val entries: Int,
    val checkedAt: Long,
)

/** A dictionary being downloaded, imported or updated, keyed by its source id or file name. */
data class InstallProgress(val name: String, val bytes: Long = 0, val entries: Int = 0, val error: String? = null)

/** One dictionary entry for a looked-up word; [glossary] is Yomitan's JSON array. */
data class DictionaryEntry(
    val dictionary: String,
    val expression: String,
    val reading: String,
    val tags: List<String>,
    val glossary: String,
)

data class Frequency(val dictionary: String, val value: String)

data class LookupResult(
    val terms: List<DictionaryEntry> = emptyList(),
    val frequencies: List<Frequency> = emptyList(),
    /** Single-kanji entries for the kanji in the word, from kanji dictionaries. */
    val kanji: List<DictionaryEntry> = emptyList(),
)

/**
 * Yomitan dictionaries on the phone: installing them (the recommended set, or any Yomitan zip),
 * keeping updatable ones up to date, and looking words up. Lookups only use dictionaries of
 * the language being studied.
 */
@Singleton
class DictionaryRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val db = DictionaryDatabase(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** One install or update at a time: they write the same database. */
    private val writeLock = Mutex()

    private val _installed = MutableStateFlow<List<InstalledDictionary>>(emptyList())
    val installed: StateFlow<List<InstalledDictionary>> = _installed.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, InstallProgress>>(emptyMap())
    /** Installs and updates that are running or failed, by source id or file name. */
    val progress: StateFlow<Map<String, InstallProgress>> = _progress.asStateFlow()

    init {
        scope.launch {
            // An import cut short (app killed) left a dictionary that never got ready.
            val unfinished = query("SELECT id FROM dictionary WHERE ready = 0") { it.getLong(0) }
            unfinished.forEach(db::delete)
            refresh()
        }
    }

    /** Downloads and installs recommended dictionaries in the background. */
    fun installRecommended(sources: List<RecommendedDictionary>) {
        for (source in sources) {
            if (_progress.value[source.sourceId]?.error == null && source.sourceId in _progress.value) continue
            _progress.update { it + (source.sourceId to InstallProgress(source.name)) }
            scope.launch {
                runInstall(source.sourceId, source.name) {
                    val file = download(source.downloadUrl, source.sourceId)
                    try {
                        importFile(file, source.sourceId, source.role, source.language, source.sourceId, replacing = installedId(source.sourceId), title = source.name.takeIf { WiktionaryCatalog.isWiktionary(source.sourceId) })
                    } finally {
                        file.delete()
                    }
                }
            }
        }
    }

    /** Imports a Yomitan zip the user picked, for [language]. */
    fun importUri(uri: Uri, name: String, language: Language) {
        _progress.update { it + (name to InstallProgress(name)) }
        scope.launch {
            runInstall(name, name) {
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open $name")
                input.use { importStream(it, sourceId = null, role = null, language = language, key = name, replacing = null) }
            }
        }
    }

    fun remove(id: Long) = scope.launch {
        writeLock.withLock { db.delete(id) }
        refresh()
    }

    fun setEnabled(id: Long, enabled: Boolean) = scope.launch {
        db.update(id, ContentValues().apply { put("enabled", if (enabled) 1 else 0) })
        refresh()
    }

    /** Moves a dictionary one place up ([by] -1) or down (+1) among those of its language; lookups follow this order. */
    fun move(id: Long, by: Int) = scope.launch {
        val list = loadInstalled()
        val d = list.firstOrNull { it.id == id } ?: return@launch
        val same = list.filter { it.language == d.language }
        val other = same.getOrNull(same.indexOf(d) + by) ?: return@launch
        val positions = query("SELECT id, position FROM dictionary WHERE id IN (${d.id}, ${other.id})") { it.getLong(0) to it.getInt(1) }.toMap()
        var a = positions[d.id] ?: return@launch
        var b = positions[other.id] ?: return@launch
        // Two dictionaries can share a position (their order then goes by id); split them first.
        if (a == b) if (d.id < other.id) b++ else a++
        db.update(d.id, ContentValues().apply { put("position", b) })
        db.update(other.id, ContentValues().apply { put("position", a) })
        refresh()
    }

    fun setAutoUpdate(id: Long, auto: Boolean) = scope.launch {
        db.update(id, ContentValues().apply { put("auto_update", if (auto) 1 else 0) })
        refresh()
    }

    fun dismissError(key: String) = _progress.update { it - key }

    /**
     * Checks updatable dictionaries' index URLs and installs newer revisions. [all] checks
     * every updatable one, not just those with automatic updates on. Returns how many updated.
     */
    suspend fun checkForUpdates(all: Boolean = false): Int = withContext(Dispatchers.IO) {
        var updated = 0
        for (d in loadInstalled().filter { it.updatable && (all || it.autoUpdate) }) {
            val row = query("SELECT index_url, download_url, role FROM dictionary WHERE id = ${d.id}") {
                Triple(it.getString(0), it.getString(1), it.getString(2))
            }.firstOrNull() ?: continue
            val (indexUrl, downloadUrl, role) = row
            if (indexUrl.isNullOrBlank()) continue
            val key = d.sourceId ?: d.title
            try {
                val index = DictionaryIndex.parse(fetchText(indexUrl))
                db.update(d.id, ContentValues().apply { put("checked_at", System.currentTimeMillis()) })
                if (index.revision == d.revision) continue
                val url = index.downloadUrl ?: downloadUrl ?: continue
                _progress.update { it + (key to InstallProgress(d.title)) }
                runInstall(key, d.title) {
                    val file = download(url, key)
                    try {
                        importFile(file, d.sourceId, role, d.language, key, replacing = d.id, title = d.title.takeIf { d.sourceId?.let(WiktionaryCatalog::isWiktionary) == true })
                    } finally {
                        file.delete()
                    }
                }
                updated++
            } catch (e: Exception) {
                // An unreachable index isn't worth a visible error; the next check tries again.
            }
        }
        refresh()
        updated
    }

    /**
     * Looks up a word by its [forms] (e.g. dictionary form and the form in the sentence), for
     * [language]. With a [reading], entries with that reading come first.
     */
    suspend fun lookup(language: Language, forms: List<String>, reading: String?): LookupResult = withContext(Dispatchers.IO) {
        if (forms.isEmpty()) return@withContext LookupResult()
        val terms = queryTerms(language, forms).toMutableList()
        // Inflected forms (Wiktionary's "dricker") point to their lemma ("dricka"); add its entries.
        val lemmas = terms.flatMap { lemmasOf(it.glossary) }.distinct().filter { it !in forms }
        if (lemmas.isNotEmpty()) terms += queryTerms(language, lemmas)
        val kanji = mutableListOf<DictionaryEntry>()
        // Entries with the word's reading first, then in the order of your dictionaries, and within
        // a dictionary the most frequent headwords first (so 成る comes before 生る).
        val ranks = terms.map { it.expression }.distinct().associateWith { rankOf(language, it, reading) }
        val order = terms.map { it.dictionary }.distinct()
        val sorted = terms.sortedWith(
            compareBy<DictionaryEntry> { reading != null && it.reading != reading }
                .thenBy { order.indexOf(it.dictionary) }
                .thenBy { ranks[it.expression] ?: Int.MAX_VALUE },
        ).take(MAX_TERMS)

        val word = forms.first()
        val kanjiChars = word.filter { it.code in 0x3400..0x9FFF }.toSet()
        for (ch in kanjiChars) {
            db.readableDatabase.rawQuery(
                "SELECT d.title, t.expression, t.reading, t.def_tags, t.term_tags, t.glossary FROM term t JOIN dictionary d ON d.id = t.dict " +
                    "WHERE d.ready = 1 AND d.enabled = 1 AND d.language = ? AND d.role = ? AND t.expression = ? LIMIT 1",
                arrayOf(language.code, ROLE_KANJI, ch.toString()),
            ).use { c ->
                if (c.moveToFirst()) {
                    kanji += DictionaryEntry(c.getString(0), c.getString(1), c.getString(2), emptyList(), DictionaryDatabase.inflate(c.getBlob(5)))
                }
            }
        }

        val frequencies = mutableListOf<Frequency>()
        db.readableDatabase.rawQuery(
            "SELECT d.title, m.data FROM term_meta m JOIN dictionary d ON d.id = m.dict WHERE d.ready = 1 AND d.enabled = 1 " +
                "AND d.language = ? AND m.mode = 'freq' AND m.expression = ? ORDER BY d.position, d.id",
            arrayOf(language.code, word),
        ).use { c ->
            while (c.moveToNext()) {
                val value = frequencyText(c.getString(1), reading) ?: continue
                if (frequencies.none { it.dictionary == c.getString(0) }) frequencies += Frequency(c.getString(0), value)
            }
        }
        LookupResult(sorted, frequencies, kanji)
    }

    /** Term entries (not kanji) whose expression or reading is one of [forms]. */
    private fun queryTerms(language: Language, forms: List<String>): List<DictionaryEntry> {
        val marks = forms.joinToString(",") { "?" }
        return query(
            "SELECT d.title, t.expression, t.reading, t.def_tags, t.term_tags, t.glossary FROM term t " +
                "JOIN dictionary d ON d.id = t.dict WHERE d.ready = 1 AND d.enabled = 1 AND d.language = ? " +
                "AND (d.role IS NULL OR d.role != '$ROLE_KANJI') AND (t.expression IN ($marks) OR t.reading IN ($marks)) ORDER BY d.position, d.id, t.score DESC LIMIT 60",
            (listOf(language.code) + forms + forms).toTypedArray(),
        ) { c ->
            DictionaryEntry(
                dictionary = c.getString(0),
                expression = c.getString(1),
                reading = c.getString(2),
                tags = (c.getString(3).orEmpty() + " " + c.getString(4).orEmpty()).split(' ').filter { it.isNotBlank() }.distinct(),
                glossary = DictionaryDatabase.inflate(c.getBlob(5)),
            )
        }
    }

    /** The best (lowest) rank any frequency dictionary gives [expression], or null. */
    private fun rankOf(language: Language, expression: String, reading: String?): Int? {
        var best: Int? = null
        db.readableDatabase.rawQuery(
            "SELECT m.data FROM term_meta m JOIN dictionary d ON d.id = m.dict WHERE d.ready = 1 AND d.enabled = 1 " +
                "AND d.language = ? AND m.mode = 'freq' AND m.expression = ?",
            arrayOf(language.code, expression),
        ).use { c ->
            while (c.moveToNext()) {
                val rank = frequencyRank(c.getString(0), reading) ?: continue
                best = minOf(best ?: rank, rank)
            }
        }
        return best
    }

    private suspend fun runInstall(key: String, name: String, block: suspend () -> Unit) {
        try {
            writeLock.withLock { block() }
            _progress.update { it - key }
        } catch (e: Exception) {
            _progress.update { it + (key to InstallProgress(name, error = e.message ?: e.toString())) }
        }
        refresh()
    }

    private fun importFile(file: File, sourceId: String?, role: String?, language: Language, key: String, replacing: Long?, title: String? = null) {
        file.inputStream().use { importStream(it, sourceId, role, language, key, replacing, title) }
    }

    /**
     * Imports a zip as a new dictionary of [language] (the one being studied, like decks: a
     * Chinese dictionary serves Mandarin, Cantonese or Taiwanese); when it's ready, it takes the
     * place of [replacing]. A [title] replaces the one in its index (wty titles are like "wty-sv-en").
     */
    private fun importStream(input: InputStream, sourceId: String?, role: String?, language: Language, key: String, replacing: Long?, title: String? = null) {
        val position = replacing?.let { id -> query("SELECT position FROM dictionary WHERE id = $id") { it.getInt(0) }.firstOrNull() }
            ?: ((query("SELECT MAX(position) FROM dictionary") { it.getInt(0) }.firstOrNull() ?: 0) + 1)
        val id = db.insertDictionary(
            ContentValues().apply {
                put("title", key)
                put("revision", "")
                put("source_id", sourceId)
                put("role", role)
                put("language", language.code)
                put("position", position)
                put("imported_at", System.currentTimeMillis())
            },
        )
        val writer = db.Writer(id)
        try {
            val index = YomitanReader.read(input, writer) { n ->
                if (n % 2000 == 0) _progress.update { it + (key to (it[key] ?: InstallProgress(key)).copy(entries = n)) }
            }
            writer.finish(success = true)
            db.update(
                id,
                ContentValues().apply {
                    put("title", title ?: index.title)
                    put("revision", index.revision)
                    put("index_url", index.indexUrl)
                    put("download_url", index.downloadUrl)
                    put("updatable", if (index.updatable && index.indexUrl != null) 1 else 0)
                    put("entries", writer.entries)
                    put("attribution", index.attribution)
                    put("checked_at", System.currentTimeMillis())
                    put("ready", 1)
                },
            )
            if (replacing != null) db.delete(replacing)
        } catch (e: Exception) {
            runCatching { writer.finish(success = false) }
            db.delete(id)
            throw e
        }
    }

    private fun download(url: String, key: String): File {
        val file = File(context.cacheDir, "dictionary-$key.zip".replace(Regex("[^A-Za-z0-9._-]"), "_"))
        var connection = URL(url).openConnection() as HttpURLConnection
        // Follow redirects ourselves too, since HttpURLConnection won't across some hosts.
        repeat(5) {
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "anki-listening-practice")
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            val code = connection.responseCode
            if (code in 300..399) {
                val next = connection.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                connection.disconnect()
                connection = URL(URL(url), next).openConnection() as HttpURLConnection
            } else {
                if (code !in 200..299) throw IOException("Download failed: HTTP $code")
                connection.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            total += n
                            if (total % (1024 * 1024) < n) {
                                _progress.update { it + (key to (it[key] ?: InstallProgress(key)).copy(bytes = total)) }
                            }
                        }
                    }
                }
                return file
            }
        }
        throw IOException("Too many redirects")
    }

    private fun fetchText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "anki-listening-practice")
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun installedId(sourceId: String): Long? =
        query("SELECT id FROM dictionary WHERE source_id = ? AND ready = 1", arrayOf(sourceId)) { it.getLong(0) }.firstOrNull()

    private fun loadInstalled(): List<InstalledDictionary> = query(
        "SELECT id, title, revision, source_id, language, updatable, auto_update, enabled, entries, checked_at " +
            "FROM dictionary WHERE ready = 1 ORDER BY position, id",
    ) { c ->
        InstalledDictionary(
            id = c.getLong(0),
            title = c.getString(1),
            revision = c.getString(2),
            sourceId = c.getString(3),
            language = Language.fromCode(c.getString(4)),
            updatable = c.getInt(5) == 1,
            autoUpdate = c.getInt(6) == 1,
            enabled = c.getInt(7) == 1,
            entries = c.getInt(8),
            checkedAt = c.getLong(9),
        )
    }

    private fun refresh() {
        _installed.value = loadInstalled()
    }

    private fun <T> query(sql: String, args: Array<String>? = null, map: (android.database.Cursor) -> T): List<T> =
        db.readableDatabase.rawQuery(sql, args).use { c ->
            buildList { while (c.moveToNext()) add(map(c)) }
        }

    companion object {
        const val ROLE_KANJI = "kanji"
        private const val MAX_TERMS = 12

        /**
         * A frequency entry as text: a number, {value, displayValue}, or either of those under
         * {reading, frequency}. Entries for another reading than [reading] are skipped.
         */
        fun frequencyText(json: String, reading: String?): String? {
            val trimmed = json.trim()
            if (!trimmed.startsWith("{")) return trimmed.removeSurrounding("\"")
            val o = JSONObject(trimmed)
            if (o.has("frequency")) {
                val r = o.optString("reading")
                if (reading != null && r.isNotEmpty() && r != reading) return null
                val f = o.get("frequency")
                return if (f is JSONObject) f.optString("displayValue").ifEmpty { f.opt("value")?.toString() } else f.toString()
            }
            return o.optString("displayValue").ifEmpty { o.opt("value")?.toString() }
        }

        /** The lemmas an inflected form's glossary points to: [["dricka", ["present"]]] → dricka. */
        fun lemmasOf(glossary: String): List<String> {
            val items = runCatching { org.json.JSONArray(glossary) }.getOrNull() ?: return emptyList()
            return (0 until items.length()).mapNotNull { i -> (items.opt(i) as? org.json.JSONArray)?.optString(0)?.takeIf { it.isNotEmpty() } }
        }

        /** The numeric rank in a frequency entry, or null if it has none (or is for another reading). */
        fun frequencyRank(json: String, reading: String?): Int? {
            val trimmed = json.trim()
            if (!trimmed.startsWith("{")) return trimmed.removeSurrounding("\"").takeWhile { it.isDigit() }.toIntOrNull()
            val o = JSONObject(trimmed)
            val f = if (o.has("frequency")) {
                val r = o.optString("reading")
                if (reading != null && r.isNotEmpty() && r != reading) return null
                o.get("frequency")
            } else o
            return when (f) {
                is JSONObject -> f.opt("value")?.toString()?.toDoubleOrNull()?.toInt()
                is Number -> f.toInt()
                else -> f.toString().takeWhile { it.isDigit() }.toIntOrNull()
            }
        }
    }
}
