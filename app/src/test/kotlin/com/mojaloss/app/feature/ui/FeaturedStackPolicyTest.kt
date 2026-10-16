package com.mojaloss.app.feature.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturedStackPolicyTest {
    @Test fun eitherTerminalSwipeOpensReleasesWithoutChangingEarlierCardDirections() {
        for (total in listOf(1, 2, 8)) {
            for (delta in listOf(-1, 1)) assertTrue(featuredSwipeOpensReleases(total - 1, total, delta, true))
            for (page in 0 until total - 1) for (delta in listOf(-1, 1))
                assertTrue(!featuredSwipeOpensReleases(page, total, delta, true))
        }
    }

    @Test fun cancelledSwipesAndUnavailableDiscoveryCannotOpenAReleasePage() {
        assertTrue(!featuredSwipeOpensReleases(0, 0, 1, true))
        assertTrue(!featuredSwipeOpensReleases(0, 1, null, true))
        assertTrue(!featuredSwipeOpensReleases(0, 1, -1, false))
        assertTrue(!featuredSwipeOpensReleases(0, 1, 1, false))
    }

    @Test fun incomingIsAlwaysCenteredAndRisesAsEitherOutgoingDirectionAdvances() {
        val resting = featuredIncomingTransform(0f, 24f, true)
        for (direction in listOf(-1, 1)) {
            var previousY = resting.translationY
            for (distance in listOf(0f, 75f, 150f, 225f, 300f, 330f)) {
                val progress = kotlin.math.abs(distance * direction) / 300f
                val transform = featuredIncomingTransform(progress, 24f, true)
                assertEquals(0f, transform.translationX, 0f)
                assertTrue(transform.translationY >= 0f && transform.translationY <= previousY)
                assertTrue(transform.scale in .94f..1f)
                previousY = transform.translationY
            }
            assertEquals(0f, previousY, 0f)
        }
    }

    @Test fun reducedMotionKeepsIncomingAtFinalGeometryAndNeverTiltsOrSlidesIt() {
        for (progress in listOf(0f, .25f, .5f, .75f, 1f)) {
            assertEquals(FeaturedIncomingTransform(0f, 0f, 1f), featuredIncomingTransform(progress, 24f, false))
            for (direction in listOf(-1, 1)) assertEquals(FeaturedOutgoingTransform(),
                featuredOutgoingTransform(progress, 300f, 24f, direction, false))
        }
    }

    @Test fun outgoingReturnsToTheCenteredRearSlotInBothDirectionsInsteadOfLeavingTheDeck() {
        for (direction in listOf(-1, 1)) {
            var previousY = 0f
            var previousScale = 1f
            for (step in 0..100) {
                val progress = step / 100f
                val outgoing = featuredOutgoingTransform(progress, 300f, 24f, direction, true)
                assertTrue("A card must remain inside the deck's small gesture arc", kotlin.math.abs(outgoing.translationX) <= 42f)
                assertTrue(outgoing.translationX * direction >= 0f)
                assertTrue(outgoing.translationY >= previousY)
                assertTrue(outgoing.scale <= previousScale)
                assertEquals(progress >= .5f, outgoing.behindIncoming)
                previousY = outgoing.translationY
                previousScale = outgoing.scale
            }
            assertEquals(FeaturedOutgoingTransform(translationY = 24f, scale = .94f, behindIncoming = true),
                featuredOutgoingTransform(1f, 300f, 24f, direction, true))
            assertEquals(FeaturedOutgoingTransform(), featuredOutgoingTransform(0f, 300f, 24f, direction, true))
        }
    }

    @Test fun outgoingDirectionalArcMirrorsAndReturnsWhileIncomingStaysCentered() {
        for (progress in listOf(.1f, .25f, .5f, .75f, .9f)) {
            val left = featuredOutgoingTransform(progress, 300f, 24f, -1, true)
            val right = featuredOutgoingTransform(progress, 300f, 24f, 1, true)
            assertEquals(-left.translationX, right.translationX, 0f)
            assertEquals(-left.rotationZ, right.rotationZ, 0f)
            assertEquals(left.scale, right.scale, 0f)
            assertEquals(left.translationY, right.translationY, 0f)
            assertEquals(0f, featuredIncomingTransform(progress, 24f, true).translationX, 0f)
        }
        assertTrue(kotlin.math.abs(featuredOutgoingTransform(.9f, 300f, 24f, -1, true).translationX) <
            kotlin.math.abs(featuredOutgoingTransform(.5f, 300f, 24f, -1, true).translationX))
    }

    @Test fun uncommittedDraggingCannotSwapDepthOrPullTheCardOutOfTheDeck() {
        for (displacement in listOf(-3_000f, -300f, -150f, -75f, 0f, 75f, 150f, 300f, 3_000f)) {
            val progress = featuredDragProgress(displacement, 300f)
            assertTrue(progress in 0f.. .45f)
            assertTrue(!featuredOutgoingTransform(progress, 300f, 24f, if (displacement < 0) -1 else 1, true).behindIncoming)
        }
        assertEquals(.25f, featuredDragProgress(-75f, 300f), 0f)
        assertEquals(.45f, featuredDragProgress(300f, 300f), 0f)
    }

    @Test fun horizontalCommitRequiresDeliberateDistanceOrFlingWithARealDisplacement() {
        assertEquals(1, featuredSwipeDelta(-90f, 300f, 0f, 850f))
        assertEquals(-1, featuredSwipeDelta(90f, 300f, 0f, 850f))
        assertNull(featuredSwipeDelta(5f, 300f, 4_000f, 850f))
        assertNull(featuredSwipeDelta(45f, 300f, 100f, 850f))
        assertEquals(1, featuredSwipeDelta(-45f, 300f, -1_000f, 850f))
    }
}
