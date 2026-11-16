package dev.lovkvist.listening.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lovkvist.listening.R
import dev.lovkvist.listening.data.media.EmbeddedSubtitles
import dev.lovkvist.listening.data.media.EmbeddedTrack
import dev.lovkvist.listening.data.media.MediaEntry
import dev.lovkvist.listening.data.media.SubtitleSource
import dev.lovkvist.listening.l10n.displayName
import dev.lovkvist.listening.text.Language

/**
 * Adding a video or audio file from the device, one step at a time: the file, its subtitles in the language studied (a
 * subtitle file, or a track inside the file if it has one), and optionally subtitles in another language to show as the
 * translation. [translation] is the language translations are usually from; it can be changed here for this file.
 */
@Composable
internal fun AddMediaDialog(vm: SettingsViewModel, language: Language, translation: Language?, onDismiss: () -> Unit) {
    // Saved, so that turning the phone while a file is being picked does not lose the steps already done.
    var media by rememberSaveable { mutableStateOf<Uri?>(null) }
    var tracks by remember { mutableStateOf<List<EmbeddedTrack>>(emptyList()) }
    var subtitles by rememberSaveable { mutableStateOf<Uri?>(null) }
    var trLanguage by rememberSaveable { mutableStateOf(translation) }
    var trFile by rememberSaveable { mutableStateOf<Uri?>(null) }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            media = uri
            subtitles = null
            trFile = null
            tracks = emptyList()
        }
    }
    val pickSubtitles = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) subtitles = uri }
    val pickTranslation = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) trFile = uri }

    // A file can hold subtitles itself: look, so that the steps that are not needed can be skipped.
    LaunchedEffect(media) { media?.let { tracks = vm.inspectMedia(it) } }

    val studyTrack = if (subtitles == null) EmbeddedSubtitles.inLanguage(tracks, language) else null
    val trTrack = if (trFile == null) trLanguage?.let { EmbeddedSubtitles.inLanguage(tracks, it) } else null
    val ready = media != null && (subtitles != null || studyTrack != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_media)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Step(
                    title = stringResource(R.string.add_step_media),
                    chosen = media?.let(vm::nameOf),
                    enabled = true,
                    onChoose = { pickMedia.launch(arrayOf("video/*", "audio/*")) },
                )
                HorizontalDivider()
                Step(
                    title = stringResource(R.string.add_step_subtitles, language.displayName()),
                    chosen = subtitles?.let(vm::nameOf),
                    foundInside = studyTrack?.let { language.displayName() },
                    enabled = media != null,
                    onChoose = { pickSubtitles.launch(arrayOf("*/*")) },
                )
                HorizontalDivider()
                TranslationStep(
                    title = stringResource(R.string.add_step_translation),
                    language = trLanguage,
                    onLanguage = { trLanguage = it; trFile = null },
                    chosen = trFile?.let(vm::nameOf),
                    foundInside = trTrack?.let { trLanguage?.displayName() },
                    enabled = media != null,
                    onChoose = { pickTranslation.launch(arrayOf("*/*")) },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    val file = media ?: return@TextButton
                    val study = subtitles?.let { SubtitleSource.File(it) } ?: studyTrack?.let { SubtitleSource.Inside(it) } ?: return@TextButton
                    val tr = trLanguage?.let { l -> (trFile?.let { SubtitleSource.File(it) } ?: trTrack?.let { SubtitleSource.Inside(it) })?.let { l to it } }
                    vm.addLocalMedia(file, study, tr)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Adds translations to [item] afterwards: subtitles in another language, from a file or from inside its file. */
@Composable
internal fun AddTranslationDialog(vm: SettingsViewModel, item: MediaEntry, translation: Language?, onDismiss: () -> Unit) {
    var tracks by remember { mutableStateOf<List<EmbeddedTrack>>(emptyList()) }
    var language by rememberSaveable { mutableStateOf(item.translation ?: translation) }
    var file by rememberSaveable { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) file = uri }
    LaunchedEffect(item.id) { tracks = if (item.media.scheme == "content") vm.inspectMedia(item.media) else emptyList() }

    val track = if (file == null) language?.let { EmbeddedSubtitles.inLanguage(tracks, it) } else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_translation)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TranslationStep(
                    title = null,
                    language = language,
                    onLanguage = { language = it; file = null },
                    chosen = file?.let(vm::nameOf),
                    foundInside = track?.let { language?.displayName() },
                    enabled = true,
                    onChoose = { pick.launch(arrayOf("*/*")) },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = language != null && (file != null || track != null),
                onClick = {
                    val l = language ?: return@TextButton
                    val source = file?.let { SubtitleSource.File(it) } ?: track?.let { SubtitleSource.Inside(it) } ?: return@TextButton
                    vm.addTranslation(item, source, l)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** One step: what it is, what is chosen (or found inside the video/audio file), and a button to choose. */
@Composable
private fun Step(title: String, chosen: String?, enabled: Boolean, onChoose: () -> Unit, foundInside: String? = null) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    ChosenLine(chosen, foundInside)
    OutlinedButton(onClick = onChoose, enabled = enabled) {
        Text(stringResource(if (chosen != null) R.string.change else R.string.choose_file))
    }
}

/** The translation step: its language (Off = no translation), and then the same choice of file as the others. */
@Composable
private fun TranslationStep(
    title: String?,
    language: Language?,
    onLanguage: (Language?) -> Unit,
    chosen: String?,
    foundInside: String?,
    enabled: Boolean,
    onChoose: () -> Unit,
) {
    if (title != null) Text(title, style = MaterialTheme.typography.labelLarge)
    Text(stringResource(R.string.add_translation_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.translations_from), style = MaterialTheme.typography.bodyMedium)
        TranslationPicker(language, onLanguage)
    }
    if (language != null) {
        ChosenLine(chosen, foundInside)
        OutlinedButton(onClick = onChoose, enabled = enabled) {
            Text(stringResource(if (chosen != null) R.string.change else R.string.choose_file))
        }
    }
}

@Composable
private fun ChosenLine(chosen: String?, foundInside: String?) {
    val text = when {
        chosen != null -> "✓ $chosen"
        foundInside != null -> "✓ " + stringResource(R.string.found_inside, foundInside)
        else -> null
    }
    if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
}
