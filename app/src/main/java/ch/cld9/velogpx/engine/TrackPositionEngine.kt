package ch.cld9.velogpx.engine

import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxTrack
import kotlin.math.abs

/** A source point together with its continuous distance from the start of a track. */
data class TrackProfileSample(
    val segmentIndex: Int,
    val pointIndex: Int,
    val point: GpxPoint,
    val distanceMeters: Double,
)

/**
 * Profile samples are kept in separate runs so charts never draw an artificial line across a GPX
 * segment boundary. Distance, however, remains continuous and deliberately excludes those gaps.
 */
data class TrackProfile(
    val runs: List<List<TrackProfileSample>>,
    val totalDistanceMeters: Double,
) {
    val samples: List<TrackProfileSample> get() = runs.flatten()
    val minElevationMeters: Double? get() = samples.mapNotNull { it.point.elevation }.minOrNull()
    val maxElevationMeters: Double? get() = samples.mapNotNull { it.point.elevation }.maxOrNull()
}

/** A precise position on a track. It may be between two recorded GPX points. */
data class TrackPosition(
    val trackId: String,
    val segmentIndex: Int,
    val edgeStartPointIndex: Int,
    val fraction: Double,
    val point: GpxPoint,
    val distanceAlongMeters: Double,
    val distanceToTrackMeters: Double = 0.0,
    val sourcePointIndex: Int? = null,
)

object TrackPositionEngine {
    private const val ENDPOINT_EPSILON = 1e-9

    /**
     * Builds a distance profile. `maximumSamples` only bounds retained chart samples; distances are
     * still accumulated across every source edge, so the total remains exact. The default keeps the
     * original full-precision behavior used by cursor/projection operations.
     */
    fun profile(track: GpxTrack, maximumSamples: Int = Int.MAX_VALUE): TrackProfile {
        var distance = 0.0
        val pointCount = track.segments.sumOf { it.points.size }
        val stride = if (maximumSamples >= pointCount || maximumSamples < 2) 1
        else ((pointCount - 1 + maximumSamples - 2) / (maximumSamples - 1)).coerceAtLeast(1)
        var sourceIndex = 0
        val runs = track.segments.mapIndexedNotNull { segmentIndex, segment ->
            if (segment.points.isEmpty()) return@mapIndexedNotNull null
            segment.points.mapIndexedNotNull { pointIndex, point ->
                if (pointIndex > 0) {
                    distance += GeoMath.distanceMeters(segment.points[pointIndex - 1], point)
                }
                val keep = pointIndex == 0 || pointIndex == segment.points.lastIndex || sourceIndex % stride == 0
                sourceIndex++
                if (keep) TrackProfileSample(segmentIndex, pointIndex, point, distance) else null
            }
        }
        return TrackProfile(runs, distance)
    }

    fun atSourcePoint(track: GpxTrack, segmentIndex: Int, pointIndex: Int): TrackPosition? {
        val segment = track.segments.getOrNull(segmentIndex) ?: return null
        val point = segment.points.getOrNull(pointIndex) ?: return null
        var distance = 0.0
        for (sourceSegmentIndex in 0..segmentIndex) {
            val sourcePoints = track.segments[sourceSegmentIndex].points
            val lastEdge = if (sourceSegmentIndex == segmentIndex) pointIndex else sourcePoints.lastIndex
            for (index in 1..lastEdge) {
                distance += GeoMath.distanceMeters(sourcePoints[index - 1], sourcePoints[index])
            }
        }
        val edgeStart = pointIndex.coerceAtMost(segment.points.lastIndex.coerceAtLeast(0))
        return TrackPosition(
            trackId = track.id,
            segmentIndex = segmentIndex,
            edgeStartPointIndex = edgeStart,
            fraction = 0.0,
            point = point,
            distanceAlongMeters = distance,
            sourcePointIndex = pointIndex,
        )
    }

    fun atDistance(track: GpxTrack, requestedDistanceMeters: Double): TrackPosition? {
        val trackProfile = profile(track)
        val target = requestedDistanceMeters.coerceIn(0.0, trackProfile.totalDistanceMeters)
        val allSamples = trackProfile.samples
        if (allSamples.isEmpty()) return null

        for (run in trackProfile.runs) {
            if (run.size == 1 && abs(target - run[0].distanceMeters) < ENDPOINT_EPSILON) {
                return positionAtSample(track, run[0])
            }
            for (index in 0 until run.lastIndex) {
                val start = run[index]
                val end = run[index + 1]
                if (target <= end.distanceMeters + ENDPOINT_EPSILON &&
                    target >= start.distanceMeters - ENDPOINT_EPSILON
                ) {
                    val edgeDistance = end.distanceMeters - start.distanceMeters
                    val fraction = if (edgeDistance <= ENDPOINT_EPSILON) 0.0
                    else ((target - start.distanceMeters) / edgeDistance).coerceIn(0.0, 1.0)
                    return positionOnEdge(track, start, end, fraction, target)
                }
            }
        }
        return positionAtSample(track, allSamples.last())
    }

    fun project(track: GpxTrack, query: GpxPoint): TrackPosition? {
        val trackProfile = profile(track)
        var best: TrackPosition? = null
        for (run in trackProfile.runs) {
            if (run.size == 1) {
                val sample = run.single()
                val candidate = positionAtSample(
                    track,
                    sample,
                    distanceToTrackMeters = GeoMath.distanceMeters(query, sample.point),
                )
                best = closer(best, candidate)
                continue
            }
            for (index in 0 until run.lastIndex) {
                val start = run[index]
                val end = run[index + 1]
                val projection = GeoMath.projectToSegment(query, start.point, end.point)
                val along = start.distanceMeters +
                    (end.distanceMeters - start.distanceMeters) * projection.fraction
                val candidate = positionOnEdge(
                    track = track,
                    start = start,
                    end = end,
                    fraction = projection.fraction,
                    distanceAlongMeters = along,
                    distanceToTrackMeters = projection.distanceMeters,
                    projectedPoint = projection.point,
                )
                best = closer(best, candidate)
            }
        }
        return best
    }

    private fun closer(current: TrackPosition?, candidate: TrackPosition): TrackPosition = when {
        current == null -> candidate
        candidate.distanceToTrackMeters < current.distanceToTrackMeters - ENDPOINT_EPSILON -> candidate
        abs(candidate.distanceToTrackMeters - current.distanceToTrackMeters) < ENDPOINT_EPSILON &&
            candidate.distanceAlongMeters < current.distanceAlongMeters -> candidate
        else -> current
    }

    private fun positionAtSample(
        track: GpxTrack,
        sample: TrackProfileSample,
        distanceToTrackMeters: Double = 0.0,
    ) = TrackPosition(
        trackId = track.id,
        segmentIndex = sample.segmentIndex,
        edgeStartPointIndex = sample.pointIndex,
        fraction = 0.0,
        point = sample.point,
        distanceAlongMeters = sample.distanceMeters,
        distanceToTrackMeters = distanceToTrackMeters,
        sourcePointIndex = sample.pointIndex,
    )

    private fun positionOnEdge(
        track: GpxTrack,
        start: TrackProfileSample,
        end: TrackProfileSample,
        fraction: Double,
        distanceAlongMeters: Double,
        distanceToTrackMeters: Double = 0.0,
        projectedPoint: GpxPoint? = null,
    ): TrackPosition {
        val bounded = fraction.coerceIn(0.0, 1.0)
        val sourcePointIndex = when {
            bounded <= ENDPOINT_EPSILON -> start.pointIndex
            bounded >= 1.0 - ENDPOINT_EPSILON -> end.pointIndex
            else -> null
        }
        return TrackPosition(
            trackId = track.id,
            segmentIndex = start.segmentIndex,
            edgeStartPointIndex = start.pointIndex,
            fraction = bounded,
            point = when (sourcePointIndex) {
                start.pointIndex -> start.point
                end.pointIndex -> end.point
                else -> projectedPoint ?: GeoMath.interpolate(start.point, end.point, bounded)
            },
            distanceAlongMeters = distanceAlongMeters,
            distanceToTrackMeters = distanceToTrackMeters,
            sourcePointIndex = sourcePointIndex,
        )
    }
}
