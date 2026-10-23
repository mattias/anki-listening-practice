package dev.lovkvist.listening.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lovkvist.listening.data.SentenceRepository
import dev.lovkvist.listening.data.PracticeSource
import dev.lovkvist.listening.data.Sentence
import dev.lovkvist.listening.data.media.MediaLibrary
import dev.lovkvist.listening.data.settings.SettingsRepository
import dev.lovkvist.listening.playback.LoopPlayer
import dev.lovkvist.listening.text.Language
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

sealed interface PracticeState {
    data object Loading : PracticeState
    data class Message(val text: String) : PracticeState
    data class Ready(
        val current: Sentence,
        val showText: Boolean,
    ) : PracticeState
}

@HiltViewModel
class PracticeViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: SentenceRepository,
    private val settings: SettingsRepository,
    media: MediaLibrary,
) : ViewModel() {

    val player = LoopPlayer(context, viewModelScope)

    private val _state = MutableStateFlow<PracticeState>(PracticeState.Loading)
    val state: StateFlow<PracticeState> = _state.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _delayMs = MutableStateFlow(1000L)
    val delayMs: StateFlow<Long> = _delayMs.asStateFlow()

    private val _autoClearAfter = MutableStateFlow(0)
    /** Plays before a sentence is marked Crystal Clear automatically; 0 = off. */
    val autoClearAfter: StateFlow<Int> = _autoClearAfter.asStateFlow()

    /** Everything that can be practiced: Anki cards plus each video/audio in the current language. */
    val sources: StateFlow<List<Pair<PracticeSource, String>>> = combine(media.items, settings.settings) { items, s ->
        listOf(PracticeSource.Anki to "Anki cards") +
            items.filter { it.language == s.language }.map { PracticeSource.Media(it.id) to it.name }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, listOf(PracticeSource.Anki to "Anki cards"))

    val language: StateFlow<Language> = settings.settings.map { it.language }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Language.JAPANESE)

    val source: StateFlow<PracticeSource> = settings.settings.map { it.source }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PracticeSource.Anki)

    fun selectSource(source: PracticeSource) {
        viewModelScope.launch {
            settings.setSource(source)
            repository.invalidate(collection = false, known = false)
        }
    }

    /** Serializes moving between sentences, so quick taps can't skip two at once. */
    private val lock = Mutex()

    init {
        player.onPlayLimit = { crystalClear() }
        viewModelScope.launch {
            val s = settings.current()
            setSpeed(s.speed, persist = false)
            setDelay(s.delayMs, persist = false)
            setAutoClearAfter(s.autoClearAfter, persist = false)
        }
        viewModelScope.launch {
            // Initial load, and again whenever the collection, mapping or known words change.
            repository.generation.collect { rebuild() }
        }
    }

    /** Starts over from the source's first sentence, keeping audio paused unless it was playing. */
    fun rebuild() {
        viewModelScope.launch {
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
        if (persist) viewModelScope.launch { settings.setSpeed(rounded) }
    }

    fun setDelay(ms: Long, persist: Boolean = true) {
        _delayMs.value = ms
        player.delayMs = ms
        if (persist) viewModelScope.launch { settings.setDelayMs(ms) }
    }

    fun setAutoClearAfter(plays: Int, persist: Boolean = true) {
        _autoClearAfter.value = plays
        player.playLimit = plays
        if (persist) viewModelScope.launch { settings.setAutoClearAfter(plays) }
    }

    /** Records [action] for the current sentence and moves to the next one. */
    private fun advance(action: suspend (Sentence) -> Unit) {
        viewModelScope.launch {
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
                    if (source.value is PracticeSource.Media) {
                        "No more sentences you know in this video. Pick another source, or reset " +
                            "cool-downs in Settings to go through it again."
                    } else {
                        "No eligible sentences. Import your known words, check the note type preset " +
                            "in Settings, or wait for cool-downs to expire."
                    },
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
        player.load(sentence.audio, sentence.clip, autoPlay)
    }

    override fun onCleared() {
        player.release()
    }
}
