package com.mojaloss.core.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojaloss.core.model.*
import kotlinx.coroutines.*
import okhttp3.Request
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
import java.util.concurrent.atomic.AtomicInteger

/** The pinned SDK serializes and decodes real requests; every transport is intercepted before DNS. */
@RunWith(AndroidJUnit4::class)
class HomeShelfDataTest {
    @Test fun groupingEnabledServerKeepsHindiLibraryTitlesAsMoviesInsteadOfBoxSets() = runBlocking(Dispatchers.IO) {
        Fixture { request -> when {
            request.url.queryParameter("searchTerm") == "Hindi" ->
                page(0, 1, listOf(collection(101, "Hindi Movies").replace("BoxSet", "CollectionFolder")))
            request.url.queryParameter("parentId") == id(101) && request.url.queryParameter("collapseBoxSetItems") != "false" ->
                page(0, 1, listOf(collection(999, "Grouped movie collection")))
            else -> null
        } }.use { fixture ->
            fixture.connect()
            val result = success(fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES))
            assertEquals(listOf(id(1), id(2)), result.items.map { it.id })
            assertTrue(result.items.all { it.kind == MediaKind.MOVIE })
            val query = fixture.contentRequests().single()
            assertEquals("false", query.url.queryParameter("collapseBoxSetItems"))
            assertEquals("12", query.url.queryParameter("limit"))
            assertEquals(1, fixture.metadataRequests().size)
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test fun visibleUserLibrariesRecoverHindiAndBothInternationalRowsWithoutScanningMedia() = runBlocking(Dispatchers.IO) {
        Fixture { request -> when {
            request.url.queryParameter("searchTerm") != null -> page(0, 0, emptyList())
            request.url.encodedPath == "/jelly/UserViews" -> page(0, 4, listOf(
                collection(501, "01. Movies - Hindi").replace("BoxSet", "CollectionFolder"),
                collection(502, "Hindi TV Series").replace("BoxSet", "UserView"),
                collection(503, "International Movies").replace("BoxSet", "CollectionFolder"),
                collection(504, "International Series").replace("BoxSet", "UserView"),
            ))
            else -> null
        } }.use { fixture ->
            fixture.connect()
            assertTrue(fixture.itemRequests().isEmpty())
            assertTrue(fixture.requests.none { it.url.encodedPath == "/jelly/UserViews" })
            val kinds = listOf(HomeShelfKind.HINDI_MOVIES, HomeShelfKind.HINDI_SHOWS,
                HomeShelfKind.INTERNATIONAL_MOVIES, HomeShelfKind.INTERNATIONAL_SHOWS)
            kinds.forEachIndexed { index, kind ->
                val result = success(fixture.repository.homeShelf(kind))
                assertEquals(id(501 + index), result.selectedSource!!.id)
                assertEquals(2, result.items.size)
                assertTrue(result.items.all { it.kind == kind.mediaKind })
            }
            assertEquals(2, fixture.metadataRequests().size) // Hindi and International, shared by movie/show.
            val directory = fixture.requests.single { it.url.encodedPath == "/jelly/UserViews" }
            assertEquals("false", directory.url.queryParameter("includeHidden"))
            assertEquals("false", directory.url.queryParameter("includeExternalContent"))
            assertEquals(4, fixture.contentRequests().size)
            assertTrue(fixture.contentRequests().all { it.url.queryParameter("limit") == "12" &&
                it.url.queryParameter("startIndex") == "0" && it.url.queryParameter("collapseBoxSetItems") == "false" })
            val count = fixture.requests.size
            val reopened = fixture.reopen(); reopened.initialize()
            val verified = fixture.requests.size
            assertTrue(reopened.session.value is SessionState.Connected)
            assertEquals(listOf("/jelly/Users/Me"), fixture.requests.drop(count).map { it.url.encodedPath })
            kinds.forEach { kind -> assertEquals(2, success(reopened.homeShelf(kind)).items.size) }
            assertEquals(verified, fixture.requests.size)
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test fun failedCategoryQueryRecoversOnlyThroughAMatchingAuthorizedLibrary() = runBlocking(Dispatchers.IO) {
        for (matching in listOf(true, false)) {
            Fixture { request -> when {
                request.url.queryParameter("searchTerm") == "Hindi" -> InterceptedSdkTransport.FixtureResponse(500, "")
                request.url.encodedPath == "/jelly/UserViews" -> if (matching)
                    page(0, 1, listOf(collection(501, "Hindi Movies").replace("BoxSet", "UserView")))
                    else page(0, 1, listOf(collection(501, "English Movies").replace("BoxSet", "UserView")))
                else -> null
            } }.use { fixture ->
                fixture.connect()
                val result = fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES)
                if (matching) assertEquals(id(501), success(result).selectedSource!!.id)
                else assertTrue(result is OperationResult.Failure)
                assertEquals(1, fixture.requests.count { it.url.encodedPath == "/jelly/UserViews" })
                assertEquals(if (matching) 1 else 0, fixture.contentRequests().size)
                assertEquals(0, fixture.transport.dnsAttempts.get())
            }
        }
    }

    @Test fun broadInternationalUserViewLoadsMoviesAndSeriesButSpecificBranchesWinWhenAvailable() = runBlocking(Dispatchers.IO) {
        for (specific in listOf(false, true)) {
            Fixture { request -> when {
                request.url.queryParameter("searchTerm") == "International" -> page(0, 0, emptyList())
                request.url.encodedPath == "/jelly/UserViews" -> {
                    val libraries = listOf(collection(601, "International").replace("BoxSet", "UserView")) +
                        if (specific) listOf(collection(602, "International Movies").replace("BoxSet", "CollectionFolder"),
                            collection(603, "International Series").replace("BoxSet", "UserView")) else emptyList()
                    page(0, libraries.size, libraries)
                }
                else -> null
            } }.use { fixture ->
                fixture.connect()
                val movies = success(fixture.repository.homeShelf(HomeShelfKind.INTERNATIONAL_MOVIES))
                val series = success(fixture.repository.homeShelf(HomeShelfKind.INTERNATIONAL_SHOWS))
                assertEquals(id(if (specific) 602 else 601), movies.selectedSource!!.id)
                assertEquals(id(if (specific) 603 else 601), series.selectedSource!!.id)
                assertTrue(movies.items.isNotEmpty() && movies.items.all { it.kind == MediaKind.MOVIE })
                assertTrue(series.items.isNotEmpty() && series.items.all { it.kind == MediaKind.SERIES })
                assertEquals(listOf("Movie", "Series"), fixture.contentRequests().map { it.url.queryParameter("includeItemTypes") })
                assertTrue(fixture.contentRequests().all { it.url.queryParameter("limit") == "12" &&
                    it.url.queryParameter("collapseBoxSetItems") == "false" })
                assertEquals(1, fixture.metadataRequests().size)
                assertEquals(1, fixture.requests.count { it.url.encodedPath == "/jelly/UserViews" })
                assertEquals(0, fixture.transport.dnsAttempts.get())
            }
        }
    }

    @Test fun categoryDnsAndAccessFailuresDoNotFanOutIntoAnotherEndpoint() = runBlocking(Dispatchers.IO) {
        for (status in listOf(0, 401, 403, 429)) {
            Fixture { request -> if (request.url.queryParameter("searchTerm") == "Hindi") {
                if (status == 0) throw UnknownHostException("Offline local fixture")
                else InterceptedSdkTransport.FixtureResponse(status, "")
            } else null }.use { fixture ->
                fixture.connect()
                assertTrue(fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES) is OperationResult.Failure)
                assertTrue(fixture.requests.none { it.url.encodedPath == "/jelly/UserViews" })
                assertTrue(fixture.contentRequests().isEmpty())
                assertEquals(0, fixture.transport.dnsAttempts.get())
            }
        }
    }

    @Test fun directoryOverflowIsAnErrorAndUnknownLanguageNeverBecomesInternational() = runBlocking(Dispatchers.IO) {
        for (overflow in listOf(false, true)) {
            Fixture { request -> when {
                request.url.queryParameter("searchTerm") != null -> page(0, 0, emptyList())
                request.url.encodedPath == "/jelly/UserViews" -> if (overflow)
                    page(0, 61, (1..61).map { collection(500 + it, "International Movies").replace("BoxSet", "UserView") })
                    else page(0, 3, listOf(collection(501, "Uncategorized"), collection(502, "French Movies"),
                        collection(503, "English [Hindi audio] Movies")))
                else -> null
            } }.use { fixture ->
                fixture.connect()
                val result = fixture.repository.homeShelf(HomeShelfKind.INTERNATIONAL_MOVIES)
                if (overflow) assertTrue(result is OperationResult.Failure)
                else { assertTrue(success(result).items.isEmpty()); assertNull(success(result).selectedSource) }
                assertTrue(fixture.contentRequests().isEmpty())
                assertEquals(1, fixture.requests.count { it.url.encodedPath == "/jelly/UserViews" })
            }
        }
    }

    @Test fun shelfDemandUsesOnlyMetadata20AndContent12WithReleaseOrderAndNoLanguageGuess() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.connect()
            assertTrue(fixture.itemRequests().isEmpty())
            val page = success(fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES))
            assertEquals(listOf(id(1), id(2)), page.items.map { it.id })
            assertEquals(id(101), page.selectedSource!!.id)
            assertEquals(2, page.nextStart)
            val metadata = fixture.metadataRequests().single()
            assertEquals("20", metadata.url.queryParameter("limit"))
            assertEquals("Hindi", metadata.url.queryParameter("searchTerm"))
            assertEquals(listOf("BoxSet", "CollectionFolder", "Folder"), types(metadata))
            assertEquals("false", metadata.url.queryParameter("enableImages"))
            val content = fixture.contentRequests().single()
            assertEquals("12", content.url.queryParameter("limit"))
            assertEquals("false", content.url.queryParameter("collapseBoxSetItems"))
            assertEquals("Movie", content.url.queryParameter("includeItemTypes"))
            assertEquals(listOf("PremiereDate", "ProductionYear", "SortName"), content.url.queryParameterValues("sortBy"))
            assertEquals(listOf("Descending", "Descending", "Ascending"), content.url.queryParameterValues("sortOrder"))
            assertNull(content.url.queryParameter("audioLanguages"))
            assertNull(content.url.queryParameter("preferredMetadataLanguage"))
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test fun movieAndShowRowsShareOneLanguageMetadataLookupButKeepTypedSources() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.connect()
            val movie = success(fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES))
            val show = success(fixture.repository.homeShelf(HomeShelfKind.HINDI_SHOWS))
            assertEquals(id(101), movie.selectedSource!!.id)
            assertEquals(id(102), show.selectedSource!!.id)
            assertEquals(1, fixture.metadataRequests().size)
            assertEquals(listOf("Movie", "Series"), fixture.contentRequests().map { it.url.queryParameter("includeItemTypes") })
        }
    }

    @Test fun realTrendingKeepsServerMembershipButRequestsNewestReleaseFirst() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.connect()
            val page = success(fixture.repository.homeShelf(HomeShelfKind.TRENDING))
            assertEquals(listOf(id(9), id(3)), page.items.map { it.id })
            val request = fixture.contentRequests().single()
            assertEquals(listOf("Movie", "Series"), types(request))
            assertEquals(listOf("PremiereDate", "ProductionYear", "SortName"), request.url.queryParameterValues("sortBy"))
            assertEquals(listOf("Descending", "Descending", "Ascending"), request.url.queryParameterValues("sortOrder"))
            assertEquals("Trending", fixture.metadataRequests().single().url.queryParameter("searchTerm"))
        }
    }

    @Test fun missingActualTrendingSourceNeverFallsBackToPopularOrRecentTitles() = runBlocking(Dispatchers.IO) {
        Fixture { request ->
            if (request.url.queryParameter("searchTerm") == "Trending") page(0, 1, listOf(collection(900, "Popular"))) else null
        }.use { fixture ->
            fixture.connect()
            val page = success(fixture.repository.homeShelf(HomeShelfKind.TRENDING))
            assertTrue(page.items.isEmpty()); assertTrue(page.sources.isEmpty()); assertNull(page.selectedSource)
            assertTrue(fixture.contentRequests().isEmpty())
        }
    }

    @Test fun multipleSourcesAutomaticallyLoadCanonicalBranchAndAnUnverifiedSourceCannotBeQueried() = runBlocking(Dispatchers.IO) {
        Fixture { request ->
            if (request.url.queryParameter("searchTerm") == "Hindi") page(0, 3,
                listOf(collection(103, "Hindi Films"), collection(104, "Hindi"), collection(101, "Hindi Movies"))) else null
        }.use { fixture ->
            fixture.connect()
            val automatic = success(fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES))
            assertEquals(listOf(id(103), id(104), id(101)), automatic.sources.map { it.id })
            assertEquals(listOf(id(1), id(2)), automatic.items.map { it.id })
            assertEquals(id(101), automatic.selectedSource!!.id)
            assertEquals(1, fixture.contentRequests().size)
            assertEquals(id(101), fixture.contentRequests().single().url.queryParameter("parentId"))
            val removed = fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES, sourceId = id(999))
            assertTrue(removed is OperationResult.Failure)
            assertEquals(OperationRecovery.RESET_SOURCE, (removed as OperationResult.Failure).recovery)
            assertEquals(1, fixture.contentRequests().size)
            val chosen = success(fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES, sourceId = id(101)))
            assertEquals(id(101), chosen.selectedSource!!.id)
            assertEquals(1, fixture.contentRequests().size)
            assertEquals(1, fixture.metadataRequests().size)
        }
    }

    @Test fun automaticSourceStaysStableAndPagesWithoutCategorySelection() = runBlocking(Dispatchers.IO) {
        val metadata = AtomicInteger()
        Fixture { request ->
            if (request.url.queryParameter("searchTerm") == "Hindi") {
                val sources = listOf(collection(101, "Hindi Movies"), collection(103, "Hindi Films"))
                page(0, 2, if (metadata.getAndIncrement() == 0) sources.reversed() else sources)
            } else null
        }.use { fixture ->
            fixture.connect()
            val first = success(fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES))
            val second = success(fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES, startIndex = first.nextStart!!))
            assertEquals(first.selectedSource, second.selectedSource)
            assertEquals(listOf(id(3), id(4)), second.items.map { it.id })
            val refreshed = success(fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES, force = true))
            assertEquals(first.selectedSource, refreshed.selectedSource)
            assertTrue(fixture.contentRequests().all { it.url.queryParameter("parentId") == id(101) })
            assertEquals(listOf("0", "2", "0"), fixture.contentRequests().map { it.url.queryParameter("startIndex") })
        }
    }

    @Test fun eachNearEndDemandFetchesOneActualRawCursorWithoutWalkingTheRemainingCatalog() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.connect()
            val first = success(fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES))
            assertEquals(2, first.nextStart); assertEquals(1, fixture.contentRequests().size)
            val second = success(fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES, startIndex = first.nextStart!!))
            assertEquals(4, second.nextStart); assertEquals(2, fixture.contentRequests().size)
            val last = success(fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES, startIndex = second.nextStart!!))
            assertNull(last.nextStart); assertEquals(listOf(id(5)), last.items.map { it.id })
            assertEquals(listOf("0", "2", "4"), fixture.contentRequests().map { it.url.queryParameter("startIndex") })
            assertTrue(fixture.contentRequests().all { it.url.queryParameter("limit") == "12" })
        }
    }

    @Test fun repeatedOrChangedLaterPagesFailLocallyAndCannotReplaceTheRecentCatalog() = runBlocking(Dispatchers.IO) {
        Fixture { request ->
            if (request.url.queryParameter("parentId") == id(101) && request.url.queryParameter("startIndex") == "2")
                page(2, 5, listOf(movie(1), movie(4))) else null
        }.use { fixture ->
            fixture.connect(); fixture.repository.showCatalog(CatalogQuery())
            val recent = fixture.repository.catalog.value
            success(fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES))
            val drifted = fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES, startIndex = 2)
            assertTrue(drifted is OperationResult.Failure)
            assertEquals(OperationRecovery.RESTART_PAGING, (drifted as OperationResult.Failure).recovery)
            assertEquals(recent, fixture.repository.catalog.value)
            assertEquals(2, fixture.contentRequests().size)
        }
    }

    @Test fun failedShelfRequestPreservesVerifiedRecentTitlesAndIsNotCachedAsEmptySuccess() = runBlocking(Dispatchers.IO) {
        val failures = AtomicInteger()
        Fixture { request ->
            if (request.url.queryParameter("parentId") == id(101) && failures.getAndIncrement() == 0)
                throw UnknownHostException("Offline fixture")
            null
        }.use { fixture ->
            fixture.connect(); fixture.repository.showCatalog(CatalogQuery())
            val recent = fixture.repository.catalog.value
            val offline = fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES)
            assertTrue(offline is OperationResult.Failure)
            assertEquals(OperationRecovery.RETRY, (offline as OperationResult.Failure).recovery)
            assertEquals(recent, fixture.repository.catalog.value)
            assertFalse(success(fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES)).items.isEmpty())
            assertEquals(recent, fixture.repository.catalog.value)
            assertEquals(1, fixture.metadataRequests().size)
            assertEquals(2, fixture.contentRequests().size)
        }
    }

    @Test fun freshScopedEncryptedShelfReopensWithoutMetadataOrItemRefetchAndReusesSeeAllQuery() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.connect()
            val first = success(fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES))
            val query = CatalogQuery(Destination.BROWSE, BrowseFilter(kind = MediaKind.MOVIE, sort = "Release newest"), parentId = id(101))
            assertEquals(first.items.map { it.id }, fixture.repository.cachedCatalog(query)!!.titles.map { it.id })
            val reopened = fixture.reopen(); reopened.initialize()
            val saved = success(reopened.homeShelf(HomeShelfKind.HINDI_MOVIES))
            assertEquals(first.items.map { it.id }, saved.items.map { it.id })
            assertTrue(saved.items.all { it.artworkHeaders["X-Emby-Token"] == "local-fixture-token" })
            assertEquals(1, fixture.metadataRequests().size); assertEquals(1, fixture.contentRequests().size)
            reopened.showCatalog(query)
            assertEquals(first.items.map { it.id }, reopened.catalog.value.titles.map { it.id })
            assertEquals(1, fixture.contentRequests().size)
            assertTrue(reopened.catalog.value.hasMore)
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test fun contradictoryCollectionIdsAreNotSilentlyTurnedIntoPlatformOrLanguageMembership() = runBlocking(Dispatchers.IO) {
        Fixture { request ->
            if (types(request).contains("BoxSet")) page(0, 2, listOf(collection(101, "Hindi Movies"), collection(101, "Hindi Shows"))) else null
        }.use { fixture ->
            fixture.connect()
            assertTrue(success(fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES)).sources.isEmpty())
            assertTrue(success(fixture.repository.platformCollections()).isEmpty())
            assertTrue(fixture.contentRequests().isEmpty())
        }
    }

    @Test fun atMostTwoShelfReadsRunTogetherAndSignOutClearsTheirScopedResults() = runBlocking(Dispatchers.IO) {
        val active = AtomicInteger(); val maximum = AtomicInteger(); val entered = CountDownLatch(2); val release = CountDownLatch(1)
        Fixture { request ->
            if (request.url.queryParameter("parentId") != null) {
                val count = active.incrementAndGet(); maximum.updateAndGet { old -> maxOf(old, count) }; entered.countDown()
                try { await(release) } finally { active.decrementAndGet() }
            }
            null
        }.use { fixture ->
            fixture.connect()
            val jobs = listOf(HomeShelfKind.HINDI_MOVIES, HomeShelfKind.ENGLISH_MOVIES, HomeShelfKind.INTERNATIONAL_MOVIES)
                .map { kind -> async { fixture.repository.homeShelf(kind) } }
            try {
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                assertEquals(2, maximum.get())
                assertEquals(2, fixture.contentRequests().size)
            } finally { release.countDown() }
            withTimeout(20_000) { jobs.awaitAll() }
            assertEquals(2, maximum.get())
            fixture.repository.signOut()
            assertNull(fixture.repository.cachedCatalog(CatalogQuery(Destination.BROWSE, BrowseFilter(kind = MediaKind.MOVIE, sort = "Release newest"), parentId = id(101))))
            assertTrue(fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES) is OperationResult.Failure)
        }
    }

    @Test fun signOutCancelsPendingShelfAndDiscardsLateContentAndSourceCache() = runBlocking(Dispatchers.IO) {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture { request ->
            if (request.url.queryParameter("parentId") == id(101)) { entered.countDown(); await(release) }
            null
        }.use { fixture ->
            fixture.connect()
            val pending = async { fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES) }
            try {
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                fixture.repository.signOut()
            } finally { release.countDown() }
            try { pending.await() } catch (_: CancellationException) { }
            assertEquals(SessionState.SignedOut, fixture.repository.session.value)
            assertNull(CatalogCacheStorage(fixture.context).load(INPUT, USER_ID, "home-shelf-sources-v1|Hindi"))
            assertTrue(fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES) is OperationResult.Failure)
            assertNull(fixture.repository.cachedCatalog(CatalogQuery(Destination.BROWSE, BrowseFilter(kind = MediaKind.MOVIE, sort = "Release newest"), parentId = id(101))))
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test fun catalogMetadataResumeDoNotInventAConfirmedPlaybackReadback() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.connect(); fixture.repository.showCatalog(CatalogQuery())
            success(fixture.repository.homeShelf(HomeShelfKind.HINDI_MOVIES))
            assertNull(fixture.repository.confirmedProgress(id(1)))
            fixture.repository.signOut()
            assertNull(fixture.repository.confirmedProgress(id(1)))
        }
    }

    private class Fixture(private val extra: (Request) -> InterceptedSdkTransport.FixtureResponse? = { null }) : Closeable {
        private val root: Context = ApplicationProvider.getApplicationContext()
        private val directory = File(root.cacheDir, "shelf-data-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(root) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        private val scopes = CopyOnWriteArrayList<CoroutineScope>()
        val requests = CopyOnWriteArrayList<Request>()
        val transport = InterceptedSdkTransport { request ->
            requests += request
            extra(request) ?: when (request.url.encodedPath) {
                "/jelly/System/Info/Public" -> response("""{"Version":"10.11.0","Id":"offline-fixture"}""")
                "/jelly/Users/AuthenticateByName" -> response("""{"User":$USER_JSON,"AccessToken":"local-fixture-token"}""")
                "/jelly/Users/Me" -> response(USER_JSON)
                "/jelly/UserViews" -> page(0, 0, emptyList())
                "/jelly/Items" -> when {
                    request.url.queryParameter("searchTerm") != null -> {
                        val term = request.url.queryParameter("searchTerm")!!
                        val base = when (term) { "Hindi" -> 101; "English" -> 201; "International" -> 301; else -> 401 }
                        page(0, if (term == "Trending") 1 else 2,
                            if (term == "Trending") listOf(collection(base, "Trending"))
                            else listOf(collection(base, "$term Movies"), collection(base + 1, "$term Shows")))
                    }
                    request.url.queryParameter("parentId") != null -> {
                        val start = request.url.queryParameter("startIndex")!!.toInt()
                        if (request.url.queryParameter("parentId") == id(401)) page(0, 2, listOf(movie(9), movie(3)))
                        else page(start, 5, (start + 1..minOf(start + 2, 5)).map { movie(it, if (types(request) == listOf("Series")) "Series" else "Movie") })
                    }
                    else -> page(0, 2, listOf(movie(8001), movie(8002)))
                }
                else -> throw IOException("Unexpected offline SDK endpoint")
            }
        }
        val repository = reopen()
        fun reopen(): JellyfinMediaRepository {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
            return JellyfinMediaRepository(context, scope, apiClientFactory = { base, token -> transport.createApi(context, base, token) },
                catalogLoadOptions = CatalogLoadOptions(24, 20_000, 0, 0))
        }
        suspend fun connect() { repository.connectPassword(INPUT, "fixture", "fixture-password"); assertTrue(repository.session.value is SessionState.Connected) }
        fun itemRequests() = requests.filter { it.url.encodedPath == "/jelly/Items" }
        fun metadataRequests() = itemRequests().filter { it.url.queryParameter("searchTerm") != null }
        fun contentRequests() = itemRequests().filter { it.url.queryParameter("parentId") != null }
        override fun close() { scopes.forEach { it.cancel() }; transport.close(); directory.deleteRecursively() }
    }

    private companion object {
        const val INPUT = "https://www.mojaloss.stream/jelly"
        const val USER_ID = "00000000-0000-0000-0000-000000000042"
        const val USER_JSON = """{"Id":"00000000-0000-0000-0000-000000000042","Name":"Fixture","HasPassword":false,"HasConfiguredPassword":false,"HasConfiguredEasyPassword":false}"""
        fun id(value: Int) = UUID(0, value.toLong()).toString()
        fun types(request: Request) = request.url.queryParameterValues("includeItemTypes")
        fun response(body: String) = InterceptedSdkTransport.FixtureResponse(200, body)
        fun page(start: Int, total: Int, items: List<String>) = response("""{"Items":[${items.joinToString(",")}],"StartIndex":$start,"TotalRecordCount":$total}""")
        fun collection(number: Int, name: String) = """{"Id":"${id(number)}","Name":"$name","Type":"BoxSet"}"""
        fun movie(number: Int, type: String = "Movie") = """{"Id":"${id(number)}","Name":"Title $number","Type":"$type","PlayAccess":"Full","RunTimeTicks":54000000000,"ImageTags":{"Primary":"poster$number"},"UserData":{"PlaybackPositionTicks":0,"PlayCount":0,"IsFavorite":false,"Played":false,"Key":"fixture$number","ItemId":"${id(number)}"}}"""
        fun <T> success(result: OperationResult<T>): T = when (result) {
            is OperationResult.Success -> result.value
            is OperationResult.Failure -> throw AssertionError(result.message)
        }
        fun await(latch: CountDownLatch) {
            try { if (!latch.await(20, TimeUnit.SECONDS)) throw IOException("Offline fixture timed out") }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Offline fixture interrupted") }
        }
    }
}
