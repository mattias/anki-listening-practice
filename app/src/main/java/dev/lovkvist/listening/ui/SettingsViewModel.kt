package dev.lovkvist.listening.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lovkvist.listening.data.SentenceRepository
import dev.lovkvist.listening.data.anki.DeckLibrary
import dev.lovkvist.listening.data.anki.FieldMapping
import dev.lovkvist.listening.data.anki.ImportedDeck
import dev.lovkvist.listening.data.anki.Preset
import dev.lovkvist.listening.data.known.KnownWordsRepository
import dev.lovkvist.listening.data.local.SentenceStateDao
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
) : ViewModel() {

    val settings: StateFlow<Settings?> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val knownCount: StateFlow<Int?> = knownWords.count

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _blacklistCount = MutableStateFlow(0)
    val blacklistCount: StateFlow<Int> = _blacklistCount.asStateFlow()

    init {
        viewModelScope.launch {
            knownWords.words() // populates knownCount
            _blacklistCount.value = dao.blacklistCount()
        }
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
    }

    fun saveMapping(p: Preset, m: FieldMapping) = viewModelScope.launch {
        settingsRepo.setMapping(p, m)
        sentences.invalidate()
        _message.value = "Saved ${p.label} field mapping"
    }

    fun resetMapping(p: Preset) = viewModelScope.launch {
        settingsRepo.resetMapping(p)
        sentences.invalidate()
    }

    val decks: StateFlow<List<ImportedDeck>> = library.decks

    val importProgress: StateFlow<Int?> = library.importProgress

    fun importDeck(uri: Uri) = viewModelScope.launch {
        _message.value = try {
            val deck = library.import(uri)
            sentences.invalidate(collection = true, known = false)
            "Imported ${deck.name}: ${deck.noteCount} notes, ${deck.mediaCount} media files"
        } catch (e: Exception) {
            "Import failed: ${e.message ?: e}"
        }
    }

    fun removeDeck(id: String) = viewModelScope.launch {
        library.remove(id)
        sentences.invalidate(collection = true, known = false)
    }

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
