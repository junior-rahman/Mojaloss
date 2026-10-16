package com.mojaloss.app

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.media3.common.util.UnstableApi
import androidx.test.espresso.Espresso
import org.junit.Rule
import org.junit.Test

/** Native fixture checks: never authenticate, stream, or modify a real account. */
@UnstableApi
class PreviewJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun explicitPreviewSearchTitleBackAndSettingsPreserveOrigin() {
        compose.onNodeWithText("Connect to MojaLoss").assertExists()
        compose.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("Preview the design"))
        compose.onNodeWithText("Preview the design").performClick()
        compose.onNode(hasText("Design preview · Sample titles") and hasAnySibling(hasText("Exit preview"))).assertIsDisplayed()
        compose.onNodeWithText("Home", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Browse", useUnmergedTree = true).assertDoesNotExist()
        capture("home-artwork-preview")
        compose.onNodeWithTag("home-search").performClick()
        compose.onNodeWithText("Search movies and series").performTextInput("After")
        compose.onNode(hasText("After Hours") and hasClickAction()).performClick()
        compose.onNodeWithText("Watch trailer").assertDoesNotExist()
        capture("movie-artwork-preview")
        Espresso.pressBack()
        compose.onNodeWithText("Search movies and series").assertTextContains("After")
        compose.onNodeWithText("Search movies and series").performTextReplacement("no such sample title")
        compose.onNodeWithText("No results for", substring = true).assertExists()
        compose.onNodeWithContentDescription("Back to Home").performClick()
        compose.onNodeWithContentDescription("Account").performClick()
        compose.onNodeWithText("Settings").assertExists()
        compose.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("Reduce motion"))
        compose.onNodeWithText("Reduce motion").assertExists()
        Espresso.pressBack()
        compose.onNodeWithTag("home-search").performClick()
        compose.onNodeWithText("Search movies and series").assertTextContains("no such sample title")
    }

    @Test fun terminalRightSwipeOpensAllReleasesClearsOldFiltersAndReturnsToTheSameRealTitle() {
        compose.onNodeWithText("Connect to MojaLoss").assertExists()
        compose.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("Preview the design"))
        compose.onNodeWithText("Preview the design").performClick()
        var advances = 0
        while (true) {
            val next = compose.onNodeWithTag("featured-pager").performScrollTo().fetchSemanticsNode()
                .config[SemanticsActions.CustomActions].firstOrNull { it.label == "Next featured card" } ?: break
            assertTrue("The bounded preview deck must reach its last real title", advances++ < 8)
            compose.runOnIdle { assertTrue(next.action()) }
            compose.waitForIdle()
        }
        val lastPosition = compose.onNodeWithTag("featured-pager").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        compose.onNodeWithTag("featured-see-all").assertDoesNotExist()
        compose.onNodeWithTag("featured-pager").performTouchInput { swipeRight() }
        compose.onNodeWithText("Explore").assertIsDisplayed()
        compose.onNodeWithText("Filters").performClick()
        val allKind = hasText("All") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        val seriesKind = hasText("Series") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        val newestSort = hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Newest releases")
        compose.onNode(allKind).assertIsSelected()
        // Scroll the owning options list to the complete control, not a clipped inner Text
        // within a nested bottom sheet. A hidden sort label is not a usable control.
        compose.onNodeWithTag("browse-filter-options").performTouchInput { swipeUp() }
        compose.onNodeWithTag("browse-filter-options").performScrollToNode(newestSort)
        compose.waitForIdle()
        assertVisibleFilterControl(newestSort, "initial-sort").performClick()
        compose.onNodeWithText("Recently uploaded").performClick()
        compose.onNodeWithTag("browse-filter-options").performScrollToNode(seriesKind)
        assertVisibleFilterControl(seriesKind, "series-kind").performClick()
        compose.onNodeWithTag("browse-filter-options").performScrollToNode(hasText("Genre"))
        assertVisibleFilterControl(hasText("Genre"), "genre-input").performTextReplacement("Drama")
        compose.onNodeWithTag("browse-filter-options").performScrollToNode(hasText("Year"))
        assertVisibleFilterControl(hasText("Year"), "year-input").performTextReplacement("2000")
        Espresso.closeSoftKeyboard()
        val applyFilters = hasText("Apply filters") and hasClickAction()
        compose.onNodeWithTag("browse-filter-options").performTouchInput { swipeUp() }
        compose.onNodeWithTag("browse-filter-options").performScrollToNode(applyFilters)
        compose.waitForIdle()
        assertVisibleFilterControl(applyFilters, "apply-filters").performClick()
        compose.onNodeWithText("Filters · 4").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to Home").performClick()
        compose.onNodeWithTag("featured-pager").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, lastPosition))
        capture("hero-terminal-swipe-returned-preview")
        // A second physical right swipe resets every Explore facet, not just the heading.
        compose.onNodeWithTag("featured-pager").performTouchInput { swipeRight() }
        compose.onNodeWithText("Filters").performClick()
        compose.onNode(allKind).assertIsSelected()
        compose.onNodeWithTag("browse-filter-options").performScrollToNode(hasText("Genre"))
        assertEquals("", compose.onNodeWithText("Genre").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("browse-filter-options").performScrollToNode(hasText("Year"))
        assertEquals("", compose.onNodeWithText("Year").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("browse-filter-options").performScrollToNode(newestSort)
        compose.waitForIdle()
        assertVisibleFilterControl(newestSort, "reset-sort")
        Espresso.pressBack()
        compose.onNodeWithContentDescription("Back to Home").performClick()
        compose.onNodeWithTag("featured-pager").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, lastPosition))
    }

    /** Preserve small, textual owning-list/control geometry if a visible control regresses.
     * No Compose dialog bitmap or UiAutomation screenshot is accepted as window evidence. */
    private fun assertVisibleFilterControl(matcher: SemanticsMatcher, label: String): SemanticsNodeInteraction {
        val target = compose.onNode(matcher)
        try {
            target.assertIsDisplayed()
        } catch (failure: AssertionError) {
            runCatching {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val folder = File(context.filesDir, "design-${BuildConfig.VERSION_NAME}-preview-fixtures").apply { mkdirs() }
                val owner = compose.onNodeWithTag("browse-filter-options")
                File(folder, "filter-visibility-$label.txt").writeText(
                    "version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                        "status=FAILED_VISIBLE_CONTROL_ASSERTION\n" +
                        "fixture=Explicit MainActivity design preview; no provider/account traffic\n" +
                        "control=$label\n" +
                        "options_bounds_in_root=${owner.fetchSemanticsNode().boundsInRoot}\n" +
                        "control_bounds_in_root=${target.fetchSemanticsNode().boundsInRoot}\n" +
                        "options_semantics=\n${owner.printToString().take(16000)}\n" +
                        "control_semantics=\n${target.printToString().take(4000)}\n")
            }
            throw failure
        }
        return target
    }

    private fun capture(label: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.filesDir, "design-${BuildConfig.VERSION_NAME}-preview-fixtures").apply { mkdirs() }
        val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
        File(folder, "$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(folder, "$label.txt").writeText("Local labelled design preview. No customer authentication or media.\nversion=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
    }
}
