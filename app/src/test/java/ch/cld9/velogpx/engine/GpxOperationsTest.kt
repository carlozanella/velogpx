package ch.cld9.velogpx.engine

import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxBounds
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GpxOperationsTest {
    private val a = GpxPoint(0.0, 0.0, elevation = 100.0, time = Instant.parse("2026-01-01T00:00:00Z"))
    private val b = GpxPoint(0.0, 1.0, elevation = 110.0, time = Instant.parse("2026-01-01T01:00:00Z"))
    private val c = GpxPoint(0.0, 2.0, elevation = 120.0, time = Instant.parse("2026-01-01T03:00:00Z"))

    @Test fun splitDuplicatesBoundaryAndPreservesDistance() {
        val segment = GpxTrackSegment(listOf(a, b, c))
        val before = GpxAnalytics.statistics(listOf(segment)).distanceMeters
        val (left, right) = GpxOperations.splitSegment(segment, 1)
        assertEquals(listOf(a, b), left.points)
        assertEquals(listOf(b, c), right.points)
        val after = GpxAnalytics.statistics(listOf(left, right)).distanceMeters
        assertEquals(before, after, 0.001)
    }

    @Test fun projectedEndpointSplitHasNoDuplicateWithinEitherOutput() {
        val segment = GpxTrackSegment(listOf(a, b, c))
        val (left, right, _) = GpxOperations.splitAtProjectedPoint(segment, b)
        assertEquals(listOf(a, b), left.points)
        assertEquals(listOf(b, c), right.points)
        assertTrue(left.id != right.id)
    }

    @Test fun segmentGapIsNotCounted() {
        val far = GpxPoint(40.0, 40.0)
        val track = GpxTrack(segments = listOf(GpxTrackSegment(listOf(a, b)), GpxTrackSegment(listOf(far, far.copy(longitude = 40.01)))))
        val expected = GeoMath.distanceMeters(a, b) + GeoMath.distanceMeters(far, far.copy(longitude = 40.01))
        assertEquals(expected, GpxAnalytics.statistics(track).distanceMeters, 0.001)
    }

    @Test fun reverseTwiceRestoresGeometry() {
        val track = GpxTrack(segments = listOf(GpxTrackSegment(listOf(a, b)), GpxTrackSegment(listOf(c))))
        val once = GpxOperations.reverse(track, ReverseTimePolicy.KEEP_WITH_POINT).value
        val twice = GpxOperations.reverse(once, ReverseTimePolicy.KEEP_WITH_POINT).value
        assertEquals(track.segments.map { it.points }, twice.segments.map { it.points })
    }

    @Test fun chronologicalReversePreservesEdgeDurations() {
        val track = GpxTrack(segments = listOf(GpxTrackSegment(listOf(a, b, c))))
        val reversed = GpxOperations.reverse(track, ReverseTimePolicy.REASSIGN_MONOTONIC).value.segments.single().points
        assertEquals(listOf(c.id, b.id, a.id), reversed.map { it.id })
        assertTrue(reversed.zipWithNext().all { (first, second) -> second.time!! >= first.time })
        assertEquals(2 * 3600L, java.time.Duration.between(reversed[0].time, reversed[1].time).seconds)
        assertEquals(3600L, java.time.Duration.between(reversed[1].time, reversed[2].time).seconds)
    }

    @Test fun chronologicalReverseIsMonotonicAcrossReversedSegments() {
        val d = GpxPoint(1.0, 3.0, time = Instant.parse("2026-01-01T05:00:00Z"))
        val track = GpxTrack(segments = listOf(GpxTrackSegment(listOf(a, b)), GpxTrackSegment(listOf(c, d))))
        val reversed = GpxOperations.reverse(track, ReverseTimePolicy.REASSIGN_MONOTONIC).value
        val times = reversed.segments.flatMap { it.points }.map { it.time!! }
        assertTrue(times.zipWithNext().all { (first, second) -> second >= first })
    }

    @Test fun simplifyPreservesNamedPoint() {
        val middle = GpxPoint(0.00001, 1.0, name = "Water")
        val track = GpxTrack(segments = listOf(GpxTrackSegment(listOf(a, middle, c))))
        val result = GpxOperations.simplify(track, 100.0).value
        assertEquals(3, result.segments.single().points.size)
    }

    @Test fun timeGenerationUsesDistanceAndSpeed() {
        val second = GpxPoint(0.0, 0.00898315284)
        val track = GpxTrack(segments = listOf(GpxTrackSegment(listOf(GpxPoint(0.0, 0.0), second))))
        val generated = GpxOperations.generateTime(track, Instant.EPOCH, 18.0).segments.single().points
        assertTrue(kotlin.math.abs(200L - java.time.Duration.between(generated[0].time, generated[1].time).seconds) <= 1L)
    }

    @Test fun cropDoesNotConnectAcrossRemovedInterval() {
        val points = listOf(
            GpxPoint(0.0, 0.0), GpxPoint(0.0, 1.0), GpxPoint(10.0, 10.0),
            GpxPoint(0.0, 2.0), GpxPoint(0.0, 3.0),
        )
        val track = GpxTrack(segments = listOf(GpxTrackSegment(points)))
        val result = GpxOperations.crop(track, GpxBounds(-1.0, -1.0, 1.0, 4.0), keepInside = true).value
        assertEquals(2, result.segments.size)
        assertEquals(listOf(points[0], points[1]), result.segments[0].points)
        assertEquals(listOf(points[3], points[4]), result.segments[1].points)
    }

    @Test fun stitchOnlyDeduplicatesActualJoinEndpoint() {
        val stopA = GpxPoint(47.0, 8.0, time = Instant.parse("2026-01-01T00:00:00Z"))
        val stopB = GpxPoint(47.0, 8.0, time = Instant.parse("2026-01-01T00:05:00Z"))
        val join = GpxPoint(47.1, 8.1)
        val first = GpxTrack(segments = listOf(GpxTrackSegment(listOf(stopA, stopB, join))))
        val second = GpxTrack(segments = listOf(GpxTrackSegment(listOf(join.copy(), GpxPoint(47.2, 8.2)))))
        val stitched = GpxOperations.stitch(listOf(first, second)).segments.single().points
        assertEquals(4, stitched.size)
        assertEquals(stopA.time, stitched[0].time)
        assertEquals(stopB.time, stitched[1].time)
    }

    @Test fun stitchKeepsCoincidentJoinPointsWithDifferentTelemetry() {
        val joinA = GpxPoint(47.1, 8.1, time = Instant.parse("2026-01-01T00:00:00Z"))
        val joinB = GpxPoint(47.1, 8.1, time = Instant.parse("2026-01-01T00:10:00Z"))
        val first = GpxTrack(segments = listOf(GpxTrackSegment(listOf(GpxPoint(47.0, 8.0), joinA))))
        val second = GpxTrack(segments = listOf(GpxTrackSegment(listOf(joinB, GpxPoint(47.2, 8.2)))))
        assertEquals(4, GpxOperations.stitch(listOf(first, second)).segments.single().points.size)
    }
}
