package dev.lovkvist.listening.data.sound

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

/** A sentence for sound training, as the service made it. */
data class SoundItem(
    val id: String,
    /** What is written and shown. */
    val text: String,
    /** What the voice was given: the sentence written the way it is said (これは → こりゃ). */
    val spoken: String,
    val translation: String,
    /** The phenomenon the sentence was written for ([dev.lovkvist.listening.text.Phenomenon] id). */
    val target: String,
    /** Every phenomenon found in it. */
    val phenomena: List<String>,
    /** Where in [text] the target is. */
    val matches: List<String>,
    val voice: String,
    val audioReady: Boolean = false,
    val createdAt: Long = 0,
)

/** A batch the service is making, or has made. */
data class SoundBatch(
    val id: String,
    /** queued, writing, speaking, done or failed. */
    val state: String,
    val error: String,
    val total: Int,
    val done: Int,
    val items: List<SoundItem>,
)

/** What the service says about itself and its parts. */
data class SoundStatus(val llmModel: String, val llmAvailable: Boolean, val voiceOk: Boolean)

/** A phenomenon as the service lists it. */
data class SoundPhenomenon(val id: String, val example: String, val title: String, val description: String)

/** The service could not be used; [message] says what to do about it. */
class SoundException(message: String) : IOException(message)

/**
 * Talks to the sound training service on the user's computer (the Anki add-on Irodori Audio): the
 * app asks for sentences about a sound phenomenon and gets sentences, translations and speech back.
 */
@Singleton
class SoundClient @Inject constructor() {
    suspend fun status(url: String, token: String): SoundStatus {
        // The address is checked first, without the token, so a wrong address is told apart from a wrong token.
        call(url, token, "GET", "/v1/sound/health", authorize = false)
        val json = JSONObject(call(url, token, "GET", "/v1/sound/status"))
        val llm = json.getJSONObject("llm")
        return SoundStatus(llm.optString("model"), llm.optBoolean("available"), json.getJSONObject("voice").optBoolean("ok"))
    }

    suspend fun phenomena(url: String, token: String): List<SoundPhenomenon> =
        parsePhenomena(call(url, token, "GET", "/v1/sound/phenomena"))

    /** Sends the known words; returns the id the service keeps them under. */
    suspend fun putVocabulary(url: String, token: String, words: List<String>): String {
        val body = JSONObject().put("words", JSONArray(words)).toString()
        return JSONObject(call(url, token, "PUT", "/v1/sound/vocabulary", body)).getString("id")
    }

    suspend fun createBatch(
        url: String,
        token: String,
        vocabularyId: String,
        phenomena: List<String>,
        count: Int,
        translationLanguage: String,
        maxUnknown: Int,
    ): String {
        val body = JSONObject()
            .put("vocabulary_id", vocabularyId)
            .put("phenomena", JSONArray(phenomena))
            .put("count", count)
            .put("translation_language", translationLanguage)
            .put("max_unknown", maxUnknown)
            .toString()
        return JSONObject(call(url, token, "POST", "/v1/sound/batches", body)).getString("id")
    }

    suspend fun batch(url: String, token: String, id: String): SoundBatch =
        parseBatch(call(url, token, "GET", "/v1/sound/batches/$id"))

    suspend fun downloadAudio(url: String, token: String, batchId: String, itemId: String, to: File) {
        withContext(Dispatchers.IO) {
            val connection = open(url, token, "GET", "/v1/sound/batches/$batchId/items/$itemId/audio")
            try {
                check(connection)
                connection.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } }
            } catch (e: IOException) {
                to.delete()
                throw explain(e, url)
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun deleteBatch(url: String, token: String, id: String) {
        runCatching { call(url, token, "DELETE", "/v1/sound/batches/$id") }
    }

    private suspend fun call(url: String, token: String, method: String, path: String, body: String? = null, authorize: Boolean = true): String =
        withContext(Dispatchers.IO) {
            val connection = open(url, token, method, path, authorize)
            try {
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                check(connection)
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: IOException) {
                throw explain(e, url)
            } finally {
                connection.disconnect()
            }
        }

    private fun open(url: String, token: String, method: String, path: String, authorize: Boolean = true): HttpURLConnection {
        val base = url.trim().trimEnd('/').let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 8_000
        // Starting a batch uploads the word list; the model may be busy loading.
        connection.readTimeout = 120_000
        if (authorize) connection.setRequestProperty("Authorization", "Bearer ${token.trim()}")
        return connection
    }

    private fun check(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code in 200..299) return
        val detail = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty().take(200)
        throw SoundException(
            when (code) {
                401 -> "WRONG_TOKEN"
                404 -> "NOT_FOUND:$detail"
                else -> "HTTP $code $detail".trim()
            },
        )
    }

    /** A connection problem as something the user can act on; the strings are codes the screen turns into text. */
    private fun explain(e: IOException, url: String): SoundException = when (e) {
        is SoundException -> e
        is SocketTimeoutException -> SoundException("UNREACHABLE:$url")
        is ConnectException, is NoRouteToHostException, is UnknownHostException -> SoundException("UNREACHABLE:$url")
        else -> SoundException(e.message ?: e.toString())
    }

    companion object {
        fun parsePhenomena(json: String): List<SoundPhenomenon> {
            val array = JSONArray(json)
            return (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                SoundPhenomenon(o.getString("id"), o.optString("example"), o.optString("title"), o.optString("description"))
            }
        }

        fun parseBatch(json: String): SoundBatch {
            val o = JSONObject(json)
            val items = o.optJSONArray("items") ?: JSONArray()
            return SoundBatch(
                id = o.getString("id"),
                state = o.optString("state"),
                error = o.optString("error"),
                total = o.optInt("total"),
                done = o.optInt("done"),
                items = (0 until items.length()).map { parseItem(items.getJSONObject(it)) },
            )
        }

        private fun strings(array: JSONArray?): List<String> = (0 until (array?.length() ?: 0)).map { array!!.getString(it) }

        fun parseItem(o: JSONObject): SoundItem = SoundItem(
            id = o.getString("id"),
            text = o.getString("text"),
            spoken = o.optString("spoken").ifBlank { o.getString("text") },
            translation = o.optString("translation"),
            target = o.optString("target"),
            phenomena = strings(o.optJSONArray("phenomena")),
            matches = strings(o.optJSONArray("matches")),
            voice = o.optString("voice"),
            audioReady = o.optBoolean("audio_ready"),
        )
    }
}
