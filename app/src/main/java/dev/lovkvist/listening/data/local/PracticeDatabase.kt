package dev.lovkvist.listening.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Practice state per sentence. Kept in our own DB; the Anki collection is never written. */
@Entity(tableName = "sentence_state")
data class SentenceState(
    // Named before videos existed; holds any sentence id (negative for video lines).
    @PrimaryKey val noteId: Long,
    /** Epoch millis until which the sentence is hidden (set by "Crystal Clear"). */
    val cooldownUntil: Long = 0,
    val blacklisted: Boolean = false,
    /** The sentence as shown when it was blacklisted, so the blacklist can be listed. */
    @ColumnInfo(defaultValue = "") val text: String = "",
    /** Where it came from: "Anki cards" or the video's name. */
    @ColumnInfo(defaultValue = "") val source: String = "",
)

@Dao
interface SentenceStateDao {
    @Query("SELECT noteId FROM sentence_state WHERE blacklisted = 1 OR cooldownUntil > :now")
    suspend fun excludedIds(now: Long): List<Long>

    @Query("SELECT * FROM sentence_state WHERE noteId = :noteId")
    suspend fun get(noteId: Long): SentenceState?

    @Upsert
    suspend fun upsert(state: SentenceState)

    @Query("DELETE FROM sentence_state WHERE noteId = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM sentence_state WHERE blacklisted = 1 ORDER BY source, text")
    suspend fun blacklisted(): List<SentenceState>

    @Query("UPDATE sentence_state SET blacklisted = 0")
    suspend fun clearBlacklist()

    @Query("SELECT COUNT(*) FROM sentence_state WHERE blacklisted = 1")
    suspend fun blacklistCount(): Int

    @Query("UPDATE sentence_state SET cooldownUntil = 0")
    suspend fun clearCooldowns()

    @Query("SELECT COUNT(*) FROM sentence_state WHERE cooldownUntil > :now")
    suspend fun cooldownCount(now: Long): Int
}

/** Sentences practiced per day, for the stats on the practice screen. */
@Entity(tableName = "practice_day")
data class PracticeDay(
    /** ISO date in local time, e.g. "2026-10-24". */
    @PrimaryKey val day: String,
    /** Sentences finished: Crystal Clear, skipped or blacklisted. */
    @ColumnInfo(defaultValue = "0") val heard: Int = 0,
    /** Of those, marked Crystal Clear. */
    @ColumnInfo(defaultValue = "0") val clear: Int = 0,
)

@Dao
interface PracticeDayDao {
    @Query("INSERT OR IGNORE INTO practice_day (day, heard, clear) VALUES (:day, 0, 0)")
    suspend fun ensure(day: String)

    @Query("UPDATE practice_day SET heard = heard + 1, clear = clear + :clear WHERE day = :day")
    suspend fun add(day: String, clear: Int)

    @Query("UPDATE practice_day SET heard = MAX(heard - 1, 0), clear = MAX(clear - :clear, 0) WHERE day = :day")
    suspend fun subtract(day: String, clear: Int)

    /** Newest first; enough days for any realistic streak. */
    @Query("SELECT * FROM practice_day WHERE heard > 0 ORDER BY day DESC LIMIT 1000")
    fun recent(): Flow<List<PracticeDay>>
}

/**
 * A word you marked yourself from a sentence: known (counts as known and is exported with your
 * known words) or ignored (no longer counts as unknown, e.g. names, but isn't exported).
 */
@Entity(tableName = "word_status", primaryKeys = ["language", "word"])
data class WordStatus(
    /** [dev.lovkvist.listening.text.Language.code]. */
    val language: String,
    /** The word as it's matched: normalized, and for Japanese the dictionary form. */
    val word: String,
    val ignored: Boolean,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface WordStatusDao {
    @Query("SELECT * FROM word_status WHERE language = :language ORDER BY updatedAt DESC")
    fun observe(language: String): Flow<List<WordStatus>>

    @Query("SELECT * FROM word_status WHERE language = :language")
    suspend fun all(language: String): List<WordStatus>

    @Upsert
    suspend fun upsert(status: WordStatus)

    @Query("DELETE FROM word_status WHERE language = :language AND word = :word")
    suspend fun delete(language: String, word: String)
}

@Database(entities = [SentenceState::class, PracticeDay::class, WordStatus::class], version = 4, exportSchema = false)
abstract class PracticeDatabase : RoomDatabase() {
    abstract fun sentenceStateDao(): SentenceStateDao
    abstract fun practiceDayDao(): PracticeDayDao
    abstract fun wordStatusDao(): WordStatusDao

    companion object {
        /** 0.4.0: blacklisted sentences keep their text and source so they can be listed. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sentence_state ADD COLUMN text TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sentence_state ADD COLUMN source TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 0.7.0: words marked known or ignored in the app. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS word_status (language TEXT NOT NULL, word TEXT NOT NULL, " +
                        "ignored INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(language, word))",
                )
            }
        }

        /** 0.6.0: daily practice counts. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS practice_day " +
                        "(day TEXT NOT NULL PRIMARY KEY, heard INTEGER NOT NULL DEFAULT 0, clear INTEGER NOT NULL DEFAULT 0)",
                )
            }
        }
    }
}
