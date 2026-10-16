package com.mojaloss.app

import androidx.lifecycle.ViewModelStore
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import com.mojaloss.core.data.DemoMediaRepository
import com.mojaloss.core.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Series artwork resolves an actual episode; fixtures contain no customer login or streams. */
@UnstableApi
class SeriesStartStateTest {
    @Test fun serviceResumeAndFirstEpisodeTargetsAreKnownWithoutPlayingSeriesContainer() = fixture { model, repo, main ->
        val resume = EPISODE_ONE.copy(confirmedResumeMs = 61_000)
        repo.startHandler = { _, _ -> OperationResult.Success(resume) }
        main { model.rememberTitle(SERIES); model.loadSeriesStart(SERIES.id) }
        assertEquals(resume, model.seriesStarts.value[SERIES.id]?.episode)
        assertEquals(resume, model.knownTitle(resume.id))
        main { model.play(SERIES.id) }
        assertEquals("Choose an episode to play.", model.notice.value?.text)
        assertTrue(repo.preparationCalls.isEmpty())
        main { model.play(resume.id) }
        assertEquals(listOf(resume.id), repo.preparationCalls)
        val first = EPISODE_TWO.copy(confirmedResumeMs = 0)
        repo.startHandler = { _, _ -> OperationResult.Success(first) }
        main { model.loadSeriesStart(SERIES.id, force = true) }
        assertEquals(first, model.knownTitle(first.id))
        main { model.play(first.id) }
        assertEquals(listOf(resume.id, first.id), repo.preparationCalls)
    }

    @Test fun mismatchedSeriesAndNonEpisodeServerTargetsAreRejected() = fixture { model, repo, main ->
        val before = repo.catalog.value
        val invalidTargets = listOf(
            EPISODE_ONE.copy(id = "foreign-episode", seriesId = "foreign-series"),
            EPISODE_ONE.copy(id = "missing-series", seriesId = null),
            EPISODE_ONE.copy(id = "movie-target", kind = MediaKind.MOVIE),
            SERIES.copy(id = "series-target", seriesId = SERIES.id),
        )
        invalidTargets.forEach { invalid ->
            repo.startHandler = { _, _ -> OperationResult.Success(invalid) }
            main { model.loadSeriesStart(SERIES.id, force = true) }
            assertEquals(ContentStatus.ERROR, model.seriesStarts.value[SERIES.id]?.status)
            assertNull(model.seriesStarts.value[SERIES.id]?.episode)
            assertNull(model.knownTitle(invalid.id))
        }
        assertEquals(before, repo.catalog.value)
        assertNull(model.notice.value)
        assertTrue(repo.preparationCalls.isEmpty())
    }

    @Test fun freshSeriesTargetControlsAdmissionWhenCachedEpisodeRowHasOlderPermissions() = fixture { model, repo, main ->
        val freshPlayable = EPISODE_ONE.copy(isPlayable = true, confirmedResumeMs = 61_000)
        repo.episodeTitles = listOf(EPISODE_ONE.copy(isPlayable = false))
        repo.startHandler = { _, _ -> OperationResult.Success(freshPlayable) }
        main { model.loadSeasons(SERIES.id); model.loadSeriesStart(SERIES.id) }
        assertFalse(model.episodeSections.value[SERIES.id]!!.titles.single().isPlayable)
        assertEquals(freshPlayable, model.knownTitle(EPISODE_ONE.id))
        main { assertTrue(model.selectSeriesEpisode(SERIES.id, freshPlayable)); model.play(freshPlayable.id) }
        assertEquals(listOf(freshPlayable.id), repo.preparationCalls)
        assertEquals(freshPlayable, model.seriesStarts.value[SERIES.id]?.episode)

        repo.episodeTitles = listOf(EPISODE_ONE.copy(isPlayable = true))
        main { model.retryEpisodes(SERIES.id) }
        val freshUnavailable = EPISODE_ONE.copy(isPlayable = false)
        repo.startHandler = { _, _ -> OperationResult.Success(freshUnavailable) }
        main { model.loadSeriesStart(SERIES.id, force = true) }
        assertTrue(model.episodeSections.value[SERIES.id]!!.titles.single().isPlayable)
        main { assertFalse(model.selectSeriesEpisode(SERIES.id, EPISODE_ONE.copy(isPlayable = true))) }
        assertEquals(freshUnavailable, model.seriesStarts.value[SERIES.id]?.episode)
        assertEquals(freshUnavailable, model.knownTitle(EPISODE_ONE.id))
        assertEquals(listOf(freshPlayable.id), repo.preparationCalls)
    }

    @Test fun selectingKnownEpisodeCancelsLateResumeAndRejectsUnknownUnavailableOrForeignSelection() = fixture { model, repo, main ->
        val gate = CompletableDeferred<Unit>()
        val unavailable = EPISODE_ONE.copy(id = "unavailable-episode", isPlayable = false)
        repo.episodeTitles = listOf(EPISODE_TWO, unavailable)
        repo.startHandler = { _, _ -> withContext(NonCancellable) {
            gate.await(); OperationResult.Success(EPISODE_ONE.copy(confirmedResumeMs = 99_000))
        } }
        try {
            main { model.loadSeasons(SERIES.id); model.loadSeriesStart(SERIES.id) }
            main {
                assertFalse(model.selectSeriesEpisode(SERIES.id, unavailable))
                assertFalse(model.selectSeriesEpisode(SERIES.id, EPISODE_TWO.copy(id = "unknown-episode")))
                assertFalse(model.selectSeriesEpisode(SERIES.id, EPISODE_TWO.copy(seriesId = "foreign-series")))
                assertFalse(model.selectSeriesEpisode(SERIES.id, EPISODE_TWO.copy(kind = MediaKind.MOVIE)))
                assertTrue(model.selectSeriesEpisode(SERIES.id, EPISODE_TWO))
            }
            assertEquals(EPISODE_TWO, model.seriesStarts.value[SERIES.id]?.episode)
            main { gate.complete(Unit) }
            assertEquals(EPISODE_TWO, model.seriesStarts.value[SERIES.id]?.episode)
            assertNull(model.knownTitle(EPISODE_ONE.id))
            assertEquals(EPISODE_TWO, model.knownTitle(EPISODE_TWO.id))
        } finally { main { gate.complete(Unit) } }
    }

    @Test fun accountChangeClearsStartAndRejectsOldAccountLateCallback() = fixture { model, repo, main ->
        val gate = CompletableDeferred<Unit>()
        repo.startHandler = { _, _ ->
            if (repo.startCalls.size == 1) withContext(NonCancellable) {
                gate.await(); OperationResult.Success(EPISODE_ONE)
            } else OperationResult.Success(EPISODE_TWO)
        }
        try {
            main { model.loadSeriesStart(SERIES.id) }
            main { repo.session.value = CONNECTED.copy(account = Account("other", "Other fixture account"),
                scope = CONNECTED.scope.copy(accountId = "other", generation = 2)) }
            assertTrue(model.seriesStarts.value.isEmpty())
            main { model.loadSeriesStart(SERIES.id) }
            main { gate.complete(Unit) }
            assertEquals(EPISODE_TWO, model.seriesStarts.value[SERIES.id]?.episode)
            assertNull(model.knownTitle(EPISODE_ONE.id))
        } finally { main { gate.complete(Unit) } }
    }

    @Test fun evictedCanceledStartCannotEnterSameSeriesAfterItReopens() = fixture { model, repo, main ->
        val gate = CompletableDeferred<Unit>()
        var originalRequests = 0
        repo.startHandler = { seriesId, _ ->
            if (seriesId == SERIES.id && ++originalRequests == 1) withContext(NonCancellable) {
                gate.await(); OperationResult.Success(EPISODE_ONE)
            } else OperationResult.Success(if (seriesId == SERIES.id) EPISODE_TWO else
                EPISODE_TWO.copy(id = "$seriesId-episode", seriesId = seriesId))
        }
        try {
            main { model.loadSeriesStart(SERIES.id) }
            main { repeat(32) { model.loadSeriesStart("other-series-$it") } }
            assertFalse(model.seriesStarts.value.containsKey(SERIES.id))
            assertEquals(32, model.seriesStarts.value.size)
            main { model.loadSeriesStart(SERIES.id) }
            assertEquals(EPISODE_TWO, model.seriesStarts.value[SERIES.id]?.episode)
            main { gate.complete(Unit) }
            assertEquals(EPISODE_TWO, model.seriesStarts.value[SERIES.id]?.episode)
            assertNull(model.knownTitle(EPISODE_ONE.id))
            assertEquals(32, model.seriesStarts.value.size)
        } finally { main { gate.complete(Unit) } }
    }

    @Test fun pendingStartCoalescesAndRefreshFailureRetainsActualTargetUntilRetry() = fixture { model, repo, main ->
        repo.startHandler = { _, _ -> OperationResult.Success(EPISODE_ONE) }
        main { model.loadSeriesStart(SERIES.id); model.loadSeriesStart(SERIES.id) }
        assertEquals(1, repo.startCalls.size)
        val gate = CompletableDeferred<Unit>()
        repo.startHandler = { _, _ -> gate.await(); OperationResult.Failure("Fixture episode lookup unavailable") }
        try {
            main { model.loadSeriesStart(SERIES.id, force = true); model.loadSeriesStart(SERIES.id) }
            assertEquals(2, repo.startCalls.size)
            assertEquals(EPISODE_ONE, model.seriesStarts.value[SERIES.id]?.episode)
            main { gate.complete(Unit) }
            assertEquals(ContentStatus.ERROR, model.seriesStarts.value[SERIES.id]?.status)
            assertEquals(EPISODE_ONE, model.seriesStarts.value[SERIES.id]?.episode)
            assertNull(model.notice.value)
            repo.startHandler = { _, _ -> OperationResult.Success(null) }
            main { model.loadSeriesStart(SERIES.id, force = true) }
            assertEquals(ContentStatus.READY, model.seriesStarts.value[SERIES.id]?.status)
            assertNull(model.seriesStarts.value[SERIES.id]?.episode)
            assertEquals(listOf(false, true, true), repo.startCalls.map { it.second })
        } finally { main { gate.complete(Unit) } }
    }

    private fun fixture(block: (AppViewModel, FixtureRepository, (() -> Unit) -> Unit) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repo = FixtureRepository()
        val store = ViewModelStore()
        lateinit var model: AppViewModel
        fun main(action: () -> Unit) {
            instrumentation.runOnMainSync(action)
            instrumentation.waitForIdleSync()
        }
        try {
            main { model = AppViewModel(repo, instrumentation.targetContext.applicationContext); store.put("series-start-fixture", model) }
            block(model, repo, ::main)
        } finally { main { store.clear() } }
    }

    private class FixtureRepository : MediaRepository by DemoMediaRepository() {
        override val session = MutableStateFlow<SessionState>(CONNECTED)
        override val catalog = MutableStateFlow(CatalogSnapshot(ContentStatus.READY))
        val startCalls = mutableListOf<Pair<String, Boolean>>()
        val preparationCalls = mutableListOf<String>()
        var episodeTitles = listOf(EPISODE_ONE, EPISODE_TWO)
        var startHandler: suspend (String, Boolean) -> OperationResult<MediaTitle?> = { _, _ -> OperationResult.Success(null) }
        override suspend fun initialize() = Unit
        override suspend fun seriesStart(seriesId: String, force: Boolean): OperationResult<MediaTitle?> {
            startCalls += seriesId to force
            return startHandler(seriesId, force)
        }
        override suspend fun seasonPage(seriesId: String, startIndex: Int): OperationResult<SeasonPage> =
            OperationResult.Success(SeasonPage(listOf(SEASON)))
        override suspend fun episodePage(seriesId: String, season: SeasonOption?, startIndex: Int): OperationResult<EpisodePage> =
            OperationResult.Success(EpisodePage(episodeTitles))
        override suspend fun preparePlayback(id: String, fromStart: Boolean): OperationResult<PlaybackDescriptor> {
            preparationCalls += id
            return OperationResult.Failure("Fixture contains no real stream")
        }
    }

    private companion object {
        val CONNECTED = SessionState.Connected(Account("account", "Fixture account"),
            SessionScope("https://fixture.invalid/jelly/", "account", 1))
        val SERIES = MediaTitle("series", "Fixture series", kind = MediaKind.SERIES, isPlayable = true)
        val SEASON = SeasonOption("season-one", 1, "Season 1")
        val EPISODE_ONE = MediaTitle("episode-one", "Fixture episode one", kind = MediaKind.EPISODE,
            isPlayable = true, seriesId = SERIES.id, seasonNumber = 1, episodeNumber = 1)
        val EPISODE_TWO = MediaTitle("episode-two", "Fixture episode two", kind = MediaKind.EPISODE,
            isPlayable = true, seriesId = SERIES.id, seasonNumber = 1, episodeNumber = 2)
    }
}
