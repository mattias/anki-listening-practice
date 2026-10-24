package dev.lovkvist.listening.data.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class YomitanReaderTest {
    private class Collector : DictionarySink {
        val terms = mutableListOf<List<Any>>()
        val meta = mutableListOf<Triple<String, String, String>>()
        val kanji = mutableListOf<String>()
        val tags = mutableListOf<String>()

        override fun term(
            expression: String, reading: String, definitionTags: String, rules: String,
            score: Int, glossary: String, sequence: Long, termTags: String,
        ) {
            terms += listOf(expression, reading, definitionTags, rules, score, glossary, sequence, termTags)
        }

        override fun meta(expression: String, mode: String, data: String) {
            meta += Triple(expression, mode, data)
        }

        override fun kanji(character: String, onyomi: String, kunyomi: String, tags: String, meanings: String, stats: String) {
            kanji += "$character|$onyomi|$kunyomi|$meanings"
        }

        override fun tag(name: String, category: String, order: Int, notes: String) {
            tags += "$name|$category|$order|$notes"
        }
    }

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun readsEveryKindOfBankWhateverTheOrder() {
        val bytes = zip(
            // Banks before the index, as some zips have them.
            "term_bank_1.json" to """[
                ["猫","ねこ","n","",10,[{"type":"structured-content","content":{"tag":"ul","content":[{"tag":"li","content":"cat"}]}}],1234,"common"],
                ["食べる","たべる","v1","v1",5,["to eat"],99,""]
            ]""",
            "term_meta_bank_1.json" to """[["猫","freq",{"value":1500,"displayValue":"1500㋕"}],["猫","freq",3]]""",
            "kanji_bank_1.json" to """[["猫","ビョウ","ねこ","jouyou",["cat"],{"strokes":"11"}]]""",
            "tag_bank_1.json" to """[["n","partOfSpeech",-3,"noun",0]]""",
            "index.json" to """{"title":"Test [2026-10-01]","format":3,"revision":"r2","isUpdatable":true,
                "indexUrl":"https://example.org/index.json","downloadUrl":"https://example.org/d.zip","sourceLanguage":"ja"}""",
        )
        val sink = Collector()
        var entries = 0
        val index = YomitanReader.read(bytes.inputStream(), sink) { entries = it }

        assertEquals("Test [2026-10-01]", index.title)
        assertEquals("r2", index.revision)
        assertTrue(index.updatable)
        assertEquals("https://example.org/index.json", index.indexUrl)
        assertEquals("ja", index.sourceLanguage)

        assertEquals(2, sink.terms.size)
        assertEquals(listOf("猫", "ねこ", "n", "", 10), sink.terms[0].take(5))
        assertTrue((sink.terms[0][5] as String).contains("structured-content"))
        assertEquals(1234L, sink.terms[0][6])
        assertEquals("[\"to eat\"]", sink.terms[1][5])
        assertEquals(2, sink.meta.size)
        assertEquals(listOf("猫|ビョウ|ねこ|[\"cat\"]"), sink.kanji)
        assertEquals(listOf("n|partOfSpeech|-3|noun"), sink.tags)
        assertEquals(5, entries)
    }

    @Test
    fun aMissingRevisionFallsBackToTheTitle() {
        // Jitendex dates its title instead of giving a revision.
        val index = DictionaryIndex.parse("""{"title":"Jitendex.org [2026-10-03]","isUpdatable":true}""")
        assertEquals("Jitendex.org [2026-10-03]", index.revision)
    }

    @Test
    fun frequenciesInEveryShape() {
        assertEquals("3", DictionaryRepository.frequencyText("3", null))
        assertEquals("1㋕", DictionaryRepository.frequencyText("""{"value":1,"displayValue":"1㋕"}""", null))
        assertEquals("1500", DictionaryRepository.frequencyText("""{"value":1500}""", null))
        val forReading = """{"reading":"の","frequency":{"value":1,"displayValue":"1㋕"}}"""
        assertEquals("1㋕", DictionaryRepository.frequencyText(forReading, "の"))
        assertNull(DictionaryRepository.frequencyText(forReading, "ゆき"))
    }

    @Test
    fun glossariesSurviveDeflating() {
        val text = """[{"type":"structured-content","content":"日本語 ✓"}]"""
        assertEquals(text, DictionaryDatabase.inflate(DictionaryDatabase.deflate(text)))
    }
}
