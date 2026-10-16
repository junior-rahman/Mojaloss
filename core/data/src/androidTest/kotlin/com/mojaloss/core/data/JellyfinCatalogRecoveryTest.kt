package com.mojaloss.core.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojaloss.core.model.*
import kotlinx.coroutines.*
import okhttp3.Request
import org.jellyfin.sdk.api.client.exception.TimeoutException as SdkTimeoutException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Production SDK and actual encrypted disk cache; every HTTP response is intercepted offline. */
@RunWith(AndroidJUnit4::class)
class JellyfinCatalogRecoveryTest {
    @Test fun reportedDnsChainAfterExactly300TitlesRetriesBoundedlyAndManualRetryResumes300() = runBlocking(Dispatchers.IO) {
        val failDns = AtomicBoolean(true)
        Fixture(CatalogLoadOptions(50, 20_000, 2, 0), catalogResponse = { request ->
            val start = start(request)
            if (start == 300 && failDns.get()) throw UnknownHostException("Local fixture DNS failure")
            page(start, 350, (start + 1..minOf(start + 50, 350)).toList())
        }).use { fixture ->
            fixture.connect()
            repeat(5) { fixture.repository.loadMore() }
            fixture.repository.loadMore()
            val failed = fixture.repository.catalog.value
            assertEquals(ContentStatus.ERROR, failed.status)
            assertEquals(300, failed.titles.size)
            assertFalse(failed.isComplete)
            assertFalse(failed.isRefreshing)
            assertTrue(failed.message!!.contains("find the server address"))
            val failure = fixture.failures.single().second
            assertTrue(failure is SdkTimeoutException)
            assertEquals(CatalogNetworkFailure.DNS, catalogNetworkFailure(failure))
            assertEquals(listOf(0, 50, 100, 150, 200, 250, 300, 300, 300), fixture.catalogStarts.toList())
            assertTrue(fixture.repository.session.value is SessionState.Connected)
            failDns.set(false)
            fixture.repository.refresh()
            assertEquals(300, fixture.catalogStarts.last())
            assertEquals(10, fixture.catalogStarts.size)
            assertEquals(350, fixture.repository.catalog.value.titles.size)
            assertEquals(ContentStatus.READY, fixture.repository.catalog.value.status)
            assertTrue(fixture.repository.catalog.value.isComplete)
            assertEquals(1, fixture.authRequests.get())
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test fun changedPaginationTotalDropsCheckpointAndRetryRestartsSafelyAtZero() = runBlocking(Dispatchers.IO) {
        val valid = AtomicBoolean(false)
        Fixture(catalogResponse = { request ->
            val start = start(request)
            if (valid.get()) page(start, 3, if (start == 0) listOf(1, 2) else listOf(3))
            else if (start == 0) page(0, 3, listOf(1, 2)) else page(2, 4, listOf(3, 4))
        }).use { fixture ->
            fixture.connect()
            fixture.repository.loadMore()
            assertEquals(ContentStatus.ERROR, fixture.repository.catalog.value.status)
            assertEquals(2, fixture.repository.catalog.value.titles.size)
            valid.set(true)
            fixture.repository.refresh()
            fixture.repository.loadMore()
            assertEquals(listOf(0, 2, 0, 2), fixture.catalogStarts.toList())
            assertTrue(fixture.repository.catalog.value.isComplete)
            assertEquals(3, fixture.repository.catalog.value.titles.size)
        }
    }

    @Test fun cachedRowsStayDuringPendingRefreshThenAcceptedFirstPageReplacesDeletedRows() = runBlocking(Dispatchers.IO) {
        val refresh = AtomicBoolean(false)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture(catalogResponse = { request ->
            val start = start(request)
            if (!refresh.get()) page(start, 4, if (start == 0) listOf(1, 2) else listOf(3, 4))
            else if (start == 0) { entered.countDown(); await(release); page(0, 3, listOf(1, 2)) }
            else page(2, 3, listOf(5))
        }).use { fixture ->
            fixture.connect(); fixture.repository.loadMore(); refresh.set(true)
            val update = async { fixture.repository.refresh() }
            try {
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                assertEquals(4, fixture.repository.catalog.value.titles.size)
                assertEquals(ContentStatus.READY, fixture.repository.catalog.value.status)
                assertTrue(fixture.repository.catalog.value.isRefreshing)
            } finally { release.countDown() }
            withTimeout(20_000) { update.await() }
            assertEquals(listOf(id(1), id(2)), fixture.repository.catalog.value.titles.map { it.id })
            assertFalse(fixture.repository.catalog.value.isComplete)
            fixture.repository.loadMore()
            assertEquals(listOf(id(1), id(2), id(5)), fixture.repository.catalog.value.titles.map { it.id })
            assertFalse(fixture.repository.catalog.value.isRefreshing)
            assertTrue(fixture.repository.catalog.value.isComplete)
        }
    }

    @Test fun freshCompleteCacheAppearsBeforeVerificationWithoutCredentialsThenSkipsCatalogReload() = runBlocking(Dispatchers.IO) {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blockVerification = AtomicBoolean(false)
        Fixture(userResponse = {
            if (blockVerification.get()) { entered.countDown(); await(release) }
            response(200, USER_JSON)
        }).use { fixture ->
            fixture.connect()
            PrivateStorage(fixture.context).saveSession(StoredSession(ServerUrls.normalize(INPUT), USER_ID, "fresh-fixture-token"))
            val reopened = fixture.reopen(); blockVerification.set(true)
            val restoring = async { reopened.refreshIfStale() }
            var pendingScope: SessionScope? = null
            try {
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                val pending = reopened.session.value as SessionState.Restoring
                pendingScope = pending.scope
                val cached = reopened.catalog.value
                assertTrue(cached.isCached); assertTrue(cached.isComplete)
                assertEquals(2, cached.titles.size)
                assertFalse(cached.isConnectionVerified); assertFalse(cached.canUseLibrary)
                assertTrue(cached.titles.all { it.artworkHeaders.isEmpty() && it.posterUrl == null && it.backdropUrl == null })
                assertTrue(reopened.preparePlayback(id(1), fromStart = false) is OperationResult.Failure)
                assertTrue(reopened.setFavorite(id(1), true) is OperationResult.Failure)
                assertEquals(1, fixture.catalogStarts.size)
            } finally { release.countDown() }
            withTimeout(20_000) { restoring.await() }
            assertEquals(pendingScope, (reopened.session.value as SessionState.Connected).scope)
            assertEquals(1, fixture.catalogStarts.size)
            assertTrue(reopened.catalog.value.canUseLibrary)
            assertTrue(reopened.catalog.value.isCached)
            assertFalse(reopened.catalog.value.isRefreshing)
            assertTrue(reopened.catalog.value.titles.first().posterUrl!!.contains("tag=poster1"))
            assertEquals("fresh-fixture-token", reopened.catalog.value.titles.first().artworkHeaders["X-Emby-Token"])
        }
    }

    @Test fun freshPartialCacheRestoresWithoutReloadAndDemandContinuesItsQueryCursor() = runBlocking(Dispatchers.IO) {
        val fail = AtomicBoolean(true)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val block = AtomicBoolean(false)
        Fixture(catalogResponse = { request ->
            val start = start(request)
            if (start == 2 && fail.get()) throw UnknownHostException("Local fixture DNS failure")
            if (start == 2 && block.get()) { entered.countDown(); await(release) }
            page(start, 3, if (start == 0) listOf(1, 2) else listOf(3))
        }).use { fixture ->
            fixture.connect(); fixture.repository.loadMore()
            assertEquals(2, fixture.repository.catalog.value.titles.size)
            fail.set(false); block.set(true)
            val reopened = fixture.reopen()
            reopened.refreshIfStale()
            assertEquals(listOf(0, 2), fixture.catalogStarts.toList())
            val update = async { reopened.loadMore() }
            try {
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                assertEquals(2, reopened.catalog.value.titles.size)
                assertTrue(reopened.catalog.value.isCached)
                assertTrue(reopened.catalog.value.isLoadingMore)
                assertFalse(reopened.catalog.value.isComplete)
                assertEquals(listOf(0, 2, 2), fixture.catalogStarts.toList())
            } finally { release.countDown() }
            withTimeout(20_000) { update.await() }
            assertFalse(reopened.catalog.value.isCached)
            assertTrue(reopened.catalog.value.isComplete)
            assertEquals(3, reopened.catalog.value.titles.size)
        }
    }

    @Test fun transientRestoreFailureKeepsReadOnlySavedLibraryAndRetryVerifiesSameAccount() = runBlocking(Dispatchers.IO) {
        val failVerification = AtomicBoolean(false)
        Fixture(userResponse = {
            if (failVerification.get()) throw UnknownHostException("Local fixture DNS failure")
            response(200, USER_JSON)
        }).use { fixture ->
            fixture.connect(); failVerification.set(true)
            val reopened = fixture.reopen(); reopened.refreshIfStale()
            assertTrue(reopened.session.value is SessionState.Restoring)
            val pendingScope = (reopened.session.value as SessionState.Restoring).scope
            assertEquals(ContentStatus.ERROR, reopened.catalog.value.status)
            assertTrue(reopened.catalog.value.isCached)
            assertEquals(2, reopened.catalog.value.titles.size)
            assertFalse(reopened.catalog.value.canUseLibrary)
            assertTrue(reopened.catalog.value.message!!.contains("find the server address"))
            assertTrue(reopened.details(id(1)) is OperationResult.Failure)
            failVerification.set(false); reopened.refresh()
            assertEquals(pendingScope, (reopened.session.value as SessionState.Connected).scope)
            assertTrue(reopened.catalog.value.canUseLibrary)
            assertEquals(1, fixture.catalogStarts.size)
        }
    }

    @Test fun authorizationDenialPurgesSavedSessionAndCatalogInsteadOfShowingAnotherAccount() = runBlocking(Dispatchers.IO) {
        val deny = AtomicBoolean(false)
        Fixture(userResponse = { if (deny.get()) response(401, "{}") else response(200, USER_JSON) }).use { fixture ->
            fixture.connect(); deny.set(true)
            val reopened = fixture.reopen(); reopened.refreshIfStale()
            assertTrue(reopened.session.value is SessionState.Failed)
            assertTrue(reopened.catalog.value.titles.isEmpty())
            assertNull(PrivateStorage(fixture.context).loadSession())
            assertNull(CatalogCacheStorage(fixture.context).load(INPUT, USER_ID, "home-v4-release-year"))
        }
    }

    @Test fun mismatchedVerifiedAccountPurgesCacheAndNeverPublishesConnected() = runBlocking(Dispatchers.IO) {
        val mismatch = AtomicBoolean(false)
        Fixture(userResponse = { response(200, if (mismatch.get()) USER_JSON.replace(USER_ID, OTHER_USER_ID) else USER_JSON) }).use { fixture ->
            fixture.connect(); mismatch.set(true)
            val reopened = fixture.reopen(); reopened.refreshIfStale()
            assertTrue(reopened.session.value is SessionState.Failed)
            assertTrue(reopened.catalog.value.titles.isEmpty())
            assertNull(PrivateStorage(fixture.context).loadSession())
            assertNull(CatalogCacheStorage(fixture.context).load(INPUT, USER_ID, "home-v4-release-year"))
        }
    }

    @Test fun signOutDuringRestorePurgesCacheAndRejectsLateVerification() = runBlocking(Dispatchers.IO) {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val block = AtomicBoolean(false)
        Fixture(userResponse = {
            if (block.get()) { entered.countDown(); await(release) }
            response(200, USER_JSON)
        }).use { fixture ->
            fixture.connect(); block.set(true)
            val reopened = fixture.reopen()
            val update = async { reopened.refreshIfStale() }
            try {
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                assertTrue(reopened.session.value is SessionState.Restoring)
                reopened.signOut()
            } finally { release.countDown() }
            try { update.await() } catch (_: CancellationException) { }
            assertEquals(SessionState.SignedOut, reopened.session.value)
            assertTrue(reopened.catalog.value.titles.isEmpty())
            assertNull(PrivateStorage(fixture.context).loadSession())
            assertNull(CatalogCacheStorage(fixture.context).load(INPUT, USER_ID, "home-v4-release-year"))
        }
    }

    @Test fun serviceConfirmedFavoriteUpdatesEncryptedCacheAndSurvivesColdReopen() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.connect()
            assertFalse(fixture.repository.catalog.value.titles.first().favorite)
            val result = fixture.repository.setFavorite(id(1), true)
            assertEquals(OperationResult.Success(true), result)
            assertTrue(fixture.repository.catalog.value.titles.first().favorite)
            val cached = CatalogCacheStorage(fixture.context).load(INPUT, USER_ID, "home-v4-release-year")!!
            assertTrue(cached.titles.first().favorite)
            val reopened = fixture.reopen(); reopened.refreshIfStale()
            assertTrue(reopened.catalog.value.titles.first().favorite)
            assertTrue(reopened.favorites.value[id(1)]!!.confirmed)
        }
    }

    @Test fun expiredCompleteCacheUpdatesInBackgroundButStaysVisibleDuringFirstRequest() = runBlocking(Dispatchers.IO) {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val block = AtomicBoolean(false)
        Fixture(catalogResponse = { request ->
            if (block.get()) { entered.countDown(); await(release) }
            page(start(request), 2, listOf(1, 2))
        }).use { fixture ->
            fixture.connect()
            val storage = CatalogCacheStorage(fixture.context)
            val cache = storage.load(INPUT, USER_ID, "home-v4-release-year")!!
            assertTrue(storage.save(cache.copy(savedAtEpochMs = System.currentTimeMillis() - 11 * 60 * 1_000L)))
            block.set(true)
            val reopened = fixture.reopen()
            val update = async { reopened.refreshIfStale() }
            try {
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                assertEquals(2, reopened.catalog.value.titles.size)
                assertEquals(ContentStatus.READY, reopened.catalog.value.status)
                assertTrue(reopened.catalog.value.isCached)
                assertTrue(reopened.catalog.value.isRefreshing)
                assertEquals(listOf(0, 0), fixture.catalogStarts.toList())
            } finally { release.countDown() }
            withTimeout(20_000) { update.await() }
            assertFalse(reopened.catalog.value.isCached)
            assertFalse(reopened.catalog.value.isRefreshing)
            assertTrue(reopened.catalog.value.isComplete)
        }
    }

    @Test fun firstPageStopsUntilDemandAndConcurrentDemandCoalescesToOneNextRequest() = runBlocking(Dispatchers.IO) {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture(catalogResponse = { request ->
            if (start(request) == 2) { entered.countDown(); await(release) }
            page(start(request), 3, if (start(request) == 0) listOf(1, 2) else listOf(3))
        }).use { fixture ->
            fixture.connect()
            assertEquals(listOf(0), fixture.catalogStarts.toList())
            assertEquals(ContentStatus.READY, fixture.repository.catalog.value.status)
            assertTrue(fixture.repository.catalog.value.hasMore)
            assertFalse(fixture.repository.catalog.value.isComplete)
            val demand = async { fixture.repository.loadMore() }
            try {
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                fixture.repository.loadMore()
                assertEquals(listOf(0, 2), fixture.catalogStarts.toList())
                assertTrue(fixture.repository.catalog.value.isLoadingMore)
            } finally { release.countDown() }
            withTimeout(20_000) { demand.await() }
            assertFalse(fixture.repository.catalog.value.hasMore)
            assertTrue(fixture.repository.catalog.value.isComplete)
            fixture.repository.loadMore()
            assertEquals(2, fixture.catalogStarts.size)
        }
    }

    @Test fun browseSearchAndFavoritesAreRemoteQueriesBeyondTheHomePage() = runBlocking(Dispatchers.IO) {
        Fixture(catalogResponse = { request ->
            if (request.url.queryParameter("isFavorite") == "true") page(0, 1, listOf(99), favorite = true)
            else if (request.url.queryParameter("searchTerm") != null || request.url.queryParameter("genres") != null) page(0, 1, listOf(99))
            else page(0, 2, listOf(1, 2))
        }).use { fixture ->
            fixture.connect()
            val browse = CatalogQuery(Destination.BROWSE, BrowseFilter(MediaKind.SERIES, "Science Fiction", 2020, "Newest"))
            fixture.repository.showCatalog(browse)
            val request = fixture.catalogRequests.last()
            assertEquals(listOf("Series"), request.url.queryParameterValues("includeItemTypes"))
            assertEquals("Science Fiction", request.url.queryParameter("genres"))
            assertEquals("2020", request.url.queryParameter("years"))
            assertEquals("ProductionYear", request.url.queryParameter("sortBy"))
            assertEquals("Descending", request.url.queryParameter("sortOrder"))
            assertEquals(browse, fixture.repository.catalog.value.query)
            assertEquals(id(99), fixture.repository.catalog.value.titles.single().id)
            fixture.repository.showCatalog(CatalogQuery(Destination.SEARCH, search = "Title 99"))
            assertEquals("Title 99", fixture.catalogRequests.last().url.queryParameter("searchTerm"))
            assertEquals(id(99), fixture.repository.catalog.value.titles.single().id)
            fixture.repository.showCatalog(CatalogQuery(Destination.MY_LIST))
            assertEquals("true", fixture.catalogRequests.last().url.queryParameter("isFavorite"))
            assertEquals(id(99), fixture.repository.catalog.value.titles.single().id)
            fixture.repository.showCatalog(CatalogQuery())
            assertEquals(listOf(id(1), id(2)), fixture.repository.catalog.value.titles.map { it.id })
            assertTrue(fixture.repository.catalog.value.isCached)
            assertEquals(4, fixture.catalogRequests.size)
        }
    }

    @Test fun emptySearchMakesNoCatalogRequestAndInitializationNeverFetchesHiddenHomePage() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.connect()
            val reopened = fixture.reopen()
            reopened.initialize()
            assertEquals(1, fixture.catalogRequests.size)
            assertTrue(reopened.session.value is SessionState.Connected)
            reopened.showCatalog(CatalogQuery(Destination.SEARCH, search = "   "))
            assertEquals(1, fixture.catalogRequests.size)
            assertTrue(reopened.catalog.value.titles.isEmpty())
            assertTrue(reopened.catalog.value.isComplete)
            assertFalse(reopened.catalog.value.hasMore)
            assertEquals(0, reopened.catalog.value.totalCount)
        }
    }

    @Test fun changingSearchRejectsLateResponseForPreviousVisibleQuery() = runBlocking(Dispatchers.IO) {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture(catalogResponse = { request ->
            when (request.url.queryParameter("searchTerm")) {
                "old" -> { entered.countDown(); await(release); page(0, 1, listOf(9)) }
                "new" -> page(0, 1, listOf(10))
                else -> page(0, 2, listOf(1, 2))
            }
        }).use { fixture ->
            fixture.connect()
            val old = async { fixture.repository.showCatalog(CatalogQuery(Destination.SEARCH, search = "old")) }
            try {
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                fixture.repository.showCatalog(CatalogQuery(Destination.SEARCH, search = "new"))
                assertEquals(id(10), fixture.repository.catalog.value.titles.single().id)
            } finally { release.countDown() }
            try { old.await() } catch (_: CancellationException) { }
            assertEquals(CatalogQuery(Destination.SEARCH, search = "new"), fixture.repository.catalog.value.query)
            assertEquals(id(10), fixture.repository.catalog.value.titles.single().id)
        }
    }

    @Test fun removingFavoriteInvalidatesSavedQueryCursorSoShiftedRowsCannotBeSkipped() = runBlocking(Dispatchers.IO) {
        val removed = AtomicBoolean(false)
        Fixture(catalogResponse = { request ->
            if (request.url.queryParameter("isFavorite") == "true") {
                if (removed.get()) page(0, 2, listOf(2, 3), favorite = true)
                else page(0, 3, listOf(1, 2), favorite = true)
            } else page(0, 2, listOf(1, 2))
        }).use { fixture ->
            fixture.connect()
            fixture.repository.setFavorite(id(1), true)
            fixture.repository.showCatalog(CatalogQuery(Destination.MY_LIST))
            assertTrue(fixture.repository.catalog.value.hasMore)
            assertEquals(2, fixture.repository.catalog.value.titles.size)
            assertEquals(OperationResult.Success(false), fixture.repository.setFavorite(id(1), false))
            removed.set(true)
            assertEquals(listOf(id(2)), fixture.repository.catalog.value.titles.map { it.id })
            assertNull(fixture.repository.catalog.value.totalCount)
            assertFalse(fixture.repository.catalog.value.isComplete)
            fixture.repository.loadMore()
            assertEquals(listOf(0, 0, 0), fixture.catalogStarts.toList())
            assertEquals(listOf(id(2), id(3)), fixture.repository.catalog.value.titles.map { it.id })
            assertTrue(fixture.repository.catalog.value.isComplete)
        }
    }

    @Test fun retryAfterPartialCacheVerificationDnsFailureKeepsAcceptedCursorWithoutFetchingPageZero() = runBlocking(Dispatchers.IO) {
        val failVerification = AtomicBoolean(false)
        Fixture(userResponse = {
            if (failVerification.get()) throw UnknownHostException("Local fixture DNS failure")
            response(200, USER_JSON)
        }, catalogResponse = { request -> page(start(request), 3, if (start(request) == 0) listOf(1, 2) else listOf(3)) }).use { fixture ->
            fixture.connect(); failVerification.set(true)
            val reopened = fixture.reopen(); reopened.initialize()
            assertTrue(reopened.session.value is SessionState.Restoring)
            assertEquals(listOf(0), fixture.catalogStarts.toList())
            assertFalse(reopened.catalog.value.canUseLibrary)
            failVerification.set(false); reopened.refresh()
            assertTrue(reopened.session.value is SessionState.Connected)
            assertTrue(reopened.catalog.value.canUseLibrary)
            assertEquals(listOf(0), fixture.catalogStarts.toList())
            reopened.loadMore()
            assertEquals(listOf(0, 2), fixture.catalogStarts.toList())
            assertEquals(3, reopened.catalog.value.titles.size)
            assertTrue(reopened.catalog.value.isComplete)
        }
    }

    @Test fun authenticationMakesNoHiddenCatalogRequestBeforeTheVisibleQueryDemandsOne() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.repository.connectPassword(INPUT, "local-fixture", "local-fixture-password")
            assertTrue(fixture.repository.session.value is SessionState.Connected)
            assertTrue(fixture.catalogRequests.isEmpty())
            fixture.repository.showCatalog(CatalogQuery(Destination.SEARCH, search = "visible query"))
            assertEquals(1, fixture.catalogRequests.size)
            assertEquals("visible query", fixture.catalogRequests.single().url.queryParameter("searchTerm"))
            assertEquals(Destination.SEARCH, fixture.repository.catalog.value.query.destination)
        }
    }

    @Test fun switchingUnverifiedCachedAccountToUncachedPageKeepsReadOnlyRetryAndScope() = runBlocking(Dispatchers.IO) {
        val failVerification = AtomicBoolean(false)
        Fixture(userResponse = {
            if (failVerification.get()) throw UnknownHostException("Local fixture DNS failure")
            response(200, USER_JSON)
        }).use { fixture ->
            fixture.connect(); failVerification.set(true)
            val reopened = fixture.reopen(); reopened.initialize()
            val savedScope = (reopened.session.value as SessionState.Restoring).scope
            val search = CatalogQuery(Destination.SEARCH, search = "uncached query")
            reopened.showCatalog(search)
            assertEquals(savedScope, (reopened.session.value as SessionState.Restoring).scope)
            assertEquals(search, reopened.catalog.value.query)
            assertEquals(ContentStatus.ERROR, reopened.catalog.value.status)
            assertFalse(reopened.catalog.value.isConnectionVerified)
            assertFalse(reopened.catalog.value.canUseLibrary)
            assertTrue(reopened.catalog.value.titles.isEmpty())
            assertTrue(reopened.catalog.value.message!!.contains("Reconnect"))
            assertEquals(1, fixture.catalogRequests.size)
            failVerification.set(false); reopened.refresh()
            assertEquals(savedScope, (reopened.session.value as SessionState.Connected).scope)
            assertEquals(search, reopened.catalog.value.query)
            assertTrue(reopened.catalog.value.isConnectionVerified)
            assertEquals(2, fixture.catalogRequests.size)
            assertEquals("uncached query", fixture.catalogRequests.last().url.queryParameter("searchTerm"))
        }
    }

    private class Fixture(
        private val options: CatalogLoadOptions = CatalogLoadOptions(2, 20_000, 0, 0),
        userResponse: () -> InterceptedSdkTransport.FixtureResponse = { response(200, USER_JSON) },
        catalogResponse: (Request) -> InterceptedSdkTransport.FixtureResponse = { request -> page(start(request), 2, listOf(1, 2)) },
    ) : Closeable {
        private val rootContext: Context = ApplicationProvider.getApplicationContext()
        private val directory = File(rootContext.cacheDir, "catalog-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(rootContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        private val scopes = CopyOnWriteArrayList<CoroutineScope>()
        val failures = CopyOnWriteArrayList<Pair<String, Throwable>>()
        val catalogStarts = CopyOnWriteArrayList<Int>()
        val catalogRequests = CopyOnWriteArrayList<Request>()
        val authRequests = AtomicInteger()
        private val favorite = AtomicBoolean(false)
        val transport = InterceptedSdkTransport { request ->
            when (request.url.encodedPath) {
                "/jelly/System/Info/Public" -> response(200, """{"Version":"10.11.0","ServerName":"Local fixture","Id":"fixture-server"}""")
                "/jelly/Users/AuthenticateByName" -> { authRequests.incrementAndGet(); response(200, """{"User":$USER_JSON,"AccessToken":"local-fixture-token"}""") }
                "/jelly/Users/Me" -> userResponse()
                "/jelly/Items" -> { catalogStarts += start(request); catalogRequests += request; catalogResponse(request) }
                "/jelly/UserFavoriteItems/${id(1)}" -> {
                    assertEquals(USER_ID, request.url.queryParameter("userId"))
                    assertTrue(request.method in setOf("POST", "DELETE"))
                    favorite.set(request.method == "POST")
                    response(200, userData(1, favorite.get()))
                }
                "/jelly/Items/${id(1)}" -> {
                    assertEquals(USER_ID, request.url.queryParameter("userId"))
                    assertEquals("GET", request.method)
                    response(200, item(1, favorite.get()))
                }
                else -> throw IOException("Unexpected local fixture endpoint")
            }
        }
        val repository = reopen()
        fun reopen(): JellyfinMediaRepository {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
            return JellyfinMediaRepository(
                context, scope,
                onConnectionDiagnostic = { stage, failure -> if (failure != null) failures += stage to failure },
                apiClientFactory = { base, token -> transport.createApi(context, base, token) },
                catalogLoadOptions = options,
            )
        }
        suspend fun connect() {
            repository.connectPassword(INPUT, "local-fixture", "local-fixture-password")
            repository.showCatalog(CatalogQuery()) // Explicit visible HOME demand in this fixture.
        }
        override fun close() { scopes.forEach { it.cancel() }; transport.close(); directory.deleteRecursively() }
    }

    private companion object {
        const val INPUT = "https://www.mojaloss.stream/jelly"
        const val USER_ID = "00000000-0000-0000-0000-000000000042"
        const val OTHER_USER_ID = "00000000-0000-0000-0000-000000000043"
        const val USER_JSON = """{"Id":"00000000-0000-0000-0000-000000000042","Name":"Local fixture","HasPassword":false,"HasConfiguredPassword":false,"HasConfiguredEasyPassword":false}"""
        fun id(value: Int) = UUID(0, value.toLong()).toString()
        fun start(request: Request) = requireNotNull(request.url.queryParameter("startIndex")).toInt()
        fun response(status: Int, body: String) = InterceptedSdkTransport.FixtureResponse(status, body)
        fun page(start: Int, total: Int, ids: List<Int>, favorite: Boolean = false) = response(200, """{"Items":[${ids.joinToString(",") { item(it, favorite) }}],"TotalRecordCount":$total,"StartIndex":$start}""")
        fun userData(value: Int, favorite: Boolean) = """{"PlaybackPositionTicks":0,"PlayCount":0,"IsFavorite":$favorite,"Played":false,"Key":"fixture-$value","ItemId":"${id(value)}"}"""
        fun item(value: Int, favorite: Boolean = false) = """{"Id":"${id(value)}","Name":"Title $value","Type":"Movie","Genres":["Drama"],"PlayAccess":"Full","RunTimeTicks":54000000000,"ImageTags":{"Primary":"poster$value"},"BackdropImageTags":["backdrop$value"],"UserData":${userData(value, favorite)}}"""
        fun await(latch: CountDownLatch) {
            try { if (!latch.await(20, TimeUnit.SECONDS)) throw IOException("Local fixture timed out") }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Local fixture interrupted") }
        }
    }
}
