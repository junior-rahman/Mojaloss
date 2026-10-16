package com.mojaloss.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateManifestTest {
    @Test
    fun designEvidenceUsesExactlyTheApprovedThirtyNineStableStateIds() {
        val expected = listOf("D", "A", "Q", "T", "B", "P", "S", "U", "R")
            .flatMap { group -> (1..4).map { "$group${it.toString().padStart(2, '0')}" } }
            .toSet() + setOf("F01", "F02", "F03")
        val ids = StateManifest.states.map { it.id }

        assertEquals("Each approved state needs one evidence identity", 39, ids.size)
        assertEquals("No duplicate state can displace another state", 39, ids.toSet().size)
        assertEquals("Do not silently rename or replace approved state IDs", expected, ids.toSet())
        assertTrue("Every state must remain understandable to reviewers", StateManifest.states.all { it.name.isNotBlank() })
    }
}
