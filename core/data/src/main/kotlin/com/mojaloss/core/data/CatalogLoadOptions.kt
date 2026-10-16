package com.mojaloss.core.data

/** Bounded page work; every declared catalog page remains eligible to load. */
data class CatalogLoadOptions(
    val pageSize: Int = 24,
    val pageTimeoutMillis: Long = 20_000,
    val transientRetries: Int = 2,
    val retryBackoffMillis: Long = 750,
) {
    init {
        require(pageSize in 1..200)
        require(pageTimeoutMillis > 0)
        require(transientRetries in 0..2)
        require(retryBackoffMillis in 0..5_000)
    }
}
