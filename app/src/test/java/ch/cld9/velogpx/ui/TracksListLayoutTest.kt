package ch.cld9.velogpx.ui

import ch.cld9.velogpx.data.project.ProjectTrackGroup
import ch.cld9.velogpx.model.GpxDocument
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.TrackStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TracksListLayoutTest {
    private val tracks = listOf(
        GpxTrack(id = "a", name = "A"),
        GpxTrack(id = "b", name = "B"),
        GpxTrack(id = "c", name = "C"),
    )

    @Test fun itemIndexAccountsForHeaderSelectionSummaryAndGroups() {
        assertEquals(3, TracksListLayout.itemIndex(tracks, emptyList(), selectionMode = false, trackId = "c"))

        val groups = listOf(
            ProjectTrackGroup(id = "g1", name = "France", trackIds = listOf("a")),
            ProjectTrackGroup(id = "g2", name = "Germany", trackIds = listOf("b", "c")),
        )
        assertEquals(5, TracksListLayout.itemIndex(tracks, groups, selectionMode = true, trackId = "b"))
    }

    @Test fun collapsedTrackHasNoRowUntilItsContainingGroupIsRevealed() {
        val groups = listOf(
            ProjectTrackGroup(id = "g1", name = "France", trackIds = listOf("a"), collapsed = true),
            ProjectTrackGroup(id = "g2", name = "Germany", trackIds = listOf("b", "c"), collapsed = true),
        )

        assertNull(TracksListLayout.itemIndex(tracks, groups, selectionMode = false, trackId = "a"))
        val revealed = TracksListLayout.revealTrack(groups, "a")
        assertFalse(revealed[0].collapsed)
        assertTrue(revealed[1].collapsed)
        assertEquals(2, TracksListLayout.itemIndex(tracks, revealed, selectionMode = false, trackId = "a"))
    }

    @Test fun enteringTracksRevealsAndRequestsThePrimarySelectionExactlyOnce() {
        val group = ProjectTrackGroup(
            id = "g1",
            name = "France",
            trackIds = listOf("b"),
            collapsed = true,
        )
        val mapState = EditorUiState(
            document = GpxDocument(tracks = tracks),
            selectedTrackId = "b",
            selectedTrackIds = setOf("b"),
            panel = EditorPanel.MAP,
            groups = listOf(group),
        )
        var generations = 0

        val entered = TracksListLayout.enterPanel(mapState, EditorPanel.LAYERS) { (++generations).toLong() }
        assertEquals(EditorPanel.LAYERS, entered.panel)
        assertFalse(entered.groups.single().collapsed)
        assertEquals(TrackListFocusRequest(1, "b"), entered.trackListFocusRequest)

        val tappedAgain = TracksListLayout.enterPanel(entered, EditorPanel.LAYERS) { (++generations).toLong() }
        assertEquals(1, generations)
        assertEquals(entered.trackListFocusRequest, tappedAgain.trackListFocusRequest)
    }

    @Test fun hiddenGroupGatesMapVisibilityWithoutChangingTrackPreference() {
        val preferredStyle = TrackStyle(color = 0xFF176B45, visible = true)
        val state = EditorUiState(
            document = GpxDocument(tracks = tracks),
            styles = mapOf("a" to preferredStyle),
            groups = listOf(ProjectTrackGroup(id = "g1", name = "Hidden", trackIds = listOf("a"), visible = false)),
        )

        assertFalse(state.mapStyles.getValue("a").visible)
        assertTrue(state.styles.getValue("a").visible)
    }
}
