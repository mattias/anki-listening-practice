package dev.lovkvist.listening.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitlesTest {

    @Test
    fun srt() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,500
            <i>こんにちは</i>

            2
            00:00:04,000 --> 00:00:06,000
            元気ですか？
            はい
        """.trimIndent()
        assertEquals(
            listOf(SubtitleCue(1000, 3500, "こんにちは"), SubtitleCue(4000, 6000, "元気ですか？\nはい")),
            Subtitles.parse("ep1.srt", srt),
        )
    }

    @Test
    fun vttWithShortTimestampsAndSettings() {
        val vtt = """
            WEBVTT
            Kind: captions

            NOTE a comment

            intro
            00:01.500 --> 00:03.000 align:start position:0%
            <c.colorE5E5E5>猫が</c><00:00:02.000><c> 好き</c>
        """.trimIndent()
        assertEquals(listOf(SubtitleCue(1500, 3000, "猫が 好き")), Subtitles.parse("a.ja.vtt", vtt))
    }

    @Test
    fun assKeepsCommasInTextAndStripsOverrides() {
        val ass = """
            [Script Info]
            Title: test

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\an8}行こう、早く\Nね
            Comment: 0,0:00:04.00,0:00:05.00,Default,,0,0,0,,not shown
        """.trimIndent()
        assertEquals(listOf(SubtitleCue(1500, 3000, "行こう、早く\nね")), Subtitles.parse("ep.ass", ass))
    }

    @Test
    fun youtubeJson3EndsOverlappingLinesAtNextStart() {
        val json = """
            {"events":[
              {"tStartMs":0,"dDurationMs":4000,"segs":[{"utf8":"今日は"},{"utf8":"晴れ"}]},
              {"tStartMs":2000,"dDurationMs":10,"aAppend":1,"segs":[{"utf8":"\n"}]},
              {"tStartMs":2500,"dDurationMs":3000,"segs":[{"utf8":"です"}]}
            ]}
        """.trimIndent()
        assertEquals(
            listOf(SubtitleCue(0, 2500, "今日は晴れ"), SubtitleCue(2500, 5500, "です")),
            Subtitles.parse("x.ja.json3", json),
        )
    }

    @Test
    fun linesJoinWithSpacesOnlyWhereTheLanguageUsesThem() {
        assertEquals("Jag dricker kaffe", Subtitles.joinLines("Jag dricker\nkaffe", unspaced = false))
        assertEquals("今日はいい天気", Subtitles.joinLines("今日は\nいい天気", unspaced = true))
        // Romanized Taiwanese inside an unspaced language keeps its word boundary.
        assertEquals("Guá beh khì tshit-thô", Subtitles.joinLines("Guá beh khì\ntshit-thô", unspaced = true))
        assertEquals("我欲去tshit-thô", Subtitles.joinLines("我欲去\ntshit-thô", unspaced = true))
    }

    @Test
    fun identicalBackToBackCuesMerge() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nあ\n\n2\n00:00:02,000 --> 00:00:03,000\nあ\n"
        assertEquals(listOf(SubtitleCue(1000, 3000, "あ")), Subtitles.parse("a.srt", srt))
    }
}
