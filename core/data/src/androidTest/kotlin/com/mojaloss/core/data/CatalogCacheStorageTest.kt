package com.mojaloss.core.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojaloss.core.model.MediaKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Uses the platform Android Keystore and disk storage, with no provider or network access. */
@RunWith(AndroidJUnit4::class)
class CatalogCacheStorageTest {
    @Test fun completeAndPartialCatalogRoundTripPreservesObservedMetadataAndTime() {
        Fixture().use { fixture ->
            val partial = catalog(complete = false).copy(totalCount = 3, nextStart = 1)
            assertTrue(fixture.storage.save(partial))
            assertEquals(partial, fixture.storage.load(SERVER, ACCOUNT))
            val complete = partial.copy(complete = true, savedAtEpochMs = SAVED_AT + 1, totalCount = 1)
            assertTrue(fixture.storage.save(complete))
            assertEquals(complete, fixture.storage.load(SERVER, ACCOUNT))
            val emptyComplete = complete.copy(titles = emptyList(), totalCount = 0, nextStart = 0)
            assertTrue(fixture.storage.save(emptyComplete))
            assertEquals(emptyComplete, fixture.storage.load(SERVER, ACCOUNT))
        }
    }

    @Test fun encryptedFileDoesNotExposeAccountOrTitleMetadataAndUsesNewIv() {
        Fixture().use { fixture ->
            val value = catalog()
            assertTrue(fixture.storage.save(value))
            assertTrue(fixture.file.isFile)
            assertTrue(fixture.file.canonicalPath.startsWith(fixture.directory.canonicalPath + File.separator))
            val first = fixture.file.readBytes()
            val representation = first.toString(Charsets.ISO_8859_1)
            listOf(SERVER, ACCOUNT, "Private display name", "Private catalog title", "poster-fixture", "Drama",
                "artworkHeaders", "X-Emby-Token", "accessToken", "token", "https://").forEach {
                assertFalse("Encrypted cache exposed $it", representation.contains(it))
            }
            assertTrue(fixture.storage.save(value))
            assertFalse(first.contentEquals(fixture.file.readBytes()))
            assertEquals(value, fixture.storage.load(SERVER, ACCOUNT))
        }
    }

    @Test fun normalizedScopeRestoresButOtherAccountServerAndPathCannotReadOrDeleteIt() {
        Fixture().use { fixture ->
            val mixedScope = catalog().copy(serverUrl = " https://CACHE-FIXTURE.invalid:443/jelly ",
                accountId = ACCOUNT.uppercase())
            assertTrue(fixture.storage.save(mixedScope))
            val expected = mixedScope.copy(serverUrl = SERVER, accountId = ACCOUNT)
            assertEquals(expected, fixture.storage.load("https://cache-fixture.invalid/jelly", ACCOUNT))
            assertNull(fixture.storage.load(SERVER, OTHER_ACCOUNT))
            assertNull(fixture.storage.load("https://other-cache-fixture.invalid/jelly/", ACCOUNT))
            assertNull(fixture.storage.load("https://cache-fixture.invalid/other/", ACCOUNT))
            assertNull(fixture.storage.load("http://cache-fixture.invalid/jelly/", ACCOUNT))
            assertEquals(expected, fixture.storage.load(SERVER, ACCOUNT))
        }
    }

    @Test fun corruptedTruncatedUnsupportedVersionAndOversizedFilesFailClosed() {
        Fixture().use { fixture ->
            assertNull(fixture.storage.load(SERVER, ACCOUNT))
            assertTrue(fixture.storage.save(catalog()))
            val good = fixture.file.readBytes()
            val damaged = listOf(
                byteArrayOf(), good.copyOf(8),
                good.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() },
                good.copyOf().apply { this[4] = 3 },
            )
            damaged.forEach { bytes ->
                fixture.file.writeBytes(bytes)
                assertNull(fixture.storage.load(SERVER, ACCOUNT))
            }
            RandomAccessFile(fixture.file, "rw").use { it.setLength(8L * 1024 * 1024 + 1024) }
            assertNull(fixture.storage.load(SERVER, ACCOUNT))
            fixture.file.writeBytes(good)
            assertEquals(catalog(), fixture.storage.load(SERVER, ACCOUNT))
        }
    }

    @Test fun invalidIdsNumbersAndArtworkUrlsCannotReplaceLastValidCache() {
        Fixture().use { fixture ->
            val good = catalog()
            assertTrue(fixture.storage.save(good))
            val title = good.titles.single()
            val invalid = listOf(
                good.copy(accountId = "not-an-account"), good.copy(savedAtEpochMs = 0),
                good.copy(queryKey = ""), good.copy(queryKey = "x".repeat(4097)),
                good.copy(totalCount = -1), good.copy(totalCount = 2), good.copy(nextStart = -1),
                good.copy(totalCount = 1, nextStart = 2),
                good.copy(displayName = ""), good.copy(titles = listOf(title.copy(id = "not-an-item"))),
                good.copy(titles = listOf(title, title)),
                good.copy(titles = listOf(title.copy(runtimeMs = -1))),
                good.copy(titles = listOf(title.copy(confirmedResumeMs = -1))),
                good.copy(titles = listOf(title.copy(year = 10_000))),
                good.copy(titles = listOf(title.copy(seasonNumber = -1))),
                good.copy(titles = listOf(title.copy(posterTag = "https://cache-fixture.invalid/poster?token=secret"))),
            )
            invalid.forEach { value ->
                assertFalse(fixture.storage.save(value))
                assertEquals(good, fixture.storage.load(SERVER, ACCOUNT))
            }
        }
    }

    @Test fun clearAndConcurrentInstancesNeverExposeAnIncompleteWrite() {
        Fixture().use { fixture ->
            val second = CatalogCacheStorage(fixture.context)
            val start = CountDownLatch(1)
            val finished = CountDownLatch(2)
            val error = AtomicReference<Throwable?>()
            val first = catalog()
            val other = first.copy(savedAtEpochMs = SAVED_AT + 1, complete = false)
            listOf(fixture.storage to first, second to other).forEach { (storage, value) ->
                thread(name = "catalog-cache-fixture") {
                    try {
                        assertTrue(start.await(10, TimeUnit.SECONDS))
                        repeat(3) {
                            assertTrue(storage.save(value))
                            val restored = storage.load(SERVER, ACCOUNT)
                            assertTrue(restored == first || restored == other)
                        }
                    } catch (failure: Throwable) {
                        error.compareAndSet(null, failure)
                    } finally { finished.countDown() }
                }
            }
            start.countDown()
            assertTrue(finished.await(30, TimeUnit.SECONDS))
            error.get()?.let { throw it }
            assertNotNull(fixture.storage.load(SERVER, ACCOUNT))
            second.clear()
            assertNull(fixture.storage.load(SERVER, ACCOUNT))
            assertFalse(fixture.file.exists())
        }
    }

    @Test fun exactQueryScopeSeparatesSavedCatalogsAndClearLeavesOtherPrivateFiles() {
        Fixture().use { fixture ->
            val home = catalog()
            val movies = home.copy(queryKey = "browse-movies-v1", complete = false, totalCount = 9, nextStart = 1)
            assertTrue(fixture.storage.save(home))
            assertTrue(fixture.storage.save(movies))
            assertEquals(home, fixture.storage.load(SERVER, ACCOUNT))
            assertEquals(movies, fixture.storage.load(SERVER, ACCOUNT, movies.queryKey))
            assertNull(fixture.storage.load(SERVER, ACCOUNT, "browse-series-v1"))
            fixture.fileForQuery("browse-series-v1").writeBytes(fixture.file.readBytes())
            assertNull(fixture.storage.load(SERVER, ACCOUNT, "browse-series-v1"))
            assertEquals(home, fixture.storage.load(SERVER, ACCOUNT))
            val unrelated = File(fixture.directory, "mojaloss/preferences.json").apply { writeText("fixture") }
            fixture.storage.clear()
            assertNull(fixture.storage.load(SERVER, ACCOUNT))
            assertNull(fixture.storage.load(SERVER, ACCOUNT, movies.queryKey))
            assertEquals("fixture", unrelated.readText())
        }
    }

    @Test fun queryCacheQuotaEvictsOldSearchesAndRetainsHome() {
        Fixture().use { fixture ->
            val home = catalog()
            assertTrue(fixture.storage.save(home))
            repeat(20) { index ->
                assertTrue(fixture.storage.save(home.copy(queryKey = "search-v1-$index")))
            }
            assertEquals(home, fixture.storage.load(SERVER, ACCOUNT))
            val files = File(fixture.directory, "mojaloss").listFiles().orEmpty()
                .filter { it.name.startsWith("catalog-v2-") }
            assertEquals(12, files.size)
            assertTrue(files.sumOf { it.length() } <= 24L * 1024 * 1024)
            assertNotNull(fixture.storage.load(SERVER, ACCOUNT, "search-v1-19"))
            files.filter { it != fixture.file }.take(4).forEach { file ->
                // Sparse files exercise the aggregate byte quota without large fixture allocations.
                RandomAccessFile(file, "rw").use { it.setLength(8L * 1024 * 1024) }
            }
            val current = home.copy(queryKey = "search-v1-latest")
            assertTrue(fixture.storage.save(current))
            val boundedFiles = File(fixture.directory, "mojaloss").listFiles().orEmpty()
                .filter { it.name.startsWith("catalog-v2-") }
            assertTrue(boundedFiles.size <= 12)
            assertTrue(boundedFiles.sumOf { it.length() } <= 24L * 1024 * 1024)
            assertEquals(home, fixture.storage.load(SERVER, ACCOUNT))
            assertEquals(current, fixture.storage.load(SERVER, ACCOUNT, current.queryKey))
        }
    }

    private class Fixture : Closeable {
        private val rootContext: Context = ApplicationProvider.getApplicationContext()
        val directory = File(rootContext.noBackupFilesDir,
            "catalog-cache-regression-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(rootContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        val storage = CatalogCacheStorage(context)
        fun fileForQuery(queryKey: String): File {
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(queryKey.encodeToByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return File(directory, "mojaloss/catalog-v2-$fingerprint.enc")
        }
        val file = fileForQuery("home-v1")
        override fun close() { directory.deleteRecursively() }
    }

    private companion object {
        const val SERVER = "https://cache-fixture.invalid/jelly/"
        const val ACCOUNT = "00000000-0000-0000-0000-0000000000ab"
        const val OTHER_ACCOUNT = "00000000-0000-0000-0000-0000000000cd"
        const val SAVED_AT = 1_792_000_000_000L
        fun catalog(complete: Boolean = true) = CachedCatalog(
            serverUrl = SERVER, accountId = ACCOUNT, displayName = "Private display name",
            savedAtEpochMs = SAVED_AT, complete = complete,
            titles = listOf(CachedCatalogTitle(
                id = "00000000-0000-0000-0000-000000000001", title = "Private catalog title",
                kind = MediaKind.EPISODE, year = 2026, genre = "Drama", runtimeMs = 5_400_000,
                isPlayable = true, isUpcoming = false, posterTag = "poster-fixture",
                backdropTag = "backdrop-fixture", seriesId = "00000000-0000-0000-0000-000000000002",
                seasonNumber = 2, episodeNumber = 3, confirmedResumeMs = 120_000, favorite = true,
            )),
        )
    }
}
