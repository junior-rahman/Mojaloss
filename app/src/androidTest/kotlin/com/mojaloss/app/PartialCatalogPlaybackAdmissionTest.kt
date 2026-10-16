package com.mojaloss.app

import androidx.lifecycle.ViewModelStore
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import com.mojaloss.core.data.DemoMediaRepository
import com.mojaloss.core.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Test

/** Real ViewModel admission policy with no stream/player construction and no service requests. */
@UnstableApi
class PartialCatalogPlaybackAdmissionTest {
    @Test fun connectedPartialCatalogActuallyReachesPlaybackPreparation() {
        withModel(CatalogSnapshot(ContentStatus.LOADING, titles = listOf(FIXTURE_TITLE)), CONNECTED, fromStart = true) { model, repository ->
            assertEquals(listOf(FIXTURE_TITLE.id to true), repository.prepared)
            assertEquals(FIXTURE_PREPARATION_FAILURE, model.message.value)
            assertFalse(model.preparing.value)
            assertNull(model.player.value)
        }
    }

    @Test fun emptyLoadingPreviewUnverifiedAndSignedOutCatalogsStayBlocked() {
        val cases = listOf(
            CatalogSnapshot(ContentStatus.LOADING) to CONNECTED,
            CatalogSnapshot(ContentStatus.READY, titles = listOf(FIXTURE_TITLE), isDemo = true) to CONNECTED,
            CatalogSnapshot(ContentStatus.ERROR, titles = listOf(FIXTURE_TITLE), isConnectionVerified = false) to CONNECTED,
            CatalogSnapshot(ContentStatus.OFFLINE, titles = listOf(FIXTURE_TITLE), isConnectionVerified = false) to CONNECTED,
            CatalogSnapshot(ContentStatus.READY, titles = listOf(FIXTURE_TITLE)) to SessionState.SignedOut,
        )
        cases.forEach { (catalog, session) ->
            withModel(catalog, session) { model, repository ->
                assertTrue(repository.prepared.isEmpty())
                assertNotNull(model.message.value)
                assertFalse(model.preparing.value)
                assertNull(model.player.value)
            }
        }
    }

    @Test fun listingFailureDoesNotBlockVerifiedKnownPlayableTitle() {
        listOf(ContentStatus.ERROR, ContentStatus.OFFLINE).forEach { status ->
            withModel(CatalogSnapshot(status, listOf(FIXTURE_TITLE)), CONNECTED) { model, repository ->
                assertEquals(listOf(FIXTURE_TITLE.id to false), repository.prepared)
                assertEquals(FIXTURE_PREPARATION_FAILURE, model.notice.value?.text)
            }
        }
    }

    @Test fun unverifiedSavedCatalogCannotReachPlaybackPreparation() {
        val cached = CatalogSnapshot(ContentStatus.READY, listOf(FIXTURE_TITLE),
            isCached = true, isRefreshing = true, isConnectionVerified = false)
        val restoring = SessionState.Restoring(CONNECTED.account, CONNECTED.scope)
        listOf(restoring, CONNECTED).forEach { session ->
            withModel(cached, session) { model, repository ->
                assertTrue(repository.prepared.isEmpty())
                assertNotNull(model.message.value)
                assertFalse(model.preparing.value)
                assertNull(model.player.value)
            }
        }
        withModel(cached.copy(isConnectionVerified = true), CONNECTED) { model, repository ->
            assertEquals(listOf(FIXTURE_TITLE.id to false), repository.prepared)
            assertEquals(FIXTURE_PREPARATION_FAILURE, model.message.value)
        }
    }

    private fun withModel(
        catalog: CatalogSnapshot,
        session: SessionState,
        fromStart: Boolean = false,
        assertions: (AppViewModel, AdmissionRepository) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = ViewModelStore()
        val repository = AdmissionRepository(catalog, session)
        lateinit var model: AppViewModel
        try {
            instrumentation.runOnMainSync {
                model = AppViewModel(repository, instrumentation.targetContext.applicationContext)
                store.put("fixture-model", model)
            }
            instrumentation.waitForIdleSync()
            // Exercise AppViewModel.play itself, then let its Main coroutine settle before
            // checking preparation. The fake returns Failure and never creates a player.
            instrumentation.runOnMainSync { model.play(FIXTURE_TITLE.id, fromStart) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertions(model, repository) }
            instrumentation.waitForIdleSync()
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            instrumentation.waitForIdleSync()
        }
    }

    private class AdmissionRepository(
        initialCatalog: CatalogSnapshot,
        initialSession: SessionState,
    ) : MediaRepository by DemoMediaRepository() {
        override val catalog: StateFlow<CatalogSnapshot> = MutableStateFlow(initialCatalog)
        override val session: StateFlow<SessionState> = MutableStateFlow(initialSession)
        val prepared = mutableListOf<Pair<String, Boolean>>()
        override suspend fun refresh() = Unit
        override suspend fun preparePlayback(id: String, fromStart: Boolean): OperationResult<PlaybackDescriptor> {
            prepared += id to fromStart
            return OperationResult.Failure(FIXTURE_PREPARATION_FAILURE)
        }
    }

    private companion object {
        const val FIXTURE_PREPARATION_FAILURE = "Fixture preparation reached; no stream requested."
        val FIXTURE_TITLE = MediaTitle("fixture-movie", "Fixture movie", isPlayable = true)
        val CONNECTED = SessionState.Connected(Account("fixture-account", "Fixture account"),
            SessionScope("https://fixture.invalid/jelly/", "fixture-account", 1))
    }
}
