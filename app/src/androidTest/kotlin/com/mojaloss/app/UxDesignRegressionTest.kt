package com.mojaloss.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.mojaloss.app.adaptive.AdaptiveWindowInfo
import com.mojaloss.app.adaptive.LocalAdaptiveWindowInfo
import com.mojaloss.app.feature.ui.*
import com.mojaloss.core.model.*
import com.mojaloss.core.ui.MojaLossBackdrop
import com.mojaloss.core.ui.MojaLossTheme
import com.mojaloss.core.ui.motion.LocalMotionPolicy
import com.mojaloss.core.ui.motion.MotionPolicy
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Compose fixtures only; all artwork is local, with no provider/DNS/transport. */
class UxDesignRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun seriesActionSeparatesPendingFailedEmptyAndSeasonRecovery() {
        val season = SeasonOption("season-1", 1, "Season 1")
        val state = mutableStateOf(EpisodeSectionState(seasons = listOf(season), selectedSeason = season, seasonStatus = ContentStatus.READY))
        var episodeRetries = 0; var seasonRetries = 0
        compose.setContent { MojaLossTheme { TitleScreen(SERIES, FavoriteState(), emptyList(), {}, {}, {}, {}, {},
            episodeState = state.value, onEpisodeRetry = { episodeRetries++; state.value = state.value.copy(status = ContentStatus.READY) }, onSeasonRetry = { seasonRetries++ }) } }
        compose.onNodeWithText("Unavailable").assertDoesNotExist()
        compose.onNodeWithText("Choose episode").assertDoesNotExist()
        compose.onNodeWithTag("title-details").performScrollToNode(hasTestTag("details-episode-skeletons"))
        compose.onNodeWithTag("details-episode-skeletons").assertIsDisplayed()
        compose.onNodeWithText("No episodes available").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(status = ContentStatus.ERROR, message = "Fixture episode failure") }
        compose.onNodeWithText("Retry episodes").performScrollTo().performClick()
        compose.onNodeWithText("No episodes available").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(seasonStatus = ContentStatus.ERROR, seasonMessage = "Fixture season failure") }
        compose.onNodeWithText("Retry seasons").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, episodeRetries); assertEquals(1, seasonRetries) }
    }

    @Test fun listingFailureKeepsVerifiedActionsButUnverifiedAccountRemainsReadOnly() {
        val verified = mutableStateOf(true); var plays = 0
        compose.setContent { MojaLossTheme { HomeScreen(CatalogSnapshot(ContentStatus.ERROR, listOf(MOVIE), message = "Fixture listing failure", isConnectionVerified = verified.value), {}, { plays++ }) } }
        compose.onNodeWithContentDescription("Play Fixture movie").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { verified.value = false; assertEquals(1, plays) }
        compose.onNodeWithContentDescription("Play Fixture movie").assertIsNotEnabled()
    }

    @Test fun posterGeometrySurvivesVerificationAndSearchControlsStayVisible() {
        val artwork = localArtwork().toURI().toString()
        val titles = (1..30).map { MOVIE.copy(id = "poster-$it", title = "Poster $it") }
        val snapshot = mutableStateOf(CatalogSnapshot(ContentStatus.READY, titles, query = CatalogQuery(Destination.SEARCH, search = "Poster"), isConnectionVerified = false))
        compose.setContent { MojaLossTheme { SearchScreen(snapshot.value, "Poster", {}, {}, onClose = {}) } }
        val before = compose.onNodeWithTag("poster-artwork-poster-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(4f / 3f, before.height / before.width, .02f)
        compose.runOnIdle { snapshot.value = snapshot.value.copy(isConnectionVerified = true, titles = titles.map { it.copy(posterUrl = artwork) }) }
        val after = compose.onNodeWithTag("poster-artwork-poster-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(before.height, after.height, .01f)
        compose.onNodeWithTag("search-catalog").performScrollToNode(hasText("Poster 29"))
        compose.onNodeWithText("Search movies and series").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to Home").assertIsDisplayed()
        captureFixture("Search-scrolled-local-artwork")
    }

    @Test fun settingsAndCredentialRowsOwnTheirNamedToggleSemantics() {
        val prefs = mutableStateOf(AppPreferences()); val connect = mutableStateOf(false)
        var reconnects = 0
        compose.setContent { MojaLossTheme {
            if (connect.value) ConnectScreen(SessionState.SignedOut, {}, { _, _, _ -> }, {}, initialServerUrl = "https://fixture.invalid")
            else SettingsScreen(prefs.value, "Fixture account", { prefs.value = it }, {}, {}, accountVerified = false, onReconnect = { reconnects++ })
        } }
        compose.onNodeWithText("Reconnect account").performScrollTo().performClick()
        val switch = hasText("Reduce motion") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        val verticalList = hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        compose.onNode(verticalList).performScrollToNode(switch)
        compose.onNode(switch).performScrollTo().assertIsOff().performClick().assertIsOn()
        val audioSelector = hasText("Audio language: ") and hasClickAction()
        compose.onNode(verticalList).performScrollToNode(audioSelector)
        val audio = compose.onNode(audioSelector).performScrollTo().fetchSemanticsNode().config
        assertEquals("Change Audio language", audio[SemanticsActions.OnClick].label)
        compose.runOnIdle { assertTrue(prefs.value.reducedMotion); assertEquals(1, reconnects); connect.value = true }
        val credentials = hasText("My provider issued a username and password") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
        compose.onNode(verticalList).performScrollToNode(credentials)
        compose.onNode(credentials).performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNode(verticalList).performScrollToNode(hasText("Username"))
        compose.onNodeWithText("Username").assertIsDisplayed()
    }

    @Test fun reducedMotionPagerActionMovesWithoutTimedAnimation() {
        compose.setContent { MojaLossTheme { CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(false)) {
            HomeScreen(CatalogSnapshot(ContentStatus.READY, listOf(MOVIE, MOVIE.copy(id = "second", title = "Second"))), {}, {})
        } } }
        compose.mainClock.autoAdvance = false
        val action = compose.onNodeWithTag("featured-pager").fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Next featured card" }
        compose.runOnIdle { assertTrue(action.action()) }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("featured-pager").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "New releases. Featured card 2 of 2"))
    }

    @Test fun platformsDemandOnlyAfterVisibleAndActiveThenSelectsActualServerId() {
        val active = mutableStateOf(false); val platforms = mutableStateOf(PlatformCollectionsState())
        var demands = 0; var selected: String? = null
        compose.setContent { MojaLossTheme { HomeScreen(CatalogSnapshot(ContentStatus.READY, (1..12).map { MOVIE.copy(id = "recent-$it", title = "Recent $it") }), {}, {},
            resumeState = HomeResumeState(ContentStatus.READY, listOf(MOVIE.copy(id = "resume", title = "Resume fixture", confirmedResumeMs = 5000))),
            platformState = platforms.value, feedActive = active.value, onRequestPlatforms = { demands++ }, onPlatformSelected = { selected = it.id }) } }
        compose.runOnIdle { assertEquals(0, demands) }
        compose.onNodeWithTag("home-catalog").performScrollToNode(hasTestTag("home-platform-anchor"))
        compose.runOnIdle { assertEquals(0, demands); active.value = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, demands); platforms.value = PlatformCollectionsState(ContentStatus.READY, listOf(PlatformCollection("verified-server-collection", "Netflix"))) }
        compose.onNodeWithTag("platform-NETFLIX").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("verified-server-collection", selected); assertEquals(1, demands) }
    }

    @Test fun platformButtonsExcludeGenericGroupsAndRequireAnExplicitBranchChoice() {
        val records = listOf(PlatformCollection("netflix-movies", "Netflix Movies"), PlatformCollection("netflix-series", "Netflix Series"),
            PlatformCollection("hbo", "HBO"), PlatformCollection("apple", "Apple TV+"), PlatformCollection("generic", "Criterion"))
        val selected = mutableListOf<PlatformCollection>()
        compose.setContent { MojaLossTheme { HomeScreen(CatalogSnapshot(ContentStatus.READY, listOf(MOVIE)), {}, {},
            platformState = PlatformCollectionsState(ContentStatus.READY, records), onPlatformSelected = { selected += it }) } }
        compose.onNodeWithTag("home-catalog").performScrollToNode(hasTestTag("home-platforms"))
        compose.onNode(hasText("Criterion") and hasAnyAncestor(hasTestTag("home-platforms"))).assertDoesNotExist()
        compose.onNodeWithTag("platform-PEACOCK").assertDoesNotExist()
        compose.onNodeWithTag("platform-NETFLIX").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(selected.isEmpty()) }
        compose.onNodeWithText("Netflix Series").performClick()
        compose.runOnIdle { assertEquals(listOf(records[1]), selected) }
        compose.onNodeWithTag("platform-HBO").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(records[1], records[2]), selected) }
    }

    @Test fun platformRowCapturesCompactAnd200PercentFontUsingNamedFixtureTargets() {
        val largeFont = mutableStateOf(false)
        val selected = mutableListOf<String>()
        val records = listOf(PlatformCollection("fixture-netflix", "Netflix"), PlatformCollection("fixture-hbo", "HBO"), PlatformCollection("fixture-apple", "Apple TV"))
        compose.setContent {
            val physical = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(physical.density, if (largeFont.value) 2f else physical.fontScale)) {
                MojaLossTheme { MojaLossBackdrop(Modifier.fillMaxSize()) { HomeScreen(CatalogSnapshot(ContentStatus.READY, listOf(MOVIE)), {}, {},
                    platformState = PlatformCollectionsState(ContentStatus.READY, records), onPlatformSelected = { selected += it.id }) } }
            }
        }
        compose.onNodeWithTag("home-catalog").performScrollToNode(hasTestTag("home-platforms"))
        compose.onNodeWithTag("platform-NETFLIX").performScrollTo().assertIsDisplayed()
        captureFixture("Platforms-compact-named-collection-fixture")
        compose.runOnIdle { largeFont.value = true }
        compose.onNodeWithTag("home-catalog").performScrollToNode(hasTestTag("home-platforms"))
        compose.onNodeWithTag("platform-APPLE_TV").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("fixture-apple"), selected) }
        captureFixture("Platforms-compact-200percent-font-named-collection-fixture")
    }

    @Test fun emptyAndSmallLibrariesKeepExploreReachableWithoutNavigationTabs() {
        val titles = mutableStateOf(listOf(MOVIE)); var explores = 0
        compose.setContent { MojaLossTheme { HomeScreen(CatalogSnapshot(ContentStatus.READY, titles.value), {}, {}, onSeeAll = { explores++ }) } }
        compose.onNodeWithText("See all").assertDoesNotExist()
        val explore = compose.onNodeWithTag("featured-pager").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .first { it.label == "Explore all new releases" }
        compose.runOnIdle { assertTrue(explore.action()); titles.value = emptyList() }
        compose.onNodeWithText("Explore library").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, explores) }
    }

    @Test fun ratingsWithoutVerifiedDataAndDisclosureStatesAreTruthful() {
        val screen = mutableStateOf(false)
        compose.setContent { MojaLossTheme {
            if (screen.value) HomeScreen(CatalogSnapshot(ContentStatus.ERROR, listOf(MOVIE), message = "Fixture failure"), {}, {})
            else TitleScreen(MOVIE, FavoriteState(), emptyList(), {}, {}, {}, {}, {}, detailStatus = ContentStatus.LOADING)
        } }
        compose.onNodeWithText("Ratings").assertDoesNotExist()
        compose.runOnIdle { screen.value = true }
        compose.onNodeWithContentDescription("Show library error details").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")).performClick()
        compose.onNodeWithContentDescription("Hide library error details")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded")).performClick()
        compose.onNodeWithContentDescription("Show library error details").assertIsDisplayed()
    }

    @Test fun scopedRefreshIsReachableAndNewSearchStartsAtItsOwnAnchor() {
        val query = mutableStateOf("First"); var refreshes = 0
        compose.setContent { MojaLossTheme { CompositionLocalProvider(LocalCatalogRefresh provides { refreshes++ }) {
            SearchScreen(CatalogSnapshot(ContentStatus.READY, (1..30).map { MOVIE.copy(id = "${query.value}-$it", title = "${query.value} $it") }, query = CatalogQuery(Destination.SEARCH, search = query.value)), query.value, { query.value = it }, {})
        } } }
        compose.onNodeWithTag("search-catalog").performScrollToNode(hasText("First 29"))
        compose.onNodeWithText("Refresh").assertDoesNotExist()
        val refresh = compose.onNodeWithTag("catalog-refresh-container").fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Refresh titles" }
        compose.runOnIdle { assertTrue(refresh.action()); assertEquals(1, refreshes); query.value = "Second" }
        compose.onNodeWithText("Second 1").assertIsDisplayed()
        compose.onNodeWithText("Search movies and series").assertIsDisplayed()
    }

    @Test fun illustrativeCompactAndExpandedLargeFontFeedCapturesKeepCoreControls() {
        val expanded = mutableStateOf(false)
        val artwork = localArtwork().toURI().toString()
        compose.setContent {
            val physicalDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (expanded.value) Density(.65f, 2f) else physicalDensity,
                LocalAdaptiveWindowInfo provides if (expanded.value) AdaptiveWindowInfo(widthDp = 738f, heightDp = 1200f) else AdaptiveWindowInfo(widthDp = 320f, heightDp = 510f)) {
                MojaLossTheme { MojaLossBackdrop(Modifier.fillMaxSize()) {
                    HomeScreen(CatalogSnapshot(ContentStatus.READY, (1..16).map { MOVIE.copy(id = "visual-$it", title = "Fixture story $it", posterUrl = artwork, backdropUrl = artwork) }), {}, {},
                        resumeState = HomeResumeState(ContentStatus.READY, listOf(MOVIE.copy(id = "progress", title = "Saved progress fixture", confirmedResumeMs = 6000, posterUrl = artwork))),
                        platformState = PlatformCollectionsState(ContentStatus.READY, listOf(PlatformCollection("fixture-collection", "Fixture collection"))), onPlatformSelected = {}, onMyList = {})
                } }
            }
        }
        compose.onNodeWithText("New releases").assertDoesNotExist()
        compose.onNodeWithTag("featured-pager").assertIsDisplayed().assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "New releases. Featured card 1 of 8"))
        captureFixture("Home-compact-illustrative-local-artwork")
        compose.runOnIdle { expanded.value = true }
        compose.onNodeWithContentDescription("My List").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play Fixture story 1").performScrollTo().assertIsDisplayed()
        captureFixture("Home-expanded-density-fixture-200percent-font")
    }

    @Test fun homeShelvesDemandOnlyVisibleActiveRowsAndKeepExplicitServerSourceChoice() {
        val active = mutableStateOf(false)
        val requests = mutableListOf<HomeShelfKind>()
        val choices = mutableListOf<Pair<HomeShelfKind, PlatformCollection>>()
        val opened = mutableListOf<Pair<HomeShelfKind, PlatformCollection>>()
        val a = PlatformCollection("fixture-hindi-a", "Hindi Movies Archive")
        val b = PlatformCollection("fixture-hindi-b", "Hindi Movies New Releases")
        val shelves = mutableStateOf(HomeShelfKind.entries.associateWith { HomeShelfState() })
        compose.setContent { MojaLossTheme { HomeScreen(CatalogSnapshot(ContentStatus.READY, listOf(MOVIE)), {}, {},
            feedActive = active.value, shelfStates = shelves.value,
            onRequestShelf = { requests += it }, onShelfSourceSelected = { kind, source ->
                choices += kind to source
                shelves.value = shelves.value + (kind to HomeShelfState(ContentStatus.READY,
                    titles = listOf(MOVIE.copy(id = "hindi-fixture", title = "Hindi fixture", year = 2025)), sources = listOf(a, b), selectedSource = source))
            }, onShelfSeeAll = { kind, source -> opened += kind to source }) } }
        compose.onNodeWithTag("home-catalog").performScrollToNode(hasTestTag("shelf-HINDI_MOVIES"))
        compose.runOnIdle { assertTrue(requests.isEmpty()); active.value = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(HomeShelfKind.HINDI_MOVIES in requests)
            assertFalse(HomeShelfKind.INTERNATIONAL_SHOWS in requests)
            shelves.value = shelves.value + (HomeShelfKind.HINDI_MOVIES to HomeShelfState(ContentStatus.READY, sources = listOf(a, b)))
        }
        compose.onNode(hasText("Choose a group") and hasAnyAncestor(hasTestTag("shelf-HINDI_MOVIES"))).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(choices.isEmpty()) }
        compose.onNodeWithText(b.name).performClick()
        compose.onNode(hasText("See all") and hasAnyAncestor(hasTestTag("shelf-HINDI_MOVIES"))).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(HomeShelfKind.HINDI_MOVIES to b), choices); assertEquals(choices, opened) }
        compose.onNodeWithText("Discover tonight").assertDoesNotExist()
    }

    @Test fun horizontalShelfPagingKeepsCardsWithStaticAppendMasksAndNoHealthyControls() {
        val source = PlatformCollection("fixture-english", "English Movies")
        val first = (1..12).map { MOVIE.copy(id = "shelf-$it", title = "Shelf $it", year = 2026, genre = "Drama") }
        val state = mutableStateOf(HomeShelfState(ContentStatus.READY, first, sources = listOf(source), selectedSource = source, hasMore = true))
        var demands = 0
        compose.setContent { MojaLossTheme { HomeScreen(CatalogSnapshot(ContentStatus.READY, listOf(MOVIE)), {}, {},
            shelfStates = mapOf(HomeShelfKind.ENGLISH_MOVIES to state.value), onShelfLoadMore = { demands++; state.value = state.value.copy(isLoadingMore = true) }) } }
        compose.onNodeWithTag("home-catalog").performScrollToNode(hasTestTag("shelf-row-ENGLISH_MOVIES"))
        compose.onNodeWithTag("shelf-row-ENGLISH_MOVIES").performScrollToNode(hasText("Shelf 12"))
        compose.runOnIdle { assertEquals(0, demands) }
        compose.onNodeWithTag("shelf-row-ENGLISH_MOVIES").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(1, demands) }
        compose.onNodeWithTag("shelf-row-ENGLISH_MOVIES").performScrollToNode(hasTestTag("shelf-skeleton-ENGLISH_MOVIES-1"))
        compose.onNodeWithTag("shelf-skeleton-ENGLISH_MOVIES-1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Loading more…").assertDoesNotExist()
        compose.onNodeWithText("More titles").assertDoesNotExist()
        compose.onNodeWithText("Load more").assertDoesNotExist()
        captureFixture("Home-shelf-silent-append-illustrative-fixture")
        compose.runOnIdle { state.value = state.value.copy(isLoadingMore = false, titles = first + (13..24).map { MOVIE.copy(id = "shelf-$it", title = "Shelf $it") }) }
        compose.runOnIdle { assertEquals(1, demands) }
        val action = compose.onNodeWithTag("shelf-row-ENGLISH_MOVIES").fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Load next titles" }
        compose.runOnIdle { assertTrue(action.action()); assertEquals(2, demands) }
    }

    @Test fun fullImagePostersKeepActualTitleYearGenreAtTopAt200PercentFont() {
        val artwork = localArtwork().toURI().toString()
        val movie = MOVIE.copy(title = "A fixture story", year = 2025, genre = "Drama", posterUrl = artwork)
        val series = SERIES.copy(year = null, genre = "Mystery", posterUrl = artwork)
        var densityPx = 1f
        var opened: String? = null
        compose.setContent {
            val physical = LocalDensity.current
            androidx.compose.runtime.SideEffect { densityPx = physical.density }
            CompositionLocalProvider(LocalDensity provides Density(physical.density, 2f)) {
                MojaLossTheme { BrowseScreen(CatalogSnapshot(ContentStatus.READY, listOf(movie, series)), BrowseFilter(), {}, { opened = it.id }) }
            }
        }
        val art = compose.onNodeWithTag("poster-artwork-${movie.id}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val caption = compose.onNodeWithTag("poster-caption-${movie.id}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(4f / 3f, art.height / art.width, .02f)
        // The tag follows the caption's 10 dp content padding, while its backing
        // begins at the top of the artwork. Measure that intentional inset.
        assertEquals(art.top + 10f * densityPx, caption.top, .1f)
        assertTrue(caption.top < art.top + art.height / 4f)
        assertTrue(caption.bottom <= art.bottom)
        compose.onNodeWithText("A fixture story").assertIsDisplayed()
        compose.onNodeWithText("2025 · Drama").assertIsDisplayed()
        compose.onNodeWithText("Mystery").assertIsDisplayed()
        compose.onNodeWithContentDescription("Fixture series · Mystery").assertIsDisplayed()
        captureFixture("Explore-top-caption-3by4-200percent-font-illustrative-fixture")
        compose.onNodeWithContentDescription("A fixture story · 2025 · Drama").performClick()
        compose.runOnIdle { assertEquals(movie.id, opened) }
    }

    private fun fixtureFolder(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty().replace(Regex("[^a-zA-Z0-9.-]"), "_")
        return File(context.filesDir, "ux-$version-native-fixtures").apply { mkdirs() }
    }

    private fun localArtwork(): File {
        val folder = fixtureFolder()
        val file = File(folder, "illustrative-local-artwork.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888)
            for (x in 0 until bitmap.width) for (y in 0 until bitmap.height) bitmap.setPixel(x, y, android.graphics.Color.rgb(40 + x, 70 + y, 120 + x / 2))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        return file
    }

    /** Optional actual pixels, labelled fixture; unsupported capture never substitutes for a pass. */
    private fun captureFixture(label: String) {
        val folder = fixtureFolder()
        val manifest = File(folder, "capture-manifest.txt")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val method = Thread.currentThread().stackTrace.firstOrNull { it.className == javaClass.name && it.methodName != "captureFixture" }?.methodName.orEmpty()
        val identity = "class=${javaClass.name}; method=$method; app=$version; screen=$label"
        try {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val samples = buildSet { for (x in 0 until bitmap.width step 13) for (y in 0 until bitmap.height step 13) add(bitmap.getPixel(x, y)) }
            if (samples.size < 8) { manifest.appendText("$identity: unsupported/blank pixels (${bitmap.width}x${bitmap.height})\n"); return }
            File(folder, "$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            manifest.appendText("$identity: native Compose capture ${bitmap.width}x${bitmap.height}; illustrative fixture/local artwork; not physical Fold/live service\n")
        } catch (failure: Exception) { manifest.appendText("$identity: capture unavailable (${failure.javaClass.simpleName}); interaction checks separate\n") }
        catch (failure: AssertionError) { manifest.appendText("$identity: capture unavailable (${failure.javaClass.simpleName}); interaction checks separate\n") }
    }

    companion object {
        val MOVIE = MediaTitle("fixture-movie", "Fixture movie", isPlayable = true, runtimeMs = 90_000)
        val SERIES = MediaTitle("fixture-series", "Fixture series", kind = MediaKind.SERIES, isPlayable = false)
    }
}
