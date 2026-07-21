package ch.cld9.velogpx.engine

import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import ch.cld9.velogpx.model.XmlElement
import ch.cld9.velogpx.model.XmlName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StagePlannerTest {
    @Test fun stagesCoverRouteExactlyOnceWithoutDistanceLoss() {
        val points = (0..10).map { GpxPoint(0.0, it * 0.1) }
        val track = GpxTrack(name = "EuroVelo", segments = listOf(GpxTrackSegment(points)))
        val originalDistance = GpxAnalytics.statistics(track).distanceMeters
        val plan = StagePlanner.byDistance(track, 30_000.0)
        assertEquals(4, plan.stages.size)
        assertEquals(originalDistance, plan.totalDistanceMeters, 0.01)
        plan.stages.dropLast(1).forEach { stage ->
            assertEquals(30_000.0, GpxAnalytics.statistics(stage).distanceMeters, 0.01)
        }
        plan.stages.zipWithNext().forEach { (first, second) ->
            assertEquals(first.segments.last().points.last().latitude, second.segments.first().points.first().latitude, 0.0)
            assertEquals(first.segments.last().points.last().longitude, second.segments.first().points.first().longitude, 1e-10)
        }
        assertTrue(plan.stages.first().name!!.startsWith("Day 01"))
    }

    @Test fun stageSplitPreservesSegmentExtensions() {
        val extension = XmlElement(XmlName("urn:test", "style", "t"))
        val track = GpxTrack(segments = listOf(GpxTrackSegment(listOf(GpxPoint(0.0, 0.0), GpxPoint(0.0, 1.0)), extensions = listOf(extension))))
        val plan = StagePlanner.byDistance(track, 30_000.0)
        assertTrue(plan.stages.all { stage -> stage.segments.all { it.extensions == listOf(extension) } })
    }
}
