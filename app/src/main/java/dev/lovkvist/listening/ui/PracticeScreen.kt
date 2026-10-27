package dev.lovkvist.listening.ui

import androidx.compose.ui.platform.LocalContext
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lovkvist.listening.R
import dev.lovkvist.listening.data.PracticeSource
import dev.lovkvist.listening.data.Sentence
import dev.lovkvist.listening.data.SentenceWords
import dev.lovkvist.listening.data.TappableWord
import dev.lovkvist.listening.data.WordState
import dev.lovkvist.listening.data.dict.DictionaryEntry
import dev.lovkvist.listening.data.dict.LookupResult
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
    var tapped by remember { mutableStateOf<TappableWord?>(null) }
    val autoAdvanceSkip by practice.autoAdvanceSkip.collectAsStateWithLifecycle()
    val canUndo by practice.canUndo.collectAsStateWithLifecycle()
    val favorites by practice.favoriteLanguages.collectAsStateWithLifecycle()
    val sleepAt by practice.sleepAt.collectAsStateWithLifecycle()
    val sleepMinutes by practice.sleepMinutes.collectAsStateWithLifecycle()
    val headsetDoubleTapClear by practice.headsetDoubleTapClear.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Header(sources, source, practice::selectSource, language, practice::selectLanguage, favorites, practice::toggleFavoriteLanguage, onOpenSettings)
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
                                stringResource(
                                    if (autoAdvanceSkip) R.string.play_n_of_m_skip else R.string.play_n_of_m,
                                    minOf(repeats + 1, autoClearAfter),
                                    autoClearAfter,
                                )
                            } else {
                                stringResource(R.string.repeats, repeats)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        sleepAt?.let { at -> SleepCountdown(at) { showPlayback = true } }
                        Spacer(Modifier.height(16.dp))
                        // A new sentence gets a fresh card, so it never flips past the old one's text.
                        key(s.current.id) {
                            SentenceCard(s.current, s.words, language, s.showText, practice::toggleText) { tapped = it }
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    PlaybackChips(speed, delayMs, autoClearAfter) { showPlayback = true }
                    Spacer(Modifier.height(12.dp))
                    ActionButtons(
                        onClear = practice::crystalClear,
                        onSkip = practice::skip,
                        onBlacklist = { confirmBlacklist = true },
                        canUndo = canUndo,
                        onUndo = practice::undo,
                    )
                }
            }
        }
    }

    if (showPlayback) {
        Sheet(onDismiss = { showPlayback = false }) {
            Column(
                Modifier
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
                    .verticalScroll(rememberScrollState()),
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
                    autoAdvanceSkip = autoAdvanceSkip,
                    onAutoAdvanceSkip = practice::setAutoAdvanceSkip,
                )
                HeadsetSettings(headsetDoubleTapClear, practice::setHeadsetDoubleTapClear)
                SleepTimerSettings(sleepMinutes, practice::setSleepTimer)
            }
        }
    }

    tapped?.let { word -> WordSheet(word, practice) { tapped = null } }

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
    favorites: Set<Language>,
    onToggleFavorite: (Language) -> Unit,
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
                items = emptyList(),
                menu = { close -> LanguageMenuItems(language, favorites, onLanguage, onToggleFavorite, close) },
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
    /** Menu content in place of [items]; gets a function that closes the menu. */
    menu: (@Composable (close: () -> Unit) -> Unit)? = null,
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
            menu?.invoke { open = false }
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
private fun SentenceCard(
    sentence: Sentence,
    words: SentenceWords,
    language: Language,
    shown: Boolean,
    onToggle: () -> Unit,
    onWord: (TappableWord) -> Unit,
) {
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
                SentenceText(sentence, words, language, onWord, onToggle)
                Spacer(Modifier.height(12.dp))
                Hint(R.drawable.ic_visibility_off, stringResource(R.string.hide_text))
                val context = LocalContext.current
                TextButton(onClick = { explainInGemini(context, sentence, language) }) {
                    Icon(painterResource(R.drawable.ic_auto_awesome), contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.explain_sentence))
                }
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
private fun SentenceText(sentence: Sentence, words: SentenceWords, language: Language, onWord: (TappableWord) -> Unit, onOther: () -> Unit) {
    // A tap on a word opens it; anywhere else in the text hides the text, like the rest of the card.
    val onTap = { pos: Int -> words.words.firstOrNull { pos in it.span.range }?.let(onWord) ?: onOther() }
    val unknown = words.unknown
    // Tagging the text with its language makes Android draw the right Han glyph variants.
    val style = MaterialTheme.typography.headlineSmall.copy(
        fontFamily = FontFamily.Serif,
        localeList = LocaleList(language.textLocale),
    )
    if (language == Language.JAPANESE || sentence.furigana.isNotBlank()) {
        // Without a furigana field the sentence still goes through RubyText, so it lays out the same.
        RubyText(furigana = sentence.furigana.ifBlank { sentence.text }, style = style, highlights = unknown, onTap = onTap)
    } else {
        // Plain text keeps words whole when wrapping, and Arabic letters joined.
        // Unknown words (with n+1 and up) are marked like in RubyText.
        val marked = SpanStyle(color = MaterialTheme.colorScheme.tertiary, textDecoration = TextDecoration.Underline)
        val text = buildAnnotatedString {
            append(sentence.text)
            for (r in unknown) if (r.last < sentence.text.length) addStyle(marked, r.first, r.last + 1)
        }
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            text,
            style = style,
            textAlign = TextAlign.Center,
            onTextLayout = { layout = it },
            modifier = Modifier.pointerInput(words) {
                detectTapGestures { pos -> layout?.let { onTap(it.getOffsetForPosition(pos)) } }
            },
        )
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

/** Time left on the sleep timer, ticking each second; a tap opens the playback settings. */
@Composable
private fun SleepCountdown(at: Long, onClick: () -> Unit) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(at) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val left = maxOf(0L, (at - now + 999) / 1000)
    TextButton(onClick = onClick) {
        Icon(painterResource(R.drawable.ic_bedtime), contentDescription = null, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.sleep_stops_in, "%d:%02d".format(left / 60, left % 60)),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** What a double tap on the headset button does: skip, or mark Crystal Clear. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeadsetSettings(clear: Boolean, onClear: (Boolean) -> Unit) {
    Text(stringResource(R.string.headset_double_tap), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = !clear, onClick = { onClear(false) }, label = { Text(stringResource(R.string.skip)) })
        FilterChip(selected = clear, onClick = { onClear(true) }, label = { Text(stringResource(R.string.crystal_clear)) })
    }
}

/** Pauses playback after a while, for listening in bed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepTimerSettings(minutes: Int, onMinutes: (Int) -> Unit) {
    Text(stringResource(R.string.sleep_timer), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (m in listOf(0, 10, 15, 30, 45, 60)) {
            FilterChip(
                selected = m == minutes,
                onClick = { onMinutes(m) },
                label = { Text(if (m == 0) stringResource(R.string.off) else stringResource(R.string.minutes_short, m)) },
            )
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
    autoAdvanceSkip: Boolean,
    onAutoAdvanceSkip: (Boolean) -> Unit,
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
    // Hands-free: after this many plays the sentence counts as Crystal Clear (or is skipped) and the next one starts.
    Text(stringResource(R.string.auto_advance_after), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (n in autoClearOptions) {
            FilterChip(
                selected = n == autoClearAfter,
                onClick = { onAutoClear(n) },
                label = { Text(if (n == 0) stringResource(R.string.off) else "$n×") },
            )
        }
    }
    Text(stringResource(R.string.auto_then), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = !autoAdvanceSkip, onClick = { onAutoAdvanceSkip(false) }, label = { Text(stringResource(R.string.crystal_clear)) })
        FilterChip(selected = autoAdvanceSkip, onClick = { onAutoAdvanceSkip(true) }, label = { Text(stringResource(R.string.skip)) })
    }
}

@Composable
private fun ActionButtons(onClear: () -> Unit, onSkip: () -> Unit, onBlacklist: () -> Unit, canUndo: Boolean, onUndo: () -> Unit) {
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // Back to the sentence before, taking back what was done to it.
        FilledTonalIconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(52.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(painterResource(R.drawable.ic_undo), contentDescription = stringResource(R.string.undo))
        }
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

/**
 * A bottom sheet that Back closes (the sheet's own Back handling leaves a tall sheet open), kept
 * clear of the navigation bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        BackHandler { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
        Box(Modifier.navigationBarsPadding()) { content() }
    }
}

/**
 * A tapped word: how it counts, buttons to mark it known or ignored, and what the installed
 * dictionaries say about it (frequency, definitions, its kanji).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordSheet(word: TappableWord, practice: PracticeController, onDismiss: () -> Unit) {
    val installed by practice.installedDictionaries.collectAsStateWithLifecycle()
    val language by practice.language.collectAsStateWithLifecycle()
    var result by remember(word) { mutableStateOf<LookupResult?>(null) }
    LaunchedEffect(word) { result = runCatching { practice.lookup(word.span) }.getOrDefault(LookupResult()) }
    val tagColor = MaterialTheme.colorScheme.tertiary

    Sheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val textStyle = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = FontFamily.Serif,
                localeList = LocaleList(language.textLocale),
            )
            // Only words with kanji need their reading, as furigana.
            val hasKanji = word.span.key.any { it.code in 0x3400..0x9FFF }
            Headword(
                word.span.key,
                word.span.reading?.takeIf { hasKanji }.orEmpty(),
                style = textStyle,
                readingColor = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(
                    when (word.state) {
                        WordState.KNOWN -> R.string.word_known
                        WordState.MINE -> R.string.word_mine
                        WordState.IGNORED -> R.string.word_ignored
                        WordState.UNKNOWN -> R.string.word_unknown
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
                color = if (word.state == WordState.UNKNOWN) tagColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (word.state != WordState.KNOWN) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = word.state == WordState.MINE,
                        onClick = {
                            practice.markWord(word.span.key, if (word.state == WordState.MINE) null else false)
                            onDismiss()
                        },
                        label = { Text(stringResource(R.string.mark_known)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(18.dp)) },
                    )
                    FilterChip(
                        selected = word.state == WordState.IGNORED,
                        onClick = {
                            practice.markWord(word.span.key, if (word.state == WordState.IGNORED) null else true)
                            onDismiss()
                        },
                        label = { Text(stringResource(R.string.mark_ignored)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_visibility_off), contentDescription = null, Modifier.size(18.dp)) },
                    )
                }
            }

            val r = result
            when {
                installed.none { it.language == language } -> Text(
                    stringResource(R.string.no_dictionaries),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                r == null -> CircularProgressIndicator(Modifier.size(24.dp))
                r.terms.isEmpty() && r.kanji.isEmpty() -> Text(
                    stringResource(R.string.no_entries),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    if (r.frequencies.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (f in r.frequencies) {
                                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text(
                                        "${f.dictionary}  ${f.value}",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                    for (entry in r.terms) DictionaryEntryView(entry, language, tagColor)
                    if (r.kanji.isNotEmpty()) {
                        HorizontalDivider()
                        Text(stringResource(R.string.kanji), style = MaterialTheme.typography.titleMedium)
                        for (entry in r.kanji) DictionaryEntryView(entry, language, tagColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun DictionaryEntryView(entry: DictionaryEntry, language: Language, tagColor: androidx.compose.ui.graphics.Color) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Headword(
                    entry.expression,
                    entry.reading,
                    style = MaterialTheme.typography.titleMedium.copy(localeList = LocaleList(language.textLocale)),
                    readingColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(entry.dictionary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (entry.tags.isNotEmpty()) {
                Text(entry.tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = tagColor)
            }
            GlossaryText(
                entry.glossary,
                style = MaterialTheme.typography.bodyMedium.copy(localeList = LocaleList(language.textLocale)),
                tagColor = tagColor,
                readingColor = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
