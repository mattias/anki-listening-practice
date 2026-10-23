package dev.lovkvist.listening.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lovkvist.listening.data.anki.FieldMapping
import dev.lovkvist.listening.data.anki.Preset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class Settings(
    val preset: Preset,
    val mapping: FieldMapping,
    val speed: Float,
    val delayMs: Long,
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val preset = stringPreferencesKey("preset")
        val speed = floatPreferencesKey("speed")
        val delayMs = longPreferencesKey("delay_ms")

        // Per-preset overrides, so editing Lapis doesn't clobber Kiku.
        fun noteType(p: Preset) = stringPreferencesKey("${p.name}_note_type")
        fun sentence(p: Preset) = stringPreferencesKey("${p.name}_sentence")
        fun furigana(p: Preset) = stringPreferencesKey("${p.name}_furigana")
        fun translation(p: Preset) = stringPreferencesKey("${p.name}_translation")
        fun audio(p: Preset) = stringPreferencesKey("${p.name}_audio")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        val preset = prefs[Keys.preset]?.let { runCatching { Preset.valueOf(it) }.getOrNull() } ?: Preset.KIKU
        Settings(
            preset = preset,
            mapping = mappingFor(prefs, preset),
            speed = prefs[Keys.speed] ?: 1.0f,
            delayMs = prefs[Keys.delayMs] ?: 1000L,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun mappingFor(preset: Preset): FieldMapping = mappingFor(context.dataStore.data.first(), preset)

    private fun mappingFor(prefs: Preferences, p: Preset): FieldMapping {
        val d = p.defaults
        return FieldMapping(
            noteTypeMatch = prefs[Keys.noteType(p)] ?: d.noteTypeMatch,
            sentence = prefs[Keys.sentence(p)] ?: d.sentence,
            furigana = prefs[Keys.furigana(p)] ?: d.furigana,
            translation = prefs[Keys.translation(p)] ?: d.translation,
            audio = prefs[Keys.audio(p)] ?: d.audio,
        )
    }

    suspend fun setPreset(p: Preset) = context.dataStore.edit { it[Keys.preset] = p.name }

    suspend fun setMapping(p: Preset, m: FieldMapping) = context.dataStore.edit {
        it[Keys.noteType(p)] = m.noteTypeMatch
        it[Keys.sentence(p)] = m.sentence
        it[Keys.furigana(p)] = m.furigana
        it[Keys.translation(p)] = m.translation
        it[Keys.audio(p)] = m.audio
    }

    suspend fun resetMapping(p: Preset) = context.dataStore.edit {
        it.remove(Keys.noteType(p))
        it.remove(Keys.sentence(p))
        it.remove(Keys.furigana(p))
        it.remove(Keys.translation(p))
        it.remove(Keys.audio(p))
    }

    suspend fun setSpeed(speed: Float) = context.dataStore.edit { it[Keys.speed] = speed }

    suspend fun setDelayMs(delayMs: Long) = context.dataStore.edit { it[Keys.delayMs] = delayMs }
}
