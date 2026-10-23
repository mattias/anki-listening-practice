package dev.lovkvist.listening.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

/** Practice state per Anki note. Kept in our own DB; the Anki collection is never written. */
@Entity(tableName = "sentence_state")
data class SentenceState(
    @PrimaryKey val noteId: Long,
    /** Epoch millis until which the sentence is hidden (set by "Crystal Clear"). */
    val cooldownUntil: Long = 0,
    val blacklisted: Boolean = false,
)

@Dao
interface SentenceStateDao {
    @Query("SELECT noteId FROM sentence_state WHERE blacklisted = 1 OR cooldownUntil > :now")
    suspend fun excludedIds(now: Long): List<Long>

    @Query("SELECT * FROM sentence_state WHERE noteId = :noteId")
    suspend fun get(noteId: Long): SentenceState?

    @Upsert
    suspend fun upsert(state: SentenceState)

    @Query("UPDATE sentence_state SET blacklisted = 0")
    suspend fun clearBlacklist()

    @Query("SELECT COUNT(*) FROM sentence_state WHERE blacklisted = 1")
    suspend fun blacklistCount(): Int

    @Query("UPDATE sentence_state SET cooldownUntil = 0")
    suspend fun clearCooldowns()

    @Query("SELECT COUNT(*) FROM sentence_state WHERE cooldownUntil > :now")
    suspend fun cooldownCount(now: Long): Int
}

@Database(entities = [SentenceState::class], version = 1, exportSchema = false)
abstract class PracticeDatabase : RoomDatabase() {
    abstract fun sentenceStateDao(): SentenceStateDao
}
