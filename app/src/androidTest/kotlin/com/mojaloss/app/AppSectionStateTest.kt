package com.mojaloss.app

import androidx.lifecycle.ViewModelStore
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import com.mojaloss.core.data.DemoMediaRepository
import com.mojaloss.core.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Exercises actual section ownership and recovery without DNS, streams or customer credentials. */
@UnstableApi
class AppSectionStateTest {
    @Test fun evictedCanceledSeasonResponseCannotEnterReopenedSeries() = staleResponseAfterEviction(seasons = true)
    @Test fun evictedCanceledEpisodeResponseCannotEnterReopenedSeries() = staleResponseAfterEviction(seasons = false)

    private fun staleResponseAfterEviction(seasons: Boolean) = fixture { model, repo, main ->
        val gate = CompletableDeferred<Unit>()
        repo.detailResult = OperationResult.Success(MediaTitle("series", "Fixture series", kind = MediaKind.SERIES))
        var seasonRequests = 0
        repo.seasonHandler = { _, _ ->
            if (++seasonRequests == 1 && seasons) withContext(NonCancellable) {
                gate.await(); OperationResult.Success(SeasonPage(listOf(SEASON_ONE)))
            } else OperationResult.Success(SeasonPage(listOf(SEASON_TWO)))
        }
        var episodeRequests = 0
        repo.episodeHandler = { _, _, _ ->
            if (++episodeRequests == 1 && !seasons) withContext(NonCancellable) {
                gate.await(); OperationResult.Success(EpisodePage(listOf(EPISODE_ONE)))
            } else OperationResult.Success(EpisodePage(listOf(EPISODE_TWO)))
        }
        try {
            main { model.loadTitle("series") }
            main { repeat(32) { model.loadTitle("other-$it") } }
            assertFalse(model.episodeSections.value.containsKey("series"))
            main { model.loadTitle("series") }
            assertEquals(SEASON_TWO, model.episodeSections.value["series"]?.selectedSeason)
            assertEquals(listOf(EPISODE_TWO), model.episodeSections.value["series"]?.titles)
            main { gate.complete(Unit) }
            assertEquals(SEASON_TWO, model.episodeSections.value["series"]?.selectedSeason)
            assertEquals(listOf(EPISODE_TWO), model.episodeSections.value["series"]?.titles)
        } finally { main { gate.complete(Unit) } }
    }

    @Test fun recoveredSeriesDetailsStartsItsIndependentEpisodeSection() = fixture { model, repo, main ->
        val series = MediaTitle("series", "Fixture series", kind = MediaKind.SERIES)
        repo.detailResult = OperationResult.Failure("Fixture metadata unavailable")
        main { model.rememberTitle(series); model.loadTitle(series.id) }
        assertEquals(ContentStatus.ERROR, model.episodeSections.value[series.id]?.seasonStatus)
        assertTrue(repo.seasonCalls.isEmpty())
        repo.detailResult = OperationResult.Success(series)
        main { model.loadTitle(series.id) }
        assertEquals(ContentStatus.READY, model.episodeSections.value[series.id]?.seasonStatus)
        assertEquals(ContentStatus.READY, model.episodeSections.value[series.id]?.status)
        assertNull(model.detailErrors.value[series.id])
    }

    @Test fun newFavoriteOperationInvalidatesOldUndoWithoutDismissingNewFeedback() = fixture { model, repo, main ->
        main { model.setFavorite("first", false) }
        val oldId = model.notice.value!!.id
        assertTrue(model.notice.value!!.canUndo)
        val gate = CompletableDeferred<Unit>()
        repo.favoriteHandler = { desired -> gate.await(); OperationResult.Success(desired) }
        main { model.setFavorite("second", true) }
        assertNull(model.notice.value)
        main { model.undoRemoval(oldId) }
        assertEquals(listOf("first" to false, "second" to true), repo.favoriteCalls)
        main { gate.complete(Unit) }
        val current = model.notice.value!!
        assertFalse(current.canUndo)
        main { model.dismissMessage(oldId); model.undoRemoval(oldId) }
        assertEquals(current, model.notice.value)
    }

    @Test fun episodePendingFailureRetryAndTrueEmptyAreDistinct() = fixture { model, repo, main ->
        val gate = CompletableDeferred<Unit>()
        repo.episodeHandler = { _, _, _ -> gate.await(); OperationResult.Failure("Fixture episodes unavailable") }
        main { model.loadSeasons("series") }
        assertEquals(ContentStatus.LOADING, model.episodeSections.value["series"]?.status)
        assertEquals(ContentStatus.READY, model.episodeSections.value["series"]?.seasonStatus)
        main { model.loadMoreEpisodes("series"); model.retryEpisodes("series") }
        assertEquals(1, repo.episodeCalls.size)
        main { gate.complete(Unit) }
        assertEquals(ContentStatus.ERROR, model.episodeSections.value["series"]?.status)
        assertNull(model.notice.value) // Local recovery does not produce a second global error.
        repo.episodeHandler = { _, _, _ -> OperationResult.Success(EpisodePage(emptyList())) }
        main { model.retryEpisodes("series") }
        assertEquals(ContentStatus.READY, model.episodeSections.value["series"]?.status)
        assertTrue(model.episodeSections.value["series"]!!.titles.isEmpty())
        assertEquals(2, repo.episodeCalls.size)
    }

    @Test fun seasonFailureHasItsOwnRetryAndDoesNotPretendThereAreNoEpisodes() = fixture { model, repo, main ->
        repo.seasonResult = OperationResult.Failure("Fixture seasons unavailable")
        main { model.loadSeasons("series") }
        assertEquals(ContentStatus.ERROR, model.episodeSections.value["series"]?.seasonStatus)
        assertTrue(repo.episodeCalls.isEmpty())
        repo.seasonResult = OperationResult.Success(SeasonPage(listOf(SEASON_ONE)))
        main { model.retryEpisodes("series") }
        assertEquals(ContentStatus.READY, model.episodeSections.value["series"]?.seasonStatus)
        assertEquals(ContentStatus.READY, model.episodeSections.value["series"]?.status)
        assertEquals(2, repo.seasonCalls.size)
    }

    @Test fun demandPagingRetainsRowsOnFailureAndRetriesOnlyTheFailedPage() = fixture { model, repo, main ->
        repo.episodeHandler = { _, _, start -> if (start == 0)
            OperationResult.Success(EpisodePage(listOf(EPISODE_ONE), 50, 51))
            else OperationResult.Failure("Fixture page unavailable") }
        main { model.loadSeasons("series") }
        assertEquals(listOf(0), repo.episodeCalls.map { it.third })
        main { model.loadMoreEpisodes("series") }
        val failed = model.episodeSections.value["series"]!!
        assertEquals(listOf(EPISODE_ONE), failed.titles)
        assertTrue(failed.hasMore)
        assertEquals(ContentStatus.ERROR, failed.status)
        repo.episodeHandler = { _, _, _ -> OperationResult.Success(EpisodePage(listOf(EPISODE_TWO), null, 51)) }
        main { model.retryEpisodes("series") }
        assertEquals(listOf(0, 50, 50), repo.episodeCalls.map { it.third })
        assertEquals(listOf(EPISODE_ONE, EPISODE_TWO), model.episodeSections.value["series"]?.titles)
        assertFalse(model.episodeSections.value["series"]!!.hasMore)
    }

    @Test fun repeatedEpisodePageStopsDemandUntilExplicitRetry() = fixture { model, repo, main ->
        repo.episodeHandler = { _, _, start -> OperationResult.Success(EpisodePage(listOf(EPISODE_ONE), start + 50)) }
        main { model.loadSeasons("series"); model.loadMoreEpisodes("series") }
        assertEquals(ContentStatus.ERROR, model.episodeSections.value["series"]?.status)
        assertFalse(model.episodeSections.value["series"]!!.hasMore)
        main { model.loadMoreEpisodes("series") }
        assertEquals(2, repo.episodeCalls.size)
        main { model.retryEpisodes("series") }
        assertEquals(listOf(0, 50, 0), repo.episodeCalls.map { it.third })
    }

    @Test fun switchingSeasonCancelsOldRequestAndClearsItsRows() = fixture { model, repo, main ->
        var canceled = false
        repo.episodeHandler = { _, season, _ ->
            if (season?.id == SEASON_ONE.id) try { CompletableDeferred<Unit>().await(); error("unreachable") }
                catch (error: CancellationException) { canceled = true; throw error }
            else OperationResult.Success(EpisodePage(listOf(EPISODE_TWO)))
        }
        main { model.loadSeasons("series") }
        main { model.selectSeason("series", SEASON_TWO) }
        assertTrue(canceled)
        assertEquals(SEASON_TWO, model.episodeSections.value["series"]?.selectedSeason)
        assertEquals(listOf(EPISODE_TWO), model.episodes.value["series"])
    }

    @Test fun accountChangeCancelsSectionRequestsAndClearsKnownTitleIdentity() = fixture { model, repo, main ->
        var canceled = false
        repo.episodeHandler = { _, _, _ -> try { CompletableDeferred<Unit>().await(); error("unreachable") }
            catch (error: CancellationException) { canceled = true; throw error } }
        main { model.rememberTitle(EPISODE_ONE); model.loadSeasons("series") }
        main { repo.session.value = CONNECTED.copy(account = Account("other", "Other"),
            scope = CONNECTED.scope.copy(accountId = "other", generation = 2)) }
        assertTrue(canceled)
        assertTrue(model.episodeSections.value.isEmpty())
        assertTrue(model.episodes.value.isEmpty())
        assertNull(model.knownTitle(EPISODE_ONE.id))
    }

    @Test fun resumeDemandIsCoalescedAndRetainsProgressDuringRefreshFailure() = fixture { model, repo, main ->
        repo.resumeHandler = { OperationResult.Success(listOf(EPISODE_ONE)) }
        main { model.loadHomeResume() }
        val gate = CompletableDeferred<Unit>()
        repo.resumeHandler = { gate.await(); OperationResult.Failure("Fixture resume unavailable") }
        main { model.loadHomeResume(force = true); model.loadHomeResume() }
        assertEquals(2, repo.resumeCalls)
        assertEquals(listOf(EPISODE_ONE), model.homeResume.value.titles)
        assertTrue(model.homeResume.value.isRefreshing)
        main { gate.complete(Unit) }
        assertEquals(ContentStatus.ERROR, model.homeResume.value.status)
        assertEquals(listOf(EPISODE_ONE), model.homeResume.value.titles)
        assertNull(model.notice.value)
    }

    @Test fun hiddenSectionsDoNotLoadAtBootstrapAndManualRefreshCoalesces() = fixture { model, repo, main ->
        assertEquals(0, repo.resumeCalls)
        assertEquals(0, repo.platformCalls)
        assertEquals(0, repo.genreCalls)
        assertTrue(repo.seasonCalls.isEmpty())
        val gate = CompletableDeferred<Unit>()
        repo.refreshHandler = { gate.await() }
        main { model.refresh(); model.refresh(); model.reconnect() }
        assertEquals(1, repo.refreshCalls)
        main { gate.complete(Unit) }
        assertNull(model.notice.value)
    }

    @Test fun seriesAndUnavailableTitlesCannotNegotiateAStream() = fixture { model, repo, main ->
        main {
            model.rememberTitle(MediaTitle("series", "Fixture series", kind = MediaKind.SERIES, isPlayable = true))
            model.play("series")
        }
        assertEquals("Choose an episode to play.", model.notice.value?.text)
        main { model.rememberTitle(EPISODE_ONE.copy(isPlayable = false)); model.play(EPISODE_ONE.id) }
        assertEquals(0, repo.preparationCalls)
        assertEquals("This title is not available to play.", model.notice.value?.text)
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
            main { model = AppViewModel(repo, instrumentation.targetContext.applicationContext); store.put("fixture", model) }
            block(model, repo, ::main)
        } finally { main { store.clear() } }
    }

    private class FixtureRepository : MediaRepository by DemoMediaRepository() {
        override val session = MutableStateFlow<SessionState>(CONNECTED)
        override val catalog = MutableStateFlow(CatalogSnapshot(ContentStatus.READY))
        val seasonCalls = mutableListOf<Pair<String, Int>>()
        val episodeCalls = mutableListOf<Triple<String, SeasonOption?, Int>>()
        var seasonResult: OperationResult<SeasonPage> = OperationResult.Success(SeasonPage(listOf(SEASON_ONE, SEASON_TWO)))
        var detailResult: OperationResult<MediaTitle> = OperationResult.Failure("Fixture metadata unavailable")
        var seasonHandler: (suspend (String, Int) -> OperationResult<SeasonPage>)? = null
        var episodeHandler: suspend (String, SeasonOption?, Int) -> OperationResult<EpisodePage> = { _, _, _ -> OperationResult.Success(EpisodePage(listOf(EPISODE_ONE))) }
        var resumeHandler: suspend () -> OperationResult<List<MediaTitle>> = { OperationResult.Success(emptyList()) }
        var refreshHandler: suspend () -> Unit = {}
        var favoriteHandler: suspend (Boolean) -> OperationResult<Boolean> = { OperationResult.Success(it) }
        val favoriteCalls = mutableListOf<Pair<String, Boolean>>()
        var resumeCalls = 0; var platformCalls = 0; var genreCalls = 0; var refreshCalls = 0; var preparationCalls = 0
        override suspend fun initialize() = Unit
        override suspend fun details(id: String): OperationResult<MediaTitle> =
            if (id == "series") detailResult else OperationResult.Success(MediaTitle(id, id, isPlayable = true))
        override suspend fun setFavorite(id: String, desired: Boolean): OperationResult<Boolean> {
            favoriteCalls += id to desired; return favoriteHandler(desired)
        }
        override suspend fun refresh() { refreshCalls++; refreshHandler() }
        override suspend fun seasonPage(seriesId: String, startIndex: Int): OperationResult<SeasonPage> {
            seasonCalls += seriesId to startIndex; return seasonHandler?.invoke(seriesId, startIndex) ?: seasonResult
        }
        override suspend fun episodePage(seriesId: String, season: SeasonOption?, startIndex: Int): OperationResult<EpisodePage> {
            episodeCalls += Triple(seriesId, season, startIndex); return episodeHandler(seriesId, season, startIndex)
        }
        override suspend fun homeResume(kind: MediaKind?, force: Boolean): OperationResult<List<MediaTitle>> {
            resumeCalls++; return resumeHandler()
        }
        override suspend fun platformCollections(force: Boolean): OperationResult<List<PlatformCollection>> {
            platformCalls++; return OperationResult.Success(emptyList())
        }
        override suspend fun genres(kind: MediaKind?, search: String): OperationResult<List<String>> {
            genreCalls++; return OperationResult.Success(emptyList())
        }
        override suspend fun preparePlayback(id: String, fromStart: Boolean): OperationResult<PlaybackDescriptor> {
            preparationCalls++; return OperationResult.Failure("Fixture only")
        }
    }

    private companion object {
        val CONNECTED = SessionState.Connected(Account("account", "Fixture account"),
            SessionScope("https://fixture.invalid/jelly/", "account", 1))
        val SEASON_ONE = SeasonOption("season-one", 1, "Season 1")
        val SEASON_TWO = SeasonOption("season-two", 2, "Season 2")
        val EPISODE_ONE = MediaTitle("episode-one", "Fixture episode one", kind = MediaKind.EPISODE, isPlayable = true, seriesId = "series", seasonNumber = 1)
        val EPISODE_TWO = MediaTitle("episode-two", "Fixture episode two", kind = MediaKind.EPISODE, isPlayable = true, seriesId = "series", seasonNumber = 2)
    }
}
