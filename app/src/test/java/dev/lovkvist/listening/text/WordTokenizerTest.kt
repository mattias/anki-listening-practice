package dev.lovkvist.listening.text

import dev.lovkvist.listening.l10n.displayName
import dev.lovkvist.listening.l10n.label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class WordTokenizerTest {
    private fun known(language: Language, vararg words: String): Pair<WordTokenizer, Set<String>> {
        val tokenizer = WordTokenizer(language)
        return tokenizer to words.mapTo(HashSet(), tokenizer::normalize)
    }

    @Test
    fun wordsSkipPunctuationAndNumbers() {
        assertEquals(listOf("Hej", "Jag", "är", "år"), WordTokenizer(Language.SWEDISH).words("Hej! Jag är 30 år."))
    }

    @Test
    fun matchingIgnoresCase() {
        val (t, known) = known(Language.SWEDISH, "hej", "jag", "är", "här")
        assertTrue(t.isFullyKnown("Hej, jag är HÄR.", known))
    }

    @Test
    fun oneUnknownWordExcludesTheSentence() {
        val (t, known) = known(Language.ENGLISH, "i", "like")
        assertFalse(t.isFullyKnown("I like cats.", known))
    }

    @Test
    fun inflectedFormsMustBeListed() {
        val (t, known) = known(Language.GERMAN, "ich", "gehen")
        assertFalse(t.isFullyKnown("Ich gehe.", known))
    }

    @Test
    fun frenchElisionMatchesItsParts() {
        val (t, known) = known(Language.FRENCH, "l'", "homme", "est", "là")
        assertTrue(t.isFullyKnown("L’homme est là.", known))
    }

    @Test
    fun russianStressMarksAreIgnored() {
        val (t, known) = known(Language.RUSSIAN, "я", "люблю", "молоко")
        assertTrue(t.isFullyKnown("Я люблю́ молоко́.", known))
    }

    @Test
    fun arabicVowelMarksAreIgnored() {
        val (t, known) = known(Language.ARABIC, "كتاب")
        assertTrue(t.isFullyKnown("كِتَابٌ", known))
    }

    @Test
    fun turkishUsesTurkishCasing() {
        val (t, known) = known(Language.TURKISH, "ılık")
        assertTrue(t.isFullyKnown("ILIK", known))
    }

    @Test
    fun koreanParticlesAfterAKnownWordCount() {
        val (t, known) = known(Language.KOREAN, "학교", "가다")
        assertTrue(t.isFullyKnown("학교에서는", known))
        assertFalse(t.isFullyKnown("학생에서", known))
    }

    @Test
    fun unspacedTextIsKnownIfItSplitsIntoKnownWords() {
        // The JVM's word breaker doesn't segment Chinese (Android's ICU does), so this exercises the fallback.
        val (t, known) = known(Language.CHINESE, "我", "喜欢", "猫")
        assertTrue(t.isFullyKnown("我喜欢猫。", known))
        assertFalse(t.isFullyKnown("我喜欢狗。", known))
    }

    @Test
    fun tagalogContractionsMatchTheFullWords() {
        val (t, known) = known(Language.TAGALOG, "ako", "ay", "masaya", "siya", "at", "ikaw")
        assertTrue(t.isFullyKnown("Ako'y masaya.", known))
        assertTrue(t.isFullyKnown("Siya’t ikaw.", known))
        assertFalse(t.isFullyKnown("Ako'y malungkot.", known))
    }

    @Test
    fun tagalogLinkerAttachedToTheWord() {
        val (t, known) = known(Language.TAGALOG, "maganda", "araw", "ulan", "malakas")
        assertTrue(t.isFullyKnown("Magandang araw!", known))
        assertTrue(t.isFullyKnown("Malakas na ulan.", known.plus("na")))
        assertTrue(t.isFullyKnown("Ulang malakas.", known))
        assertFalse(t.isFullyKnown("Mabuting araw!", known))
    }

    @Test
    fun hyphenatedCompoundsListedAsAWhole() {
        val (t, known) = known(Language.TAGALOG, "mag-aral", "tayo", "araw-araw")
        assertTrue(t.isFullyKnown("Mag-aral tayo araw-araw.", known))
        assertFalse(t.isFullyKnown("Mag-ingat tayo.", known))
        val (en, enKnown) = known(Language.ENGLISH, "a", "well-known", "song")
        assertTrue(en.isFullyKnown("A well-known song.", enKnown))
    }

    @Test
    fun taiwaneseInHanCharacters() {
        val (t, known) = known(Language.TAIWANESE, "我", "欲", "去", "台北")
        assertTrue(t.isFullyKnown("我欲去台北。", known))
        assertFalse(t.isFullyKnown("我欲去高雄。", known))
    }

    @Test
    fun taiwaneseInTailoWithToneMarks() {
        val (t, known) = known(Language.TAIWANESE, "guá", "beh", "khì", "tshit-thô")
        assertTrue(t.isFullyKnown("Guá beh khì tshit-thô.", known))
        // The same tone mark typed as a separate combining character still matches.
        assertTrue(t.isFullyKnown("Guá beh khì tshit-thô.", known))
        assertFalse(t.isFullyKnown("Guá beh khì tsia̍h-pn̄g.", known))
    }

    @Test
    fun taiwaneseMatchesItsTagAndCode() {
        assertTrue(Language.TAIWANESE.matchesTag("nan-TW"))
        assertEquals(Language.TAIWANESE, Language.fromCode("nan"))
        assertEquals("台語 (Taiwanese)", Language.TAIWANESE.label(Locale.ENGLISH))
    }

    @Test
    fun languageNamesFollowTheAppLanguage() {
        assertEquals("Chinese (Mandarin)", Language.CHINESE.displayName(Locale.ENGLISH))
        assertEquals("Japanska", Language.JAPANESE.displayName(Locale.forLanguageTag("sv")))
        assertEquals("Svenska", Language.SWEDISH.label(Locale.forLanguageTag("sv")))
        assertEquals("日本語 (Japanska)", Language.JAPANESE.label(Locale.forLanguageTag("sv")))
    }

    @Test
    fun tagalogMatchesFilipinoTags() {
        assertTrue(Language.TAGALOG.matchesTag("tl"))
        assertTrue(Language.TAGALOG.matchesTag("fil"))
        assertEquals(Language.TAGALOG, Language.fromCode("tl"))
    }
}
