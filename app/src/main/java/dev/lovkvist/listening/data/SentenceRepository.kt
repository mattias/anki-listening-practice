package dev.lovkvist.listening.data

import dev.lovkvist.listening.R
import dev.lovkvist.listening.data.anki.DeckLibrary
import dev.lovkvist.listening.data.known.KnownWordsRepository
import dev.lovkvist.listening.data.local.SentenceState
import dev.lovkvist.listening.data.local.SentenceStateDao
import dev.lovkvist.listening.data.media.MediaLibrary
import dev.lovkvist.listening.data.settings.SettingsRepository
import dev.lovkvist.listening.l10n.Strings
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
    private val strings: Strings,
) {
    private val mutex = Mutex()

    /**
     * Sentences of the current source: Anki cards in random order, or a video's subtitle
     * chunks in playback order. Reloaded by [invalidate] or when the source changes.
     */
    private var pool: List<Sentence>? = null
    private var poolSource: PracticeSource? = null
    private var poolLanguage: Language? = null

    /** Target words of the language's cards (e.g. Expression); they count as known. */
    private var cardWords: Set<String>? = null

    /** Memoized unknown-word counts (null: no words), keyed by sentence id. Cleared when known words change. */
    private val knownCache = HashMap<Long, Int?>()

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
     * The sentence to practice after [afterId] (or the first one if null): the next one in the
     * source's order with no more unknown words than allowed (n+0 to n+5), and not blacklisted or
     * cooling down. Anki cards are in random order, reshuffled each time the end is reached; a
     * video plays in order and starts over at its end. Returns [afterId]'s sentence again only if
     * nothing else is eligible, and null if nothing is. Tokenizes lazily, so it stops at the first
     * match.
     */
    suspend fun next(afterId: Long?): Sentence? = mutex.withLock {
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
        val tokenizer = tokenizers.forLanguage(language)
        val known = (knownWords.words(language) + loadCardWords(language)).mapTo(HashSet(), tokenizer::normalize)
        if (known.isEmpty()) return@withLock null
        val blocked = dao.excludedIds(System.currentTimeMillis()).toHashSet()
        val maxUnknown = current.maxUnknownWords

        withContext(Dispatchers.Default) {
            var list = pool.orEmpty()
            val start = list.indexOfFirst { it.id == afterId } + 1 // 0 when there's no current one
            fun unknownWords(s: Sentence): Int? =
                if (s.id in knownCache) knownCache[s.id] else tokenizer.unknownWords(s.text, known).also { knownCache[s.id] = it }
            fun eligible(s: Sentence) =
                s.id !in blocked && unknownWords(s)?.let { it <= maxUnknown } == true

            list.drop(start).firstOrNull { it.id != afterId && eligible(it) }?.let { return@withContext it }
            // Reached the end: Anki cards get a new random order, a video starts over.
            if (source == PracticeSource.Anki && start > 0) {
                list = list.shuffled()
                pool = list
            }
            list.firstOrNull { it.id != afterId && eligible(it) }
                ?: list.firstOrNull { it.id == afterId && eligible(it) }
        }
    }

    suspend fun markCrystalClear(id: Long) {
        val until = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(COOLDOWN_DAYS)
        val current = dao.get(id) ?: SentenceState(id)
        dao.upsert(current.copy(cooldownUntil = until))
    }

    /** Hides [sentence] for good; its text is kept so it can be listed and restored. */
    suspend fun blacklist(sentence: Sentence) {
        val current = dao.get(sentence.id) ?: SentenceState(sentence.id)
        val sourceName = when (val s = settings.current().source) {
            PracticeSource.Anki -> strings(R.string.anki_cards)
            is PracticeSource.Media -> media.items.value.firstOrNull { it.id == s.id }?.name.orEmpty()
        }
        dao.upsert(current.copy(blacklisted = true, text = sentence.text, source = sourceName))
    }

    suspend fun unblacklist(id: Long) {
        dao.get(id)?.let { dao.upsert(it.copy(blacklisted = false)) }
        _generation.value += 1
    }

    suspend fun blacklisted(): List<SentenceState> = dao.blacklisted()

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
