package dev.lovkvist.listening.data.media

import dev.lovkvist.listening.text.Language
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeSearchTest {
    @Test
    fun parsesFlatSearchResults() {
        val json = """
            {"_type": "playlist", "id": "q", "entries": [
              {"_type": "url", "ie_key": "Youtube", "id": "SmxSCZzV7j0", "url": "https://www.youtube.com/watch?v=SmxSCZzV7j0",
               "title": "Listening Practice", "duration": 1460.0, "channel": "Bite Size Japanese", "uploader": "Other"},
              {"id": "live1", "title": "Live stream", "uploader": "Someone"},
              {"id": "", "title": "No id"},
              {"id": "noTitle"}
            ]}
        """
        val results = YouTubeImporter.parseSearchResults(json)
        assertEquals(listOf("SmxSCZzV7j0", "live1"), results.map { it.id })
        val first = results[0]
        assertEquals("Bite Size Japanese", first.channel)
        assertEquals(1460L, first.durationSeconds)
        assertEquals("https://www.youtube.com/watch?v=SmxSCZzV7j0", first.url)
        // No duration (a live stream), the uploader as channel, and a built link.
        assertNull(results[1].durationSeconds)
        assertEquals("Someone", results[1].channel)
        assertEquals("https://www.youtube.com/watch?v=live1", results[1].url)
    }

    @Test
    fun noEntriesIsEmpty() {
        assertEquals(emptyList<VideoResult>(), YouTubeImporter.parseSearchResults("""{"_type": "playlist"}"""))
    }

    /** yt-dlp info for a video with these subtitle tracks, and optionally its audio language. */
    private fun info(manual: String, auto: String, language: String? = null): JSONObject {
        val json = JSONObject("""{"subtitles": {$manual}, "automatic_captions": {$auto}}""")
        if (language != null) json.put("language", language)
        return json
    }

    private val track = """[{"ext": "vtt"}]"""

    @Test
    fun aVideoSpokenInTheLanguage() {
        val sv = Language.SWEDISH
        // Teknikveckan: speech recognized in Swedish, one human subtitle track, audio language "sv".
        val withSubtitles = info(""""sv": $track""", """"sv-orig": $track, "sv": $track, "en": $track""", "sv")
        assertEquals(SubtitleStatus.MANUAL, YouTubeImporter.subtitleStatus(withSubtitles, sv))
        assertEquals(SubtitleStatus.AUTO, YouTubeImporter.subtitleStatus(info("", """"sv-orig": $track, "sv": $track""", "sv"), sv))
    }

    @Test
    fun dubbedAndTranslatedVideosAreAnotherLanguage() {
        // Natasha's Kitchen: English speech, machine dubs and subtitles into dozens of languages.
        val english = info(""""en": $track, "sv": $track""", """"en-orig": $track, "en": $track, "sv": $track""", "en")
        assertEquals(SubtitleStatus.OTHER_LANGUAGE, YouTubeImporter.subtitleStatus(english, Language.SWEDISH))
        assertEquals(SubtitleStatus.MANUAL, YouTubeImporter.subtitleStatus(english, Language.ENGLISH))
        // The video's own language counts when there is no recognition track.
        assertEquals(SubtitleStatus.OTHER_LANGUAGE, YouTubeImporter.subtitleStatus(info(""""sv": $track""", "", "de"), Language.SWEDISH))
    }

    @Test
    fun captionsOnAMusicVideoAreNoSpeech() {
        // "Fast & Easy Recipes": over a hundred translated subtitle tracks, no speech recognition,
        // no audio language. The translated "sv" among the automatic captions is no speech either.
        val captions = info(""""sv": $track, "de": $track""", """"sv": $track, "de": $track, "fr": $track""")
        assertEquals(SubtitleStatus.NO_SPEECH, YouTubeImporter.subtitleStatus(captions, Language.SWEDISH))
    }

    @Test
    fun noSubtitlesInTheLanguage() {
        assertEquals(SubtitleStatus.NONE, YouTubeImporter.subtitleStatus(info(""""en": $track""", ""), Language.JAPANESE))
        assertEquals(SubtitleStatus.NONE, YouTubeImporter.subtitleStatus(info("", ""), Language.JAPANESE))
    }
}
