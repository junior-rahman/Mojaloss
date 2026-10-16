package com.mojaloss.core.ui.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/** Finite fade for navigation. Playback routes use instant transfer of surface ownership. */
fun pageTransition(policy: MotionPolicy, playback: Boolean = false): ContentTransform =
    if (!policy.animationsEnabled || playback) EnterTransition.None togetherWith ExitTransition.None
    else fadeIn(tween(policy.pageDurationMillis)) togetherWith
        fadeOut(tween(policy.pageDurationMillis / 2))

/**
 * A single draw-layer reveal. It never changes list measurement, waits before an action,
 * duplicates outgoing controls, or runs while idle. Use a stable page/coarse phase key;
 * do not use transferred bytes, playback position or a whole mutable data object.
 * Turning motion off immediately restores opacity even if a reveal is in flight.
 */
fun Modifier.motionArrival(key: Any, policy: MotionPolicy): Modifier = composed {
    val alpha = remember(key) { Animatable(if (policy.animationsEnabled) 0f else 1f) }
    LaunchedEffect(key, policy.animationsEnabled) {
        if (policy.animationsEnabled) alpha.animateTo(1f, tween(policy.pageDurationMillis))
        else alpha.snapTo(1f)
    }
    graphicsLayer { this.alpha = if (policy.animationsEnabled) alpha.value else 1f }
}
