package dev.lovkvist.listening.data.dict

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** The parts of a Yomitan dictionary's index.json the app uses. */
data class DictionaryIndex(
    val title: String,
    val revision: String,
    val updatable: Boolean,
    val indexUrl: String?,
    val downloadUrl: String?,
    val sourceLanguage: String?,
    val attribution: String,
) {
    companion object {
        fun parse(json: String): DictionaryIndex {
            val j = JSONObject(json)
            fun str(key: String) = j.optString(key).takeIf { it.isNotBlank() && it != "null" }
            return DictionaryIndex(
                title = str("title") ?: throw IOException("index.json has no title"),
                // Jitendex has no revision; its title carries the date instead.
                revision = str("revision") ?: str("title")!!,
                updatable = j.optBoolean("isUpdatable"),
                indexUrl = str("indexUrl"),
                downloadUrl = str("downloadUrl"),
                sourceLanguage = str("sourceLanguage"),
                attribution = str("attribution").orEmpty(),
            )
        }
    }
}

/** Where a [YomitanReader] puts what it reads. */
interface DictionarySink {
    fun term(
        expression: String,
        reading: String,
        definitionTags: String,
        rules: String,
        score: Int,
        glossary: String,
        sequence: Long,
        termTags: String,
    )

    /** Frequency, pitch or IPA data ([mode]) for a term, as JSON. */
    fun meta(expression: String, mode: String, data: String)

    fun kanji(character: String, onyomi: String, kunyomi: String, tags: String, meanings: String, stats: String)

    fun tag(name: String, category: String, order: Int, notes: String)
}

/**
 * Reads a Yomitan dictionary zip (format 1 to 3) as a stream: index.json, term banks,
 * term meta banks (frequency etc.), kanji banks and tag banks. Banks can be tens of MB (Jiten's
 * frequency list is one 56 MB array), so entries are parsed one at a time, never whole files.
 */
object YomitanReader {
    /** Reads [input] into [sink]; returns the index. [onEntry] gets a running count of entries. */
    fun read(input: InputStream, sink: DictionarySink, onEntry: (Int) -> Unit = {}): DictionaryIndex {
        var index: DictionaryIndex? = null
        var count = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                // The parser must not close the zip when it's done with an entry.
                val stream = object : FilterInputStream(zip) {
                    override fun close() {}
                }
                when {
                    name == "index.json" -> index = DictionaryIndex.parse(stream.readBytes().toString(Charsets.UTF_8))
                    name.startsWith("term_bank_") -> eachEntry(stream) { r -> readTerm(r, sink); onEntry(++count) }
                    name.startsWith("term_meta_bank_") -> eachEntry(stream) { r ->
                        r.beginArray()
                        val expression = r.nextString()
                        val mode = r.nextString()
                        val data = JsonParser.parseReader(r).toString()
                        skipRest(r)
                        sink.meta(expression, mode, data)
                        onEntry(++count)
                    }
                    name.startsWith("kanji_bank_") -> eachEntry(stream) { r ->
                        r.beginArray()
                        val character = r.nextString()
                        val onyomi = nextText(r)
                        val kunyomi = nextText(r)
                        val tags = nextText(r)
                        val meanings = if (r.hasNext()) JsonParser.parseReader(r).toString() else "[]"
                        val stats = if (r.hasNext()) JsonParser.parseReader(r).toString() else "{}"
                        skipRest(r)
                        sink.kanji(character, onyomi, kunyomi, tags, meanings, stats)
                        onEntry(++count)
                    }
                    name.startsWith("tag_bank_") -> eachEntry(stream) { r ->
                        r.beginArray()
                        val tagName = nextText(r)
                        val category = nextText(r)
                        val order = nextNumber(r).toInt()
                        val notes = nextText(r)
                        skipRest(r)
                        sink.tag(tagName, category, order, notes)
                    }
                }
                zip.closeEntry()
            }
        }
        return index ?: throw IOException("Not a Yomitan dictionary: no index.json")
    }

    // [expression, reading, definitionTags, rules, score, glossary, sequence, termTags];
    // format 1 stops after the glossary, given as the remaining strings.
    private fun readTerm(r: JsonReader, sink: DictionarySink) {
        r.beginArray()
        val expression = r.nextString()
        val reading = nextText(r)
        val definitionTags = nextText(r)
        val rules = nextText(r)
        val score = nextNumber(r).toInt()
        val glossary: JsonElement = if (r.peek() == JsonToken.BEGIN_ARRAY) {
            JsonParser.parseReader(r)
        } else {
            com.google.gson.JsonArray().also { a -> while (r.hasNext() && r.peek() == JsonToken.STRING) a.add(r.nextString()) }
        }
        val sequence = if (r.hasNext()) nextNumber(r).toLong() else 0L
        val termTags = if (r.hasNext()) nextText(r) else ""
        skipRest(r)
        sink.term(expression, reading.ifEmpty { expression }, definitionTags, rules, score, glossary.toString(), sequence, termTags)
    }

    private fun eachEntry(stream: InputStream, block: (JsonReader) -> Unit) {
        val r = JsonReader(stream.reader(Charsets.UTF_8))
        r.isLenient = true
        r.beginArray()
        while (r.hasNext()) block(r)
        r.endArray()
    }

    /** A string field that may also be null or a number. */
    private fun nextText(r: JsonReader): String = when (r.peek()) {
        JsonToken.NULL -> "".also { r.nextNull() }
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        else -> JsonParser.parseReader(r).toString()
    }

    private fun nextNumber(r: JsonReader): Double = when (r.peek()) {
        JsonToken.NULL -> 0.0.also { r.nextNull() }
        JsonToken.STRING -> r.nextString().toDoubleOrNull() ?: 0.0
        else -> r.nextDouble()
    }

    private fun skipRest(r: JsonReader) {
        while (r.hasNext()) r.skipValue()
        r.endArray()
    }
}
