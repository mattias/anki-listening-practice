package dev.lovkvist.listening.data.stats

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.lovkvist.listening.data.local.ListeningStatDao
import kotlinx.coroutines.runBlocking

@EntryPoint
@InstallIn(SingletonComponent::class)
interface StatisticsEntryPoint {
    fun statDao(): ListeningStatDao
    fun recorder(): StatsRecorder
}

/**
 * Lets another app read the statistics, read-only: the Immersion Dashboard's Android app fetches them from here and
 * uploads them itself. The data is the same as in the exported file, one row per day, language and kind of source.
 *
 * `content://dev.lovkvist.listening.statistics/entries[?since=YYYY-MM-DD]`, columns [StatsExport.COLUMNS]. A reader needs the
 * permission [PERMISSION], which the user allows at run time (it is declared as dangerous so that it can be asked for).
 */
class StatisticsProvider : ContentProvider() {
    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply { addURI(AUTHORITY, "entries", ENTRIES) }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (matcher.match(uri) != ENTRIES) throw IllegalArgumentException("Unknown URI: $uri")
        val entry = EntryPointAccessors.fromApplication(context!!.applicationContext, StatisticsEntryPoint::class.java)
        val rows = runBlocking {
            // What is counted but not yet written (the last seconds of listening) is included.
            entry.recorder().flush()
            entry.statDao().all()
        }
        val cursor = MatrixCursor(StatsExport.COLUMNS)
        for (row in StatsExport.rows(rows, uri.getQueryParameter("since"))) cursor.addRow(row)
        return cursor
    }

    override fun getType(uri: Uri) = if (matcher.match(uri) == ENTRIES) "vnd.android.cursor.dir/vnd.$AUTHORITY.entry" else null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")

    companion object {
        const val AUTHORITY = "dev.lovkvist.listening.statistics"
        const val PERMISSION = "dev.lovkvist.listening.permission.READ_STATISTICS"
        private const val ENTRIES = 1
    }
}
