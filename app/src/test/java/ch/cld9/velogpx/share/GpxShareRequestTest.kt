package ch.cld9.velogpx.share

import ch.cld9.velogpx.model.GpxDocument
import ch.cld9.velogpx.model.GpxMetadata
import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxRoute
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import ch.cld9.velogpx.model.GpxVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxShareRequestTest {
    @Test fun trackRequestContainsOnlyTheSelectedTrackGeometry() {
        val source = fixture()
        val selected = GpxShareRequest.Track(source, "track-b").materialize()

        assertEquals(GpxVersion.V1_1, selected.document.version)
        assertEquals(listOf("track-b"), selected.document.tracks.map { it.id })
        assertTrue(selected.document.routes.isEmpty())
        assertTrue(selected.document.waypoints.isEmpty())
        assertEquals("Alpine option", selected.document.metadata?.name)
        assertEquals("Alpine option.gpx", selected.displayName)
    }

    @Test fun segmentRequestContainsExactlyOneSelectedSegment() {
        val source = fixture()
        val selected = GpxShareRequest.Segment(source, "track-a", "segment-a2").materialize()

        assertEquals(1, selected.document.tracks.size)
        assertEquals(listOf("segment-a2"), selected.document.tracks.single().segments.map { it.id })
        assertEquals("EuroVelo 5 - segment 2", selected.document.tracks.single().name)
        assertEquals("EuroVelo 5 - segment 2.gpx", selected.displayName)
        assertEquals(48.0, selected.document.tracks.single().segments.single().points.single().latitude, 0.0)
    }

    @Test fun documentRequestPreservesAllGeometryAndForcesGpx11() {
        val source = fixture().copy(version = GpxVersion.V1_0)
        val selected = GpxShareRequest.Document(source).materialize()

        assertEquals(GpxVersion.V1_1, selected.document.version)
        assertEquals(source.tracks.size, selected.document.tracks.size)
        assertEquals(source.routes.size, selected.document.routes.size)
        assertEquals(source.waypoints.size, selected.document.waypoints.size)
    }

    @Test fun fileNameSanitizationRemovesPathsControlsAndDuplicateSuffix() {
        val sanitized = sanitizeGpxFileName(" ../EuroVelo:\u0000 Route?.GPX ")

        assertEquals("EuroVelo_ Route.gpx", sanitized)
        assertFalse(sanitized.contains('/'))
        assertFalse(sanitized.contains('\u0000'))
        assertTrue(sanitized.endsWith(".gpx"))
        assertEquals("route.gpx", sanitizeGpxFileName("...___"))
    }

    @Test fun invalidSelectionIsRejectedBeforeWriting() {
        assertThrows(IllegalArgumentException::class.java) {
            GpxShareRequest.Track(fixture(), "missing").materialize()
        }
        assertThrows(IllegalArgumentException::class.java) {
            GpxShareRequest.Segment(fixture(), "track-a", "missing").materialize()
        }
    }

    private fun fixture() = GpxDocument(
        version = GpxVersion.V1_1,
        metadata = GpxMetadata(name = "Assembly project"),
        waypoints = listOf(GpxPoint(46.0, 7.0, name = "Water")),
        routes = listOf(GpxRoute(name = "Guide route", points = listOf(GpxPoint(46.5, 7.5)))),
        tracks = listOf(
            GpxTrack(
                id = "track-a",
                name = "EuroVelo 5",
                segments = listOf(
                    GpxTrackSegment(id = "segment-a1", points = listOf(GpxPoint(47.0, 8.0))),
                    GpxTrackSegment(id = "segment-a2", points = listOf(GpxPoint(48.0, 9.0))),
                ),
            ),
            GpxTrack(
                id = "track-b",
                name = "Alpine option",
                segments = listOf(GpxTrackSegment(id = "segment-b1", points = listOf(GpxPoint(49.0, 10.0)))),
            ),
        ),
    )
}
