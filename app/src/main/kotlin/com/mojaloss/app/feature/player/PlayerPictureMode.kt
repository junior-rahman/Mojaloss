package com.mojaloss.app.feature.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout

/** A presentation preference only: the media source, clock and progress stay unchanged. */
internal enum class PlayerPictureMode(val label: String, val description: String) {
    FIT("Fit", "Show the whole picture. Black bars may remain."),
    CROP("Crop", "Fill the screen while keeping proportions. Edges are cropped."),
    STRETCH("Stretch", "Fill the screen without cropping. Picture proportions change.");

    companion object {
        fun fromName(name: String?): PlayerPictureMode = entries.firstOrNull { it.name == name } ?: FIT
    }
}

@get:UnstableApi
internal val PlayerPictureMode.resizeMode: Int
    get() = when (this) {
        PlayerPictureMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        PlayerPictureMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        PlayerPictureMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
