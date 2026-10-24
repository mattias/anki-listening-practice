package dev.lovkvist.listening.data.known

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lovkvist.listening.R
import dev.lovkvist.listening.l10n.Strings
import dev.lovkvist.listening.text.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Known words per language: a plain .txt with one word per line, as exported by Jiten or
 * Anki Miner (its .csv export works too). The imported file is copied into app storage so it
 * survives the original being moved.
 */
@Singleton
class KnownWordsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val strings: Strings,
) {
    private val mutex = Mutex()
    private val cache = HashMap<Language, Set<String>>()

    private val _counts = MutableStateFlow<Map<Language, Int>>(emptyMap())
    /** Number of imported words per language, for languages loaded so far. */
    val counts: StateFlow<Map<Language, Int>> = _counts.asStateFlow()

    suspend fun import(uri: Uri, language: Language): Int = withContext(Dispatchers.IO) {
        val words = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { r ->
            parse(r.readLines())
        } ?: error(strings(R.string.could_not_open_file))
        file(language).writeText(words.joinToString("\n"), Charsets.UTF_8)
        mutex.withLock { cache[language] = words }
        _counts.update { it + (language to words.size) }
        words.size
    }

    suspend fun words(language: Language): Set<String> = mutex.withLock {
        cache[language] ?: withContext(Dispatchers.IO) {
            val file = file(language)
            if (file.isFile) parse(file.readLines(Charsets.UTF_8)) else emptySet()
        }.also { words ->
            cache[language] = words
            _counts.update { it + (language to words.size) }
        }
    }

    // Japanese keeps the file name from before other languages existed.
    private fun file(language: Language) =
        File(context.filesDir, if (language == Language.JAPANESE) "known_words.txt" else "known_words_${language.code}.txt")

    private fun parse(lines: List<String>): Set<String> {
        val first = lines.firstOrNull()?.removePrefix("﻿")?.trim()
        // Anki Miner's .csv export: a "word,source,added_at_utc" header, the word in the first column.
        val csv = first != null && first.startsWith("word,")
        val rows = if (csv) lines.drop(1).map(::firstCsvColumn) else lines
        return rows.mapNotNull(::normalize).toSet()
    }

    private fun firstCsvColumn(line: String): String =
        if (line.startsWith('"')) line.drop(1).substringBefore("\",").replace("\"\"", "\"") else line.substringBefore(',')

    private fun normalize(line: String): String? =
        // Tolerate a BOM and trailing columns (e.g. "word\tfrequency").
        // Section headers such as Jiten's "=== MASTERED ===" aren't words.
        line.removePrefix("﻿").substringBefore('\t').trim().takeIf { it.isNotEmpty() && !it.startsWith("===") }
}
