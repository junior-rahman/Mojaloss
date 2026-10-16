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
import java.util.concurrent.atomic.AtomicBoolean

/** Actual pinned SDK and encrypted storage; the interceptor answers before DNS/socket work. */
@RunWith(AndroidJUnit4::class)
class HomeDiscoveryDataTest {
    @Test fun collectionGroupingCannotReplaceRecentHeroOrSeeAllTitleCardsWithBoxSets() = runBlocking(Dispatchers.IO) {
        Fixture(extraResponse = { request ->
            if (request.url.encodedPath == "/jelly/Items" && request.url.queryParameter("collapseBoxSetItems") != "false")
                page(0, 1, listOf(collection(7999, "Grouping enabled on server"))) else null
        }).use { fixture ->
            fixture.connect()
            val recent = success(fixture.repository.homeRecentUploads())
            assertFalse(recent.isEmpty())
            val queries = listOf(CatalogQuery(), CatalogQuery(Destination.BROWSE),
                CatalogQuery(Destination.BROWSE, BrowseFilter(sort = "Recently added")),
                CatalogQuery(Destination.SEARCH, search = "Movie"), CatalogQuery(Destination.MY_LIST),
                CatalogQuery(Destination.BROWSE, parentId = id(7001)))
            queries.forEach { query ->
                fixture.repository.showCatalog(query)
                assertEquals(ContentStatus.READY, fixture.repository.catalog.value.status)
                assertFalse(fixture.repository.catalog.value.titles.isEmpty())
                assertEquals("false", fixture.requests.last().url.queryParameter("collapseBoxSetItems"))
            }
            val reads = fixture.requests.filter { it.url.encodedPath == "/jelly/Items" }
            assertEquals(7, reads.size)
            assertTrue(reads.all { it.url.queryParameter("collapseBoxSetItems") == "false" &&
                it.url.queryParameter("limit")!!.toInt() <= 24 })
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test fun explicitAlphabeticalAndUploadChoicesSurviveNormalizationButInvalidLegacyDefaultsBecomeReleaseFirst() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.connect()
            fixture.repository.showCatalog(CatalogQuery(filter = BrowseFilter(sort = "Title")))
            assertEquals(listOf("SortName"), fixture.requests.last().url.queryParameterValues("sortBy"))
            assertEquals(listOf("Ascending"), fixture.requests.last().url.queryParameterValues("sortOrder"))
            assertEquals("Title", fixture.repository.catalog.value.query.filter.sort)
            val uploads = CatalogQuery(Destination.BROWSE, BrowseFilter(sort = "Recently added"))
            fixture.repository.showCatalog(uploads)
            assertEquals(listOf("DateCreated", "SortName"), fixture.requests.last().url.queryParameterValues("sortBy"))
            assertEquals(listOf("Descending", "Ascending"), fixture.requests.last().url.queryParameterValues("sortOrder"))
            assertEquals(uploads, fixture.repository.catalog.value.query)
            assertNotNull(fixture.repository.cachedCatalog(uploads))
            for (legacy in listOf("Newest", "Server order", "unknown order")) {
                fixture.repository.showCatalog(CatalogQuery(Destination.SEARCH, BrowseFilter(sort = legacy), search = legacy))
                assertEquals("Release newest", fixture.repository.catalog.value.query.filter.sort)
                assertEquals(listOf("PremiereDate", "ProductionYear", "SortName"), fixture.requests.last().url.queryParameterValues("sortBy"))
                assertEquals(listOf("Descending", "Descending", "Ascending"), fixture.requests.last().url.queryParameterValues("sortOrder"))
            }
            fixture.repository.showCatalog(CatalogQuery())
            assertEquals(listOf("PremiereDate", "ProductionYear", "SortName"), fixture.requests.last().url.queryParameterValues("sortBy"))
            assertEquals("Release newest", fixture.repository.catalog.value.query.filter.sort)
        }
    }

    @Test fun recentlyAddedSeeAllPagesByRawCursorWithoutReplacingReleaseFirstHeroCache() = runBlocking(Dispatchers.IO) {
        Fixture(extraResponse = { request ->
            if (request.url.encodedPath == "/jelly/Items" && request.url.queryParameterValues("sortBy").firstOrNull() == "DateCreated" &&
                request.url.queryParameter("limit") == "24") {
                val start = request.url.queryParameter("startIndex")!!.toInt()
                page(start, 4, (start + 1..minOf(start + 2, 4)).map { movie(400 + it) })
            } else null
        }).use { fixture ->
            fixture.connect(); fixture.repository.showCatalog(CatalogQuery())
            val hero = fixture.repository.catalog.value
            val uploads = CatalogQuery(Destination.BROWSE, BrowseFilter(sort = "Recently added"))
            fixture.repository.showCatalog(uploads)
            assertEquals(listOf(id(401), id(402)), fixture.repository.catalog.value.titles.map { it.id })
            assertTrue(fixture.repository.catalog.value.hasMore)
            fixture.repository.loadMore()
            assertEquals(listOf(id(401), id(402), id(403), id(404)), fixture.repository.catalog.value.titles.map { it.id })
            assertTrue(fixture.repository.catalog.value.isComplete)
            val requests = fixture.requests.filter { it.url.queryParameterValues("sortBy").firstOrNull() == "DateCreated" }
            assertEquals(listOf("0", "2"), requests.map { it.url.queryParameter("startIndex") })
            assertTrue(requests.all { it.url.queryParameter("limit") == "24" })
            assertEquals(hero.titles, fixture.repository.cachedCatalog(CatalogQuery())!!.titles)
            val count = fixture.requests.size
            fixture.repository.showCatalog(CatalogQuery())
            assertEquals(hero.titles, fixture.repository.catalog.value.titles)
            assertEquals(count, fixture.requests.size)
        }
    }

    @Test fun allVisibleTitleDestinationsAndPlatformQueriesDefaultToReleaseNewest() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.connect()
            val queries = listOf(CatalogQuery(), CatalogQuery(Destination.BROWSE),
                CatalogQuery(Destination.SEARCH, search = "New releases"), CatalogQuery(Destination.MY_LIST),
                CatalogQuery(Destination.BROWSE, parentId = id(7001)))
            queries.forEach { query ->
                fixture.repository.showCatalog(query)
                val request = fixture.requests.last()
                assertEquals(listOf("PremiereDate", "ProductionYear", "SortName"), request.url.queryParameterValues("sortBy"))
                assertEquals(listOf("Descending", "Descending", "Ascending"), request.url.queryParameterValues("sortOrder"))
                assertEquals("24", request.url.queryParameter("limit"))
            }
        }
    }

    @Test fun bottomRecentlyAddedIsIndependentTwelveItemDateCreatedDemandWithScopedCache() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.connect(); assertTrue(fixture.requests.none { it.url.encodedPath == "/jelly/Items" })
            fixture.repository.showCatalog(CatalogQuery())
            val original = fixture.repository.catalog.value
            val first = success(fixture.repository.homeRecentUploads())
            val request = fixture.requests.last()
            assertEquals("12", request.url.queryParameter("limit"))
            assertEquals(listOf("DateCreated", "SortName"), request.url.queryParameterValues("sortBy"))
            assertEquals(listOf("Descending", "Ascending"), request.url.queryParameterValues("sortOrder"))
            assertEquals("false", request.url.queryParameter("enableTotalRecordCount"))
            assertTrue(first.size <= 12); assertEquals(original, fixture.repository.catalog.value)
            val count = fixture.requests.size
            success(fixture.repository.homeRecentUploads()); assertEquals(count, fixture.requests.size)
            success(fixture.repository.homeRecentUploads(force = true)); assertEquals(count + 1, fixture.requests.size)
            val reopened = fixture.reopen(); reopened.initialize()
            val verifiedCount = fixture.requests.size
            assertEquals(first.map { it.id }, success(reopened.homeRecentUploads()).map { it.id })
            assertEquals(verifiedCount, fixture.requests.size)
            reopened.signOut(); assertTrue(reopened.homeRecentUploads() is OperationResult.Failure)
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test fun recentPreviewDeduplicatesIdenticalBoundedRowsWithDisabledTotalCount() = runBlocking(Dispatchers.IO) {
        Fixture(extraResponse = { request ->
            if (request.url.encodedPath == "/jelly/Items" && request.url.queryParameter("limit") == "12")
                // A count-disabled Jellyfin QueryResult still contains the SDK's required
                // integer fields. Zero total is not a cursor/completeness claim for this row.
                response("""{"Items":[${movie(701)},${movie(701)},${movie(702)}],"TotalRecordCount":0,"StartIndex":0}""") else null
        }).use { fixture ->
            fixture.connect(); fixture.repository.showCatalog(CatalogQuery())
            val hero = fixture.repository.catalog.value
            assertEquals(listOf(id(701), id(702)), success(fixture.repository.homeRecentUploads()).map { it.id })
            assertEquals(hero, fixture.repository.catalog.value)
            assertEquals(1, fixture.requests.count { it.url.queryParameter("limit") == "12" })
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test fun recentPreviewRejectsWrongTypesOffsetsOversizedAndConflictingRowsButRetainsAcceptedCache() = runBlocking(Dispatchers.IO) {
        for ((index, invalid) in listOf(
            """{"Items":[${season(1)}],"TotalRecordCount":0,"StartIndex":0}""",
            """{"Items":[${movie(702)}],"TotalRecordCount":0,"StartIndex":12}""",
            """{"Items":[${(1..13).joinToString(",") { movie(it) }}],"TotalRecordCount":0,"StartIndex":0}""",
            """{"Items":[${movie(702)},${movie(702).replace("Movie 702", "Contradictory title")}],"TotalRecordCount":0,"StartIndex":0}""",
            // Missing required SDK schema fields fails closed before app preview validation.
            """{"Items":[${movie(702)}]}""",
        ).withIndex()) {
            val bad = AtomicBoolean(false)
            Fixture(extraResponse = { request ->
                if (request.url.encodedPath == "/jelly/Items" && request.url.queryParameter("limit") == "12" && bad.get()) response(invalid) else null
            }).use { fixture ->
                fixture.connect(); fixture.repository.showCatalog(CatalogQuery())
                val hero = fixture.repository.catalog.value
                val saved = success(fixture.repository.homeRecentUploads())
                bad.set(true)
                val failure = fixture.repository.homeRecentUploads(force = true)
                assertTrue(failure is OperationResult.Failure)
                assertEquals("The server returned section data this app could not read. Retry, or copy the error report.",
                    (failure as OperationResult.Failure).message)
                assertEquals("home_recent_uploads", fixture.failures.last().first)
                if (index < 4) assertTrue(fixture.failures.last().second is IllegalArgumentException)
                assertEquals(saved, success(fixture.repository.homeRecentUploads()))
                assertEquals(hero, fixture.repository.catalog.value)
            }
        }
    }

    @Test fun recentNetworkFailureRecordsItsFixedDiagnosticStageWithoutReplacingTheHero() = runBlocking(Dispatchers.IO) {
        Fixture(extraResponse = { request ->
            if (request.url.encodedPath == "/jelly/Items" && request.url.queryParameter("limit") == "12")
                throw UnknownHostException("Private fixture text must not become a user-facing error")
            null
        }).use { fixture ->
            fixture.connect(); fixture.repository.showCatalog(CatalogQuery())
            val hero = fixture.repository.catalog.value
            val failure = fixture.repository.homeRecentUploads()
            assertTrue(failure is OperationResult.Failure)
            assertEquals("Could not update Recently added. Try again.", (failure as OperationResult.Failure).message)
            assertEquals("home_recent_uploads", fixture.failures.single().first)
            assertEquals(hero, fixture.repository.catalog.value)
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test fun recentSavedPreviewSurvivesRestoringWithoutAuthenticatedArtOrNetworkRead() = runBlocking(Dispatchers.IO) {
        val block = AtomicBoolean(false); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture(extraResponse = { request ->
            if (request.url.encodedPath == "/jelly/Users/Me" && block.get()) { entered.countDown(); await(release) }
            null
        }).use { fixture ->
            fixture.connect()
            val saved = success(fixture.repository.homeRecentUploads())
            block.set(true)
            val reopened = fixture.reopen(); val initialization = async { reopened.initialize() }
            try {
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                val count = fixture.requests.size
                val restoring = success(reopened.homeRecentUploads())
                assertEquals(saved.map { it.id }, restoring.map { it.id })
                assertTrue(restoring.all { !it.isPlayable && it.artworkHeaders.isEmpty() && it.posterUrl == null && it.backdropUrl == null })
                assertEquals(count, fixture.requests.size)
            } finally { release.countDown() }
            withTimeout(20_000) { initialization.await() }
            assertTrue(success(reopened.homeRecentUploads()).all { it.artworkHeaders.isNotEmpty() })
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test fun releasePageUsesActualPremiereDateAndYearFallbackWithoutUploadDate() = runBlocking(Dispatchers.IO) {
        Fixture(extraResponse = { request ->
            if (request.url.encodedPath == "/jelly/Items" && request.url.queryParameter("limit") == "24") page(0, 4, listOf(
                movie(801).dropLast(1) + """, "PremiereDate":"2020-02-01T00:00:00Z","ProductionYear":2020,"DateCreated":"2026-10-16T00:00:00Z"}""",
                movie(802).dropLast(1) + """, "ProductionYear":2026}""",
                movie(803).dropLast(1) + """, "PremiereDate":"2026-09-03T00:00:00Z"}""",
                movie(804),
            )) else null
        }).use { fixture ->
            fixture.connect(); fixture.repository.showCatalog(CatalogQuery())
            assertEquals(listOf(id(803), id(802), id(801), id(804)), fixture.repository.catalog.value.titles.map { it.id })
            assertEquals(2026, fixture.repository.catalog.value.titles.first().year)
            val query = fixture.requests.last()
            assertEquals(listOf("PremiereDate", "ProductionYear", "SortName"), query.url.queryParameterValues("sortBy"))
            assertEquals(listOf("Descending", "Descending", "Ascending"), query.url.queryParameterValues("sortOrder"))
            assertFalse(query.url.queryParameterValues("sortBy").contains("DateCreated"))
            assertEquals(1, fixture.requests.count { it.url.encodedPath == "/jelly/Items" })
        }
    }

    @Test fun previousUploadOrderedHomeCacheCannotMasqueradeAsReleaseFirstHero() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.connect()
            val storage = CatalogCacheStorage(fixture.context)
            assertTrue(storage.save(CachedCatalog(INPUT, USER_ID, "Fixture", System.currentTimeMillis(), true,
                listOf(CachedCatalogTitle(id(888), "Upload ordered title")), queryKey = "home-v2-recent", totalCount = 1, nextStart = 1)))
            assertTrue(storage.save(CachedCatalog(INPUT, USER_ID, "Fixture", System.currentTimeMillis(), true,
                listOf(CachedCatalogTitle(id(889), "Legacy release-only order")), queryKey = "home-v3-release", totalCount = 1, nextStart = 1)))
            val reopened = fixture.reopen(); reopened.initialize()
            assertTrue(reopened.catalog.value.titles.isEmpty())
            reopened.showCatalog(CatalogQuery())
            assertFalse(reopened.catalog.value.titles.any { it.id == id(888) })
            assertFalse(reopened.catalog.value.titles.any { it.id == id(889) })
            assertNotNull(storage.load(INPUT, USER_ID, "home-v4-release-year"))
            assertNull(storage.load(INPUT, USER_ID, "home-v2-recent"))
            assertNull(storage.load(INPUT, USER_ID, "home-v3-release"))
        }
    }

    @Test fun authenticationDoesNotFetchHomeResumeCollectionsGenresOrSeasons() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.connect()
            assertTrue(fixture.requests.none { it.url.encodedPath in setOf("/jelly/Items", "/jelly/UserItems/Resume", "/jelly/Genres") })
            fixture.repository.showCatalog(CatalogQuery())
            val request = fixture.requests.last()
            assertEquals("/jelly/Items", request.url.encodedPath)
            assertEquals(listOf("PremiereDate", "ProductionYear", "SortName"), request.url.queryParameterValues("sortBy"))
            assertEquals(listOf("Descending", "Descending", "Ascending"), request.url.queryParameterValues("sortOrder"))
            assertEquals("24", request.url.queryParameter("limit"))
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test fun resumeOutsideTheRecentPageHasItsOwnBoundedCachedDemandAndForceRetry() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.connect(); fixture.repository.showCatalog(CatalogQuery())
            val first = success(fixture.repository.homeResume())
            assertEquals(listOf(id(777)), first.map { it.id })
            assertFalse(fixture.repository.catalog.value.titles.any { it.id == id(777) })
            val request = fixture.requests.single { it.url.encodedPath == "/jelly/UserItems/Resume" }
            assertEquals("12", request.url.queryParameter("limit"))
            assertEquals(listOf("Movie", "Episode"), request.url.queryParameterValues("includeItemTypes"))
            fixture.repository.homeResume()
            assertEquals(1, fixture.resumeRequests())
            fixture.repository.homeResume(force = true)
            assertEquals(2, fixture.resumeRequests())
            val search = CatalogQuery(Destination.SEARCH, search = "Independent query")
            fixture.repository.showCatalog(search)
            fixture.repository.homeResume()
            assertEquals(search, fixture.repository.catalog.value.query)
            assertEquals(2, fixture.resumeRequests())
        }
    }

    @Test fun freshEncryptedResumeSidecarReopensOnlyAfterVerifiedAccountWithoutRefetch() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.connect(); success(fixture.repository.homeResume())
            val cached = CatalogCacheStorage(fixture.context).load(INPUT, USER_ID, "home-resume-v1|")!!
            assertEquals(listOf(id(777)), cached.titles.map { it.id })
            val reopened = fixture.reopen(); reopened.initialize()
            assertTrue(reopened.session.value is SessionState.Connected)
            val result = success(reopened.homeResume())
            assertEquals(id(777), result.single().id)
            assertTrue(result.single().artworkHeaders.isNotEmpty())
            assertEquals(1, fixture.resumeRequests())
            reopened.signOut()
            assertNull(CatalogCacheStorage(fixture.context).load(INPUT, USER_ID, "home-resume-v1|"))
            assertTrue(reopened.homeResume() is OperationResult.Failure)
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test fun restoringResumeAndRememberedQueryRemainReadOnlyThenRebindAfterVerification() = runBlocking(Dispatchers.IO) {
        val block = AtomicBoolean(false); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture(extraResponse = { request ->
            if (request.url.encodedPath == "/jelly/Users/Me" && block.get()) { entered.countDown(); await(release) }
            null
        }).use { fixture ->
            fixture.connect()
            val home = CatalogQuery(); val search = CatalogQuery(Destination.SEARCH, search = "Remembered query")
            fixture.repository.showCatalog(search); fixture.repository.showCatalog(home); fixture.repository.homeResume()
            block.set(true)
            val reopened = fixture.reopen(); val initialization = async { reopened.initialize() }
            var selection: Deferred<Unit>? = null
            try {
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                val savedResume = success(reopened.homeResume())
                assertTrue(savedResume.all { !it.isPlayable && it.artworkHeaders.isEmpty() && it.posterUrl == null && it.backdropUrl == null })
                selection = async { reopened.showCatalog(search) }
                withTimeout(20_000) { while (reopened.catalog.value.query != search) delay(1) }
                val before = reopened.cachedCatalog(home)!!
                assertFalse(before.isConnectionVerified)
                assertTrue(before.titles.all { it.artworkHeaders.isEmpty() && it.posterUrl == null })
            } finally { release.countDown() }
            withTimeout(20_000) { initialization.await(); selection?.await() }
            val after = reopened.cachedCatalog(home)!!
            assertTrue(after.isConnectionVerified)
            assertTrue(after.titles.all { it.artworkHeaders.isNotEmpty() && it.posterUrl != null })
            assertTrue(success(reopened.homeResume()).all { it.isPlayable && it.artworkHeaders.isNotEmpty() })
            assertEquals(1, fixture.resumeRequests())
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test fun resumeFailureDoesNotPoisonTheRecentCatalogOrMarkItsTitlesUnavailable() = runBlocking(Dispatchers.IO) {
        val fail = AtomicBoolean(false)
        Fixture(extraResponse = { request ->
            if (request.url.encodedPath == "/jelly/UserItems/Resume" && fail.get()) throw UnknownHostException("Offline fixture")
            null
        }).use { fixture ->
            fixture.connect(); fixture.repository.showCatalog(CatalogQuery())
            assertEquals(ContentStatus.READY, fixture.repository.catalog.value.status)
            success(fixture.repository.homeResume())
            fail.set(true)
            assertTrue(fixture.repository.homeResume(force = true) is OperationResult.Failure)
            assertEquals(ContentStatus.READY, fixture.repository.catalog.value.status)
            assertTrue(fixture.repository.catalog.value.canUseLibrary)
            assertEquals(2, fixture.repository.catalog.value.titles.size)
        }
    }

    @Test fun genreMetadataIsFilterDemandOnlyBoundedKindScopedAndCachedAcrossDuplicateDemand() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.connect(); fixture.repository.showCatalog(CatalogQuery())
            assertTrue(fixture.requests.none { it.url.encodedPath == "/jelly/Genres" })
            val requests = awaitAll(async { fixture.repository.genres(MediaKind.MOVIE) }, async { fixture.repository.genres(MediaKind.MOVIE) })
            assertTrue(requests.all { it is OperationResult.Success && it.value == listOf("Adventure", "Drama") })
            assertEquals(1, fixture.genreRequests())
            val request = fixture.requests.single { it.url.encodedPath == "/jelly/Genres" }
            assertEquals("60", request.url.queryParameter("limit"))
            assertEquals("Movie", request.url.queryParameter("includeItemTypes"))
            assertEquals("false", request.url.queryParameter("enableImages"))
            assertEquals(USER_ID, request.url.queryParameter("userId"))
            fixture.repository.genres(MediaKind.SERIES, "Mystery")
            assertEquals(2, fixture.genreRequests())
            assertEquals("Mystery", fixture.requests.last().url.queryParameter("searchTerm"))
            assertEquals("Series", fixture.requests.last().url.queryParameter("includeItemTypes"))
        }
    }

    @Test fun failedGenreDemandIsNotCachedAsAnEmptySuccess() = runBlocking(Dispatchers.IO) {
        val fail = AtomicBoolean(true)
        Fixture(extraResponse = { request ->
            if (request.url.encodedPath == "/jelly/Genres" && fail.getAndSet(false)) throw UnknownHostException("Offline fixture")
            null
        }).use { fixture ->
            fixture.connect()
            assertTrue(fixture.repository.genres() is OperationResult.Failure)
            assertEquals(listOf("Adventure", "Drama"), success(fixture.repository.genres()))
            assertEquals(2, fixture.genreRequests())
        }
    }

    @Test fun verifiedCollectionLabelsAndParentQueriesNeverInferStudioMembership() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.connect()
            val values = success(fixture.repository.platformCollections())
            assertEquals(listOf("Criterion collection", "Documentaries"), values.map { it.name })
            fixture.repository.platformCollections()
            assertEquals(1, fixture.collectionRequests())
            val request = fixture.requests.single { types(it) == listOf("BoxSet", "CollectionFolder") }
            assertEquals("60", request.url.queryParameter("limit")); assertEquals("false", request.url.queryParameter("enableImages"))
            val first = CatalogQuery(Destination.BROWSE, parentId = id(7001))
            val second = CatalogQuery(Destination.BROWSE, parentId = id(7002))
            fixture.repository.showCatalog(first)
            assertEquals(id(901), fixture.repository.catalog.value.titles.single().id)
            fixture.repository.showCatalog(second)
            assertEquals(id(902), fixture.repository.catalog.value.titles.single().id)
            val count = fixture.requests.size
            assertEquals(id(901), fixture.repository.cachedCatalog(first)!!.titles.single().id)
            assertEquals(count, fixture.requests.size) // Memory peek never performs I/O.
            assertEquals(id(7002), fixture.requests.last().url.queryParameter("parentId"))
            fixture.repository.showCatalog(first)
            assertEquals(count, fixture.requests.size) // Separate encrypted parent-bound cursors.
        }
    }

    @Test fun seasonalPagesUseActualServerOffsetsAndNeverLoadAllSeriesEpisodes() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.connect()
            val first = success(fixture.repository.seasonPage(SERIES_ID))
            assertEquals(2, first.items.size); assertEquals(2, first.nextStart); assertEquals(3, first.totalCount)
            val request = fixture.requests.last()
            assertEquals("Season", request.url.queryParameter("includeItemTypes")); assertEquals("50", request.url.queryParameter("limit"))
            assertEquals(SERIES_ID, request.url.queryParameter("parentId")); assertEquals("false", request.url.queryParameter("recursive"))
            val last = success(fixture.repository.seasonPage(SERIES_ID, first.nextStart!!))
            assertEquals(1, last.items.size); assertNull(last.nextStart)
            val season = first.items.first()
            val episodes = success(fixture.repository.episodePage(SERIES_ID, season))
            assertEquals(2, episodes.items.size); assertEquals(2, episodes.nextStart)
            val episodeRequest = fixture.requests.last()
            assertEquals("20", episodeRequest.url.queryParameter("limit")); assertEquals(season.id, episodeRequest.url.queryParameter("seasonId"))
            assertEquals("1", episodeRequest.url.queryParameter("season"))
            val tail = success(fixture.repository.episodePage(SERIES_ID, season, episodes.nextStart!!))
            assertEquals(1, tail.items.size); assertNull(tail.nextStart)
            assertEquals("2", fixture.requests.last().url.queryParameter("startIndex"))
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test fun malformedSeasonAndWrongSeasonEpisodePagesFailWithoutClearingTheAccount() = runBlocking(Dispatchers.IO) {
        Fixture(extraResponse = { request -> when {
            request.url.queryParameter("includeItemTypes") == "Season" -> page(4, 3, listOf(season(1)))
            request.url.encodedPath.endsWith("/Episodes") -> page(0, 1, listOf(episode(1, seasonNumber = 2)))
            else -> null
        } }).use { fixture ->
            fixture.connect()
            assertTrue(fixture.repository.seasonPage(SERIES_ID) is OperationResult.Failure)
            assertTrue(fixture.repository.episodePage(SERIES_ID, SeasonOption(id(1001), 1, "Season 1")) is OperationResult.Failure)
            assertTrue(fixture.repository.session.value is SessionState.Connected)
        }
    }

    @Test fun memoryQueryPeeksAreBoundedAndClearedOnSignOut() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.connect()
            val queries = (0..13).map { CatalogQuery(Destination.SEARCH, search = "Query $it") }
            queries.forEach { fixture.repository.showCatalog(it) }
            assertNull(fixture.repository.cachedCatalog(queries.first()))
            assertNotNull(fixture.repository.cachedCatalog(queries.last()))
            assertEquals(12, queries.count { fixture.repository.cachedCatalog(it) != null })
            fixture.repository.signOut()
            assertTrue(queries.all { fixture.repository.cachedCatalog(it) == null })
        }
    }

    @Test fun legacyAlphabeticalHomeCacheIsNeverPresentedAsRecentUploads() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.connect()
            val storage = CatalogCacheStorage(fixture.context)
            assertTrue(storage.save(CachedCatalog(INPUT, USER_ID, "Fixture", System.currentTimeMillis(), true,
                listOf(CachedCatalogTitle(id(888), "Old alphabetical title")), totalCount = 1, nextStart = 1)))
            val reopened = fixture.reopen(); reopened.initialize()
            assertTrue(reopened.catalog.value.titles.isEmpty())
            reopened.showCatalog(CatalogQuery())
            assertFalse(reopened.catalog.value.titles.any { it.id == id(888) })
            assertNotNull(storage.load(INPUT, USER_ID, "home-v4-release-year"))
            assertNull(storage.load(INPUT, USER_ID, "home-v1"))
            assertEquals(listOf("PremiereDate", "ProductionYear", "SortName"), fixture.requests.last().url.queryParameterValues("sortBy"))
        }
    }

    @Test fun signOutCancelsPendingResumeAndRejectsLateMetadata() = runBlocking(Dispatchers.IO) {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture(extraResponse = { request ->
            if (request.url.encodedPath == "/jelly/UserItems/Resume") { entered.countDown(); await(release) }
            null
        }).use { fixture ->
            fixture.connect()
            val pending = async { fixture.repository.homeResume() }
            try {
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                fixture.repository.signOut()
            } finally { release.countDown() }
            try { pending.await() } catch (_: CancellationException) { }
            assertEquals(SessionState.SignedOut, fixture.repository.session.value)
            assertTrue(fixture.repository.homeResume() is OperationResult.Failure)
            assertNull(CatalogCacheStorage(fixture.context).load(INPUT, USER_ID, "home-resume-v1|"))
        }
    }

    private class Fixture(private val extraResponse: (Request) -> InterceptedSdkTransport.FixtureResponse? = { null }) : Closeable {
        private val root: Context = ApplicationProvider.getApplicationContext()
        private val directory = File(root.cacheDir, "home-data-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(root) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        private val scopes = CopyOnWriteArrayList<CoroutineScope>()
        val requests = CopyOnWriteArrayList<Request>()
        val failures = CopyOnWriteArrayList<Pair<String, Throwable>>()
        val transport = InterceptedSdkTransport { request ->
            requests += request
            extraResponse(request) ?: when (request.url.encodedPath) {
                "/jelly/System/Info/Public" -> response("""{"Version":"10.11.0","Id":"offline-fixture"}""")
                "/jelly/Users/AuthenticateByName" -> response("""{"User":$USER_JSON,"AccessToken":"local-fixture-token"}""")
                "/jelly/Users/Me" -> response(USER_JSON)
                "/jelly/UserItems/Resume" -> page(0, 1, listOf(movie(777, resume = true)))
                "/jelly/Genres" -> page(0, 2, listOf("""{"Id":"${id(8001)}","Name":"Drama","Type":"Genre"}""", """{"Id":"${id(8002)}","Name":"Adventure","Type":"Genre"}"""))
                "/jelly/Shows/$SERIES_ID/Episodes" -> {
                    val start = request.url.queryParameter("startIndex")!!.toInt()
                    page(start, 3, if (start == 0) listOf(episode(1), episode(2)) else listOf(episode(3)))
                }
                "/jelly/Items" -> when (types(request)) {
                    listOf("BoxSet", "CollectionFolder") -> page(0, 2, listOf("""{"Id":"${id(7001)}","Name":"Criterion collection","Type":"BoxSet"}""", """{"Id":"${id(7002)}","Name":"Documentaries","Type":"CollectionFolder"}"""))
                    listOf("Season") -> {
                        val start = request.url.queryParameter("startIndex")!!.toInt()
                        page(start, 3, if (start == 0) listOf(season(1), season(2)) else listOf(season(3)))
                    }
                    else -> when (request.url.queryParameter("parentId")) {
                        id(7001) -> page(0, 1, listOf(movie(901)))
                        id(7002) -> page(0, 1, listOf(movie(902)))
                        else -> page(0, 2, listOf(movie(1), movie(2)))
                    }
                }
                else -> throw IOException("Unexpected offline SDK endpoint")
            }
        }
        val repository = reopen()
        fun reopen(): JellyfinMediaRepository {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
            return JellyfinMediaRepository(context, scope,
                onConnectionDiagnostic = { stage, failure -> if (failure != null) failures += stage to failure },
                apiClientFactory = { base, token -> transport.createApi(context, base, token) },
                catalogLoadOptions = CatalogLoadOptions(24, 20_000, 0, 0))
        }
        suspend fun connect() = repository.connectPassword(INPUT, "fixture", "fixture-password")
        fun resumeRequests() = requests.count { it.url.encodedPath == "/jelly/UserItems/Resume" }
        fun genreRequests() = requests.count { it.url.encodedPath == "/jelly/Genres" }
        fun collectionRequests() = requests.count { types(it) == listOf("BoxSet", "CollectionFolder") }
        override fun close() { scopes.forEach { it.cancel() }; transport.close(); directory.deleteRecursively() }
    }

    private companion object {
        const val INPUT = "https://www.mojaloss.stream/jelly"
        const val USER_ID = "00000000-0000-0000-0000-000000000042"
        val SERIES_ID = id(9000)
        const val USER_JSON = """{"Id":"00000000-0000-0000-0000-000000000042","Name":"Fixture","HasPassword":false,"HasConfiguredPassword":false,"HasConfiguredEasyPassword":false}"""
        fun <T> success(result: OperationResult<T>): T = when (result) {
            is OperationResult.Success -> result.value
            is OperationResult.Failure -> throw AssertionError(result.message)
        }
        fun id(value: Int) = UUID(0, value.toLong()).toString()
        fun types(request: Request) = request.url.queryParameterValues("includeItemTypes")
        fun response(body: String) = InterceptedSdkTransport.FixtureResponse(200, body)
        fun page(start: Int, total: Int, items: List<String>) = response("""{"Items":[${items.joinToString(",")}],"StartIndex":$start,"TotalRecordCount":$total}""")
        fun collection(number: Int, name: String) = """{"Id":"${id(number)}","Name":"$name","Type":"BoxSet"}"""
        fun movie(number: Int, resume: Boolean = false) = """{"Id":"${id(number)}","Name":"Movie $number","Type":"Movie","PlayAccess":"Full","RunTimeTicks":54000000000,"ImageTags":{"Primary":"poster$number"},"UserData":{"PlaybackPositionTicks":${if (resume) 12000000000 else 0},"PlayCount":0,"IsFavorite":false,"Played":false,"Key":"fixture$number","ItemId":"${id(number)}"}}"""
        fun season(number: Int) = """{"Id":"${id(1000 + number)}","Name":"Season $number","Type":"Season","IndexNumber":$number}"""
        fun episode(number: Int, seasonNumber: Int = 1) = """{"Id":"${id(2000 + number)}","Name":"Episode $number","Type":"Episode","SeriesId":"$SERIES_ID","ParentIndexNumber":$seasonNumber,"IndexNumber":$number,"PlayAccess":"Full","RunTimeTicks":27000000000}"""
        fun await(latch: CountDownLatch) {
            try { if (!latch.await(20, TimeUnit.SECONDS)) throw IOException("Offline fixture timed out") }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Offline fixture interrupted") }
        }
    }
}
