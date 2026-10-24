package dev.lovkvist.listening.data.dict

import dev.lovkvist.listening.text.Language
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class WiktionaryCatalogTest {
    private val metadata = """
        {"date": "2026-10-22", "dicts": {
          "glossary": {"sources": {"sv": {"targets": {"en": "1.0 MB"}}}},
          "main": {"sources": {
            "sv": {"targets": {"en": "8.0 MB", "de": "813.5 KB"}},
            "zh": {"targets": {"en": "20.3 MB"}},
            "sh": {"targets": {"en": "11.4 MB"}}
          }}
        }}
    """

    @Test
    fun parsesMainDictionaries() {
        val pairs = WiktionaryCatalog.parse(metadata)
        assertEquals(mapOf("en" to "8.0 MB", "de" to "813.5 KB"), pairs["sv"])
        assertEquals(setOf("sv", "zh", "sh"), pairs.keys)
    }

    @Test
    fun chineseVariantsAndCroatianUseWiktionaryCodes() {
        val pairs = WiktionaryCatalog.parse(metadata)
        assertEquals("zh", WiktionaryCatalog.options(pairs, Language.CANTONESE).single().source)
        assertEquals("zh", WiktionaryCatalog.options(pairs, Language.TAIWANESE).single().source)
        assertEquals("sh", WiktionaryCatalog.options(pairs, Language.CROATIAN).single().source)
        assertEquals(emptyList<WiktionaryOption>(), WiktionaryCatalog.options(pairs, Language.KOREAN))
    }

    @Test
    fun suggestsAppLanguageThenEnglish() {
        val options = WiktionaryCatalog.options(WiktionaryCatalog.parse(metadata), Language.SWEDISH)
        assertEquals("de", WiktionaryCatalog.defaultTarget(options, "de"))
        assertEquals("en", WiktionaryCatalog.defaultTarget(options, "fi"))
        // Definitions in the studied language itself aren't the first suggestion.
        val own = listOf(WiktionaryOption("de", "de", "9 MB"), WiktionaryOption("de", "en", "16 MB"))
        assertEquals("en", WiktionaryCatalog.defaultTarget(own, "de"))
    }

    @Test
    fun buildsDownloadUrlAndSize() {
        val d = WiktionaryCatalog.dictionary(WiktionaryOption("sv", "en", "8.0 MB"), Language.SWEDISH, Locale.ENGLISH)
        assertEquals("wty-sv-en", d.sourceId)
        assertEquals("https://huggingface.co/datasets/daxida/wty-release/resolve/main/latest/dict/sv/en/wty-sv-en.zip", d.downloadUrl)
        assertEquals("Swedish → English", d.description)
        assertEquals(8, d.sizeMb)
        assertEquals(1, WiktionaryCatalog.sizeMb("813.5 KB"))
        assertEquals(1229, WiktionaryCatalog.sizeMb("1.2 GB"))
    }
}
