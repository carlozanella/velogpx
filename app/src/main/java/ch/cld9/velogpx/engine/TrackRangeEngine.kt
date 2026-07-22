package ch.cld9.velogpx.engine

import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Stable, snapshot-bound reference to a projected position on one source edge. */
data class TrackLocation(
    val segmentId: String,
    val edgeStartPointId: String,
    val edgeEndPointId: String,
    val fraction: Double,
    val projectedPoint: GpxPoint,
    val distanceFromQueryMeters: Double = 0.0,
)

data class TrackSplitResult(
    val tracks: List<GpxTrack>,
    val appliedCuts: List<TrackLocation>,
    val coalescedCutCount: Int,
)

data class SpanExtractionResult(
    val track: GpxTrack,
    val reversed: Boolean,
    val warnings: List<String> = emptyList(),
)

/**
 * Transactional range operations. All locations are resolved before output is produced; an invalid
 * location therefore leaves the source untouched. Segment gaps are never converted into edges.
 */
object TrackRangeEngine {
    private const val FRACTION_EPSILON = 1e-8
    private const val CUT_COALESCE_METERS = 0.05

    fun project(track: GpxTrack, query: GpxPoint): TrackLocation {
        var best: TrackLocation? = null
        track.segments.forEach { segment ->
            segment.points.zipWithNext().forEach { (start, end) ->
                val projection = GeoMath.projectToSegment(query, start, end)
                val candidate = TrackLocation(
                    segmentId = segment.id,
                    edgeStartPointId = start.id,
                    edgeEndPointId = end.id,
                    fraction = projection.fraction,
                    projectedPoint = projection.point,
                    distanceFromQueryMeters = projection.distanceMeters,
                )
                if (best == null || candidate.distanceFromQueryMeters < best!!.distanceFromQueryMeters) best = candidate
            }
        }
        return best ?: throw IllegalArgumentException("Track has no edge to project onto")
    }

    fun splitAtProjectedCuts(
        track: GpxTrack,
        queries: List<GpxPoint>,
        maximumDistanceMeters: Double = Double.POSITIVE_INFINITY,
    ): TrackSplitResult {
        require(maximumDistanceMeters >= 0.0)
        val locations = queries.map { query ->
            project(track, query).also {
                require(it.distanceFromQueryMeters <= maximumDistanceMeters) {
                    "A split position is farther than $maximumDistanceMeters m from the track"
                }
            }
        }
        return splitAtLocations(track, locations)
    }

    /** Applies every cut in one pass and returns all parts as one undoable result. */
    fun splitAtLocations(track: GpxTrack, locations: List<TrackLocation>): TrackSplitResult {
        if (locations.isEmpty()) return TrackSplitResult(listOf(track), emptyList(), 0)
        val resolved = normalizeCuts(track, locations)
        resolved.forEach { cut ->
            require(hasGeometryBefore(track, cut)) { "Cannot split at the track start" }
            require(hasGeometryAfter(track, cut)) { "Cannot split at the track end" }
        }
        val cutsBySegment = resolved.groupBy { it.segmentIndex }

        val outputParts = mutableListOf<List<GpxTrackSegment>>()
        var current = mutableListOf<GpxTrackSegment>()
        fun closePart() {
            if (current.any { it.points.isNotEmpty() }) outputParts += current
            current = mutableListOf()
        }

        track.segments.forEachIndexed { segmentIndex, segment ->
            val segmentCuts = cutsBySegment[segmentIndex].orEmpty()
            if (segmentCuts.isEmpty() || segment.points.size < 2) {
                current += segment
                return@forEachIndexed
            }

            var cursor = 0.0
            var startOverride: GpxPoint? = null
            segmentCuts.forEach { cut ->
                if (cut.position <= FRACTION_EPSILON) {
                    closePart()
                    cursor = 0.0
                    startOverride = null
                    return@forEach
                }

                val atEnd = cut.position >= segment.points.lastIndex - FRACTION_EPSILON
                val leftBoundary = cut.materializedPoint
                val rightBoundary = clonePoint(leftBoundary)
                val fragment = slicePoints(
                    points = segment.points,
                    from = cursor,
                    to = cut.position,
                    startOverride = startOverride,
                    endOverride = leftBoundary,
                )
                if (fragment.isNotEmpty()) current += segment.copy(points = fragment, id = newId())
                closePart()
                cursor = cut.position
                startOverride = rightBoundary
                if (atEnd) startOverride = null
            }

            if (cursor < segment.points.lastIndex - FRACTION_EPSILON) {
                val tail = slicePoints(
                    points = segment.points,
                    from = cursor,
                    to = segment.points.lastIndex.toDouble(),
                    startOverride = startOverride,
                )
                if (tail.isNotEmpty()) current += segment.copy(points = tail, id = newId())
            }
        }
        closePart()
        require(outputParts.size >= 2) { "Cuts did not divide the track into multiple non-empty parts" }

        val tracks = outputParts.mapIndexed { index, segments ->
            track.copy(
                name = track.name?.let { "$it — part ${index + 1}" },
                segments = segments,
                id = newId(),
            )
        }
        return TrackSplitResult(
            tracks = tracks,
            appliedCuts = resolved.map { it.location },
            coalescedCutCount = locations.size - resolved.size,
        )
    }

    /** Extracts a copy. All descendant IDs are regenerated so it can coexist with the source. */
    fun extractSpan(
        track: GpxTrack,
        start: TrackLocation,
        end: TrackLocation,
        reverseWhenBackwards: Boolean = false,
    ): SpanExtractionResult {
        val resolvedStart = resolve(track, start)
        val resolvedEnd = resolve(track, end)
        val comparison = compareLocations(resolvedStart, resolvedEnd)
        require(comparison != 0) { "Span start and end are the same position" }
        if (comparison > 0 && !reverseWhenBackwards) {
            throw IllegalArgumentException("Span end precedes its start")
        }
        val lower = if (comparison < 0) resolvedStart else resolvedEnd
        val upper = if (comparison < 0) resolvedEnd else resolvedStart
        val segments = extractSegments(track, lower, upper)
        require(segments.any { it.points.isNotEmpty() }) { "The selected span is empty" }
        var extracted = track.copy(
            name = track.name?.let { "$it — extract" },
            segments = segments,
            id = newId(),
        )
        val reversed = comparison > 0
        val warnings = mutableListOf<String>()
        if (reversed) {
            val result = GpxOperations.reverse(extracted, ReverseTimePolicy.KEEP_WITH_POINT)
            extracted = result.value
            warnings += result.warnings
        }
        return SpanExtractionResult(extracted, reversed, warnings)
    }

    private data class ResolvedLocation(
        val location: TrackLocation,
        val segmentIndex: Int,
        val edgeIndex: Int,
        val position: Double,
        val materializedPoint: GpxPoint,
    )

    private fun normalizeCuts(track: GpxTrack, locations: List<TrackLocation>): List<ResolvedLocation> {
        val sorted = locations.map { resolve(track, it) }
            .sortedWith(compareBy<ResolvedLocation> { it.segmentIndex }.thenBy { it.position })
        val result = mutableListOf<ResolvedLocation>()
        sorted.forEach { candidate ->
            val previous = result.lastOrNull()
            if (previous == null || !sameCut(track, previous, candidate)) result += candidate
        }
        return result
    }

    private fun resolve(track: GpxTrack, location: TrackLocation): ResolvedLocation {
        require(location.fraction.isFinite() && location.fraction in 0.0..1.0) { "Cut fraction must be between zero and one" }
        val segmentIndex = track.segments.indexOfFirst { it.id == location.segmentId }
        require(segmentIndex >= 0) { "Cut segment is no longer present" }
        val segment = track.segments[segmentIndex]
        val edgeIndex = (0 until segment.points.lastIndex).firstOrNull { index ->
            segment.points[index].id == location.edgeStartPointId && segment.points[index + 1].id == location.edgeEndPointId
        } ?: throw IllegalArgumentException("Cut edge is no longer present")
        val rawPosition = edgeIndex + location.fraction
        val position = when {
            location.fraction <= FRACTION_EPSILON -> edgeIndex.toDouble()
            location.fraction >= 1.0 - FRACTION_EPSILON -> (edgeIndex + 1).toDouble()
            else -> rawPosition
        }
        val point = pointAt(segment.points, position)
        return ResolvedLocation(
            location = location.copy(fraction = position - edgeIndex, projectedPoint = point),
            segmentIndex = segmentIndex,
            edgeIndex = edgeIndex,
            position = position,
            materializedPoint = point,
        )
    }

    private fun sameCut(track: GpxTrack, first: ResolvedLocation, second: ResolvedLocation): Boolean {
        if (first.segmentIndex != second.segmentIndex) return false
        if (abs(first.position - second.position) <= FRACTION_EPSILON) return true
        val firstEdge = floor(first.position.coerceAtMost(track.segments[first.segmentIndex].points.lastIndex - FRACTION_EPSILON)).toInt()
        val secondEdge = floor(second.position.coerceAtMost(track.segments[second.segmentIndex].points.lastIndex - FRACTION_EPSILON)).toInt()
        return firstEdge == secondEdge && GeoMath.distanceMeters(first.materializedPoint, second.materializedPoint) <= CUT_COALESCE_METERS
    }

    private fun hasGeometryBefore(track: GpxTrack, location: ResolvedLocation): Boolean =
        location.position > FRACTION_EPSILON || track.segments.take(location.segmentIndex).any { it.points.isNotEmpty() }

    private fun hasGeometryAfter(track: GpxTrack, location: ResolvedLocation): Boolean {
        val segment = track.segments[location.segmentIndex]
        return location.position < segment.points.lastIndex - FRACTION_EPSILON ||
            track.segments.drop(location.segmentIndex + 1).any { it.points.isNotEmpty() }
    }

    private fun compareLocations(first: ResolvedLocation, second: ResolvedLocation): Int =
        compareValuesBy(first, second, ResolvedLocation::segmentIndex, ResolvedLocation::position)

    private fun extractSegments(
        track: GpxTrack,
        start: ResolvedLocation,
        end: ResolvedLocation,
    ): List<GpxTrackSegment> = buildList {
        if (start.segmentIndex == end.segmentIndex) {
            val source = track.segments[start.segmentIndex]
            add(
                source.copy(
                    points = slicePoints(source.points, start.position, end.position).map(::clonePoint),
                    id = newId(),
                ),
            )
            return@buildList
        }

        val startSegment = track.segments[start.segmentIndex]
        add(
            startSegment.copy(
                points = slicePoints(startSegment.points, start.position, startSegment.points.lastIndex.toDouble()).map(::clonePoint),
                id = newId(),
            ),
        )
        for (index in start.segmentIndex + 1 until end.segmentIndex) {
            val source = track.segments[index]
            if (source.points.isNotEmpty()) add(cloneSegment(source))
        }
        val endSegment = track.segments[end.segmentIndex]
        add(
            endSegment.copy(
                points = slicePoints(endSegment.points, 0.0, end.position).map(::clonePoint),
                id = newId(),
            ),
        )
    }

    private fun slicePoints(
        points: List<GpxPoint>,
        from: Double,
        to: Double,
        startOverride: GpxPoint? = null,
        endOverride: GpxPoint? = null,
    ): List<GpxPoint> {
        require(points.isNotEmpty())
        require(from >= -FRACTION_EPSILON && to <= points.lastIndex + FRACTION_EPSILON && from <= to + FRACTION_EPSILON)
        if (abs(from - to) <= FRACTION_EPSILON) return listOf(startOverride ?: endOverride ?: pointAt(points, from))
        return buildList {
            add(startOverride ?: pointAt(points, from))
            val firstInterior = floor(from + FRACTION_EPSILON).toInt() + 1
            val lastInterior = floor(to - FRACTION_EPSILON).toInt()
            if (firstInterior <= lastInterior) {
                for (index in firstInterior..lastInterior) {
                    if (index in points.indices && index.toDouble() < to - FRACTION_EPSILON) add(points[index])
                }
            }
            val endPoint = endOverride ?: pointAt(points, to)
            if (last().id != endPoint.id || GeoMath.distanceMeters(last(), endPoint) > 0.001) add(endPoint)
        }
    }

    private fun pointAt(points: List<GpxPoint>, position: Double): GpxPoint {
        val rounded = position.roundToInt()
        if (abs(position - rounded) <= FRACTION_EPSILON) return points[rounded.coerceIn(points.indices)]
        val index = floor(position).toInt().coerceIn(0, points.lastIndex - 1)
        return GeoMath.interpolate(points[index], points[index + 1], position - index)
    }

    private fun clonePoint(point: GpxPoint): GpxPoint = point.copy(id = newId())

    private fun cloneSegment(segment: GpxTrackSegment): GpxTrackSegment = segment.copy(
        points = segment.points.map(::clonePoint),
        id = newId(),
    )

    private fun newId(): String = UUID.randomUUID().toString()
}
