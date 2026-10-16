package com.mojaloss.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import com.mojaloss.app.feature.ui.HomeScreen
import com.mojaloss.app.feature.ui.BrowseScreen
import com.mojaloss.app.feature.ui.LocalCatalogLoadMore
import com.mojaloss.app.feature.ui.LocalAccountAction
import com.mojaloss.app.feature.ui.LocalCatalogRefresh
import com.mojaloss.app.feature.ui.LocalErrorReportCopy
import com.mojaloss.app.feature.ui.MyListScreen
import com.mojaloss.app.feature.ui.SearchScreen
import com.mojaloss.core.model.CatalogSnapshot
import com.mojaloss.core.model.CatalogQuery
import com.mojaloss.core.model.Destination
import com.mojaloss.core.model.BrowseFilter
import com.mojaloss.core.model.ContentStatus
import com.mojaloss.core.model.MediaKind
import com.mojaloss.core.model.MediaTitle
import com.mojaloss.core.ui.MojaLossBackdrop
import com.mojaloss.core.ui.MojaLossTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Native pager gestures and catalog state changes with fixture titles; no provider calls. */
class HomeFeaturedPagerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun swipingBothWaysChangesTheTitleAndItsDetailsAndPlayActions() {
        val opened = mutableListOf<String>()
        val played = mutableListOf<String>()
        compose.setContent {
            MojaLossTheme {
                MojaLossBackdrop(Modifier.fillMaxSize()) {
                    HomeScreen(CatalogSnapshot(ContentStatus.READY, TITLES),
                        onTitle = { opened += it.id }, onPlay = { played += it.id })
                }
            }
        }
        swipeFeaturedLeft()
        compose.onNodeWithTag("featured-card-beta").assertIsDisplayed()
        assertFeaturedPosition(2, 3)
        compose.onNodeWithContentDescription("Details for Beta").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Play Beta").performScrollTo().assertIsEnabled().performClick()
        swipeFeaturedRight()
        compose.onNodeWithTag("featured-card-alpha").assertIsDisplayed()
        compose.onNodeWithContentDescription("Details for Alpha").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Play Alpha").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("beta", "alpha"), opened)
            assertEquals(listOf("beta", "alpha"), played)
        }
    }

    @Test fun selectedTitleSurvivesCatalogGrowthAndReorderingById() {
        val snapshot = mutableStateOf(CatalogSnapshot(ContentStatus.LOADING, TITLES.take(2)))
        val played = mutableListOf<String>()
        compose.setContent {
            MojaLossTheme {
                MojaLossBackdrop(Modifier.fillMaxSize()) {
                    HomeScreen(snapshot.value, onTitle = { }, onPlay = { played += it.id })
                }
            }
        }
        swipeFeaturedLeft()
        compose.onNodeWithTag("featured-card-beta").assertIsDisplayed()
        compose.runOnIdle { snapshot.value = CatalogSnapshot(ContentStatus.READY, TITLES) }
        assertFeaturedPosition(2, 3)
        // A refreshed page changes order, not the selected story. Stable pager keys
        // must move its numeric index without silently changing the Play target.
        compose.runOnIdle { snapshot.value = snapshot.value.copy(titles = listOf(TITLES[2], TITLES[0], TITLES[1])) }
        assertFeaturedPosition(3, 3)
        compose.onNodeWithContentDescription("Play Beta").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("beta"), played) }
    }

    @Test fun accountChangesResetThePagerAndRemovedTitlesCannotStaySelected() {
        val snapshot = mutableStateOf(CatalogSnapshot(ContentStatus.READY, TITLES))
        val account = mutableStateOf("fixture-account-a")
        compose.setContent {
            MojaLossTheme {
                MojaLossBackdrop(Modifier.fillMaxSize()) {
                    HomeScreen(snapshot.value, onTitle = { }, onPlay = { }, featuredScopeKey = account.value)
                }
            }
        }
        swipeFeaturedLeft()
        compose.onNode(hasText("All") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertDoesNotExist()
        compose.onNode(hasText("Movies") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertDoesNotExist()
        compose.runOnIdle { account.value = "fixture-account-b" }
        assertFeaturedPosition(1, 3)
        swipeFeaturedLeft()
        compose.runOnIdle { snapshot.value = snapshot.value.copy(titles = listOf(TITLES[0])) }
        compose.onNodeWithContentDescription("Details for Alpha").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Details for Beta").assertDoesNotExist()
        compose.onNodeWithTag("featured-page-indicator").assertDoesNotExist()
        assertFeaturedPosition(1, 1)
    }

    @Test fun namedNextPreviousControlsExposePositionAndDoNotAdvanceByThemselves() {
        compose.setContent {
            MojaLossTheme {
                MojaLossBackdrop(Modifier.fillMaxSize()) {
                    HomeScreen(CatalogSnapshot(ContentStatus.READY, TITLES), onTitle = { }, onPlay = { })
                }
            }
        }
        assertFalse(pagerActions().any { it.label == "Previous featured card" })
        assertTrue(invokePagerAction("Next featured card"))
        assertFeaturedPosition(2, 3)
        assertTrue(invokePagerAction("Previous featured card"))
        assertFeaturedPosition(1, 3)
        compose.mainClock.advanceTimeBy(30_000)
        assertFeaturedPosition(1, 3)
    }

    @Test fun cachedCheckingAndBackgroundUpdatesKeepTitlesVisibleAndFailuresOfferRecovery() {
        val snapshot = mutableStateOf(CatalogSnapshot(ContentStatus.READY, TITLES,
            isCached = true, isComplete = false, isConnectionVerified = false, hasMore = true))
        var retries = 0
        var reports = 0
        var pageRetries = 0
        compose.setContent {
            MojaLossTheme {
                MojaLossBackdrop(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        LocalCatalogRefresh provides { retries++ },
                        LocalCatalogLoadMore provides { pageRetries++ },
                        LocalErrorReportCopy provides { reports++ },
                    ) { HomeScreen(snapshot.value, onTitle = { }, onPlay = { }) }
                }
            }
        }
        compose.onNodeWithText("Saved library · checking connection…").assertDoesNotExist()
        compose.onNodeWithText("Refresh").assertDoesNotExist()
        compose.onNodeWithText("Loading your library…").assertDoesNotExist()
        compose.onNodeWithContentDescription("Play Alpha").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            snapshot.value = snapshot.value.copy(isConnectionVerified = true, isRefreshing = true)
        }
        compose.onNodeWithText("Updating library…").assertDoesNotExist()
        compose.onNodeWithText("Loading your library…").assertDoesNotExist()
        compose.onNodeWithContentDescription("Play Alpha").performScrollTo().assertIsEnabled()
        compose.runOnIdle { snapshot.value = snapshot.value.copy(isRefreshing = false) }
        compose.onNodeWithText("3 titles available").assertDoesNotExist()
        compose.onNodeWithText("3 titles in your library").assertDoesNotExist()
        compose.runOnIdle {
            snapshot.value = snapshot.value.copy(status = ContentStatus.ERROR, message = "Connection interrupted.", isConnectionVerified = false)
        }
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Show library error details").performScrollTo().performClick()
        compose.onNodeWithText("Copy error report").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, retries); assertEquals(1, reports); assertEquals(0, pageRetries)
            snapshot.value = snapshot.value.copy(isConnectionVerified = true)
        }
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, pageRetries) }
    }

    @Test fun incompleteCachedCatalogNeverClaimsSearchResultsOrSavedTitlesAreComplete() {
        val page = mutableStateOf(false)
        val query = mutableStateOf("Missing title")
        val snapshot = mutableStateOf(CatalogSnapshot(ContentStatus.READY, TITLES,
            isCached = true, isComplete = false, isConnectionVerified = true,
            query = CatalogQuery(Destination.SEARCH, search = query.value)))
        compose.setContent {
            MojaLossTheme {
                MojaLossBackdrop(Modifier.fillMaxSize()) {
                    if (page.value) MyListScreen(snapshot.value, emptyMap(), { }, { })
                    else SearchScreen(snapshot.value, query.value, { }, { })
                }
            }
        }
        compose.onNodeWithText("No results for", substring = true).assertDoesNotExist()
        compose.runOnIdle {
            query.value = "Long search " + "x".repeat(600)
            snapshot.value = snapshot.value.copy(
                titles = listOf(MediaTitle("long-result", "Long query result")),
                query = CatalogQuery(Destination.SEARCH, search = query.value.trim().take(512)))
        }
        compose.onNodeWithText("Long query result").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { page.value = true }
        compose.onNodeWithText("Keep a story for later").assertDoesNotExist()
        compose.onNodeWithText("Your library is empty").assertDoesNotExist()
    }

    @Test fun catalogPagingIsSilentAndRequiresActualNearEndDragOrAccessibilityDemand() {
        val page = mutableStateOf(Destination.BROWSE)
        val fixtureTitles = (1..24).map { MediaTitle("demand-$it", "Demand $it", isPlayable = true, favorite = true) }
        val snapshot = mutableStateOf(CatalogSnapshot(ContentStatus.READY, fixtureTitles,
            isComplete = false, hasMore = true, totalCount = 100))
        var demands = 0
        compose.setContent {
            MojaLossTheme {
                MojaLossBackdrop(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalCatalogLoadMore provides {
                        demands++
                        snapshot.value = snapshot.value.copy(isLoadingMore = true)
                    }) {
                        val current = snapshot.value.copy(query = CatalogQuery(page.value,
                            search = if (page.value == Destination.SEARCH) "Demand" else ""))
                        when (page.value) {
                            Destination.BROWSE -> BrowseScreen(current, BrowseFilter(), { }, { })
                            Destination.SEARCH -> SearchScreen(current, "Demand", { }, { })
                            Destination.MY_LIST -> MyListScreen(current, emptyMap(), { }, { })
                            Destination.HOME -> error("Home rows use independent shelf pages")
                        }
                    }
                }
            }
        }
        listOf(Destination.BROWSE, Destination.SEARCH, Destination.MY_LIST).forEachIndexed { index, destination ->
            val tag = when (destination) {
                Destination.BROWSE -> "browse-catalog"
                Destination.SEARCH -> "search-catalog"
                else -> "saved-catalog"
            }
            compose.runOnIdle {
                page.value = destination
                snapshot.value = CatalogSnapshot(ContentStatus.READY, fixtureTitles,
                    isComplete = false, hasMore = true, totalCount = 100)
            }
            compose.onNodeWithText("Load more").assertDoesNotExist()
            compose.onNodeWithTag(tag).performScrollToNode(hasText("Demand 24"))
            // Restoring or programmatic scrolling to the end never fetches a page.
            compose.runOnIdle { assertEquals(index * 2, demands) }
            // A real gesture at this near-end anchor starts only one page.
            compose.onNodeWithTag(tag).performTouchInput { swipeDown() }
            compose.runOnIdle {
                assertEquals(index * 2 + 1, demands)
                snapshot.value = snapshot.value.copy(isLoadingMore = false,
                    titles = fixtureTitles + (25..48).map { MediaTitle("demand-$it", "Demand $it", favorite = true) })
            }
            compose.onNodeWithText("Loading more…").assertDoesNotExist()
            compose.onNodeWithText("Load more").assertDoesNotExist()
            compose.runOnIdle { assertEquals(index * 2 + 1, demands) }
            val action = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.CustomActions]
                .first { it.label == "Load next titles" }
            compose.runOnIdle { assertTrue(action.action()) }
            compose.runOnIdle {
                assertEquals(index * 2 + 2, demands)
                snapshot.value = snapshot.value.copy(isLoadingMore = false, hasMore = false, isComplete = true)
            }
            compose.runOnIdle { assertEquals(index * 2 + 2, demands) }
        }
    }

    @Test fun compactBrowseCommitsOneServerFilterOnlyWhenDraftIsApplied() {
        val filters = mutableListOf<BrowseFilter>()
        var accountVisits = 0
        compose.setContent {
            MojaLossTheme {
                MojaLossBackdrop(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalAccountAction provides { accountVisits++ }) {
                        BrowseScreen(CatalogSnapshot(ContentStatus.READY, TITLES), BrowseFilter(), { filters += it }, { })
                    }
                }
            }
        }
        compose.onNodeWithText("Genre").assertDoesNotExist()
        compose.onNodeWithText("Year").assertDoesNotExist()
        compose.onNodeWithText("Refresh").assertDoesNotExist()
        compose.onNodeWithText("3 titles in your library").assertDoesNotExist()
        compose.onNodeWithText("Account").assertDoesNotExist()
        compose.onNodeWithContentDescription("Account").performClick()
        compose.onNodeWithText("Filters").performClick()
        compose.onNodeWithText("Genre").performScrollTo().assertIsDisplayed().performTextReplacement("Unloaded Genre")
        compose.onNodeWithText("Year").performScrollTo().assertIsDisplayed().performTextReplacement("1999")
        Espresso.closeSoftKeyboard()
        compose.runOnIdle { assertEquals(0, filters.size) }
        compose.onNodeWithText("Apply filters").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(BrowseFilter(genre = "Unloaded Genre", year = 1999)), filters)
            assertEquals(1, accountVisits)
        }
        compose.onNodeWithText("Genre").assertDoesNotExist()
    }

    private fun assertFeaturedPosition(page: Int, total: Int) = compose.onNodeWithTag("featured-pager").performScrollTo()
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "New releases. Featured card $page of $total"))

    private fun pagerActions() = compose.onNodeWithTag("featured-pager").fetchSemanticsNode().config[SemanticsActions.CustomActions]

    private fun invokePagerAction(label: String): Boolean {
        val action = pagerActions().first { it.label == label }.action
        var invoked = false
        compose.runOnIdle { invoked = action() }
        compose.waitForIdle()
        return invoked
    }

    private fun swipeFeaturedLeft() = compose.onNodeWithTag("featured-pager")
        .performScrollTo().performTouchInput { swipeLeft() }

    private fun swipeFeaturedRight() = compose.onNodeWithTag("featured-pager")
        .performScrollTo().performTouchInput { swipeRight() }

    private companion object {
        val TITLES = listOf(
            MediaTitle("alpha", "Alpha", isPlayable = true),
            MediaTitle("beta", "Beta", isPlayable = true),
            MediaTitle("gamma", "Gamma", kind = MediaKind.SERIES, isPlayable = true),
        )
    }
}
