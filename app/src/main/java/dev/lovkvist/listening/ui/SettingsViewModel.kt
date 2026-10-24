package dev.lovkvist.listening.ui

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lovkvist.listening.data.dict.DictionaryRepository
import dev.lovkvist.listening.data.dict.InstallProgress
import dev.lovkvist.listening.data.dict.InstalledDictionary
import dev.lovkvist.listening.data.dict.RecommendedDictionary
import dev.lovkvist.listening.data.dict.WiktionaryCatalog
import dev.lovkvist.listening.data.known.MyWords
import dev.lovkvist.listening.data.local.WordStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
import dev.lovkvist.listening.ui.theme.DarkMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val knownWords: KnownWordsRepository,
    private val sentences: SentenceRepository,
    private val dao: SentenceStateDao,
    private val library: DeckLibrary,
    private val media: MediaLibrary,
    private val youTube: YouTubeImporter,
    private val strings: Strings,
    private val dictionaries: DictionaryRepository,
    private val wiktionary: WiktionaryCatalog,
    private val myWords: MyWords,
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

    fun setTheme(id: String) = viewModelScope.launch { settingsRepo.setTheme(id) }

    fun setDarkMode(mode: DarkMode) = viewModelScope.launch { settingsRepo.setDarkMode(mode) }

    fun setMaxUnknownWords(n: Int) = viewModelScope.launch {
        settingsRepo.setMaxUnknownWords(n)
        sentences.invalidate(collection = false, known = false)
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
        _blacklisted.value = emptyList()
        sentences.invalidate(collection = false, known = false)
        _message.value = strings(R.string.blacklist_cleared)
    }

    val installedDictionaries: StateFlow<List<InstalledDictionary>> = dictionaries.installed
    val dictionaryProgress: StateFlow<Map<String, InstallProgress>> = dictionaries.progress

    fun installDictionaries(list: List<RecommendedDictionary>) = dictionaries.installRecommended(list)

    fun importDictionary(uri: Uri, name: String) = viewModelScope.launch {
        dictionaries.importUri(uri, name, settingsRepo.current().language)
    }

    fun removeDictionary(id: Long) = dictionaries.remove(id)

    fun setDictionaryEnabled(id: Long, enabled: Boolean) = dictionaries.setEnabled(id, enabled)

    fun setDictionaryAutoUpdate(id: Long, auto: Boolean) = dictionaries.setAutoUpdate(id, auto)

    fun dismissDictionaryError(key: String) = dictionaries.dismissError(key)

    fun moveDictionary(id: Long, by: Int) = dictionaries.move(id, by)

    /** Wiktionary dictionaries on offer, by source and target language (null until loaded). */
    val wiktionaryPairs = wiktionary.pairs
    val wiktionaryFailed = wiktionary.failed

    fun loadWiktionary() = viewModelScope.launch { wiktionary.load() }

    private val _checkingUpdates = MutableStateFlow(false)
    val checkingUpdates: StateFlow<Boolean> = _checkingUpdates.asStateFlow()

    fun checkDictionaryUpdates() = viewModelScope.launch {
        _checkingUpdates.value = true
        val n = dictionaries.checkForUpdates(all = true)
        _checkingUpdates.value = false
        _message.value = strings(R.string.dictionaries_updated, n)
    }

    /** Words marked in sentences, for the language studied. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val myWordList: StateFlow<List<WordStatus>> = settingsRepo.settings.map { it.language }.distinctUntilChanged()
        .flatMapLatest { myWords.observe(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Marks a word known or ignored, or (null) removes it from your words. */
    fun setMyWord(word: String, ignored: Boolean?) = viewModelScope.launch { sentences.setWord(word, ignored) }

    /** Writes every known word, one per line, for Jiten, Migaku or Anki Miner. */
    fun exportKnownWords(uri: Uri) = viewModelScope.launch {
        _message.value = try {
            val words = sentences.exportableKnownWords()
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { w ->
                    words.forEach { w.write(it); w.write("\n") }
                } ?: error(strings(R.string.could_not_open_file))
            }
            strings(R.string.exported_known_words, words.size)
        } catch (e: Exception) {
            strings(R.string.export_failed, e.message ?: e.toString())
        }
    }

    fun setOnboardingDone() = viewModelScope.launch { settingsRepo.setOnboardingDone(true) }

    fun dismissMessage() {
        _message.value = null
    }
}
