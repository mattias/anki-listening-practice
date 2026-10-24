package dev.lovkvist.listening.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lovkvist.listening.BuildConfig
import dev.lovkvist.listening.R
import dev.lovkvist.listening.l10n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    /** [progress] is 0..1, or null while the size is unknown. */
    data class Downloading(val release: Release, val progress: Float?) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Checks GitHub for a newer release and installs its APK. Android shows its own install
 * prompt; the update keeps all data because it's signed with the same key.
 */
@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val strings: Strings,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String = BuildConfig.VERSION_NAME

    private val dir get() = File(context.cacheDir, "updates")

    /** Looks for a newer release. A [quiet] check (on start) only reports an available update. */
    suspend fun check(quiet: Boolean = false) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        val result = try {
            val release = withContext(Dispatchers.IO) { parseRelease(get(LATEST_URL)) }
            // Leftovers from an installed update are no longer needed.
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
            if (release != null && isNewer(release.version, currentVersion)) UpdateState.Available(release) else UpdateState.UpToDate
        } catch (e: Exception) {
            UpdateState.Failed(strings(R.string.update_check_failed, e.message ?: e.toString()))
        }
        _state.value = if (quiet && result !is UpdateState.Available) UpdateState.Idle else result
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    /** True if the user has let this app install APKs; otherwise [installPermissionIntent] asks. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Downloads [release]'s APK, checks its SHA-256 and returns the intent that installs it. */
    suspend fun download(release: Release): Intent? {
        _state.value = UpdateState.Downloading(release, null)
        return try {
            val apk = withContext(Dispatchers.IO) { fetchApk(release) }
            _state.value = UpdateState.Available(release)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(strings(R.string.update_download_failed, e.message ?: e.toString()))
            null
        }
    }

    private fun fetchApk(release: Release): File {
        dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, "update-${release.version}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        open(release.apkUrl).run {
            val total = contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        _state.value = UpdateState.Downloading(release, if (total > 0) done.toFloat() / total else null)
                    }
                }
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256 != null && !hash.equals(release.sha256, ignoreCase = true)) {
            file.delete()
            throw IOException(strings(R.string.update_damaged))
        }
        return file
    }

    private fun get(url: String): String = open(url).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "anki-listening-practice/$currentVersion")
        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            throw IOException("GitHub answered HTTP ${connection.responseCode}")
        }
        return connection
    }

    private companion object {
        const val LATEST_URL = "https://api.github.com/repos/mattias/anki-listening-practice/releases/latest"
    }
}
