package com.mojaloss.app

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mojaloss.app.feature.ui.BrowseScreen
import com.mojaloss.app.feature.ui.FeaturedCarousel
import com.mojaloss.app.feature.ui.HomeScreen
import com.mojaloss.app.feature.ui.ObserveSectionDemand
import com.mojaloss.core.model.BrowseFilter
import com.mojaloss.core.model.CatalogSnapshot
import com.mojaloss.core.model.ContentStatus
import com.mojaloss.core.model.HomeResumeState
import com.mojaloss.core.model.MediaTitle
import com.mojaloss.core.model.PlatformCollection
import com.mojaloss.core.model.PlatformCollectionsState
import com.mojaloss.core.ui.MojaLossTheme
import kotlinx.coroutines.flow.collect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Native local fixtures: these checks do not call the catalog or download media. */
class HomeDesign016RegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun featuredActionsStayInsideArtworkAndDeckHeightSurvivesDifferentCaptions() {
        compose.setContent { MojaLossTheme { FeaturedCarousel(listOf(MOVIE, LONG_TITLE), true, {}, {}) } }
        val first = compose.onNodeWithTag("featured-card-movie").fetchSemanticsNode().boundsInRoot
        val play = compose.onNodeWithTag("featured-play-movie").fetchSemanticsNode().boundsInRoot
        assertTrue(play.left >= first.left && play.right <= first.right)
        assertTrue(play.top >= first.top && play.bottom <= first.bottom)
        val next = compose.onNodeWithTag("featured-pager").fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Next featured card" }
        compose.runOnIdle { assertTrue(next.action()) }
        val second = compose.onNodeWithTag("featured-card-long").fetchSemanticsNode().boundsInRoot
        assertEquals(first.height, second.height, .1f)
        compose.onNodeWithContentDescription("Play A longer story about finding the way home").assertIsDisplayed()
        compose.onNodeWithText("New releases").assertDoesNotExist()
        compose.onNodeWithTag("featured-pager").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "New releases. Featured card 2 of 2"))
        compose.onNodeWithText("Recent uploads").assertDoesNotExist()
    }

    @Test fun largeFontFeaturedCaptionAndPlayRemainContainedInTheFullArtwork() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MojaLossTheme { FeaturedCarousel(listOf(LONG_TITLE), true, {}, {}) }
            }
        }
        val card = compose.onNodeWithTag("featured-card-long").fetchSemanticsNode().boundsInRoot
        val caption = compose.onNodeWithTag("featured-caption-long", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val play = compose.onNodeWithTag("featured-play-long").fetchSemanticsNode().boundsInRoot
        assertTrue(caption.top >= card.top && caption.bottom <= card.bottom)
        assertTrue(play.left >= card.left && play.right <= card.right && play.bottom <= card.bottom)
    }

    @Test fun continueWatchingCardResumesAndItsSeparateDetailsActionDoesNotPlay() {
        val played = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val episode = MOVIE.copy(id = "episode", title = "Episode two", confirmedResumeMs = 18_000)
        compose.setContent { MojaLossTheme {
            HomeScreen(CatalogSnapshot(ContentStatus.READY, listOf(MOVIE)), { opened += it.id }, { played += it.id },
                resumeState = HomeResumeState(ContentStatus.READY, listOf(episode)))
        } }
        compose.onNodeWithContentDescription("Resume Episode two").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Details for Episode two").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("episode"), played); assertEquals(listOf("episode"), opened) }
    }

    @Test fun recentlyUploadedWaitsForItsBottomViewportAndActiveFeed() {
        val active = mutableStateOf(false)
        var requests = 0
        var downloads = 0
        compose.setContent { MojaLossTheme {
            Box(Modifier.size(360.dp, 400.dp)) {
                HomeScreen(CatalogSnapshot(ContentStatus.READY, listOf(MOVIE)), {}, {}, feedActive = active.value,
                    platformState = PlatformCollectionsState(ContentStatus.READY, listOf(PlatformCollection("fixture-collection", "Fixture collection"))),
                    recentUploadsState = HomeResumeState(ContentStatus.READY, listOf(LONG_TITLE)),
                    onRequestRecentUploads = { requests++ }, onDownloads = { downloads++ })
            }
        } }
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithContentDescription("Downloads").performClick()
        compose.onNodeWithTag("home-catalog").performScrollToNode(hasTestTag("home-recent-uploads"))
        compose.runOnIdle { assertEquals(0, requests); assertEquals(1, downloads); active.value = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithText("Recently uploaded").assertIsDisplayed()
    }

    @Test fun initialSkeletonGridOffersOneLoadingAnnouncement() {
        compose.setContent { MojaLossTheme { BrowseScreen(CatalogSnapshot(ContentStatus.LOADING), BrowseFilter(), {}, {}) } }
        compose.onAllNodesWithContentDescription("Loading titles").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Loading title").assertCountEquals(0)
    }

    @Test fun resettingFiltersReturnsToNewestReleaseOrder() {
        var selected: BrowseFilter? = null
        compose.setContent { MojaLossTheme {
            BrowseScreen(CatalogSnapshot(ContentStatus.READY, listOf(MOVIE)), BrowseFilter(sort = "Title"), { selected = it }, {})
        } }
        compose.onNodeWithText("Filters · 1").performClick()
        compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithText("Apply filters").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Release newest", selected?.sort) }
    }

    @Test fun aReleasedFlingCanSpendOneDemandWithoutWalkingEveryPage() {
        var dragging = false
        var demands = 0
        var releasedDemand = false
        compose.setContent { MojaLossTheme {
            val state = rememberLazyListState()
            LaunchedEffect(state) { state.interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is DragInteraction.Start -> dragging = true
                    is DragInteraction.Stop, is DragInteraction.Cancel -> dragging = false
                    else -> Unit
                }
            } }
            ObserveSectionDemand(state, "fixture-release-fling", true) { demands++; releasedDemand = !dragging }
            LazyRow(state = state, modifier = Modifier.width(320.dp).height(180.dp).testTag("fixture-fling-row")) {
                items(12) { Box(Modifier.width(140.dp).height(170.dp)) }
            }
        } }
        compose.onNodeWithTag("fixture-fling-row").performTouchInput {
            swipe(Offset(width * .9f, height * .5f), Offset(width * .1f, height * .5f), durationMillis = 80)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, demands); assertTrue(releasedDemand) }
        compose.mainClock.advanceTimeBy(5_000)
        compose.runOnIdle { assertEquals(1, demands) }
    }

    private companion object {
        val MOVIE = MediaTitle("movie", "Home", isPlayable = true, year = 2026, genre = "Drama", runtimeMs = 5_400_000)
        val LONG_TITLE = MOVIE.copy(id = "long", title = "A longer story about finding the way home", genre = "Drama / Mystery")
    }
}
