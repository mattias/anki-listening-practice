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
import dev.lovkvist.listening.data.media.YouTubeImporter
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

/** The progress and outcome of timing a song's lyrics from the video's captions. */
sealed interface AutoTimeState {
    data object Idle : AutoTimeState
    data object Working : AutoTimeState
    /** The offset that was set, in ms. */
    data class Done(val offsetMs: Long) : AutoTimeState
    /** The video has no automatic captions in the language. */
    data object NoCaptions : AutoTimeState
    /** The captions don't look enough like the lyrics. */
    data object NoMatch : AutoTimeState
    data class Failed(val message: String) : AutoTimeState
}

sealed interface PracticeState {
    data object Loading : PracticeState
    data class Message(val text: String) : PracticeState
    data class Ready(
        /** What is shown and played: one sentence, or a chain of neighboring ones as a single clip. */
        val current: Sentence,
        val showText: Boolean,
        /** The words of the shown text ([displayedText]): for tapping, and the unknown ones to mark. */
        val words: SentenceWords = SentenceWords(),
        /** The sentences [current] is made of; more than one in a chain. */
        val lines: List<Sentence> = listOf(current),
    ) : PracticeState
}

/**
 * The practice session: which sentence is up, and its looping playback. App-wide rather than a
 * view model, so the media notification, headset buttons and [PlaybackService] keep working
 * while the app is in the background. Created and used on the main thread.
 */
@Singleton
class PracticeController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SentenceRepository,
    private val settings: SettingsRepository,
    private val strings: Strings,
    private val days: PracticeDayDao,
    private val dictionaries: DictionaryRepository,
    private val library: MediaLibrary,
    private val youTube: YouTubeImporter,
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

    /** The current timeline source's sentences, or empty when the source is shuffled. */
    private var timeline: List<Sentence> = emptyList()
    /** The chain: [chainStart]..[chainEnd] in [timeline], inclusive. A single sentence when chaining is off. */
    private var chainStart = 0
    private var chainEnd = 0

    private val _chainOn = MutableStateFlow(false)
    /** Neighboring sentences are repeated together as one chain (only for timeline sources). */
    val chainOn: StateFlow<Boolean> = _chainOn.asStateFlow()

    /** What was done to a sentence, so [undo] can take it back. */
    private data class Step(val sentences: List<Sentence>, val clear: Boolean, val before: Map<Long, SentenceState?>, val day: String)

    /** Sentences left behind, newest last. */
    private val history = ArrayDeque<Step>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    /**
     * Everything that can be practiced: Anki cards plus each video/audio in the current language.
     * Anki cards have no name here; it's translated where it's shown.
     */
    val sources: StateFlow<List<Pair<PracticeSource, String>>> = combine(library.items, settings.settings) { items, s ->
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

    /** The current source can chain sentences (a video, not Anki cards). */
    val canChain: StateFlow<Boolean> = source.map { it.supportsChain }
        .stateIn(scope, SharingStarted.Eagerly, false)

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
            _singBack.value = s.singBack
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

    private val _singBack = MutableStateFlow(true)
    /** Songs: a silence as long as the line follows each play, to sing it back. */
    val singBack: StateFlow<Boolean> = _singBack.asStateFlow()

    fun setSingBack(on: Boolean) {
        _singBack.value = on
        player.singBack = on && isSong.value
        scope.launch { settings.setSingBack(on) }
    }

    /** The current source is a song: all its lines are practiced, in order, with no cooldown. */
    val isSong: StateFlow<Boolean> = combine(source, library.items) { s, items ->
        s is PracticeSource.Media && items.any { it.id == s.id && it.song }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch {
            // The silence for singing back is for songs only.
            combine(_singBack, isSong) { on, song -> on && song }.collect { player.singBack = it }
        }
    }

    /** The song's timing offset in ms (0 for other sources). */
    val songOffsetMs: StateFlow<Long> = combine(source, library.items) { s, items ->
        (s as? PracticeSource.Media)?.let { m -> items.firstOrNull { it.id == m.id }?.offsetMs } ?: 0L
    }.stateIn(scope, SharingStarted.Eagerly, 0L)

    /** Moves the song's lyrics [byMs] later (negative: earlier) against the audio, and replays the line. */
    fun adjustSongOffset(byMs: Long) {
        scope.launch { setSongOffset(songOffsetMs.value + byMs) }
    }

    /** Sets the song's timing offset to [offsetMs] and replays the line it is on. */
    private suspend fun setSongOffset(offsetMs: Long) {
        val s = source.value as? PracticeSource.Media ?: return
        lock.withLock {
            val ready = _state.value as? PracticeState.Ready ?: return@withLock
            library.setOffset(s.id, offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS))
            repository.refreshTimeline()
            timeline = repository.timeline().orEmpty()
            val wasPlaying = player.isPlaying.value
            val ids = ready.lines.map { it.id }.toSet()
            val lines = timeline.filter { it.id in ids }
            if (lines.isNotEmpty()) showLines(lines, autoPlay = wasPlaying, keepText = true)
        }
    }

    /** The song was downloaded from YouTube, so its lyrics can be timed against the video's captions. */
    val songAutoTimable: StateFlow<Boolean> = combine(source, library.items) { s, items ->
        s is PracticeSource.Media && items.any { it.id == s.id && it.song && it.sourceUrl != null }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private val _autoTiming = MutableStateFlow<AutoTimeState>(AutoTimeState.Idle)
    /** What auto-timing is doing, and how it ended. */
    val autoTiming: StateFlow<AutoTimeState> = _autoTiming.asStateFlow()

    /**
     * Times the song's lyrics from the video's automatic captions: the lines that look alike show how
     * far the lyrics are off, and that becomes the timing offset. Says in [autoTiming] if it can't.
     */
    fun autoTimeSong() {
        val entry = (source.value as? PracticeSource.Media)?.let { m -> library.items.value.firstOrNull { it.id == m.id } } ?: return
        val url = entry.sourceUrl ?: return
        if (_autoTiming.value == AutoTimeState.Working) return
        scope.launch {
            _autoTiming.value = AutoTimeState.Working
            _autoTiming.value = try {
                val result = youTube.songShift(url, entry.language, library.rawCues(entry.id)) {}
                val shift = result.shiftMs
                when {
                    shift != null -> {
                        setSongOffset(shift)
                        AutoTimeState.Done(shift)
                    }
                    result.found -> AutoTimeState.NoMatch
                    else -> AutoTimeState.NoCaptions
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AutoTimeState.Failed(e.message ?: e.toString())
            }
            // The result also shows in the playback sheet, but under a button that may be out of sight.
            val message = when (val r = _autoTiming.value) {
                is AutoTimeState.Done -> strings(R.string.auto_time_done, "%+.1f".format(r.offsetMs / 1000.0))
                AutoTimeState.NoCaptions -> strings(R.string.auto_time_no_captions)
                AutoTimeState.NoMatch -> strings(R.string.auto_time_no_match)
                is AutoTimeState.Failed -> strings(R.string.auto_time_failed, r.message)
                else -> null
            }
            if (message != null) android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
        }
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

    /** Turns chaining on or off; when on, neighboring sentences can be added to the repeated stretch. */
    fun setChain(on: Boolean) {
        _chainOn.value = on
        reshowChain { if (!on) chainEnd = chainStart }
    }

    /** Adds the next sentence after the chain. To add one before it, move the chain back first. */
    fun extendChain() = reshowChain {
        if (chainEnd < timeline.lastIndex) chainEnd++
    }

    /** Drops the chain's last sentence (never the only one). */
    fun shrinkChain() = reshowChain {
        if (chainEnd > chainStart) chainEnd--
    }

    /** Moves the whole chain [by] sentences along the timeline (negative: back). */
    fun moveChain(by: Int) = reshowChain {
        val shift = by.coerceIn(-chainStart, timeline.lastIndex - chainEnd)
        chainStart += shift
        chainEnd += shift
    }

    /** Applies [change] to the chain and plays the result, keeping the text shown and audio playing as it was. */
    private fun reshowChain(change: () -> Unit) {
        scope.launch {
            lock.withLock {
                if (_state.value !is PracticeState.Ready || timeline.isEmpty() || chainStart < 0) return@withLock
                val wasPlaying = player.isPlaying.value
                change()
                showLines(timeline.subList(chainStart, chainEnd + 1), autoPlay = wasPlaying, keepText = true)
            }
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
                for (s in step.sentences) repository.restore(s.id, step.before[s.id])
                // What that action counted: every sentence of its chain, whatever the chain is now.
                val n = step.sentences.size
                days.subtract(step.day, n, if (step.clear) n else 0)
                show(step.sentences.first(), autoPlay = wasPlaying, chainLength = step.sentences.size)
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
                val ready = _state.value as? PracticeState.Ready ?: return@withLock
                // A chain is marked as a whole; its sentences are the lines it is made of.
                val lines = ready.lines
                // A new sentence continues in the same state: playing on, or staying paused.
                val wasPlaying = player.isPlaying.value
                val before = lines.associate { it.id to repository.stateOf(it.id) }
                lines.forEach { action(it) }
                val today = LocalDate.now().toString()
                history.addLast(Step(lines, clear, before, today))
                while (history.size > MAX_UNDO) history.removeFirst()
                _canUndo.value = true
                days.ensure(today)
                // Every sentence of a chain counts.
                days.add(today, lines.size, if (clear) lines.size else 0)
                show(fetch(afterId = lines.last().id), autoPlay = wasPlaying, chainLength = lines.size)
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

    /**
     * Shows [sentence], with [chainLength] sentences from it on when chaining is on. A chain is
     * played as one clip from its first line's start to its last line's end.
     */
    private suspend fun show(sentence: Sentence?, autoPlay: Boolean, chainLength: Int = chainLength()) {
        if (sentence == null) {
            player.stop()
            return
        }
        timeline = repository.timeline().orEmpty()
        val lines = startChain(sentence, chainLength)
        showLines(lines, autoPlay, keepText = false)
    }

    /** How many sentences the chain has now: 1 when chaining is off. */
    private fun chainLength(): Int = if (_chainOn.value && timeline.isNotEmpty()) chainEnd - chainStart + 1 else 1

    /** Starts a chain at [first]: [length] sentences long if chaining is on, else just [first]. */
    private fun startChain(first: Sentence, length: Int): List<Sentence> {
        val i = timeline.indexOfFirst { it.id == first.id }
        if (i < 0 || !_chainOn.value) {
            chainStart = i
            chainEnd = i
            return listOf(first)
        }
        chainStart = i
        chainEnd = minOf(timeline.lastIndex, i + length - 1)
        return timeline.subList(chainStart, chainEnd + 1)
    }

    /** One sentence for [lines]: their texts one under the other, and a clip spanning all of them. */
    private fun merged(lines: List<Sentence>): Sentence {
        val first = lines.first()
        if (lines.size == 1) return first
        val last = lines.last()
        return first.copy(
            text = lines.joinToString("\n") { it.text },
            furigana = if (lines.all { it.furigana.isBlank() }) "" else lines.joinToString("\n") { it.furigana.ifBlank { it.text } },
            translation = lines.joinToString(" ") { it.translation }.trim(),
            clip = first.clip?.let { it.first..(last.clip?.last ?: it.last) },
        )
    }

    private suspend fun showLines(lines: List<Sentence>, autoPlay: Boolean, keepText: Boolean) {
        val sentence = merged(lines)
        val prev = _state.value as? PracticeState.Ready
        val same = prev?.current?.id == sentence.id
        _state.value = PracticeState.Ready(
            current = sentence,
            showText = (same || keepText) && prev?.showText == true,
            lines = lines,
        )
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
        const val MAX_OFFSET_MS = 120_000L

        // A headset's "next" reaches the player right after its press arrives; this leaves room to spare.
        const val HEADSET_WINDOW_MS = 1000L
    }
}
