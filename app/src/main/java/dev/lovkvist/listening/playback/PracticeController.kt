package dev.lovkvist.listening.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lovkvist.listening.R
import dev.lovkvist.listening.data.PracticeSource
import dev.lovkvist.listening.data.Sentence
import dev.lovkvist.listening.data.SentenceRepository
import dev.lovkvist.listening.data.SentenceWords
import dev.lovkvist.listening.data.dict.DictionaryRepository
import dev.lovkvist.listening.data.dict.LookupResult
import dev.lovkvist.listening.data.local.SentenceState
import dev.lovkvist.listening.text.WordSpan
import dev.lovkvist.listening.data.anki.AnkiText
import dev.lovkvist.listening.data.local.PracticeDayDao
import dev.lovkvist.listening.data.media.MediaLibrary
import dev.lovkvist.listening.data.settings.SettingsRepository
import dev.lovkvist.listening.l10n.Strings
import dev.lovkvist.listening.l10n.displayName
import dev.lovkvist.listening.text.Language
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Today's counts and the run of days with practice, ending today or yesterday. */
data class PracticeStats(val today: Int = 0, val clearToday: Int = 0, val streak: Int = 0)

/**
 * The text as the practice screen draws it: for ruby text, the base text without readings, so
 * positions in it line up with what's drawn.
 */
fun displayedText(sentence: Sentence, language: Language): String =
    if (language == Language.JAPANESE || sentence.furigana.isNotBlank()) {
        AnkiText.ruby(sentence.furigana.ifBlank { sentence.text }).joinToString("") { it.base }
    } else {
        sentence.text
    }

sealed interface PracticeState {
    data object Loading : PracticeState
    data class Message(val text: String) : PracticeState
    data class Ready(
        val current: Sentence,
        val showText: Boolean,
        /** The words of the shown text ([displayedText]): for tapping, and the unknown ones to mark. */
        val words: SentenceWords = SentenceWords(),
    ) : PracticeState
}

/**
 * The practice session: which sentence is up, and its looping playback. App-wide rather than a
 * view model, so the media notification, headset buttons and [PlaybackService] keep working
 * while the app is in the background. Created and used on the main thread.
 */
@Singleton
class PracticeController @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: SentenceRepository,
    private val settings: SettingsRepository,
    private val strings: Strings,
    private val days: PracticeDayDao,
    private val dictionaries: DictionaryRepository,
    media: MediaLibrary,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val player = LoopPlayer(context)

    /**
     * The player the media session sees. "Next" skips the sentence (a double tap on the headset
     * button can mark it Crystal Clear instead) and "previous" plays it again; the playlist's
     * silent gaps stay out of sight.
     */
    val sessionPlayer: Player = object : ForwardingPlayer(player.player) {
        override fun play() = player.play()
        override fun pause() = player.pause()
        override fun seekToNext() = onNext()
        override fun seekToNextMediaItem() = onNext()
        override fun seekToPrevious() = player.restart()
        override fun seekToPreviousMediaItem() = player.restart()
    }

    private val _state = MutableStateFlow<PracticeState>(PracticeState.Loading)
    val state: StateFlow<PracticeState> = _state.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _delayMs = MutableStateFlow(1000L)
    val delayMs: StateFlow<Long> = _delayMs.asStateFlow()

    private val _autoClearAfter = MutableStateFlow(0)
    /** Plays before a sentence is marked Crystal Clear automatically; 0 = off. */
    val autoClearAfter: StateFlow<Int> = _autoClearAfter.asStateFlow()

    private val _autoAdvanceSkip = MutableStateFlow(false)
    /** After [autoClearAfter] plays: skip the sentence instead of marking it Crystal Clear. */
    val autoAdvanceSkip: StateFlow<Boolean> = _autoAdvanceSkip.asStateFlow()

    /** What was done to a sentence, so [undo] can take it back. */
    private data class Step(val sentence: Sentence, val clear: Boolean, val before: SentenceState?, val day: String)

    /** Sentences left behind, newest last. */
    private val history = ArrayDeque<Step>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    /**
     * Everything that can be practiced: Anki cards plus each video/audio in the current language.
     * Anki cards have no name here; it's translated where it's shown.
     */
    val sources: StateFlow<List<Pair<PracticeSource, String>>> = combine(media.items, settings.settings) { items, s ->
        listOf(PracticeSource.Anki to "") +
            items.filter { it.language == s.language }.map { PracticeSource.Media(it.id) to it.name }
    }.stateIn(scope, SharingStarted.Eagerly, listOf(PracticeSource.Anki to ""))

    val language: StateFlow<Language> = settings.settings.map { it.language }
        .stateIn(scope, SharingStarted.Eagerly, Language.JAPANESE)

    /** Starred languages, listed first in the language menu. */
    val favoriteLanguages: StateFlow<Set<Language>> = settings.settings.map { it.favoriteLanguages }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun toggleFavoriteLanguage(l: Language) {
        scope.launch { settings.toggleFavoriteLanguage(l) }
    }

    val source: StateFlow<PracticeSource> = settings.settings.map { it.source }
        .stateIn(scope, SharingStarted.Eagerly, PracticeSource.Anki)

    val stats: StateFlow<PracticeStats> = days.recent().map { list ->
        val today = LocalDate.now()
        val practiced = list.mapTo(HashSet()) { it.day }
        var day = if (today.toString() in practiced) today else today.minusDays(1)
        var streak = 0
        while (day.toString() in practiced) {
            streak++
            day = day.minusDays(1)
        }
        val t = list.firstOrNull { it.day == today.toString() }
        PracticeStats(today = t?.heard ?: 0, clearToday = t?.clear ?: 0, streak = streak)
    }.stateIn(scope, SharingStarted.Eagerly, PracticeStats())

    /** Switches the language you study; each language keeps its own source and words. */
    fun selectLanguage(language: Language) {
        scope.launch {
            settings.setLanguage(language)
            repository.invalidate()
        }
    }

    fun selectSource(source: PracticeSource) {
        scope.launch {
            settings.setSource(source)
            repository.invalidate(collection = false, known = false)
        }
    }

    /** Serializes moving between sentences, so quick taps can't skip two at once. */
    private val lock = Mutex()

    init {
        player.onPlayLimit = { if (_autoAdvanceSkip.value) skip() else crystalClear() }
        scope.launch {
            val s = settings.current()
            setSpeed(s.speed, persist = false)
            setDelay(s.delayMs, persist = false)
            setAutoClearAfter(s.autoClearAfter, persist = false)
            _autoAdvanceSkip.value = s.autoAdvanceSkip
            _headsetDoubleTapClear.value = s.headsetDoubleTapClear
        }
        scope.launch {
            // Initial load, and again whenever the collection, mapping or known words change.
            repository.generation.collect { rebuild() }
        }
    }

    /** Starts over from the source's first sentence, keeping audio paused unless it was playing. */
    fun rebuild() {
        scope.launch {
            lock.withLock {
                val wasPlaying = player.isPlaying.value
                player.stop()
                // Another source or new data: the old sentences can't be stepped back to.
                history.clear()
                _canUndo.value = false
                _state.value = PracticeState.Loading
                show(fetch(afterId = null), autoPlay = wasPlaying)
            }
        }
    }

    /** Retries after an error (e.g. storage access was just granted). */
    fun retryIfStuck() {
        if (_state.value is PracticeState.Message) rebuild()
    }

    fun crystalClear() = advance(clear = true) { repository.markCrystalClear(it.id) }

    fun blacklist() = advance(clear = false) { repository.blacklist(it) }

    fun skip() = advance(clear = false) { }

    fun toggleText() = _state.update { if (it is PracticeState.Ready) it.copy(showText = !it.showText) else it }

    fun togglePlay() = player.toggle()

    fun setSpeed(value: Float, persist: Boolean = true) {
        val rounded = (value * 20).toInt() / 20f // 0.05 steps
        _speed.value = rounded
        player.speed = rounded
        if (persist) scope.launch { settings.setSpeed(rounded) }
    }

    fun setDelay(ms: Long, persist: Boolean = true) {
        _delayMs.value = ms
        player.delayMs = ms
        if (persist) scope.launch { settings.setDelayMs(ms) }
    }

    fun setAutoAdvanceSkip(skip: Boolean) {
        _autoAdvanceSkip.value = skip
        scope.launch { settings.setAutoAdvanceSkip(skip) }
    }

    private val _headsetDoubleTapClear = MutableStateFlow(false)
    /** A double tap on the headset button marks Crystal Clear instead of skipping. */
    val headsetDoubleTapClear: StateFlow<Boolean> = _headsetDoubleTapClear.asStateFlow()

    fun setHeadsetDoubleTapClear(clear: Boolean) {
        _headsetDoubleTapClear.value = clear
        scope.launch { settings.setHeadsetDoubleTapClear(clear) }
    }

    /** When a headset press last arrived: a "next" right after it (or with it) comes from the headset. */
    private var headsetPressAt = 0L

    /** Called for each play/pause or next press from a headset. */
    fun onHeadsetButton() {
        headsetPressAt = SystemClock.uptimeMillis()
    }

    /** "Next": skip, or Crystal Clear when it comes from the headset (its double tap) and that's the setting. */
    private fun onNext() {
        val fromHeadset = SystemClock.uptimeMillis() - headsetPressAt < HEADSET_WINDOW_MS
        if (fromHeadset && _headsetDoubleTapClear.value) crystalClear() else skip()
    }

    private val _sleepAt = MutableStateFlow<Long?>(null)
    /** When the sleep timer pauses playback, in [SystemClock.elapsedRealtime] time; null = off. */
    val sleepAt: StateFlow<Long?> = _sleepAt.asStateFlow()
    private val _sleepMinutes = MutableStateFlow(0)
    /** The minutes chosen for the running timer; 0 when it is off. */
    val sleepMinutes: StateFlow<Int> = _sleepMinutes.asStateFlow()
    private var sleepJob: Job? = null

    /** Pauses playback after [minutes] (0 turns the timer off). Not kept across app restarts. */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        _sleepMinutes.value = maxOf(minutes, 0)
        if (minutes <= 0) {
            _sleepAt.value = null
            return
        }
        val ms = minutes * 60_000L
        _sleepAt.value = SystemClock.elapsedRealtime() + ms
        sleepJob = scope.launch {
            delay(ms)
            player.pause()
            _sleepAt.value = null
            _sleepMinutes.value = 0
        }
    }

    /**
     * Goes back to the sentence before, and takes back what was done to it: its Crystal Clear
     * rest or its blacklisting, and its count in today's stats. Can be repeated.
     */
    fun undo() {
        scope.launch {
            lock.withLock {
                val step = history.removeLastOrNull() ?: return@withLock
                _canUndo.value = history.isNotEmpty()
                val wasPlaying = player.isPlaying.value
                repository.restore(step.sentence.id, step.before)
                days.subtract(step.day, if (step.clear) 1 else 0)
                show(step.sentence, autoPlay = wasPlaying)
            }
        }
    }

    /** Marks a word of the shown sentence known, ignored, or (null) neither, and updates the marks. */
    fun markWord(word: String, ignored: Boolean?) {
        scope.launch {
            repository.setWord(word, ignored)
            val ready = _state.value as? PracticeState.Ready ?: return@launch
            updateWords(ready.current)
        }
    }

    /** Installed dictionaries, to tell whether the studied language has any. */
    val installedDictionaries = dictionaries.installed

    /** Dictionary entries for a tapped word, in the language studied. */
    suspend fun lookup(span: WordSpan): LookupResult = dictionaries.lookup(language.value, span.lookup, span.reading)

    fun setAutoClearAfter(plays: Int, persist: Boolean = true) {
        _autoClearAfter.value = plays
        player.playLimit = plays
        if (persist) scope.launch { settings.setAutoClearAfter(plays) }
    }

    /** Records [action] for the current sentence and moves to the next one. */
    private fun advance(clear: Boolean, action: suspend (Sentence) -> Unit) {
        scope.launch {
            lock.withLock {
                val current = (_state.value as? PracticeState.Ready)?.current ?: return@withLock
                // A new sentence continues in the same state: playing on, or staying paused.
                val wasPlaying = player.isPlaying.value
                val before = repository.stateOf(current.id)
                action(current)
                val today = LocalDate.now().toString()
                history.addLast(Step(current, clear, before, today))
                while (history.size > MAX_UNDO) history.removeFirst()
                _canUndo.value = true
                days.ensure(today)
                days.add(today, if (clear) 1 else 0)
                show(fetch(afterId = current.id), autoPlay = wasPlaying)
            }
        }
    }

    /** The next sentence, or null after showing why there's none. */
    private suspend fun fetch(afterId: Long?): Sentence? =
        try {
            repository.next(afterId) ?: run {
                _state.value = PracticeState.Message(
                    strings(if (source.value is PracticeSource.Media) R.string.no_more_in_video else R.string.no_eligible),
                )
                null
            }
        } catch (e: Exception) {
            _state.value = PracticeState.Message(e.message ?: e.toString())
            null
        }

    private suspend fun show(sentence: Sentence?, autoPlay: Boolean) {
        if (sentence == null) {
            player.stop()
            return
        }
        val prev = _state.value as? PracticeState.Ready
        val same = prev?.current?.id == sentence.id
        _state.value = PracticeState.Ready(current = sentence, showText = same && prev?.showText == true)
        player.metadata = MediaMetadata.Builder()
            .setTitle(sources.value.firstOrNull { it.first == source.value }?.second?.ifEmpty { null } ?: strings(R.string.anki_cards))
            .setArtist(strings(R.string.notification_subtitle, language.value.displayName()))
            .build()
        player.load(sentence.audio, sentence.clip, autoPlay)
        updateWords(sentence)
    }

    private suspend fun updateWords(sentence: Sentence) {
        val words = runCatching { repository.wordsIn(displayedText(sentence, language.value)) }.getOrDefault(SentenceWords())
        _state.update { if (it is PracticeState.Ready && it.current.id == sentence.id) it.copy(words = words) else it }
    }

    private companion object {
        const val MAX_UNDO = 50

        // A headset's "next" reaches the player right after its press arrives; this leaves room to spare.
        const val HEADSET_WINDOW_MS = 1000L
    }
}
