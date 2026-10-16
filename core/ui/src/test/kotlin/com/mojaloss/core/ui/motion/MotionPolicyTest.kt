package com.mojaloss.core.ui.motion

import com.mojaloss.core.model.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPolicyTest {
    @Test fun reducedMotionAndEachSystemDisableOverrideNormalFeedback() {
        assertTrue(motionPolicy(AppPreferences(), SystemAnimationSettings()).animationsEnabled)
        val disabled = listOf(
            motionPolicy(AppPreferences(reducedMotion = true), SystemAnimationSettings()),
            motionPolicy(AppPreferences(), SystemAnimationSettings(animatorScale = 0f)),
            motionPolicy(AppPreferences(), SystemAnimationSettings(transitionScale = 0f)),
            motionPolicy(AppPreferences(), SystemAnimationSettings(windowScale = 0f)),
            motionPolicy(AppPreferences(), SystemAnimationSettings(powerSaveMode = true)),
        )
        disabled.forEach {
            assertFalse(it.animationsEnabled)
            assertEquals(0, it.pressDurationMillis)
            assertEquals(0, it.transitionDurationMillis)
            assertEquals(0, it.pageDurationMillis)
        }
        assertEquals(260, motionPolicy(AppPreferences(), SystemAnimationSettings(animatorScale = 2f)).transitionDurationMillis)
        assertEquals(180, motionPolicy(AppPreferences(), SystemAnimationSettings(animatorScale = 2f)).pageDurationMillis)
    }

    @Test fun batterySaverExitRestoresMotionOnlyWhenAllComfortSettingsAllowIt() {
        assertFalse(motionPolicy(AppPreferences(), SystemAnimationSettings(powerSaveMode = true)).animationsEnabled)
        assertTrue(motionPolicy(AppPreferences(), SystemAnimationSettings(powerSaveMode = false)).animationsEnabled)
        assertFalse(motionPolicy(AppPreferences(reducedMotion = true), SystemAnimationSettings(powerSaveMode = false)).animationsEnabled)
        assertFalse(motionPolicy(AppPreferences(), SystemAnimationSettings(animatorScale = 0f, powerSaveMode = false)).animationsEnabled)
    }

    @Test fun repeatedAndLateEventsCannotReplayConfirmation() {
        val gate = ConfirmationEventGate()
        assertTrue(gate.consume(ConfirmationEvent(1, "favorite:a", 3)))
        assertFalse(gate.consume(ConfirmationEvent(1, "favorite:a", 3)))
        assertFalse(gate.consume(ConfirmationEvent(1, "favorite:a", 2)))
        assertTrue(gate.consume(ConfirmationEvent(1, "favorite:b", 1)))
        assertTrue(gate.consume(ConfirmationEvent(1, "favorite:a", 4)))
        assertTrue(gate.consume(ConfirmationEvent(2, "favorite:a", 1)))
        assertFalse(gate.consume(ConfirmationEvent(1, "favorite:c", 9)))
    }
}
