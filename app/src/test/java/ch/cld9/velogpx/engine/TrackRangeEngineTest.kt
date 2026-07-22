package ch.cld9.velogpx.engine

import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

class TrackRangeEngineTest {
    @Test fun repeatedProjectedCutsAreSortedAndAppliedAtomically() {
        val track = linearTrack()
        val originalDistance = GpxAnalytics.statistics(track).distanceMeters
        val result = TrackRangeEngine.splitAtProjectedCuts(
            track,
            listOf(query(3.5), query(0.5), query(2.0)),
        )

        assertEquals(4, result.tracks.size)
        assertEquals(3, result.appliedCuts.size)
        assertEquals(0, result.coalescedCutCount)
        assertEquals(originalDistance, result.tracks.sumOf { GpxAnalytics.statistics(it).distanceMeters }, 0.01)
        assertEquals(listOf(0.0, 0.5), longitudes(result.tracks[0]))
        assertEquals(listOf(0.5, 1.0, 2.0), longitudes(result.tracks[1]))
        assertEquals(listOf(2.0, 3.0, 3.5), longitudes(result.tracks[2]))
        assertEquals(listOf(3.5, 4.0), longitudes(result.tracks[3]))
        assertEquals(linearTrack().segments.single().points.map { it.longitude }, track.segments.single().points.map { it.longitude })
    }

    @Test fun cutInputOrderDoesNotChangeGeometry() {
        val track = linearTrack()
        val queries = listOf(query(0.4), query(1.7), query(3.2))
        val forward = TrackRangeEngine.splitAtProjectedCuts(track, queries)
        val shuffled = TrackRangeEngine.splitAtProjectedCuts(track, queries.shuffled(Random(42)))
        assertEquals(forward.tracks.map(::longitudes), shuffled.tracks.map(::longitudes))
    }

    @Test fun nearbyCutsOnSameEdgeAreCoalesced() {
        val track = linearTrack()
        val result = TrackRangeEngine.splitAtProjectedCuts(track, listOf(query(0.5), query(0.50000001)))
        assertEquals(2, result.tracks.size)
        assertEquals(1, result.appliedCuts.size)
        assertEquals(1, result.coalescedCutCount)
    }

    @Test fun cutBoundariesHaveUniqueIdsAcrossOutputs() {
        val track = linearTrack()
        val result = TrackRangeEngine.splitAtProjectedCuts(track, listOf(query(2.0)))
        val leftBoundary = result.tracks[0].segments.last().points.last()
        val rightBoundary = result.tracks[1].segments.first().points.first()
        assertEquals(leftBoundary.latitude, rightBoundary.latitude, 0.0)
        assertEquals(leftBoundary.longitude, rightBoundary.longitude, 0.0)
        assertNotEquals(leftBoundary.id, rightBoundary.id)
        assertEquals(leftBoundary.time, rightBoundary.time)
    }

    @Test fun splittingAcrossSegmentsPreservesGapTopology() {
        val first = GpxTrackSegment(listOf(point(0.0), point(1.0)))
        val second = GpxTrackSegment(listOf(point(10.0), point(11.0)))
        val track = GpxTrack(name = "gapped", segments = listOf(first, second))
        val originalDistance = GpxAnalytics.statistics(track).distanceMeters

        val result = TrackRangeEngine.splitAtProjectedCuts(track, listOf(query(0.5), query(10.5)))

        assertEquals(3, result.tracks.size)
        assertEquals(2, result.tracks[1].segments.size)
        assertEquals(originalDistance, result.tracks.sumOf { GpxAnalytics.statistics(it).distanceMeters }, 0.01)
    }

    @Test fun cutAtTrueSegmentBoundaryDoesNotCreateSingletonFragment() {
        val first = GpxTrackSegment(listOf(point(0.0), point(1.0)))
        val second = GpxTrackSegment(listOf(point(10.0), point(11.0)))
        val track = GpxTrack(segments = listOf(first, second))
        val result = TrackRangeEngine.splitAtProjectedCuts(track, listOf(query(1.0)))
        assertEquals(2, result.tracks.size)
        assertEquals(listOf(2, 2), result.tracks.map { part -> part.segments.sumOf { it.points.size } })
    }

    @Test fun endpointCutsAreRejectedWithoutChangingSource() {
        val track = linearTrack()
        val snapshot = track.copy()
        val failure = runCatching { TrackRangeEngine.splitAtProjectedCuts(track, listOf(query(0.0))) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(snapshot, track)
    }

    @Test fun extractWithinSegmentInterpolatesBoundariesAndRegeneratesIds() {
        val track = linearTrack()
        val result = TrackRangeEngine.extractSpan(
            track,
            TrackRangeEngine.project(track, query(0.5)),
            TrackRangeEngine.project(track, query(2.5)),
        )

        assertEquals(listOf(0.5, 1.0, 2.0, 2.5), longitudes(result.track))
        assertTrue(result.track.segments.single().points.first().elevation!! in 104.9..105.1)
        val sourceIds = track.segments.flatMap { it.points }.mapTo(mutableSetOf()) { it.id }
        assertTrue(result.track.segments.flatMap { it.points }.none { it.id in sourceIds })
        assertTrue(!result.reversed)
    }

    @Test fun extractAcrossSegmentsRetainsSeparateSegmentsAndExcludesGapDistance() {
        val first = GpxTrackSegment(listOf(point(0.0), point(1.0)))
        val second = GpxTrackSegment(listOf(point(10.0), point(11.0)))
        val track = GpxTrack(segments = listOf(first, second))
        val result = TrackRangeEngine.extractSpan(
            track,
            TrackRangeEngine.project(track, query(0.5)),
            TrackRangeEngine.project(track, query(10.5)),
        )
        assertEquals(2, result.track.segments.size)
        val expected = GeoMath.distanceMeters(query(0.5), query(1.0)) + GeoMath.distanceMeters(query(10.0), query(10.5))
        assertEquals(expected, GpxAnalytics.statistics(result.track).distanceMeters, 0.01)
    }

    @Test fun backwardsExtractionRequiresExplicitReverse() {
        val track = linearTrack()
        val later = TrackRangeEngine.project(track, query(3.5))
        val earlier = TrackRangeEngine.project(track, query(1.5))
        assertTrue(runCatching { TrackRangeEngine.extractSpan(track, later, earlier) }.isFailure)

        val reversed = TrackRangeEngine.extractSpan(track, later, earlier, reverseWhenBackwards = true)
        assertTrue(reversed.reversed)
        assertEquals(listOf(3.5, 3.0, 2.0, 1.5), longitudes(reversed.track))
    }

    @Test fun distanceConservationPropertyForRandomCuts() {
        val track = linearTrack(pointCount = 30)
        val random = Random(7)
        repeat(30) {
            val positions = List(5) { random.nextDouble(0.01, 28.99) }.distinct()
            val result = TrackRangeEngine.splitAtProjectedCuts(track, positions.map(::query))
            assertEquals(
                GpxAnalytics.statistics(track).distanceMeters,
                result.tracks.sumOf { GpxAnalytics.statistics(it).distanceMeters },
                0.1,
            )
        }
    }

    private fun linearTrack(pointCount: Int = 5): GpxTrack = GpxTrack(
        name = "route",
        segments = listOf(GpxTrackSegment((0 until pointCount).map { point(it.toDouble()) })),
    )

    private fun point(longitude: Double): GpxPoint = GpxPoint(
        latitude = 0.0,
        longitude = longitude,
        elevation = 100.0 + longitude * 10.0,
        time = Instant.EPOCH.plusSeconds((longitude * 100).toLong()),
    )

    private fun query(longitude: Double): GpxPoint = GpxPoint(latitude = 0.0, longitude = longitude)

    private fun longitudes(track: GpxTrack): List<Double> = track.segments.flatMap { it.points }.map { it.longitude }
}
