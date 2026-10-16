package com.mojaloss.app

import com.mojaloss.core.model.*

/** Catalog cards are lightweight: fresh server admission, rather than isPlayable, is authoritative. */
internal fun canRequestDownload(title: MediaTitle, scope: SessionScope?, verified: Boolean): Boolean =
    scope != null && verified && title.id.isNotBlank() && title.kind != MediaKind.SERIES && !title.isUpcoming

/** Keep an explicitly requested season bounded, scoped, unique, and forward-only. */
internal fun validateSeasonDownloadPage(
    seriesId: String, season: SeasonOption, start: Int, seenIds: Set<String>, page: EpisodePage,
): OperationResult<List<MediaTitle>> {
    val items = page.items
    val invalid = start < 0 || items.size > 20 || seenIds.size + items.size > 100 ||
        items.any { it.id.isBlank() || it.kind != MediaKind.EPISODE || it.seriesId != seriesId ||
            season.number != null && it.seasonNumber != season.number || it.id in seenIds } ||
        items.map { it.id }.distinct().size != items.size ||
        page.nextStart?.let { items.isEmpty() || it != start + items.size } == true
    return if (invalid) OperationResult.Failure("This season changed. Download episodes individually or reopen the season.")
    else OperationResult.Success(items)
}
