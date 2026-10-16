package com.mojaloss.core.ui.motion

import com.mojaloss.core.model.AppPreferences

/** Durations are not scaled here: Compose applies the system animator scale itself. */
data class MotionPolicy(
    val animationsEnabled: Boolean,
    val pressDurationMillis: Int = if (animationsEnabled) 100 else 0,
    val transitionDurationMillis: Int = if (animationsEnabled) 260 else 0,
    val pageDurationMillis: Int = if (animationsEnabled) 180 else 0,
)

data class SystemAnimationSettings(
    val animatorScale: Float = 1f,
    val transitionScale: Float = 1f,
    val windowScale: Float = 1f,
    val powerSaveMode: Boolean = false,
)

fun motionPolicy(
    preferences: AppPreferences,
    system: SystemAnimationSettings,
): MotionPolicy = MotionPolicy(
    animationsEnabled = !preferences.reducedMotion && !system.powerSaveMode &&
        system.animatorScale > 0f && system.transitionScale > 0f && system.windowScale > 0f,
)

/**
 * The owner emits this only for a confirmed action, not for a press or pending network write.
 * generation is the monotonically increasing session generation. key identifies the operation
 * stream (for example "favorite:title-id"); sequence increases in that stream.
 */
data class ConfirmationEvent(val generation: Long, val key: String, val sequence: Long)

/** Keep this gate at the app/session level, not inside a transient screen. */
class ConfirmationEventGate {
    private var generation: Long? = null
    private val latestSequences = mutableMapOf<String, Long>()

    @Synchronized
    fun consume(event: ConfirmationEvent): Boolean {
        val currentGeneration = generation
        if (currentGeneration != null && event.generation < currentGeneration) return false
        if (currentGeneration == null || event.generation > currentGeneration) {
            generation = event.generation
            latestSequences.clear()
        }
        val previous = latestSequences[event.key]
        if (previous != null && event.sequence <= previous) return false
        latestSequences[event.key] = event.sequence
        return true
    }
}
