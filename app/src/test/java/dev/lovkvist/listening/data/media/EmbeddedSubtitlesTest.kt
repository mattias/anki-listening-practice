package dev.lovkvist.listening.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddedSubtitlesTest {
    private fun tx3g(text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        return byteArrayOf((body.size shr 8).toByte(), body.size.toByte()) + body
    }

    @Test
    fun readsThe3gppTimedTextSample() {
        assertEquals("こんにちは", EmbeddedSubtitles.textOf("text/3gpp-tt", tx3g("こんにちは")))
        // Style boxes after the text are not part of it.
        assertEquals("Hi", EmbeddedSubtitles.textOf("text/3gpp-tt", tx3g("Hi") + byteArrayOf(0, 0, 0, 12, 's'.code.toByte())))
        // An empty sample clears the previous line.
        assertEquals("", EmbeddedSubtitles.textOf("text/3gpp-tt", byteArrayOf(0, 0)))
        assertEquals("", EmbeddedSubtitles.textOf("text/3gpp-tt", byteArrayOf()))
    }

    @Test
    fun readsAssAndSrtSamples() {
        val ass = "12,0,Default,,0,0,0,,{\\i1}Hello, there{\\i0}\\Nsecond line".toByteArray()
        assertEquals("Hello, there\nsecond line", EmbeddedSubtitles.textOf("text/x-ssa", ass))
        assertEquals("Hello", EmbeddedSubtitles.textOf("application/x-subrip", "<i>Hello</i>".toByteArray()))
    }

    @Test
    fun aLineLastsUntilTheNextSample() {
        val cues = EmbeddedSubtitles.cuesFrom(
            listOf(
                5_000L to "second", 1_000L to "first", 3_000L to "",
                20_000L to "last",
            ),
        )
        assertEquals(listOf("first", "second", "last"), cues.map { it.text })
        // First ends where the empty sample clears it; second runs to the next line but no longer than ten seconds.
        assertEquals(1_000L to 3_000L, cues[0].startMs to cues[0].endMs)
        assertEquals(5_000L to 15_000L, cues[1].startMs to cues[1].endMs)
        assertEquals(20_000L to 30_000L, cues[2].startMs to cues[2].endMs)
    }
}
