package dev.lovkvist.listening

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dev.lovkvist.listening.playback.PlaybackService
import dev.lovkvist.listening.playback.PracticeController
import dev.lovkvist.listening.ui.PracticeScreen
import dev.lovkvist.listening.ui.SettingsScreen
import dev.lovkvist.listening.ui.SettingsViewModel
import dev.lovkvist.listening.ui.UpdateDialog
import dev.lovkvist.listening.ui.UpdateViewModel
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var practice: PracticeController

    /** Keeps the playback service and its media session up while the app is visible. */
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val context = LocalContext.current
            val colors = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                val updateVm: UpdateViewModel = hiltViewModel()
                LaunchedEffect(Unit) { updateVm.checkOnStart() }
                UpdateDialog(updateVm)
                AskForNotificationsOnFirstPlay()
                var inSettings by rememberSaveable { mutableStateOf(false) }

                if (inSettings) {
                    val back = {
                        inSettings = false
                        practice.retryIfStuck()
                    }
                    BackHandler(onBack = back)
                    val settingsVm: SettingsViewModel = hiltViewModel()
                    SettingsScreen(settingsVm, updateVm, onBack = back)
                } else {
                    PracticeScreen(practice, onOpenSettings = {
                        practice.player.pause()
                        inSettings = true
                    })
                }
            }
        }
    }

    /**
     * Android 13+ hides the media notification (and its play/pause, skip and Crystal Clear
     * buttons) unless notifications are allowed, so ask the first time audio plays. If the
     * answer is no, Android stops showing the question after the second time.
     */
    @Composable
    private fun AskForNotificationsOnFirstPlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val playing by practice.player.isPlaying.collectAsStateWithLifecycle()
        var asked by rememberSaveable { mutableStateOf(false) }
        val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(playing) {
            val granted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (playing && !asked && !granted) {
                asked = true
                request.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
            .buildAsync()
    }

    override fun onStop() {
        controller?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }
}
