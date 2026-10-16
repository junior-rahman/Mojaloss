package com.mojaloss.core.ui.motion

import android.database.ContentObserver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.mojaloss.core.model.AppPreferences

val LocalMotionPolicy = staticCompositionLocalOf { MotionPolicy(animationsEnabled = false) }

@Composable
fun rememberMotionPolicy(preferences: AppPreferences): MotionPolicy {
    val system by rememberSystemAnimationSettings()
    return motionPolicy(preferences, system)
}

@Composable
private fun rememberSystemAnimationSettings(): State<SystemAnimationSettings> {
    val context = LocalContext.current.applicationContext
    val resolver = context.contentResolver
    fun readSettings() = SystemAnimationSettings(
        animatorScale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
        transitionScale = Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f),
        windowScale = Settings.Global.getFloat(resolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f),
        powerSaveMode = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true,
    )
    val state = remember(resolver) { mutableStateOf(readSettings()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { state.value = readSettings() }
        }
        listOf(
            Settings.Global.ANIMATOR_DURATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            Settings.Global.WINDOW_ANIMATION_SCALE,
        ).forEach { resolver.registerContentObserver(Settings.Global.getUriFor(it), false, observer) }
        val powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { state.value = readSettings() }
        }
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(powerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(powerReceiver, filter)
        state.value = readSettings()
        onDispose { resolver.unregisterContentObserver(observer); context.unregisterReceiver(powerReceiver) }
    }
    return state
}

/** Visual feedback only. The clickable must share this source and call its action immediately. */
fun Modifier.motionPressFeedback(
    interactionSource: MutableInteractionSource,
    policy: MotionPolicy,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && policy.animationsEnabled) .985f else 1f,
        animationSpec = tween(policy.pressDurationMillis),
        label = "MojaLoss press feedback",
    )
    graphicsLayer {
        val currentScale = if (policy.animationsEnabled) scale else 1f
        scaleX = currentScale; scaleY = currentScale
    }
}

class ConfirmationFeedback internal constructor(
    private val gate: ConfirmationEventGate,
    private val preferences: () -> AppPreferences,
    private val view: () -> View,
) {
    /** Suppressed events are consumed too, so enabling haptics cannot replay old confirmations. */
    fun confirm(event: ConfirmationEvent): Boolean {
        if (!gate.consume(event) || !preferences().haptics) return false
        val target = view()
        if (!target.isAttachedToWindow || !target.isShown || !target.hasWindowFocus()) return false
        return target.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY,
        ) // No override flags: View honors the system touch-feedback preference.
    }
}

/** Call once above the navigation host; retain or inject the gate across transient screens. */
@Composable
fun rememberConfirmationFeedback(
    preferences: AppPreferences,
    gate: ConfirmationEventGate = remember { ConfirmationEventGate() },
): ConfirmationFeedback {
    val currentPreferences = rememberUpdatedState(preferences)
    val currentView = rememberUpdatedState(LocalView.current)
    return remember(gate) { ConfirmationFeedback(gate, { currentPreferences.value }, { currentView.value }) }
}
