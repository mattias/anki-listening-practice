package dev.lovkvist.listening.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lovkvist.listening.R
import dev.lovkvist.listening.data.PracticeSource
import dev.lovkvist.listening.data.Sentence
import dev.lovkvist.listening.l10n.displayName
import dev.lovkvist.listening.l10n.label
import dev.lovkvist.listening.playback.PracticeController
import dev.lovkvist.listening.playback.PracticeState
import dev.lovkvist.listening.playback.PracticeStats
import dev.lovkvist.listening.text.Language
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val stats by practice.stats.collectAsStateWithLifecycle()
    var confirmBlacklist by remember { mutableStateOf(false) }
    var showPlayback by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Header(sources, source, practice::selectSource, language, practice::selectLanguage, onOpenSettings)
            Spacer(Modifier.height(12.dp))
            StatsRow(stats)

            when (val s = state) {
                PracticeState.Loading -> Centered {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.loading_collection), textAlign = TextAlign.Center)
                }

                is PracticeState.Message -> Centered {
                    Text(s.text, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = practice::rebuild) { Text(stringResource(R.string.retry)) }
                }

                is PracticeState.Ready -> {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Spacer(Modifier.height(16.dp))
                        PlayRing(playing, repeats, autoClearAfter, practice.player::clipProgress, practice::togglePlay)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (autoClearAfter > 0) {
                                stringResource(R.string.play_n_of_m, minOf(repeats + 1, autoClearAfter), autoClearAfter)
                            } else {
                                stringResource(R.string.repeats, repeats)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        // A new sentence gets a fresh card, so it never flips past the old one's text.
                        key(s.current.id) {
                            SentenceCard(s.current, language, s.showText, practice::toggleText)
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    PlaybackChips(speed, delayMs, autoClearAfter) { showPlayback = true }
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

    if (showPlayback) {
        ModalBottomSheet(onDismissRequest = { showPlayback = false }) {
            Column(
                Modifier
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.playback), style = MaterialTheme.typography.titleLarge)
                PlaybackSettings(
                    speed = speed,
                    delayMs = delayMs,
                    autoClearAfter = autoClearAfter,
                    onSpeed = { practice.setSpeed(it) },
                    onDelay = { practice.setDelay(it) },
                    onAutoClear = { practice.setAutoClearAfter(it) },
                )
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

/** Source and study language as menus, and the way to Settings. */
@Composable
private fun Header(
    sources: List<Pair<PracticeSource, String>>,
    source: PracticeSource,
    onSource: (PracticeSource) -> Unit,
    language: Language,
    onLanguage: (Language) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ankiName = stringResource(R.string.anki_cards)
    fun nameOf(name: String) = name.ifEmpty { ankiName }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // The source name gets all the room the language pill and the icon leave.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            PillMenu(
                label = nameOf(sources.firstOrNull { it.first == source }?.second.orEmpty()),
                icon = if (source is PracticeSource.Media) R.drawable.ic_video_library else R.drawable.ic_style,
                items = sources.map { (s, name) -> nameOf(name) to { if (s != source) onSource(s) } },
                modifier = Modifier.weight(1f, fill = false),
                emphasized = true,
            )
            Spacer(Modifier.width(8.dp))
            PillMenu(
                label = language.nativeName,
                icon = null,
                items = Language.entries.sortedBy { it.displayName() }.map { l -> l.label() to { if (l != language) onLanguage(l) } },
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings))
        }
    }
}

@Composable
private fun PillMenu(
    label: String,
    @DrawableRes icon: Int?,
    items: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(onClick = { open = true }, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(painterResource(icon), contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((name, action) in items) {
                DropdownMenuItem(
                    text = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }
        }
    }
}

@Composable
private fun StatsRow(stats: PracticeStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(stats.today.toString(), stringResource(R.string.stats_today), null, Modifier.weight(1f))
        StatTile(stats.clearToday.toString(), stringResource(R.string.stats_clear), R.drawable.ic_check, Modifier.weight(1f))
        StatTile(stats.streak.toString(), stringResource(R.string.stats_streak), R.drawable.ic_local_fire_department, Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(value: String, label: String, @DrawableRes icon: Int?, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(painterResource(icon), contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(4.dp))
                }
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The play button inside a ring. With auto Crystal Clear on, the ring fills over all the plays
 * ("2 of 5"); otherwise it shows how far the current play has come.
 */
@Composable
private fun PlayRing(playing: Boolean, repeats: Int, autoClearAfter: Int, progress: () -> Float, onClick: () -> Unit) {
    var clip by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        while (true) {
            clip = progress()
            delay(if (playing) 50 else 300)
        }
    }
    // During the pause after a play, that play is already counted in [repeats].
    val fraction = if (autoClearAfter > 0) {
        ((repeats + if (clip >= 1f) 0f else clip) / autoClearAfter).coerceIn(0f, 1f)
    } else {
        clip
    }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .size(184.dp)
            .drawBehind {
                val width = 8.dp.toPx()
                val inset = width / 2
                val arcSize = Size(size.width - width, size.height - width)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(width))
                drawArc(fill, -90f, 360f * fraction, false, Offset(inset, inset), arcSize, style = Stroke(width, cap = StrokeCap.Round))
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(onClick = onClick, shape = CircleShape, color = fill, modifier = Modifier.size(144.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                    contentDescription = null,
                    Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/** The sentence, face down until tapped; it flips over to show the text. */
@Composable
private fun SentenceCard(sentence: Sentence, language: Language, shown: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (shown) 180f else 0f, tween(450), label = "flip")
    Card(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 16 * density
            },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp)
                .padding(20.dp)
                .graphicsLayer { if (rotation > 90f) rotationY = 180f },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (rotation > 90f) {
                SentenceText(sentence, language)
                Spacer(Modifier.height(12.dp))
                Hint(R.drawable.ic_visibility_off, stringResource(R.string.hide_text))
            } else {
                for (width in listOf(0.85f, 0.6f, 0.72f)) {
                    Box(
                        Modifier
                            .fillMaxWidth(width)
                            .padding(vertical = 5.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Hint(R.drawable.ic_menu_book, stringResource(R.string.show_text))
            }
        }
    }
}

@Composable
private fun Hint(@DrawableRes icon: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
private fun SentenceText(sentence: Sentence, language: Language) {
    // Tagging the text with its language makes Android draw the right Han glyph variants.
    val style = MaterialTheme.typography.headlineSmall.copy(
        fontFamily = FontFamily.Serif,
        localeList = LocaleList(language.textLocale),
    )
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

private fun secondsLabel(ms: Long) = if (ms % 1000 == 0L) "${ms / 1000}" else "%.1f".format(ms / 1000.0)

/** Speed, pause and auto Crystal Clear at a glance; tapping opens the sheet to change them. */
@Composable
private fun PlaybackChips(speed: Float, delayMs: Long, autoClearAfter: Int, onClick: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val chips = listOf(
            R.drawable.ic_speed to "%.2f×".format(speed),
            R.drawable.ic_hourglass_empty to stringResource(R.string.seconds_short, secondsLabel(delayMs)),
            R.drawable.ic_repeat to if (autoClearAfter == 0) stringResource(R.string.auto_off) else stringResource(R.string.auto_n, autoClearAfter),
        )
        for ((icon, text) in chips) {
            Surface(
                onClick = onClick,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(icon), contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (d in delayOptions) {
            FilterChip(
                selected = d == delayMs,
                onClick = { onDelay(d) },
                label = { Text(stringResource(R.string.seconds_short, secondsLabel(d))) },
            )
        }
    }
    // Hands-free: after this many plays the sentence counts as Crystal Clear and the next one starts.
    Text(stringResource(R.string.auto_clear_after), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
    val pulse = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Button(
        onClick = {
            scope.launch {
                pulse.animateTo(1.05f, tween(90))
                pulse.animateTo(1f, spring(dampingRatio = 0.4f))
            }
            onClear()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .graphicsLayer {
                scaleX = pulse.value
                scaleY = pulse.value
            },
        shape = RoundedCornerShape(20.dp),
    ) {
        Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.crystal_clear), fontSize = 20.sp)
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            onClick = onSkip,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(painterResource(R.drawable.ic_skip_next), contentDescription = null, Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.skip), fontSize = 16.sp)
        }
        OutlinedButton(
            onClick = onBlacklist,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(painterResource(R.drawable.ic_block), contentDescription = null, Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.blacklist), fontSize = 16.sp)
        }
    }
    Spacer(Modifier.height(12.dp))
}
