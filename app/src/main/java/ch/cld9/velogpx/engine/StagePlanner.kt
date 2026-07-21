package ch.cld9.velogpx.engine

import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import java.util.UUID

data class StagePlan(
    val stages: List<GpxTrack>,
    val targetDistanceMeters: Double,
) {
    val totalDistanceMeters: Double get() = stages.sumOf { GpxAnalytics.statistics(it).distanceMeters }
}

object StagePlanner {
    fun byDistance(track: GpxTrack, targetDistanceMeters: Double): StagePlan {
        require(targetDistanceMeters > 0)
        if (track.segments.all { it.points.size < 2 }) return StagePlan(listOf(track), targetDistanceMeters)

        val stageSegments = mutableListOf<MutableList<GpxTrackSegment>>()
        var currentStage = mutableListOf<GpxTrackSegment>()
        var currentPoints = mutableListOf<GpxPoint>()
        var currentExtensions = emptyList<ch.cld9.velogpx.model.XmlElement>()
        var distanceInStage = 0.0

        fun closeSegment() {
            if (currentPoints.isNotEmpty()) {
                currentStage += GpxTrackSegment(currentPoints.toList(), extensions = currentExtensions)
                currentPoints = mutableListOf()
            }
        }
        fun closeStage() {
            closeSegment()
            if (currentStage.isNotEmpty()) {
                stageSegments += currentStage
                currentStage = mutableListOf()
                distanceInStage = 0.0
            }
        }

        for (sourceSegment in track.segments) {
            if (sourceSegment.points.isEmpty()) continue
            if (currentPoints.isNotEmpty()) closeSegment()
            currentExtensions = sourceSegment.extensions
            currentPoints += sourceSegment.points.first()
            for (edgeIndex in 1 until sourceSegment.points.size) {
                var edgeStart = sourceSegment.points[edgeIndex - 1]
                val edgeEnd = sourceSegment.points[edgeIndex]
                var edgeDistance = GeoMath.distanceMeters(edgeStart, edgeEnd)
                while (distanceInStage + edgeDistance > targetDistanceMeters && edgeDistance > 0.01) {
                    val remaining = targetDistanceMeters - distanceInStage
                    val boundary = GeoMath.interpolate(edgeStart, edgeEnd, remaining / edgeDistance)
                    currentPoints += boundary
                    closeStage()
                    currentPoints += boundary
                    edgeStart = boundary
                    edgeDistance = GeoMath.distanceMeters(edgeStart, edgeEnd)
                }
                currentPoints += edgeEnd
                distanceInStage += edgeDistance
                if (kotlin.math.abs(distanceInStage - targetDistanceMeters) < 0.01) closeStage()
            }
            closeSegment()
        }
        closeStage()

        val width = stageSegments.size.toString().length.coerceAtLeast(2)
        val stages = stageSegments.mapIndexed { index, segments ->
            track.copy(
                id = UUID.randomUUID().toString(),
                name = "Day ${(index + 1).toString().padStart(width, '0')} — ${track.name ?: "Bicycle tour"}",
                segments = segments,
            )
        }
        return StagePlan(stages, targetDistanceMeters)
    }
}
