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
import java.util.concurrent.atomic.AtomicInteger

/** Pinned SDK requests are intercepted before DNS/socket work; every credential/body is a fixture. */
@RunWith(AndroidJUnit4::class)
class SeriesStartDataTest {
    @Test fun scopedResumeUsesOneEpisodeOnlyAndFreshCacheCoalescesUntilForceOrNewSession() = runBlocking(Dispatchers.IO) {
        Fixture().use { fixture ->
            fixture.connect()
            assertEquals(0, fixture.resumeRequests().size)
            assertTrue(fixture.requests.none { it.url.encodedPath == "/jelly/Items" })
            val results = awaitAll(async { fixture.repository.seriesStart(SERIES_ID) }, async { fixture.repository.seriesStart(SERIES_ID) })
            assertTrue(results.all { success(it)?.id == id(2777) })
            val target = success(results.first())!!
            assertEquals(MediaKind.EPISODE, target.kind)
            assertEquals(SERIES_ID, target.seriesId)
            assertEquals(61_000L, target.confirmedResumeMs)
            assertTrue(target.isPlayable)
            val request = fixture.resumeRequests().single()
            assertEquals(SERIES_ID, request.url.queryParameter("parentId"))
            assertEquals(USER_ID, request.url.queryParameter("userId"))
            assertEquals(listOf("Episode"), types(request))
            assertEquals("1", request.url.queryParameter("limit"))
            assertEquals("0", request.url.queryParameter("startIndex"))
            assertEquals("false", request.url.queryParameter("enableTotalRecordCount"))
            fixture.repository.seriesStart(SERIES_ID)
            assertEquals(1, fixture.resumeRequests().size)
            fixture.repository.seriesStart(SERIES_ID, force = true)
            assertEquals(2, fixture.resumeRequests().size)
            val oldScope = (fixture.repository.session.value as SessionState.Connected).scope
            fixture.repository.signOut()
            fixture.connect()
            assertNotEquals(oldScope, (fixture.repository.session.value as SessionState.Connected).scope)
            success(fixture.repository.seriesStart(SERIES_ID))
            assertEquals(3, fixture.resumeRequests().size)
            assertTrue(fixture.requests.none { it.url.encodedPath.contains("NextUp") || it.url.encodedPath.endsWith("/Episodes") })
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test fun successfulEmptyResumeSelectsFirstRegularSeasonThenItsFirstSortedEpisode() = runBlocking(Dispatchers.IO) {
        Fixture(extraResponse = { request ->
            if (request.url.encodedPath == "/jelly/UserItems/Resume") page(0, 0, emptyList()) else null
        }).use { fixture ->
            fixture.connect()
            val target = success(fixture.repository.seriesStart(SERIES_ID))!!
            assertEquals(id(2001), target.id)
            assertEquals(1, target.seasonNumber)
            assertEquals(1, target.episodeNumber)
            assertEquals(0L, target.confirmedResumeMs)
            val seasonRequest = fixture.requests.single { types(it) == listOf("Season") }
            assertEquals(SERIES_ID, seasonRequest.url.queryParameter("parentId"))
            assertEquals(USER_ID, seasonRequest.url.queryParameter("userId"))
            assertEquals("50", seasonRequest.url.queryParameter("limit"))
            assertEquals("false", seasonRequest.url.queryParameter("recursive"))
            assertEquals(listOf("IndexNumber", "SortName"), seasonRequest.url.queryParameterValues("sortBy"))
            assertEquals(listOf("Ascending"), seasonRequest.url.queryParameterValues("sortOrder"))
            val episodeRequest = fixture.requests.single { it.url.encodedPath == "/jelly/Items" && types(it) == listOf("Episode") }
            assertEquals(id(1001), episodeRequest.url.queryParameter("parentId"))
            assertEquals("1", episodeRequest.url.queryParameter("limit"))
            assertEquals("0", episodeRequest.url.queryParameter("startIndex"))
            assertEquals("false", episodeRequest.url.queryParameter("recursive"))
            assertEquals(listOf("IndexNumber", "SortName"), episodeRequest.url.queryParameterValues("sortBy"))
            assertEquals(listOf("Ascending"), episodeRequest.url.queryParameterValues("sortOrder"))
            assertEquals(1, fixture.resumeRequests().size)
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test fun resumeFailureNeverFallsBackToFirstEpisodeOrPoisonsRecentCatalog() = runBlocking(Dispatchers.IO) {
        Fixture(extraResponse = { request ->
            if (request.url.encodedPath == "/jelly/UserItems/Resume") throw UnknownHostException("Local offline fixture")
            null
        }).use { fixture ->
            fixture.connect()
            fixture.repository.showCatalog(CatalogQuery())
            val before = fixture.repository.catalog.value
            assertEquals(ContentStatus.READY, before.status)
            assertEquals(2, before.titles.size)
            assertTrue(fixture.repository.seriesStart(SERIES_ID) is OperationResult.Failure)
            assertEquals(before, fixture.repository.catalog.value)
            assertTrue(fixture.repository.session.value is SessionState.Connected)
            assertTrue(fixture.requests.none { types(it) == listOf("Season") || types(it) == listOf("Episode") && it.url.encodedPath == "/jelly/Items" })
            assertEquals(1, fixture.resumeRequests().size)
            assertTrue(fixture.repository.seriesStart(SERIES_ID) is OperationResult.Failure)
            assertEquals(2, fixture.resumeRequests().size) // A failed request never becomes an empty cached success.
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test fun malformedResumeAndForeignOrNonEpisodeFallbackTargetsAreRejected() = runBlocking(Dispatchers.IO) {
        val mode = AtomicInteger(0)
        Fixture(extraResponse = { request -> when {
            request.url.encodedPath == "/jelly/UserItems/Resume" -> when (mode.get()) {
                0 -> page(0, 1, listOf(episode(777, seriesId = id(9001), resumeMs = 61_000)))
                1 -> page(0, 1, listOf(episode(777, type = "Movie", resumeMs = 61_000)))
                2 -> page(0, 1, listOf(episode(777, resumeMs = 0)))
                else -> page(0, 0, emptyList())
            }
            request.url.encodedPath == "/jelly/Items" && types(request) == listOf("Episode") -> when (mode.get()) {
                3 -> page(0, 1, listOf(episode(1, seasonNumber = 2)))
                4 -> page(0, 1, listOf(episode(1, seriesId = id(9001))))
                else -> page(0, 1, listOf(episode(1, type = "Movie")))
            }
            else -> null
        } }).use { fixture ->
            fixture.connect()
            repeat(3) { invalid ->
                mode.set(invalid)
                assertTrue(fixture.repository.seriesStart(SERIES_ID, force = true) is OperationResult.Failure)
                assertTrue(fixture.requests.none { types(it) == listOf("Season") })
            }
            for (invalid in 3..5) {
                mode.set(invalid)
                assertTrue(fixture.repository.seriesStart(SERIES_ID, force = true) is OperationResult.Failure)
            }
            assertEquals(6, fixture.resumeRequests().size)
            assertEquals(3, fixture.requests.count { types(it) == listOf("Season") })
            assertTrue(fixture.repository.session.value is SessionState.Connected)
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test fun signOutCancelsPendingStartAndLateEpisodeNeverWarmsAnotherSessionCache() = runBlocking(Dispatchers.IO) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockOnce = AtomicBoolean(true)
        Fixture(extraResponse = { request ->
            if (request.url.encodedPath == "/jelly/UserItems/Resume") {
                if (blockOnce.getAndSet(false)) {
                    entered.countDown(); await(release)
                    page(0, 1, listOf(episode(777, resumeMs = 61_000)))
                } else page(0, 1, listOf(episode(778, resumeMs = 71_000)))
            } else null
        }).use { fixture ->
            fixture.connect()
            val pending = async { fixture.repository.seriesStart(SERIES_ID) }
            try {
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                fixture.repository.signOut()
                assertEquals(SessionState.SignedOut, fixture.repository.session.value)
            } finally { release.countDown() }
            try { withTimeout(20_000) { pending.await() } } catch (_: CancellationException) { }
            assertTrue(pending.isCancelled)
            assertTrue(fixture.repository.seriesStart(SERIES_ID) is OperationResult.Failure)
            assertEquals(1, fixture.resumeRequests().size)
            fixture.connect()
            val target = success(fixture.repository.seriesStart(SERIES_ID))!!
            assertEquals(id(2778), target.id)
            assertEquals(71_000L, target.confirmedResumeMs)
            assertEquals(2, fixture.resumeRequests().size)
            fixture.repository.seriesStart(SERIES_ID)
            assertEquals(2, fixture.resumeRequests().size)
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    private class Fixture(private val extraResponse: (Request) -> InterceptedSdkTransport.FixtureResponse? = { null }) : Closeable {
        private val root: Context = ApplicationProvider.getApplicationContext()
        private val directory = File(root.cacheDir, "series-start-data-${UUID.randomUUID()}").apply { mkdirs() }
        private val context = object : ContextWrapper(root) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val requests = CopyOnWriteArrayList<Request>()
        val transport = InterceptedSdkTransport { request ->
            requests += request
            extraResponse(request) ?: when (request.url.encodedPath) {
                "/jelly/System/Info/Public" -> response("""{"Version":"10.11.0","Id":"offline-fixture"}""")
                "/jelly/Users/AuthenticateByName" -> response("""{"User":$USER_JSON,"AccessToken":"local-fixture-token"}""")
                "/jelly/Users/Me" -> response(USER_JSON)
                "/jelly/UserItems/Resume" -> page(0, 1, listOf(episode(777, resumeMs = 61_000)))
                "/jelly/Items" -> when (types(request)) {
                    listOf("Season") -> page(0, 3, listOf(season(0), season(2), season(1)))
                    listOf("Episode") -> page(0, 1, listOf(episode(1)))
                    else -> page(0, 2, listOf(movie(1), movie(2)))
                }
                else -> throw IOException("Unexpected offline SDK endpoint")
            }
        }
        val repository = JellyfinMediaRepository(context, scope,
            apiClientFactory = { base, token -> transport.createApi(context, base, token) },
            catalogLoadOptions = CatalogLoadOptions(24, 20_000, 0, 0))
        suspend fun connect() {
            repository.connectPassword(INPUT, "fixture", "fixture-password")
            assertTrue(repository.session.value is SessionState.Connected)
        }
        fun resumeRequests() = requests.filter { it.url.encodedPath == "/jelly/UserItems/Resume" }
        override fun close() { scope.cancel(); transport.close(); directory.deleteRecursively() }
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
        fun userData(number: Int, resumeMs: Long = 0) = """{"PlaybackPositionTicks":${resumeMs * 10_000},"PlayCount":0,"IsFavorite":false,"Played":false,"Key":"fixture$number","ItemId":"${id(number)}"}"""
        fun movie(number: Int) = """{"Id":"${id(number)}","Name":"Movie $number","Type":"Movie","PlayAccess":"Full","RunTimeTicks":54000000000,"UserData":${userData(number)}}"""
        fun season(number: Int) = """{"Id":"${id(1000 + number)}","Name":"Season $number","Type":"Season","SeriesId":"$SERIES_ID","IndexNumber":$number}"""
        fun episode(number: Int, seriesId: String = SERIES_ID, seasonNumber: Int = 1, type: String = "Episode", resumeMs: Long = 0) =
            """{"Id":"${id(2000 + number)}","Name":"Episode $number","Type":"$type","SeriesId":"$seriesId","ParentIndexNumber":$seasonNumber,"IndexNumber":$number,"PlayAccess":"Full","RunTimeTicks":27000000000,"UserData":${userData(2000 + number, resumeMs)}}"""
        fun await(latch: CountDownLatch) {
            try { if (!latch.await(20, TimeUnit.SECONDS)) throw IOException("Offline fixture timed out") }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Offline fixture interrupted") }
        }
    }
}
