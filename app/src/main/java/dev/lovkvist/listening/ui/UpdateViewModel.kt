package dev.lovkvist.listening.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lovkvist.listening.data.settings.SettingsRepository
import dev.lovkvist.listening.data.update.AppUpdater
import dev.lovkvist.listening.data.update.Release
import dev.lovkvist.listening.data.update.UpdateState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updater: AppUpdater,
    private val settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<UpdateState> = updater.state

    val currentVersion: String get() = updater.currentVersion

    val checkOnStart: StateFlow<Boolean> = settings.settings.map { it.checkUpdates }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private var startChecked = false

    /** Once per app run, if enabled in Settings. */
    fun checkOnStart() {
        if (startChecked) return
        startChecked = true
        viewModelScope.launch { if (settings.current().checkUpdates) updater.check(quiet = true) }
    }

    fun check() = viewModelScope.launch { updater.check() }

    fun setCheckOnStart(enabled: Boolean) = viewModelScope.launch { settings.setCheckUpdates(enabled) }

    fun dismiss() = updater.dismiss()

    fun canInstall(): Boolean = updater.canInstall()

    fun installPermissionIntent(): Intent = updater.installPermissionIntent()

    /** Downloads the update and hands Android's installer intent to [install]. */
    fun download(release: Release, install: (Intent) -> Unit) = viewModelScope.launch {
        updater.download(release)?.let(install)
    }
}
