package dev.lovkvist.listening.ui

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lovkvist.listening.data.PracticeSource
import dev.lovkvist.listening.data.Sentence
import dev.lovkvist.listening.text.Language

private val delayOptions = listOf(0L, 500L, 1000L, 2000L, 3000L, 5000L)
private val autoClearOptions = listOf(0, 3, 5, 10, 20)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(vm: PracticeViewModel, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val playing by vm.player.isPlaying.collectAsStateWithLifecycle()
    val repeats by vm.player.repeats.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val delayMs by vm.delayMs.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()
    val autoClearAfter by vm.autoClearAfter.collectAsStateWithLifecycle()
    var confirmBlacklist by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { SourcePicker(sources, source, vm::selectSource) },
                actions = { TextButton(onClick = onOpenSettings) { Text("Settings") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            when (val s = state) {
                PracticeState.Loading -> Box {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Reading collection and matching known words…")
                }

                is PracticeState.Message -> Box {
                    Text(s.text, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = vm::rebuild) { Text("Retry") }
                }

                is PracticeState.Ready -> {
                    Text(
                        if (autoClearAfter > 0) {
                            "Play ${minOf(repeats + 1, autoClearAfter)} of $autoClearAfter, then Crystal Clear"
                        } else {
                            "Repeats: $repeats"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        LargeFloatingActionButton(onClick = vm::togglePlay) {
                            Text(if (playing) "❚❚" else "▶", fontSize = 32.sp)
                        }
                        Spacer(Modifier.height(24.dp))
                        OutlinedButton(onClick = vm::toggleText) {
                            Text(if (s.showText) "Hide Text" else "Show Text")
                        }
                        if (s.showText) {
                            Spacer(Modifier.height(16.dp))
                            SentenceText(s.current, language)
                        }
                    }

                    PlaybackSettings(
                        speed = speed,
                        delayMs = delayMs,
                        autoClearAfter = autoClearAfter,
                        onSpeed = { vm.setSpeed(it) },
                        onDelay = { vm.setDelay(it) },
                        onAutoClear = { vm.setAutoClearAfter(it) },
                    )
                    Spacer(Modifier.height(12.dp))
                    ActionButtons(
                        onClear = vm::crystalClear,
                        onSkip = vm::skip,
                        onBlacklist = { confirmBlacklist = true },
                    )
                }
            }
        }
    }

    if (confirmBlacklist) {
        AlertDialog(
            onDismissRequest = { confirmBlacklist = false },
            title = { Text("Blacklist this sentence?") },
            text = { Text("It won't be played again. You can restore it in Settings → Progress.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmBlacklist = false
                    vm.blacklist()
                }) { Text("Blacklist") }
            },
            dismissButton = { TextButton(onClick = { confirmBlacklist = false }) { Text("Cancel") } },
        )
    }
}

/** The top bar title doubles as a menu of everything you can practice. */
@Composable
private fun SourcePicker(
    sources: List<Pair<PracticeSource, String>>,
    current: PracticeSource,
    onSelect: (PracticeSource) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = sources.firstOrNull { it.first == current }?.second ?: "Anki cards"
    TextButton(onClick = { open = true }) {
        Text("$label ▾", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        for ((source, name) in sources) {
            DropdownMenuItem(
                text = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                onClick = {
                    open = false
                    if (source != current) onSelect(source)
                },
            )
        }
    }
}

@Composable
private fun Box(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

@Composable
private fun SentenceText(sentence: Sentence, language: Language) {
    // Tagging the text with its language makes Android draw the right Han glyph variants.
    val style = MaterialTheme.typography.headlineSmall.copy(localeList = LocaleList(language.textLocale))
    if (language == Language.JAPANESE || sentence.furigana.isNotBlank()) {
        // Without a furigana field the sentence still goes through RubyText, so it lays out the same.
        RubyText(furigana = sentence.furigana.ifBlank { sentence.text }, style = style)
    } else {
        // Plain text keeps words whole when wrapping, and Arabic letters joined.
        Text(sentence.text, style = style, textAlign = TextAlign.Center)
    }
    if (sentence.translation.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(
            sentence.translation,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PlaybackSettings(
    speed: Float,
    delayMs: Long,
    autoClearAfter: Int,
    onSpeed: (Float) -> Unit,
    onDelay: (Long) -> Unit,
    onAutoClear: (Int) -> Unit,
) {
    Text("Speed: ${"%.2f".format(speed)}x", style = MaterialTheme.typography.labelLarge)
    Slider(value = speed, onValueChange = onSpeed, valueRange = 0.5f..1.5f, steps = 19)
    Text("Pause between repeats", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (d in delayOptions) {
            FilterChip(
                selected = d == delayMs,
                onClick = { onDelay(d) },
                label = { Text(if (d % 1000 == 0L) "${d / 1000}s" else "${d / 1000.0}s") },
            )
        }
    }
    // Hands-free: after this many plays the sentence counts as Crystal Clear and the next one starts.
    Text("Auto Crystal Clear after", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (n in autoClearOptions) {
            FilterChip(
                selected = n == autoClearAfter,
                onClick = { onAutoClear(n) },
                label = { Text(if (n == 0) "Off" else "$n×") },
            )
        }
    }
}

@Composable
private fun ActionButtons(onClear: () -> Unit, onSkip: () -> Unit, onBlacklist: () -> Unit) {
    Button(
        onClick = onClear,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
    ) { Text("Crystal Clear", fontSize = 20.sp) }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            onClick = onSkip,
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
        ) { Text("Skip", fontSize = 18.sp) }
        Button(
            onClick = onBlacklist,
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) { Text("Blacklist", fontSize = 18.sp) }
    }
    Spacer(Modifier.size(8.dp))
}
