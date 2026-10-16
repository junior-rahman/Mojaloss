package com.mojaloss.core.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.io.IOException

/** Immutable per descriptor. Checks initial requests AND absolute manifest children before IO. */
@UnstableApi
internal class GuardedMediaDataSourceFactory(
    client: OkHttpClient,
    serverUrl: String,
    headers: Map<String, String>
) : DataSource.Factory {
    private val origin = MediaOriginPolicy(serverUrl)
    private val delegateFactory = OkHttpDataSource.Factory(client)
        .setDefaultRequestProperties(headers.toMap())

    init {
        // Redirects must be rejected by HTTP transport before any foreign request is sent.
        require(!client.followRedirects && !client.followSslRedirects)
    }

    override fun createDataSource(): DataSource {
        val delegate = delegateFactory.createDataSource()
        return object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) =
                delegate.addTransferListener(transferListener)

            override fun open(dataSpec: DataSpec): Long {
                if (!origin.allows(dataSpec.uri.toString())) {
                    // Never include credential-bearing URLs or headers in diagnostic messages.
                    throw IOException("Media request left the authorized server origin")
                }
                return delegate.open(dataSpec)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)
            override fun getUri(): Uri? = delegate.uri
            override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
            override fun close() = delegate.close()
        }
    }
}
