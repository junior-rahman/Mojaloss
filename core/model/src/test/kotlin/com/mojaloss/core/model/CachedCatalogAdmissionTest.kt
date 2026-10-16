package com.mojaloss.core.model

import org.junit.Assert.*
import org.junit.Test

class CachedCatalogAdmissionTest {
    private val title = MediaTitle("saved-title", "Saved title", isPlayable = true)

    @Test fun savedIdentityCannotAuthorizeServiceActionsBeforeVerification() {
        val snapshot = CatalogSnapshot(ContentStatus.READY, listOf(title), isCached = true,
            isComplete = true, isRefreshing = true, isConnectionVerified = false)
        assertFalse(snapshot.canUseLibrary)
        assertTrue(snapshot.copy(isConnectionVerified = true).canUseLibrary)
    }

    @Test fun partialCacheNeverImpliesACompleteLibraryOrConfirmsAServiceFailure() {
        val partial = CatalogSnapshot(ContentStatus.READY, listOf(title), isCached = true, isComplete = false)
        assertFalse(partial.isComplete)
        assertTrue(partial.canUseLibrary)
        // Listing health cannot revoke a verified account's access to known titles.
        assertTrue(partial.copy(status = ContentStatus.ERROR).canUseLibrary)
        assertTrue(partial.copy(status = ContentStatus.OFFLINE).canUseLibrary)
        assertFalse(partial.copy(status = ContentStatus.ERROR, isConnectionVerified = false).canUseLibrary)
        assertFalse(partial.copy(isDemo = true).canUseLibrary)
    }
}
