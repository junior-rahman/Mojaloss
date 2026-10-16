package com.mojaloss.app

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.mojaloss.app.feature.ui.HomeScreen
import com.mojaloss.app.feature.ui.SearchScreen
import com.mojaloss.core.model.CatalogQuery
import com.mojaloss.core.model.CatalogSnapshot
import com.mojaloss.core.model.ContentStatus
import com.mojaloss.core.model.Destination
import com.mojaloss.core.model.MediaTitle
import com.mojaloss.core.ui.MojaLossBackdrop
import com.mojaloss.core.ui.MojaLossColors
import com.mojaloss.core.ui.MojaLossPalettes
import com.mojaloss.core.ui.MojaLossTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Android UI_MODE_NIGHT configuration fixture; exercises the default system-theme API, never a theme boolean override. */
class ThemeFollowSystemTest {
    @get:Rule val compose = createComposeRule()

    @Test fun systemNightFlagChangesSemanticAndMaterialColorsWithoutResettingFeaturedSelection() {
        val configuration = mutableStateOf(configuration(night = false))
        var background = Color.Unspecified
        var foreground = Color.Unspecified
        var accent = Color.Unspecified
        val played = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration.value) {
                MojaLossTheme {
                    val currentBackground = MaterialTheme.colorScheme.background
                    val currentForeground = MojaLossColors.Ink
                    val currentAccent = MojaLossColors.Forest
                    SideEffect { background = currentBackground; foreground = currentForeground; accent = currentAccent }
                    MojaLossBackdrop(Modifier.fillMaxSize()) {
                        HomeScreen(CatalogSnapshot(ContentStatus.READY, listOf(
                            MediaTitle("local-alpha", "Alpha", isPlayable = true),
                            MediaTitle("local-beta", "Beta", isPlayable = true))), {}, { played += it.id })
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(MojaLossPalettes.Light.Porcelain, background)
            assertEquals(MojaLossPalettes.Light.Ink, foreground)
            assertEquals(MojaLossPalettes.Light.Forest, accent)
        }
        val next = compose.onNodeWithTag("featured-pager").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].first { it.label == "Next featured card" }
        assertTrue(next.action())
        compose.onNodeWithContentDescription("Play Beta").assertIsDisplayed()
        capture("home-light-beta-local-fixture")
        compose.runOnIdle { configuration.value = configuration(night = true) }
        compose.onNodeWithContentDescription("Play Beta").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(MojaLossPalettes.Dark.Porcelain, background)
            assertEquals(MojaLossPalettes.Dark.Ink, foreground)
            assertEquals(MojaLossPalettes.Dark.Forest, accent)
            assertEquals(listOf("local-beta"), played)
        }
        capture("home-dark-beta-local-fixture")
        compose.runOnIdle { configuration.value = configuration(night = false) }
        compose.onNodeWithContentDescription("Play Beta").assertIsDisplayed()
    }

    @Test fun systemNightFlagPreservesTypedSearchAndItsCurrentScreen() {
        val configuration = mutableStateOf(configuration(night = false))
        var queryChanges = 0
        var retainedQuery = ""
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration.value) {
                MojaLossTheme {
                    var query by rememberSaveable { mutableStateOf("") }
                    // Observe the saved state in this composition scope. Reading its delegated
                    // getter only inside SideEffect leaves this probe stale when just the
                    // nested SearchScreen composition is invalidated by its real text callback.
                    val committedQuery = query
                    SideEffect { retainedQuery = committedQuery }
                    MojaLossBackdrop(Modifier.fillMaxSize()) {
                        SearchScreen(CatalogSnapshot(ContentStatus.READY, emptyList(),
                            query = CatalogQuery(Destination.SEARCH, search = committedQuery)), committedQuery,
                            onQuery = { query = it; queryChanges++ }, onTitle = {})
                    }
                }
            }
        }
        compose.onNodeWithText("Search movies and series").performTextReplacement("Winter")
        compose.onNodeWithText("Winter").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Winter", retainedQuery); assertEquals(1, queryChanges) }
        capture("search-light-winter-local-fixture")
        compose.runOnIdle { configuration.value = configuration(night = true) }
        compose.onNodeWithText("Winter").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Winter", retainedQuery); assertEquals(1, queryChanges) }
        capture("search-dark-winter-local-fixture")
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("Search movies and series").assertIsDisplayed()
        compose.runOnIdle { assertEquals("", retainedQuery); assertEquals(2, queryChanges) }
    }

    private fun configuration(night: Boolean): Configuration = Configuration(
        InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }

    private fun capture(label: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "system-theme-${BuildConfig.VERSION_NAME}-native-fixtures").apply { mkdirs() }
        val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val sampled = mutableSetOf<Int>()
        for (y in 0 until bitmap.height step (bitmap.height / 64).coerceAtLeast(1))
            for (x in 0 until bitmap.width step (bitmap.width / 64).coerceAtLeast(1)) sampled += bitmap.getPixel(x, y)
        assertTrue("Native theme fixture must have nonblank pixels", sampled.size >= 8)
        File(directory, "$label.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        File(directory, "$label.txt").writeText("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "class=${javaClass.name}\ncapture_method=Compose.onRoot.captureToImage\nstatus=NONBLANK_NATIVE_CONFIGURATION_FIXTURE\n" +
            "sampled_colors=${sampled.size}\nActual Android night-mask fixture/default isSystemInDarkTheme; no OS-setting switch, Activity recreation or provider proof.\n")
    }
}
