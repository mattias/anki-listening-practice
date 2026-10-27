package dev.lovkvist.listening.data

import dev.lovkvist.listening.R
import dev.lovkvist.listening.data.anki.DeckLibrary
import dev.lovkvist.listening.data.known.KnownWordsRepository
import dev.lovkvist.listening.data.known.MyWords
import dev.lovkvist.listening.data.local.SentenceState
import dev.lovkvist.listening.data.local.SentenceStateDao
import dev.lovkvist.listening.data.media.MediaLibrary
import dev.lovkvist.listening.data.settings.SettingsRepository
import dev.lovkvist.listening.l10n.Strings
import dev.lovkvist.listening.text.Language
import dev.lovkvist.listening.text.SentenceTokenizer
import dev.lovkvist.listening.text.Tokenizers
import dev.lovkvist.listening.text.WordSpan
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

/** How a word in a shown sentence counts. */
enum class WordState {
    /** In your word list or a card's word field. */
    KNOWN,
    /** Marked known in the app. */
    MINE,
    /** Marked ignored in the app (names and such). */
    IGNORED,
    UNKNOWN,
}

data class TappableWord(val span: WordSpan, val state: WordState)

/** A sentence's words, and where the unknown ones are. */
data class SentenceWords(val words: List<TappableWord> = emptyList(), val unknown: List<IntRange> = emptyList())

@Singleton
class SentenceRepository @Inject constructor(
    private val decks: DeckLibrary,
    private val media: MediaLibrary,
    private val knownWords: KnownWordsRepository,
    private val myWords: MyWords,
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
        // A song is sung in full: every line in order, whatever its words, and none is cooled down.
        if (isSong(source)) {
            val list = pool.orEmpty()
            val start = list.indexOfFirst { it.id == afterId } + 1
            return@withLock list.getOrNull(start) ?: list.firstOrNull()
        }
        val tokenizer = tokenizers.forLanguage(language)
        val known = knownSet(language, tokenizer)
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
            if (!source.ordered && start > 0) {
                list = list.shuffled()
                pool = list
            }
            list.firstOrNull { it.id != afterId && eligible(it) }
                ?: list.firstOrNull { it.id == afterId && eligible(it) }
        }
    }

    /**
     * Every sentence of the current source in timeline order, whatever their words, for sources
     * that [PracticeSource.supportsChain]; null for a shuffled source. Chains of neighboring
     * sentences are cut from it.
     */
    suspend fun timeline(): List<Sentence>? = mutex.withLock {
        val current = settings.current()
        if (!current.source.supportsChain) return@withLock null
        if (pool == null || poolSource != current.source || poolLanguage != current.language) {
            pool = loadPool(current.source, current.language)
            poolSource = current.source
            poolLanguage = current.language
        }
        pool
    }

    /**
     * The words of [text] as shown, each with whether it's known and how, and where the unknown
     * ones are (to highlight them).
     */
    suspend fun wordsIn(text: String): SentenceWords = mutex.withLock {
        val language = settings.current().language
        val tokenizer = tokenizers.forLanguage(language)
        val known = knownSet(language, tokenizer)
        val mine = myWords.all(language)
        withContext(Dispatchers.Default) {
            val unknown = tokenizer.unknownRanges(text, known)
            val words = tokenizer.wordSpans(text).map { span ->
                val status = when (mine[span.key]) {
                    true -> WordState.IGNORED
                    false -> WordState.MINE
                    null -> if (unknown.any { it.first <= span.range.last && span.range.first <= it.last }) WordState.UNKNOWN else WordState.KNOWN
                }
                TappableWord(span, status)
            }
            SentenceWords(words, unknown)
        }
    }

    /** Marks [word] known (or ignored) for the current language, or forgets it with null. */
    suspend fun setWord(word: String, ignored: Boolean?) {
        val language = settings.current().language
        if (ignored == null) myWords.remove(language, word) else myWords.set(language, word, ignored)
        // Verdicts change, but practice goes on where it is: no new generation.
        mutex.withLock { knownCache.clear() }
    }

    /** Every known word of the current language, for a file to import elsewhere (Jiten, Migaku). */
    suspend fun exportableKnownWords(): List<String> = mutex.withLock {
        val language = settings.current().language
        (knownWords.words(language) + loadCardWords(language) + myWords.known(language)).distinct()
    }

    /** The stored state of a sentence, so an undo can put it back. */
    suspend fun stateOf(id: Long): SentenceState? = dao.get(id)

    /** Puts back what [stateOf] returned before an action (null: the sentence had no state). */
    suspend fun restore(id: Long, state: SentenceState?) {
        if (state == null) dao.delete(id) else dao.upsert(state)
        mutex.withLock { knownCache.remove(id) }
    }


    /** Whether [source] is a song, whose lines aren't filtered by words or cooled down. */
    fun isSong(source: PracticeSource): Boolean =
        source is PracticeSource.Media && media.items.value.any { it.id == source.id && it.song }

    /**
     * Rebuilds the current source's lines after something changed in them (a song's timing),
     * without a new generation: practice goes on where it is.
     */
    suspend fun refreshTimeline() = mutex.withLock { pool = null }

    suspend fun markCrystalClear(id: Long) {
        if (isSong(settings.current().source)) return // songs have no cooldown
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

    /** Your word list, the cards' words and the words you marked known or ignored. */
    private suspend fun knownSet(language: Language, tokenizer: SentenceTokenizer): Set<String> =
        (knownWords.words(language) + loadCardWords(language) + myWords.counted(language)).mapTo(HashSet(), tokenizer::normalize)

    private suspend fun loadCardWords(language: Language): Set<String> =
        cardWords ?: runCatching { decks.readWords(settings.current().mapping, language) }.getOrDefault(emptySet())
            .also { cardWords = it }

    companion object {
        const val COOLDOWN_DAYS = 10L
    }
}
