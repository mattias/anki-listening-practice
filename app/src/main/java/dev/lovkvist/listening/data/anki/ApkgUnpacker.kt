package dev.lovkvist.listening.data.anki

import com.github.luben.zstd.ZstdInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Unpacks an Anki export (.apkg deck or .colpkg collection) in a single streaming pass.
 *
 * Handles both package layouts:
 * - Legacy ("Support older Anki versions"): plain SQLite `collection.anki21`/`.anki2`,
 *   a JSON `media` map, uncompressed media files.
 * - Modern (Anki 2.1.50+ / AnkiDroid 2.16+ default): zstd-compressed `collection.anki21b`,
 *   a zstd-compressed protobuf `media` map, zstd-compressed media files. Modern packages also
 *   contain a stub `collection.anki2` that must be ignored.
 *
 * Media inside the zip are stored as "0", "1", ...; the map restores their real names.
 */
class ApkgUnpacker {

    data class Result(val collection: File, val mediaCount: Int)

    fun unpack(input: InputStream, destDir: File, onEntry: (Int) -> Unit = {}): Result {
        destDir.mkdirs()
        val staging = File(destDir, "staging").apply { mkdirs() }
        val mediaDir = File(destDir, "media").apply { mkdirs() }
        var mediaMap: ByteArray? = null
        var entries = 0

        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                when {
                    entry.isDirectory -> Unit
                    name in COLLECTION_NAMES -> decompressed(zip).copyToFile(File(destDir, name))
                    name == "media" -> mediaMap = decompressed(zip).readBytes()
                    name.isNotEmpty() && name.all(Char::isDigit) ->
                        decompressed(zip).copyToFile(File(staging, name))
                    else -> Unit // "meta" and anything unknown
                }
                zip.closeEntry()
                onEntry(++entries)
            }
        }

        // Prefer the newest format present; modern packages ship a dummy collection.anki2.
        val collection = COLLECTION_NAMES.map { File(destDir, it) }.firstOrNull { it.isFile }
            ?: throw IOException("Not an Anki package: no collection found")
        val target = File(destDir, COLLECTION_FILE)
        if (!collection.renameTo(target)) throw IOException("Could not store the collection")
        COLLECTION_NAMES.forEach { File(destDir, it).delete() }

        var count = 0
        for ((index, realName) in parseMediaMap(mediaMap)) {
            val staged = File(staging, index)
            if (!staged.isFile || !isSafeName(realName)) continue
            if (staged.renameTo(File(mediaDir, realName))) count++
        }
        staging.deleteRecursively()
        return Result(target, count)
    }

    /** Maps zip entry name ("0", "1", ...) to the real media file name. */
    internal fun parseMediaMap(bytes: ByteArray?): Map<String, String> {
        if (bytes == null || bytes.isEmpty()) return emptyMap()
        val text = bytes.decodeToString().trimStart()
        if (text.startsWith("{")) {
            val json = JSONObject(text)
            return json.keys().asSequence().associateWith { json.getString(it) }
        }
        return parseMediaEntries(bytes)
    }

    /**
     * Minimal decoder for Anki's `MediaEntries` protobuf:
     * `message MediaEntries { repeated MediaEntry entries = 1; }`
     * `message MediaEntry { string name = 1; uint32 size = 2; bytes sha1 = 3; optional uint32 legacy_zip_filename = 255; }`
     * The zip name is the entry's index unless legacy_zip_filename is set.
     */
    private fun parseMediaEntries(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val reader = ProtoReader(bytes, 0, bytes.size)
        var index = 0
        while (reader.hasMore()) {
            val (field, wire) = reader.tag()
            if (field == 1 && wire == WIRE_LEN) {
                val (start, end) = reader.lengthDelimited()
                var name: String? = null
                var zipName: Long? = null
                val entry = ProtoReader(bytes, start, end)
                while (entry.hasMore()) {
                    val (f, w) = entry.tag()
                    when {
                        f == 1 && w == WIRE_LEN -> entry.lengthDelimited().let { (s, e) ->
                            name = String(bytes, s, e - s, Charsets.UTF_8)
                        }
                        f == 255 && w == WIRE_VARINT -> zipName = entry.varint()
                        else -> entry.skip(w)
                    }
                }
                name?.let { out[(zipName ?: index.toLong()).toString()] = it }
                index++
            } else {
                reader.skip(wire)
            }
        }
        return out
    }

    private class ProtoReader(private val buf: ByteArray, private var pos: Int, private val end: Int) {
        fun hasMore() = pos < end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= end) throw IOException("Truncated media map")
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun tag(): Pair<Int, Int> = varint().let { (it ushr 3).toInt() to (it and 7).toInt() }

        fun lengthDelimited(): Pair<Int, Int> {
            val len = varint().toInt()
            val start = pos
            pos += len
            if (pos > end) throw IOException("Truncated media map")
            return start to pos
        }

        fun skip(wire: Int) {
            when (wire) {
                WIRE_VARINT -> varint()
                WIRE_I64 -> pos += 8
                WIRE_LEN -> lengthDelimited()
                WIRE_I32 -> pos += 4
                else -> throw IOException("Unsupported protobuf wire type $wire")
            }
        }
    }

    /** Transparently zstd-decompresses [zip]'s current entry if it starts with the zstd magic. */
    private fun decompressed(zip: ZipInputStream): InputStream {
        val entry = BufferedInputStream(NonClosing(zip))
        entry.mark(4)
        val head = ByteArray(4)
        val n = entry.readNBytesCompat(head)
        entry.reset()
        val isZstd = n == 4 && head.contentEquals(ZSTD_MAGIC)
        return if (isZstd) ZstdInputStream(entry) else entry
    }

    private fun InputStream.readNBytesCompat(b: ByteArray): Int {
        var total = 0
        while (total < b.size) {
            val r = read(b, total, b.size - total)
            if (r < 0) break
            total += r
        }
        return total
    }

    private fun InputStream.copyToFile(file: File) {
        val tmp = File(file.path + ".part")
        tmp.outputStream().use { out -> copyTo(out, 64 * 1024) }
        if (!tmp.renameTo(file)) throw IOException("Could not write ${file.name}")
    }

    /** Keeps ZstdInputStream/BufferedInputStream from closing the shared zip stream. */
    private class NonClosing(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    private fun isSafeName(name: String) =
        name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != ".."

    companion object {
        const val COLLECTION_FILE = "collection.db"
        private val COLLECTION_NAMES = listOf("collection.anki21b", "collection.anki21", "collection.anki2")
        private val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())
        private const val WIRE_VARINT = 0
        private const val WIRE_I64 = 1
        private const val WIRE_LEN = 2
        private const val WIRE_I32 = 5
    }
}
