package dev.lovkvist.listening.data

import dev.lovkvist.listening.data.anki.DeckLibrary
import dev.lovkvist.listening.data.known.KnownWordsRepository
import dev.lovkvist.listening.data.local.SentenceState
import dev.lovkvist.listening.data.local.SentenceStateDao
import dev.lovkvist.listening.data.media.MediaLibrary
import dev.lovkvist.listening.data.settings.SettingsRepository
import dev.lovkvist.listening.text.Language
import dev.lovkvist.listening.text.Tokenizers
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
    private val decks: DeckLibrary,
    private val media: MediaLibrary,
    private val knownWords: KnownWordsRepository,
    private val tokenizers: Tokenizers,
    private val dao: SentenceStateDao,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()

    /**
     * Sentences of the current source: Anki cards in random order, or a video's subtitle
     * chunks in playback order. Reloaded by [invalidate] or when the source changes.
     */
    private var pool: List<Sentence>? = null
    private var poolSource: PracticeSource? = null
    private var poolLanguage: Language? = null

    /** Target words of all imported cards (e.g. Expression); they count as known everywhere. */
    private var cardWords: Set<String>? = null

    /** Memoized "every word is known" results, keyed by sentence id. Cleared when known words change. */
    private val knownCache = HashMap<Long, Boolean>()

    private val _generation = MutableStateFlow(0)
    /** Bumped whenever the source data changes, so the practice queue knows to rebuild. */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    suspend fun invalidate(collection: Boolean = true, known: Boolean = true) {
        mutex.withLock {
            if (collection) {
                pool = null
                cardWords = null
            }
            // Card words feed the known set, so new cards can make more sentences eligible.
            if (known || collection) knownCache.clear()
        }
        _generation.value += 1
    }

    /** Number of distinct card words that count as known. */
    suspend fun cardWordCount(): Int = mutex.withLock { loadCardWords(settings.current().language).size }

    /**
     * Returns up to [count] sentences that are fully known, not blacklisted, not cooling down
     * and not in [exclude], in the source's order. Tokenizes lazily, so it stops as soon as
     * enough are found.
     */
    suspend fun nextEligible(count: Int, exclude: Set<Long>): List<Sentence> = mutex.withLock {
        val current = settings.current()
        val language = current.language
        val source = current.source
        if (language != poolLanguage) {
            // Card words and verdicts depend on the language.
            cardWords = null
            knownCache.clear()
        }
        if (pool == null || poolSource != source || poolLanguage != language) {
            pool = loadPool(source, language)
            poolSource = source
            poolLanguage = language
        }
        val pool = pool.orEmpty()
        val tokenizer = tokenizers.forLanguage(language)
        val known = (knownWords.words(language) + loadCardWords(language)).mapTo(HashSet(), tokenizer::normalize)
        if (known.isEmpty()) return@withLock emptyList()
        val blocked = dao.excludedIds(System.currentTimeMillis()).toHashSet()

        withContext(Dispatchers.Default) {
            pool.asSequence()
                .filter { it.id !in exclude && it.id !in blocked }
                .filter { s -> knownCache.getOrPut(s.id) { tokenizer.isFullyKnown(s.text, known) } }
                .take(count)
                .toList()
        }
    }

    suspend fun markCrystalClear(id: Long) {
        val until = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(COOLDOWN_DAYS)
        val current = dao.get(id) ?: SentenceState(id)
        dao.upsert(current.copy(cooldownUntil = until))
    }

    suspend fun blacklist(id: Long) {
        val current = dao.get(id) ?: SentenceState(id)
        dao.upsert(current.copy(blacklisted = true))
    }

    private suspend fun loadPool(source: PracticeSource, language: Language): List<Sentence> = when (source) {
        PracticeSource.Anki -> decks.readSentences(settings.current().mapping, language).shuffled()
        is PracticeSource.Media -> media.sentences(source.id)
    }

    private suspend fun loadCardWords(language: Language): Set<String> =
        cardWords ?: runCatching { decks.readWords(settings.current().mapping, language) }.getOrDefault(emptySet())
            .also { cardWords = it }

    companion object {
        const val COOLDOWN_DAYS = 10L
    }
}
