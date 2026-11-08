package dev.lovkvist.listening.data.stats

import dev.lovkvist.listening.data.local.ListeningStatDao
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Counts what is practiced: time is added while the audio plays and written to the database every so often (and when
 * playback stops), so a kill loses seconds at most; sentences are written at once.
 */
@Singleton
class StatsRecorder @Inject constructor(private val dao: ListeningStatDao) {
    private data class Key(val day: String, val language: String, val kind: String)

    private class Pending {
        var listenMs = 0L
        var activeMs = 0L
        var plays = 0
    }

    private val pending = HashMap<Key, Pending>()

    /** Adds time to today: [listenMs] of it while a sentence's audio played, [activeMs] while playback went on at all. */
    fun addTime(language: String, kind: String, listenMs: Long, activeMs: Long) {
        synchronized(pending) {
            val p = pending.getOrPut(Key(LocalDate.now().toString(), language, kind)) { Pending() }
            p.listenMs += listenMs
            p.activeMs += activeMs
        }
    }

    /** One more play of a sentence (a repeat counts). */
    fun addPlay(language: String, kind: String) {
        synchronized(pending) {
            pending.getOrPut(Key(LocalDate.now().toString(), language, kind)) { Pending() }.plays++
        }
    }

    /** Writes what has been counted so far. */
    suspend fun flush() {
        val batch = synchronized(pending) { pending.toMap().also { pending.clear() } }
        for ((key, p) in batch) {
            dao.ensure(key.day, key.language, key.kind)
            dao.add(key.day, key.language, key.kind, 0, p.plays, 0, p.listenMs, p.activeMs)
        }
    }

    /** Sentences finished today, [clear] of them marked Crystal Clear. */
    suspend fun sentences(language: String, kind: String, day: String, sentences: Int, clear: Int) {
        dao.ensure(day, language, kind)
        dao.add(day, language, kind, sentences, 0, clear, 0, 0)
    }

    /** Takes back what an undone step counted. */
    suspend fun takeBack(language: String, kind: String, day: String, sentences: Int, clear: Int) {
        dao.subtract(day, language, kind, sentences, clear)
    }
}
