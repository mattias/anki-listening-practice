package dev.lovkvist.listening.l10n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lovkvist.listening.text.Language
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The language of the app's own text. By default it follows the phone; it can be set in
 * Settings (and on Android 13+ also in the system's per-app language settings).
 */
object AppLanguage {
    /** UI languages with a translation, as BCP 47 tags, with their names in themselves. */
    val available: List<Pair<String, String>> = listOf(
        "en" to "English",
        "ar" to "العربية",
        "ca" to "Català",
        "da" to "Dansk",
        "de" to "Deutsch",
        "el" to "Ελληνικά",
        "es" to "Español",
        "fa" to "فارسی",
        "fil" to "Filipino",
        "fi" to "Suomi",
        "fr" to "Français",
        "he" to "עברית",
        "hr" to "Hrvatski",
        "hu" to "Magyar",
        "id" to "Bahasa Indonesia",
        "it" to "Italiano",
        "ja" to "日本語",
        "ko" to "한국어",
        "lt" to "Lietuvių",
        "nb" to "Norsk bokmål",
        "nl" to "Nederlands",
        "pl" to "Polski",
        "pt" to "Português",
        "ro" to "Română",
        "ru" to "Русский",
        "sl" to "Slovenščina",
        "sv" to "Svenska",
        "th" to "ไทย",
        "tr" to "Türkçe",
        "uk" to "Українська",
        "vi" to "Tiếng Việt",
        "zh-Hans" to "简体中文",
        "zh-Hant" to "繁體中文",
    )

    /** The chosen tag from [available], or null when following the phone. */
    fun current(): String? {
        val chosen = AppCompatDelegate.getApplicationLocales().get(0) ?: return null
        return available.firstOrNull { (tag, _) -> sameLanguage(Locale.forLanguageTag(tag), chosen) }?.first
    }

    /** Switches the app's language (null = the phone's); open activities are recreated. */
    fun set(tag: String?) {
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
        )
    }

    /** The locale text is shown in: the chosen one, or the phone's. */
    fun locale(): Locale = AppCompatDelegate.getApplicationLocales().get(0) ?: LocaleListCompat.getDefault()[0] ?: Locale.ENGLISH

    // Java still knows Indonesian, Hebrew and Filipino by old codes ("in", "iw", "tl"), and
    // Chinese variants by script or region.
    private fun sameLanguage(a: Locale, b: Locale): Boolean {
        fun norm(l: Locale) = when (val lang = Locale.forLanguageTag(l.toLanguageTag()).language) {
            "in" -> "id"
            "iw" -> "he"
            "tl" -> "fil"
            "zh" -> if (l.script == "Hant" || l.country in setOf("TW", "HK", "MO")) "zh-Hant" else "zh-Hans"
            else -> lang
        }
        return norm(a) == norm(b)
    }
}

/**
 * Strings in the app's language for code outside the UI (view models, the playback service,
 * importers). Before Android 13 the application context keeps the phone's language, so this
 * resolves against a context set to [AppLanguage.locale].
 */
@Singleton
class Strings @Inject constructor(@ApplicationContext private val context: Context) {
    private var cached: Pair<Locale, Context>? = null

    operator fun invoke(@StringRes id: Int, vararg args: Any): String = localized().getString(id, *args)

    private fun localized(): Context {
        val locale = AppLanguage.locale()
        cached?.let { (l, c) -> if (l == locale) return c }
        val config = Configuration(context.resources.configuration).apply { setLocales(LocaleList(locale)) }
        return context.createConfigurationContext(config).also { cached = locale to it }
    }
}

/**
 * The study language's name in the app's language, e.g. "Japanese", "japanska", "日语";
 * capitalized, since it's shown on its own (menus, parentheses).
 */
fun Language.displayName(ui: Locale = AppLanguage.locale()): String {
    // English names are the app's own, e.g. "Chinese (Mandarin)" and "Taiwanese".
    if (ui.language == "en") return englishName
    val name = Locale.forLanguageTag(if (this == Language.TAIWANESE) "nan-TW" else code).getDisplayLanguage(ui)
    if (name.isBlank() || name.equals(code, ignoreCase = true)) return englishName
    return name.replaceFirstChar { it.titlecase(ui) }
}

/** "日本語 (Japanska)": the name in the language itself, and in the app's language if different. */
fun Language.label(ui: Locale = AppLanguage.locale()): String {
    val local = displayName(ui)
    return if (local.equals(nativeName, ignoreCase = true)) nativeName else "$nativeName ($local)"
}
