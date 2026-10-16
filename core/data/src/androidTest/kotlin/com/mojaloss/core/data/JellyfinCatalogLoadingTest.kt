package com.mojaloss.core.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojaloss.core.model.CatalogQuery
import com.mojaloss.core.model.ContentStatus
import com.mojaloss.core.model.SessionState
import kotlinx.coroutines.*
import okhttp3.Request
import org.jellyfin.sdk.api.client.exception.InvalidContentException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Successful and failed production catalog loads through the real, fully intercepted SDK. */
@RunWith(AndroidJUnit4::class)
class JellyfinCatalogLoadingTest {
    @Test fun publishesFirstVerifiedPageBeforeBlockedSecondPageAndMapsUserData() = runBlocking(Dispatchers.IO) {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture { request ->
            val start = start(request)
            if (start == 2) { entered.countDown(); awaitFixture(release) }
            page(start, 3, if (start == 0) listOf(1, 2) else listOf(3))
        }.use { fixture ->
            fixture.connect()
            val loading = async { fixture.repository.loadMore() }
            try {
                assertTrue(entered.await(15, TimeUnit.SECONDS))
                assertEquals(ContentStatus.READY, fixture.repository.catalog.value.status)
                assertTrue(fixture.repository.catalog.value.isLoadingMore)
                assertEquals(2, fixture.repository.catalog.value.titles.size)
                val first = fixture.repository.catalog.value.titles.first()
                assertTrue(first.favorite)
                assertEquals(120_000L, first.confirmedResumeMs)
                assertTrue(first.posterUrl!!.contains("/jelly/Items/"))
                assertTrue(first.backdropUrl!!.contains("/jelly/Items/"))
                assertEquals("local-fixture-token", first.artworkHeaders["X-Emby-Token"])
            } finally { release.countDown() }
            withTimeout(15_000) { loading.await() }
            assertEquals(ContentStatus.READY, fixture.repository.catalog.value.status)
            assertEquals(3, fixture.repository.catalog.value.titles.size)
            assertEquals(listOf(0, 2), fixture.catalogStarts.toList())
            assertTrue(fixture.failures.isEmpty())
            assertLeanAuthenticatedQuery(fixture.catalogRequests.first())
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test fun serverCappedShortPagesDoNotSilentlyTruncateLibrary() = runBlocking(Dispatchers.IO) {
        Fixture { request -> page(start(request), 3, listOf(start(request) + 1)) }.use { fixture ->
            fixture.connect()
            while (fixture.repository.catalog.value.hasMore) fixture.repository.loadMore()
            assertEquals(ContentStatus.READY, fixture.repository.catalog.value.status)
            assertEquals(listOf(id(1), id(2), id(3)), fixture.repository.catalog.value.titles.map { it.id })
            assertEquals(listOf(0, 1, 2), fixture.catalogStarts.toList())
        }
    }

    @Test fun laterPageDenialPreservesVerifiedTitlesAndConnectedAccount() = runBlocking(Dispatchers.IO) {
        Fixture { request -> if (start(request) == 0) page(0, 3, listOf(1, 2)) else response(403, "{}") }.use { fixture ->
            fixture.connect()
            fixture.repository.loadMore()
            assertEquals(ContentStatus.ERROR, fixture.repository.catalog.value.status)
            assertEquals(2, fixture.repository.catalog.value.titles.size)
            assertTrue(fixture.repository.session.value is SessionState.Connected)
            assertEquals("catalog_refresh", fixture.failures.single().first)
            assertTrue(fixture.failures.single().second is InvalidStatusException)
            assertEquals(listOf(0, 2), fixture.catalogStarts.toList())
            assertTrue(fixture.repository.catalog.value.message!!.contains("2 loaded titles"))
        }
    }

    @Test fun incompleteSdkJsonBecomesCatalogErrorWithoutClaimingCachedTitles() = runBlocking(Dispatchers.IO) {
        Fixture { response(200, """{"Items":[],"TotalRecordCount":0}""") }.use { fixture ->
            fixture.connect()
            assertEquals(ContentStatus.ERROR, fixture.repository.catalog.value.status)
            assertTrue(fixture.repository.catalog.value.titles.isEmpty())
            assertTrue(fixture.repository.session.value is SessionState.Connected)
            assertTrue(fixture.failures.single().second is InvalidContentException)
            assertTrue(fixture.repository.catalog.value.message!!.contains("data this app could not read"))
            assertFalse(fixture.repository.catalog.value.message!!.contains("cached"))
            assertEquals(1, fixture.catalogStarts.size)
        }
    }

    @Test fun slowLaterPageEndsLoadingLocallyWithoutExpiringSuccessfulSignIn() = runBlocking(Dispatchers.IO) {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture(CatalogLoadOptions(2, 1_000, 0)) { request ->
            if (start(request) == 0) page(0, 3, listOf(1, 2))
            else { entered.countDown(); awaitFixture(release); page(2, 3, listOf(3)) }
        }.use { fixture ->
            fixture.connect()
            val loading = async { fixture.repository.loadMore() }
            try {
                assertTrue(entered.await(15, TimeUnit.SECONDS))
                withTimeout(5_000) { loading.await() }
                assertEquals(ContentStatus.ERROR, fixture.repository.catalog.value.status)
                assertEquals(2, fixture.repository.catalog.value.titles.size)
                assertTrue(fixture.repository.session.value is SessionState.Connected)
                assertTrue(fixture.repository.catalog.value.message!!.contains("too long"))
                assertEquals("catalog_refresh", fixture.failures.single().first)
            } finally { release.countDown() }
            delay(100)
            assertEquals(ContentStatus.ERROR, fixture.repository.catalog.value.status)
            assertEquals(2, fixture.repository.catalog.value.titles.size)
        }
    }

    @Test fun cancelledLoadClearsLoadingAndRetryUsesSameVerifiedAccount() = runBlocking(Dispatchers.IO) {
        val requests = AtomicInteger(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture { request ->
            if (requests.incrementAndGet() == 1) { entered.countDown(); awaitFixture(release) }
            page(start(request), 1, listOf(1))
        }.use { fixture ->
            val loading = async { fixture.connect() }
            try {
                assertTrue(entered.await(15, TimeUnit.SECONDS))
                loading.cancel()
                try { loading.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
                assertEquals(ContentStatus.ERROR, fixture.repository.catalog.value.status)
                assertTrue(fixture.repository.session.value is SessionState.Connected)
            } finally { release.countDown() }
            fixture.repository.refresh()
            assertEquals(ContentStatus.READY, fixture.repository.catalog.value.status)
            assertEquals(1, fixture.repository.catalog.value.titles.size)
            assertEquals(1, fixture.authRequests.get())
        }
    }

    @Test fun replacedRefreshCannotPublishLateOldCatalog() = runBlocking(Dispatchers.IO) {
        val calls = AtomicInteger(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture { request ->
            val call = calls.incrementAndGet()
            if (call == 2) { entered.countDown(); awaitFixture(release) }
            page(start(request), 1, listOf(if (call == 3) 9 else 1))
        }.use { fixture ->
            fixture.connect()
            val old = async { fixture.repository.refresh() }
            try {
                assertTrue(entered.await(15, TimeUnit.SECONDS))
                fixture.repository.refresh()
                assertEquals(id(9), fixture.repository.catalog.value.titles.single().id)
            } finally { release.countDown() }
            try { old.await() } catch (_: CancellationException) { }
            delay(100)
            assertEquals(ContentStatus.READY, fixture.repository.catalog.value.status)
            assertEquals(id(9), fixture.repository.catalog.value.titles.single().id)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test fun signOutRejectsLatePageFromPreviousAccount() = runBlocking(Dispatchers.IO) {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture { request -> entered.countDown(); awaitFixture(release); page(start(request), 1, listOf(1)) }.use { fixture ->
            val loading = async { fixture.connect() }
            try {
                assertTrue(entered.await(15, TimeUnit.SECONDS))
                fixture.repository.signOut()
            } finally { release.countDown() }
            try { loading.await() } catch (_: CancellationException) { }
            delay(100)
            assertEquals(SessionState.SignedOut, fixture.repository.session.value)
            assertTrue(fixture.repository.catalog.value.titles.isEmpty())
            assertEquals(ContentStatus.READY, fixture.repository.catalog.value.status)
        }
    }

    @Test fun repeatedPageStopsWithDeduplicatedVisibleTitlesAndRetryableError() = runBlocking(Dispatchers.IO) {
        Fixture { request -> page(start(request), 3, listOf(1)) }.use { fixture ->
            fixture.connect()
            fixture.repository.loadMore()
            assertEquals(ContentStatus.ERROR, fixture.repository.catalog.value.status)
            assertEquals(listOf(id(1)), fixture.repository.catalog.value.titles.map { it.id })
            assertTrue(fixture.repository.catalog.value.message!!.contains("repeated"))
            assertEquals(listOf(0, 1), fixture.catalogStarts.toList())
        }
    }

    @Test fun inconsistentEmptyTailCannotClaimCompleteLibrary() = runBlocking(Dispatchers.IO) {
        Fixture { request -> if (start(request) == 0) page(0, 3, listOf(1, 2)) else page(2, 1, emptyList()) }.use { fixture ->
            fixture.connect()
            fixture.repository.loadMore()
            assertEquals(ContentStatus.ERROR, fixture.repository.catalog.value.status)
            assertEquals(2, fixture.repository.catalog.value.titles.size)
            assertEquals(listOf(0, 2), fixture.catalogStarts.toList())
            assertTrue(fixture.repository.catalog.value.message!!.contains("rest of your library"))
        }
    }

    @Test fun oneTransientServerFailureRetriesSamePageOnce() = runBlocking(Dispatchers.IO) {
        val calls = AtomicInteger()
        Fixture(CatalogLoadOptions(2, 20_000, 1)) { request ->
            if (calls.incrementAndGet() == 1) response(503, "{}") else page(start(request), 1, listOf(1))
        }.use { fixture ->
            fixture.connect()
            assertEquals(ContentStatus.READY, fixture.repository.catalog.value.status)
            assertEquals(listOf(0, 0), fixture.catalogStarts.toList())
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test fun genuinelyEmptyLibraryIsCompleteAndReady() = runBlocking(Dispatchers.IO) {
        Fixture { request -> page(start(request), 0, emptyList()) }.use { fixture ->
            fixture.connect()
            assertEquals(ContentStatus.READY, fixture.repository.catalog.value.status)
            assertTrue(fixture.repository.catalog.value.titles.isEmpty())
            assertNull(fixture.repository.catalog.value.message)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    private class Fixture(
        options: CatalogLoadOptions = CatalogLoadOptions(2, 20_000, 0),
        catalogResponse: (Request) -> InterceptedSdkTransport.FixtureResponse,
    ) : Closeable {
        private val rootContext: Context = ApplicationProvider.getApplicationContext()
        private val directory = File(rootContext.cacheDir, "catalog-regression-${UUID.randomUUID()}").apply { mkdirs() }
        private val context = object : ContextWrapper(rootContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val failures = CopyOnWriteArrayList<Pair<String, Throwable>>()
        val catalogStarts = CopyOnWriteArrayList<Int>()
        val catalogRequests = CopyOnWriteArrayList<Request>()
        val authRequests = AtomicInteger()
        val transport = InterceptedSdkTransport { request ->
            when (request.url.encodedPath) {
                "/jelly/System/Info/Public" -> response(200, """{"Version":"10.11.0","ServerName":"Local fixture","Id":"fixture-server"}""")
                "/jelly/Users/AuthenticateByName" -> { authRequests.incrementAndGet(); response(200, """{"User":$USER_JSON,"AccessToken":"local-fixture-token"}""") }
                "/jelly/Users/Me" -> response(200, USER_JSON)
                "/jelly/Items" -> {
                    catalogStarts += start(request)
                    catalogRequests += request
                    catalogResponse(request)
                }
                else -> throw IOException("Unexpected local fixture endpoint")
            }
        }
        val repository = JellyfinMediaRepository(
            context, scope,
            onConnectionDiagnostic = { stage, failure -> if (failure != null) failures += stage to failure },
            apiClientFactory = { base, token -> transport.createApi(context, base, token) },
            catalogLoadOptions = options,
        )
        suspend fun connect() {
            repository.connectPassword(INPUT, "local-fixture", "local-fixture-password")
            repository.showCatalog(CatalogQuery()) // Explicit visible HOME demand in this fixture.
        }
        override fun close() { scope.cancel(); transport.close(); directory.deleteRecursively() }
    }

    private companion object {
        const val INPUT = "https://www.mojaloss.stream/jelly"
        const val USER_ID = "00000000-0000-0000-0000-000000000042"
        const val USER_JSON = """{"Id":"00000000-0000-0000-0000-000000000042","Name":"Local fixture","HasPassword":false,"HasConfiguredPassword":false,"HasConfiguredEasyPassword":false}"""
        fun id(value: Int) = UUID(0, value.toLong()).toString()
        fun start(request: Request) = requireNotNull(request.url.queryParameter("startIndex")).toInt()
        fun response(status: Int, body: String) = InterceptedSdkTransport.FixtureResponse(status, body)
        fun page(start: Int, total: Int, ids: List<Int>) = response(200, """{"Items":[${ids.joinToString(",") { item(it) }}],"TotalRecordCount":$total,"StartIndex":$start}""")
        fun item(value: Int) = """{"Id":"${id(value)}","Name":"Title $value","Type":"${if (value == 3) "Series" else "Movie"}","Genres":["Drama"],"PlayAccess":"Full","RunTimeTicks":54000000000,"ImageTags":{"Primary":"poster$value"},"BackdropImageTags":["backdrop$value"],"UserData":{"PlaybackPositionTicks":${if (value == 1) 1200000000 else 0},"PlayCount":0,"IsFavorite":${value == 1},"Played":false,"Key":"fixture-$value","ItemId":"${id(value)}"}}"""
        fun awaitFixture(latch: CountDownLatch) {
            try { if (!latch.await(15, TimeUnit.SECONDS)) throw IOException("Local fixture timed out") }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Local fixture interrupted") }
        }
        fun assertLeanAuthenticatedQuery(request: Request) {
            assertEquals(setOf("Genres", "PlayAccess"), request.url.queryParameterValues("fields").toSet())
            assertEquals(setOf("Primary", "Backdrop"), request.url.queryParameterValues("enableImageTypes").toSet())
            assertEquals("1", request.url.queryParameter("imageTypeLimit"))
            assertEquals("2", request.url.queryParameter("limit"))
            assertEquals("true", request.url.queryParameter("enableUserData"))
            assertEquals(USER_ID, request.url.queryParameter("userId"))
            assertTrue(request.header("Authorization")!!.contains("local-fixture-token"))
        }
    }
}
