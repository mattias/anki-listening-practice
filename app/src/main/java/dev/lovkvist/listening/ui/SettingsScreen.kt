package dev.lovkvist.listening.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lovkvist.listening.data.anki.FieldMapping
import dev.lovkvist.listening.data.anki.ImportedDeck
import dev.lovkvist.listening.data.anki.Preset
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val knownCount by vm.knownCount.collectAsStateWithLifecycle()
    val blacklistCount by vm.blacklistCount.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val cooldownCount by vm.cooldownCount.collectAsStateWithLifecycle()
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
            Section("Anki decks")
            Decks(vm)

            Section("Known words (Jiten .txt)")
            Text(
                when (val n = knownCount) {
                    null -> "Loading…"
                    0 -> "No known words imported yet."
                    else -> "$n known words imported."
                },
            )
            Button(onClick = { pickKnownWords.launch(arrayOf("text/*")) }) { Text("Import known_words.txt") }

            Section("Note type preset")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in Preset.entries) {
                    FilterChip(selected = p == s.preset, onClick = { vm.selectPreset(p) }, label = { Text(p.label) })
                }
            }
            MappingEditor(
                preset = s.preset,
                mapping = s.mapping,
                onSave = { vm.saveMapping(s.preset, it) },
                onReset = { vm.resetMapping(s.preset) },
            )

            Section("Progress")
            Text("$cooldownCount sentences cooling down after Crystal Clear.")
            OutlinedButton(onClick = { confirm = Reset.COOLDOWNS }, enabled = cooldownCount > 0) {
                Text("Reset Crystal Clear cool-downs")
            }
            Text("$blacklistCount sentences blacklisted.")
            OutlinedButton(onClick = { confirm = Reset.BLACKLIST }, enabled = blacklistCount > 0) {
                Text("Clear blacklist")
            }
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
private fun MappingEditor(
    preset: Preset,
    mapping: FieldMapping,
    onSave: (FieldMapping) -> Unit,
    onReset: () -> Unit,
) {
    var noteType by remember(preset, mapping) { mutableStateOf(mapping.noteTypeMatch) }
    var sentence by remember(preset, mapping) { mutableStateOf(mapping.sentence) }
    var furigana by remember(preset, mapping) { mutableStateOf(mapping.furigana) }
    var translation by remember(preset, mapping) { mutableStateOf(mapping.translation) }
    var audio by remember(preset, mapping) { mutableStateOf(mapping.audio) }

    Field("Note type name contains", noteType) { noteType = it }
    Field("Sentence field", sentence) { sentence = it }
    Field("Furigana field", furigana) { furigana = it }
    Field("Translation field (optional)", translation) { translation = it }
    Field("Sentence audio field", audio) { audio = it }

    Text(
        "Imports keep only the audio from each preset's audio field. If you change the audio field, re-import your decks.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val edited = FieldMapping(noteType, sentence, furigana, translation, audio)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onSave(edited) }, enabled = edited != mapping) { Text("Save mapping") }
        OutlinedButton(onClick = onReset, enabled = mapping != preset.defaults) { Text("Reset to defaults") }
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

