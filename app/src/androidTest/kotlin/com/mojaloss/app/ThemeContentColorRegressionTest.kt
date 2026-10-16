package com.mojaloss.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.mojaloss.core.ui.MojaLossColors
import com.mojaloss.core.ui.MojaLossPalettes
import com.mojaloss.core.ui.MojaLossTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Native pixels are required: the JVM Android stubs do not exercise Material text/icon drawing. */
class ThemeContentColorRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lightGradientContentUsesPaletteInkWithoutASurface() = verifyForeground(false, MojaLossPalettes.Light.Ink)
    @Test fun darkGradientContentUsesPaletteInkWithoutASurface() = verifyForeground(true, MojaLossPalettes.Dark.Ink)
    @Test fun artworkWhiteOverrideStillWinsInsideDarkTheme() = verifyForeground(true, Color.White, photo = true)

    private fun verifyForeground(dark: Boolean, expected: Color, photo: Boolean = false) {
        var inherited = Color.Unspecified
        compose.setContent {
            CompositionLocalProvider(LocalContentColor provides Color.Magenta) {
                MojaLossTheme(darkTheme = dark) {
                    val body: @androidx.compose.runtime.Composable () -> Unit = {
                        val color = LocalContentColor.current
                        SideEffect { inherited = color }
                        Column(Modifier.background(if (photo) Color(0xFF071B13) else MojaLossColors.Porcelain).padding(24.dp)) {
                            Icon(Icons.Outlined.Download, contentDescription = "Download", modifier = Modifier.size(48.dp))
                            Text("Watch offline")
                        }
                    }
                    if (photo) CompositionLocalProvider(LocalContentColor provides Color.White, content = body) else body()
                }
            }
        }
        compose.runOnIdle { assertEquals(expected, inherited) }
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        var painted = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            if (pixels[x, y].toArgb() == expected.toArgb()) painted++
        }
        assertTrue("Default icon/text must paint the intended content color; pixels=$painted", painted > 20)
    }
}
