package com.mojaloss.core.model

import java.security.MessageDigest

/** Stable saved-account ownership, without transient generation or credentials. */
fun downloadOwnerKey(scope: SessionScope): String = MessageDigest.getInstance("SHA-256")
    .digest("${scope.serverUrl.trimEnd('/')}\n${scope.accountId}".toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

data class DownloadSource(val title: MediaTitle, val url: String, val sessionScope: SessionScope,
    val expectedBytes: Long? = null, val mimeType: String? = null, val mediaSourceId: String? = null) {
    val ownerKey: String get() = downloadOwnerKey(sessionScope)
}
/** Ephemeral authorization, never serialized in a download/index/URL. */
data class DownloadAuthorization(val scope: SessionScope, val headers: Map<String, String>)
enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, WAITING_FOR_WIFI, READY, ERROR, REMOVING }
data class DownloadItem(val id: String, val ownerKey: String, val title: MediaTitle,
    val status: DownloadStatus, val bytesDownloaded: Long = 0, val totalBytes: Long? = null,
    val message: String? = null, val offlineResumeMs: Long = 0) {
    val ready: Boolean get() = status == DownloadStatus.READY
    val progress: Float? get() = totalBytes?.takeIf { it > 0 }?.let {
        (bytesDownloaded.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
}
data class DownloadSettings(val wifiOnly: Boolean = true, val storageLimitBytes: Long = 5L * 1024 * 1024 * 1024)
data class DownloadLibrary(val items: List<DownloadItem> = emptyList(),
    val settings: DownloadSettings = DownloadSettings(), val initialized: Boolean = false,
    val ownerKey: String? = null, val usedBytes: Long = 0, val freeBytes: Long? = null,
    val message: String? = null)
