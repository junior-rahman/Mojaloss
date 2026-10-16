package com.mojaloss.core.data

import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.LocalDateTime

/** Upload timestamps never stand in for a release date. Unknown dates retain server order. */
internal fun releaseOrderedItems(items: List<BaseItemDto>): List<BaseItemDto> = items.sortedWith(
    compareByDescending<BaseItemDto> { item ->
        item.premiereDate ?: item.productionYear?.takeIf { it in 1..9999 }?.let {
            LocalDateTime.of(it, 1, 1, 0, 0)
        }
    },
)
