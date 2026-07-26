package ch.cld9.velogpx.data.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTrackGroupsTest {
    @Test fun normalizationKeepsFirstMembershipAndAssignsEveryTrack() {
        val groups = listOf(
            ProjectTrackGroup(id = "one", name = "Imported", trackIds = listOf("a", "b", "missing")),
            ProjectTrackGroup(id = "two", name = "Other", trackIds = listOf("b")),
        )

        val normalized = normalizeTrackGroups(listOf("a", "b", "c"), groups)

        assertEquals(listOf("a", "b"), normalized.first().trackIds)
        assertTrue(normalized.none { "missing" in it.trackIds })
        assertEquals("Tracks", normalized.last().name)
        assertEquals(listOf("c"), normalized.last().trackIds)
        assertEquals(listOf("a", "b", "c"), normalized.flatMap { it.trackIds })
    }

    @Test fun normalizationRemovesEmptyGroups() {
        val normalized = normalizeTrackGroups(
            listOf("a"),
            listOf(ProjectTrackGroup(id = "empty", name = "Empty"), ProjectTrackGroup(id = "full", name = "Full", trackIds = listOf("a"))),
        )

        assertEquals(listOf("full"), normalized.map { it.id })
    }
}
