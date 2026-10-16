package com.mojaloss.app

import androidx.lifecycle.ViewModelStore
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import com.mojaloss.core.data.DemoMediaRepository
import com.mojaloss.core.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Actual ViewModel request ownership, using only suspended local fixtures. */
@UnstableApi
class CoreRequestEfficiencyTest {
    @Test fun bootstrapDoesNotLoadHiddenCatalogAndLatestSelectionCancelsOldWork() = fixture { model, repo, main ->
        assertEquals(1, repo.initializations)
        assertEquals(0, repo.refreshes)
        assertTrue(repo.selected.isEmpty())
        main { model.showCatalog(CatalogQuery(Destination.SEARCH, search = "blocked")) }
        assertEquals(1, repo.selected.size)
        main { model.showCatalog(CatalogQuery(Destination.BROWSE)) }
        assertTrue(repo.canceledSelection)
        assertEquals(Destination.BROWSE, repo.currentQuery?.destination)
        assertEquals(2, repo.selected.size)
    }

    @Test fun duplicateDetailsAreCoalescedAndSuccessfulMetadataIsReused() = fixture { model, repo, main ->
        main { model.loadTitle("blocked-title"); model.loadTitle("blocked-title") }
        assertEquals(listOf("blocked-title"), repo.detailRequests)
        main { repo.detailGate.complete(Unit) }
        assertEquals("blocked-title", model.details.value["blocked-title"]?.id)
        main { model.loadTitle("blocked-title") }
        assertEquals(1, repo.detailRequests.size)
    }

    @Test fun metadataIsBoundedAndAccountChangeCancelsOutstandingWork() = fixture { model, repo, main ->
        main { repeat(40) { model.loadTitle("title-$it") } }
        assertEquals(32, model.details.value.size)
        assertFalse(model.details.value.containsKey("title-0"))
        assertTrue(model.details.value.containsKey("title-39"))
        main { model.loadTitle("blocked-title") }
        main { repo.session.value = SessionState.Connected(Account("other", "Other"),
            SessionScope("https://fixture.invalid/jelly/", "other", 2)) }
        assertTrue(repo.canceledDetail)
        assertTrue(model.details.value.isEmpty())
        main { repo.detailGate.complete(Unit) }
        assertTrue(model.details.value.isEmpty())
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
        override val session = MutableStateFlow<SessionState>(SessionState.Connected(Account("account", "Account"),
            SessionScope("https://fixture.invalid/jelly/", "account", 1)))
        override val catalog = MutableStateFlow(CatalogSnapshot(ContentStatus.READY,
            listOf(MediaTitle("fixture-title", "Fixture title", isPlayable = true))))
        var initializations = 0
        var refreshes = 0
        val selected = mutableListOf<CatalogQuery>()
        var currentQuery: CatalogQuery? = null
        var canceledSelection = false
        val detailRequests = mutableListOf<String>()
        val detailGate = CompletableDeferred<Unit>()
        var canceledDetail = false
        override suspend fun initialize() { initializations++ }
        override suspend fun refresh() { refreshes++ }
        override suspend fun showCatalog(query: CatalogQuery) {
            selected += query
            if (query.search == "blocked") {
                try { CompletableDeferred<Unit>().await() }
                finally { canceledSelection = true }
            } else currentQuery = query
        }
        override suspend fun details(id: String): OperationResult<MediaTitle> {
            detailRequests += id
            if (id == "blocked-title") {
                try { detailGate.await() }
                catch (error: kotlinx.coroutines.CancellationException) { canceledDetail = true; throw error }
            }
            return OperationResult.Success(MediaTitle(id, id, isPlayable = true))
        }
    }
}
