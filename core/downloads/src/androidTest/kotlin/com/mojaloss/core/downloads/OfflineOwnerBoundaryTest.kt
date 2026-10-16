package com.mojaloss.core.downloads

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojaloss.core.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OfflineOwnerBoundaryTest {
    @Test fun restoredReadyCopyPlaysWithoutAuthorizationAndAccountChangeStopsCacheReads() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val fixture = PrivateFixtureContext(base, File(base.noBackupFilesDir, "offline-owner-${UUID.randomUUID()}"))
        val scope = SessionScope("https://never-contact.example.test/jelly", "ownerA", 9)
        val owner = downloadOwnerKey(scope)
        val url = "${scope.serverUrl}/Items/movie/Download?mediaSourceId=source"
        val id = DownloadPolicy.id(owner, "movie", "source")
        val row = StoredDownload(id, owner, scope.serverUrl, scope.accountId,
            MediaTitle("movie", "Private offline fixture", isPlayable = true, runtimeMs = 60_000),
            url, "source", "video/mp4", 64, DownloadPolicy.fingerprint(url, "source", 64), 1,
            positionMs = 12_000)
        val directory = File(fixture.noBackupFilesDir, "offline-media")
        DownloadStore(directory).write(listOf(row), DownloadSettings())
        val database = StandaloneDatabaseProvider(fixture)
        val cache = SimpleCache(File(directory, "media"), NoOpCacheEvictor(), database)
        val spec = DataSpec.Builder().setUri(url).setKey(id).setLength(64).build()
        val bytes = ByteArray(64) { it.toByte() }
        // Direct fixture writers must own the cache hole, just as CacheDataSource does.
        val hole = cache.startReadWrite(id, 0, 64)
        assertFalse(hole.isCached)
        val sink = CacheDataSink(cache, 2 * 1024 * 1024L)
        try {
            try { sink.open(spec); sink.write(bytes, 0, bytes.size) }
            finally { sink.close() }
        } finally { cache.releaseHoleSpan(hole) }
        val request = DownloadRequest.Builder(id, Uri.parse(url)).setCustomCacheKey(id).build()
        DefaultDownloadIndex(database).putDownload(Download(request, Download.STATE_COMPLETED, 1, 1, 64, 0, 0))
        cache.release(); database.close()
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var authorizationCalls = 0
        val runtime = OfflineDownloads(fixture, runtimeScope) { authorizationCalls++; null }
        try {
            runtime.setOwner(scope)
            runtime.initialize()
            val prepared = runtime.prepareOfflinePlayback(id, scope)
            assertTrue(prepared is OperationResult.Success)
            val descriptor = (prepared as OperationResult.Success).value
            assertEquals(id, descriptor.offlineCacheKey)
            assertEquals(12_000L, descriptor.resumeMs)
            assertTrue(descriptor.headers.isEmpty())
            assertNull(descriptor.playSessionId)
            val callsBeforeOfflineReads = authorizationCalls
            val source = runtime.cacheOnlyDataSourceFactory().createDataSource()
            assertEquals(64L, source.open(spec))
            assertEquals(8, source.read(ByteArray(8), 0, 8))
            assertEquals(callsBeforeOfflineReads, authorizationCalls)
            runtime.setOwner(scope.copy(accountId = "ownerB", generation = 10))
            try { source.read(ByteArray(8), 0, 8); fail("Switching accounts must stop offline cache reads") }
            catch (_: IOException) { }
            finally { source.close() }
            val denied = runtime.prepareOfflinePlayback(id, scope.copy(accountId = "ownerB", generation = 10))
            assertTrue(denied is OperationResult.Failure && denied.denied)
            runtime.setOwner(null)
            runtime.clearOwner(owner)
            assertTrue(runtime.prepareOfflinePlayback(id, scope) is OperationResult.Failure)
        } finally { runtime.release(); runtimeScope.cancel(); fixture.root.deleteRecursively() }
    }
    private class PrivateFixtureContext(base: Context, val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = root.apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, "databases/$name").apply { parentFile?.mkdirs() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
    }
}
