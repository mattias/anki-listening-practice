package dev.lovkvist.listening.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lovkvist.listening.data.PracticeSource
import dev.lovkvist.listening.data.SentenceRepository
import dev.lovkvist.listening.data.anki.DeckLibrary
import dev.lovkvist.listening.data.anki.FieldMapping
import dev.lovkvist.listening.data.anki.ImportedDeck
import dev.lovkvist.listening.data.anki.Preset
import dev.lovkvist.listening.data.known.KnownWordsRepository
import dev.lovkvist.listening.data.local.SentenceStateDao
import dev.lovkvist.listening.data.media.MediaEntry
import dev.lovkvist.listening.data.media.MediaLibrary
import dev.lovkvist.listening.data.media.YouTubeImporter
import dev.lovkvist.listening.data.settings.Settings
import dev.lovkvist.listening.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val knownWords: KnownWordsRepository,
    private val sentences: SentenceRepository,
    private val dao: SentenceStateDao,
    private val library: DeckLibrary,
    private val media: MediaLibrary,
    private val youTube: YouTubeImporter,
) : ViewModel() {

    val settings: StateFlow<Settings?> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val knownCount: StateFlow<Int?> = knownWords.count

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _blacklistCount = MutableStateFlow(0)
    val blacklistCount: StateFlow<Int> = _blacklistCount.asStateFlow()

    private val _cooldownCount = MutableStateFlow(0)
    /** Sentences hidden by "Crystal Clear" whose 10-day cool-down hasn't run out. */
    val cooldownCount: StateFlow<Int> = _cooldownCount.asStateFlow()

    init {
        viewModelScope.launch { knownWords.words() } // populates knownCount
    }

    /** Called whenever Settings opens, since practice changes these counts in the meantime. */
    fun refreshCounts() = viewModelScope.launch {
        _blacklistCount.value = dao.blacklistCount()
        _cooldownCount.value = dao.cooldownCount(System.currentTimeMillis())
        _cardWordCount.value = sentences.cardWordCount()
    }

    fun resetCooldowns() = viewModelScope.launch {
        dao.clearCooldowns()
        _cooldownCount.value = 0
        sentences.invalidate(collection = false, known = false)
        _message.value = "Cool-downs reset. All Crystal Clear sentences are back in practice."
    }

    fun importKnownWords(uri: Uri) = viewModelScope.launch {
        _message.value = try {
            val n = knownWords.import(uri)
            sentences.invalidate(collection = false, known = true)
            "Imported $n known words"
        } catch (e: Exception) {
            "Import failed: ${e.message}"
        }
    }

    fun selectPreset(p: Preset) = viewModelScope.launch {
        settingsRepo.setPreset(p)
        sentences.invalidate()
        refreshCounts()
    }

    fun saveMapping(p: Preset, m: FieldMapping) = viewModelScope.launch {
        settingsRepo.setMapping(p, m)
        sentences.invalidate()
        refreshCounts()
        _message.value = "Saved ${p.label} field mapping"
    }

    fun resetMapping(p: Preset) = viewModelScope.launch {
        settingsRepo.resetMapping(p)
        sentences.invalidate()
        refreshCounts()
    }

    val decks: StateFlow<List<ImportedDeck>> = library.decks

    val importProgress: StateFlow<Int?> = library.importProgress

    fun importDeck(uri: Uri) = viewModelScope.launch {
        _message.value = try {
            val deck = library.import(uri)
            sentences.invalidate(collection = true, known = false)
            "Imported ${deck.name}: ${deck.noteCount} notes, ${deck.mediaCount} sentence audio files. " +
                "Removed ${formatBytes(deck.freedBytes)} of unused media (images, word audio, etc.)."
        } catch (e: Exception) {
            "Import failed: ${e.message ?: e}"
        }
    }

    fun removeDeck(id: String) = viewModelScope.launch {
        library.remove(id)
        sentences.invalidate(collection = true, known = false)
        refreshCounts()
    }

    val mediaItems: StateFlow<List<MediaEntry>> = media.items

    private val _mediaBusy = MutableStateFlow<String?>(null)
    /** What a running media import/download is doing, or null when idle. */
    val mediaBusy: StateFlow<String?> = _mediaBusy.asStateFlow()

    fun importMedia(file: Uri, subtitles: Uri) = viewModelScope.launch {
        _mediaBusy.value = "Reading subtitles…"
        _message.value = try {
            val item = media.importLocal(file, subtitles)
            "Added ${item.name}: ${item.chunkCount} subtitle lines. Pick it from the menu at the top of the practice screen."
        } catch (e: Exception) {
            "Import failed: ${e.message ?: e}"
        } finally {
            _mediaBusy.value = null
        }
    }

    fun importYouTube(url: String) = viewModelScope.launch {
        _mediaBusy.value = "Starting…"
        _message.value = try {
            val item = youTube.import(url.trim()) { _mediaBusy.value = it }
            "Added ${item.name}: ${item.chunkCount} subtitle lines. Pick it from the menu at the top of the practice screen."
        } catch (e: Exception) {
            "YouTube download failed: ${e.message ?: e}"
        } finally {
            _mediaBusy.value = null
        }
    }

    fun removeMedia(id: String) = viewModelScope.launch {
        media.remove(id)
        if (settingsRepo.current().source == PracticeSource.Media(id)) settingsRepo.setSource(PracticeSource.Anki)
        sentences.invalidate(collection = true, known = false)
        refreshCounts()
    }

    private val _cardWordCount = MutableStateFlow(0)
    /** Card target words (e.g. Expression) that count as known. */
    val cardWordCount: StateFlow<Int> = _cardWordCount.asStateFlow()

    fun clearBlacklist() = viewModelScope.launch {
        dao.clearBlacklist()
        _blacklistCount.value = 0
        sentences.invalidate(collection = false, known = false)
        _message.value = "Blacklist cleared"
    }

    fun dismissMessage() {
        _message.value = null
    }
}
