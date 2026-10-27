package dev.lovkvist.listening.data.media

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import dev.lovkvist.listening.text.Language
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** What a video's title says about the language it is in. */
enum class LikelyLanguage {
    /** The title is in the study language. */
    SAME,
    /** The title is clearly in another language. */
    OTHER,
    /** Too short, or the guess isn't sure. */
    UNKNOWN,
}

/**
 * Guesses the language of a video from its title, offline (ML Kit's language identification),
 * so search results that are clearly in another language needn't be asked about at YouTube. It
 * is only a hint: learner channels often write English titles over Japanese or Swedish speech,
 * so such videos stay available, just not checked automatically.
 */
@Singleton
class TitleLanguage @Inject constructor() {
    private val client by lazy {
        LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(CONFIDENCE).build())
    }

    suspend fun likely(title: String, language: Language): LikelyLanguage =
        verdict(language, detect(title))

    private suspend fun detect(text: String): String? = suspendCancellableCoroutine { cont ->
        client.identifyLanguage(text)
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }

    companion object {
        private const val CONFIDENCE = 0.6f

        /** [tag]: ML Kit's BCP-47 guess ("und" or null when it isn't sure). */
        fun verdict(language: Language, tag: String?): LikelyLanguage {
            if (tag == null || tag.equals("und", ignoreCase = true)) return LikelyLanguage.UNKNOWN
            // ML Kit has one Chinese; Taiwanese titles are written in it as well.
            val chineseTitle = tag.startsWith("zh", ignoreCase = true)
            if (chineseTitle && language.code in CHINESE_WRITTEN) return LikelyLanguage.SAME
            val same = language.captionCodes.any { c ->
                tag.equals(c, ignoreCase = true) || tag.startsWith("$c-", ignoreCase = true) || c.startsWith("$tag-", ignoreCase = true)
            }
            return if (same) LikelyLanguage.SAME else LikelyLanguage.OTHER
        }

        private val CHINESE_WRITTEN = setOf("zh", "yue", "nan")
    }
}
