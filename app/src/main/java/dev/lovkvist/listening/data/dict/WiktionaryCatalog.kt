package dev.lovkvist.listening.data.dict

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lovkvist.listening.text.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A Wiktionary dictionary on offer: words of [source] explained in [target], [size] like "8.0 MB". */
data class WiktionaryOption(val source: String, val target: String, val size: String)

/**
 * The Wiktionary dictionaries built by wty (github.com/yomidevs/wiktionary-to-yomitan), which
 * cover every language the app studies. A dictionary is a pair: the language of the words and the
 * language of the definitions (the Wiktionary edition they come from). The list of pairs comes
 * from wty's release metadata, cached for a day.
 */
@Singleton
class WiktionaryCatalog @Inject constructor(@ApplicationContext private val context: Context) {
    private val cache = File(context.filesDir, "wty-metadata.json")
    private val lock = Mutex()

    private val _pairs = MutableStateFlow<Map<String, Map<String, String>>?>(null)
    /** Sizes by source and target language, or null until loaded. */
    val pairs: StateFlow<Map<String, Map<String, String>>?> = _pairs.asStateFlow()

    private val _failed = MutableStateFlow(false)
    /** The list couldn't be fetched and there is no cached copy. */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    /** Loads the list: the cached copy if it's fresh, else from the network (the cache if that fails). */
    suspend fun load() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (_pairs.value != null && cache.lastModified() > System.currentTimeMillis() - DAY) return@withLock
            val fresh = cache.isFile && cache.lastModified() > System.currentTimeMillis() - DAY
            val json = if (fresh) cache.readText() else runCatching { fetch().also { cache.writeText(it) } }.getOrNull()
                ?: cache.takeIf { it.isFile }?.readText()
            val parsed = json?.let { runCatching { parse(it) }.getOrNull() }
            if (parsed != null) _pairs.value = parsed
            _failed.value = parsed == null
        }
    }

    private fun fetch(): String {
        val connection = URL(METADATA_URL).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "anki-listening-practice")
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    companion object {
        private const val DAY = 24 * 60 * 60 * 1000L
        private const val METADATA_URL = "https://yomidevs.github.io/wiktionary-to-yomitan/release_metadata_yomitan.json"
        private const val BASE_URL = "https://huggingface.co/datasets/daxida/wty-release/resolve/main/latest/dict"

        /** {"dicts": {"main": {"sources": {"sv": {"targets": {"en": "8.0 MB"}}}}}} → sv → en → 8.0 MB. */
        fun parse(json: String): Map<String, Map<String, String>> {
            val sources = JSONObject(json).getJSONObject("dicts").getJSONObject("main").getJSONObject("sources")
            return sources.keys().asSequence().associateWith { source ->
                val targets = sources.getJSONObject(source).optJSONObject("targets") ?: JSONObject()
                targets.keys().asSequence().associateWith { targets.getString(it) }
            }
        }

        /** Whether a recommended dictionary's id is a Wiktionary one. */
        fun isWiktionary(sourceId: String) = sourceId.startsWith("wty-")

        /** The dictionaries in [pairs] for [language], one per definition language. */
        fun options(pairs: Map<String, Map<String, String>>?, language: Language): List<WiktionaryOption> {
            val source = sourceCode(language)
            return pairs?.get(source).orEmpty().map { (target, size) -> WiktionaryOption(source, target, size) }
        }

        /**
         * The Wiktionary language code for words of [language]. English Wiktionary files all
         * Chinese (Mandarin, Cantonese, Hokkien) under Chinese and Croatian under Serbo-Croatian.
         */
        fun sourceCode(language: Language): String = when (language) {
            Language.CHINESE, Language.CANTONESE, Language.TAIWANESE -> "zh"
            Language.CROATIAN -> "sh"
            else -> language.code
        }

        /**
         * The definition language to suggest: the app's language if there's a dictionary in it
         * (and it isn't the studied language), else English, else the first one.
         */
        fun defaultTarget(options: List<WiktionaryOption>, appLanguage: String): String? {
            val targets = options.map { it.target }
            val app = when (appLanguage) { "in" -> "id"; "iw" -> "he"; else -> appLanguage }
            return targets.firstOrNull { it == app && it != options.first().source }
                ?: targets.firstOrNull { it == "en" }
                ?: targets.firstOrNull()
        }

        /** The option as a dictionary to install; it publishes an index URL, so it updates itself. */
        fun dictionary(option: WiktionaryOption, language: Language, appLocale: Locale): RecommendedDictionary {
            val (s, t) = option.source to option.target
            val pair = "${languageName(s, appLocale)} → ${languageName(t, appLocale)}"
            return RecommendedDictionary(
                sourceId = "wty-$s-$t",
                name = "Wiktionary ($pair)",
                description = pair,
                downloadUrl = "$BASE_URL/$s/$t/wty-$s-$t.zip",
                language = language,
                sizeMb = sizeMb(option.size),
            )
        }

        fun languageName(code: String, appLocale: Locale): String =
            Locale.forLanguageTag(code).getDisplayLanguage(appLocale).replaceFirstChar { it.titlecase(appLocale) }

        /** "813.5 KB" → 1, "8.0 MB" → 8, "1.2 GB" → 1229: whole MB, at least 1. */
        fun sizeMb(size: String): Int {
            val parts = size.trim().split(' ')
            val n = parts.firstOrNull()?.toDoubleOrNull() ?: return 0
            val mb = when (parts.getOrNull(1)?.uppercase()) {
                "KB" -> n / 1024
                "GB" -> n * 1024
                else -> n
            }
            return maxOf(1, Math.round(mb).toInt())
        }
    }
}
