package dev.lovkvist.listening.data.known

import dev.lovkvist.listening.data.local.WordStatus
import dev.lovkvist.listening.data.local.WordStatusDao
import dev.lovkvist.listening.text.Language
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Words marked from sentences in the app, per language: known or ignored. Kept apart from the
 * imported word list, so they can be searched, switched or removed one by one later.
 */
@Singleton
class MyWords @Inject constructor(private val dao: WordStatusDao) {
    private val mutex = Mutex()
    private val cache = HashMap<Language, Map<String, Boolean>>()

    fun observe(language: Language): Flow<List<WordStatus>> = dao.observe(language.code)

    /** Word to ignored-flag for [language]. */
    suspend fun all(language: Language): Map<String, Boolean> = mutex.withLock {
        cache.getOrPut(language) { dao.all(language.code).associate { it.word to it.ignored } }
    }

    /** Words that count as known when choosing sentences: known and ignored alike. */
    suspend fun counted(language: Language): Set<String> = all(language).keys

    /** Only the known ones, for exporting. */
    suspend fun known(language: Language): List<String> = all(language).filterValues { !it }.keys.toList()

    suspend fun set(language: Language, word: String, ignored: Boolean) = mutex.withLock {
        dao.upsert(WordStatus(language.code, word, ignored))
        cache.remove(language)
    }

    suspend fun remove(language: Language, word: String) = mutex.withLock {
        dao.delete(language.code, word)
        cache.remove(language)
    }
}
