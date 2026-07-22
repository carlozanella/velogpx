package ch.cld9.velogpx.engine

import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import ch.cld9.velogpx.model.GpxRoute
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackDeduplicatorTest {
    @Test fun `reimported geometry is skipped despite new ids and metadata`() {
        val existing = track("EuroVelo", listOf(point(46.0, 7.0), point(46.1, 7.1)))
        val reimported = track("Renamed", listOf(point(46.0, 7.0), point(46.1, 7.1)))

        val result = TrackDeduplicator.filterNovel(listOf(existing), listOf(reimported))

        assertEquals(1, result.duplicateCount)
        assertEquals(emptyList<GpxTrack>(), result.novelTracks)
    }

    @Test fun `segment boundaries and direction remain semantically distinct`() {
        val continuous = track("One", listOf(point(46.0, 7.0), point(46.1, 7.1)))
        val segmented = GpxTrack(segments = listOf(
            GpxTrackSegment(listOf(point(46.0, 7.0))),
            GpxTrackSegment(listOf(point(46.1, 7.1))),
        ))
        val reversed = track("Reverse", listOf(point(46.1, 7.1), point(46.0, 7.0)))

        val result = TrackDeduplicator.filterNovel(listOf(continuous), listOf(segmented, reversed))

        assertEquals(0, result.duplicateCount)
        assertEquals(2, result.novelTracks.size)
    }

    @Test fun `duplicates within the same import batch are skipped`() {
        val first = track("A", listOf(point(46.0, 7.0), point(46.1, 7.1)))
        val second = first.copy(id = "another", name = "B")
        val result = TrackDeduplicator.filterNovel(emptyList(), listOf(first, second))
        assertEquals(listOf(first), result.novelTracks)
        assertEquals(1, result.duplicateCount)
    }

    @Test fun `route identity uses ordered geometry`() {
        val forward = GpxRoute(points = listOf(point(46.0, 7.0), point(46.1, 7.1)))
        val same = forward.copy(id = "new-id", name = "Renamed")
        val reverse = forward.copy(points = forward.points.reversed())
        assertEquals(TrackDeduplicator.routeFingerprint(forward), TrackDeduplicator.routeFingerprint(same))
        assertNotEquals(TrackDeduplicator.routeFingerprint(forward), TrackDeduplicator.routeFingerprint(reverse))
    }

    @Test fun `waypoint identity includes coordinate and user-facing meaning`() {
        val water = point(46.0, 7.0).copy(name = "Water", symbol = "Drinking Water")
        val duplicate = water.copy(id = "new-id")
        val campsite = water.copy(name = "Campsite")
        assertEquals(TrackDeduplicator.waypointFingerprint(water), TrackDeduplicator.waypointFingerprint(duplicate))
        assertNotEquals(TrackDeduplicator.waypointFingerprint(water), TrackDeduplicator.waypointFingerprint(campsite))
    }

    private fun track(name: String, points: List<GpxPoint>) = GpxTrack(name = name, segments = listOf(GpxTrackSegment(points)))
    private fun point(latitude: Double, longitude: Double) = GpxPoint(latitude, longitude)
}
