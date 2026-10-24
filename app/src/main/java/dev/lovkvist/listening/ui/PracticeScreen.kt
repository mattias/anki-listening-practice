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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lovkvist.listening.R
import dev.lovkvist.listening.data.PracticeSource
import dev.lovkvist.listening.data.Sentence
import dev.lovkvist.listening.playback.PracticeController
import dev.lovkvist.listening.playback.PracticeState
import dev.lovkvist.listening.text.Language

private val delayOptions = listOf(0L, 500L, 1000L, 2000L, 3000L, 5000L)
private val autoClearOptions = listOf(0, 3, 5, 10, 20)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(practice: PracticeController, onOpenSettings: () -> Unit) {
    val state by practice.state.collectAsStateWithLifecycle()
    val playing by practice.player.isPlaying.collectAsStateWithLifecycle()
    val repeats by practice.player.repeats.collectAsStateWithLifecycle()
    val speed by practice.speed.collectAsStateWithLifecycle()
    val delayMs by practice.delayMs.collectAsStateWithLifecycle()
    val sources by practice.sources.collectAsStateWithLifecycle()
    val source by practice.source.collectAsStateWithLifecycle()
    val language by practice.language.collectAsStateWithLifecycle()
    val autoClearAfter by practice.autoClearAfter.collectAsStateWithLifecycle()
    var confirmBlacklist by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { SourcePicker(sources, source, practice::selectSource) },
                actions = { TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings)) } },
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
                    Text(stringResource(R.string.loading_collection))
                }

                is PracticeState.Message -> Box {
                    Text(s.text, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = practice::rebuild) { Text(stringResource(R.string.retry)) }
                }

                is PracticeState.Ready -> {
                    Text(
                        if (autoClearAfter > 0) {
                            stringResource(R.string.play_n_of_m, minOf(repeats + 1, autoClearAfter), autoClearAfter)
                        } else {
                            stringResource(R.string.repeats, repeats)
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
                        LargeFloatingActionButton(onClick = practice::togglePlay) {
                            Text(if (playing) "❚❚" else "▶", fontSize = 32.sp)
                        }
                        Spacer(Modifier.height(24.dp))
                        OutlinedButton(onClick = practice::toggleText) {
                            Text(stringResource(if (s.showText) R.string.hide_text else R.string.show_text))
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
                        onSpeed = { practice.setSpeed(it) },
                        onDelay = { practice.setDelay(it) },
                        onAutoClear = { practice.setAutoClearAfter(it) },
                    )
                    Spacer(Modifier.height(12.dp))
                    ActionButtons(
                        onClear = practice::crystalClear,
                        onSkip = practice::skip,
                        onBlacklist = { confirmBlacklist = true },
                    )
                }
            }
        }
    }

    if (confirmBlacklist) {
        AlertDialog(
            onDismissRequest = { confirmBlacklist = false },
            title = { Text(stringResource(R.string.blacklist_confirm_title)) },
            text = { Text(stringResource(R.string.blacklist_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmBlacklist = false
                    practice.blacklist()
                }) { Text(stringResource(R.string.blacklist)) }
            },
            dismissButton = { TextButton(onClick = { confirmBlacklist = false }) { Text(stringResource(R.string.cancel)) } },
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
    val label = sources.firstOrNull { it.first == current }?.second?.ifEmpty { null } ?: stringResource(R.string.anki_cards)
    TextButton(onClick = { open = true }) {
        Text("$label ▾", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        for ((source, name) in sources) {
            DropdownMenuItem(
                text = { Text(name.ifEmpty { stringResource(R.string.anki_cards) }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
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
    Text(stringResource(R.string.speed, "%.2f".format(speed)), style = MaterialTheme.typography.labelLarge)
    Slider(value = speed, onValueChange = onSpeed, valueRange = 0.5f..1.5f, steps = 19)
    Text(stringResource(R.string.pause_between_repeats), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (d in delayOptions) {
            FilterChip(
                selected = d == delayMs,
                onClick = { onDelay(d) },
                label = { Text(stringResource(R.string.seconds_short, if (d % 1000 == 0L) "${d / 1000}" else "%.1f".format(d / 1000.0))) },
            )
        }
    }
    // Hands-free: after this many plays the sentence counts as Crystal Clear and the next one starts.
    Text(stringResource(R.string.auto_clear_after), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (n in autoClearOptions) {
            FilterChip(
                selected = n == autoClearAfter,
                onClick = { onAutoClear(n) },
                label = { Text(if (n == 0) stringResource(R.string.off) else "$n×") },
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
    ) { Text(stringResource(R.string.crystal_clear), fontSize = 20.sp) }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            onClick = onSkip,
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
        ) { Text(stringResource(R.string.skip), fontSize = 18.sp) }
        Button(
            onClick = onBlacklist,
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) { Text(stringResource(R.string.blacklist), fontSize = 18.sp) }
    }
    Spacer(Modifier.size(8.dp))
}
