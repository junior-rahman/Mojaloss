package com.mojaloss.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mojaloss.core.ui.ForestButton
import com.mojaloss.core.ui.GlassSurface
import com.mojaloss.core.ui.MojaLossColors
import com.mojaloss.core.ui.motion.LocalMotionPolicy
import com.mojaloss.core.ui.motion.motionArrival
import java.util.Locale

/** The shell supplies navigation and installer effects; this screen renders truthful transfer state. */
@Composable
fun AppUpdateScreen(
    state: AppUpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onBack: () -> Unit,
) {
    val motion = LocalMotionPolicy.current
    Column(Modifier.fillMaxSize().testTag("app-update-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.ArrowBack, "Back") }
            Text("App updates", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("MojaLoss ${state.currentVersion}", style = MaterialTheme.typography.labelLarge, color = MojaLossColors.SecondaryInk)
            // Progress byte updates retain this layer; only a new coarse phase reveals it.
            GlassSurface(Modifier.fillMaxWidth().motionArrival(state.phase, motion), cornerRadius = 20.dp) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(if (state.phase == AppUpdatePhase.UP_TO_DATE) Icons.Outlined.CheckCircleOutline else Icons.Outlined.SystemUpdate,
                        contentDescription = null, modifier = Modifier.size(36.dp), tint = MojaLossColors.Forest)
                    Text(updateHeading(state.phase), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    state.release?.let { release ->
                        Text("Version ${release.versionName} · ${updateFileSize(release.size)}", color = MojaLossColors.SecondaryInk)
                    }
                    when (state.phase) {
                        AppUpdatePhase.CHECKING -> {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = MojaLossColors.Forest)
                            Text("Checking the latest release…", color = MojaLossColors.SecondaryInk)
                            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                        }
                        AppUpdatePhase.DOWNLOADING -> {
                            LinearProgressIndicator(progress = {
                                if (state.totalBytes > 0) (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) else 0f
                            }, modifier = Modifier.fillMaxWidth(), color = MojaLossColors.Forest, trackColor = MojaLossColors.SurfaceHigh)
                            Text("${updateFileSize(state.downloadedBytes)} of ${updateFileSize(state.totalBytes)}", style = MaterialTheme.typography.labelLarge)
                            OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel download") }
                        }
                        AppUpdatePhase.VERIFYING -> {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = MojaLossColors.Forest)
                            Text("Checking the app file and its signature…", color = MojaLossColors.SecondaryInk)
                        }
                        AppUpdatePhase.AVAILABLE -> ForestButton("Download update", onDownload, Modifier.fillMaxWidth())
                        AppUpdatePhase.READY, AppUpdatePhase.INSTALL_PERMISSION -> ForestButton("Install update", onInstall, Modifier.fillMaxWidth())
                        AppUpdatePhase.INSTALLER_OPENED -> ForestButton("Install again", onInstall, Modifier.fillMaxWidth())
                        AppUpdatePhase.ERROR -> {
                            if (state.release != null) ForestButton("Try download again", onDownload, Modifier.fillMaxWidth())
                            else ForestButton("Try again", onCheck, Modifier.fillMaxWidth())
                        }
                        AppUpdatePhase.IDLE, AppUpdatePhase.UP_TO_DATE -> ForestButton("Check for updates", onCheck, Modifier.fillMaxWidth())
                    }
                    state.message?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = if (state.phase == AppUpdatePhase.ERROR) MojaLossColors.Error else MojaLossColors.SecondaryInk,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("app-update-message"))
                    }
                    if (state.phase in setOf(AppUpdatePhase.AVAILABLE, AppUpdatePhase.ERROR, AppUpdatePhase.READY, AppUpdatePhase.INSTALLER_OPENED)) {
                        if (state.release != null) TextButton(onClick = onCheck, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check again") }
                    }
                }
            }
            state.release?.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                Text("What’s new", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(notes, color = MojaLossColors.SecondaryInk)
            }
            Text("Updates are downloaded only when you ask. Android will ask you to confirm installation.",
                style = MaterialTheme.typography.labelMedium, color = MojaLossColors.SecondaryInk)
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun updateHeading(phase: AppUpdatePhase): String = when (phase) {
    AppUpdatePhase.IDLE -> "Keep your next watch smooth"
    AppUpdatePhase.CHECKING -> "Looking for an update"
    AppUpdatePhase.UP_TO_DATE -> "You’re up to date"
    AppUpdatePhase.AVAILABLE -> "A new version is ready"
    AppUpdatePhase.DOWNLOADING -> "Downloading your update"
    AppUpdatePhase.VERIFYING -> "Verifying your update"
    AppUpdatePhase.READY -> "Ready to install"
    AppUpdatePhase.INSTALL_PERMISSION -> "One Android permission"
    AppUpdatePhase.INSTALLER_OPENED -> "Continue in Android"
    AppUpdatePhase.ERROR -> "Let’s try that again"
}

private fun updateFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
