package dev.lovkvist.listening.data.dict

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Imported Yomitan dictionaries in their own SQLite file (they're large and bulk-loaded, so
 * plain SQLite rather than Room). Glossaries are stored deflated: Jitendex's structured content
 * is a few hundred MB as JSON.
 */
class DictionaryDatabase(context: Context) : SQLiteOpenHelper(context, "dictionaries.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE dictionary (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                revision TEXT NOT NULL,
                source_id TEXT,
                role TEXT,
                language TEXT NOT NULL,
                index_url TEXT,
                download_url TEXT,
                updatable INTEGER NOT NULL DEFAULT 0,
                auto_update INTEGER NOT NULL DEFAULT 1,
                enabled INTEGER NOT NULL DEFAULT 1,
                position INTEGER NOT NULL DEFAULT 0,
                imported_at INTEGER NOT NULL,
                checked_at INTEGER NOT NULL DEFAULT 0,
                entries INTEGER NOT NULL DEFAULT 0,
                attribution TEXT NOT NULL DEFAULT '',
                ready INTEGER NOT NULL DEFAULT 0)""",
        )
        db.execSQL(
            "CREATE TABLE term (dict INTEGER NOT NULL, expression TEXT NOT NULL, reading TEXT NOT NULL, " +
                "def_tags TEXT, rules TEXT, score INTEGER, glossary BLOB, sequence INTEGER, term_tags TEXT)",
        )
        db.execSQL("CREATE INDEX term_expression ON term (expression)")
        db.execSQL("CREATE INDEX term_reading ON term (reading)")
        db.execSQL("CREATE TABLE term_meta (dict INTEGER NOT NULL, expression TEXT NOT NULL, mode TEXT, data TEXT)")
        db.execSQL("CREATE INDEX term_meta_expression ON term_meta (expression)")
        db.execSQL(
            "CREATE TABLE kanji (dict INTEGER NOT NULL, character TEXT NOT NULL, onyomi TEXT, kunyomi TEXT, " +
                "tags TEXT, meanings TEXT, stats TEXT)",
        )
        db.execSQL("CREATE INDEX kanji_character ON kanji (character)")
        db.execSQL("CREATE TABLE tag (dict INTEGER NOT NULL, name TEXT NOT NULL, category TEXT, ord INTEGER, notes TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** Removes a dictionary and everything in it. */
    fun delete(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (table in listOf("term", "term_meta", "kanji", "tag")) db.delete(table, "dict = ?", arrayOf(id.toString()))
            db.delete("dictionary", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Writes a dictionary's entries as they're read, committing every few thousand. */
    inner class Writer(private val dict: Long) : DictionarySink {
        private val db = writableDatabase
        private val term: SQLiteStatement = db.compileStatement(
            "INSERT INTO term (dict, expression, reading, def_tags, rules, score, glossary, sequence, term_tags) VALUES (?,?,?,?,?,?,?,?,?)",
        )
        private val meta: SQLiteStatement = db.compileStatement("INSERT INTO term_meta (dict, expression, mode, data) VALUES (?,?,?,?)")
        private val kanji: SQLiteStatement = db.compileStatement(
            "INSERT INTO kanji (dict, character, onyomi, kunyomi, tags, meanings, stats) VALUES (?,?,?,?,?,?,?)",
        )
        private val tag: SQLiteStatement = db.compileStatement("INSERT INTO tag (dict, name, category, ord, notes) VALUES (?,?,?,?,?)")
        private var pending = 0
        var entries = 0
            private set

        init {
            db.beginTransaction()
        }

        override fun term(
            expression: String, reading: String, definitionTags: String, rules: String,
            score: Int, glossary: String, sequence: Long, termTags: String,
        ) {
            term.clearBindings()
            term.bindLong(1, dict)
            term.bindString(2, expression)
            term.bindString(3, reading)
            term.bindString(4, definitionTags)
            term.bindString(5, rules)
            term.bindLong(6, score.toLong())
            term.bindBlob(7, deflate(glossary))
            term.bindLong(8, sequence)
            term.bindString(9, termTags)
            term.executeInsert()
            step()
        }

        override fun meta(expression: String, mode: String, data: String) {
            meta.clearBindings()
            meta.bindLong(1, dict)
            meta.bindString(2, expression)
            meta.bindString(3, mode)
            meta.bindString(4, data)
            meta.executeInsert()
            step()
        }

        override fun kanji(character: String, onyomi: String, kunyomi: String, tags: String, meanings: String, stats: String) {
            kanji.clearBindings()
            kanji.bindLong(1, dict)
            kanji.bindString(2, character)
            kanji.bindString(3, onyomi)
            kanji.bindString(4, kunyomi)
            kanji.bindString(5, tags)
            kanji.bindString(6, meanings)
            kanji.bindString(7, stats)
            kanji.executeInsert()
            step()
        }

        override fun tag(name: String, category: String, order: Int, notes: String) {
            tag.clearBindings()
            tag.bindLong(1, dict)
            tag.bindString(2, name)
            tag.bindString(3, category)
            tag.bindLong(4, order.toLong())
            tag.bindString(5, notes)
            tag.executeInsert()
        }

        private fun step() {
            entries++
            if (++pending >= BATCH) {
                db.setTransactionSuccessful()
                db.endTransaction()
                db.beginTransaction()
                pending = 0
            }
        }

        /** Commits the rest; [success] false rolls back the last batch (the caller deletes the rest). */
        fun finish(success: Boolean) {
            if (success) db.setTransactionSuccessful()
            db.endTransaction()
        }
    }

    fun insertDictionary(values: ContentValues): Long = writableDatabase.insertOrThrow("dictionary", null, values)

    fun update(id: Long, values: ContentValues) {
        writableDatabase.update("dictionary", values, "id = ?", arrayOf(id.toString()))
    }

    companion object {
        private const val BATCH = 5000

        fun deflate(text: String): ByteArray {
            val deflater = Deflater(Deflater.BEST_SPEED)
            deflater.setInput(text.toByteArray(Charsets.UTF_8))
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            deflater.end()
            return out.toByteArray()
        }

        fun inflate(bytes: ByteArray): String {
            val inflater = Inflater()
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
            }
            inflater.end()
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
