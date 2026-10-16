package com.mojaloss.core.downloads

import android.app.Notification
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler

/** Foreground transfer notification is Android's background-work contract, not extra in-app clutter. */
@UnstableApi
class OfflineDownloadService : DownloadService(
    2016, 1_000, "offline_downloads", R.string.offline_download_channel,
    R.string.offline_download_channel_description,
) {
    private var usable = false
    private val notificationHelper by lazy { DownloadNotificationHelper(this, "offline_downloads") }
    override fun onCreate() {
        try { super.onCreate(); usable = true }
        catch (_: Exception) { usable = false; stopSelf() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!usable) { stopSelf(startId); return START_NOT_STICKY }
        return try { super.onStartCommand(intent, flags, startId) }
        catch (_: Exception) { stopSelf(startId); START_NOT_STICKY }
    }
    override fun onDestroy() { if (usable) runCatching { super.onDestroy() } }
    override fun getDownloadManager(): DownloadManager = runtime().managerForService()
    override fun getScheduler(): Scheduler = runtime().scheduler()
    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        notificationHelper.buildProgressNotification(
            this, R.drawable.ic_download_notification, null, "Saving for offline watching", downloads, notMetRequirements)
    override fun onTimeout(startId: Int, fgsType: Int) {
        // API 35 dataSync quota: persist a real stop reason before promptly stopping the service.
        runCatching { runtime().pauseForServiceTimeout() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }
    private fun runtime() = (application as DownloadRuntimeProvider).offlineDownloads
}
