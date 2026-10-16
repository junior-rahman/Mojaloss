package com.mojaloss.app.feature.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test

@UnstableApi
class PlayerPictureModeTest {
    @Test fun modesUseDistinctActualPlayerViewAspectPolicies() {
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, PlayerPictureMode.FIT.resizeMode)
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, PlayerPictureMode.CROP.resizeMode)
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, PlayerPictureMode.STRETCH.resizeMode)
    }

    @Test fun invalidOrAbsentStoredPreferenceAlwaysShowsTheWholePicture() {
        assertEquals(PlayerPictureMode.FIT, PlayerPictureMode.fromName(null))
        assertEquals(PlayerPictureMode.FIT, PlayerPictureMode.fromName("unknown"))
        assertEquals(PlayerPictureMode.CROP, PlayerPictureMode.fromName("CROP"))
        assertEquals(PlayerPictureMode.STRETCH, PlayerPictureMode.fromName("STRETCH"))
    }
}
