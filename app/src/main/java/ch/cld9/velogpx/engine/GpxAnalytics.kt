package ch.cld9.velogpx.engine

import ch.cld9.velogpx.model.GpxRoute
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import java.time.Duration
import kotlin.math.hypot

data class TrackStatistics(
    val distanceMeters: Double = 0.0,
    val threeDimensionalDistanceMeters: Double = 0.0,
    val ascentMeters: Double = 0.0,
    val descentMeters: Double = 0.0,
    val minimumElevationMeters: Double? = null,
    val maximumElevationMeters: Double? = null,
    val elapsed: Duration? = null,
    val moving: Duration? = null,
    val averageSpeedKph: Double? = null,
    val movingAverageSpeedKph: Double? = null,
    val pointCount: Int = 0,
    val segmentCount: Int = 0,
)

object GpxAnalytics {
    fun statistics(
        track: GpxTrack,
        elevationNoiseMeters: Double = 3.0,
        movingSpeedThresholdKph: Double = 1.5,
        maximumSampleGap: Duration = Duration.ofMinutes(10),
    ): TrackStatistics = statistics(track.segments, elevationNoiseMeters, movingSpeedThresholdKph, maximumSampleGap)

    fun statistics(route: GpxRoute): TrackStatistics =
        statistics(listOf(GpxTrackSegment(route.points)), 3.0, 1.5, Duration.ofMinutes(10))

    fun statistics(
        segments: List<GpxTrackSegment>,
        elevationNoiseMeters: Double = 3.0,
        movingSpeedThresholdKph: Double = 1.5,
        maximumSampleGap: Duration = Duration.ofMinutes(10),
    ): TrackStatistics {
        var horizontal = 0.0
        var threeDimensional = 0.0
        var ascent = 0.0
        var descent = 0.0
        var elapsedMillis = 0L
        var movingMillis = 0L
        var timedDistance = 0.0
        var movingDistance = 0.0
        val elevations = segments.flatMap { it.points }.mapNotNull { it.elevation }

        for (segment in segments) {
            var elevationAnchor = segment.points.firstOrNull()?.elevation
            for (index in 1 until segment.points.size) {
                val previous = segment.points[index - 1]
                val current = segment.points[index]
                val distance = GeoMath.distanceMeters(previous, current)
                horizontal += distance
                val elevationDelta = if (previous.elevation != null && current.elevation != null) {
                    current.elevation - previous.elevation
                } else 0.0
                threeDimensional += hypot(distance, elevationDelta)

                val elevation = current.elevation
                if (elevation == null) {
                    elevationAnchor = null
                } else if (elevationAnchor != null && kotlin.math.abs(elevation - elevationAnchor) >= elevationNoiseMeters) {
                    val delta = elevation - elevationAnchor
                    if (delta > 0) ascent += delta else descent -= delta
                    elevationAnchor = elevation
                } else if (elevationAnchor == null) elevationAnchor = elevation

                if (previous.time != null && current.time != null) {
                    val sampleMillis = Duration.between(previous.time, current.time).toMillis()
                    if (sampleMillis > 0) {
                        elapsedMillis += sampleMillis
                        timedDistance += distance
                        if (sampleMillis <= maximumSampleGap.toMillis()) {
                            val kph = distance / sampleMillis * 3_600.0
                            if (kph >= movingSpeedThresholdKph) {
                                movingMillis += sampleMillis
                                movingDistance += distance
                            }
                        }
                    }
                }
            }
        }
        return TrackStatistics(
            distanceMeters = horizontal,
            threeDimensionalDistanceMeters = threeDimensional,
            ascentMeters = ascent,
            descentMeters = descent,
            minimumElevationMeters = elevations.minOrNull(),
            maximumElevationMeters = elevations.maxOrNull(),
            elapsed = elapsedMillis.takeIf { it > 0 }?.let(Duration::ofMillis),
            moving = movingMillis.takeIf { it > 0 }?.let(Duration::ofMillis),
            averageSpeedKph = elapsedMillis.takeIf { it > 0 }?.let { timedDistance / it * 3_600.0 },
            movingAverageSpeedKph = movingMillis.takeIf { it > 0 }?.let { movingDistance / it * 3_600.0 },
            pointCount = segments.sumOf { it.points.size },
            segmentCount = segments.size,
        )
    }

    fun cumulativeDistances(segment: GpxTrackSegment): DoubleArray {
        val result = DoubleArray(segment.points.size)
        for (index in 1 until segment.points.size) {
            result[index] = result[index - 1] + GeoMath.distanceMeters(segment.points[index - 1], segment.points[index])
        }
        return result
    }
}
