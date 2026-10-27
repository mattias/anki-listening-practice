package dev.lovkvist.listening.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongAlignerTimingTest {
    // Invented lines; only their times matter here, since the captions are in another language.
    private val lyrics = (0 until 10).map { i ->
        SubtitleCue(5_000L + i * 6_000, 9_000L + i * 6_000, "lyric line number $i")
    }

    @Test
    fun takesTheShiftFromTheFirstCaptionOfAnotherLanguage() {
        // A translation: other words, the same lines, 18.2 s later, each a bit off in time.
        val captions = lyrics.mapIndexed { i, c -> SubtitleCue(c.startMs + 18_200 + (i % 3) * 200L, c.endMs + 18_200, "traduction $i") }
        assertEquals(18_200L, SongAligner.offsetByTiming(lyrics, captions))
    }

    @Test
    fun soundLabelsDoNotCountAsTheFirstCaption() {
        val captions = listOf(SubtitleCue(0, 3_000, "[Music]"), SubtitleCue(500, 2_000, "♪")) +
            lyrics.mapIndexed { i, c -> SubtitleCue(c.startMs + 12_000, c.endMs + 12_000, "words $i") }
        assertEquals(12_000L, SongAligner.offsetByTiming(lyrics, captions))
    }

    @Test
    fun captionsThatDoNotFollowTheLyricsGiveNoAnswer() {
        // The first caption is far before the singing, the last far after: no single shift fits.
        val captions = listOf(
            SubtitleCue(1_000, 2_000, "intro talk"),
            SubtitleCue(70_000, 72_000, "something later"),
            SubtitleCue(140_000, 142_000, "the end"),
        )
        assertNull(SongAligner.offsetByTiming(lyrics, captions))
        assertNull(SongAligner.offsetByTiming(lyrics, emptyList()))
    }
}
