package com.mojaloss.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mojaloss.core.model.Destination
import com.mojaloss.core.ui.motion.LocalMotionPolicy
import com.mojaloss.core.ui.motion.motionPressFeedback

/** Immutable role values are also available to plain contrast tests and exported design references. */
data class MojaLossPalette(
    val Porcelain: Color, val Forest: Color, val OnForest: Color, val Sage: Color,
    val SelectedInk: Color, val NavigationGlass: Color, val Ink: Color, val SecondaryInk: Color,
    val Outline: Color, val Error: Color, val OnError: Color, val ErrorContainer: Color,
    val OnErrorContainer: Color, val SurfaceLow: Color, val SurfaceHigh: Color,
    val SurfaceBright: Color, val GlassTop: Color, val GlassBottom: Color, val GlassRim: Color,
    val ControlGlass: Color, val BackdropStops: List<Color>, val dark: Boolean,
) {
    val FunctionalOutline: Color = Outline.copy(alpha = .85f)
    val DecorativeOutline: Color = Outline.copy(alpha = if (dark) .50f else .65f)
}

object MojaLossPalettes {
    val Light = MojaLossPalette(
        Porcelain = Color(0xFFF1F4F1), Forest = Color(0xFF1E6654), OnForest = Color.White,
        Sage = Color(0xFFA8C7AE), SelectedInk = Color(0xFF173B2D),
        NavigationGlass = Color(0xFFDDE8E2), Ink = Color(0xFF172B25), SecondaryInk = Color(0xFF52665C),
        Outline = Color(0xFF587469), Error = Color(0xFF943C32), OnError = Color.White,
        ErrorContainer = Color(0xFFF6DDD8), OnErrorContainer = Color(0xFF5B211B),
        SurfaceLow = Color(0xFFF6F8F6), SurfaceHigh = Color(0xFFE7EEE8), SurfaceBright = Color.White,
        GlassTop = Color.White.copy(alpha = .92f), GlassBottom = Color(0xFFF1F4F1).copy(alpha = .96f),
        GlassRim = Color.White.copy(alpha = .86f), ControlGlass = Color.White.copy(alpha = .58f),
        BackdropStops = listOf(Color(0xFFE3EDE5), Color(0xFFF1F4F1), Color(0xFFEAF0EB), Color(0xFFDFEAE3)), dark = false,
    )
    val Dark = MojaLossPalette(
        Porcelain = Color(0xFF101B17), Forest = Color(0xFFB4D2BD), OnForest = Color(0xFF153B2A),
        Sage = Color(0xFF254D3C), SelectedInk = Color(0xFFDCECDF),
        NavigationGlass = Color(0xFF22382E), Ink = Color(0xFFEDF3EC), SecondaryInk = Color(0xFFB7C9BD),
        Outline = Color(0xFF90AC9A), Error = Color(0xFFFFB4A4), OnError = Color(0xFF4F231B),
        ErrorContainer = Color(0xFF552B21), OnErrorContainer = Color(0xFFFFDAD2),
        SurfaceLow = Color(0xFF15241B), SurfaceHigh = Color(0xFF263A2E), SurfaceBright = Color(0xFF304437),
        GlassTop = Color(0xFF253D30).copy(alpha = .94f), GlassBottom = Color(0xFF15241B).copy(alpha = .96f),
        GlassRim = Color(0xFFB4D2BD).copy(alpha = .22f), ControlGlass = Color(0xFF25392F).copy(alpha = .92f),
        BackdropStops = listOf(Color(0xFF14271D), Color(0xFF101B17), Color(0xFF182B22), Color(0xFF102119)), dark = true,
    )
}

private val LocalMojaLossPalette = staticCompositionLocalOf { MojaLossPalettes.Light }

/** Semantic lookups are local to this composition, never shared mutable global colors. */
object MojaLossColors {
    val Porcelain: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.Porcelain
    val Forest: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.Forest
    val OnForest: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.OnForest
    val Sage: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.Sage
    val SelectedInk: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.SelectedInk
    val NavigationGlass: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.NavigationGlass
    val Ink: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.Ink
    val SecondaryInk: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.SecondaryInk
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.Outline
    val Error: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.Error
    val OnError: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.OnError
    val ErrorContainer: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.ErrorContainer
    val OnErrorContainer: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.OnErrorContainer
    val SurfaceLow: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.SurfaceLow
    val SurfaceHigh: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.SurfaceHigh
    val SurfaceBright: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.SurfaceBright
    val GlassTop: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.GlassTop
    val GlassBottom: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.GlassBottom
    val GlassRim: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.GlassRim
    val ControlGlass: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.ControlGlass
    val FunctionalOutline: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.FunctionalOutline
    val DecorativeOutline: Color @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.DecorativeOutline
    val BackdropStops: List<Color> @Composable @ReadOnlyComposable get() = LocalMojaLossPalette.current.BackdropStops
}

/** Material surfaces and role pairs follow the approved palette in both modes. */
internal val SageScheme = paletteScheme(MojaLossPalettes.Light)
internal val ForestDarkScheme = paletteScheme(MojaLossPalettes.Dark)

private fun paletteScheme(palette: MojaLossPalette): ColorScheme {
    val base = if (palette.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = palette.Forest, onPrimary = palette.OnForest,
        primaryContainer = palette.Sage, onPrimaryContainer = palette.SelectedInk,
        secondary = palette.Forest, onSecondary = palette.OnForest,
        secondaryContainer = palette.NavigationGlass, onSecondaryContainer = palette.Ink,
        tertiary = palette.Forest, onTertiary = palette.OnForest,
        tertiaryContainer = palette.Sage, onTertiaryContainer = palette.SelectedInk,
        background = palette.Porcelain, onBackground = palette.Ink,
        surface = palette.Porcelain, onSurface = palette.Ink,
        surfaceVariant = palette.NavigationGlass, onSurfaceVariant = palette.SecondaryInk,
        outline = palette.Outline, outlineVariant = palette.FunctionalOutline,
        inverseSurface = palette.Ink, inverseOnSurface = palette.Porcelain,
        inversePrimary = if (palette.dark) MojaLossPalettes.Light.Forest else palette.Sage,
        surfaceTint = palette.Forest, surfaceBright = palette.SurfaceBright,
        surfaceDim = palette.NavigationGlass,
        surfaceContainerLowest = if (palette.dark) Color(0xFF0C1611) else Color.White,
        surfaceContainerLow = palette.SurfaceLow, surfaceContainer = palette.Porcelain,
        surfaceContainerHigh = palette.SurfaceHigh, surfaceContainerHighest = palette.NavigationGlass,
        error = palette.Error, onError = palette.OnError,
        errorContainer = palette.ErrorContainer, onErrorContainer = palette.OnErrorContainer,
    )
}

private val SageTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp)
)

@Composable
fun MojaLossTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (darkTheme) MojaLossPalettes.Dark else MojaLossPalettes.Light
    CompositionLocalProvider(LocalMojaLossPalette provides palette) {
        MaterialTheme(colorScheme = if (darkTheme) ForestDarkScheme else SageScheme,
            typography = SageTypography) {
            // A gradient Box is not a Material Surface and therefore cannot establish
            // onSurface itself. Give uncoloured text/icons the current palette's ink;
            // photographic controls can still provide white content locally.
            CompositionLocalProvider(LocalContentColor provides palette.Ink, content = content)
        }
    }
}

/** A static ambient canvas: no real-time backdrop blur, including over video. */
@Composable
fun MojaLossBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.background(
            Brush.linearGradient(MojaLossColors.BackdropStops)
        ),
        content = content
    )
}

/** Reading surfaces keep a dense backing beneath static translucent rims. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 20.dp,
    functionalBorder: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(MojaLossColors.GlassTop, MojaLossColors.GlassBottom)))
            .border(1.dp, if (functionalBorder) MojaLossColors.FunctionalOutline else MojaLossColors.DecorativeOutline, shape)
            .padding(2.dp)
            .border(1.dp, MojaLossColors.GlassRim, RoundedCornerShape((cornerRadius - 2.dp).coerceAtLeast(0.dp))),
        content = content
    )
}

@Composable
fun ForestButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    busy: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val policy = LocalMotionPolicy.current
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.heightIn(min = 52.dp).motionPressFeedback(interactionSource, policy),
        interactionSource = interactionSource,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 13.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MojaLossColors.Forest,
            contentColor = MojaLossColors.OnForest,
            disabledContainerColor = MojaLossColors.Forest.copy(alpha = .28f),
            disabledContentColor = MojaLossColors.Ink
        )
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MojaLossColors.Ink)
            Spacer(Modifier.width(9.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(9.dp))
        }
        Text(text)
    }
}

/** Pure navigation visuals. The app shell owns destinations and the back stack. */
@Composable
fun SageNavigation(selected: Destination, onSelected: (Destination) -> Unit, modifier: Modifier = Modifier) {
    val destinations = listOf(
        Triple(Destination.HOME, "Home", Icons.Outlined.Home),
        Triple(Destination.BROWSE, "Browse", Icons.Outlined.GridView),
        Triple(Destination.SEARCH, "Search", Icons.Outlined.Search),
        Triple(Destination.MY_LIST, "My List", Icons.Outlined.BookmarkBorder)
    )
    NavigationBar(
        modifier = modifier.clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).border(
            1.dp, MojaLossColors.GlassRim, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ),
        containerColor = MojaLossColors.NavigationGlass.copy(alpha = .96f),
        tonalElevation = 0.dp
    ) {
        destinations.forEach { (destination, label, icon) ->
            NavigationBarItem(
                selected = selected == destination,
                onClick = { onSelected(destination) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MojaLossColors.SelectedInk,
                    selectedTextColor = MojaLossColors.SelectedInk,
                    indicatorColor = MojaLossColors.Sage,
                    unselectedIconColor = MojaLossColors.Ink,
                    unselectedTextColor = MojaLossColors.Ink
                )
            )
        }
    }
}
