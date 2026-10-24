package dev.lovkvist.listening.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lovkvist.listening.R
import dev.lovkvist.listening.data.update.UpdateState

/** Shows an available update, its download progress, or the result of a manual check. */
@Composable
fun UpdateDialog(vm: UpdateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    when (val s = state) {
        UpdateState.Idle, UpdateState.Checking -> {}

        UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = vm::dismiss,
            confirmButton = { TextButton(onClick = vm::dismiss) { Text(stringResource(R.string.ok)) } },
            text = { Text(stringResource(R.string.latest_version, vm.currentVersion)) },
        )

        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = vm::dismiss,
            confirmButton = { TextButton(onClick = vm::dismiss) { Text(stringResource(R.string.ok)) } },
            text = { Text(s.message) },
        )

        is UpdateState.Available -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text(stringResource(R.string.version_available, s.release.version)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.you_have_version, vm.currentVersion) +
                            if (vm.canInstall()) "" else "\n\n" + stringResource(R.string.install_permission_note),
                    )
                    if (s.release.notes.isNotBlank()) {
                        Text(
                            s.release.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (vm.canInstall()) {
                        vm.download(s.release) { context.startActivity(it) }
                    } else {
                        context.startActivity(vm.installPermissionIntent())
                    }
                }) { Text(stringResource(R.string.update)) }
            },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text(stringResource(R.string.later)) } },
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(R.string.downloading_version, s.release.version)) },
            text = {
                val progress = s.progress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
        )
    }
}
