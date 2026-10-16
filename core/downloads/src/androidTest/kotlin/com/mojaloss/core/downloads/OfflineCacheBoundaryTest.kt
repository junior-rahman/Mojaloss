package com.mojaloss.core.downloads

import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojaloss.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OfflineCacheBoundaryTest {
    private val scope = SessionScope("https://unused.example.test/jelly", "fixture-account", 1)
    private fun row(): StoredDownload {
        val owner = downloadOwnerKey(scope)
        val url = "${scope.serverUrl}/Items/movie/Download?mediaSourceId=source"
        val id = DownloadPolicy.id(owner, "movie", "source")
        return StoredDownload(id, owner, scope.serverUrl, scope.accountId,
            MediaTitle("movie", "Offline fixture", isPlayable = true, year = 2026,
                artworkHeaders = mapOf("Authorization" to "NEVER-PERSIST-FIXTURE-SECRET")),
            url, "source", "video/mp4", 64, DownloadPolicy.fingerprint(url, "source", 64), 1)
    }
    @Test fun metadataPersistsPauseWifiQuotaAndProgressWithoutCredentialHeaders() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.noBackupFilesDir, "download-store-${UUID.randomUUID()}")
        try {
            val row = row().copy(userPaused = true, positionMs = 12_000)
            val settings = DownloadSettings(wifiOnly = false, storageLimitBytes = 1024L * 1024 * 1024)
            DownloadStore(dir).write(listOf(row), settings, setOf("blocked-fixture-owner"))
            val saved = File(dir, "library.json").readText()
            assertFalse(saved.contains("NEVER-PERSIST-FIXTURE-SECRET"))
            assertFalse(saved.contains("Authorization"))
            val (items, restored) = DownloadStore(dir).read()
            assertTrue(items.single().userPaused)
            assertEquals(12_000L, items.single().positionMs)
            assertEquals(settings, restored)
            assertTrue(items.single().title.artworkHeaders.isEmpty())
            assertEquals(setOf("blocked-fixture-owner"), DownloadStore(dir).readBlockedOwners())
        } finally { dir.deleteRecursively() }
    }
    @Test fun completedIndexRequiresAllRealBytesAndOfflineMissNeverUsesNetwork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.noBackupFilesDir, "download-cache-${UUID.randomUUID()}")
        val database = StandaloneDatabaseProvider(context)
        val cache = SimpleCache(dir, NoOpCacheEvictor(), database)
        try {
            val row = row()
            val request = DownloadRequest.Builder(row.id, Uri.parse(row.url)).setCustomCacheKey(row.id).build()
            val completed = Download(request, Download.STATE_COMPLETED, 1, 1, 64, 0, 0)
            assertFalse(isFullyCached(row, completed, cache))
            val spec = DataSpec.Builder().setUri(row.url).setKey(row.id).setLength(64).build()
            val bytes = ByteArray(64) { it.toByte() }
            // Direct fixture writers must own the cache hole, just as CacheDataSource does.
            val hole = cache.startReadWrite(row.id, 0, 64)
            assertFalse(hole.isCached)
            val sink = CacheDataSink(cache, 2 * 1024 * 1024L)
            try {
                try { sink.open(spec); sink.write(bytes, 0, bytes.size) }
                finally { sink.close() }
            } finally { cache.releaseHoleSpan(hole) }
            assertTrue(isFullyCached(row, completed, cache))
            assertFalse(isFullyCached(row, Download(request, Download.STATE_STOPPED, 1, 1, 64, 1001, 0), cache))
            assertFalse(isFullyCached(row.copy(expectedBytes = 65), completed, cache))
            val source = cacheOnlyFactory(cache).createDataSource()
            source.open(spec)
            val actual = ByteArray(64)
            assertEquals(64, source.read(actual, 0, 64)); source.close()
            assertArrayEquals(bytes, actual)
            val missing = DataSpec.Builder().setUri(row.url).setKey("missing").setLength(64).build()
            val cacheMiss = cacheOnlyFactory(cache).createDataSource()
            try { cacheMiss.open(missing); fail("An offline cache miss must fail") }
            catch (_: IOException) { /* No upstream factory exists, so no DNS/HTTP request is possible. */ }
            finally { cacheMiss.close() }
            cache.removeResource(row.id)
            assertFalse(isFullyCached(row, completed, cache))
        } finally { cache.release(); database.close(); dir.deleteRecursively() }
    }
}
