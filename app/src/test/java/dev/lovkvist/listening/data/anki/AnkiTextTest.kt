package dev.lovkvist.listening.data.anki

import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiTextTest {
    private fun segments(furigana: String) = AnkiText.ruby(furigana).map { it.base to it.reading }

    @Test
    fun htmlRubyBecomesBrackets() {
        val html = "<ruby>日本語<rt>にほんご</rt></ruby>を<ruby>勉強<rt>べんきょう</rt></ruby>する"
        assertEquals(
            listOf("日本語" to "にほんご", "を" to null, "勉強" to "べんきょう", "する" to null),
            segments(AnkiText.rubyHtmlToBrackets(html)),
        )
    }

    @Test
    fun htmlRubyWithRbRpAndSeveralPairs() {
        val html = "<ruby><rb>漢</rb><rp>(</rp><rt>かん</rt><rp>)</rp><rb>字</rb><rp>(</rp><rt>じ</rt><rp>)</rp></ruby>です"
        assertEquals(
            listOf("漢" to "かん", "字" to "じ", "です" to null),
            segments(AnkiText.rubyHtmlToBrackets(html)),
        )
    }

    @Test
    fun otherHtmlIsLeftAlone() {
        assertEquals("<b>猫</b>が好き", AnkiText.rubyHtmlToBrackets("<b>猫</b>が好き"))
    }

    @Test
    fun bracketAndHtmlFormatsGiveTheSameSegments() {
        val brackets = "私[わたし]は 猫[ねこ]が 好[す]き"
        val html = "<ruby>私<rt>わたし</rt></ruby>は<ruby>猫<rt>ねこ</rt></ruby>が<ruby>好<rt>す</rt></ruby>き"
        assertEquals(segments(brackets), segments(AnkiText.rubyHtmlToBrackets(html)))
    }

    @Test
    fun allSoundTagsInAFieldAreFound() {
        assertEquals(
            listOf("a.mp3", "b c.ogg"),
            AnkiText.soundFiles("[sound:a.mp3]<br>[sound: b c.ogg ]").toList(),
        )
    }
}
