package dev.lovkvist.listening.playback

import android.content.Context
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lovkvist.listening.R
import dev.lovkvist.listening.data.PracticeSource
import dev.lovkvist.listening.data.Sentence
import dev.lovkvist.listening.data.SentenceRepository
import dev.lovkvist.listening.data.media.MediaLibrary
import dev.lovkvist.listening.data.settings.SettingsRepository
import dev.lovkvist.listening.l10n.Strings
import dev.lovkvist.listening.l10n.displayName
import dev.lovkvist.listening.text.Language
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PracticeState {
    data object Loading : PracticeState
    data class Message(val text: String) : PracticeState
    data class Ready(
        val current: Sentence,
        val showText: Boolean,
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
    media: MediaLibrary,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val player = LoopPlayer(context)

    /**
     * The player the media session sees. "Next" (also a double tap on the headset button) skips
     * the sentence and "previous" plays it again; the playlist's silent gaps stay out of sight.
     */
    val sessionPlayer: Player = object : ForwardingPlayer(player.player) {
        override fun play() = player.play()
        override fun pause() = player.pause()
        override fun seekToNext() = skip()
        override fun seekToNextMediaItem() = skip()
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

    val source: StateFlow<PracticeSource> = settings.settings.map { it.source }
        .stateIn(scope, SharingStarted.Eagerly, PracticeSource.Anki)

    fun selectSource(source: PracticeSource) {
        scope.launch {
            settings.setSource(source)
            repository.invalidate(collection = false, known = false)
        }
    }

    /** Serializes moving between sentences, so quick taps can't skip two at once. */
    private val lock = Mutex()

    init {
        player.onPlayLimit = { crystalClear() }
        scope.launch {
            val s = settings.current()
            setSpeed(s.speed, persist = false)
            setDelay(s.delayMs, persist = false)
            setAutoClearAfter(s.autoClearAfter, persist = false)
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
                _state.value = PracticeState.Loading
                show(fetch(afterId = null), autoPlay = wasPlaying)
            }
        }
    }

    /** Retries after an error (e.g. storage access was just granted). */
    fun retryIfStuck() {
        if (_state.value is PracticeState.Message) rebuild()
    }

    fun crystalClear() = advance { repository.markCrystalClear(it.id) }

    fun blacklist() = advance { repository.blacklist(it) }

    fun skip() = advance { }

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

    fun setAutoClearAfter(plays: Int, persist: Boolean = true) {
        _autoClearAfter.value = plays
        player.playLimit = plays
        if (persist) scope.launch { settings.setAutoClearAfter(plays) }
    }

    /** Records [action] for the current sentence and moves to the next one. */
    private fun advance(action: suspend (Sentence) -> Unit) {
        scope.launch {
            lock.withLock {
                val current = (_state.value as? PracticeState.Ready)?.current ?: return@withLock
                // A new sentence continues in the same state: playing on, or staying paused.
                val wasPlaying = player.isPlaying.value
                action(current)
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

    private fun show(sentence: Sentence?, autoPlay: Boolean) {
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
    }
}
