package dev.lovkvist.listening.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageTest {
    @Test
    fun codesAreUnique() {
        assertEquals(Language.entries.size, Language.entries.map { it.code }.toSet().size)
    }

    @Test
    fun noteTagsMatchRegionalVariants() {
        assertTrue(Language.CHINESE.matchesTag("zh-Hans"))
        assertTrue(Language.PORTUGUESE.matchesTag("pt-BR"))
        assertTrue(Language.SWEDISH.matchesTag(" SV "))
        assertFalse(Language.SWEDISH.matchesTag("sl"))
        assertTrue(Language.HEBREW.matchesTag("iw"))
        assertTrue(Language.CANTONESE.matchesTag("zh-Hant"))
    }

    @Test
    fun unknownCodesFallBackToJapanese() {
        assertEquals(Language.JAPANESE, Language.fromCode(null))
        assertEquals(Language.JAPANESE, Language.fromCode("xx"))
        assertEquals(Language.SWEDISH, Language.fromCode("sv"))
    }

    @Test
    fun onlyJapaneseHasFurigana() {
        assertEquals(listOf(Language.JAPANESE), Language.entries.filter { it.reading == Reading.FURIGANA })
    }
}
