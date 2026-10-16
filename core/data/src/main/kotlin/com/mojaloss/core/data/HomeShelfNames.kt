package com.mojaloss.core.data

import com.mojaloss.core.model.HomeShelfKind
import com.mojaloss.core.model.PlatformCollection
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.Locale

/** Unknown words, including non-Latin words, never disappear into an approved category. */
internal fun normalizedCollectionName(value: String): String = value.lowercase(Locale.ROOT)
    .replace("+", " plus ").replace(COLLECTION_SEPARATORS, "")

private val COLLECTION_SEPARATORS = Regex("[\\p{P}\\p{S}\\p{Z}\\s]+")

/** A duplicate ID with conflicting type/name cannot safely label or select a server category. */
internal fun validCollectionTargets(items: List<BaseItemDto>, includeFolders: Boolean,
    includeLibraryViews: Boolean = false): List<PlatformCollection> {
    val types = setOf(BaseItemKind.BOX_SET, BaseItemKind.COLLECTION_FOLDER) +
        (if (includeFolders) setOf(BaseItemKind.FOLDER) else emptySet()) +
        (if (includeLibraryViews) setOf(BaseItemKind.USER_VIEW) else emptySet())
    return items.groupBy { it.id }.values.mapNotNull { versions ->
        if (versions.any { it.type !in types || it.name.isNullOrBlank() }) return@mapNotNull null
        if (versions.map { normalizedCollectionName(it.name.orEmpty()) }.distinct().size != 1 ||
            versions.map { it.type }.distinct().size != 1) return@mapNotNull null
        versions.first().let { PlatformCollection(it.id.toString(), it.name!!.trim()) }
    }
}

internal fun shelfMetadataTerm(kind: HomeShelfKind): String = when (kind) {
    HomeShelfKind.TRENDING -> "Trending"
    HomeShelfKind.HINDI_MOVIES, HomeShelfKind.HINDI_SHOWS -> "Hindi"
    HomeShelfKind.ENGLISH_MOVIES, HomeShelfKind.ENGLISH_SHOWS -> "English"
    HomeShelfKind.INTERNATIONAL_MOVIES, HomeShelfKind.INTERNATIONAL_SHOWS -> "International"
}

/** These are provider-defined categories, not guesses about production country or dubbed audio. */
internal fun matchesShelfSource(kind: HomeShelfKind, name: String): Boolean {
    val aliases = when (kind) {
        HomeShelfKind.TRENDING -> setOf("trending", "trendingnow", "trendingtoday", "trendingthisweek", "trendingthismonth",
            "trendingmovies", "trendingfilms", "trendingshows", "trendingseries", "trendingtvshows")
        HomeShelfKind.HINDI_MOVIES -> movieAliases("hindi")
        HomeShelfKind.ENGLISH_MOVIES -> movieAliases("english")
        HomeShelfKind.HINDI_SHOWS -> showAliases("hindi")
        HomeShelfKind.ENGLISH_SHOWS -> showAliases("english")
        HomeShelfKind.INTERNATIONAL_MOVIES -> movieAliases("international")
        HomeShelfKind.INTERNATIONAL_SHOWS -> showAliases("international")
    }
    return normalizedShelfSourceName(name) in aliases
}

/** Library owners often prefix a display-order number; it is not part of the category. */
private fun normalizedShelfSourceName(value: String) = normalizedCollectionName(
    value.replace(Regex("^\\s*\\d{1,2}\\s*[.)_-]\\s*"), ""))

/**
 * Home opens a real provider category without making the viewer configure the row. Prefer
 * the specifically named movie/show category over a broad language folder. The stable tie
 * break keeps cached paging and See all bound to the same actual source between demands.
 */
internal fun automaticShelfSource(kind: HomeShelfKind, sources: List<PlatformCollection>): PlatformCollection? {
    val canonical = normalizedCollectionName(kind.displayName)
    val broad = normalizedCollectionName(shelfMetadataTerm(kind))
    return sources.filter { matchesShelfSource(kind, it.name) }.minWithOrNull(
        compareBy<PlatformCollection> {
            when (normalizedShelfSourceName(it.name)) {
                canonical -> 0
                broad -> if (kind == HomeShelfKind.TRENDING) 0 else 2
                else -> 1
            }
        }.thenBy { normalizedCollectionName(it.name) }.thenBy { it.id },
    )
}

private fun movieAliases(language: String) = setOf(language, "${language}movies", "${language}movie",
    "${language}films", "${language}film", "${language}moviescollection", "${language}filmscollection",
    "${language}moviecollection", "${language}filmcollection", "movies$language", "films$language")
private fun showAliases(language: String) = setOf(language, "${language}shows", "${language}series",
    "${language}tv", "${language}tvshows", "${language}tvseries", "${language}webseries", "${language}showscollection", "${language}seriescollection",
    "shows$language", "series$language", "tvshows$language", "tvseries$language", "webseries$language")
