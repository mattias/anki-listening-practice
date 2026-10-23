package dev.lovkvist.listening

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import android.os.Build
import dagger.hilt.android.AndroidEntryPoint
import dev.lovkvist.listening.ui.PracticeScreen
import dev.lovkvist.listening.ui.PracticeViewModel
import dev.lovkvist.listening.ui.SettingsScreen
import dev.lovkvist.listening.ui.SettingsViewModel
import dev.lovkvist.listening.ui.UpdateDialog
import dev.lovkvist.listening.ui.UpdateViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
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
                // Practice VM is created at activity scope so playback survives visiting Settings.
                val practiceVm: PracticeViewModel = hiltViewModel()
                val updateVm: UpdateViewModel = hiltViewModel()
                LaunchedEffect(Unit) { updateVm.checkOnStart() }
                UpdateDialog(updateVm)
                var inSettings by rememberSaveable { mutableStateOf(false) }

                if (inSettings) {
                    val back = {
                        inSettings = false
                        practiceVm.retryIfStuck()
                    }
                    BackHandler(onBack = back)
                    val settingsVm: SettingsViewModel = hiltViewModel()
                    SettingsScreen(settingsVm, updateVm, onBack = back)
                } else {
                    PracticeScreen(practiceVm, onOpenSettings = {
                        practiceVm.player.pause()
                        inSettings = true
                    })
                }
            }
        }
    }
}
