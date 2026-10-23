package dev.lovkvist.listening.data.media

import dev.lovkvist.listening.text.Language

/** Subtitle track chosen for a video. */
internal data class Track(val lang: String, val automatic: Boolean)

/**
 * Picks the subtitle track for [language] from a video's manual and automatic subtitle codes.
 * Manual subtitles win, in the order of [Language.captionCodes] (exact codes before regional
 * variants like "pt-BR"). Of the automatic captions, "<code>-orig" is speech recognition on the
 * original audio and preferred; the others are machine translations.
 */
internal fun pickTrack(manual: Collection<String>, automatic: Collection<String>, language: Language): Track? {
    fun rank(key: String): Int? {
        language.captionCodes.forEachIndexed { i, c -> if (key.equals(c, ignoreCase = true)) return 2 * i }
        language.captionCodes.forEachIndexed { i, c -> if (key.startsWith("$c-", ignoreCase = true)) return 2 * i + 1 }
        return null
    }
    manual.filterNot { it.endsWith("-orig") }
        .mapNotNull { key -> rank(key)?.let { key to it } }
        .minByOrNull { it.second }
        ?.let { return Track(it.first, automatic = false) }
    val lang = language.captionCodes.firstNotNullOfOrNull { c -> automatic.firstOrNull { it.equals("$c-orig", ignoreCase = true) } }
        ?: language.captionCodes.firstNotNullOfOrNull { c -> automatic.firstOrNull { it.equals(c, ignoreCase = true) } }
        ?: return null
    return Track(lang, automatic = true)
}
