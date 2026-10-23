package dev.lovkvist.listening.data.known

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Known words exported from Jiten: a plain .txt with one word per line.
 * The imported file is copied into app storage so it survives the original being moved.
 */
@Singleton
class KnownWordsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file = File(context.filesDir, "known_words.txt")
    private val mutex = Mutex()
    private var cache: Set<String>? = null

    private val _count = MutableStateFlow<Int?>(null)
    /** Number of imported words, or null before the first load. */
    val count: StateFlow<Int?> = _count.asStateFlow()

    suspend fun import(uri: Uri): Int = withContext(Dispatchers.IO) {
        val words = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { r ->
            r.readLines().mapNotNull(::normalize).toSet()
        } ?: error("Could not open the selected file")
        file.writeText(words.joinToString("\n"), Charsets.UTF_8)
        mutex.withLock { cache = words }
        _count.value = words.size
        words.size
    }

    suspend fun words(): Set<String> = mutex.withLock {
        cache ?: withContext(Dispatchers.IO) {
            if (file.isFile) file.readLines(Charsets.UTF_8).mapNotNull(::normalize).toSet() else emptySet()
        }.also {
            cache = it
            _count.value = it.size
        }
    }

    private fun normalize(line: String): String? =
        // Tolerate a BOM and trailing columns (e.g. "word\tfrequency").
        line.removePrefix("﻿").substringBefore('\t').trim().takeIf { it.isNotEmpty() }
}
