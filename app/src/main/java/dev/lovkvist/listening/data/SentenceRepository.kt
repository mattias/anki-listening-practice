package dev.lovkvist.listening.data

import dev.lovkvist.listening.data.anki.DeckLibrary
import dev.lovkvist.listening.data.anki.AnkiSentence
import dev.lovkvist.listening.data.known.KnownWordsRepository
import dev.lovkvist.listening.data.local.SentenceState
import dev.lovkvist.listening.data.local.SentenceStateDao
import dev.lovkvist.listening.data.settings.SettingsRepository
import dev.lovkvist.listening.text.JapaneseTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SentenceRepository @Inject constructor(
    private val library: DeckLibrary,
    private val knownWords: KnownWordsRepository,
    private val tokenizer: JapaneseTokenizer,
    private val dao: SentenceStateDao,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()

    /** All notes from the collection, in random order; reloaded by [invalidate]. */
    private var pool: List<AnkiSentence>? = null

    /** Memoized "every word is known" results, keyed by note id. Cleared when known words change. */
    private val knownCache = HashMap<Long, Boolean>()

    private val _generation = MutableStateFlow(0)
    /** Bumped whenever the source data changes, so the practice queue knows to rebuild. */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    suspend fun invalidate(collection: Boolean = true, known: Boolean = true) {
        mutex.withLock {
            if (collection) pool = null
            if (known) knownCache.clear()
        }
        _generation.value += 1
    }

    /**
     * Returns up to [count] sentences that are fully known, not blacklisted, not cooling down
     * and not in [exclude]. Tokenizes lazily, so it stops as soon as enough are found.
     */
    suspend fun nextEligible(count: Int, exclude: Set<Long>): List<AnkiSentence> = mutex.withLock {
        val pool = pool ?: loadPool().also { pool = it }
        val known = knownWords.words()
        if (known.isEmpty()) return@withLock emptyList()
        val blocked = dao.excludedIds(System.currentTimeMillis()).toHashSet()

        withContext(Dispatchers.Default) {
            pool.asSequence()
                .filter { it.noteId !in exclude && it.noteId !in blocked }
                .filter { s -> knownCache.getOrPut(s.noteId) { tokenizer.isFullyKnown(s.text, known) } }
                .take(count)
                .toList()
        }
    }

    suspend fun poolSize(): Int = mutex.withLock { pool?.size ?: 0 }

    suspend fun markCrystalClear(noteId: Long) {
        val until = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(COOLDOWN_DAYS)
        val current = dao.get(noteId) ?: SentenceState(noteId)
        dao.upsert(current.copy(cooldownUntil = until))
    }

    suspend fun blacklist(noteId: Long) {
        val current = dao.get(noteId) ?: SentenceState(noteId)
        dao.upsert(current.copy(blacklisted = true))
    }

    private suspend fun loadPool(): List<AnkiSentence> =
        library.readSentences(settings.current().mapping).shuffled()

    companion object {
        const val COOLDOWN_DAYS = 10L
    }
}
