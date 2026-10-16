package com.mojaloss.core.model

import java.util.Locale

/** Labels for explicitly named groups in this library, not external watch availability. */
enum class PlatformBrand(val displayName: String, internal val aliases: Set<String>) {
    NETFLIX("Netflix", setOf("netflix")),
    HBO("HBO", setOf("hbo", "hbomax", "max")),
    APPLE_TV("Apple TV", setOf("appletv", "appletvplus", "appleoriginals")),
    PRIME_VIDEO("Prime Video", setOf("primevideo", "amazonprimevideo", "amazonprime")),
    DISNEY_PLUS("Disney+", setOf("disneyplus")),
    HULU("Hulu", setOf("hulu")),
    PARAMOUNT_PLUS("Paramount+", setOf("paramountplus")),
    PEACOCK("Peacock", setOf("peacock", "peacocktv")),
}

data class PlatformGroup(val brand: PlatformBrand, val targets: List<PlatformCollection>)

/**
 * The platform strip accepts only explicit brand names and known branch
 * labels qualify. Every source ID/name is retained, so multiple Movies/Series
 * branches can be selected separately instead of inventing a combined result.
 */
fun recognizedPlatforms(collections: List<PlatformCollection>): List<PlatformGroup> {
    val targets = collections.filter { it.id.isNotBlank() && it.name.isNotBlank() }
        .groupBy { it.id }.values.mapNotNull { versions ->
            // A contradictory duplicate ID is unsafe to attach to any brand.
            val brands = versions.map { platformBrand(it.name) }.distinct()
            val labels = versions.map { normalizedPlatformName(it.name) }.distinct()
            if (brands.size == 1 && labels.size == 1) brands.single()?.let { it to versions.first() } else null
        }
    return PlatformBrand.entries.mapNotNull { brand ->
        targets.filter { it.first == brand }.map { it.second }.takeIf { it.isNotEmpty() }
            ?.let { PlatformGroup(brand, it) }
    }
}

private fun platformBrand(name: String): PlatformBrand? {
    val normalized = normalizedPlatformName(name)
    val matches = PlatformBrand.entries.filter { brand ->
        brand.aliases.any { alias ->
            normalized.startsWith(alias) && normalized.removePrefix(alias) in PLATFORM_BRANCH_SUFFIXES
        }
    }
    return matches.singleOrNull()
}

private fun normalizedPlatformName(name: String): String = name.lowercase(Locale.ROOT)
    .replace("+", " plus ").replace(NON_PLATFORM_NAME_CHARACTERS, "")

// Unknown Unicode words remain intact; they must never disappear into a brand alias.
private val NON_PLATFORM_NAME_CHARACTERS = Regex("[\\p{P}\\p{S}\\p{Z}\\s]+")
private val PLATFORM_BRANCH_SUFFIXES = setOf(
    "", "movies", "films", "series", "shows", "tv", "tvshows", "originals",
    "collection", "collections", "originalmovies", "originalseries", "originalshows",
    "originalsmovies", "originalsseries", "originalsshows", "originalscollection",
    "moviescollection", "seriescollection", "showscollection",
)
