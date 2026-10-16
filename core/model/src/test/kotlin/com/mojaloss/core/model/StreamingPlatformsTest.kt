package com.mojaloss.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingPlatformsTest {
    @Test fun explicitPlatformNamesNormalizeWithoutChangingServerTargets() {
        val sources = listOf(
            PlatformCollection("netflix-server-id", " NETFLIX - Originals "),
            PlatformCollection("hbo-server-id", "HBO Max"),
            PlatformCollection("apple-server-id", "Apple TV +"),
            PlatformCollection("prime-server-id", "Amazon Prime Video"),
            PlatformCollection("disney-server-id", "Disney+"),
            PlatformCollection("hulu-server-id", "Hulu"),
            PlatformCollection("paramount-server-id", "Paramount Plus"),
            PlatformCollection("peacock-server-id", "Peacock TV"),
        )
        val groups = recognizedPlatforms(sources.reversed())
        assertEquals(PlatformBrand.entries.toList(), groups.map { it.brand })
        assertEquals(sources, groups.flatMap { it.targets })
    }

    @Test fun genericOrAmbiguousNamesCannotBecomePlatformButtons() {
        val names = listOf("Favorites", "Disney", "Paramount", "Marvel", "Not Netflix",
            "Netflix and HBO", "Netflix Fan Films", "Mad Max", "HBO documentary about Netflix",
            "Netflix সম্পর্কে", "Netflixについてのドキュメンタリー")
        assertTrue(recognizedPlatforms(names.mapIndexed { i, name -> PlatformCollection("id-$i", name) }).isEmpty())
        assertTrue(recognizedPlatforms(listOf(PlatformCollection("", "Netflix"))).isEmpty())
    }

    @Test fun oneBrandKeepsAllMovieAndSeriesBranchesWithoutChoosingOne() {
        val movies = PlatformCollection("movies-source-id", "Netflix Movies")
        val series = PlatformCollection("series-source-id", "Netflix Series")
        val result = recognizedPlatforms(listOf(movies, series, movies, PlatformCollection("generic", "Drama")))
        assertEquals(1, result.size)
        assertEquals(PlatformBrand.NETFLIX, result.single().brand)
        assertEquals(listOf(movies, series), result.single().targets)
    }

    @Test fun contradictoryIdsAndAbsentBrandsDoNotInventTargets() {
        val results = recognizedPlatforms(listOf(PlatformCollection("same-id", "Netflix"),
            PlatformCollection("same-id", "HBO"), PlatformCollection("branch-id", "Netflix Movies"),
            PlatformCollection("branch-id", "Netflix Series"), PlatformCollection("actual-id", "Hulu")))
        assertEquals(listOf(PlatformBrand.HULU), results.map { it.brand })
        assertEquals("actual-id", results.single().targets.single().id)
    }
}
