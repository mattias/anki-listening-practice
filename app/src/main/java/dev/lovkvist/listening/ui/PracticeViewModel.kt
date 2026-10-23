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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        val queueSize: Int,
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

    /** Everything that can be practiced: Anki cards plus each imported video/audio. */
    val sources: StateFlow<List<Pair<PracticeSource, String>>> = media.items
        .map { items -> listOf(PracticeSource.Anki to "Anki cards") + items.map { PracticeSource.Media(it.id) to it.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf(PracticeSource.Anki to "Anki cards"))

    val source: StateFlow<PracticeSource> = settings.settings.map { it.source }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PracticeSource.Anki)

    fun selectSource(source: PracticeSource) {
        viewModelScope.launch {
            settings.setSource(source)
            repository.invalidate(collection = false, known = false)
        }
    }

    /** Active queue; the head is the sentence being practiced. */
    private val queue = ArrayDeque<Sentence>()
    private val queueLock = Mutex()

    init {
        viewModelScope.launch {
            val s = settings.current()
            setSpeed(s.speed, persist = false)
            setDelay(s.delayMs, persist = false)
        }
        viewModelScope.launch {
            // Initial load, and again whenever the collection, mapping or known words change.
            repository.generation.collect { rebuild() }
        }
    }

    fun rebuild() {
        viewModelScope.launch {
            queueLock.withLock {
                player.stop()
                queue.clear()
                _state.value = PracticeState.Loading
                refill()
                publish(newSentence = true)
            }
        }
    }

    /** Retries after an error (e.g. storage access was just granted). */
    fun retryIfStuck() {
        if (_state.value is PracticeState.Message) rebuild()
    }

    fun crystalClear() = removeCurrent { repository.markCrystalClear(it.id) }

    fun blacklist() = removeCurrent { repository.blacklist(it.id) }

    fun skip() {
        viewModelScope.launch {
            queueLock.withLock {
                if (queue.isEmpty()) return@withLock
                queue.addLast(queue.removeFirst())
                publish(newSentence = true)
            }
        }
    }

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

    private fun removeCurrent(record: suspend (Sentence) -> Unit) {
        viewModelScope.launch {
            queueLock.withLock {
                val current = queue.removeFirstOrNull() ?: return@withLock
                record(current)
                if (queue.size <= REFILL_AT) {
                    if (queue.isEmpty()) _state.value = PracticeState.Loading
                    refill()
                }
                publish(newSentence = true)
            }
        }
    }

    private suspend fun refill() {
        val exclude = queue.mapTo(HashSet()) { it.id }
        val more = try {
            repository.nextEligible(QUEUE_SIZE - queue.size, exclude)
        } catch (e: Exception) {
            _state.value = PracticeState.Message(e.message ?: e.toString())
            return
        }
        queue.addAll(more)
    }

    private fun publish(newSentence: Boolean) {
        val head = queue.firstOrNull()
        if (head == null) {
            if (_state.value !is PracticeState.Message) {
                _state.value = PracticeState.Message(
                    if (source.value is PracticeSource.Media) {
                        "No more sentences you know in this video. Pick another source, or reset " +
                            "cool-downs in Settings to go through it again."
                    } else {
                        "No eligible sentences. Import your known words, check the note type preset " +
                            "in Settings, or wait for cool-downs to expire."
                    },
                )
            }
            player.stop()
            return
        }
        val prev = _state.value as? PracticeState.Ready
        val changed = newSentence && prev?.current?.id != head.id
        _state.value = PracticeState.Ready(
            current = head,
            queueSize = queue.size,
            showText = if (changed) false else prev?.showText ?: false,
        )
        if (changed || prev == null) player.load(head.audio, head.clip)
    }

    override fun onCleared() {
        player.release()
    }

    companion object {
        const val QUEUE_SIZE = 10
        const val REFILL_AT = 1
    }
}
