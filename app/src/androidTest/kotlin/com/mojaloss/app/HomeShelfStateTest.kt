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

/** Actual ViewModel demand/state boundaries; local fixtures never negotiate real media or DNS. */
@UnstableApi
class HomeShelfStateTest {
    @Test fun hiddenBootstrapAndInvisibleRowsDoNotFetch() = fixture { model, repo, main ->
        assertTrue(repo.shelfCalls.isEmpty())
        main {
            model.setHomeFeedActive(false)
            HomeShelfKind.entries.forEach { model.loadHomeShelf(it) }
        }
        assertTrue(repo.shelfCalls.isEmpty())
        assertTrue(model.homeShelves.value.isEmpty())
        main { model.setHomeFeedActive(true); model.loadHomeShelf(HomeShelfKind.TRENDING) }
        assertEquals(listOf(HomeShelfKind.TRENDING), repo.shelfCalls.map { it.kind })
        assertFalse(model.homeShelves.value.containsKey(HomeShelfKind.HINDI_MOVIES))
    }

    @Test fun visibleDuplicateDemandIsCoalescedWhilePageIsPending() = fixture { model, repo, main ->
        val gate = CompletableDeferred<Unit>()
        repo.shelfHandler = { call ->
            gate.await()
            OperationResult.Success(HomeShelfPage(firstPage, selectedSource = call.source(), nextStart = 12, totalCount = 24))
        }
        try {
            main {
                model.setHomeFeedActive(true)
                model.loadHomeShelf(HomeShelfKind.TRENDING)
                model.loadHomeShelf(HomeShelfKind.TRENDING)
                model.loadHomeShelf(HomeShelfKind.TRENDING, more = true)
            }
            assertEquals(1, repo.shelfCalls.size)
            assertEquals(ContentStatus.LOADING, model.homeShelves.value[HomeShelfKind.TRENDING]?.status)
            main { gate.complete(Unit) }
            assertEquals(firstPage, model.homeShelves.value[HomeShelfKind.TRENDING]?.titles)
            assertEquals(1, repo.shelfCalls.size)
        } finally { main { gate.complete(Unit) } }
    }

    @Test fun automaticallyResolvedServerSourcePublishesTitlesWithoutChoiceRoundTrip() = fixture { model, repo, main ->
        repo.shelfHandler = {
            OperationResult.Success(HomeShelfPage(listOf(TITLE_B), listOf(SOURCE_A, SOURCE_B), SOURCE_B))
        }
        main { model.setHomeFeedActive(true); model.loadHomeShelf(HomeShelfKind.HINDI_MOVIES) }
        val resolved = model.homeShelves.value[HomeShelfKind.HINDI_MOVIES]!!
        assertEquals(ContentStatus.READY, resolved.status)
        assertEquals(listOf(SOURCE_A, SOURCE_B), resolved.sources)
        assertEquals(SOURCE_B, resolved.selectedSource)
        assertEquals(listOf(TITLE_B), resolved.titles)
        assertEquals(listOf<String?>(null), repo.shelfCalls.map { it.sourceId })
        main { model.loadHomeShelf(HomeShelfKind.HINDI_MOVIES, source = PlatformCollection("unlisted", "Unlisted fixture source")) }
        assertEquals(1, repo.shelfCalls.size)
        assertEquals(resolved, model.homeShelves.value[HomeShelfKind.HINDI_MOVIES])
        main { model.loadHomeShelf(HomeShelfKind.HINDI_MOVIES) }
        assertEquals(1, repo.shelfCalls.size)
    }

    @Test fun smallPagesAdvanceExactlyOncePerVisibleDemand() = fixture { model, repo, main ->
        repo.shelfHandler = { call -> OperationResult.Success(HomeShelfPage(
            if (call.startIndex == 0) firstPage else secondPage,
            nextStart = if (call.startIndex == 0) 12 else null,
            totalCount = 24)) }
        main { model.setHomeFeedActive(true); model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES) }
        assertEquals(listOf(0), repo.shelfCalls.map { it.startIndex })
        assertEquals(12, model.homeShelves.value[HomeShelfKind.ENGLISH_MOVIES]?.titles?.size)
        main { model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES) }
        assertEquals(listOf(0), repo.shelfCalls.map { it.startIndex })
        main { model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES, more = true) }
        assertEquals(listOf(0, 12), repo.shelfCalls.map { it.startIndex })
        assertEquals(firstPage + secondPage, model.homeShelves.value[HomeShelfKind.ENGLISH_MOVIES]?.titles)
        assertFalse(model.homeShelves.value[HomeShelfKind.ENGLISH_MOVIES]!!.hasMore)
        main { model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES, more = true) }
        assertEquals(2, repo.shelfCalls.size)
    }

    @Test fun appendFailureRetainsRowsAndExplicitRetryRequestsOnlyFailedCursor() = fixture { model, repo, main ->
        repo.shelfHandler = { call -> if (call.startIndex == 0)
            OperationResult.Success(HomeShelfPage(firstPage, nextStart = 12, totalCount = 24))
            else OperationResult.Failure("Fixture page unavailable") }
        main {
            model.setHomeFeedActive(true)
            model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES)
            model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES, more = true)
        }
        val failed = model.homeShelves.value[HomeShelfKind.ENGLISH_MOVIES]!!
        assertEquals(firstPage, failed.titles)
        assertEquals(ContentStatus.ERROR, failed.status)
        assertTrue(failed.hasMore)
        assertFalse(failed.isLoadingMore)
        assertNull(model.notice.value)
        main {
            repeat(3) { model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES) }
            model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES, more = true)
        }
        assertEquals(2, repo.shelfCalls.size)
        repo.shelfHandler = { OperationResult.Success(HomeShelfPage(secondPage, totalCount = 24)) }
        main { model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES, force = true) }
        assertEquals(listOf(0, 12, 12), repo.shelfCalls.map { it.startIndex })
        assertTrue(repo.shelfCalls.last().force)
        assertEquals(firstPage + secondPage, model.homeShelves.value[HomeShelfKind.ENGLISH_MOVIES]?.titles)
        assertEquals(ContentStatus.READY, model.homeShelves.value[HomeShelfKind.ENGLISH_MOVIES]?.status)
    }

    @Test fun homePreviewStopsAtSeventyTwoEvenWhenServerReportsOneThousandTitles() = fixture { model, repo, main ->
        repo.shelfHandler = { call -> OperationResult.Success(HomeShelfPage(
            (call.startIndex until call.startIndex + 12).map { MediaTitle("bounded-$it", "Fixture bounded title $it") },
            nextStart = call.startIndex + 12, totalCount = 1_000)) }
        main {
            model.setHomeFeedActive(true)
            model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES)
            repeat(5) { model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES, more = true) }
        }
        val capped = model.homeShelves.value[HomeShelfKind.ENGLISH_MOVIES]!!
        assertEquals(72, capped.titles.size)
        assertTrue(capped.previewLimitReached)
        assertFalse(capped.hasMore)
        assertEquals(listOf(0, 12, 24, 36, 48, 60), repo.shelfCalls.map { it.startIndex })
        main { repeat(5) { model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES, more = true) } }
        assertEquals(6, repo.shelfCalls.size)
        assertTrue(repo.catalogCalls.isEmpty())
    }

    @Test fun shortFirstPageCannotOverflowHomePreviewEvenWhenLastPageCompletesCatalog() = fixture { model, repo, main ->
        repo.shelfHandler = { call ->
            val count = if (call.startIndex == 0) 11 else 12
            val end = call.startIndex + count
            OperationResult.Success(HomeShelfPage(
                (call.startIndex until end).map { MediaTitle("short-page-$it", "Fixture short-page title $it") },
                nextStart = end.takeIf { it < 83 }, totalCount = 83))
        }
        main {
            model.setHomeFeedActive(true)
            model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES)
            repeat(6) { model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES, more = true) }
        }
        val capped = model.homeShelves.value[HomeShelfKind.ENGLISH_MOVIES]!!
        assertEquals(ContentStatus.READY, capped.status)
        assertEquals(72, capped.titles.size)
        assertEquals("short-page-71", capped.titles.last().id)
        assertTrue(capped.previewLimitReached)
        assertFalse(capped.hasMore)
        assertEquals(listOf(0, 11, 23, 35, 47, 59, 71), repo.shelfCalls.map { it.startIndex })
        main { model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES, more = true) }
        assertEquals(7, repo.shelfCalls.size)
    }

    @Test fun repeatedSuccessfulAppendRetainsRowsButRetryRestartsAtFirstPage() = fixture { model, repo, main ->
        repo.shelfHandler = { call -> OperationResult.Success(HomeShelfPage(firstPage,
            nextStart = if (call.startIndex == 0) 12 else 24, totalCount = 24)) }
        main {
            model.setHomeFeedActive(true)
            model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES)
            model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES, more = true)
        }
        val invalid = model.homeShelves.value[HomeShelfKind.ENGLISH_MOVIES]!!
        assertEquals(ContentStatus.ERROR, invalid.status)
        assertEquals(firstPage, invalid.titles)
        assertFalse(invalid.hasMore)
        assertFalse(invalid.isLoadingMore)
        main { model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES, more = true) }
        assertEquals(2, repo.shelfCalls.size)
        repo.shelfHandler = { OperationResult.Success(HomeShelfPage(secondPage)) }
        main { model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES, force = true) }
        assertEquals(listOf(0, 12, 0), repo.shelfCalls.map { it.startIndex })
        assertEquals(secondPage, model.homeShelves.value[HomeShelfKind.ENGLISH_MOVIES]?.titles)
        assertEquals(ContentStatus.READY, model.homeShelves.value[HomeShelfKind.ENGLISH_MOVIES]?.status)
    }

    @Test fun typedPagingRestartPreservesSourceAndRowsButDiscardsBrokenCursor() = fixture { model, repo, main ->
        repo.shelfHandler = { call -> if (call.startIndex == 0)
            OperationResult.Success(HomeShelfPage(firstPage, listOf(SOURCE_A), SOURCE_A, nextStart = 12))
            else OperationResult.Failure("Fixture source changed order", recovery = OperationRecovery.RESTART_PAGING) }
        main {
            model.setHomeFeedActive(true)
            model.loadHomeShelf(HomeShelfKind.HINDI_MOVIES)
            model.loadHomeShelf(HomeShelfKind.HINDI_MOVIES, more = true)
        }
        val failed = model.homeShelves.value[HomeShelfKind.HINDI_MOVIES]!!
        assertEquals(firstPage, failed.titles)
        assertEquals(SOURCE_A, failed.selectedSource)
        assertEquals(listOf(SOURCE_A), failed.sources)
        assertFalse(failed.hasMore)
        repo.shelfHandler = { OperationResult.Success(HomeShelfPage(secondPage, listOf(SOURCE_A), SOURCE_A)) }
        main { model.loadHomeShelf(HomeShelfKind.HINDI_MOVIES, force = true) }
        assertEquals(listOf(0, 12, 0), repo.shelfCalls.map { it.startIndex })
        assertEquals(listOf(null, SOURCE_A.id, SOURCE_A.id), repo.shelfCalls.map { it.sourceId })
        assertEquals(secondPage, model.homeShelves.value[HomeShelfKind.HINDI_MOVIES]?.titles)
        assertEquals(SOURCE_A, model.homeShelves.value[HomeShelfKind.HINDI_MOVIES]?.selectedSource)
        assertNull(model.notice.value)
    }

    @Test fun typedSourceResetDropsOldIdentityAndDiscoversNewChoicesBeforeSelection() = fixture { model, repo, main ->
        val newSource = PlatformCollection("source-c", "Fixture replacement Hindi source")
        repo.shelfHandler = { call -> if (call.startIndex == 0)
            OperationResult.Success(HomeShelfPage(firstPage, listOf(SOURCE_A), SOURCE_A, nextStart = 12))
            else OperationResult.Failure("Fixture category removed", recovery = OperationRecovery.RESET_SOURCE) }
        main {
            model.setHomeFeedActive(true)
            model.loadHomeShelf(HomeShelfKind.HINDI_MOVIES)
            model.loadHomeShelf(HomeShelfKind.HINDI_MOVIES, more = true)
        }
        val failed = model.homeShelves.value[HomeShelfKind.HINDI_MOVIES]!!
        assertEquals(ContentStatus.ERROR, failed.status)
        assertNull(failed.selectedSource)
        assertTrue(failed.sources.isEmpty())
        assertFalse(failed.hasMore)
        main { repeat(3) { model.loadHomeShelf(HomeShelfKind.HINDI_MOVIES) } }
        assertEquals(2, repo.shelfCalls.size)
        repo.shelfHandler = { OperationResult.Success(HomeShelfPage(emptyList(), listOf(SOURCE_B, newSource))) }
        main { model.loadHomeShelf(HomeShelfKind.HINDI_MOVIES, force = true) }
        assertEquals(listOf(0, 12, 0), repo.shelfCalls.map { it.startIndex })
        assertEquals(listOf(null, SOURCE_A.id, null), repo.shelfCalls.map { it.sourceId })
        val choices = model.homeShelves.value[HomeShelfKind.HINDI_MOVIES]!!
        assertEquals(ContentStatus.READY, choices.status)
        assertTrue(choices.titles.isEmpty())
        assertNull(choices.selectedSource)
        assertEquals(listOf(SOURCE_B, newSource), choices.sources)
        main { model.loadHomeShelf(HomeShelfKind.HINDI_MOVIES, source = SOURCE_A) }
        assertEquals(3, repo.shelfCalls.size)
        repo.shelfHandler = { OperationResult.Success(HomeShelfPage(secondPage, listOf(SOURCE_B, newSource), newSource)) }
        main { model.loadHomeShelf(HomeShelfKind.HINDI_MOVIES, source = newSource) }
        assertEquals(0, repo.shelfCalls.last().startIndex)
        assertEquals(newSource.id, repo.shelfCalls.last().sourceId)
        assertEquals(secondPage, model.homeShelves.value[HomeShelfKind.HINDI_MOVIES]?.titles)
        assertNull(model.notice.value)
    }

    @Test fun hiddenFeedRejectsCanceledLateResponseAfterSameRowReopens() = fixture { model, repo, main ->
        val gate = CompletableDeferred<Unit>()
        repo.shelfHandler = {
            if (repo.shelfCalls.size == 1) withContext(NonCancellable) {
                gate.await(); OperationResult.Success(HomeShelfPage(listOf(TITLE_A)))
            } else OperationResult.Success(HomeShelfPage(listOf(TITLE_B)))
        }
        try {
            main { model.setHomeFeedActive(true); model.loadHomeShelf(HomeShelfKind.TRENDING) }
            main { model.setHomeFeedActive(false) }
            main { model.setHomeFeedActive(true); model.loadHomeShelf(HomeShelfKind.TRENDING) }
            assertEquals(listOf(TITLE_B), model.homeShelves.value[HomeShelfKind.TRENDING]?.titles)
            main { gate.complete(Unit) }
            assertEquals(listOf(TITLE_B), model.homeShelves.value[HomeShelfKind.TRENDING]?.titles)
            assertNull(model.knownTitle(TITLE_A.id))
            assertEquals(2, repo.shelfCalls.size)
        } finally { main { gate.complete(Unit) } }
    }

    @Test fun hidingCanceledAppendRetainsCompletedRowsAndAllowsDemandOnReturn() = fixture { model, repo, main ->
        val gate = CompletableDeferred<Unit>()
        repo.shelfHandler = { call ->
            if (call.startIndex == 0) OperationResult.Success(HomeShelfPage(firstPage, nextStart = 12))
            else if (repo.shelfCalls.size == 2) withContext(NonCancellable) {
                gate.await(); OperationResult.Success(HomeShelfPage(listOf(TITLE_A)))
            } else OperationResult.Success(HomeShelfPage(secondPage))
        }
        try {
            main {
                model.setHomeFeedActive(true)
                model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES)
                model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES, more = true)
            }
            assertTrue(model.homeShelves.value[HomeShelfKind.ENGLISH_MOVIES]!!.isLoadingMore)
            main { model.setHomeFeedActive(false) }
            assertEquals(firstPage, model.homeShelves.value[HomeShelfKind.ENGLISH_MOVIES]?.titles)
            assertFalse(model.homeShelves.value[HomeShelfKind.ENGLISH_MOVIES]!!.isLoadingMore)
            main { model.setHomeFeedActive(true); model.loadHomeShelf(HomeShelfKind.ENGLISH_MOVIES, more = true) }
            main { gate.complete(Unit) }
            assertEquals(firstPage + secondPage, model.homeShelves.value[HomeShelfKind.ENGLISH_MOVIES]?.titles)
            assertEquals(listOf(0, 12, 12), repo.shelfCalls.map { it.startIndex })
        } finally { main { gate.complete(Unit) } }
    }

    @Test fun accountChangeClearsRowsAndRejectsNonCooperativeOldAccountResponse() = fixture { model, repo, main ->
        val gate = CompletableDeferred<Unit>()
        repo.shelfHandler = {
            if (repo.shelfCalls.size == 1) withContext(NonCancellable) {
                gate.await(); OperationResult.Success(HomeShelfPage(listOf(TITLE_A)))
            } else OperationResult.Success(HomeShelfPage(listOf(TITLE_B)))
        }
        try {
            main { model.setHomeFeedActive(true); model.loadHomeShelf(HomeShelfKind.TRENDING) }
            main { repo.session.value = CONNECTED.copy(account = Account("other", "Other fixture account"),
                scope = CONNECTED.scope.copy(accountId = "other", generation = 2)) }
            assertTrue(model.homeShelves.value.isEmpty())
            assertNull(model.knownTitle(TITLE_A.id))
            main { model.loadHomeShelf(HomeShelfKind.TRENDING) }
            assertEquals(listOf(TITLE_B), model.homeShelves.value[HomeShelfKind.TRENDING]?.titles)
            main { gate.complete(Unit) }
            assertEquals(listOf(TITLE_B), model.homeShelves.value[HomeShelfKind.TRENDING]?.titles)
            assertNull(model.knownTitle(TITLE_A.id))
        } finally { main { gate.complete(Unit) } }
    }

    @Test fun selectingAnotherSourceRejectsCanceledPreviousSourceResponse() = fixture { model, repo, main ->
        val gate = CompletableDeferred<Unit>()
        repo.shelfHandler = { call ->
            when (call.sourceId) {
                null -> OperationResult.Success(HomeShelfPage(emptyList(), sources = listOf(SOURCE_A, SOURCE_B)))
                SOURCE_A.id -> withContext(NonCancellable) {
                    gate.await(); OperationResult.Success(HomeShelfPage(listOf(TITLE_A), listOf(SOURCE_A, SOURCE_B), SOURCE_A))
                }
                else -> OperationResult.Success(HomeShelfPage(listOf(TITLE_B), listOf(SOURCE_A, SOURCE_B), SOURCE_B))
            }
        }
        try {
            main {
                model.setHomeFeedActive(true)
                model.loadHomeShelf(HomeShelfKind.HINDI_MOVIES)
                model.loadHomeShelf(HomeShelfKind.HINDI_MOVIES, source = SOURCE_A)
            }
            main { model.loadHomeShelf(HomeShelfKind.HINDI_MOVIES, source = SOURCE_B) }
            assertEquals(SOURCE_B, model.homeShelves.value[HomeShelfKind.HINDI_MOVIES]?.selectedSource)
            main { gate.complete(Unit) }
            assertEquals(SOURCE_B, model.homeShelves.value[HomeShelfKind.HINDI_MOVIES]?.selectedSource)
            assertEquals(listOf(TITLE_B), model.homeShelves.value[HomeShelfKind.HINDI_MOVIES]?.titles)
            assertNull(model.knownTitle(TITLE_A.id))
        } finally { main { gate.complete(Unit) } }
    }

    @Test fun shelfFailureNeverNavigatesOrMutatesMainCatalog() = fixture { model, repo, main ->
        val before = repo.catalog.value
        repo.shelfHandler = { call -> if (call.kind == HomeShelfKind.TRENDING)
            OperationResult.Success(HomeShelfPage(listOf(TITLE_A)))
            else OperationResult.Failure("Fixture source unavailable") }
        main {
            model.setHomeFeedActive(true)
            model.loadHomeShelf(HomeShelfKind.TRENDING)
            model.loadHomeShelf(HomeShelfKind.HINDI_MOVIES)
        }
        assertEquals(before, repo.catalog.value)
        assertTrue(repo.catalogCalls.isEmpty())
        assertEquals(ContentStatus.READY, model.homeShelves.value[HomeShelfKind.TRENDING]?.status)
        assertEquals(ContentStatus.ERROR, model.homeShelves.value[HomeShelfKind.HINDI_MOVIES]?.status)
        assertNull(model.notice.value)
        assertEquals(0, repo.refreshCalls)
    }

    @Test fun shelfTitlesCanPlayIndependentlyOfListingHealthWithPerTitleGuards() = fixture { model, repo, main ->
        val movie = TITLE_A.copy(isPlayable = true)
        val series = TITLE_B.copy(kind = MediaKind.SERIES, isPlayable = true)
        val unavailable = MediaTitle("unavailable", "Fixture unavailable movie", isPlayable = false)
        repo.catalog.value = repo.catalog.value.copy(status = ContentStatus.ERROR, message = "Fixture listing unavailable")
        repo.shelfHandler = { OperationResult.Success(HomeShelfPage(listOf(movie, series, unavailable))) }
        main { model.setHomeFeedActive(true); model.loadHomeShelf(HomeShelfKind.TRENDING) }
        assertEquals(movie, model.knownTitle(movie.id))
        main { model.play(movie.id) }
        assertEquals(listOf(movie.id), repo.preparationCalls)
        main { model.play(series.id) }
        assertEquals("Choose an episode to play.", model.notice.value?.text)
        main { model.play(unavailable.id) }
        assertEquals("This title is not available to play.", model.notice.value?.text)
        assertEquals(listOf(movie.id), repo.preparationCalls)
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
            main { model = AppViewModel(repo, instrumentation.targetContext.applicationContext); store.put("home-shelf-fixture", model) }
            block(model, repo, ::main)
        } finally { main { store.clear() } }
    }

    private data class ShelfCall(val kind: HomeShelfKind, val sourceId: String?, val startIndex: Int, val force: Boolean) {
        fun source(): PlatformCollection? = listOf(SOURCE_A, SOURCE_B).firstOrNull { it.id == sourceId }
    }

    private class FixtureRepository : MediaRepository by DemoMediaRepository() {
        override val session = MutableStateFlow<SessionState>(CONNECTED)
        override val catalog = MutableStateFlow(CatalogSnapshot(ContentStatus.READY,
            listOf(MediaTitle("catalog-only", "Fixture main catalog")), query = CatalogQuery(search = "retained query")))
        val shelfCalls = mutableListOf<ShelfCall>()
        val catalogCalls = mutableListOf<CatalogQuery>()
        val preparationCalls = mutableListOf<String>()
        var refreshCalls = 0
        var shelfHandler: suspend (ShelfCall) -> OperationResult<HomeShelfPage> = {
            OperationResult.Success(HomeShelfPage(emptyList()))
        }
        override suspend fun initialize() = Unit
        override suspend fun homeShelf(kind: HomeShelfKind, sourceId: String?, startIndex: Int, force: Boolean): OperationResult<HomeShelfPage> {
            val call = ShelfCall(kind, sourceId, startIndex, force)
            shelfCalls += call
            return shelfHandler(call)
        }
        override suspend fun showCatalog(query: CatalogQuery) { catalogCalls += query }
        override suspend fun refresh() { refreshCalls++ }
        override suspend fun preparePlayback(id: String, fromStart: Boolean): OperationResult<PlaybackDescriptor> {
            preparationCalls += id
            return OperationResult.Failure("Fixture contains no real stream")
        }
    }

    private companion object {
        val CONNECTED = SessionState.Connected(Account("account", "Fixture account"),
            SessionScope("https://fixture.invalid/jelly/", "account", 1))
        val SOURCE_A = PlatformCollection("source-a", "Fixture Hindi source A")
        val SOURCE_B = PlatformCollection("source-b", "Fixture Hindi source B")
        val TITLE_A = MediaTitle("title-a", "Fixture title A", year = 2025)
        val TITLE_B = MediaTitle("title-b", "Fixture title B", year = 2026)
        val firstPage = (0 until 12).map { MediaTitle("page-one-$it", "Fixture first page $it", year = 2026) }
        val secondPage = (0 until 12).map { MediaTitle("page-two-$it", "Fixture second page $it", year = 2025) }
    }
}
