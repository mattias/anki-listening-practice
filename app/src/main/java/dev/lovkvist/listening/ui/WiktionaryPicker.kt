package dev.lovkvist.listening.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lovkvist.listening.R
import dev.lovkvist.listening.data.dict.InstalledDictionary
import dev.lovkvist.listening.data.dict.WiktionaryCatalog
import dev.lovkvist.listening.l10n.AppLanguage
import dev.lovkvist.listening.l10n.displayName
import dev.lovkvist.listening.text.Language

/**
 * A Wiktionary dictionary for [language]: pick the language of the definitions (the app's
 * language by default, else English) and install it. Used in Settings and the setup guide.
 */
@Composable
fun WiktionaryPicker(vm: SettingsViewModel, language: Language, installed: List<InstalledDictionary>) {
    LaunchedEffect(Unit) { vm.loadWiktionary() }
    val pairs by vm.wiktionaryPairs.collectAsStateWithLifecycle()
    val failed by vm.wiktionaryFailed.collectAsStateWithLifecycle()
    val progress by vm.dictionaryProgress.collectAsStateWithLifecycle()
    val appLocale = AppLanguage.locale()
    val options = remember(pairs, language, appLocale) {
        WiktionaryCatalog.options(pairs, language).sortedBy { WiktionaryCatalog.languageName(it.target, appLocale) }
    }
    when {
        pairs == null && !failed -> CircularProgressIndicator(Modifier.size(24.dp))
        options.isEmpty() -> Text(
            stringResource(if (failed) R.string.wiktionary_unavailable else R.string.onboarding_no_recommended, language.displayName()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> {
            var target by rememberSaveable(language) { mutableStateOf(WiktionaryCatalog.defaultTarget(options, appLocale.language)) }
            val option = options.firstOrNull { it.target == target } ?: options.first()
            val dict = WiktionaryCatalog.dictionary(option, language, appLocale)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.definitions_in), style = MaterialTheme.typography.bodyMedium)
                var open by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { open = true }) {
                        Text("${WiktionaryCatalog.languageName(option.target, appLocale)} ▾")
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        for (o in options) {
                            DropdownMenuItem(
                                text = {
                                    Text("${WiktionaryCatalog.languageName(o.target, appLocale)} · ${stringResource(R.string.size_mb, WiktionaryCatalog.sizeMb(o.size))}")
                                },
                                onClick = {
                                    open = false
                                    target = o.target
                                },
                            )
                        }
                    }
                }
            }
            val p = progress[dict.sourceId]
            val done = installed.any { it.sourceId == dict.sourceId }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(dict.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.size_mb, dict.sizeMb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (p != null) InstallStatus(p) { vm.dismissDictionaryError(dict.sourceId) }
                }
                when {
                    p != null && p.error == null -> CircularProgressIndicator(Modifier.size(24.dp))
                    done -> Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = stringResource(R.string.installed),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    else -> TextButton(onClick = { vm.installDictionaries(listOf(dict)) }) { Text(stringResource(R.string.install)) }
                }
            }
            // Other Wiktionary installs of this language still running, after switching the definition language.
            for ((key, other) in progress) {
                if (key == dict.sourceId || !key.startsWith("wty-${option.source}-")) continue
                Column {
                    Text(other.name, style = MaterialTheme.typography.bodyLarge)
                    InstallStatus(other) { vm.dismissDictionaryError(key) }
                }
            }
        }
    }
}
