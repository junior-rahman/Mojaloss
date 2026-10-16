package com.mojaloss.core.data

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.util.UUID

class ReleaseOrderTest {
    @Test fun actualDayAndMonthOutrankYearsAndUploadDatesAreNeverUsed() {
        val oldNewUpload = item(1, "2020-12-31T00:00:00", 2020, "2026-10-16T00:00:00")
        val august = item(2, "2026-08-02T00:00:00", 2026, "2026-08-02T00:00:00")
        val september = item(3, "2026-09-01T00:00:00", 2026, "2026-09-01T00:00:00")
        assertEquals(listOf(september, august, oldNewUpload), releaseOrderedItems(listOf(oldNewUpload, august, september)))
    }

    @Test fun missingDateUsesOnlyValidProductionYearAndUnknownDatesStayLastInStableOrder() {
        val yearOnly = item(1, null, 2026)
        val dated = item(2, "2025-12-31T00:00:00", 2025)
        val unknown = item(3, null, null)
        val invalidYear = item(4, null, 10000)
        assertEquals(listOf(yearOnly, dated, unknown, invalidYear), releaseOrderedItems(listOf(unknown, dated, yearOnly, invalidYear)))
    }

    @Test fun actualPremiereDateWinsOverAContradictoryProductionYear() {
        val early = item(1, "2020-01-01T00:00:00", 2026)
        val recent = item(2, "2025-01-01T00:00:00", 2024)
        assertEquals(listOf(recent, early), releaseOrderedItems(listOf(early, recent)))
    }

    private fun item(id: Int, date: String?, year: Int?, uploaded: String? = null) = BaseItemDto(
        id = UUID(0, id.toLong()), name = "Fixture $id", type = BaseItemKind.MOVIE, productionYear = year,
        premiereDate = date?.let(LocalDateTime::parse), dateCreated = uploaded?.let(LocalDateTime::parse),
    )
}
