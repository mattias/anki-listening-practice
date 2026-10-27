package dev.lovkvist.listening.data.media

import dev.lovkvist.listening.data.media.LikelyLanguage.OTHER
import dev.lovkvist.listening.data.media.LikelyLanguage.SAME
import dev.lovkvist.listening.data.media.LikelyLanguage.UNKNOWN
import dev.lovkvist.listening.text.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SearchHelpersTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun titleLanguageVerdict() {
        assertEquals(SAME, TitleLanguage.verdict(Language.SWEDISH, "sv"))
        assertEquals(OTHER, TitleLanguage.verdict(Language.SWEDISH, "en"))
        assertEquals(UNKNOWN, TitleLanguage.verdict(Language.SWEDISH, "und"))
        assertEquals(UNKNOWN, TitleLanguage.verdict(Language.SWEDISH, null))
        // Aliases and regional codes.
        assertEquals(SAME, TitleLanguage.verdict(Language.NORWEGIAN, "no"))
        assertEquals(SAME, TitleLanguage.verdict(Language.HEBREW, "iw"))
        assertEquals(SAME, TitleLanguage.verdict(Language.HEBREW, "he"))
        assertEquals(SAME, TitleLanguage.verdict(Language.TAGALOG, "fil"))
        assertEquals(SAME, TitleLanguage.verdict(Language.PORTUGUESE, "pt"))
        // ML Kit has one Chinese; it fits Mandarin, Cantonese and Taiwanese titles.
        for (l in listOf(Language.CHINESE, Language.CANTONESE, Language.TAIWANESE)) {
            assertEquals(SAME, TitleLanguage.verdict(l, "zh"))
        }
        assertEquals(OTHER, TitleLanguage.verdict(Language.JAPANESE, "zh"))
        assertEquals(SAME, TitleLanguage.verdict(Language.JAPANESE, "ja"))
    }

    @Test
    fun cacheKeepsCertainAnswersPerVideoAndLanguage() {
        val file = folder.newFile("cache.json")
        file.delete()
        val cache = SubtitleCache(file)
        assertNull(cache.get("abc", Language.SWEDISH, now = 1000))
        cache.put("abc", Language.SWEDISH, SubtitleStatus.AUTO, now = 1000)
        cache.put("abc", Language.JAPANESE, SubtitleStatus.NONE, now = 1000)
        cache.put("xyz", Language.SWEDISH, SubtitleStatus.UNKNOWN, now = 1000)
        assertEquals(SubtitleStatus.AUTO, cache.get("abc", Language.SWEDISH, now = 2000))
        assertEquals(SubtitleStatus.NONE, cache.get("abc", Language.JAPANESE, now = 2000))
        // An unsure answer isn't kept, so it is asked again.
        assertNull(cache.get("xyz", Language.SWEDISH, now = 2000))
        // The answers survive a restart.
        val again = SubtitleCache(file)
        assertEquals(SubtitleStatus.AUTO, again.get("abc", Language.SWEDISH, now = 2000))
    }

    @Test
    fun cacheEntriesExpireAfterAMonth() {
        val cache = SubtitleCache(folder.newFile("cache2.json").also { it.delete() })
        val day = 24L * 60 * 60 * 1000
        cache.put("abc", Language.SWEDISH, SubtitleStatus.MANUAL, now = 0)
        assertEquals(SubtitleStatus.MANUAL, cache.get("abc", Language.SWEDISH, now = 29 * day))
        assertNull(cache.get("abc", Language.SWEDISH, now = 31 * day))
    }

    @Test
    fun recognizesRateLimitMessages() {
        assertTrue(YouTubeImporter.isRateLimit("ERROR: [youtube] abc: Sign in to confirm you're not a bot. Use --cookies"))
        assertTrue(YouTubeImporter.isRateLimit("HTTP Error 429: Too Many Requests"))
        assertFalse(YouTubeImporter.isRateLimit("ERROR: Video unavailable"))
        assertFalse(YouTubeImporter.isRateLimit(null))
    }
}
