package dev.lovkvist.listening.ui

import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lovkvist.listening.R
import dev.lovkvist.listening.data.anki.FieldMapping
import dev.lovkvist.listening.data.anki.ImportedDeck
import dev.lovkvist.listening.data.anki.Preset
import dev.lovkvist.listening.data.dict.InstallProgress
import dev.lovkvist.listening.data.dict.InstalledDictionary
import dev.lovkvist.listening.data.dict.RecommendedDictionary
import dev.lovkvist.listening.data.dict.WiktionaryCatalog
import dev.lovkvist.listening.data.local.WordStatus
import dev.lovkvist.listening.data.media.LikelyLanguage
import dev.lovkvist.listening.data.media.MediaEntry
import dev.lovkvist.listening.data.media.SubtitleStatus
import dev.lovkvist.listening.data.media.VideoResult
import dev.lovkvist.listening.data.settings.SettingsRepository
import dev.lovkvist.listening.data.update.UpdateState
import dev.lovkvist.listening.l10n.AppLanguage
import dev.lovkvist.listening.l10n.displayName
import dev.lovkvist.listening.l10n.label
import dev.lovkvist.listening.text.Language
import dev.lovkvist.listening.text.Reading
import dev.lovkvist.listening.ui.theme.DarkMode
import dev.lovkvist.listening.ui.theme.Palette
import java.text.DateFormat
import java.util.Date

/** Settings' main page with its groups, and the pages it opens. */
private enum class Page(val title: Int) {
    MAIN(R.string.settings),
    DECKS(R.string.anki_decks),
    PLAYBACK(R.string.playback),
    MEDIA(R.string.videos_audio),
    KNOWN(R.string.known_words),
    MY_WORDS(R.string.my_words),
    DICTIONARIES(R.string.dictionaries),
    PRESET(R.string.field_preset),
    PROGRESS(R.string.progress),
    UPDATES(R.string.app_updates),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, updateVm: UpdateViewModel, onBack: () -> Unit, onSetupGuide: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val knownCounts by vm.knownCounts.collectAsStateWithLifecycle()
    val blacklistCount by vm.blacklistCount.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val cooldownCount by vm.cooldownCount.collectAsStateWithLifecycle()
    val cardWordCount by vm.cardWordCount.collectAsStateWithLifecycle()
    val decks by vm.decks.collectAsStateWithLifecycle()
    val mediaItems by vm.mediaItems.collectAsStateWithLifecycle()
    val dictionaries by vm.installedDictionaries.collectAsStateWithLifecycle()
    val myWordList by vm.myWordList.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Reset?>(null) }
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    LaunchedEffect(Unit) { vm.refreshCounts() }
    // Back on a sub page goes to the main page; MainActivity's handler closes Settings.
    BackHandler(enabled = page != Page.MAIN) { page = Page.MAIN }

    val pickKnownWords = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importKnownWords)
    }
    val exportKnownWords = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let(vm::exportKnownWords)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(page.title)) },
                navigationIcon = {
                    IconButton(onClick = { if (page == Page.MAIN) onBack() else page = Page.MAIN }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
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
            when (page) {
                Page.MAIN -> {
                    Group(stringResource(R.string.appearance)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ThemePicker(s.theme, vm::setTheme)
                            if (s.theme == Palette.MATERIAL_YOU) {
                                Text(stringResource(R.string.dark_mode), style = MaterialTheme.typography.labelLarge)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for ((mode, label) in listOf(
                                        DarkMode.SYSTEM to R.string.dark_system,
                                        DarkMode.LIGHT to R.string.dark_light,
                                        DarkMode.DARK to R.string.dark_dark,
                                    )) {
                                        FilterChip(
                                            selected = s.darkMode == mode,
                                            onClick = { vm.setDarkMode(mode) },
                                            label = { Text(stringResource(label)) },
                                        )
                                    }
                                }
                            }
                            Text(
                                stringResource(R.string.theme_credit),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Group(stringResource(R.string.languages)) {
                        val current = AppLanguage.current()
                        val systemDefault = stringResource(R.string.system_default)
                        MenuRow(
                            R.drawable.ic_translate,
                            stringResource(R.string.app_language),
                            AppLanguage.available.firstOrNull { it.first == current }?.second ?: systemDefault,
                            (listOf(null to systemDefault) + AppLanguage.available).map { (tag, name) ->
                                name to { if (tag != current) AppLanguage.set(tag) }
                            },
                        )
                        RowDivider()
                        MenuRow(
                            R.drawable.ic_headphones,
                            stringResource(R.string.study_language),
                            s.language.label(),
                            emptyList(),
                        ) { close -> LanguageMenuItems(s.language, s.favoriteLanguages, vm::selectLanguage, vm::toggleFavoriteLanguage, close) }
                    }
                    Text(
                        stringResource(R.string.kept_per_language),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )

                    Group(stringResource(R.string.library)) {
                        NavRow(R.drawable.ic_style, stringResource(R.string.anki_decks), decks.count { it.language == s.language }.toString()) {
                            page = Page.DECKS
                        }
                        RowDivider()
                        NavRow(R.drawable.ic_tune, stringResource(R.string.field_preset), s.preset.label) { page = Page.PRESET }
                        RowDivider()
                        NavRow(R.drawable.ic_video_library, stringResource(R.string.videos_audio), mediaItems.count { it.language == s.language }.toString()) {
                            page = Page.MEDIA
                        }
                    }

                    Group(stringResource(R.string.words)) {
                        val known = (knownCounts[s.language] ?: 0).toString()
                        NavRow(
                            R.drawable.ic_abc,
                            stringResource(R.string.known_words),
                            if (s.maxUnknownWords > 0) "$known · n+${s.maxUnknownWords}" else known,
                        ) {
                            page = Page.KNOWN
                        }
                        RowDivider()
                        NavRow(R.drawable.ic_check, stringResource(R.string.my_words), myWordList.size.toString()) { page = Page.MY_WORDS }
                        RowDivider()
                        NavRow(R.drawable.ic_menu_book, stringResource(R.string.dictionaries), dictionaries.count { it.language == s.language }.toString()) {
                            page = Page.DICTIONARIES
                        }
                    }

                    Group(null) {
                        NavRow(
                            R.drawable.ic_speed,
                            stringResource(R.string.playback),
                            "%.2f×".format(s.speed) + if (s.autoClearAfter > 0) " · ${s.autoClearAfter}×" else "",
                        ) { page = Page.PLAYBACK }
                        RowDivider()
                        NavRow(R.drawable.ic_insights, stringResource(R.string.progress), "") {
                            page = Page.PROGRESS
                        }
                        RowDivider()
                        NavRow(R.drawable.ic_system_update, stringResource(R.string.app_updates), updateVm.currentVersion) {
                            page = Page.UPDATES
                        }
                        RowDivider()
                        NavRow(R.drawable.ic_headphones, stringResource(R.string.setup_guide), "", onSetupGuide)
                    }
                }

                Page.DECKS -> Decks(vm, s.language)

                Page.PLAYBACK -> {
                    // The same controls as the sheet under the sentence; changes apply to the session at once.
                    val practice = vm.practice
                    val speed by practice.speed.collectAsStateWithLifecycle()
                    val delayMs by practice.delayMs.collectAsStateWithLifecycle()
                    val autoClearAfter by practice.autoClearAfter.collectAsStateWithLifecycle()
                    val autoAdvanceSkip by practice.autoAdvanceSkip.collectAsStateWithLifecycle()
                    val headsetClear by practice.headsetDoubleTapClear.collectAsStateWithLifecycle()
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
                    HeadsetSettings(headsetClear, practice::setHeadsetDoubleTapClear)
                }

                Page.MEDIA -> MediaItems(vm, s.language, s.translation)

                Page.KNOWN -> {
                    Text(
                        when (val n = knownCounts[s.language]) {
                            null -> stringResource(R.string.loading)
                            0 -> stringResource(
                                when {
                                    s.language == Language.JAPANESE -> R.string.known_none_jiten
                                    s.language.inAnkiMiner -> R.string.known_none_miner
                                    else -> R.string.known_none
                                },
                            )
                            else -> stringResource(R.string.known_count, n)
                        },
                    )
                    Text(
                        if (s.mapping.word.isBlank()) stringResource(R.string.no_word_field)
                        else stringResource(R.string.card_words, cardWordCount, s.mapping.word),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pickKnownWords.launch(arrayOf("text/*")) }) { Text(stringResource(R.string.import_known_words)) }
                        OutlinedButton(onClick = { exportKnownWords.launch("known_words_${s.language.code}.txt") }) {
                            Text(stringResource(R.string.export_known_words))
                        }
                    }
                    Text(
                        stringResource(R.string.export_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                    Text(stringResource(R.string.unknown_words), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (n in 0..SettingsRepository.MAX_UNKNOWN_WORDS) {
                            FilterChip(selected = s.maxUnknownWords == n, onClick = { vm.setMaxUnknownWords(n) }, label = { Text("n+$n") })
                        }
                    }
                    Text(
                        stringResource(R.string.unknown_words_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Page.PRESET -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (p in Preset.availableFor(s.language)) {
                            FilterChip(selected = p == s.preset, onClick = { vm.selectPreset(s.language, p) }, label = { Text(p.label) })
                        }
                    }
                    Text(
                        stringResource(R.string.presets_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (s.language != Language.JAPANESE) {
                        Text(
                            stringResource(R.string.japanese_presets_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MappingEditor(
                        preset = s.preset,
                        language = s.language,
                        mapping = s.mapping,
                        onSave = { vm.saveMapping(s.preset, s.language, it) },
                        onReset = { vm.resetMapping(s.preset, s.language) },
                    )
                }

                Page.PROGRESS -> {
                    Text(stringResource(R.string.cooling_down, cooldownCount))
                    OutlinedButton(onClick = { confirm = Reset.COOLDOWNS }, enabled = cooldownCount > 0) {
                        Text(stringResource(R.string.reset_cooldowns))
                    }
                    HorizontalDivider()
                    Text(stringResource(R.string.blacklisted_count, blacklistCount))
                    Blacklist(vm)
                    OutlinedButton(onClick = { confirm = Reset.BLACKLIST }, enabled = blacklistCount > 0) {
                        Text(stringResource(R.string.clear_blacklist))
                    }
                }

                Page.UPDATES -> Updates(updateVm)

                Page.MY_WORDS -> MyWordsPage(vm, myWordList)

                Page.DICTIONARIES -> DictionariesPage(vm, s.language, dictionaries)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    confirm?.let { reset ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(reset.title)) },
            text = { Text(stringResource(reset.body)) },
            confirmButton = {
                TextButton(onClick = {
                    when (reset) {
                        Reset.COOLDOWNS -> vm.resetCooldowns()
                        Reset.BLACKLIST -> vm.clearBlacklist()
                    }
                    confirm = null
                }) { Text(stringResource(R.string.reset)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    message?.let {
        AlertDialog(
            onDismissRequest = vm::dismissMessage,
            confirmButton = { TextButton(onClick = vm::dismissMessage) { Text(stringResource(R.string.ok)) } },
            text = { Text(it) },
        )
    }
}

private enum class Reset(val title: Int, val body: Int) {
    COOLDOWNS(R.string.reset_cooldowns_title, R.string.reset_cooldowns_body),
    BLACKLIST(R.string.clear_blacklist_title, R.string.clear_blacklist_body),
}

/** A titled card holding rows, like Android's own settings. */
@Composable
private fun Group(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(content = content)
        }
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun RowContent(@DrawableRes icon: Int, title: String, value: String, @DrawableRes trailing: Int) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).widthIn(max = 200.dp),
        )
        Icon(painterResource(trailing), contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A row that opens a page. */
@Composable
private fun NavRow(@DrawableRes icon: Int, title: String, value: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        RowContent(icon, title, value, R.drawable.ic_chevron_right)
    }
}

/** A row that opens a menu of choices. */
@Composable
private fun MenuRow(
    @DrawableRes icon: Int,
    title: String,
    value: String,
    items: List<Pair<String, () -> Unit>>,
    /** Menu content in place of [items]; gets a function that closes the menu. */
    menu: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().clickable { open = true }) {
        RowContent(icon, title, value, R.drawable.ic_expand_more)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 480.dp)) {
            menu?.invoke { open = false }
            for ((name, action) in items) {
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }
        }
    }
}

/** Tiles in the style of daisyUI's theme picker: each in its own colors, with four swatches. */
@Composable
private fun ThemePicker(selected: String, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val materialYou = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val tiles = listOf(
        ThemeTile(
            Palette.MATERIAL_YOU, "Material You", materialYou.surfaceContainer, materialYou.onSurface,
            listOf(materialYou.primary, materialYou.secondary, materialYou.tertiary, materialYou.onSurface),
        ),
    ) + Palette.all.map { p -> ThemeTile(p.id, p.name, Color(p.base100), Color(p.content), p.swatches) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in tiles.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (tile in row) {
                    val isSelected = tile.id == selected
                    Surface(
                        onClick = { onSelect(tile.id) },
                        shape = RoundedCornerShape(12.dp),
                        color = tile.background,
                        contentColor = tile.content,
                        border = BorderStroke(
                            if (isSelected) 2.5.dp else 1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text(tile.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                for (c in tile.swatches) {
                                    Box(
                                        Modifier
                                            .size(12.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(c),
                                    )
                                }
                            }
                        }
                    }
                }
                // Keep the last row's tiles the same width as the others.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private data class ThemeTile(val id: String, val name: String, val background: Color, val content: Color, val swatches: List<Color>)

@Composable
private fun Updates(vm: UpdateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val checkOnStart by vm.checkOnStart.collectAsStateWithLifecycle()
    Text(stringResource(R.string.version_info, vm.currentVersion))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.check_on_start), Modifier.weight(1f))
        Switch(checked = checkOnStart, onCheckedChange = vm::setCheckOnStart)
    }
    if (state is UpdateState.Checking) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text(stringResource(R.string.checking))
        }
    } else {
        OutlinedButton(onClick = { vm.check() }) { Text(stringResource(R.string.check_for_updates)) }
    }
}

/** The language of YouTube subtitles used as translations, or Off. */
@Composable
private fun TranslationPicker(current: Language?, onSelect: (Language?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }) { Text("${current?.displayName() ?: stringResource(R.string.off)} ▾") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.off)) },
            onClick = {
                open = false
                onSelect(null)
            },
        )
        for (l in Language.entries.sortedBy { it.displayName() }) {
            DropdownMenuItem(
                text = { Text(l.displayName()) },
                onClick = {
                    open = false
                    onSelect(l)
                },
            )
        }
    }
}

/**
 * Search YouTube for videos with subtitles; a tap downloads one like a pasted link would. YouTube
 * can't search by language, so results whose titles are clearly in another language are folded
 * away (and not checked), and the rest are checked for subtitles in the study language.
 */
@Composable
private fun YouTubeSearchSection(vm: SettingsViewModel, language: Language) {
    val search by vm.ytSearch.collectAsStateWithLifecycle()
    val subtitles by vm.ytSubtitles.collectAsStateWithLifecycle()
    val likely by vm.ytLikely.collectAsStateWithLifecycle()
    val checking by vm.ytChecking.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showOthers by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.search_youtube), style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(stringResource(R.string.search_youtube_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { vm.searchYouTube(query) }),
        trailingIcon = {
            IconButton(onClick = { vm.searchYouTube(query) }, enabled = query.isNotBlank() && !search.searching) {
                Icon(painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.search))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(R.string.search_youtube_help, language.displayName()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when {
        search.searching -> CircularProgressIndicator(Modifier.size(24.dp))
        search.error != null -> Text(
            stringResource(R.string.search_failed, search.error.orEmpty()),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        search.searched && search.results.isEmpty() -> Text(stringResource(R.string.no_videos_found))
    }
    val (others, mine) = search.results.partition { likely[it.id] == LikelyLanguage.OTHER }
    for (r in mine) VideoRow(r, subtitles[r.id], r.id in checking) { vm.importYouTube(r.url) }
    if (search.limited) {
        Text(
            stringResource(R.string.search_limited),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (others.isNotEmpty()) {
        if (mine.isEmpty()) {
            Text(
                stringResource(R.string.search_language_tip, language.displayName()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { showOthers = !showOthers }) {
            Text(stringResource(if (showOthers) R.string.hide_other_languages else R.string.show_other_languages, others.size))
        }
        if (showOthers) {
            // Not checked, so nothing is known about their subtitles: a tap tries the download.
            for (r in others) VideoRow(r, null, false) { vm.importYouTube(r.url) }
        }
    }
}

private val DIMMED = setOf(SubtitleStatus.NONE, SubtitleStatus.OTHER_LANGUAGE, SubtitleStatus.NO_SPEECH)

/** One search result: title, channel and length, and what is known about its subtitles. */
@Composable
private fun VideoRow(video: VideoResult, status: SubtitleStatus?, checking: Boolean, onClick: () -> Unit) {
    // Nothing to practice: no subtitles, or spoken in another language. "No speech found" is only a hint
    // (YouTube makes no speech recognition for some languages), so those stay open to a tap.
    val usable = status != SubtitleStatus.NONE && status != SubtitleStatus.OTHER_LANGUAGE
    val dimmed = status in DIMMED
    Surface(
        onClick = onClick,
        enabled = usable,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().alpha(if (dimmed) 0.5f else 1f),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(video.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(video.channel.ifBlank { null }, video.durationSeconds?.let(::formatDuration)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                status == SubtitleStatus.MANUAL -> Text(stringResource(R.string.subs_manual), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                status == SubtitleStatus.AUTO -> Text(stringResource(R.string.subs_auto), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                status == SubtitleStatus.NONE -> Text(stringResource(R.string.subs_none), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                status == SubtitleStatus.OTHER_LANGUAGE -> Text(stringResource(R.string.subs_other_language), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                status == SubtitleStatus.NO_SPEECH -> Text(stringResource(R.string.subs_no_speech), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                status == null && checking -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/** 1460 -> "24:20", 3725 -> "1:02:05". */
private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Blacklisted sentences, each with a button to bring it back into practice. */
@Composable
private fun Blacklist(vm: SettingsViewModel) {
    val items by vm.blacklisted.collectAsStateWithLifecycle()
    for (item in items) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    // Sentences blacklisted before 0.4.0 weren't saved with their text.
                    item.text.ifBlank { stringResource(R.string.blacklisted_before_040) },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.source.isNotBlank()) {
                    Text(
                        item.source,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = { vm.restore(item.noteId) }) { Text(stringResource(R.string.restore)) }
        }
    }
}

@Composable
private fun Decks(vm: SettingsViewModel, language: Language) {
    val allDecks by vm.decks.collectAsStateWithLifecycle()
    val decks = allDecks.filter { it.language == language }
    val progress by vm.importProgress.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf<ImportedDeck?>(null) }

    // .apkg/.colpkg have no registered MIME type on most devices, so accept anything;
    // the unpacker rejects files that aren't Anki packages.
    val pickDeck = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importDeck)
    }

    Text(
        stringResource(R.string.decks_help),
        style = MaterialTheme.typography.bodyMedium,
    )
    for (deck in decks) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(deck.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOf(
                        stringResource(R.string.deck_notes, deck.noteCount),
                        stringResource(R.string.deck_audio, deck.mediaCount),
                        stringResource(R.string.deck_saved, formatBytes(deck.freedBytes)),
                        DateFormat.getDateInstance().format(Date(deck.importedAt)),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { confirmRemove = deck }) { Text(stringResource(R.string.remove)) }
        }
    }
    val importing = progress
    if (importing != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text(stringResource(R.string.importing_files, importing))
        }
    } else {
        Button(onClick = { pickDeck.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.import_deck)) }
    }

    confirmRemove?.let { deck ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.remove_title, deck.name)) },
            text = { Text(stringResource(R.string.remove_deck_body)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeDeck(deck.id)
                    confirmRemove = null
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun MediaItems(vm: SettingsViewModel, language: Language, translation: Language?) {
    val allItems by vm.mediaItems.collectAsStateWithLifecycle()
    val items = allItems.filter { it.language == language }
    val busy by vm.mediaBusy.collectAsStateWithLifecycle()
    var pickedMedia by remember { mutableStateOf<Uri?>(null) }
    var confirmRemove by remember { mutableStateOf<MediaEntry?>(null) }
    // Leaving the page ends the search: no checking goes on in the background, and the results are forgotten.
    DisposableEffect(Unit) { onDispose { vm.clearYouTubeSearch() } }

    // Two steps: the video/audio first, then its subtitle file.
    val pickSubtitles = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val media = pickedMedia
        pickedMedia = null
        if (uri != null && media != null) vm.importMedia(media, uri)
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedMedia = uri
            pickSubtitles.launch(arrayOf("*/*"))
        }
    }

    Text(
        stringResource(R.string.media_help),
        style = MaterialTheme.typography.bodyMedium,
    )
    for (item in items) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        stringResource(R.string.subtitle_lines, item.chunkCount),
                        item.translation?.let { stringResource(R.string.translations_in, it.displayName()) },
                        DateFormat.getDateInstance().format(Date(item.importedAt)),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { confirmRemove = item }) { Text(stringResource(R.string.remove)) }
        }
    }
    if (busy != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text(busy.orEmpty())
        }
    } else {
        Button(onClick = { pickMedia.launch(arrayOf("video/*", "audio/*")) }) { Text(stringResource(R.string.add_media)) }
        HorizontalDivider()
        YouTubeSearchSection(vm, language)
        HorizontalDivider()
        var url by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.youtube_link)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                vm.importYouTube(url)
                url = ""
            },
            enabled = url.isNotBlank(),
        ) { Text(stringResource(R.string.download_youtube)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.translations_from), style = MaterialTheme.typography.bodyMedium)
            TranslationPicker(translation, vm::setTranslation)
        }
        Text(
            stringResource(if (translation == null) R.string.translation_off_help else R.string.translation_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    confirmRemove?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.remove_title, item.name)) },
            text = {
                Text(
                    stringResource(if (item.sourceUrl != null) R.string.remove_download_body else R.string.remove_local_body),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeMedia(item.id)
                    confirmRemove = null
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun MappingEditor(
    preset: Preset,
    language: Language,
    mapping: FieldMapping,
    onSave: (FieldMapping) -> Unit,
    onReset: () -> Unit,
) {
    var sentence by remember(preset, language, mapping) { mutableStateOf(mapping.sentence) }
    var furigana by remember(preset, language, mapping) { mutableStateOf(mapping.furigana) }
    var translation by remember(preset, language, mapping) { mutableStateOf(mapping.translation) }
    var audio by remember(preset, language, mapping) { mutableStateOf(mapping.audio) }
    var word by remember(preset, language, mapping) { mutableStateOf(mapping.word) }

    Field(stringResource(R.string.field_sentence), sentence) { sentence = it }
    // Only languages with readings above the text have a reading field.
    when (val reading = language.reading) {
        null -> {}
        Reading.FURIGANA -> Field(stringResource(R.string.field_furigana), furigana) { furigana = it }
        else -> Field(stringResource(R.string.field_reading, reading.label), furigana) { furigana = it }
    }
    Field(stringResource(R.string.field_translation), translation) { translation = it }
    Field(stringResource(R.string.field_audio), audio) { audio = it }
    Field(stringResource(R.string.field_word), word) { word = it }

    Text(
        stringResource(R.string.audio_field_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val edited = FieldMapping(sentence, furigana, translation, audio, word)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onSave(edited) }, enabled = edited != mapping) { Text(stringResource(R.string.save_mapping)) }
        OutlinedButton(onClick = onReset, enabled = mapping != preset.defaults(language)) { Text(stringResource(R.string.reset_defaults)) }
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


/** Words marked in sentences: search them, switch between known and ignored, or remove them. */
@Composable
private fun MyWordsPage(vm: SettingsViewModel, words: List<WordStatus>) {
    var query by rememberSaveable { mutableStateOf("") }
    Text(stringResource(R.string.my_words_help), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(stringResource(R.string.search)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val shown = words.filter { query.isBlank() || it.word.contains(query.trim(), ignoreCase = true) }
    if (words.isEmpty()) {
        Text(stringResource(R.string.my_words_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    for (w in shown.take(300)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(w.word, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            FilterChip(
                selected = !w.ignored,
                onClick = { if (w.ignored) vm.setMyWord(w.word, false) },
                label = { Text(stringResource(R.string.mark_known)) },
            )
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = w.ignored,
                onClick = { if (!w.ignored) vm.setMyWord(w.word, true) },
                label = { Text(stringResource(R.string.mark_ignored)) },
            )
            IconButton(onClick = { vm.setMyWord(w.word, null) }) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.remove))
            }
        }
    }
}

/** The recommended set for the language, installed dictionaries, and their updates. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DictionariesPage(vm: SettingsViewModel, language: Language, installed: List<InstalledDictionary>) {
    val progress by vm.dictionaryProgress.collectAsStateWithLifecycle()
    val checking by vm.checkingUpdates.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "dictionary.zip"
            vm.importDictionary(uri, name)
        }
    }
    val mine = installed.filter { it.language == language }
    Text(stringResource(R.string.dictionaries_help), style = MaterialTheme.typography.bodyMedium)

    val recommended = RecommendedDictionary.forLanguage(language)
    if (recommended.isNotEmpty()) {
        Text(stringResource(R.string.recommended), style = MaterialTheme.typography.titleMedium)
        val missing = recommended.filter { r -> mine.none { it.sourceId == r.sourceId } && r.sourceId !in progress }
        for (r in recommended) {
            val p = progress[r.sourceId]
            val done = mine.any { it.sourceId == r.sourceId }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${r.description} · ${stringResource(R.string.size_mb, r.sizeMb)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (p != null) InstallStatus(p) { vm.dismissDictionaryError(r.sourceId) }
                }
                when {
                    p != null && p.error == null -> CircularProgressIndicator(Modifier.size(24.dp))
                    done -> Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = stringResource(R.string.installed),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    else -> TextButton(onClick = { vm.installDictionaries(listOf(r)) }) { Text(stringResource(R.string.install)) }
                }
            }
        }
        if (missing.size > 1) {
            Button(onClick = { vm.installDictionaries(missing) }) { Text(stringResource(R.string.install_all)) }
        }
        HorizontalDivider()
    }

    Text("Wiktionary", style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.wiktionary_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    WiktionaryPicker(vm, language, mine)
    HorizontalDivider()

    Text(stringResource(R.string.your_dictionaries), style = MaterialTheme.typography.titleMedium)
    if (mine.size > 1) {
        Text(stringResource(R.string.dictionary_order_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    for ((key, p) in progress.filterKeys { k -> recommended.none { it.sourceId == k } && !WiktionaryCatalog.isWiktionary(k) }) {
        Column {
            Text(p.name, style = MaterialTheme.typography.bodyLarge)
            InstallStatus(p) { vm.dismissDictionaryError(key) }
        }
    }
    for ((i, d) in mine.withIndex()) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (mine.size > 1) {
                        Column {
                            IconButton(onClick = { vm.moveDictionary(d.id, -1) }, enabled = i > 0, modifier = Modifier.size(36.dp)) {
                                Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = stringResource(R.string.move_up))
                            }
                            IconButton(onClick = { vm.moveDictionary(d.id, 1) }, enabled = i < mine.lastIndex, modifier = Modifier.size(36.dp)) {
                                Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = stringResource(R.string.move_down))
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(d.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.dictionary_entries, d.entries),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = d.enabled, onCheckedChange = { vm.setDictionaryEnabled(d.id, it) })
                }
                if (d.updatable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.auto_update), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = d.autoUpdate, onCheckedChange = { vm.setDictionaryAutoUpdate(d.id, it) })
                    }
                }
                TextButton(onClick = { vm.removeDictionary(d.id) }) { Text(stringResource(R.string.remove)) }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pick.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) {
            Text(stringResource(R.string.import_dictionary))
        }
        if (mine.any { it.updatable }) {
            OutlinedButton(onClick = { vm.checkDictionaryUpdates() }, enabled = !checking) {
                Text(stringResource(if (checking) R.string.checking else R.string.check_dictionary_updates))
            }
        }
    }
}

@Composable
internal fun InstallStatus(p: InstallProgress, onDismiss: () -> Unit) {
    val text = when {
        p.error != null -> stringResource(R.string.dictionary_failed, p.error)
        p.entries > 0 -> stringResource(R.string.importing_entries, p.entries)
        else -> stringResource(R.string.downloading_mb, formatBytes(p.bytes))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (p.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (p.error != null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
    }
}
