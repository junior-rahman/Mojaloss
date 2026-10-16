package com.mojaloss.app.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Playback owns immersion for its entire visible lifetime, including paused playback. */
@Composable
internal fun ImmersivePlayerWindow() {
    val context = LocalContext.current
    val activity = remember(context) { context.playerWindowActivity() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(activity, owner) {
        val session = activity?.window?.let(::PlayerWindowImmersion)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) session?.hideSystemBars()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); session?.close() }
    }
}

/** A Material sheet has its own focused window; it does not inherit Activity bar visibility. */
@Composable
internal fun ImmersivePlayerSheetWindow() {
    val view = LocalView.current
    val dialogWindow = remember(view) { playerSheetWindow(view) }
    DisposableEffect(dialogWindow) {
        // Never fall back to Activity.window: the player already owns that session.
        val session = dialogWindow?.let(::PlayerWindowImmersion)
        onDispose { session?.close() }
    }
}

internal fun playerSheetWindow(view: View): Window? {
    if (view is DialogWindowProvider) return view.window
    var parent: ViewParent? = view.parent
    while (parent != null) {
        if (parent is DialogWindowProvider) return parent.window
        parent = parent.parent
    }
    return null
}

/** Restore only the state this session changes; other window owners keep their own flags. */
internal class PlayerWindowImmersion(private val window: Window) : AutoCloseable {
    private val view = window.decorView
    private val controller = WindowCompat.getInsetsController(window, view)
    private val previousBehavior = controller.systemBarsBehavior
    private val previousInsets = ViewCompat.getRootWindowInsets(view)
    private val previousStatusVisible = previousInsets?.isVisible(WindowInsetsCompat.Type.statusBars()) != false
    private val previousNavigationVisible = previousInsets?.isVisible(WindowInsetsCompat.Type.navigationBars()) != false
    private val previousCutoutMode = if (Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode else 0
    private var closed = false
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (focused) hideSystemBars()
    }

    init {
        // MainActivity enables edge-to-edge once. Do not reset its decor-fit policy here.
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        hideSystemBars()
    }

    fun hideSystemBars() {
        if (closed) return
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun close() {
        if (closed) return
        closed = true
        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = previousCutoutMode
        }
        controller.systemBarsBehavior = previousBehavior
        if (previousStatusVisible) controller.show(WindowInsetsCompat.Type.statusBars())
        else controller.hide(WindowInsetsCompat.Type.statusBars())
        if (previousNavigationVisible) controller.show(WindowInsetsCompat.Type.navigationBars())
        else controller.hide(WindowInsetsCompat.Type.navigationBars())
    }
}

private tailrec fun Context.playerWindowActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.playerWindowActivity() else null
    else -> null
}
