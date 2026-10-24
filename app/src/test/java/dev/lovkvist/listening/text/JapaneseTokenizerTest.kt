package dev.lovkvist.listening.text

import dev.lovkvist.listening.data.anki.AnkiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseTokenizerTest {
    private val tokenizer = JapaneseTokenizer()

    @Test
    fun conjugatedVerbsMatchTheirDictionaryForm() {
        val known = setOf("私", "は", "本", "を", "読む", "ます", "た")
        assertTrue(tokenizer.isFullyKnown("私は本を読みました。", known))
    }

    @Test
    fun oneUnknownWordExcludesTheSentence() {
        val known = setOf("私", "は", "を", "読む", "ます", "た")
        assertFalse(tokenizer.isFullyKnown("私は本を読みました。", known))
    }

    @Test
    fun unknownWordsAreCountedOncePerDictionaryForm() {
        val known = setOf("私", "は", "を", "ます", "た")
        // 本 and 読む are unknown; 本 appears twice but counts once.
        assertEquals(2, tokenizer.unknownWords("私は本を読みました。本は", known))
        assertEquals(null, tokenizer.unknownWords("。！", known))
    }

    @Test
    fun unknownWordsAreFoundWhereTheyAre() {
        val known = setOf("私", "は", "を", "ます", "た")
        // 本 at 2 and 読み (読む) at 4–5.
        assertEquals(listOf(2..2, 4..5), tokenizer.unknownRanges("私は本を読みました。", known))
    }

    @Test
    fun kanaSpellingInKnownListCounts() {
        // Known list has the kana spelling; the sentence uses kanji.
        val known = setOf("わたし", "は", "ねこ", "が", "好き", "です")
        assertTrue(tokenizer.isFullyKnown("私は猫が好きです", known))
    }

    @Test
    fun generatedFuriganaKeepsOkuriganaOutside() {
        val segments = AnkiText.ruby(tokenizer.furigana("私は本を食べました")).filter { it.reading != null }
        assertEquals(
            listOf(
                AnkiText.RubySegment("私", "わたし"),
                AnkiText.RubySegment("本", "ほん"),
                AnkiText.RubySegment("食", "た"),
            ),
            segments,
        )
        assertEquals("私は本を食べました", AnkiText.stripFurigana(tokenizer.furigana("私は本を食べました")))
    }

    @Test
    fun rubySegmentsParseAnkiFurigana() {
        val segs = AnkiText.ruby("日本語[にほんご]を 勉強[べんきょう]する")
        assertEquals(
            listOf(
                AnkiText.RubySegment("日本語", "にほんご"),
                AnkiText.RubySegment("を", null),
                AnkiText.RubySegment("勉強", "べんきょう"),
                AnkiText.RubySegment("する", null),
            ),
            segs,
        )
        assertEquals("日本語を勉強する", AnkiText.stripFurigana("日本語[にほんご]を 勉強[べんきょう]する"))
    }
}
