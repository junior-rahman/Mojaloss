package com.mojaloss.core.downloads

import java.net.URI
import java.security.MessageDigest

/** Pure guards shared by enqueue, each transfer open and the offline playback boundary. */
internal object DownloadPolicy {
    const val QUOTA_BYTES = 5L * 1024 * 1024 * 1024
    const val MAX_QUOTA_BYTES = 10L * 1024 * 1024 * 1024
    const val DISK_RESERVE_BYTES = 256L * 1024 * 1024
    fun ownerKey(server: String, account: String): String = sha("${server.trimEnd('/')}\n$account")
    fun id(owner: String, item: String, source: String): String = sha("$owner\u0000$item\u0000$source")
    fun fingerprint(url: String, source: String, size: Long?): String = sha("$url\u0000$source\u0000${size ?: -1}")
    fun isSafeOriginal(server: String, source: String, item: String): Boolean = runCatching {
        val base = URI(server)
        val uri = URI(source)
        if (base.scheme != "https" || uri.scheme != "https" || uri.rawUserInfo != null ||
            uri.rawFragment != null || !base.host.equals(uri.host, true) || port(base) != port(uri)) return false
        val root = base.path.orEmpty().trimEnd('/')
        val expected = "$root/Items/$item/Download"
        if (uri.path != expected || uri.rawPath.contains("%", true)) return false
        // Only the chosen source identifier is permitted; tokens and redirect/transcode URLs never persist.
        uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).all {
            val pair = it.split('=', limit = 2)
            pair.size == 2 && pair[0].equals("mediaSourceId", true) && pair[1].isNotBlank()
        }
    }.getOrDefault(false)
    fun canWrite(cacheBytes: Long, usableBytes: Long, nextBytes: Long, quota: Long = QUOTA_BYTES): Boolean =
        nextBytes >= 0 && nextBytes <= quota && cacheBytes >= 0 && cacheBytes <= quota - nextBytes && usableBytes >= DISK_RESERVE_BYTES + nextBytes
    fun isProgressiveContentType(value: String?): Boolean {
        val type = value?.substringBefore(';')?.trim()?.lowercase() ?: return true
        return type.startsWith("video/") || type.startsWith("audio/") || type in setOf(
            "application/octet-stream", "binary/octet-stream", "application/mp4", "application/x-matroska", "application/x-download")
    }
    fun validator(etag: String?, modified: String?): String? =
        etag?.takeIf { it.isNotBlank() && !it.trimStart().startsWith("W/", true) }
            ?: modified?.takeIf(String::isNotBlank)
    fun sameValidator(stored: String?, etag: String?, modified: String?): Boolean =
        stored != null && stored == validator(etag, modified)
    private fun port(uri: URI) = if (uri.port == -1) 443 else uri.port
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
