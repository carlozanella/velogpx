package ch.cld9.velogpx.engine

import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSelectionProfileTest {
    @Test fun combinedProfileUsesMergeOrderAndExcludesEndpointGaps() {
        val first = track("A", 0.0, 1.0)
        val backwards = track("B", 3.0, 1.1)

        val result = TrackSelectionProfileEngine.build(listOf(backwards, first), setOf("A", "B"))

        assertEquals(JoinPlanner.plan(listOf(backwards, first), setOf("A", "B")).order, result.plan.order)
        val sourceDistance = listOf(first, backwards).sumOf { TrackPositionEngine.profile(it).totalDistanceMeters }
        assertEquals(sourceDistance, result.totalDistanceMeters, 1e-6)
        assertEquals(sourceDistance, TrackPositionEngine.profile(result.previewTrack).totalDistanceMeters, 1e-6)
        assertTrue(result.plan.totalGapMeters > 0.0)
    }

    @Test fun globalDistanceMapsRoundTripThroughReversedSourceTrack() {
        val first = track("A", 0.0, 1.0)
        val backwards = track("B", 3.0, 1.1)
        val result = TrackSelectionProfileEngine.build(listOf(first, backwards), setOf("A", "B"))
        val reversed = result.sections.first { it.reversed }
        val requested = reversed.startDistanceMeters + reversed.distanceMeters * 0.25

        val source = requireNotNull(result.sourcePositionAtDistance(requested))

        assertEquals(reversed.trackId, source.trackId)
        assertEquals(requested, requireNotNull(result.displayDistance(source)), 1e-5)
    }

    private fun track(id: String, startLongitude: Double, endLongitude: Double) = GpxTrack(
        id = id,
        name = id,
        segments = listOf(
            GpxTrackSegment(
                listOf(
                    GpxPoint(0.0, startLongitude, elevation = 100.0),
                    GpxPoint(0.0, endLongitude, elevation = 200.0),
                ),
            ),
        ),
    )
}
