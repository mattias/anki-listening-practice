package dev.lovkvist.listening.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs

class SongAlignerTest {
    // Invented lines: the aligner only looks at how alike texts are.
    private val lines = listOf(
        "the purple harbor wakes up slowly",
        "a lantern swings above the quiet bridge",
        "counting seven paper boats tonight",
        "the copper river hums a tune",
        "echoes follow every step I take",
        "morning spills across the roofs",
        "carry me to the orange hills",
        "nobody knows the road but the wind",
    )

    private fun lyrics(): List<SubtitleCue> =
        lines.mapIndexed { i, t -> SubtitleCue(5_000L + i * 6_000, 5_000L + i * 6_000 + 5_000, t) }

    @Test
    fun findsTheShiftFromRoughCaptions() {
        // Captions are the lines sung 23.4 s later, a little garbled, one missing.
        val captions = lyrics().drop(1).mapIndexed { i, cue ->
            val rough = if (i % 2 == 0) cue.text.replace("a", "e") else cue.text
            SubtitleCue(cue.startMs + 23_400 + (i * 37L), cue.endMs + 23_400, rough)
        }
        val offset = SongAligner.offsetMs(lyrics(), captions)
        assertNotNull(offset)
        assert(abs(offset!! - 23_400) <= 300) { "offset was $offset" }
    }

    @Test
    fun repeatedChorusDoesNotConfuseIt() {
        val chorus = listOf("carry me to the orange hills", "nobody knows the road but the wind")
        val sung = lyrics() + chorus.mapIndexed { i, t -> SubtitleCue(70_000L + i * 6_000, 75_000L + i * 6_000, t) }
        val captions = sung.map { SubtitleCue(it.startMs - 4_000, it.endMs - 4_000, it.text) }
        assertEquals(-4_000L, SongAligner.offsetMs(sung, captions))
    }

    @Test
    fun nothingAlikeGivesNoAnswer() {
        val captions = listOf(
            SubtitleCue(1_000, 3_000, "completely different words here"),
            SubtitleCue(5_000, 7_000, "nothing in common at all"),
        )
        assertNull(SongAligner.offsetMs(lyrics(), captions))
        assertNull(SongAligner.offsetMs(lyrics(), emptyList()))
    }

    @Test
    fun worksWithoutSpacesToo() {
        val ja = listOf("夜の街を走り抜ける", "遠くで鐘が鳴っている", "朝焼けの空に消えていく", "忘れられない光の中で")
        val lyrics = ja.mapIndexed { i, t -> SubtitleCue(10_000L + i * 5_000, 14_000L + i * 5_000, t) }
        val captions = lyrics.map { SubtitleCue(it.startMs + 31_000, it.endMs + 31_000, it.text) }
        assertEquals(31_000L, SongAligner.offsetMs(lyrics, captions))
    }
}
