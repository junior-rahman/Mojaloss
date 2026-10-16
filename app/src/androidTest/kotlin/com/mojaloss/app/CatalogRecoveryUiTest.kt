package com.mojaloss.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.mojaloss.app.feature.ui.*
import com.mojaloss.core.model.*
import com.mojaloss.core.ui.MojaLossBackdrop
import com.mojaloss.core.ui.MojaLossTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Actual catalog screens with local fixtures only; no repository, network, or real account. */
class CatalogRecoveryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyErrorOffersRetryAndReportCopyOnEveryCatalogPage() {
        val page = mutableStateOf(Destination.HOME)
        val snapshot = mutableStateOf(CatalogSnapshot(ContentStatus.ERROR,
            message = "Could not refresh. Showing this account's cached titles."))
        var retries = 0
        var copies = 0
        compose.setContent {
            MojaLossTheme {
                MojaLossBackdrop(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        LocalCatalogRefresh provides { retries++; snapshot.value = CatalogSnapshot(ContentStatus.LOADING) },
                        LocalErrorReportCopy provides { copies++ },
                    ) { CatalogFixturePage(page.value, snapshot.value) }
                }
            }
        }
        Destination.entries.forEach { destination ->
            compose.runOnIdle {
                page.value = destination
                snapshot.value = CatalogSnapshot(ContentStatus.ERROR,
                    message = "Could not refresh. Showing this account's cached titles.")
            }
            compose.onNodeWithText("Couldn’t load titles")
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("cached titles", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Your library is empty").assertDoesNotExist()
            compose.runOnIdle { snapshot.value = snapshot.value.copy(message = SAFE_FAILURE_REASON) }
            compose.onNodeWithContentDescription("Show library error details").performScrollTo().performClick()
            compose.onNodeWithText(SAFE_FAILURE_REASON).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Copy error report").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithText("Retry").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithTag(if (destination == Destination.HOME) "recent-skeletons" else "catalog-skeletons").assertIsDisplayed()
            compose.onNodeWithText("Loading your library…").assertDoesNotExist()
            compose.onNodeWithText("Refresh").assertDoesNotExist()
            compose.onNodeWithText("Copy error report").assertDoesNotExist()
        }
        compose.runOnIdle {
            assertEquals(Destination.entries.size, retries)
            assertEquals(Destination.entries.size, copies)
        }
    }

    @Test fun emptyLoadingIsNeverShownAsAnEmptyLibraryOrFinishedSearch() {
        val page = mutableStateOf(Destination.HOME)
        val snapshot = mutableStateOf(CatalogSnapshot(ContentStatus.LOADING))
        compose.setContent {
            MojaLossTheme {
                MojaLossBackdrop(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalCatalogRefresh provides { }) {
                        CatalogFixturePage(page.value, snapshot.value)
                    }
                }
            }
        }
        Destination.entries.forEach { destination ->
            compose.runOnIdle { page.value = destination; snapshot.value = CatalogSnapshot(ContentStatus.LOADING) }
            compose.onNodeWithTag(if (destination == Destination.HOME) "recent-skeletons" else "catalog-skeletons").assertIsDisplayed()
            compose.onNodeWithText("Loading your library…").assertDoesNotExist()
            compose.onNodeWithText("Your library is empty").assertDoesNotExist()
            compose.onNodeWithText("No titles match").assertDoesNotExist()
            compose.onNodeWithText("No results for", substring = true).assertDoesNotExist()
            compose.onNodeWithText("0 titles").assertDoesNotExist()
            compose.onNodeWithText("Saved titles").assertDoesNotExist()
            compose.runOnIdle { snapshot.value = CatalogSnapshot(ContentStatus.READY) }
            val emptyCopy = when (destination) {
                Destination.SEARCH -> "No results for “Fixture”"
                Destination.MY_LIST -> "No saved titles yet"
                else -> "Your library is empty"
            }
            compose.onNodeWithText(emptyCopy).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Loading your library…").assertDoesNotExist()
            compose.onNodeWithText("Refresh").assertDoesNotExist()
        }
    }

    @Test fun loadedFirstPageRemainsUsableWhileFurtherPagesLoad() {
        val page = mutableStateOf(Destination.HOME)
        var plays = 0
        var removals = 0
        val snapshot = CatalogSnapshot(ContentStatus.LOADING, titles = FIXTURE_TITLES)
        compose.setContent {
            MojaLossTheme {
                MojaLossBackdrop(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalCatalogRefresh provides { }) {
                        when (page.value) {
                            Destination.MY_LIST -> MyListScreen(snapshot, emptyMap(), { }, { removals++ })
                            else -> HomeScreen(snapshot, { }, { plays++ })
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Loading your library…").assertDoesNotExist()
        compose.onNodeWithText("Updating library…").assertDoesNotExist()
        compose.onNodeWithText("Play").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { page.value = Destination.MY_LIST }
        compose.onNodeWithText("Updating library…").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove Fixture one from My List")
            .performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, plays); assertEquals(1, removals) }
    }

    @Test fun refreshFailureRetainsActualTitlesWithoutInventingAnEmptyLibrary() {
        val snapshot = mutableStateOf(CatalogSnapshot(ContentStatus.ERROR, titles = FIXTURE_TITLES))
        var opened = 0
        compose.setContent {
            MojaLossTheme {
                MojaLossBackdrop(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalCatalogRefresh provides { }) {
                        BrowseScreen(snapshot.value, BrowseFilter(), { }, { opened++ })
                    }
                }
            }
        }
        compose.onNodeWithText("Couldn’t update titles")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your library is empty").assertDoesNotExist()
        compose.onNodeWithText("No titles match").assertDoesNotExist()
        compose.onNodeWithText("Fixture one").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opened); snapshot.value = snapshot.value.copy(status = ContentStatus.OFFLINE) }
        compose.onNodeWithText("Couldn’t update titles")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Fixture one").performScrollTo().assertIsDisplayed()
    }

    @Test fun designPreviewNeverOffersCatalogRefreshReportCopyPlayOrRemoveActions() {
        val page = mutableStateOf(Destination.HOME)
        val preview = CatalogSnapshot(ContentStatus.READY, titles = FIXTURE_TITLES, isDemo = true)
        compose.setContent {
            MojaLossTheme {
                MojaLossBackdrop(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        LocalCatalogRefresh provides { error("Preview cannot refresh a real library") },
                        LocalErrorReportCopy provides { error("Preview cannot copy a real account report") },
                    ) {
                        when (page.value) {
                            Destination.MY_LIST -> MyListScreen(preview, emptyMap(), { }, { error("Preview cannot remove a saved title") })
                            else -> HomeScreen(preview, { }, { error("Preview cannot start playback") })
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Refresh").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Copy error report").assertDoesNotExist()
        compose.onNodeWithText("Play").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { page.value = Destination.MY_LIST }
        compose.onNodeWithContentDescription("Remove Fixture one from My List")
            .performScrollTo().assertIsNotEnabled()
    }

    @Test fun pendingOrFailedInlineDetailsDoNotClaimSynopsisCastOrTrailerAreMissing() {
        val detailStatus = mutableStateOf(ContentStatus.LOADING)
        compose.setContent {
            MojaLossTheme {
                MojaLossBackdrop(Modifier.fillMaxSize()) {
                    TitleScreen(FIXTURE_TITLES.first(), FavoriteState(), emptyList(),
                        onBack = { }, onSave = { }, onPlay = { }, onEpisodePlay = { }, onTrailer = { },
                        detailStatus = detailStatus.value)
                }
            }
        }
        compose.onNodeWithText("Loading trailer…").assertDoesNotExist()
        compose.onNodeWithText("Watch trailer").assertDoesNotExist()
        compose.onNodeWithText("Trailer unavailable").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("details-synopsis-skeleton"))
        compose.onNodeWithTag("details-synopsis-skeleton").assertIsDisplayed()
        compose.onNodeWithText("A synopsis is not available for this title yet.").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Cast"))
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("details-cast-skeletons"))
        compose.onNodeWithTag("details-cast-skeletons").assertIsDisplayed()
        compose.onNodeWithText("Cast unavailable").assertDoesNotExist()
        compose.runOnIdle { detailStatus.value = ContentStatus.ERROR }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Cast could not load. Retry this title to update it."))
        compose.onNodeWithText("Cast could not load. Retry this title to update it.").assertIsDisplayed()
        compose.onNodeWithText("Cast unavailable").assertDoesNotExist()
        compose.runOnIdle { detailStatus.value = ContentStatus.READY }
        compose.onNodeWithText("Cast").assertDoesNotExist()
        compose.onNodeWithText("Cast unavailable").assertDoesNotExist()
        compose.onNodeWithTag("details-cast-skeletons").assertDoesNotExist()
        compose.onNodeWithText("Cast could not load. Retry this title to update it.").assertDoesNotExist()
    }

    @Test fun cachedDetailsCannotSaveUntilTheAccountIsVerified() {
        val canSave = mutableStateOf(false)
        var saves = 0
        compose.setContent {
            MojaLossTheme {
                MojaLossBackdrop(Modifier.fillMaxSize()) {
                    TitleScreen(FIXTURE_TITLES.first(), FavoriteState(), emptyList(),
                        onBack = { }, onSave = { saves++ }, onPlay = { }, onEpisodePlay = { }, onTrailer = { },
                        canSave = canSave.value)
                }
            }
        }
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { canSave.value = true }
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    private companion object {
        const val SAFE_FAILURE_REASON = "The server did not allow this library request. Check library access, or copy the error report."
        val FIXTURE_TITLES = listOf(
            MediaTitle("fixture-one", "Fixture one", isPlayable = true, favorite = true),
            MediaTitle("fixture-two", "Fixture two", isPlayable = true),
        )
    }
}

@androidx.compose.runtime.Composable
private fun CatalogFixturePage(destination: Destination, snapshot: CatalogSnapshot) {
    val querySnapshot = snapshot.copy(query = CatalogQuery(destination, search = if (destination == Destination.SEARCH) "Fixture" else ""))
    when (destination) {
        Destination.HOME -> HomeScreen(querySnapshot, { }, { })
        Destination.BROWSE -> BrowseScreen(querySnapshot, BrowseFilter(), { }, { })
        Destination.SEARCH -> SearchScreen(querySnapshot, "Fixture", { }, { })
        Destination.MY_LIST -> MyListScreen(querySnapshot, emptyMap(), { }, { })
    }
}
