package com.mojaloss.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class SageContrastTest {
    @Test fun ordinaryTextAndControlsRemainReadableOnEveryReadingSurface() {
        val surfaces = listOf(
            SageScheme.background, SageScheme.surface, SageScheme.surfaceVariant,
            SageScheme.surfaceContainerLowest, SageScheme.surfaceContainerLow,
            SageScheme.surfaceContainer, SageScheme.surfaceContainerHigh,
            SageScheme.surfaceContainerHighest,
        )
        val foregrounds = listOf(MojaLossPalettes.Light.Ink, MojaLossPalettes.Light.SecondaryInk, MojaLossPalettes.Light.Forest, MojaLossPalettes.Light.Error)
        surfaces.forEach { background -> foregrounds.forEach { text ->
            assertTrue("Reading text must retain 4.5:1 contrast", contrast(text, background) >= 4.5)
        } }
    }

    @Test fun FunctionalBordersRemainDistinctAfterCompositingOnGlassAndCanvas() {
        MojaLossPalettes.Light.BackdropStops.forEach { canvas ->
            val backgrounds = listOf(
                canvas,
                Color.White.copy(alpha = .92f).compositeOver(canvas),
                MojaLossPalettes.Light.Porcelain.copy(alpha = .96f).compositeOver(canvas),
            )
            backgrounds.forEach { background ->
                val border = MojaLossPalettes.Light.FunctionalOutline.compositeOver(background)
                assertTrue("A functional border must retain 3:1 after alpha compositing", contrast(border, background) >= 3.0)
            }
        }
    }

    @Test fun brandContainerAndErrorRolePairsRetainTextContrast() {
        listOf(
            SageScheme.onPrimary to SageScheme.primary,
            SageScheme.onPrimaryContainer to SageScheme.primaryContainer,
            SageScheme.onSecondaryContainer to SageScheme.secondaryContainer,
            SageScheme.onTertiaryContainer to SageScheme.tertiaryContainer,
            SageScheme.onError to SageScheme.error,
            SageScheme.onErrorContainer to SageScheme.errorContainer,
            SageScheme.inverseOnSurface to SageScheme.inverseSurface,
            SageScheme.inversePrimary to SageScheme.inverseSurface,
        ).forEach { (text, background) ->
            assertTrue("Theme role pairs must retain 4.5:1 contrast", contrast(text, background) >= 4.5)
        }
    }

    @Test fun darkReadingRolesAndAccentPairsMeetContrastOnAllMaterialSurfaces() {
        val palette = MojaLossPalettes.Dark
        val surfaces = listOf(ForestDarkScheme.background, ForestDarkScheme.surface,
            ForestDarkScheme.surfaceVariant, ForestDarkScheme.surfaceBright,
            ForestDarkScheme.surfaceContainerLowest, ForestDarkScheme.surfaceContainerLow,
            ForestDarkScheme.surfaceContainer, ForestDarkScheme.surfaceContainerHigh,
            ForestDarkScheme.surfaceContainerHighest)
        surfaces.forEach { surface ->
            listOf(palette.Ink, palette.SecondaryInk, palette.Forest, palette.Error).forEach { foreground ->
                assertTrue("Dark reading roles must retain 4.5:1 contrast", contrast(foreground, surface) >= 4.5)
            }
        }
        listOf(ForestDarkScheme.onPrimary to ForestDarkScheme.primary,
            ForestDarkScheme.onPrimaryContainer to ForestDarkScheme.primaryContainer,
            ForestDarkScheme.onSecondaryContainer to ForestDarkScheme.secondaryContainer,
            ForestDarkScheme.onError to ForestDarkScheme.error,
            ForestDarkScheme.onErrorContainer to ForestDarkScheme.errorContainer,
            ForestDarkScheme.inverseOnSurface to ForestDarkScheme.inverseSurface,
            ForestDarkScheme.inversePrimary to ForestDarkScheme.inverseSurface).forEach { (foreground, surface) ->
            assertTrue("Dark semantic role pairs must retain 4.5:1 contrast", contrast(foreground, surface) >= 4.5)
        }
    }

    @Test fun bothPalettesRemainReadableAndHaveFunctionalBordersOnTheirCompositedGlass() {
        listOf(MojaLossPalettes.Light, MojaLossPalettes.Dark).forEach { palette ->
            palette.BackdropStops.forEach { canvas ->
                listOf(canvas, palette.GlassTop.compositeOver(canvas), palette.GlassBottom.compositeOver(canvas),
                    palette.ControlGlass.compositeOver(canvas)).forEach { surface ->
                    assertTrue("Main text must retain 4.5:1 after glass composition", contrast(palette.Ink, surface) >= 4.5)
                    assertTrue("Secondary text must retain 4.5:1 after glass composition", contrast(palette.SecondaryInk, surface) >= 4.5)
                    assertTrue("Functional outlines must retain 3:1 after glass composition",
                        contrast(palette.FunctionalOutline.compositeOver(surface), surface) >= 3.0)
                }
            }
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }

    private fun luminance(color: Color): Double {
        fun linear(channel: Float): Double = channel.toDouble().let {
            if (it <= .04045) it / 12.92 else ((it + .055) / 1.055).pow(2.4)
        }
        return .2126 * linear(color.red) + .7152 * linear(color.green) + .0722 * linear(color.blue)
    }
}
