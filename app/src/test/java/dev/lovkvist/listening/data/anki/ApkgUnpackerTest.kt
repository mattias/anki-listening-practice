package dev.lovkvist.listening.data.anki

import com.github.luben.zstd.Zstd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkgUnpackerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sqliteHeader = "SQLite format 3\u0000".toByteArray()

    @Test
    fun legacyPackageWithJsonMediaMap() {
        val zip = zip(
            "collection.anki21" to sqliteHeader,
            "media" to """{"0": "a.mp3", "1": "日本.mp3"}""".toByteArray(),
            "0" to "AAA".toByteArray(),
            "1" to "BBB".toByteArray(),
        )
        val dest = tmp.newFolder()
        val result = ApkgUnpacker().unpack(ByteArrayInputStream(zip), dest)

        assertEquals(2, result.mediaCount)
        assertEquals("AAA", File(dest, "media/a.mp3").readText())
        assertEquals("BBB", File(dest, "media/日本.mp3").readText())
        assertEquals(sqliteHeader.toList(), result.collection.readBytes().toList())
        assertFalse(File(dest, "staging").exists())
    }

    @Test
    fun modernPackagePrefersAnki21bAndDecompressesEverything() {
        val realDb = sqliteHeader + "real".toByteArray()
        val zip = zip(
            // Modern exports include a stub collection.anki2 that must lose to anki21b.
            "collection.anki2" to sqliteHeader + "stub".toByteArray(),
            "collection.anki21b" to Zstd.compress(realDb),
            "0" to Zstd.compress("first clip".toByteArray()),
            "1" to Zstd.compress("second clip".toByteArray()),
            "media" to Zstd.compress(mediaEntries("x.mp3", "y.ogg")),
            "meta" to byteArrayOf(0x08, 0x03),
        )
        val dest = tmp.newFolder()
        val result = ApkgUnpacker().unpack(ByteArrayInputStream(zip), dest)

        assertEquals(realDb.toList(), result.collection.readBytes().toList())
        assertEquals("first clip", File(dest, "media/x.mp3").readText())
        assertEquals("second clip", File(dest, "media/y.ogg").readText())
        assertFalse(File(dest, "collection.anki2").exists())
    }

    @Test
    fun unsafeMediaNamesAreSkipped() {
        val zip = zip(
            "collection.anki21" to sqliteHeader,
            "media" to """{"0": "../evil.mp3", "1": "ok.mp3"}""".toByteArray(),
            "0" to "x".toByteArray(),
            "1" to "y".toByteArray(),
        )
        val dest = tmp.newFolder()
        val result = ApkgUnpacker().unpack(ByteArrayInputStream(zip), dest)

        assertEquals(1, result.mediaCount)
        assertFalse(File(dest.parentFile, "evil.mp3").exists())
    }

    /** Encodes MediaEntries { repeated MediaEntry { string name = 1; uint32 size = 2; } }. */
    private fun mediaEntries(vararg names: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (name in names) {
            val n = name.toByteArray()
            val entry = byteArrayOf(0x0a, n.size.toByte()) + n + byteArrayOf(0x10, 0x05)
            out.write(0x0a)
            out.write(entry.size)
            out.write(entry)
        }
        return out.toByteArray()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
