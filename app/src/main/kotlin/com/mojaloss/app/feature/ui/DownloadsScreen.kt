package com.mojaloss.app.feature.ui

import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mojaloss.core.model.*
import com.mojaloss.core.ui.*
import com.mojaloss.core.ui.motion.LocalMotionPolicy
import com.mojaloss.core.ui.motion.motionPressFeedback
import kotlin.math.min

/** Downloads remain usable without a network; the engine owns transfer and account admission. */
@Composable
fun DownloadsScreen(
    library: DownloadLibrary, onClose: () -> Unit, onPlay: (DownloadItem) -> Unit,
    onPause: (DownloadItem) -> Unit, onResume: (DownloadItem) -> Unit,
    onRetry: (DownloadItem) -> Unit, onRemove: (DownloadItem) -> Unit,
    onSettings: (DownloadSettings) -> Unit, onRetryLibrary: (() -> Unit)? = null,
) {
    val motion = LocalMotionPolicy.current
    val owned = library.items.filter { library.ownerKey != null && it.ownerKey == library.ownerKey }
    var settingsOpen by remember(library.ownerKey) { mutableStateOf(false) }
    var pendingRemoval by remember(library.ownerKey) { mutableStateOf<DownloadItem?>(null) }
    var clearAll by remember(library.ownerKey) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("downloads-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.ArrowBack, "Back to Home") }
            Text("Downloads", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { settingsOpen = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Tune, "Download settings") }
        }
        LazyColumn(Modifier.fillMaxSize().testTag("downloads-list"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "storage") { StorageSummary(library.usedBytes, library.settings.storageLimitBytes, library.freeBytes) }
            if (!library.message.isNullOrBlank()) item(key = "library-message") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(library.message.orEmpty(), Modifier.weight(1f), color = MojaLossColors.SecondaryInk)
                    onRetryLibrary?.let { retry -> TextButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") } }
                }
            }
            if (!library.initialized) items(2, key = { "download-placeholder-$it" }) {
                Row(Modifier.fillMaxWidth().height(108.dp).clip(RoundedCornerShape(16.dp)).background(MojaLossColors.SurfaceHigh).testTag("download-placeholder-$it")) {}
            } else if (owned.isEmpty()) item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.DownloadForOffline, null, Modifier.size(42.dp), tint = MojaLossColors.Forest)
                    Text("Your next watch, anywhere", style = MaterialTheme.typography.titleLarge)
                    Text("Download a movie or episode from its page. Finished downloads play here without internet.", color = MojaLossColors.SecondaryInk)
                }
            }
            items(owned, key = { it.id }) { item ->
                DownloadRow(item, { onPlay(item) }, { onPause(item) }, { onResume(item) }, { onRetry(item) }, { pendingRemoval = item },
                    Modifier.animateItem(fadeInSpec = if (motion.animationsEnabled) tween(motion.pageDurationMillis) else null,
                        placementSpec = null, fadeOutSpec = null))
            }
            if (owned.isNotEmpty()) item(key = "clear") {
                TextButton(onClick = { clearAll = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove all downloads", color = MojaLossColors.Error) }
            }
            item(key = "private-note") { Text("Saved on this device for this account.", style = MaterialTheme.typography.labelMedium, color = MojaLossColors.SecondaryInk) }
        }
    }
    if (settingsOpen) DownloadSettingsSheet(library.settings, onSettings) { settingsOpen = false }
    pendingRemoval?.let { item -> AlertDialog(
        onDismissRequest = { pendingRemoval = null }, title = { Text("Remove download?") },
        text = { Text("${item.title.title} will be removed from this device. You can download it again when available.") },
        confirmButton = { TextButton(onClick = { onRemove(item); pendingRemoval = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove", color = MojaLossColors.Error) } },
        dismissButton = { TextButton(onClick = { pendingRemoval = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep download") } },
    ) }
    if (clearAll) AlertDialog(onDismissRequest = { clearAll = false }, title = { Text("Remove all downloads?") },
        text = { Text("This removes ${owned.size} ${if (owned.size == 1) "download" else "downloads"} for this account from this device.") },
        confirmButton = { TextButton(onClick = { owned.forEach(onRemove); clearAll = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove all", color = MojaLossColors.Error) } },
        dismissButton = { TextButton(onClick = { clearAll = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep downloads") } })
}

@Composable
private fun StorageSummary(usedBytes: Long, quota: Long, freeBytes: Long?) {
    Column(Modifier.fillMaxWidth().testTag("download-storage"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${downloadSize(usedBytes)} used", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text("${downloadSize(quota)} limit", style = MaterialTheme.typography.labelMedium, color = MojaLossColors.SecondaryInk)
        }
        LinearProgressIndicator(progress = { if (quota > 0) (usedBytes.toFloat() / quota).coerceIn(0f, 1f) else 0f },
            Modifier.fillMaxWidth().height(3.dp), color = MojaLossColors.Forest, trackColor = MojaLossColors.SurfaceHigh)
        freeBytes?.takeIf { it >= 0 }?.let { Text("${downloadSize(it)} free on device", style = MaterialTheme.typography.labelMedium, color = MojaLossColors.SecondaryInk) }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, onPlay: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val fullWidthStatus = LocalDensity.current.fontScale >= 1.3f
    val interactions = remember { MutableInteractionSource() }
    val motion = LocalMotionPolicy.current
    GlassSurface(modifier.fillMaxWidth().testTag("download-row-${item.id}"), 16.dp) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // The transfer index deliberately contains no remote artwork/authorization.
                // Do not request poster URLs from an offline page or leak headers to Coil.
                val label = item.title.title
                Row(Modifier.weight(1f).motionPressFeedback(interactions, motion).clip(RoundedCornerShape(10.dp))
                    .then(if (item.ready) Modifier.clickable(interactionSource = interactions, indication = ripple(), role = Role.Button, onClickLabel = "Play downloaded $label", onClick = onPlay) else Modifier)
                    .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 64.dp, height = 84.dp).clip(RoundedCornerShape(10.dp)).background(MojaLossColors.SurfaceHigh), contentAlignment = Alignment.Center) {
                        Icon(if (item.ready) Icons.Outlined.PlayArrow else Icons.Outlined.Movie, null, Modifier.size(28.dp), tint = MojaLossColors.Forest)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val metadata = listOfNotNull(item.title.year?.toString(), item.title.seasonNumber?.let { s -> item.title.episodeNumber?.let { "S$s · E$it" } })
                        if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MojaLossColors.SecondaryInk)
                        if (!fullWidthStatus) DownloadStateLabel(item)
                        if (item.ready && item.offlineResumeMs > 0) Text("Resume", style = MaterialTheme.typography.labelMedium, color = MojaLossColors.Forest)
                    }
                }
                // Actions remain separate 48dp touch targets at every font scale.
                Column {
                    when (item.status) {
                        DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.WAITING_FOR_WIFI -> DownloadAction(Icons.Outlined.Pause, "Pause $label", onPause)
                        DownloadStatus.PAUSED -> DownloadAction(Icons.Outlined.PlayArrow, "Resume download $label", onResume)
                        DownloadStatus.ERROR -> DownloadAction(Icons.Outlined.Refresh, "Retry download $label", onRetry)
                        else -> Unit
                    }
                    DownloadAction(Icons.Outlined.DeleteOutline, "Remove download $label", onRemove, enabled = item.status != DownloadStatus.REMOVING)
                }
            }
            // At large text sizes the poster and independent controls must not squeeze
            // transfer state into broken words. Keep the normal-size row compact.
            if (fullWidthStatus) DownloadStateLabel(item)
            if (item.status == DownloadStatus.DOWNLOADING) {
                val progress = item.progress
                if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp), color = MojaLossColors.Forest, trackColor = MojaLossColors.SurfaceHigh)
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp), color = MojaLossColors.Forest, trackColor = MojaLossColors.SurfaceHigh)
            }
            item.message?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MojaLossColors.SecondaryInk) }
        }
    }
}

@Composable
private fun DownloadStateLabel(item: DownloadItem) {
    Text(downloadStatus(item), style = MaterialTheme.typography.labelMedium,
        color = if (item.status == DownloadStatus.ERROR) MojaLossColors.Error else MojaLossColors.SecondaryInk)
}

@Composable
private fun DownloadAction(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) { Icon(icon, label) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadConfirmSheet(
    source: DownloadSource, settings: DownloadSettings, usedBytes: Long, freeBytes: Long?,
    onSettings: (DownloadSettings) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit,
) {
    val expected = source.expectedBytes?.takeIf { it > 0 }
    val quotaRemaining = (settings.storageLimitBytes - usedBytes).coerceAtLeast(0)
    val available = freeBytes?.takeIf { it >= 0 }?.let { min(it, quotaRemaining) } ?: quotaRemaining
    val insufficient = expected != null && expected > available
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MojaLossColors.Porcelain) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("download-confirm"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Watch offline", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(source.title.title, style = MaterialTheme.typography.titleMedium)
            Text("Original file · ${expected?.let(::downloadSize) ?: "Size will be shown when available"}", color = MojaLossColors.SecondaryInk)
            Text("Downloads stay on this device. Audio and subtitles included in the original file are kept.", style = MaterialTheme.typography.bodyMedium, color = MojaLossColors.SecondaryInk)
            DownloadWifiRow(settings.wifiOnly) { onSettings(settings.copy(wifiOnly = it)) }
            StorageSummary(usedBytes, settings.storageLimitBytes, freeBytes)
            if (insufficient) Text("Not enough room within your download limit or device storage. Remove a download or raise the limit in Download settings.", color = MojaLossColors.Error)
            ForestButton(if (settings.wifiOnly) "Download on Wi-Fi" else "Download", onConfirm, Modifier.fillMaxWidth(), enabled = !insufficient, icon = Icons.Outlined.Download)
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Not now") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSettingsSheet(settings: DownloadSettings, onSettings: (DownloadSettings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MojaLossColors.Porcelain) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("download-settings"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Download settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            DownloadWifiRow(settings.wifiOnly) { onSettings(settings.copy(wifiOnly = it)) }
            Text("Storage limit", style = MaterialTheme.typography.titleMedium)
            Text("Your limit for downloads on this device. Lowering it does not remove saved videos.", style = MaterialTheme.typography.bodyMedium, color = MojaLossColors.SecondaryInk)
            listOf(1L, 2L, 5L, 10L).forEach { gib ->
                val bytes = gib * 1024 * 1024 * 1024
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = settings.storageLimitBytes == bytes, role = Role.RadioButton,
                    onClick = { onSettings(settings.copy(storageLimitBytes = bytes)) }).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = settings.storageLimitBytes == bytes, onClick = null)
                    Text("$gib GB", Modifier.padding(start = 12.dp))
                }
            }
            Text("Original files only. File size and playback compatibility depend on the source video.", style = MaterialTheme.typography.labelMedium, color = MojaLossColors.SecondaryInk)
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Done") }
        }
    }
}

@Composable
private fun DownloadWifiRow(wifiOnly: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = wifiOnly, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Wi-Fi only", style = MaterialTheme.typography.titleMedium)
            Text("Wait for Wi-Fi before downloading", style = MaterialTheme.typography.labelMedium, color = MojaLossColors.SecondaryInk)
        }
        Switch(checked = wifiOnly, onCheckedChange = null)
    }
}

/** Shared artwork action. A fresh download is admitted by the repository, not isPlayable. */
@Composable
internal fun DownloadControl(title: MediaTitle, item: DownloadItem?, preparing: Boolean = false,
    onClick: (MediaTitle) -> Unit, modifier: Modifier = Modifier, onArtwork: Boolean = false) {
    val label = when {
        preparing -> "Preparing download…"
        item == null -> "Download"
        item.ready -> "Downloaded"
        else -> downloadStatus(item)
    }
    val icon = when {
        item?.ready == true -> Icons.Outlined.DownloadDone
        item?.status == DownloadStatus.PAUSED -> Icons.Outlined.Pause
        item?.status == DownloadStatus.ERROR -> Icons.Outlined.Refresh
        else -> Icons.Outlined.Download
    }
    val foreground = if (onArtwork) Color.White else MojaLossColors.Forest
    OutlinedIconButton(onClick = { onClick(title) }, enabled = !preparing && item?.status != DownloadStatus.REMOVING,
        modifier = modifier.size(48.dp).semantics { contentDescription = "$label ${title.title}"; stateDescription = label },
        colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = foreground, disabledContentColor = foreground.copy(alpha = .6f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, foreground.copy(alpha = .65f)), shape = RoundedCornerShape(14.dp),
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (preparing || item?.status == DownloadStatus.DOWNLOADING && item.progress == null) {
                CircularProgressIndicator(Modifier.fillMaxSize(), color = foreground, strokeWidth = 2.dp)
            } else if (item?.status == DownloadStatus.DOWNLOADING && item.progress != null) {
                CircularProgressIndicator(progress = { item.progress ?: 0f }, modifier = Modifier.fillMaxSize(),
                    color = foreground, strokeWidth = 2.dp, trackColor = foreground.copy(alpha = .22f))
            }
            Icon(icon, null, Modifier.size(20.dp))
        }
    }
}

private fun downloadStatus(item: DownloadItem): String = when (item.status) {
    DownloadStatus.QUEUED -> "Queued"
    DownloadStatus.DOWNLOADING -> item.progress?.let { "Downloading ${(it * 100).toInt()}%" } ?: "Downloading · ${downloadSize(item.bytesDownloaded)}"
    DownloadStatus.PAUSED -> "Paused · ${downloadSize(item.bytesDownloaded)}"
    DownloadStatus.WAITING_FOR_WIFI -> "Waiting for Wi-Fi"
    DownloadStatus.READY -> "Downloaded · ${downloadSize(item.bytesDownloaded)}"
    DownloadStatus.ERROR -> "Download interrupted"
    DownloadStatus.REMOVING -> "Removing…"
}

internal fun downloadSize(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0)
    val gib = 1024L * 1024 * 1024
    return when {
        safe >= gib -> String.format(java.util.Locale.ROOT, "%.1f GB", safe.toDouble() / gib)
        safe >= 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.0f MB", safe.toDouble() / (1024 * 1024))
        safe >= 1024 -> String.format(java.util.Locale.ROOT, "%.0f KB", safe.toDouble() / 1024)
        else -> "$safe B"
    }
}
