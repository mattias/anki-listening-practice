package dev.lovkvist.listening.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lovkvist.listening.R
import dev.lovkvist.listening.data.dict.RecommendedDictionary
import dev.lovkvist.listening.l10n.AppLanguage
import dev.lovkvist.listening.l10n.displayName
import dev.lovkvist.listening.l10n.label
import dev.lovkvist.listening.text.Language

private const val STEPS = 5

/**
 * First-run setup: the app's language, the language you study, dictionaries, your words and
 * cards, and a few tips. Every step can be skipped; all of it is in Settings later too.
 */
@Composable
fun OnboardingScreen(vm: SettingsViewModel, onDone: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableStateOf(0) }
    val finish = {
        vm.setOnboardingDone()
        onDone()
    }
    BackHandler(enabled = step > 0) { step-- }

    Scaffold { padding ->
        val s = settings ?: return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(STEPS) { i ->
                        Box(
                            Modifier
                                .size(if (i == step) 10.dp else 8.dp)
                                .clip(CircleShape)
                                .background(if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                        )
                    }
                }
                if (step < STEPS - 1) TextButton(onClick = finish) { Text(stringResource(R.string.onboarding_skip)) }
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(16.dp))
                when (step) {
                    0 -> Welcome()
                    1 -> StudyLanguage(s.language, vm::selectLanguage)
                    2 -> Dictionaries(vm, s.language)
                    3 -> WordsAndCards(vm, s.language)
                    else -> Tips()
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step > 0) OutlinedButton(onClick = { step-- }) { Text(stringResource(R.string.back)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = { if (step < STEPS - 1) step++ else finish() }) {
                    Text(stringResource(if (step < STEPS - 1) R.string.onboarding_next else R.string.onboarding_start))
                }
            }
        }
    }

    message?.let {
        AlertDialog(
            onDismissRequest = vm::dismissMessage,
            confirmButton = { TextButton(onClick = vm::dismissMessage) { Text(stringResource(R.string.ok)) } },
            text = { Text(it) },
        )
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineMedium)

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun ColumnScope.Welcome() {
    Box(
        Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .align(Alignment.CenterHorizontally),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_headphones), contentDescription = null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
    }
    Text(stringResource(R.string.onboarding_welcome), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Body(stringResource(R.string.onboarding_welcome_body))
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.app_language), style = MaterialTheme.typography.titleMedium)
    var open by remember { mutableStateOf(false) }
    val current = AppLanguage.current()
    val systemDefault = stringResource(R.string.system_default)
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text("${AppLanguage.available.firstOrNull { it.first == current }?.second ?: systemDefault} ▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((tag, name) in listOf(null to systemDefault) + AppLanguage.available) {
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    open = false
                    if (tag != current) AppLanguage.set(tag)
                })
            }
        }
    }
}

@Composable
private fun StudyLanguage(current: Language, onSelect: (Language) -> Unit) {
    Title(stringResource(R.string.onboarding_study_title))
    Body(stringResource(R.string.onboarding_study_body))
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            // The current language first, so the choice already made is in view.
            for (l in listOf(current) + Language.entries.filter { it != current }.sortedBy { it.displayName() }) {
                val selected = l == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { if (!selected) onSelect(l) }
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(l.label(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun Dictionaries(vm: SettingsViewModel, language: Language) {
    val installed by vm.installedDictionaries.collectAsStateWithLifecycle()
    val progress by vm.dictionaryProgress.collectAsStateWithLifecycle()
    Title(stringResource(R.string.dictionaries))
    val recommended = RecommendedDictionary.forLanguage(language)
    if (recommended.isEmpty()) {
        // Other languages: a dictionary from Wiktionary, with definitions in a language of your choice.
        Body(stringResource(R.string.onboarding_wiktionary_body))
        WiktionaryPicker(vm, language, installed.filter { it.language == language })
        Text(stringResource(R.string.onboarding_dictionaries_background), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Body(stringResource(R.string.onboarding_dictionaries_body))
    var chosen by rememberSaveable { mutableStateOf(recommended.map { it.sourceId }.toSet()) }
    for (r in recommended) {
        val done = installed.any { it.sourceId == r.sourceId }
        val p = progress[r.sourceId]
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                done -> Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.installed), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp))
                p != null && p.error == null -> CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
                else -> Checkbox(checked = r.sourceId in chosen, onCheckedChange = { on -> chosen = if (on) chosen + r.sourceId else chosen - r.sourceId })
            }
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.bodyLarge)
                Text("${r.description} · ${stringResource(R.string.size_mb, r.sizeMb)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                p?.error?.let { Text(stringResource(R.string.dictionary_failed, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    val toInstall = recommended.filter { r -> r.sourceId in chosen && installed.none { it.sourceId == r.sourceId } && progress[r.sourceId]?.error == null && r.sourceId !in progress }
    if (recommended.all { r -> installed.any { it.sourceId == r.sourceId } }) return
    Button(onClick = { vm.installDictionaries(toInstall) }, enabled = toInstall.isNotEmpty()) {
        Text(stringResource(R.string.install))
    }
    Text(stringResource(R.string.onboarding_dictionaries_background), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WordsAndCards(vm: SettingsViewModel, language: Language) {
    val knownCounts by vm.knownCounts.collectAsStateWithLifecycle()
    val decks by vm.decks.collectAsStateWithLifecycle()
    val importing by vm.importProgress.collectAsStateWithLifecycle()
    val pickWords = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importKnownWords) }
    val pickDeck = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importDeck) }
    Title(stringResource(R.string.onboarding_words_title))
    Body(stringResource(R.string.onboarding_words_body))
    Step(R.drawable.ic_abc, stringResource(R.string.known_words), stringResource(R.string.known_count, knownCounts[language] ?: 0)) {
        Button(onClick = { pickWords.launch(arrayOf("text/*")) }) { Text(stringResource(R.string.import_known_words)) }
    }
    val deckCount = decks.count { it.language == language }
    Step(R.drawable.ic_style, stringResource(R.string.anki_decks), if (deckCount > 0) decks.filter { it.language == language }.joinToString { it.name } else stringResource(R.string.decks_help)) {
        if (importing != null) {
            Text(stringResource(R.string.importing_files, importing ?: 0))
        } else {
            Button(onClick = { pickDeck.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.import_deck)) }
        }
    }
    Step(R.drawable.ic_video_library, stringResource(R.string.videos_audio), stringResource(R.string.onboarding_videos_body)) {}
}

@Composable
private fun Step(@DrawableRes icon: Int, title: String, text: String, action: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action()
        }
    }
}

@Composable
private fun Tips() {
    Title(stringResource(R.string.onboarding_tips_title))
    Tip(R.drawable.ic_play_arrow, stringResource(R.string.onboarding_tip_play))
    Tip(R.drawable.ic_menu_book, stringResource(R.string.onboarding_tip_words))
    Tip(R.drawable.ic_undo, stringResource(R.string.onboarding_tip_undo))
    Tip(R.drawable.ic_repeat, stringResource(R.string.onboarding_tip_auto))
    Tip(R.drawable.ic_headphones, stringResource(R.string.onboarding_tip_headset))
}

@Composable
private fun Tip(@DrawableRes icon: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
