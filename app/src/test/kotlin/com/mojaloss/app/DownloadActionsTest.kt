package com.mojaloss.app

import com.mojaloss.core.model.*
import org.junit.Assert.*
import org.junit.Test

class DownloadActionsTest {
    private val scope = SessionScope("https://media.example.test/jelly", "fixture-account", 3)
    private val movie = MediaTitle("movie", "Fixture movie", isPlayable = false)
    private val season = SeasonOption("season-1", 1, "Season 1")
    private fun episode(number: Int) = movie.copy(id = "episode-$number", kind = MediaKind.EPISODE,
        seriesId = "series", seasonNumber = 1, episodeNumber = number)

    @Test fun lightweightCatalogCanRequestFreshAdmissionWithoutPretendingPermissionIsKnown() {
        assertTrue(canRequestDownload(movie, scope, true))
        assertTrue(canRequestDownload(episode(1), scope, true))
        assertFalse(canRequestDownload(movie.copy(kind = MediaKind.SERIES), scope, true))
        assertFalse(canRequestDownload(movie.copy(isUpcoming = true), scope, true))
        assertFalse(canRequestDownload(movie, null, true))
        assertFalse(canRequestDownload(movie, scope, false))
    }
    @Test fun seasonAcceptsOnlyItsOwnRegularEpisodesAndDoesNotInferDownloadPermission() {
        assertTrue(validateSeasonDownloadPage("series", season, 0, emptySet(),
            EpisodePage(listOf(episode(1)), nextStart = 1)) is OperationResult.Success)
        for (wrong in listOf(episode(1).copy(seriesId = "other"), episode(1).copy(seasonNumber = 2),
            movie, episode(1).copy(id = ""))) {
            assertTrue(validateSeasonDownloadPage("series", season, 0, emptySet(),
                EpisodePage(listOf(wrong))) is OperationResult.Failure)
        }
    }
    @Test fun seasonRejectsRepeatedBackwardEmptyAndOversizedPages() {
        val one = episode(1)
        for (page in listOf(EpisodePage(listOf(one, one)), EpisodePage(listOf(one), nextStart = 0),
            EpisodePage(emptyList(), nextStart = 1), EpisodePage((1..21).map(::episode)))) {
            assertTrue(validateSeasonDownloadPage("series", season, 0, emptySet(), page) is OperationResult.Failure)
        }
        assertTrue(validateSeasonDownloadPage("series", season, 20, setOf(one.id), EpisodePage(listOf(one))) is OperationResult.Failure)
    }
    @Test fun seasonNeverAdmitsMoreThanOneHundredOrSkipsCursorPositions() {
        assertTrue(validateSeasonDownloadPage("series", season, 80, (1..80).map { "old-$it" }.toSet(),
            EpisodePage((81..100).map(::episode), nextStart = 100)) is OperationResult.Success)
        assertTrue(validateSeasonDownloadPage("series", season, 90, (1..90).map { "old-$it" }.toSet(),
            EpisodePage((91..110).map(::episode))) is OperationResult.Failure)
        assertTrue(validateSeasonDownloadPage("series", season, 20, emptySet(),
            EpisodePage(listOf(episode(21)), nextStart = 40)) is OperationResult.Failure)
    }
}
