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
    fun otherLanguagesDoNotCount() {
        assertNull(pickTrack(listOf("en"), listOf("en-orig"), Language.FRENCH))
    }
}
