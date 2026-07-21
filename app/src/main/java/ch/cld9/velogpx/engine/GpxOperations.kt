package ch.cld9.velogpx.engine

import ch.cld9.velogpx.model.GpxBounds
import ch.cld9.velogpx.model.GpxDocument
import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxRoute
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

enum class ReverseTimePolicy { KEEP_WITH_POINT, REASSIGN_MONOTONIC, CLEAR }

data class EditResult<T>(
    val value: T,
    val warnings: List<String> = emptyList(),
    val removedPoints: Int = 0,
)

data class MergePlan(
    val firstReversed: Boolean,
    val secondReversed: Boolean,
    val gapMeters: Double,
)

object GpxOperations {
    fun reverse(track: GpxTrack, timePolicy: ReverseTimePolicy): EditResult<GpxTrack> {
        var reversedSegments = track.segments.asReversed().map { segment ->
            val reversed = segment.points.asReversed().map { point ->
                point.copy(course = point.course?.let { (it + 180.0) % 360.0 })
            }
            segment.copy(points = reversed, id = UUID.randomUUID().toString())
        }
        reversedSegments = when (timePolicy) {
            ReverseTimePolicy.KEEP_WITH_POINT -> reversedSegments
            ReverseTimePolicy.CLEAR -> reversedSegments.map { segment ->
                segment.copy(points = segment.points.map { it.copy(time = null, timeText = null) })
            }
            ReverseTimePolicy.REASSIGN_MONOTONIC -> reassignReversedTimes(track, reversedSegments)
        }
        val warnings = buildList {
            if (timePolicy == ReverseTimePolicy.KEEP_WITH_POINT &&
            reversedSegments.any { segment -> segment.points.zipWithNext().any { (a, b) -> a.time != null && b.time != null && b.time < a.time } }
            ) add("Timestamps now run backwards; choose chronological reverse or remove time if that is not intended.")
            if (track.extensions.isNotEmpty() || track.segments.any { it.extensions.isNotEmpty() || it.points.any { point -> point.extensions.isNotEmpty() } }) {
                add("Extension data was retained, but proprietary turn instructions may still describe the old direction.")
            }
        }
        return EditResult(track.copy(segments = reversedSegments), warnings)
    }

    private fun reassignReversedTimes(original: GpxTrack, reversedSegments: List<GpxTrackSegment>): List<GpxTrackSegment> {
        val originalPoints = original.segments.flatMap { it.points }
        val start = originalPoints.mapNotNull { it.time }.minOrNull() ?: return reversedSegments
        val durations = originalPoints.zipWithNext().map { (a, b) ->
            if (a.time != null && b.time != null) Duration.between(a.time, b.time).coerceAtLeast(Duration.ZERO) else Duration.ZERO
        }.asReversed()
        var time = start
        val reassigned = reversedSegments.flatMap { it.points }.mapIndexed { index, point ->
            if (index > 0) time = time.plus(durations.getOrElse(index - 1) { Duration.ZERO })
            point.copy(time = time, timeText = null)
        }
        var offset = 0
        return reversedSegments.map { segment ->
            segment.copy(points = reassigned.subList(offset, offset + segment.points.size)).also { offset += segment.points.size }
        }
    }

    fun splitSegment(segment: GpxTrackSegment, pointIndex: Int): Pair<GpxTrackSegment, GpxTrackSegment> {
        require(pointIndex in segment.points.indices) { "Split point is outside the segment" }
        return segment.copy(points = segment.points.subList(0, pointIndex + 1), id = UUID.randomUUID().toString()) to
            segment.copy(points = segment.points.subList(pointIndex, segment.points.size), id = UUID.randomUUID().toString())
    }

    fun splitTrack(track: GpxTrack, segmentIndex: Int, pointIndex: Int): Pair<GpxTrack, GpxTrack> {
        require(segmentIndex in track.segments.indices)
        val (leftSegment, rightSegment) = splitSegment(track.segments[segmentIndex], pointIndex)
        val left = track.copy(
            name = track.name?.let { "$it — part 1" },
            segments = track.segments.take(segmentIndex) + leftSegment,
            id = UUID.randomUUID().toString(),
        )
        val right = track.copy(
            name = track.name?.let { "$it — part 2" },
            segments = listOf(rightSegment) + track.segments.drop(segmentIndex + 1),
            id = UUID.randomUUID().toString(),
        )
        return left to right
    }

    fun splitTrackAtProjectedPoint(
        track: GpxTrack,
        segmentIndex: Int,
        query: GpxPoint,
    ): Triple<GpxTrack, GpxTrack, GeoMath.Projection> {
        require(segmentIndex in track.segments.indices)
        val (leftSegment, rightSegment, projection) = splitAtProjectedPoint(track.segments[segmentIndex], query)
        require(track.segments.take(segmentIndex).isNotEmpty() || leftSegment.points.size >= 2) { "Cannot split at the track start" }
        require(track.segments.drop(segmentIndex + 1).isNotEmpty() || rightSegment.points.size >= 2) { "Cannot split at the track end" }
        val left = track.copy(
            name = track.name?.let { "$it — part 1" },
            segments = track.segments.take(segmentIndex) + leftSegment,
            id = UUID.randomUUID().toString(),
        )
        val right = track.copy(
            name = track.name?.let { "$it — part 2" },
            segments = listOf(rightSegment) + track.segments.drop(segmentIndex + 1),
            id = UUID.randomUUID().toString(),
        )
        return Triple(left, right, projection)
    }

    fun splitAtProjectedPoint(
        segment: GpxTrackSegment,
        query: GpxPoint,
    ): Triple<GpxTrackSegment, GpxTrackSegment, GeoMath.Projection> {
        require(segment.points.size >= 2)
        val projection = segment.points.zipWithNext().mapIndexed { index, (a, b) ->
            index to GeoMath.projectToSegment(query, a, b)
        }.minBy { it.second.distanceMeters }
        val edgeIndex = projection.first
        val projected = projection.second
        if (projected.fraction <= 1e-8) {
            val (left, right) = splitSegment(segment, edgeIndex)
            return Triple(left, right, projected)
        }
        if (projected.fraction >= 1.0 - 1e-8) {
            val (left, right) = splitSegment(segment, edgeIndex + 1)
            return Triple(left, right, projected)
        }
        val boundary = projected.point
        val left = segment.points.take(edgeIndex + 1) + boundary
        val right = listOf(boundary) + segment.points.drop(edgeIndex + 1)
        return Triple(
            segment.copy(points = left, id = UUID.randomUUID().toString()),
            segment.copy(points = right, id = UUID.randomUUID().toString()),
            projected,
        )
    }

    fun mergeDocuments(primary: GpxDocument, others: List<GpxDocument>): EditResult<GpxDocument> {
        val all = listOf(primary) + others
        val warnings = buildList {
            if (all.mapNotNull { it.metadata?.name }.distinct().size > 1) add("Source metadata names differed; the first document metadata was retained.")
            if (all.map { it.version }.distinct().size > 1) add("Mixed GPX versions were merged; export defaults to the first document version.")
        }
        return EditResult(
            primary.copy(
                waypoints = all.flatMap { it.waypoints },
                routes = all.flatMap { it.routes },
                tracks = all.flatMap { it.tracks },
                rootExtensions = all.flatMap { it.rootExtensions },
            ),
            warnings,
        )
    }

    fun combineAsSegments(tracks: List<GpxTrack>, name: String? = null): GpxTrack {
        require(tracks.isNotEmpty())
        return tracks.first().copy(
            name = name ?: tracks.first().name ?: "Combined track",
            segments = tracks.flatMap { it.segments },
            id = UUID.randomUUID().toString(),
        )
    }

    fun stitch(tracks: List<GpxTrack>, deduplicateEndpoints: Boolean = true): GpxTrack {
        require(tracks.isNotEmpty())
        val points = buildList {
            tracks.flatMap { it.segments }.forEach { segment ->
                if (segment.points.isEmpty()) return@forEach
                val firstIndex = if (deduplicateEndpoints && isNotEmpty() &&
                    GeoMath.distanceMeters(last(), segment.points.first()) <= 0.05 &&
                    equivalentPayload(last(), segment.points.first())
                ) 1 else 0
                addAll(segment.points.drop(firstIndex))
            }
        }
        return tracks.first().copy(
            name = tracks.first().name ?: "Stitched track",
            links = tracks.flatMap { it.links }.distinct(),
            extensions = tracks.flatMap { it.extensions },
            segments = listOf(
                GpxTrackSegment(
                    points,
                    extensions = tracks.flatMap { track -> track.segments.flatMap { it.extensions } },
                ),
            ),
            id = UUID.randomUUID().toString(),
        )
    }

    fun bestMergePlan(first: GpxTrack, second: GpxTrack): MergePlan {
        fun endpoints(track: GpxTrack): Pair<GpxPoint, GpxPoint> {
            val points = track.segments.flatMap { it.points }
            require(points.isNotEmpty())
            return points.first() to points.last()
        }
        val (aStart, aEnd) = endpoints(first)
        val (bStart, bEnd) = endpoints(second)
        return listOf(
            MergePlan(false, false, GeoMath.distanceMeters(aEnd, bStart)),
            MergePlan(false, true, GeoMath.distanceMeters(aEnd, bEnd)),
            MergePlan(true, false, GeoMath.distanceMeters(aStart, bStart)),
            MergePlan(true, true, GeoMath.distanceMeters(aStart, bEnd)),
        ).minBy { it.gapMeters }
    }

    fun simplify(track: GpxTrack, toleranceMeters: Double): EditResult<GpxTrack> {
        var removed = 0
        val segments = track.segments.map { segment ->
            val simplified = simplifyPoints(segment.points, toleranceMeters)
            removed += segment.points.size - simplified.size
            segment.copy(points = simplified)
        }
        return EditResult(track.copy(segments = segments), removedPoints = removed)
    }

    private fun simplifyPoints(points: List<GpxPoint>, toleranceMeters: Double): List<GpxPoint> {
        if (points.size <= 2 || toleranceMeters <= 0.0) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        points.forEachIndexed { index, point -> if (point.hasSemanticContent) keep[index] = true }
        val protected = points.indices.filter { keep[it] }.sorted()
        protected.zipWithNext().forEach { (from, to) -> rdp(points, from, to, toleranceMeters, keep) }
        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun rdp(points: List<GpxPoint>, from: Int, to: Int, tolerance: Double, keep: BooleanArray) {
        if (to <= from + 1) return
        var maximum = -1.0
        var index = -1
        for (candidate in from + 1 until to) {
            val distance = GeoMath.distanceToSegmentMeters(points[candidate], points[from], points[to])
            if (distance > maximum) {
                maximum = distance
                index = candidate
            }
        }
        if (maximum > tolerance && index >= 0) {
            keep[index] = true
            rdp(points, from, index, tolerance, keep)
            rdp(points, index, to, tolerance, keep)
        }
    }

    fun removeDuplicates(track: GpxTrack, toleranceMeters: Double = 0.2): EditResult<GpxTrack> {
        var removed = 0
        val segments = track.segments.map { segment ->
            val points = buildList {
                for (point in segment.points) {
                    if (isEmpty() || GeoMath.distanceMeters(last(), point) > toleranceMeters || !equivalentPayload(last(), point)) add(point)
                    else removed++
                }
            }
            segment.copy(points = points)
        }
        return EditResult(track.copy(segments = segments), removedPoints = removed)
    }

    fun removeSpeedSpikes(track: GpxTrack, maximumKph: Double): EditResult<GpxTrack> {
        var removed = 0
        val segments = track.segments.map { segment ->
            if (segment.points.size < 3) return@map segment
            val keep = segment.points.filterIndexed { index, point ->
                if (index == 0 || index == segment.points.lastIndex || point.hasSemanticContent) true
                else {
                    val previous = segment.points[index - 1]
                    val next = segment.points[index + 1]
                    val seconds = if (previous.time != null && point.time != null) Duration.between(previous.time, point.time).seconds else 0
                    val speed = if (seconds > 0) GeoMath.distanceMeters(previous, point) / seconds * 3.6 else 0.0
                    val detour = GeoMath.distanceMeters(previous, point) + GeoMath.distanceMeters(point, next)
                    val direct = GeoMath.distanceMeters(previous, next)
                    val isSpike = speed > maximumKph && detour > direct * 1.5
                    if (isSpike) removed++
                    !isSpike
                }
            }
            segment.copy(points = keep)
        }
        return EditResult(track.copy(segments = segments), removedPoints = removed)
    }

    fun crop(track: GpxTrack, bounds: GpxBounds, keepInside: Boolean): EditResult<GpxTrack> {
        var removed = 0
        val segments = track.segments.flatMap { segment ->
            val runs = mutableListOf<GpxTrackSegment>()
            var current = mutableListOf<GpxPoint>()
            fun closeRun() {
                if (current.isNotEmpty()) runs += segment.copy(points = current.toList(), id = UUID.randomUUID().toString())
                current = mutableListOf()
            }
            for (point in segment.points) {
                if ((point in bounds) == keepInside) current += point
                else {
                    removed++
                    closeRun()
                }
            }
            closeRun()
            runs
        }
        return EditResult(track.copy(segments = segments), removedPoints = removed)
    }

    fun smoothElevation(track: GpxTrack, radius: Int): GpxTrack {
        if (radius <= 0) return track
        return track.copy(segments = track.segments.map { segment ->
            segment.copy(points = segment.points.mapIndexed { index, point ->
                val values = (index - radius..index + radius).mapNotNull { segment.points.getOrNull(it)?.elevation }
                point.copy(elevation = values.takeIf { it.isNotEmpty() }?.average())
            })
        })
    }

    fun interpolateMissingElevations(track: GpxTrack): GpxTrack = track.copy(
        segments = track.segments.map { segment ->
            val points = segment.points.toMutableList()
            var index = 0
            while (index < points.size) {
                if (points[index].elevation != null) { index++; continue }
                val gapStart = index
                while (index < points.size && points[index].elevation == null) index++
                val left = gapStart - 1
                val right = index
                if (left >= 0 && right < points.size) {
                    val total = distance(points, left, right)
                    var traversed = 0.0
                    for (position in gapStart until right) {
                        traversed += GeoMath.distanceMeters(points[position - 1], points[position])
                        val fraction = if (total == 0.0) 0.0 else traversed / total
                        val elevation = points[left].elevation!! + (points[right].elevation!! - points[left].elevation!!) * fraction
                        points[position] = points[position].copy(elevation = elevation)
                    }
                }
            }
            segment.copy(points = points)
        },
    )

    private fun distance(points: List<GpxPoint>, from: Int, to: Int): Double =
        (from + 1..to).sumOf { GeoMath.distanceMeters(points[it - 1], points[it]) }

    fun shiftTime(track: GpxTrack, duration: Duration): GpxTrack = track.copy(
        segments = track.segments.map { segment ->
            segment.copy(points = segment.points.map { point ->
                point.copy(time = point.time?.plus(duration), timeText = null)
            })
        },
    )

    fun clearTime(track: GpxTrack): GpxTrack = track.copy(
        segments = track.segments.map { segment -> segment.copy(points = segment.points.map { it.copy(time = null, timeText = null) }) },
    )

    fun generateTime(track: GpxTrack, start: Instant, speedKph: Double): GpxTrack {
        require(speedKph > 0.0)
        var time = start
        return track.copy(segments = track.segments.map { segment ->
            segment.copy(points = segment.points.mapIndexed { index, point ->
                if (index > 0) {
                    val meters = GeoMath.distanceMeters(segment.points[index - 1], point)
                    time = time.plusMillis((meters / (speedKph / 3.6) * 1000.0).toLong())
                }
                point.copy(time = time, timeText = null)
            })
        })
    }

    fun routeToTrack(route: GpxRoute): GpxTrack = GpxTrack(
        name = route.name,
        comment = route.comment,
        description = route.description,
        source = route.source,
        links = route.links,
        number = route.number,
        type = route.type,
        segments = listOf(GpxTrackSegment(route.points)),
        extensions = route.extensions,
    )

    fun trackToRoute(track: GpxTrack): GpxRoute = GpxRoute(
        name = track.name,
        comment = track.comment,
        description = track.description,
        source = track.source,
        links = track.links,
        number = track.number,
        type = track.type,
        points = track.segments.flatMap { it.points },
        extensions = track.extensions,
    )

    private fun equivalentPayload(a: GpxPoint, b: GpxPoint): Boolean =
        a.copy(latitude = 0.0, longitude = 0.0, id = "") ==
            b.copy(latitude = 0.0, longitude = 0.0, id = "")
}
