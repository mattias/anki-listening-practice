package dev.lovkvist.listening.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongLyricsTest {
    private val lrc = """
        [ar:Someone]
        [ti:A song]
        [00:12.50]First line
        [00:16.00]Second line <00:17.00>with words
        [00:20.25]
        [00:40.00]Third line
        [00:40.00][01:10.5]Chorus
        [01:14.00]Last line
    """.trimIndent()

    @Test
    fun parsesLrcLines() {
        val cues = Subtitles.parse("song.lrc", lrc)
        assertEquals(listOf("First line", "Second line with words", "Third line", "Chorus", "Chorus", "Last line"), cues.map { it.text })
        // A line ends where the next stamp starts.
        assertEquals(12_500L to 16_000L, cues[0].startMs to cues[0].endMs)
        // An empty line is a marker (a break): the line before ends there, and it is no cue itself.
        assertEquals(16_000L to 20_250L, cues[1].startMs to cues[1].endMs)
        // Long gaps aren't looped: ten seconds at most.
        assertEquals(40_000L, cues[2].startMs)
        assertEquals(40_000L, cues[3].startMs)
        assertEquals(70_500L, cues[4].startMs)
        // The last line gets a few seconds.
        assertEquals(74_000L to 79_000L, cues.last().startMs to cues.last().endMs)
    }

    @Test
    fun repeatedLinesStaySeparateInLrc() {
        val cues = Subtitles.parse("song.lrc", "[00:10.00]Na na\n[00:12.00]Na na\n[00:14.00]Hey")
        assertEquals(listOf("Na na", "Na na", "Hey"), cues.map { it.text })
    }

    @Test
    fun queriesFromTitles() {
        // A Japanese title with the song in brackets.
        assertEquals("夜に駆ける", LrcLib.queries("YOASOBI「夜に駆ける」 Official Music Video", "YOASOBI").first())
        // An English one: noise and brackets go.
        val q = LrcLib.queries("Artist - Song Name (Official Video) [4K]", "ArtistVEVO")
        assertEquals("Artist Song Name", q.first())
        assert(q.size <= 6)
    }

    @Test
    fun queriesTryBracketedAndPartsOfTheTitle() {
        val q = LrcLib.queries("【MV】Creepy Nuts - 二度寝(Nidone)", "Creepy Nuts")
        // LRCLIB has this song as "Nidone"; searching artist and Japanese title together finds nothing.
        assert("Nidone" in q)
        assert("二度寝" in q)
        assertEquals("Creepy Nuts 二度寝", q.first())
    }

    @Test
    fun picksTheRecordingOfTheRightLength() {
        val studio = Lyrics("Song", "Artist", 215.0, "[00:01.00]a")
        val live = Lyrics("Song (Live)", "Artist", 262.0, "[00:01.00]a")
        val noTimes = Lyrics("Song", "Artist", 216.0, "")
        assertEquals(studio, LrcLib.pickBest(listOf(live, noTimes, studio), 217))
        // Nothing within a few seconds: no match, rather than lyrics that drift.
        assertNull(LrcLib.pickBest(listOf(live), 217))
        assertEquals(live, LrcLib.pickBest(listOf(live), null))
    }

    @Test
    fun aLongerMusicVideoStillGetsTheNamedSong() {
        val song = Lyrics("Nidone", "Creepy Nuts", 207.0, "[00:05.00]a")
        val stranger = Lyrics("Nidone", "Nobody", 250.0, "[00:05.00]a")
        val hint = "【MV】Creepy Nuts - 二度寝(Nidone) Creepy Nuts"
        // 237 s against 207 s: nothing fits, as a strict match...
        assertNull(LrcLib.pickBest(listOf(song, stranger), 237, hint))
        // ...but the song by the named artist is taken, with the difference noted.
        val loose = LrcLib.pickLoose(listOf(stranger, song), 237, hint)!!
        assertEquals(song.trackName to song.artistName, loose.trackName to loose.artistName)
        assertEquals(30.0, loose.lengthDiffSeconds, 0.001)
        // A song nobody named, or one far off in length, isn't taken.
        assertNull(LrcLib.pickLoose(listOf(song), 237, "Unrelated video"))
        assertNull(LrcLib.pickLoose(listOf(song), 400, hint))
    }

    @Test
    fun prefersTheArtistNamedInTheVideo() {
        val other = Lyrics("Q", "Someone Else", 158.0, "[00:01.00]a")
        val right = Lyrics("Q", "Mori Calliope", 160.5, "[00:01.00]a")
        // The other one's length is closer, but the artist is the one in the video's title.
        assertEquals(right, LrcLib.pickBest(listOf(other, right), 158, "Mori Calliope - Q (MV)"))
        assertEquals(other, LrcLib.pickBest(listOf(other, right), 158, "unrelated"))
    }

    @Test
    fun parsesLrclibAnswers() {
        val json = """[
            {"id": 1, "trackName": "Song", "artistName": "Artist", "duration": 215.4, "instrumental": false,
             "plainLyrics": "a", "syncedLyrics": "[00:01.00]a"},
            {"id": 2, "trackName": "Instr", "artistName": "Artist", "duration": 100, "instrumental": true, "syncedLyrics": null},
            {"id": 3, "trackName": "Plain only", "artistName": "Artist", "duration": 120, "instrumental": false, "syncedLyrics": null}
        ]"""
        val results = LrcLib.parseResults(json)
        assertEquals(listOf("Song"), results.map { it.trackName })
        assertEquals(215.4, results[0].durationSeconds!!, 0.001)
    }

    @Test
    fun musicMetadataIsSearchedFirst() {
        val q = LrcLib.queries("Some Title (Official Video)", "Channel", track = "Real Song", artist = "Real Artist")
        assertEquals("Real Song Real Artist", q.first())
        assert(q.size <= 6)
    }
}
