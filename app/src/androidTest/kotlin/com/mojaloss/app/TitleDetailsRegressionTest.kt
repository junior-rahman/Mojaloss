package com.mojaloss.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.mojaloss.app.adaptive.AdaptiveWindowInfo
import com.mojaloss.app.adaptive.LocalAdaptiveWindowInfo
import com.mojaloss.app.feature.ui.TitleScreen
import com.mojaloss.core.model.*
import com.mojaloss.core.ui.MojaLossBackdrop
import com.mojaloss.core.ui.MojaLossTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native Compose fixtures only. No server, YouTube, PlayerView or customer credentials. */
class TitleDetailsRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun movieActionsStayInsideArtworkWithSynopsisAndCastInlineAndNoTabs() {
        val favorite = mutableStateOf(FavoriteState())
        var plays = 0; var saves = 0; var trailers = 0
        val movie = MOVIE.copy(backdropUrl = artwork().toURI().toString(), trailerVideoId = "abcdefghijk",
            cast = listOf(CastMember("Avery Lane", "Lead")))
        compose.setContent { MojaLossTheme(darkTheme = false) { MojaLossBackdrop(Modifier.fillMaxSize()) {
            TitleScreen(movie, favorite.value, emptyList(), {}, { saves++; favorite.value = FavoriteState(confirmed = it, desired = it) },
                { plays++ }, {}, { trailers++ })
        } } }
        compose.onNodeWithTag("details-primary").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("details-save").assertIsDisplayed().performClick()
        compose.onNodeWithTag("details-trailer").assertIsDisplayed().performClick()
        assertArtworkContains("details-primary", "details-save", "details-trailer")
        compose.onNodeWithTag("details-confirmed-progress").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertCountEquals(0)
        capture("Details-compact-overlay-actions-local-artwork")
        compose.onNodeWithTag("title-details").performScrollToNode(hasText("Synopsis"))
        compose.onNodeWithText(movie.synopsis).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("title-details").performScrollToNode(hasTestTag("details-cast-cards"))
        compose.onNodeWithText("Avery Lane").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, plays); assertEquals(1, saves); assertEquals(1, trailers) }
    }

    @Test fun compactMovieCardEndsAfterItsActualActionsWithoutReservedPortraitSpace() {
        val trailer = mutableStateOf(false)
        var plays = 0; var saves = 0; var downloads = 0; var trailers = 0
        val movie = MOVIE.copy(title = "A", runtimeMs = 0, backdropUrl = artwork().toURI().toString())
        compose.setContent {
            val physical = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(physical.density, 1f),
                LocalAdaptiveWindowInfo provides AdaptiveWindowInfo(300f, 600f)) {
                MojaLossTheme(darkTheme = false) { Box(Modifier.size(300.dp, 600.dp)) {
                    TitleScreen(movie.copy(trailerVideoId = if (trailer.value) "abcdefghijk" else null),
                        FavoriteState(), emptyList(), {}, { saves++ }, { plays++ }, {}, { trailers++ },
                        onDownload = { downloads++ })
                } }
            }
        }
        fun assertCompactBottom(lastAction: String) {
            compose.onNodeWithTag("title-details").performScrollToNode(hasTestTag(lastAction))
            compose.onNodeWithTag(lastAction).performScrollTo().assertIsDisplayed()
            val card = compose.onNodeWithTag("details-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val action = compose.onNodeWithTag(lastAction).fetchSemanticsNode().boundsInRoot
            val gap = (card.bottom - action.bottom) / compose.density.density
            assertTrue("Card must end after actual actions; empty bottom gap was ${gap}dp", gap >= 0f && gap <= 24f)
        }
        assertCompactBottom("details-primary")
        compose.onNodeWithTag("details-primary").performClick()
        compose.onNodeWithTag("details-save").performClick()
        compose.onNodeWithContentDescription("Download A").assertIsDisplayed().performClick()
        assertArtworkContains("details-primary", "details-save")
        capture("Details-compact-no-reserved-bottom-local-artwork")
        compose.runOnIdle { trailer.value = true }
        assertCompactBottom("details-trailer")
        assertArtworkContains("details-primary", "details-save", "details-trailer")
        compose.onNodeWithTag("details-trailer").performClick()
        compose.onNodeWithTag("title-details").performScrollToNode(hasText("Synopsis"))
        compose.onNodeWithText(movie.synopsis).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, plays); assertEquals(1, saves); assertEquals(1, downloads); assertEquals(1, trailers) }
    }

    @Test fun savePendingFailureAndConfirmationRemainTruthful() {
        val favorite = mutableStateOf(FavoriteState())
        var writes = 0
        compose.setContent { MojaLossTheme { TitleScreen(MOVIE, favorite.value, emptyList(), {}, { writes++ }, {}, {}, {}) } }
        compose.onNodeWithTag("details-save").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Saved ${MOVIE.title}").assertDoesNotExist()
        compose.runOnIdle { favorite.value = FavoriteState(pending = true, desired = true) }
        compose.onNodeWithTag("details-save").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Saving… ${MOVIE.title}").assertExists()
        compose.runOnIdle { favorite.value = FavoriteState(error = "Fixture save failed") }
        compose.onNodeWithTag("details-save-error").performScrollTo().assertTextEquals("Fixture save failed")
        compose.onNodeWithContentDescription("Saved ${MOVIE.title}").assertDoesNotExist()
        compose.runOnIdle { favorite.value = FavoriteState(confirmed = true, desired = true) }
        compose.onNodeWithContentDescription("Saved ${MOVIE.title}").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, writes) }
    }

    @Test fun missingTrailerAndRatingsDoNotCreateDecorativeActions() {
        val movie = mutableStateOf(MOVIE)
        compose.setContent { MojaLossTheme { TitleScreen(movie.value, FavoriteState(), emptyList(), {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("details-trailer").assertDoesNotExist()
        compose.onNodeWithText("Ratings").assertDoesNotExist()
        compose.runOnIdle { movie.value = MOVIE.copy(ratings = listOf(Rating("Fixture rating source", "Verified fixture value", "82%"))) }
        compose.onNodeWithTag("title-details").performScrollToNode(hasText("Fixture rating source"))
        compose.onNodeWithText("82%").assertIsDisplayed()
    }

    @Test fun seriesStartLookupDoesNotPlayWrongFirstPageAndUsesResolvedResumeEpisode() {
        val start = mutableStateOf(SeriesStartState())
        val played = mutableListOf<String>(); var retries = 0; var moviePlays = 0
        compose.setContent { MojaLossTheme { TitleScreen(SERIES, FavoriteState(), listOf(EPISODE_ONE), {}, {}, { moviePlays++ },
            { played += it.id }, {}, episodeState = section(listOf(EPISODE_ONE)), seriesStartState = start.value, onSeriesStartRetry = { retries++ }) } }
        compose.onNodeWithTag("details-primary").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("details-primary").assertContentDescriptionEquals("Play ${SERIES.title}")
        compose.onNodeWithText("Finding your episode…").assertDoesNotExist()
        compose.runOnIdle { start.value = SeriesStartState(ContentStatus.ERROR, message = "Fixture start lookup failed") }
        compose.onNodeWithTag("details-primary").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, retries); assertTrue(played.isEmpty()); start.value = SeriesStartState(ContentStatus.READY, EPISODE_TWO.copy(seasonNumber = 2, confirmedResumeMs = 30_000)) }
        compose.onNodeWithContentDescription("Resume Second episode").performScrollTo().performClick()
        compose.onNodeWithTag("details-confirmed-progress").assertExists()
        compose.onNodeWithTag("details-episode-caption").assertTextContains("Episode 2", substring = true)
        compose.onNodeWithTag("details-episode-caption").assertTextContains("Season 2", substring = true)
        compose.runOnIdle { assertEquals(listOf(EPISODE_TWO.id), played); assertEquals(0, moviePlays) }
    }

    @Test fun wholeEpisodeRowSelectsArtworkAndStartsThatEpisodeThenArtworkRepeatsExactTarget() {
        val played = mutableListOf<String>()
        val art = artwork().toURI().toString()
        val second = EPISODE_TWO.copy(backdropUrl = art)
        compose.setContent { MojaLossTheme { TitleScreen(SERIES, FavoriteState(), listOf(EPISODE_ONE, second), {}, {}, {},
            { played += it.id }, {}, episodeState = section(listOf(EPISODE_ONE, second)),
            seriesStartState = SeriesStartState(ContentStatus.READY, EPISODE_ONE)) } }
        compose.onNodeWithTag("title-details").performScrollToNode(hasTestTag("episode-row-${second.id}"))
        compose.onNodeWithTag("episode-row-${second.id}").performClick()
        compose.runOnIdle { assertEquals(listOf(second.id), played) }
        compose.onNodeWithTag("episode-row-${second.id}").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        compose.onNodeWithTag("details-artwork-play").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("details-artwork-image-${second.id}", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("details-episode-caption").performScrollTo().assertTextContains("Second episode", substring = true)
        compose.runOnIdle { assertEquals(listOf(second.id, second.id), played) }
    }

    @Test fun savedUnverifiedAccountCannotPlayOrChangeFavoritesOrEpisodeRows() {
        var plays = 0; var saves = 0; var reconnects = 0
        compose.setContent { MojaLossTheme { TitleScreen(SERIES, FavoriteState(), listOf(EPISODE_ONE), {}, { saves++ }, { plays++ },
            { plays++ }, {}, detailStatus = ContentStatus.OFFLINE, canSave = false,
            episodeState = section(listOf(EPISODE_ONE)), primaryEpisode = EPISODE_ONE, onReconnect = { reconnects++ }) } }
        compose.onNodeWithTag("details-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("details-primary").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("title-details").performScrollToNode(hasTestTag("episode-row-${EPISODE_ONE.id}"))
        compose.onNodeWithTag("episode-row-${EPISODE_ONE.id}").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, reconnects); assertEquals(0, plays); assertEquals(0, saves) }
    }

    @Test fun silentEpisodePagingRequiresActualNearEndGestureAndNeverAddsLoadingButtons() {
        val episodes = (1..20).map { EPISODE_ONE.copy(id = "page-$it", title = "Episode $it", episodeNumber = it) }
        val state = mutableStateOf(section(episodes).copy(hasMore = true))
        var pages = 0
        compose.setContent { MojaLossTheme { TitleScreen(SERIES, FavoriteState(), state.value.titles, {}, {}, {}, {}, {},
            episodeState = state.value, seriesStartState = SeriesStartState(ContentStatus.READY, episodes.first()), onEpisodeLoadMore = {
                pages++
                val base = state.value.titles.size
                state.value = state.value.copy(titles = state.value.titles + (1..20).map { EPISODE_ONE.copy(id = "page-${base + it}", title = "Episode ${base + it}", episodeNumber = base + it) })
            }) } }
        compose.onNodeWithTag("title-details").performScrollToNode(hasTestTag("episode-row-page-20"))
        compose.runOnIdle { assertEquals(0, pages) }
        compose.onNodeWithTag("title-details").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(1, pages) }
        compose.onNodeWithText("More episodes").assertDoesNotExist()
        compose.onNodeWithText("Loading more…").assertDoesNotExist()
        compose.onNodeWithTag("title-details").performScrollToNode(hasTestTag("episode-row-page-40"))
        compose.runOnIdle { assertEquals(1, pages) }
        compose.onNodeWithTag("title-details").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(2, pages) }
    }

    @Test fun enlargedTextAndDarkThemeKeepOverlayActionsReachableAndNamed() {
        val dark = mutableStateOf(false)
        var plays = 0; var trailers = 0
        val art = artwork().toURI().toString()
        compose.setContent {
            val physical = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(physical.density, 2f),
                LocalAdaptiveWindowInfo provides AdaptiveWindowInfo(300f, 340f)) {
                MojaLossTheme(darkTheme = dark.value) { MojaLossBackdrop(Modifier.fillMaxSize()) {
                    Box(Modifier.size(300.dp, 340.dp)) { TitleScreen(MOVIE.copy(title = "A long local feature title for enlarged text", backdropUrl = art, trailerVideoId = "abcdefghijk"),
                        FavoriteState(), emptyList(), {}, {}, { plays++ }, {}, { trailers++ }) }
                } }
            }
        }
        compose.onNodeWithTag("details-primary").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("details-save").performScrollTo().assertIsDisplayed()
        capture("Details-compact-200percent-font-local-artwork")
        compose.runOnIdle { dark.value = true }
        compose.onNodeWithTag("details-trailer").performScrollTo().assertIsDisplayed().performClick()
        capture("Details-dark-200percent-font-local-artwork")
        compose.runOnIdle { assertEquals(1, plays); assertEquals(1, trailers) }
    }

    @Test fun pendingPreparationBlocksDuplicateMovieAndEpisodeActionsWithoutFakeProgress() {
        val preparing = mutableStateOf(false); var plays = 0
        compose.setContent { MojaLossTheme { TitleScreen(MOVIE, FavoriteState(), emptyList(), {}, {}, { plays++; preparing.value = true }, {}, {}, isPreparing = preparing.value) } }
        compose.onNodeWithTag("details-primary").performScrollTo().performClick()
        compose.onNodeWithTag("details-primary").assertIsNotEnabled()
        compose.onNodeWithTag("details-artwork-play").assertIsNotEnabled()
        compose.onNodeWithText("Preparing…").assertExists()
        compose.onNodeWithTag("details-confirmed-progress").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, plays) }
    }

    @Test fun pendingDetailUsesShapesAndRealEpisodeArrivesWithoutVisibleLoadingCopy() {
        val detail = mutableStateOf(ContentStatus.LOADING)
        val episodes = mutableStateOf(EpisodeSectionState())
        compose.setContent { MojaLossTheme { TitleScreen(SERIES.copy(synopsis = ""), FavoriteState(), episodes.value.titles,
            {}, {}, {}, {}, {}, detailStatus = detail.value, episodeState = episodes.value,
            seriesStartState = SeriesStartState()) } }
        // Locate the containing lazy item before bringing its nested placeholder into view.
        compose.onNodeWithTag("title-details").performScrollToNode(hasTestTag("details-synopsis-skeleton"))
        compose.onNodeWithTag("details-synopsis-skeleton").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Loading synopsis…").assertDoesNotExist()
        compose.onNodeWithTag("title-details").performScrollToNode(hasTestTag("details-season-skeletons"))
        compose.onNodeWithTag("details-season-skeletons").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Loading seasons…").assertDoesNotExist()
        compose.onNodeWithTag("title-details").performScrollToNode(hasTestTag("details-episode-skeletons"))
        compose.onNodeWithTag("details-episode-skeletons").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Loading episodes…").assertDoesNotExist()
        compose.runOnIdle { detail.value = ContentStatus.READY; episodes.value = section(listOf(EPISODE_ONE)).copy(isLoadingMore = true) }
        compose.onNodeWithTag("episode-row-${EPISODE_ONE.id}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("title-details").performScrollToNode(hasTestTag("details-episode-append-skeleton-0"))
        compose.onNodeWithTag("details-episode-append-skeleton-0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Loading more…").assertDoesNotExist()
    }

    @Test fun fullTitleWrapsAndPhotoCropStaysStableAtTwoHundredPercent() {
        val title = mutableStateOf(MOVIE.copy(backdropUrl = artwork().toURI().toString()))
        compose.setContent {
            val physical = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(physical.density, 2f)) {
                MojaLossTheme { Box(Modifier.size(300.dp, 340.dp)) {
                    TitleScreen(title.value, FavoriteState(), emptyList(), {}, {}, {}, {}, {})
                } }
            }
        }
        val photoSize = compose.onNodeWithTag("details-artwork-image-${MOVIE.id}", useUnmergedTree = true).fetchSemanticsNode().size
        val longName = "A very long cinematic title that must stay readable in full at enlarged system text sizes"
        compose.runOnIdle { title.value = title.value.copy(title = longName) }
        compose.onNodeWithTag("details-title").performScrollTo().assertTextEquals(longName)
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("details-title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("Full title should wrap naturally rather than truncate after two lines", results.single().lineCount > 2)
        assertFalse("Full title may not be ellipsized", results.single().hasVisualOverflow)
        assertEquals("Text growth must not change the photo canvas crop", photoSize,
            compose.onNodeWithTag("details-artwork-image-${MOVIE.id}", useUnmergedTree = true).fetchSemanticsNode().size)
        compose.onNodeWithTag("details-action-dock").assertDoesNotExist()
        compose.onNodeWithTag("details-save").performScrollTo().assertIsDisplayed()
        capture("Details-full-title-stable-photo-200percent-local-artwork")
    }

    @Test fun unavailableEpisodeIsDisabledAndExplainsItsState() {
        var plays = 0
        val unavailable = EPISODE_ONE.copy(isPlayable = false)
        compose.setContent { MojaLossTheme { TitleScreen(SERIES, FavoriteState(), listOf(unavailable), {}, {}, {},
            { plays++ }, {}, episodeState = section(listOf(unavailable)),
            seriesStartState = SeriesStartState(ContentStatus.READY, unavailable)) } }
        compose.onNodeWithTag("title-details").performScrollToNode(hasTestTag("episode-row-${unavailable.id}"))
        compose.onNodeWithTag("episode-row-${unavailable.id}").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            .assertContentDescriptionEquals("Unavailable ${unavailable.title}")
        compose.onNodeWithTag("episode-row-${unavailable.id}").assertTextContains("Unavailable", substring = true)
        compose.runOnIdle { assertEquals(0, plays) }
    }

    @Test fun episodeDownloadTapDoesNotAlsoPlayAndSeasonActionUsesOnlyActualSelectedSeason() {
        val downloaded = mutableListOf<String>(); val played = mutableListOf<String>(); val seasons = mutableListOf<String>()
        val episodeSection = mutableStateOf(section(listOf(EPISODE_ONE, EPISODE_TWO)).copy(selectedSeason = null))
        compose.setContent { MojaLossTheme { TitleScreen(SERIES, FavoriteState(), episodeSection.value.titles,
            {}, {}, {}, { played += it.id }, {}, episodeState = episodeSection.value,
            seriesStartState = SeriesStartState(ContentStatus.READY, EPISODE_ONE),
            onDownload = { downloaded += it.id }, onDownloadSeason = { seasons += it.id }) } }
        compose.onNodeWithContentDescription("Download ${SERIES.title}").assertDoesNotExist()
        compose.onNodeWithTag("title-details").performScrollToNode(hasText("Episodes", substring = false))
        compose.onNodeWithTag("details-download-season").assertDoesNotExist()
        compose.runOnIdle { episodeSection.value = episodeSection.value.copy(selectedSeason = SeasonOption("season-1", 1, "Season 1")) }
        compose.onNodeWithTag("details-download-season").performScrollTo().performClick()
        compose.onNodeWithTag("episode-row-${EPISODE_TWO.id}").performScrollTo().assertIsDisplayed()
        compose.onNode(hasContentDescription("Download ${EPISODE_TWO.title}") and
            hasAnyAncestor(hasTestTag("episode-row-${EPISODE_TWO.id}"))).performClick()
        compose.runOnIdle { assertEquals(listOf("season-1"), seasons); assertEquals(listOf(EPISODE_TWO.id), downloaded); assertTrue(played.isEmpty()) }
        compose.onNodeWithTag("episode-row-${EPISODE_TWO.id}").performClick()
        compose.runOnIdle { assertEquals(listOf(EPISODE_TWO.id), played) }
    }

    private fun assertArtworkContains(vararg tags: String) {
        val parent = compose.onNodeWithTag("details-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        tags.forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must remain within the artwork card", bounds.left >= parent.left - 1 && bounds.right <= parent.right + 1 && bounds.top >= parent.top - 1 && bounds.bottom <= parent.bottom + 1)
            assertTrue("$tag must retain a 48dp touch height", bounds.height / compose.density.density >= 48f)
        }
    }

    private fun fixtureFolder(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        return File(context.filesDir, "details-$version-native-fixtures").apply { mkdirs() }
    }

    private fun artwork(): File {
        val file = File(fixtureFolder(), "bright-local-artwork.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888)
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) bitmap.setPixel(x, y,
                android.graphics.Color.rgb(230 + x % 25, 225 + y % 25, 220 + (x + y) % 25))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        return file
    }

    private fun capture(label: String) {
        val folder = fixtureFolder()
        val identity = "$label; method=${Thread.currentThread().stackTrace.firstOrNull { it.className == javaClass.name && it.methodName != "capture" }?.methodName}; density=${compose.density.density}; configured-font-fixture; local pixels only"
        val manifest = File(folder, "capture-manifest.txt")
        try {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val samples = (0..10).flatMap { y -> (0..10).map { x -> bitmap.getPixel((x * (bitmap.width - 1) / 10).coerceAtLeast(0), (y * (bitmap.height - 1) / 10).coerceAtLeast(0)) } }.toSet()
            if (samples.size < 8) { manifest.appendText("$identity: blocked blank pixels\n"); return }
            File(folder, "$label.compose.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            manifest.appendText("$identity: native Compose component capture ${bitmap.width}x${bitmap.height}; not physical Fold/live playback\n")
        } catch (failure: Exception) { manifest.appendText("$identity: capture unavailable (${failure.javaClass.simpleName}); interactions tested separately\n") }
        catch (failure: AssertionError) { manifest.appendText("$identity: capture unavailable (${failure.javaClass.simpleName}); interactions tested separately\n") }
    }

    private fun section(titles: List<MediaTitle>) = EpisodeSectionState(ContentStatus.READY, titles,
        seasons = listOf(SeasonOption("season-1", 1, "Season 1")), selectedSeason = SeasonOption("season-1", 1, "Season 1"), seasonStatus = ContentStatus.READY)

    private companion object {
        val MOVIE = MediaTitle("movie", "Local feature", synopsis = "A quiet coastal story shown as local test data.", year = 2025, genre = "Drama", runtimeMs = 5_400_000, isPlayable = true)
        val SERIES = MOVIE.copy(id = "series", title = "Local series", kind = MediaKind.SERIES, isPlayable = false)
        val EPISODE_ONE = MOVIE.copy(id = "episode-1", title = "First episode", kind = MediaKind.EPISODE, seriesId = "series", seasonNumber = 1, episodeNumber = 1)
        val EPISODE_TWO = EPISODE_ONE.copy(id = "episode-2", title = "Second episode", episodeNumber = 2)
    }
}
