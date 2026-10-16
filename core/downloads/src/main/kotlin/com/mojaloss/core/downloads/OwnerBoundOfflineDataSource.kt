package com.mojaloss.core.downloads

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** No HTTP factory exists here. A cache miss fails, and switching accounts stops active reads. */
internal class OwnerBoundOfflineDataSource(
    private val runtime: OfflineDownloads,
    private val cacheSource: DataSource,
) : DataSource {
    private var ownerKey: String? = null
    private var id: String? = null
    override fun addTransferListener(listener: TransferListener) = cacheSource.addTransferListener(listener)
    override fun open(dataSpec: DataSpec): Long {
        val row = runtime.rowForTransfer(dataSpec.key, dataSpec.uri.toString())
            ?: throw TransferBlocked("This download is no longer available")
        ownerKey = row.owner
        id = row.id
        checkOwner()
        return cacheSource.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkOwner()
        return cacheSource.read(buffer, offset, length)
    }
    private fun checkOwner() {
        if (ownerKey == null || id?.let { runtime.canReadOffline(it, ownerKey!!) } != true)
            throw TransferBlocked("Open the account that downloaded this title")
    }
    override fun getUri(): Uri? = cacheSource.uri
    override fun getResponseHeaders(): Map<String, List<String>> = cacheSource.responseHeaders
    override fun close() { cacheSource.close(); ownerKey = null }
}
