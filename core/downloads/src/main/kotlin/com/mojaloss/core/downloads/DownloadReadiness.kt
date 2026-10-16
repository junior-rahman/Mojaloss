package com.mojaloss.core.downloads

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.Download

/** Completion in the index alone cannot make a missing, truncated or evicted copy playable. */
internal fun isFullyCached(row: StoredDownload, download: Download?, cache: Cache): Boolean = runCatching {
    val size = row.expectedBytes ?: download?.contentLength ?: return false
    download?.state == Download.STATE_COMPLETED && size > 0 &&
        (download.contentLength <= 0 || download.contentLength == size) &&
        cache.isCached(row.id, 0, size) && cache.getCachedSpans(row.id).all { span ->
            span.isCached && span.file?.let { it.exists() && it.length() >= span.length && span.length > 0 } == true
        }
}.getOrDefault(false)

/** No upstream factory is attached: a missing byte always fails offline rather than fetching it. */
internal fun cacheOnlyFactory(cache: Cache): CacheDataSource.Factory = CacheDataSource.Factory()
    .setCache(cache).setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null)
