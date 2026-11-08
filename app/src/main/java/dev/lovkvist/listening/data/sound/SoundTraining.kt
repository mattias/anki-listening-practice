package dev.lovkvist.listening.data.sound

import dev.lovkvist.listening.data.SentenceRepository
import dev.lovkvist.listening.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What fetching a batch of sentences from the service is doing. */
sealed interface SoundState {
    data object Idle : SoundState

    /** Sending the known words and asking for the batch. */
    data object Preparing : SoundState

    /** The model writes (nothing to count yet) or speaks ([done] of [total]). */
    data class Working(val writing: Boolean, val done: Int, val total: Int) : SoundState

    data class Finished(val added: Int) : SoundState

    /** [reason] is a code from [SoundClient] ("UNREACHABLE:url", "WRONG_TOKEN", ...) or the service's own words. */
    data class Failed(val reason: String) : SoundState
}

/**
 * Fetches sentences for the chosen sound phenomena: the known words go to the service on the computer,
 * which writes, translates and speaks the sentences; they are downloaded here as they are ready.
 * App-wide, so a batch goes on while the settings page is closed.
 */
@Singleton
class SoundTraining @Inject constructor(
    private val client: SoundClient,
    private val library: SoundLibrary,
    private val settings: SettingsRepository,
    private val sentences: SentenceRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow<SoundState>(SoundState.Idle)
    val state: StateFlow<SoundState> = _state.asStateFlow()

    private val _status = MutableStateFlow<Result<SoundStatus>?>(null)
    /** The latest connection test: null before any. */
    val status: StateFlow<Result<SoundStatus>?> = _status.asStateFlow()

    private val _phenomena = MutableStateFlow<List<SoundPhenomenon>>(emptyList())
    /** The phenomena the service offers, as of the last connection test. */
    val phenomena: StateFlow<List<SoundPhenomenon>> = _phenomena.asStateFlow()

    /** Asks the service how it is: reachable, token right, model installed, voice running. */
    fun test() {
        scope.launch {
            _status.value = null
            val s = settings.current()
            _status.value = runCatching {
                client.status(s.soundServerUrl, s.soundToken).also {
                    _phenomena.value = runCatching { client.phenomena(s.soundServerUrl, s.soundToken) }.getOrDefault(_phenomena.value)
                }
            }
        }
    }

    /** Fetches [count] sentences for [phenomena] (ids, or "random" for all). */
    fun generate(phenomena: List<String>, count: Int) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = SoundState.Preparing
            val s = settings.current()
            var batchId: String? = null
            try {
                val words = sentences.exportableKnownWords()
                val vocabulary = client.putVocabulary(s.soundServerUrl, s.soundToken, words)
                batchId = client.createBatch(
                    s.soundServerUrl, s.soundToken, vocabulary, phenomena, count,
                    translationLanguage = (s.translation?.code ?: "en"), maxUnknown = s.maxUnknownWords,
                )
                var added = 0
                val fetched = HashSet<String>()
                while (isActive) {
                    val batch = client.batch(s.soundServerUrl, s.soundToken, batchId)
                    for (item in batch.items) {
                        if (!item.audioReady || item.id in fetched) continue
                        client.downloadAudio(s.soundServerUrl, s.soundToken, batchId, item.id, library.partFile(item.id))
                        library.add(item)
                        fetched += item.id
                        added++
                    }
                    when (batch.state) {
                        "failed" -> {
                            _state.value = SoundState.Failed(batch.error.ifBlank { "failed" })
                            break
                        }
                        "done" -> {
                            _state.value = SoundState.Finished(added)
                            break
                        }
                        else -> _state.value = SoundState.Working(batch.state != "speaking", batch.done, batch.total)
                    }
                    delay(POLL_MS)
                }
                // New sentences: the practice queue picks them up.
                if (added > 0) sentences.invalidate(collection = true, known = false)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.value = SoundState.Failed(e.message ?: e.toString())
            } finally {
                batchId?.let { id -> kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { client.deleteBatch(s.soundServerUrl, s.soundToken, id) } }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = SoundState.Idle
    }

    fun dismiss() {
        if (job?.isActive != true) _state.value = SoundState.Idle
    }

    private companion object {
        const val POLL_MS = 2_000L
    }
}
