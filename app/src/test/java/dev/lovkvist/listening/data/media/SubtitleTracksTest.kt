package dev.lovkvist.listening.data.media

import dev.lovkvist.listening.text.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleTracksTest {
    @Test
    fun manualSubtitlesWin() {
        assertEquals(Track("sv", automatic = false), pickTrack(listOf("en", "sv"), listOf("sv-orig"), Language.SWEDISH))
    }

    @Test
    fun exactCodeBeatsRegionalVariant() {
        assertEquals(Track("es", automatic = false), pickTrack(listOf("es-419", "es"), emptyList(), Language.SPANISH))
        assertEquals(Track("pt-BR", automatic = false), pickTrack(listOf("pt-BR"), emptyList(), Language.PORTUGUESE))
    }

    @Test
    fun autoCaptionsPreferTheOriginalSpeech() {
        assertEquals(Track("ja-orig", automatic = true), pickTrack(emptyList(), listOf("ja", "ja-orig"), Language.JAPANESE))
        assertEquals(Track("iw", automatic = true), pickTrack(emptyList(), listOf("en", "iw"), Language.HEBREW))
    }

    @Test
    fun machineTranslationIsNotTheStudyLanguage() {
        // An English video: YouTube offers "sv" as a translation of the English speech.
        val auto = listOf("en-orig", "en", "sv", "ja", "de")
        assertNull(pickTrack(emptyList(), auto, Language.SWEDISH, allowTranslated = false))
        // As the translation line it is fine.
        assertEquals(Track("sv", automatic = true), pickTrack(emptyList(), auto, Language.SWEDISH))
        // A Swedish video lists its real speech as "sv-orig".
        assertEquals(
            Track("sv-orig", automatic = true),
            pickTrack(emptyList(), listOf("sv-orig", "sv", "en"), Language.SWEDISH, allowTranslated = false),
        )
    }

    @Test
    fun plainCodeStillWorksWithoutAnOrigTrack() {
        // Older yt-dlp versions list no "-orig" track: the plain code is the recognized speech.
        assertEquals(Track("sv", automatic = true), pickTrack(emptyList(), listOf("sv", "en"), Language.SWEDISH, allowTranslated = false))
    }

    @Test
    fun otherLanguagesDoNotCount() {
        assertNull(pickTrack(listOf("en"), listOf("en-orig"), Language.FRENCH))
    }
}
