package dev.lovkvist.listening.data.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundClientTest {
    private val batch = """
        {"id": "a1b2c3", "created": 1.7e9, "state": "speaking", "error": "", "total": 2, "done": 1,
         "phenomena": ["i_wo"], "translation_language": "sv",
         "items": [
           {"id": "0123456789abcdef0123456789abcdef", "text": "今日、薬を飲む。", "spoken": "今日、くすりょ飲む。", "forced": true,
            "translation": "Idag tar jag medicin.", "target": "i_wo", "phenomena": ["i_wo", "devoice_mid"], "matches": ["薬を"],
            "audio_ready": true, "voice": "male_calm", "seconds": 2.3},
           {"id": "fedcba9876543210fedcba9876543210", "text": "友達を待つ。", "spoken": "友達を待つ。", "forced": false,
            "translation": "", "target": "i_wo", "phenomena": ["i_wo"], "matches": ["友達を"], "audio_ready": false}
         ]}
    """.trimIndent()

    @Test
    fun readsABatch() {
        val b = SoundClient.parseBatch(batch)
        assertEquals("speaking", b.state)
        assertEquals(1, b.done)
        assertEquals(2, b.total)
        val first = b.items[0]
        assertEquals("今日、くすりょ飲む。", first.spoken)
        assertEquals(listOf("i_wo", "devoice_mid"), first.phenomena)
        assertEquals(listOf("薬を"), first.matches)
        assertTrue(first.audioReady)
        assertFalse(b.items[1].audioReady)
    }

    @Test
    fun aMissingSpokenFormIsTheText() {
        val item = SoundClient.parseBatch("""{"id":"x","state":"done","items":[{"id":"ab","text":"これは本です。","audio_ready":true}]}""").items[0]
        assertEquals("これは本です。", item.spoken)
        assertEquals(emptyList<String>(), item.phenomena)
    }

    @Test
    fun readsThePhenomenaList() {
        val list = SoundClient.parsePhenomena("""[{"id":"i_wo","example":"薬を → くすりょ","title":"t","description":"d","forced":true}]""")
        assertEquals("i_wo", list.single().id)
        assertEquals("薬を → くすりょ", list.single().example)
    }

    @Test
    fun sentenceIdsAreNegativeAndStable() {
        val a = SoundLibrary.sentenceId("0123456789abcdef0123456789abcdef")
        assertTrue(a < 0)
        assertEquals(a, SoundLibrary.sentenceId("0123456789abcdef0123456789abcdef"))
        assertNotEquals(a, SoundLibrary.sentenceId("fedcba9876543210fedcba9876543210"))
        // Far from Anki note ids (positive) and from subtitle lines of videos (around -1.7e17).
        assertTrue(a > -2_000_000_000_000_000L)
    }
}
