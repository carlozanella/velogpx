package ch.cld9.velogpx.engine

import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class GpxAnalyticsTest {
    @Test fun averageSpeedUsesOnlyTimedEdges() {
        val points = listOf(
            GpxPoint(0.0, 0.0),
            GpxPoint(0.0, 0.9),
            GpxPoint(0.0, 0.9, time = Instant.EPOCH),
            GpxPoint(0.0, 0.908983, time = Instant.EPOCH.plusSeconds(3600)),
        )
        val stats = GpxAnalytics.statistics(GpxTrack(segments = listOf(GpxTrackSegment(points))))
        assertEquals(1.0, stats.averageSpeedKph!!, 0.02)
    }

    @Test fun ascentDoesNotBridgeMissingElevationGap() {
        val points = listOf(GpxPoint(0.0, 0.0, elevation = 100.0), GpxPoint(0.0, 0.1), GpxPoint(0.0, 0.2, elevation = 300.0))
        val stats = GpxAnalytics.statistics(GpxTrack(segments = listOf(GpxTrackSegment(points))))
        assertEquals(0.0, stats.ascentMeters, 0.0)
    }
}
