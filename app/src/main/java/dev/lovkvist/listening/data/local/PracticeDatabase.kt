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

@Database(entities = [SentenceState::class], version = 2, exportSchema = false)
abstract class PracticeDatabase : RoomDatabase() {
    abstract fun sentenceStateDao(): SentenceStateDao

    companion object {
        /** 0.4.0: blacklisted sentences keep their text and source so they can be listed. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sentence_state ADD COLUMN text TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sentence_state ADD COLUMN source TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
