package dev.lovkvist.listening.data.media

import dev.lovkvist.listening.text.Language

/** Subtitle track chosen for a video. */
internal data class Track(val lang: String, val automatic: Boolean)

/**
 * Picks the subtitle track for [language] from a video's manual and automatic subtitle codes.
 * Manual subtitles win, in the order of [Language.captionCodes] (exact codes before regional
 * variants like "pt-BR"). Of the automatic captions, "<code>-orig" is speech recognition on the
 * original audio and preferred; the others are machine translations.
 *
 * A machine translation is no use as the text of what is spoken (YouTube offers "Swedish" for
 * nearly every video), so with [allowTranslated] off only the speech recognition counts. Older
 * yt-dlp versions list no "-orig" track and name the recognized language plainly, so then a plain
 * code is still taken. Translations of a sentence into the user's language ([allowTranslated]
 * on) are what the translation line shows.
 */
internal fun pickTrack(
    manual: Collection<String>,
    automatic: Collection<String>,
    language: Language,
    allowTranslated: Boolean = true,
): Track? {
    fun rank(key: String): Int? {
        language.captionCodes.forEachIndexed { i, c -> if (key.equals(c, ignoreCase = true)) return 2 * i }
        language.captionCodes.forEachIndexed { i, c -> if (key.startsWith("$c-", ignoreCase = true)) return 2 * i + 1 }
        return null
    }
    manual.filterNot { it.endsWith("-orig") }
        .mapNotNull { key -> rank(key)?.let { key to it } }
        .minByOrNull { it.second }
        ?.let { return Track(it.first, automatic = false) }
    val plainIsSpeech = allowTranslated || automatic.none { it.endsWith("-orig", ignoreCase = true) }
    val lang = language.captionCodes.firstNotNullOfOrNull { c -> automatic.firstOrNull { it.equals("$c-orig", ignoreCase = true) } }
        ?: language.captionCodes.takeIf { plainIsSpeech }?.firstNotNullOfOrNull { c -> automatic.firstOrNull { it.equals(c, ignoreCase = true) } }
        ?: return null
    return Track(lang, automatic = true)
}
