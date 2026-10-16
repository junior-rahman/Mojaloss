package com.mojaloss.core.data

import com.mojaloss.core.model.HomeShelfKind
import com.mojaloss.core.model.PlatformCollection
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class HomeShelfNamesTest {
    @Test fun explicitLibraryNamesSupportNumberedAndReversedLabelsWithoutErasingExtraWords() {
        assertTrue(matchesShelfSource(HomeShelfKind.HINDI_MOVIES, "01. Movies - Hindi"))
        assertTrue(matchesShelfSource(HomeShelfKind.HINDI_SHOWS, "02) Hindi TV Series"))
        assertTrue(matchesShelfSource(HomeShelfKind.INTERNATIONAL_MOVIES, "Films - International"))
        assertTrue(matchesShelfSource(HomeShelfKind.INTERNATIONAL_SHOWS, "International TV Series"))
        assertFalse(matchesShelfSource(HomeShelfKind.HINDI_MOVIES, "01. Movies - Hindi Dubbed"))
        assertFalse(matchesShelfSource(HomeShelfKind.HINDI_MOVIES, "Hindi Movies 2026"))
        assertFalse(matchesShelfSource(HomeShelfKind.INTERNATIONAL_MOVIES, "Uncategorized"))
        val source = PlatformCollection("a", "01. Hindi Movies")
        assertEquals(source, automaticShelfSource(HomeShelfKind.HINDI_MOVIES,
            listOf(PlatformCollection("b", "Hindi"), source)))
    }

    @Test fun userViewTargetsAreAllowedOnlyForExplicitLibraryDiscovery() {
        val view = BaseItemDto(id = UUID(0, 1), name = "Hindi Movies", type = BaseItemKind.USER_VIEW)
        assertTrue(validCollectionTargets(listOf(view), true).isEmpty())
        assertEquals(1, validCollectionTargets(listOf(view), true, includeLibraryViews = true).size)
        assertTrue(validCollectionTargets(listOf(view, view.copy(name = "International Movies")), true,
            includeLibraryViews = true).isEmpty())
    }

    @Test fun explicitServerCategoriesKeepMovieAndShowBranchesSeparate() {
        assertTrue(matchesShelfSource(HomeShelfKind.HINDI_MOVIES, "Hindi Movies"))
        assertTrue(matchesShelfSource(HomeShelfKind.HINDI_SHOWS, "Hindi TV Shows"))
        assertFalse(matchesShelfSource(HomeShelfKind.HINDI_MOVIES, "Hindi TV Shows"))
        assertFalse(matchesShelfSource(HomeShelfKind.HINDI_SHOWS, "Hindi Movies"))
        assertTrue(matchesShelfSource(HomeShelfKind.ENGLISH_MOVIES, "English"))
        assertTrue(matchesShelfSource(HomeShelfKind.ENGLISH_SHOWS, "English"))
        assertEquals(shelfMetadataTerm(HomeShelfKind.HINDI_MOVIES), shelfMetadataTerm(HomeShelfKind.HINDI_SHOWS))
    }

    @Test fun trendingRequiresAnExplicitTrendingCategoryRatherThanPopularityOrRelease() {
        assertTrue(matchesShelfSource(HomeShelfKind.TRENDING, "Trending This Week"))
        listOf("Popular", "Top Rated", "New Releases", "Recently Added", "Trending documentaries", "Not Trending")
            .forEach { assertFalse(it, matchesShelfSource(HomeShelfKind.TRENDING, it)) }
    }

    @Test fun internationalIsOnlyAnExplicitCategoryNotCountriesOrComplementOfEnglish() {
        assertTrue(matchesShelfSource(HomeShelfKind.INTERNATIONAL_MOVIES, "International films"))
        listOf("French", "Non English Movies", "Bollywood", "Hollywood", "International Hindi Movies")
            .forEach { assertFalse(it, matchesShelfSource(HomeShelfKind.INTERNATIONAL_MOVIES, it)) }
    }

    @Test fun unknownUnicodeWordsAndAudioDescriptionsAreNeverErasedIntoAcceptedCategories() {
        listOf("Hindi 日本語 Movies", "Hindi বাংলা Movies", "Hindi dubbed Movies", "English [Hindi audio] Movies")
            .forEach { assertFalse(it, matchesShelfSource(HomeShelfKind.HINDI_MOVIES, it)) }
        assertTrue(matchesShelfSource(HomeShelfKind.HINDI_MOVIES, " HINDI — MOVIES "))
    }

    @Test fun contradictorySameIdsAreRejectedBeforeAnyGroupingOrDeduplication() {
        val id = UUID(0, 1)
        val movie = BaseItemDto(id = id, name = "Hindi Movies", type = BaseItemKind.BOX_SET)
        val shows = movie.copy(name = "Hindi Shows")
        assertTrue(validCollectionTargets(listOf(movie, shows), true).isEmpty())
        assertTrue(validCollectionTargets(listOf(movie, movie.copy(type = BaseItemKind.FOLDER)), true).isEmpty())
        assertTrue(validCollectionTargets(listOf(movie, movie.copy(name = "")), true).isEmpty())
        assertEquals(1, validCollectionTargets(listOf(movie, movie.copy(name = " HINDI — MOVIES ")), true).size)
    }

    @Test fun independentActualTargetsRemainAvailableAndGenericMoviesAreNotCollectionSources() {
        val first = BaseItemDto(id = UUID(0, 1), name = "Hindi Movies", type = BaseItemKind.BOX_SET)
        val second = first.copy(id = UUID(0, 2), type = BaseItemKind.FOLDER)
        assertEquals(2, validCollectionTargets(listOf(first, second), true).size)
        assertEquals(1, validCollectionTargets(listOf(first, second), false).size)
        assertTrue(validCollectionTargets(listOf(first.copy(type = BaseItemKind.MOVIE)), true).isEmpty())
    }

    @Test fun homeAutomaticallyPrefersTheSpecificLanguageBranchOverBroadFoldersAndAliases() {
        val broad = PlatformCollection("broad", "Hindi")
        val alias = PlatformCollection("alias", "Hindi Films")
        val movies = PlatformCollection("movies", "Hindi Movies")
        val shows = PlatformCollection("shows", "Hindi Shows")
        val sources = listOf(broad, alias, shows, movies)
        assertEquals(movies, automaticShelfSource(HomeShelfKind.HINDI_MOVIES, sources))
        assertEquals(shows, automaticShelfSource(HomeShelfKind.HINDI_SHOWS, sources))
        assertEquals(alias, automaticShelfSource(HomeShelfKind.HINDI_MOVIES, listOf(broad, alias)))
    }

    @Test fun equivalentAutomaticSourcesRemainStableWhenServerMetadataOrderChanges() {
        val a = PlatformCollection("001", "English Movies")
        val b = PlatformCollection("002", "ENGLISH — MOVIES")
        assertEquals(a, automaticShelfSource(HomeShelfKind.ENGLISH_MOVIES, listOf(a, b)))
        assertEquals(a, automaticShelfSource(HomeShelfKind.ENGLISH_MOVIES, listOf(b, a)))
        assertNull(automaticShelfSource(HomeShelfKind.HINDI_SHOWS, listOf(a, b)))
    }

    @Test fun genericTrendingSourceWinsOverNarrowerTrendingMovieAndShowGroups() {
        val broad = PlatformCollection("broad", "Trending")
        assertEquals(broad, automaticShelfSource(HomeShelfKind.TRENDING,
            listOf(PlatformCollection("movie", "Trending Movies"), broad, PlatformCollection("show", "Trending Shows"))))
    }
}
