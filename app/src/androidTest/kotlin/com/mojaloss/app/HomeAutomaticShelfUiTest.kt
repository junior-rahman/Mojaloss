package com.mojaloss.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.mojaloss.app.feature.ui.HomeScreen
import com.mojaloss.core.model.CatalogSnapshot
import com.mojaloss.core.model.ContentStatus
import com.mojaloss.core.model.HomeResumeState
import com.mojaloss.core.model.HomeShelfKind
import com.mojaloss.core.model.HomeShelfState
import com.mojaloss.core.model.MediaKind
import com.mojaloss.core.model.MediaTitle
import com.mojaloss.core.model.PlatformCollection
import com.mojaloss.core.ui.MojaLossTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Local native UI fixtures, with real-source identities supplied by the repository contract. */
class HomeAutomaticShelfUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun severalMatchingSourcesShowAutomaticContentAndExactSeeAllWithoutChooser() {
        val opened = mutableListOf<String>()
        val explored = mutableListOf<PlatformCollection>()
        var choices = 0
        compose.setContent { MojaLossTheme {
            HomeScreen(EMPTY_CATALOG, { opened += it.id }, {}, shelfStates = mapOf(
                HomeShelfKind.HINDI_MOVIES to HomeShelfState(ContentStatus.READY, listOf(TITLE),
                    sources = listOf(SOURCE_A, SOURCE_B), selectedSource = SOURCE_B)),
                onShelfSourceSelected = { _, _ -> choices++ },
                onShelfSeeAll = { _, source -> explored += source })
        } }
        compose.onNodeWithText("Hindi movies").assertIsDisplayed()
        compose.onAllNodesWithText("Choose a group").assertCountEquals(0)
        compose.onAllNodesWithText("Choose categories").assertCountEquals(0)
        compose.onNodeWithText(SOURCE_A.name).assertDoesNotExist()
        compose.onNodeWithText(SOURCE_B.name).assertDoesNotExist()
        compose.onNodeWithContentDescription("Fixture Hindi movie · 2026 · Drama").performClick()
        compose.onNodeWithText("See all").performClick()
        compose.runOnIdle {
            assertEquals(listOf(TITLE.id), opened)
            assertEquals(listOf(SOURCE_B), explored)
            assertEquals(0, choices)
        }
    }

    @Test fun confirmedEmptyLanguageShelfCollapsesWithoutCategoryOrEmptyPanel() {
        compose.setContent { MojaLossTheme {
            HomeScreen(EMPTY_CATALOG, {}, {}, shelfStates = mapOf(
                HomeShelfKind.HINDI_MOVIES to HomeShelfState(ContentStatus.READY,
                    sources = listOf(SOURCE_A, SOURCE_B), selectedSource = SOURCE_B)))
        } }
        compose.onNodeWithTag("shelf-anchor-HINDI_MOVIES").assertIsDisplayed()
        compose.onNodeWithText("Hindi movies").assertDoesNotExist()
        compose.onAllNodesWithText("Choose a group").assertCountEquals(0)
        compose.onNodeWithText("This group has no matching titles yet.").assertDoesNotExist()
    }

    @Test fun automaticShelvesStillWaitForTheirViewportAndActiveHome() {
        val active = mutableStateOf(false)
        val requests = mutableListOf<HomeShelfKind>()
        compose.setContent { MojaLossTheme {
            Box(Modifier.size(360.dp, 400.dp)) {
                HomeScreen(EMPTY_CATALOG, {}, {}, feedActive = active.value,
                    onRequestShelf = { requests += it })
            }
        } }
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
        compose.onNodeWithTag("home-catalog").performScrollToNode(hasTestTag("shelf-HINDI_MOVIES"))
        compose.runOnIdle { assertTrue(requests.isEmpty()); active.value = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(HomeShelfKind.HINDI_MOVIES in requests)
            assertTrue(HomeShelfKind.INTERNATIONAL_SHOWS !in requests)
        }
    }

    @Test fun internationalMoviesAndSeriesShowAutomaticContentAndDemandOnlyTheirVisibleRows() {
        val requests = mutableListOf<HomeShelfKind>()
        val opened = mutableListOf<String>()
        val internationalMovie = MediaTitle("international-movie", "Fixture international movie", year = 2026, genre = "Drama")
        val internationalSeries = MediaTitle("international-series", "Fixture international series", MediaKind.SERIES, year = 2025, genre = "Drama")
        val states = HomeShelfKind.entries.associateWith { kind ->
            when (kind) {
                HomeShelfKind.INTERNATIONAL_MOVIES -> HomeShelfState(ContentStatus.READY, listOf(internationalMovie))
                HomeShelfKind.INTERNATIONAL_SHOWS -> HomeShelfState(ContentStatus.READY, listOf(internationalSeries))
                else -> HomeShelfState(ContentStatus.READY)
            }
        }
        compose.setContent { MojaLossTheme {
            Box(Modifier.size(360.dp, 400.dp)) {
                HomeScreen(EMPTY_CATALOG, { opened += it.id }, {}, shelfStates = states,
                    onRequestShelf = { requests += it })
            }
        } }
        compose.onNodeWithTag("home-catalog").performScrollToNode(hasTestTag("shelf-INTERNATIONAL_MOVIES"))
        compose.onNodeWithText("International movies").assertIsDisplayed()
        compose.onNodeWithContentDescription("Fixture international movie · 2026 · Drama").performClick()
        compose.onNodeWithTag("home-catalog").performScrollToNode(hasTestTag("shelf-INTERNATIONAL_SHOWS"))
        compose.onNodeWithText("International series").assertIsDisplayed()
        compose.onNodeWithContentDescription("Fixture international series · 2025 · Drama").performClick()
        compose.onAllNodesWithText("Choose categories").assertCountEquals(0)
        compose.onAllNodesWithText("Choose a group").assertCountEquals(0)
        compose.runOnIdle {
            assertEquals(listOf(internationalMovie.id, internationalSeries.id), opened)
            assertTrue(HomeShelfKind.INTERNATIONAL_MOVIES in requests)
            assertTrue(HomeShelfKind.INTERNATIONAL_SHOWS in requests)
        }
    }

    @Test fun recentUploadFailureRetainsPlayableCardsAndCompactExplicitRetry() {
        val state = mutableStateOf(HomeResumeState(ContentStatus.ERROR, listOf(TITLE)))
        var retries = 0
        val opened = mutableListOf<String>()
        compose.setContent { MojaLossTheme {
            HomeScreen(EMPTY_CATALOG, { opened += it.id }, {}, recentUploadsState = state.value,
                onRequestRecentUploads = { retries++ })
        } }
        // Initial viewport demand is separate from the explicit recovery action.
        compose.waitForIdle()
        var beforeRetry = 0
        compose.runOnIdle { beforeRetry = retries }
        compose.onNodeWithContentDescription("Fixture Hindi movie · 2026 · Drama").performClick()
        compose.onNodeWithText("Couldn’t update. Available titles are still here.").assertIsDisplayed()
        compose.onNodeWithTag("recent-uploads-retry").performClick()
        compose.runOnIdle {
            assertEquals(listOf(TITLE.id), opened)
            assertEquals(beforeRetry + 1, retries)
            state.value = state.value.copy(status = ContentStatus.LOADING)
        }
        compose.onNodeWithContentDescription("Fixture Hindi movie · 2026 · Drama").assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading recently uploaded titles").assertDoesNotExist()
    }

    private companion object {
        val EMPTY_CATALOG = CatalogSnapshot(ContentStatus.READY, isComplete = false)
        val SOURCE_A = PlatformCollection("source-a", "Fixture Hindi library A")
        val SOURCE_B = PlatformCollection("source-b", "Fixture Hindi library B")
        val TITLE = MediaTitle("hindi-movie", "Fixture Hindi movie", year = 2026, genre = "Drama")
    }
}
