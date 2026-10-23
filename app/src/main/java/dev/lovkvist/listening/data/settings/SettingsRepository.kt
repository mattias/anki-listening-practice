package dev.lovkvist.listening.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lovkvist.listening.data.PracticeSource
import dev.lovkvist.listening.data.anki.FieldMapping
import dev.lovkvist.listening.data.anki.Preset
import dev.lovkvist.listening.text.Language
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class Settings(
    val language: Language,
    val preset: Preset,
    val mapping: FieldMapping,
    val speed: Float,
    val delayMs: Long,
    /** After this many plays a sentence is marked Crystal Clear and the next one starts; 0 = off. */
    val autoClearAfter: Int,
    val source: PracticeSource,
    /** Look for a new release on GitHub when the app starts. */
    val checkUpdates: Boolean,
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val language = stringPreferencesKey("language")
        val speed = floatPreferencesKey("speed")
        val delayMs = longPreferencesKey("delay_ms")
        val autoClearAfter = intPreferencesKey("auto_clear_after")
        val checkUpdates = booleanPreferencesKey("check_updates")

        // Japanese keeps the key from before other languages existed.
        fun preset(l: Language) = stringPreferencesKey(if (l == Language.JAPANESE) "preset" else "preset_${l.code}")

        // Per-preset overrides, so editing Lapis doesn't clobber Kiku. Presets for every
        // language are also per language, since their reading field differs.
        private fun prefix(p: Preset, l: Language) = if (p.everyLanguage) "${p.name}_${l.code}" else p.name
        fun noteType(p: Preset, l: Language) = stringPreferencesKey("${prefix(p, l)}_note_type")
        fun sentence(p: Preset, l: Language) = stringPreferencesKey("${prefix(p, l)}_sentence")
        fun furigana(p: Preset, l: Language) = stringPreferencesKey("${prefix(p, l)}_furigana")
        fun translation(p: Preset, l: Language) = stringPreferencesKey("${prefix(p, l)}_translation")
        fun audio(p: Preset, l: Language) = stringPreferencesKey("${prefix(p, l)}_audio")
        fun word(p: Preset, l: Language) = stringPreferencesKey("${prefix(p, l)}_word")
        fun languageField(p: Preset, l: Language) = stringPreferencesKey("${prefix(p, l)}_language")

        // Videos are per language, so the chosen source is too. Japanese keeps the old key.
        fun source(l: Language) = stringPreferencesKey(if (l == Language.JAPANESE) "source" else "source_${l.code}")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        val language = Language.fromCode(prefs[Keys.language])
        val preset = presetFor(prefs, language)
        Settings(
            language = language,
            preset = preset,
            mapping = mappingFor(prefs, preset, language),
            speed = prefs[Keys.speed] ?: 1.0f,
            delayMs = prefs[Keys.delayMs] ?: 1000L,
            autoClearAfter = prefs[Keys.autoClearAfter] ?: 0,
            source = PracticeSource.fromKey(prefs[Keys.source(language)]),
            checkUpdates = prefs[Keys.checkUpdates] ?: true,
        )
    }

    suspend fun setAutoClearAfter(plays: Int) = context.dataStore.edit { it[Keys.autoClearAfter] = plays }

    /** Sets the source for the current language. */
    suspend fun setSource(source: PracticeSource) = context.dataStore.edit {
        it[Keys.source(Language.fromCode(it[Keys.language]))] = source.key()
    }

    suspend fun current(): Settings = settings.first()

    /** Every preset's mapping in every language it applies to, e.g. to find all audio fields. */
    suspend fun allMappings(): List<FieldMapping> {
        val prefs = context.dataStore.data.first()
        return Language.entries.flatMap { l -> Preset.availableFor(l).map { mappingFor(prefs, it, l) } }
    }

    /** The language's chosen preset; the Japanese note types fall back to Anki Miner Note elsewhere. */
    private fun presetFor(prefs: Preferences, l: Language): Preset {
        val stored = prefs[Keys.preset(l)]?.let { runCatching { Preset.valueOf(it) }.getOrNull() }
        return stored?.takeIf { it.availableFor(l) }
            ?: if (l == Language.JAPANESE) Preset.KIKU else Preset.ANKI_MINER_NOTE
    }

    private fun mappingFor(prefs: Preferences, p: Preset, l: Language): FieldMapping {
        val d = p.defaults(l)
        return FieldMapping(
            noteTypeMatch = prefs[Keys.noteType(p, l)] ?: d.noteTypeMatch,
            sentence = prefs[Keys.sentence(p, l)] ?: d.sentence,
            // Only languages with readings have a reading field.
            furigana = if (l.reading == null) "" else prefs[Keys.furigana(p, l)] ?: d.furigana,
            translation = prefs[Keys.translation(p, l)] ?: d.translation,
            audio = prefs[Keys.audio(p, l)] ?: d.audio,
            word = prefs[Keys.word(p, l)] ?: d.word,
            language = prefs[Keys.languageField(p, l)] ?: d.language,
        )
    }

    /** Switches language; each language remembers its own source, so switching back resumes it. */
    suspend fun setLanguage(l: Language) = context.dataStore.edit { it[Keys.language] = l.code }

    suspend fun setPreset(l: Language, p: Preset) = context.dataStore.edit { it[Keys.preset(l)] = p.name }

    suspend fun setMapping(p: Preset, l: Language, m: FieldMapping) = context.dataStore.edit {
        it[Keys.noteType(p, l)] = m.noteTypeMatch
        it[Keys.sentence(p, l)] = m.sentence
        it[Keys.furigana(p, l)] = m.furigana
        it[Keys.translation(p, l)] = m.translation
        it[Keys.audio(p, l)] = m.audio
        it[Keys.word(p, l)] = m.word
        it[Keys.languageField(p, l)] = m.language
    }

    suspend fun resetMapping(p: Preset, l: Language) = context.dataStore.edit {
        it.remove(Keys.noteType(p, l))
        it.remove(Keys.sentence(p, l))
        it.remove(Keys.furigana(p, l))
        it.remove(Keys.translation(p, l))
        it.remove(Keys.audio(p, l))
        it.remove(Keys.word(p, l))
        it.remove(Keys.languageField(p, l))
    }

    suspend fun setCheckUpdates(enabled: Boolean) = context.dataStore.edit { it[Keys.checkUpdates] = enabled }

    suspend fun setSpeed(speed: Float) = context.dataStore.edit { it[Keys.speed] = speed }

    suspend fun setDelayMs(delayMs: Long) = context.dataStore.edit { it[Keys.delayMs] = delayMs }
}
