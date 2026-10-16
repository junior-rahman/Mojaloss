package com.mojaloss.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.mojaloss.app.feature.ui.FeaturedCarousel
import com.mojaloss.app.feature.ui.HomeScreen
import com.mojaloss.core.model.CatalogSnapshot
import com.mojaloss.core.model.ContentStatus
import com.mojaloss.core.model.MediaKind
import com.mojaloss.core.model.MediaTitle
import com.mojaloss.core.ui.MojaLossTheme
import com.mojaloss.core.ui.motion.LocalMotionPolicy
import com.mojaloss.core.ui.motion.MotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Actual native stack geometry and gestures with local titles; no provider or image URLs. */
class FeaturedStackMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bothDirectionsNudgeOutgoingInsideTheDeckWhileIncomingRisesFromUnderneath() {
        val played = mutableListOf<String>()
        val selected = mutableListOf<String>()
        compose.setContent { AnimatedFixture { FeaturedCarousel(TITLES, true, {}, { played += it.id },
            onSelectedIdChange = { selected += it }) } }
        val baseline = underBounds()
        compose.onNodeWithTag("featured-pager").performTouchInput {
            down(Offset(width * .80f, height * .45f))
            moveTo(Offset(width * .45f, height * .45f))
        }
        compose.onNodeWithContentDescription("Play Alpha").assertIsNotEnabled()
        val midLeft = underBounds()
        assertTrue(abs(midLeft.center.x - baseline.center.x) < 1.5f)
        assertTrue(midLeft.center.y < baseline.center.y)
        assertTrue(compose.onNodeWithTag("featured-front-layer").fetchSemanticsNode().boundsInRoot.center.x < baseline.center.x)
        compose.runOnIdle { assertEquals("alpha", selected.last()); assertTrue(played.isEmpty()) }
        capture("hero-left-midgesture-local-fixture")
        compose.onNodeWithTag("featured-pager").performTouchInput { up() }
        assertPosition(2, 3)
        assertEquals("Underneath Alpha", underDescription())
        compose.onNodeWithContentDescription("Play Beta").performClick()
        val restingPrevious = underBounds()
        compose.onNodeWithTag("featured-pager").performTouchInput {
            down(Offset(width * .20f, height * .45f))
            moveTo(Offset(width * .55f, height * .45f))
        }
        val midRight = underBounds()
        assertTrue(abs(midRight.center.x - restingPrevious.center.x) < 1.5f)
        assertTrue(midRight.center.y < restingPrevious.center.y)
        assertTrue(compose.onNodeWithTag("featured-front-layer").fetchSemanticsNode().boundsInRoot.center.x > restingPrevious.center.x)
        assertEquals("Underneath Alpha", compose.onNodeWithTag("featured-under-layer", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        compose.onNodeWithContentDescription("Play Beta").assertIsNotEnabled()
        capture("hero-right-midgesture-local-fixture")
        compose.onNodeWithTag("featured-pager").performTouchInput { up() }
        assertEquals("Underneath Beta", underDescription())
        compose.onNodeWithContentDescription("Play Alpha").performClick()
        compose.runOnIdle { assertEquals(listOf("beta", "alpha"), played) }
        capture("hero-settled-local-fixture")
    }

    @Test fun committedOutgoingReturnsCenteredBehindIncomingAndRemainsTheRearCard() {
        compose.setContent { AnimatedFixture(durationMillis = 1_000) { FeaturedCarousel(TITLES, true, {}, {}) } }
        val baseline = frontBounds()
        compose.mainClock.autoAdvance = false
        assertTrue(action("Next featured card").action())
        compose.mainClock.advanceTimeBy(250)
        val middleOutgoing = frontBounds()
        val middleIncoming = underBounds()
        val middleShift = abs(middleOutgoing.center.x - baseline.center.x)
        assertTrue("Outgoing uses a short gesture nudge, not a full-width exit", middleShift in 1f..baseline.width * .15f)
        assertTrue(abs(middleIncoming.center.x - baseline.center.x) < 1.5f)
        compose.mainClock.advanceTimeBy(500)
        val rearOutgoing = frontBounds()
        val risenIncoming = underBounds()
        assertTrue("Outgoing returns toward the deck center", abs(rearOutgoing.center.x - baseline.center.x) < middleShift)
        assertTrue("Outgoing recedes underneath the rising card", rearOutgoing.center.y > middleOutgoing.center.y)
        assertTrue(rearOutgoing.width < middleOutgoing.width)
        assertTrue(risenIncoming.width > middleIncoming.width)
        assertTrue(risenIncoming.center.y < middleIncoming.center.y)
        assertTrue(abs(risenIncoming.center.x - baseline.center.x) < 1.5f)
        capture("hero-outgoing-returning-underneath-local-fixture")
        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.autoAdvance = true
        assertPosition(2, 3)
        assertEquals("Underneath Alpha", underDescription())
        assertTrue(abs(frontBounds().center.x - baseline.center.x) < 1.5f)
        assertTrue(abs(frontBounds().width - baseline.width) < 1.5f)
    }

    @Test fun shortCancelledSwipeRestoresTheSameFrontCardWithoutRecyclingIt() {
        compose.setContent { AnimatedFixture { FeaturedCarousel(TITLES, true, {}, {}) } }
        val baseline = frontBounds()
        compose.onNodeWithTag("featured-pager").performTouchInput {
            down(Offset(width * .70f, height * .45f))
            moveTo(Offset(width * .58f, height * .45f), delayMillis = 600)
            up()
        }
        assertPosition(1, 3)
        compose.onNodeWithContentDescription("Play Alpha").assertIsDisplayed()
        assertEquals(baseline, frontBounds())
    }

    @Test fun artworkStartsAtTheDeckTopWithoutAVisibleHeaderCounterOrSeeAllBand() {
        compose.setContent { AnimatedFixture {
            Box(Modifier.fillMaxWidth().testTag("hero-host")) { FeaturedCarousel(TITLES, true, {}, {}, onSeeAll = {}) }
        } }
        compose.onNodeWithText("New releases").assertDoesNotExist()
        compose.onNodeWithText("See all").assertDoesNotExist()
        compose.onNodeWithTag("featured-page-indicator").assertDoesNotExist()
        val host = compose.onNodeWithTag("hero-host").fetchSemanticsNode().boundsInRoot
        val deck = compose.onNodeWithTag("featured-pager").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("featured-card-alpha").fetchSemanticsNode().boundsInRoot
        assertEquals("The deck must not reserve an invisible header band", host.top, deck.top, .1f)
        val tiltClearance = with(compose.density) { 8.dp.toPx() }
        assertEquals("Only the artwork's tilt clearance remains", tiltClearance, card.top - deck.top, 1f)
        assertPosition(1, 3)
        assertTrue(action("Next featured card").action())
        assertPosition(2, 3)
    }

    @Test fun reducedMotionSwitchesInstantlyAndKeepsAccessibleNavigationWithoutAutoAdvance() {
        compose.setContent { AnimatedFixture {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(false)) {
                FeaturedCarousel(TITLES, true, {}, {})
            }
        } }
        compose.onNodeWithTag("featured-under-layer", useUnmergedTree = true).assertDoesNotExist()
        val resting = compose.onNodeWithTag("featured-front-layer").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("featured-pager").performTouchInput {
            down(Offset(width * .80f, height * .45f)); moveTo(Offset(width * .40f, height * .45f))
        }
        assertEquals(resting, compose.onNodeWithTag("featured-front-layer").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("featured-pager").performTouchInput { up() }
        assertPosition(2, 3)
        assertTrue(action("Previous featured card").action())
        assertPosition(1, 3)
        assertTrue(action("Next featured card").action())
        assertPosition(2, 3)
        compose.mainClock.advanceTimeBy(30_000)
        assertPosition(2, 3)
        capture("hero-reduced-motion-local-fixture")
    }

    @Test fun verticalHomeGestureScrollsTheFeedWithoutChangingTheFeaturedTitle() {
        compose.setContent { AnimatedFixture {
            Box(Modifier.size(360.dp, 440.dp)) { HomeScreen(CatalogSnapshot(ContentStatus.READY, TITLES +
                (4..20).map { MediaTitle("local-$it", "Local title $it", MediaKind.MOVIE, genre = "Drama") }), {}, {}) }
        } }
        compose.onNodeWithTag("featured-pager").performScrollTo()
        val before = compose.onNodeWithTag("featured-pager").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("featured-pager").performTouchInput { swipeUp() }
        val after = compose.onNodeWithTag("featured-pager").fetchSemanticsNode().boundsInRoot.top
        assertTrue(after < before)
        // Bring it back into view without sending a horizontal gesture.
        compose.onNodeWithTag("featured-pager").performScrollTo()
        assertPosition(1, 8)
    }

    @Test fun catalogReorderKeepsCommittedIdAndADeletedTransitionTargetCannotBeSelected() {
        val titles = mutableStateOf(TITLES)
        val selected = mutableStateOf<String?>("alpha")
        val played = mutableListOf<String>()
        compose.setContent { AnimatedFixture { FeaturedCarousel(titles.value, true, {}, { played += it.id },
            selectedId = selected.value, onSelectedIdChange = { selected.value = it }) } }
        assertTrue(action("Next featured card").action())
        compose.onNodeWithContentDescription("Play Beta").assertIsDisplayed()
        compose.runOnIdle { titles.value = listOf(TITLES[2], TITLES[0], TITLES[1]) }
        assertPosition(3, 3)
        compose.onNodeWithContentDescription("Play Beta").performClick()
        compose.runOnIdle { assertEquals(listOf("beta"), played) }
        compose.mainClock.autoAdvance = false
        assertTrue(action("Previous featured card").action())
        compose.runOnIdle { titles.value = listOf(TITLES[2], TITLES[1]) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("Play Beta").assertIsDisplayed()
        compose.runOnIdle { assertEquals("beta", selected.value) }
    }

    @Test fun oneRealTitleSupportsDirectTerminalNavigationWithoutAnExtraCardOrButton() {
        var opened = 0
        var explored = 0
        var played = 0
        val active = mutableStateOf(true)
        compose.setContent { AnimatedFixture { FeaturedCarousel(TITLES.take(1), true, { opened++ }, { played++ },
            onSeeAll = { explored++; active.value = false }, active = active.value) } }
        compose.onNodeWithTag("featured-under-layer", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("featured-page-indicator").assertDoesNotExist()
        compose.onNodeWithTag("featured-see-all").assertDoesNotExist()
        compose.onNodeWithContentDescription("Details for Alpha").performClick()
        compose.onNodeWithText("See all").assertDoesNotExist()
        assertPosition(1, 1)
        compose.onNodeWithTag("featured-pager").performTouchInput { swipeRight() }
        assertPosition(1, 1)
        compose.runOnIdle { assertEquals(1, opened); assertEquals(1, explored); assertEquals(0, played) }
        // Returning to Home rearms discovery but retains the real title, including one-title libraries.
        compose.runOnIdle { active.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("featured-pager").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(2, explored); assertEquals(0, played) }
    }

    @Test fun physicalRightSwipeAtLastRealTitleOpensOnceAndPreviousRemainsAccessibleAfterBack() {
        val played = mutableListOf<String>()
        val opened = mutableListOf<String>()
        var explored = 0
        val active = mutableStateOf(true)
        compose.setContent { AnimatedFixture { FeaturedCarousel(TITLES, true, { opened += it.id }, { played += it.id },
            onSeeAll = { explored++; active.value = false }, active = active.value) } }
        repeat(2) { assertTrue(action("Next featured card").action()); compose.waitForIdle() }
        assertPosition(3, 3)
        compose.onNodeWithTag("featured-pager").performTouchInput { swipeRight() }
        assertPosition(3, 3)
        compose.runOnIdle { assertEquals(1, explored); assertTrue(played.isEmpty()); assertTrue(opened.isEmpty()) }
        // Extra pointer sequences while the page is away must not dispatch a second navigation.
        compose.onNodeWithTag("featured-pager").performTouchInput { swipeRight(); swipeLeft() }
        compose.runOnIdle { assertEquals(1, explored); active.value = true }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Play Gamma").assertIsDisplayed()
        assertTrue(action("Previous featured card").action())
        assertPosition(2, 3)
        compose.onNodeWithTag("featured-pager").performTouchInput { swipeRight() }
        assertPosition(1, 3)
        // The first-card right edge still resists rather than opening releases.
        compose.onNodeWithTag("featured-pager").performTouchInput { swipeRight() }
        assertPosition(1, 3)
        compose.runOnIdle { assertEquals(1, explored) }
        compose.onNodeWithContentDescription("Play Alpha").performClick()
        compose.runOnIdle { assertEquals(listOf("alpha"), played) }
        capture("hero-terminal-swipe-returned-real-title-local-fixture")
    }

    @Test fun shortTerminalSwipeCancelsAndCatalogChangeCannotDispatchAStaleTerminal() {
        val titles = mutableStateOf(TITLES)
        var explored = 0
        compose.setContent { AnimatedFixture { FeaturedCarousel(titles.value, true, {}, {},
            onSeeAll = { explored++ }, selectedId = "gamma") } }
        compose.onNodeWithTag("featured-pager").performTouchInput {
            down(Offset(width * .40f, height * .45f))
            moveTo(Offset(width * .52f, height * .45f), delayMillis = 600)
            up()
        }
        assertPosition(3, 3)
        compose.onNodeWithContentDescription("Play Gamma").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, explored) }
        compose.onNodeWithTag("featured-pager").performTouchInput {
            down(Offset(width * .20f, height * .45f)); moveTo(Offset(width * .65f, height * .45f))
        }
        compose.runOnIdle { titles.value = TITLES + TITLES[0].copy(id = "delta", title = "Delta") }
        compose.waitForIdle()
        compose.onNodeWithTag("featured-pager").performTouchInput { up() }
        assertPosition(3, 4)
        compose.runOnIdle { assertEquals(0, explored) }
    }

    @Test fun reducedMotionTerminalLeftSwipeNavigatesAndAccessibleExploreCannotDispatchTwice() {
        var explored = 0
        val active = mutableStateOf(true)
        compose.setContent { AnimatedFixture {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(false)) {
                FeaturedCarousel(TITLES.take(1), true, {}, {}, onSeeAll = { explored++; active.value = false }, active = active.value)
            }
        } }
        compose.onNodeWithTag("featured-pager").performTouchInput { swipeLeft() }
        assertPosition(1, 1)
        compose.runOnIdle { assertEquals(1, explored); active.value = true }
        compose.waitForIdle()
        val explore = action("Explore all new releases")
        compose.runOnIdle {
            assertTrue(explore.action())
            assertTrue(!explore.action())
            assertEquals(2, explored)
        }
        compose.onNodeWithTag("featured-under-layer", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("featured-see-all").assertDoesNotExist()
    }

    @Composable
    private fun AnimatedFixture(durationMillis: Int = 260, content: @Composable () -> Unit) {
        MojaLossTheme {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(true, transitionDurationMillis = durationMillis)) { content() }
        }
    }

    private fun assertPosition(page: Int, total: Int) = compose.onNodeWithTag("featured-pager")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "New releases. Featured card $page of $total"))

    private fun underBounds() = compose.onNodeWithTag("featured-under-layer", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun frontBounds() = compose.onNodeWithTag("featured-front-layer").fetchSemanticsNode().boundsInRoot
    private fun underDescription() = compose.onNodeWithTag("featured-under-layer", useUnmergedTree = true)
        .fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    private fun action(label: String): CustomAccessibilityAction = compose.onNodeWithTag("featured-pager")
        .fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }

    private fun capture(label: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "hero-stack-${BuildConfig.VERSION_NAME}-native-fixtures").apply { mkdirs() }
        val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val sampled = mutableSetOf<Int>()
        for (y in 0 until bitmap.height step (bitmap.height / 64).coerceAtLeast(1))
            for (x in 0 until bitmap.width step (bitmap.width / 64).coerceAtLeast(1)) sampled += bitmap.getPixel(x, y)
        assertTrue("Native Compose fixture capture must contain nonblank pixels", sampled.size >= 8)
        File(directory, "$label.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        File(directory, "$label.txt").writeText("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "class=${javaClass.name}\ncapture_method=Compose.onRoot.captureToImage\nstatus=NONBLANK_NATIVE_LOCAL_FIXTURE\n" +
            "sampled_colors=${sampled.size}\nNo real server artwork, provider traffic, physical Fold or video decoding proof.\n")
    }

    private companion object {
        val TITLES = listOf(
            MediaTitle("alpha", "Alpha", MediaKind.MOVIE, genre = "Drama", runtimeMs = 5_400_000, isPlayable = true),
            MediaTitle("beta", "Beta", MediaKind.MOVIE, genre = "Drama", runtimeMs = 5_400_000, isPlayable = true),
            MediaTitle("gamma", "Gamma", MediaKind.MOVIE, genre = "Drama", runtimeMs = 5_400_000, isPlayable = true))
    }
}
