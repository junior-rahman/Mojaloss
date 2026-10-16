package com.mojaloss.app.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerGesturePolicyTest {
    @Test fun seekPreviewUsesAUsableShortVideoSpanCapsLongVideoSensitivityAndStaysWithinDuration() {
        // A full viewport covers a quarter of a short video, but never more than two minutes.
        assertEquals(13_750L, gestureSeekPreview(10_000L, 60_000L, 100f, 400f))
        assertEquals(180_000L, gestureSeekPreview(60_000L, 7_200_000L, 400f, 400f))
        assertEquals(0L, gestureSeekPreview(5_000L, 60_000L, -400f, 400f))
        assertEquals(60_000L, gestureSeekPreview(55_000L, 60_000L, 400f, 400f))
    }

    @Test fun invalidSeekGeometryOrUnknownDurationDoesNotInventANewPosition() {
        assertEquals(61_000L, gestureSeekPreview(61_000L, 600_000L, 100f, 0f))
        assertEquals(61_000L, gestureSeekPreview(61_000L, 600_000L, 100f, -1f))
        assertEquals(61_000L, gestureSeekPreview(61_000L, 600_000L, 100f, Float.NaN))
        assertEquals(61_000L, gestureSeekPreview(61_000L, 600_000L, Float.NaN, 400f))
        assertEquals(61_000L, gestureSeekPreview(61_000L, 600_000L, Float.POSITIVE_INFINITY, 400f))
        assertEquals(61_000L, gestureSeekPreview(61_000L, 0L, 100f, 400f))
        assertEquals(0L, gestureSeekPreview(-1L, -1L, 100f, 400f))
        assertEquals(600_000L, gestureSeekPreview(900_000L, 600_000L, 100f, 0f))
    }

    @Test fun verticalLevelPreviewMovesInTheExpectedDirectionClampsAndRejectsInvalidMovement() {
        assertEquals(.70f, gestureLevelPreview(.50f, -100f, 500f), .0001f)
        assertEquals(.30f, gestureLevelPreview(.50f, 100f, 500f), .0001f)
        assertEquals(1f, gestureLevelPreview(.50f, -2_000f, 500f), .0001f)
        assertEquals(.05f, gestureLevelPreview(.50f, 2_000f, 500f, min = .05f), .0001f)
        assertEquals(.40f, gestureLevelPreview(.40f, -100f, 0f), .0001f)
        assertEquals(.40f, gestureLevelPreview(.40f, Float.NaN, 500f), .0001f)
        assertEquals(.50f, gestureLevelPreview(Float.NaN, 0f, 500f), .0001f)
    }

    @Test fun speedLabelsMatchTheActualSelectableRatesWithoutRawFloatNoise() {
        assertEquals("0.5×", speedLabel(.5f))
        assertEquals("0.75×", speedLabel(.75f))
        assertEquals("1×", speedLabel(1f))
        assertEquals("1.25×", speedLabel(1.25f))
        assertEquals("1.5×", speedLabel(1.5f))
        assertEquals("2×", speedLabel(2f))
    }
}
