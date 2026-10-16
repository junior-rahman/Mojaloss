package com.mojaloss.core.downloads

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.IOException

internal class TransferBlocked(message: String) : IOException(message)

/** The persisted request has no credentials. A current matching owner supplies headers at open. */
internal class GuardedDownloadDataSource(
    private val runtime: OfflineDownloads,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var delegate: OkHttpDataSource? = null
    private var row: StoredDownload? = null
    private var authorizedScope: com.mojaloss.core.model.SessionScope? = null
    private var openedCacheBytes = 0L
    private var readBytes = 0L
    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        delegate?.addTransferListener(transferListener)
    }
    override fun open(dataSpec: DataSpec): Long {
        val current = runtime.rowForTransfer(dataSpec.key, dataSpec.uri.toString())
            ?: throw TransferBlocked("Sign in to the account that started this download")
        runtime.checkTransferAllowed(current)
        val authorization = runBlocking { runtime.authorize(current.owner) }
            ?: throw TransferBlocked("Sign in to continue this download")
        if (!runtime.authorizationMatches(current, authorization)) throw TransferBlocked("The account has changed")
        authorizedScope = authorization.scope
        val headers = runtime.authorizationHeaders(authorization).toMutableMap()
        if (dataSpec.position > 0) {
            headers["If-Range"] = current.validator ?: throw TransferBlocked("Restart required to verify this original")
        }
        val source = OkHttpDataSource.Factory(CLIENT).setDefaultRequestProperties(headers).createDataSource()
        listeners.forEach(source::addTransferListener)
        delegate = source
        row = current
        openedCacheBytes = runtime.cachedBytes()
        readBytes = 0
        try {
            val length = source.open(dataSpec)
            runtime.checkTransferAllowed(current)
            if (source.responseCode !in 200..299 || (dataSpec.position > 0 && source.responseCode != 206)) {
                throw TransferBlocked("This original changed. Retry to start a verified copy")
            }
            val response = source.responseHeaders
            fun header(name: String) = response.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
            if (!DownloadPolicy.isProgressiveContentType(header("Content-Type")))
                throw TransferBlocked("The server did not return a playable original file")
            val etag = header("ETag")
            val modified = header("Last-Modified")
            if (dataSpec.position > 0 && !DownloadPolicy.sameValidator(current.validator, etag, modified)) {
                throw TransferBlocked("This original changed. Retry to start a verified copy")
            }
            val range = header("Content-Range")
            val total = range?.substringAfterLast('/')?.toLongOrNull()
                ?: length.takeIf { it != C.LENGTH_UNSET.toLong() && dataSpec.position == 0L }
            if (total != null && current.expectedBytes != null && total != current.expectedBytes) {
                throw TransferBlocked("This original changed. Download it again")
            }
            if (dataSpec.position > 0 && range?.startsWith("bytes ${dataSpec.position}-") != true) {
                throw TransferBlocked("The server cannot safely resume this copy")
            }
            runtime.rememberResponse(current.id, DownloadPolicy.validator(etag, modified), total)
            return length
        } catch (failure: Exception) {
            runCatching(source::close)
            throw failure
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val current = row ?: throw TransferBlocked("Download is not open")
        runtime.checkTransferAllowed(current)
        if (authorizedScope?.let(runtime::ownerMatches) != true) throw TransferBlocked("The account changed")
        val result = (delegate ?: throw TransferBlocked("Download is not open")).read(buffer, offset, length)
        if (result > 0) {
            // Validate actual bytes before returning them to Media3's cache sink; short final reads fit.
            runtime.checkTransferAllowed(current)
            if (authorizedScope?.let(runtime::ownerMatches) != true) throw TransferBlocked("The account changed")
            runtime.checkCapacity(maxOf(runtime.cachedBytes(), openedCacheBytes + readBytes), result.toLong())
            readBytes += result
        }
        return result
    }
    override fun getUri(): Uri? = delegate?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()
    override fun close() { delegate?.close(); delegate = null; row = null }
    companion object {
        // Explicitly reject all redirects. Credentials never reach another path/origin.
        private val CLIENT = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
    }
}
