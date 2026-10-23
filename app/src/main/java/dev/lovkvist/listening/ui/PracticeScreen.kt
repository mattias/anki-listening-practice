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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lovkvist.listening.data.anki.AnkiSentence
import dev.lovkvist.listening.data.anki.AnkiText

private val delayOptions = listOf(0L, 500L, 1000L, 2000L, 3000L, 5000L)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(vm: PracticeViewModel, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val playing by vm.player.isPlaying.collectAsStateWithLifecycle()
    val repeats by vm.player.repeats.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val delayMs by vm.delayMs.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Listening Practice") },
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
                        "Queue: ${s.queueSize} · Repeats: $repeats",
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
                            SentenceText(s.current)
                        }
                    }

                    PlaybackSettings(
                        speed = speed,
                        delayMs = delayMs,
                        onSpeed = { vm.setSpeed(it) },
                        onDelay = { vm.setDelay(it) },
                    )
                    Spacer(Modifier.height(12.dp))
                    ActionButtons(
                        onClear = vm::crystalClear,
                        onSkip = vm::skip,
                        onBlacklist = vm::blacklist,
                    )
                }
            }
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
private fun SentenceText(sentence: AnkiSentence) {
    val main = if (sentence.furigana.isNotBlank()) furiganaText(sentence.furigana) else AnnotatedString(sentence.text)
    Text(main, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
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

/** Renders `漢字[かんじ]` as 漢字 followed by a small reading, since Compose has no ruby layout. */
@Composable
private fun furiganaText(furigana: String): AnnotatedString {
    val readingStyle = SpanStyle(
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary,
    )
    return buildAnnotatedString {
        for (seg in AnkiText.ruby(furigana)) {
            append(seg.base)
            seg.reading?.let { withStyle(readingStyle) { append("($it)") } }
        }
    }
}

@Composable
private fun PlaybackSettings(
    speed: Float,
    delayMs: Long,
    onSpeed: (Float) -> Unit,
    onDelay: (Long) -> Unit,
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
