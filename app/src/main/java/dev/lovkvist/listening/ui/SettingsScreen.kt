package dev.lovkvist.listening.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lovkvist.listening.data.anki.FieldMapping
import dev.lovkvist.listening.data.anki.ImportedDeck
import dev.lovkvist.listening.data.anki.Preset
import dev.lovkvist.listening.data.media.MediaEntry
import dev.lovkvist.listening.data.update.UpdateState
import dev.lovkvist.listening.text.Language
import dev.lovkvist.listening.text.Reading
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, updateVm: UpdateViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val knownCounts by vm.knownCounts.collectAsStateWithLifecycle()
    val blacklistCount by vm.blacklistCount.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val cooldownCount by vm.cooldownCount.collectAsStateWithLifecycle()
    val cardWordCount by vm.cardWordCount.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Reset?>(null) }
    LaunchedEffect(Unit) { vm.refreshCounts() }

    val pickKnownWords = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importKnownWords)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        val s = settings ?: return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Language")
            LanguagePicker(s.language, vm::selectLanguage)
            Text(
                "Cards, videos and known words are kept per language. Anki Miner Note cards are " +
                    "matched by their Language field.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section("Anki decks")
            Decks(vm)

            Section("Videos & audio")
            MediaItems(vm, s.language)

            Section("Known words")
            val list = when {
                s.language == Language.JAPANESE -> " from Jiten or Anki Miner"
                s.language.inAnkiMiner -> " from Anki Miner"
                else -> ""
            }
            Text(
                when (val n = knownCounts[s.language]) {
                    null -> "Loading…"
                    0 -> "No ${s.language.englishName} words imported yet. Import a word list$list, one word per line."
                    else -> "$n ${s.language.englishName} words from your word list."
                },
            )
            Text("$cardWordCount words from your cards' ${s.mapping.word.ifBlank { "word" }} field.")
            Button(onClick = { pickKnownWords.launch(arrayOf("text/*")) }) { Text("Import known_words.txt") }

            Section("Note type preset")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in Preset.availableFor(s.language)) {
                    FilterChip(selected = p == s.preset, onClick = { vm.selectPreset(s.language, p) }, label = { Text(p.label) })
                }
            }
            if (s.language != Language.JAPANESE) {
                Text(
                    "Kiku, Lapis and Senren are Japanese note types. Other languages use Anki Miner Note.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MappingEditor(
                preset = s.preset,
                language = s.language,
                mapping = s.mapping,
                onSave = { vm.saveMapping(s.preset, s.language, it) },
                onReset = { vm.resetMapping(s.preset, s.language) },
            )

            Section("Progress")
            Text("${sentences(cooldownCount)} cooling down after Crystal Clear.")
            OutlinedButton(onClick = { confirm = Reset.COOLDOWNS }, enabled = cooldownCount > 0) {
                Text("Reset Crystal Clear cool-downs")
            }
            Text("${sentences(blacklistCount)} blacklisted.")
            Blacklist(vm)
            OutlinedButton(onClick = { confirm = Reset.BLACKLIST }, enabled = blacklistCount > 0) {
                Text("Clear blacklist")
            }

            Section("App updates")
            Updates(updateVm)
            Spacer(Modifier.height(24.dp))
        }
    }

    confirm?.let { reset ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(reset.title) },
            text = { Text(reset.body) },
            confirmButton = {
                TextButton(onClick = {
                    when (reset) {
                        Reset.COOLDOWNS -> vm.resetCooldowns()
                        Reset.BLACKLIST -> vm.clearBlacklist()
                    }
                    confirm = null
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }

    message?.let {
        AlertDialog(
            onDismissRequest = vm::dismissMessage,
            confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("OK") } },
            text = { Text(it) },
        )
    }
}

private enum class Reset(val title: String, val body: String) {
    COOLDOWNS(
        "Reset cool-downs?",
        "Every sentence you marked Crystal Clear comes back into practice now instead of after 10 days.",
    ),
    BLACKLIST(
        "Clear blacklist?",
        "Every blacklisted sentence comes back into practice.",
    ),
}

@Composable
private fun Updates(vm: UpdateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val checkOnStart by vm.checkOnStart.collectAsStateWithLifecycle()
    Text("Version ${vm.currentVersion}. New versions are downloaded from GitHub Releases.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Check for updates when the app starts", Modifier.weight(1f))
        Switch(checked = checkOnStart, onCheckedChange = vm::setCheckOnStart)
    }
    if (state is UpdateState.Checking) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text("Checking…")
        }
    } else {
        OutlinedButton(onClick = { vm.check() }) { Text("Check for updates") }
    }
}

/** A menu of every language, labeled in the language itself. */
@Composable
private fun LanguagePicker(current: Language, onSelect: (Language) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }) { Text("${current.label} ▾") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        for (l in Language.entries.sortedBy { it.englishName }) {
            DropdownMenuItem(
                text = { Text(l.label) },
                onClick = {
                    open = false
                    if (l != current) onSelect(l)
                },
            )
        }
    }
}

private fun sentences(n: Int) = if (n == 1) "1 sentence" else "$n sentences"

/** Blacklisted sentences, each with a button to bring it back into practice. */
@Composable
private fun Blacklist(vm: SettingsViewModel) {
    val items by vm.blacklisted.collectAsStateWithLifecycle()
    for (item in items) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    // Sentences blacklisted before 0.4.0 weren't saved with their text.
                    item.text.ifBlank { "Sentence blacklisted before version 0.4.0" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.source.isNotBlank()) {
                    Text(
                        item.source,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = { vm.restore(item.noteId) }) { Text("Restore") }
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Decks(vm: SettingsViewModel) {
    val decks by vm.decks.collectAsStateWithLifecycle()
    val progress by vm.importProgress.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf<ImportedDeck?>(null) }

    // .apkg/.colpkg have no registered MIME type on most devices, so accept anything;
    // the unpacker rejects files that aren't Anki packages.
    val pickDeck = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importDeck)
    }

    Text(
        "In AnkiDroid, export a deck (.apkg) or your collection (.colpkg) with \"Include media\" " +
            "checked, then import it here. Only sentence audio is kept. Re-import after adding cards.",
        style = MaterialTheme.typography.bodyMedium,
    )
    for (deck in decks) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(deck.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${deck.noteCount} notes · ${deck.mediaCount} audio · ${formatBytes(deck.freedBytes)} saved · " +
                        DateFormat.getDateInstance().format(Date(deck.importedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { confirmRemove = deck }) { Text("Remove") }
        }
    }
    val importing = progress
    if (importing != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text("Importing… $importing files")
        }
    } else {
        Button(onClick = { pickDeck.launch(arrayOf("*/*")) }) { Text("Import .apkg / .colpkg") }
    }

    confirmRemove?.let { deck ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${deck.name}?") },
            text = { Text("Its notes and audio are deleted from this app. Your AnkiDroid collection isn't affected.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeDeck(deck.id)
                    confirmRemove = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MediaItems(vm: SettingsViewModel, language: Language) {
    val allItems by vm.mediaItems.collectAsStateWithLifecycle()
    val items = allItems.filter { it.language == language }
    val busy by vm.mediaBusy.collectAsStateWithLifecycle()
    var pickedMedia by remember { mutableStateOf<Uri?>(null) }
    var confirmRemove by remember { mutableStateOf<MediaEntry?>(null) }

    // Two steps: the video/audio first, then its subtitle file.
    val pickSubtitles = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val media = pickedMedia
        pickedMedia = null
        if (uri != null && media != null) vm.importMedia(media, uri)
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedMedia = uri
            pickSubtitles.launch(arrayOf("*/*"))
        }
    }

    Text(
        "Pick a video or audio file, then its ${language.englishName} subtitles (.srt, .vtt or .ass). Each subtitle " +
            "line becomes one sentence, in order. Choose it from the menu at the top of the practice screen.",
        style = MaterialTheme.typography.bodyMedium,
    )
    for (item in items) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${item.chunkCount} subtitle lines · " + DateFormat.getDateInstance().format(Date(item.importedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { confirmRemove = item }) { Text("Remove") }
        }
    }
    if (busy != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text(busy.orEmpty())
        }
    } else {
        Button(onClick = { pickMedia.launch(arrayOf("video/*", "audio/*")) }) { Text("Add video/audio + subtitles") }
        var url by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("YouTube link with ${language.englishName} subtitles") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                vm.importYouTube(url)
                url = ""
            },
            enabled = url.isNotBlank(),
        ) { Text("Download from YouTube") }
    }

    confirmRemove?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${item.name}?") },
            text = {
                Text(
                    if (item.sourceUrl != null) "The downloaded audio and subtitles are deleted."
                    else "Only the app's subtitle copy is deleted; your video file stays where it is.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeMedia(item.id)
                    confirmRemove = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MappingEditor(
    preset: Preset,
    language: Language,
    mapping: FieldMapping,
    onSave: (FieldMapping) -> Unit,
    onReset: () -> Unit,
) {
    var noteType by remember(preset, language, mapping) { mutableStateOf(mapping.noteTypeMatch) }
    var sentence by remember(preset, language, mapping) { mutableStateOf(mapping.sentence) }
    var furigana by remember(preset, language, mapping) { mutableStateOf(mapping.furigana) }
    var translation by remember(preset, language, mapping) { mutableStateOf(mapping.translation) }
    var audio by remember(preset, language, mapping) { mutableStateOf(mapping.audio) }
    var word by remember(preset, language, mapping) { mutableStateOf(mapping.word) }
    var languageField by remember(preset, language, mapping) { mutableStateOf(mapping.language) }

    Field("Note type name contains", noteType) { noteType = it }
    Field("Sentence field", sentence) { sentence = it }
    // Only languages with readings above the text have a reading field.
    when (val reading = language.reading) {
        null -> {}
        Reading.FURIGANA -> Field("Furigana field", furigana) { furigana = it }
        else -> Field("${reading.label} field (optional)", furigana) { furigana = it }
    }
    Field("Translation field (optional)", translation) { translation = it }
    Field("Sentence audio field", audio) { audio = it }
    Field("Word field (counts as known, optional)", word) { word = it }
    Field("Language field (skips cards of other languages, optional)", languageField) { languageField = it }

    Text(
        "Imports keep only the audio from each preset's audio field. If you change the audio field, re-import your decks.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val edited = FieldMapping(noteType, sentence, furigana, translation, audio, word, languageField)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onSave(edited) }, enabled = edited != mapping) { Text("Save mapping") }
        OutlinedButton(onClick = onReset, enabled = mapping != preset.defaults(language)) { Text("Reset to defaults") }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1 shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} KB"
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

