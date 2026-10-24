package dev.lovkvist.listening.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lovkvist.listening.R
import dev.lovkvist.listening.data.PracticeSource
import dev.lovkvist.listening.data.SentenceRepository
import dev.lovkvist.listening.data.anki.DeckLibrary
import dev.lovkvist.listening.data.anki.FieldMapping
import dev.lovkvist.listening.data.anki.ImportedDeck
import dev.lovkvist.listening.data.anki.Preset
import dev.lovkvist.listening.data.known.KnownWordsRepository
import dev.lovkvist.listening.data.local.SentenceState
import dev.lovkvist.listening.data.local.SentenceStateDao
import dev.lovkvist.listening.data.media.MediaEntry
import dev.lovkvist.listening.data.media.MediaLibrary
import dev.lovkvist.listening.data.media.YouTubeImporter
import dev.lovkvist.listening.data.settings.Settings
import dev.lovkvist.listening.data.settings.SettingsRepository
import dev.lovkvist.listening.l10n.Strings
import dev.lovkvist.listening.l10n.displayName
import dev.lovkvist.listening.text.Language
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
    private val strings: Strings,
) : ViewModel() {

    val settings: StateFlow<Settings?> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Imported known words per language. */
    val knownCounts: StateFlow<Map<Language, Int>> = knownWords.counts

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _blacklistCount = MutableStateFlow(0)
    val blacklistCount: StateFlow<Int> = _blacklistCount.asStateFlow()

    private val _blacklisted = MutableStateFlow<List<SentenceState>>(emptyList())
    /** Blacklisted sentences with their text, to restore them one at a time. */
    val blacklisted: StateFlow<List<SentenceState>> = _blacklisted.asStateFlow()

    fun restore(id: Long) = viewModelScope.launch {
        sentences.unblacklist(id)
        refreshCounts()
    }

    private val _cooldownCount = MutableStateFlow(0)
    /** Sentences hidden by "Crystal Clear" whose 10-day cool-down hasn't run out. */
    val cooldownCount: StateFlow<Int> = _cooldownCount.asStateFlow()

    /** Called whenever Settings opens, since practice changes these counts in the meantime. */
    fun refreshCounts() = viewModelScope.launch {
        knownWords.words(settingsRepo.current().language) // populates knownCounts
        _blacklistCount.value = dao.blacklistCount()
        _blacklisted.value = sentences.blacklisted()
        _cooldownCount.value = dao.cooldownCount(System.currentTimeMillis())
        _cardWordCount.value = sentences.cardWordCount()
    }

    fun resetCooldowns() = viewModelScope.launch {
        dao.clearCooldowns()
        _cooldownCount.value = 0
        sentences.invalidate(collection = false, known = false)
        _message.value = strings(R.string.cooldowns_reset_done)
    }

    fun importKnownWords(uri: Uri) = viewModelScope.launch {
        _message.value = try {
            val language = settingsRepo.current().language
            val n = knownWords.import(uri, language)
            sentences.invalidate(collection = false, known = true)
            strings(R.string.known_imported, n)
        } catch (e: Exception) {
            strings(R.string.import_failed, e.message ?: e.toString())
        }
    }

    fun selectLanguage(l: Language) = viewModelScope.launch {
        settingsRepo.setLanguage(l)
        sentences.invalidate()
        refreshCounts()
    }

    fun selectPreset(l: Language, p: Preset) = viewModelScope.launch {
        settingsRepo.setPreset(l, p)
        sentences.invalidate()
        refreshCounts()
    }

    fun saveMapping(p: Preset, l: Language, m: FieldMapping) = viewModelScope.launch {
        settingsRepo.setMapping(p, l, m)
        sentences.invalidate()
        refreshCounts()
        _message.value = strings(R.string.mapping_saved, p.label)
    }

    fun resetMapping(p: Preset, l: Language) = viewModelScope.launch {
        settingsRepo.resetMapping(p, l)
        sentences.invalidate()
        refreshCounts()
    }

    val decks: StateFlow<List<ImportedDeck>> = library.decks

    val importProgress: StateFlow<Int?> = library.importProgress

    fun importDeck(uri: Uri) = viewModelScope.launch {
        _message.value = try {
            val deck = library.import(uri, settingsRepo.current().language)
            sentences.invalidate(collection = true, known = false)
            strings(R.string.deck_imported, deck.name, deck.noteCount, deck.mediaCount, formatBytes(deck.freedBytes))
        } catch (e: Exception) {
            strings(R.string.import_failed, e.message ?: e.toString())
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
        _mediaBusy.value = strings(R.string.reading_subtitles)
        _message.value = try {
            val item = media.importLocal(file, subtitles, settingsRepo.current().language)
            strings(R.string.media_added, item.name, item.chunkCount)
        } catch (e: Exception) {
            strings(R.string.import_failed, e.message ?: e.toString())
        } finally {
            _mediaBusy.value = null
        }
    }

    fun importYouTube(url: String) = viewModelScope.launch {
        _mediaBusy.value = strings(R.string.starting)
        _message.value = try {
            val s = settingsRepo.current()
            val item = youTube.import(url.trim(), s.language, s.translation) { _mediaBusy.value = it }
            item.translation?.let { strings(R.string.media_added_translated, item.name, item.chunkCount, it.displayName()) }
                ?: strings(R.string.media_added, item.name, item.chunkCount)
        } catch (e: Exception) {
            strings(R.string.youtube_failed, e.message ?: e.toString())
        } finally {
            _mediaBusy.value = null
        }
    }

    fun setTranslation(l: Language?) = viewModelScope.launch { settingsRepo.setTranslation(l) }

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
        _blacklisted.value = emptyList()
        sentences.invalidate(collection = false, known = false)
        _message.value = strings(R.string.blacklist_cleared)
    }

    fun dismissMessage() {
        _message.value = null
    }
}
