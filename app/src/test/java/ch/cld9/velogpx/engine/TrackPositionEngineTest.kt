package ch.cld9.velogpx.engine

import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TrackPositionEngineTest {
    @Test
    fun `profile continues distance but excludes gaps between segments`() {
        val track = track(
            listOf(point(46.0, 7.0), point(46.0, 7.01)),
            listOf(point(47.0, 8.0), point(47.0, 8.01)),
        )

        val profile = TrackPositionEngine.profile(track)

        assertEquals(2, profile.runs.size)
        assertEquals(profile.runs[0].last().distanceMeters, profile.runs[1].first().distanceMeters, 0.001)
        assertEquals(
            GeoMath.distanceMeters(track.segments[0].points[0], track.segments[0].points[1]) +
                GeoMath.distanceMeters(track.segments[1].points[0], track.segments[1].points[1]),
            profile.totalDistanceMeters,
            0.001,
        )
    }

    @Test
    fun `source point lookup retains distance from earlier segments`() {
        val track = track(
            listOf(point(46.0, 7.0), point(46.0, 7.01)),
            listOf(point(46.0, 8.0), point(46.0, 8.01)),
        )

        val position = TrackPositionEngine.atSourcePoint(track, segmentIndex = 1, pointIndex = 1)!!
        val expected = GeoMath.distanceMeters(track.segments[0].points[0], track.segments[0].points[1]) +
            GeoMath.distanceMeters(track.segments[1].points[0], track.segments[1].points[1])

        assertEquals(expected, position.distanceAlongMeters, 0.001)
        assertSame(track.segments[1].points[1], position.point)
    }

    @Test
    fun `bounded profile keeps exact total distance without retaining every point`() {
        val points = (0..100).map { index -> point(46.0, 7.0 + index / 10_000.0) }
        val track = track(points)

        val full = TrackPositionEngine.profile(track)
        val bounded = TrackPositionEngine.profile(track, maximumSamples = 10)

        assertEquals(full.totalDistanceMeters, bounded.totalDistanceMeters, 0.001)
        assertTrue(bounded.samples.size <= 10)
    }

    @Test
    fun `distance lookup interpolates coordinate elevation and time`() {
        val start = point(46.0, 7.0, elevation = 400.0, time = Instant.parse("2026-01-01T00:00:00Z"))
        val end = point(46.0, 7.02, elevation = 600.0, time = Instant.parse("2026-01-01T00:10:00Z"))
        val track = track(listOf(start, end))
        val half = TrackPositionEngine.atDistance(
            track,
            TrackPositionEngine.profile(track).totalDistanceMeters / 2.0,
        )

        assertNotNull(half)
        assertEquals(0.5, half!!.fraction, 0.0001)
        assertEquals(500.0, half.point.elevation!!, 0.001)
        assertEquals(Instant.parse("2026-01-01T00:05:00Z"), half.point.time)
        assertNull(half.sourcePointIndex)
    }

    @Test
    fun `distance lookup at a recorded point preserves that point`() {
        val points = listOf(point(46.0, 7.0), point(46.0, 7.01), point(46.0, 7.02))
        val track = track(points)
        val secondDistance = TrackPositionEngine.profile(track).runs.single()[1].distanceMeters

        val position = TrackPositionEngine.atDistance(track, secondDistance)!!

        assertEquals(1, position.sourcePointIndex)
        assertSame(points[1], position.point)
    }

    @Test
    fun `projection returns nearest position and distance along track`() {
        val track = track(listOf(point(46.0, 7.0), point(46.0, 7.02)))
        val profile = TrackPositionEngine.profile(track)

        val projected = TrackPositionEngine.project(track, point(46.001, 7.01))!!

        assertEquals(profile.totalDistanceMeters / 2.0, projected.distanceAlongMeters, 2.0)
        assertEquals(111.0, projected.distanceToTrackMeters, 3.0)
        assertEquals(0.5, projected.fraction, 0.01)
    }

    @Test
    fun `projection supports one point segments and picks the closest segment`() {
        val near = point(46.0, 7.0)
        val track = track(listOf(point(40.0, 0.0)), listOf(near))

        val projected = TrackPositionEngine.project(track, point(46.0001, 7.0))!!

        assertEquals(1, projected.segmentIndex)
        assertEquals(0, projected.sourcePointIndex)
        assertEquals(11.1, projected.distanceToTrackMeters, 0.5)
    }

    @Test
    fun `empty track has no position`() {
        val empty = GpxTrack(segments = emptyList())
        assertNull(TrackPositionEngine.atDistance(empty, 0.0))
        assertNull(TrackPositionEngine.project(empty, point(46.0, 7.0)))
    }

    private fun track(vararg segments: List<GpxPoint>) = GpxTrack(
        segments = segments.map { GpxTrackSegment(points = it) },
    )

    private fun point(
        latitude: Double,
        longitude: Double,
        elevation: Double? = null,
        time: Instant? = null,
    ) = GpxPoint(latitude, longitude, elevation = elevation, time = time)
}
