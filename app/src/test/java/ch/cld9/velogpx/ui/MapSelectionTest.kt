package ch.cld9.velogpx.ui

import ch.cld9.velogpx.engine.TrackPositionEngine
import ch.cld9.velogpx.model.GpxDocument
import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import ch.cld9.velogpx.model.TrackStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSelectionTest {
    @Test fun hiddenSelectedTrackDoesNotProduceSelectionOverlays() {
        val selected = GpxTrack(id = "selected")
        val document = GpxDocument(tracks = listOf(selected))

        assertNull(visibleSelectedTrack(document, mapOf(selected.id to TrackStyle(0xFF176B45, visible = false)), setOf(selected.id)))
        assertSame(selected, visibleSelectedTrack(document, mapOf(selected.id to TrackStyle(0xFF176B45, visible = true)), setOf(selected.id)))
    }

    @Test fun emptyMapTapClearsSelectionWithoutLeavingMultiSelectOrChangingDocument() {
        val track = GpxTrack(
            id = "ev5",
            segments = listOf(GpxTrackSegment(listOf(GpxPoint(47.0, 8.0), GpxPoint(47.1, 8.1)))),
        )
        val document = GpxDocument(tracks = listOf(track))
        val position = requireNotNull(TrackPositionEngine.atSourcePoint(track, 0, 0))
        val state = EditorUiState(
            document = document,
            selectedTrackId = track.id,
            selectedTrackIds = setOf(track.id),
            selectionMode = true,
            selectedPoint = PointSelection(track.id, 0, 0),
            selectedCursor = TrackCursor(position, TrackCursorSource.MAP),
            currentLocationProjection = position,
            editMode = EditMode.SELECT,
        )

        val cleared = state.withMapSelectionCleared()

        assertNull(cleared.selectedTrackId)
        assertTrue(cleared.selectedTrackIds.isEmpty())
        assertNull(cleared.selectedPoint)
        assertNull(cleared.selectedCursor)
        assertNull(cleared.currentLocationProjection)
        assertTrue(cleared.selectionMode)
        assertEquals(EditMode.SELECT, cleared.editMode)
        assertSame(document, cleared.document)
    }
}
