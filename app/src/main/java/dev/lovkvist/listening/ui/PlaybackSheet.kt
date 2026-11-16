package dev.lovkvist.listening.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lovkvist.listening.R
import dev.lovkvist.listening.playback.AutoTimeState
import dev.lovkvist.listening.playback.PracticeController
import dev.lovkvist.listening.text.Language
import dev.lovkvist.listening.text.Phenomenon
import kotlinx.coroutines.launch

/** The playback sheet under the sentence: speed, pause, auto-advance, and what only some sources have. */
@Composable
internal fun PlaybackSheet(practice: PracticeController, onDismiss: () -> Unit) {
    val language by practice.language.collectAsStateWithLifecycle()
    val isSong by practice.isSong.collectAsStateWithLifecycle()
    val soundFilter by practice.soundFilter.collectAsStateWithLifecycle()
    val singBack by practice.singBack.collectAsStateWithLifecycle()
    val songOffsetMs by practice.songOffsetMs.collectAsStateWithLifecycle()
    val autoTimable by practice.songAutoTimable.collectAsStateWithLifecycle()
    val autoTiming by practice.autoTiming.collectAsStateWithLifecycle()
    val sleepMinutes by practice.sleepMinutes.collectAsStateWithLifecycle()
    val headsetDoubleTapClear by practice.headsetDoubleTapClear.collectAsStateWithLifecycle()
    Sheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.playback), style = MaterialTheme.typography.titleLarge)
            PlaybackSettings(practice)
            if (language == Language.JAPANESE && !isSong) SoundFilterSettings(soundFilter, practice::setSoundFilter)
            if (isSong) SongSettings(singBack, practice::setSingBack, songOffsetMs, practice::adjustSongOffset, autoTimable, autoTiming, practice::autoTimeSong)
            HeadsetSettings(headsetDoubleTapClear, practice::setHeadsetDoubleTapClear)
            SleepTimerSettings(sleepMinutes, practice::setSleepTimer)
        }
    }
}

private val delayOptions = listOf(0L, 500L, 1000L, 2000L, 3000L, 5000L)
private val autoClearOptions = listOf(0, 3, 5, 10, 20)

private fun secondsLabel(ms: Long) = if (ms % 1000 == 0L) "${ms / 1000}" else "%.1f".format(ms / 1000.0)

/** Speed, pause and auto Crystal Clear at a glance; tapping opens the sheet to change them. */
@Composable
internal fun PlaybackChips(speed: Float, delayMs: Long, autoClearAfter: Int, onClick: () -> Unit) {
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

/** Japanese: practice only sentences where a way of running sounds together can be heard. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SoundFilterSettings(selected: Phenomenon?, onSelect: (Phenomenon?) -> Unit) {
    Text(stringResource(R.string.sound_filter), style = MaterialTheme.typography.labelLarge)
    Text(stringResource(R.string.sound_filter_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(stringResource(R.string.off)) })
        for (p in Phenomenon.entries) {
            FilterChip(selected = selected == p, onClick = { onSelect(p) }, label = { Text(p.example) })
        }
    }
}

/** For songs: a silence to sing each line back in, and the lyrics' timing against the audio. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SongSettings(
    singBack: Boolean,
    onSingBack: (Boolean) -> Unit,
    offsetMs: Long,
    onOffset: (Long) -> Unit,
    autoTimable: Boolean,
    autoTiming: AutoTimeState,
    onAutoTime: () -> Unit,
) {
    Text(stringResource(R.string.sing_back), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = singBack, onClick = { onSingBack(true) }, label = { Text(stringResource(R.string.on)) })
        FilterChip(selected = !singBack, onClick = { onSingBack(false) }, label = { Text(stringResource(R.string.off)) })
    }
    Text(stringResource(R.string.lyrics_timing, "%+.1f".format(offsetMs / 1000.0)), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { onOffset(-5000) }) { Text("−5 s") }
        OutlinedButton(onClick = { onOffset(-500) }) { Text(stringResource(R.string.lyrics_earlier)) }
        OutlinedButton(onClick = { onOffset(500) }) { Text(stringResource(R.string.lyrics_later)) }
        OutlinedButton(onClick = { onOffset(5000) }) { Text("+5 s") }
    }
    if (autoTimable) {
        OutlinedButton(onClick = onAutoTime, enabled = autoTiming != AutoTimeState.Working) {
            Text(stringResource(if (autoTiming == AutoTimeState.Working) R.string.auto_time_working else R.string.auto_time))
        }
        val result = when (autoTiming) {
            is AutoTimeState.Done -> stringResource(R.string.auto_time_done, "%+.1f".format(autoTiming.offsetMs / 1000.0))
            AutoTimeState.NoCaptions -> stringResource(R.string.auto_time_no_captions)
            AutoTimeState.NoMatch -> stringResource(R.string.auto_time_no_match)
            is AutoTimeState.Failed -> stringResource(R.string.auto_time_failed, autoTiming.message)
            else -> null
        }
        if (result != null) Text(result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What a double tap on the headset button does: skip, or mark Crystal Clear. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HeadsetSettings(clear: Boolean, onClear: (Boolean) -> Unit) {
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
internal fun PlaybackSettings(practice: PracticeController) {
    val speed by practice.speed.collectAsStateWithLifecycle()
    val delayMs by practice.delayMs.collectAsStateWithLifecycle()
    val autoClearAfter by practice.autoClearAfter.collectAsStateWithLifecycle()
    val autoAdvanceSkip by practice.autoAdvanceSkip.collectAsStateWithLifecycle()
    val onSpeed = { v: Float -> practice.setSpeed(v) }
    val onDelay = { v: Long -> practice.setDelay(v) }
    val onAutoClear = { v: Int -> practice.setAutoClearAfter(v) }
    val onAutoAdvanceSkip = { v: Boolean -> practice.setAutoAdvanceSkip(v) }
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

/**
 * A bottom sheet that Back closes (the sheet's own Back handling leaves a tall sheet open), kept
 * clear of the navigation bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        BackHandler { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
        Box(Modifier.navigationBarsPadding()) { content() }
    }
}
