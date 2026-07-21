package ch.cld9.velogpx.engine

import ch.cld9.velogpx.model.GpxBounds
import ch.cld9.velogpx.model.GpxPoint
import net.sf.geographiclib.Geodesic
import net.sf.geographiclib.GeodesicMask
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    fun distanceMeters(a: GpxPoint, b: GpxPoint): Double =
        Geodesic.WGS84.Inverse(a.latitude, a.longitude, b.latitude, b.longitude).s12

    fun bearingDegrees(a: GpxPoint, b: GpxPoint): Double =
        (Geodesic.WGS84.Inverse(a.latitude, a.longitude, b.latitude, b.longitude).azi1 + 360.0) % 360.0

    fun interpolate(a: GpxPoint, b: GpxPoint, fraction: Double): GpxPoint {
        val bounded = fraction.coerceIn(0.0, 1.0)
        val inverse = Geodesic.WGS84.Inverse(a.latitude, a.longitude, b.latitude, b.longitude)
        val position = Geodesic.WGS84.Line(a.latitude, a.longitude, inverse.azi1)
            .Position(inverse.s12 * bounded, GeodesicMask.STANDARD)
        val time = if (a.time != null && b.time != null) {
            val nanos = (b.time.toEpochMilli() - a.time.toEpochMilli()) * bounded
            a.time.plusMillis(nanos.toLong())
        } else null
        return GpxPoint(
            latitude = position.lat2,
            longitude = normalizeLongitude(position.lon2),
            elevation = if (a.elevation != null && b.elevation != null) {
                a.elevation + (b.elevation - a.elevation) * bounded
            } else null,
            time = time,
        )
    }

    data class Projection(val fraction: Double, val distanceMeters: Double, val point: GpxPoint)

    fun projectToSegment(query: GpxPoint, start: GpxPoint, end: GpxPoint): Projection {
        val referenceLatitude = Math.toRadians((start.latitude + end.latitude + query.latitude) / 3.0)
        fun xy(point: GpxPoint): Pair<Double, Double> {
            val longitudeDelta = shortestLongitudeDelta(start.longitude, point.longitude)
            val x = Math.toRadians(longitudeDelta) * EARTH_RADIUS_METERS * cos(referenceLatitude)
            val y = Math.toRadians(point.latitude - start.latitude) * EARTH_RADIUS_METERS
            return x to y
        }
        val (endX, endY) = xy(end)
        val (queryX, queryY) = xy(query)
        val lengthSquared = endX * endX + endY * endY
        val fraction = if (lengthSquared == 0.0) 0.0 else
            ((queryX * endX + queryY * endY) / lengthSquared).coerceIn(0.0, 1.0)
        val projected = interpolate(start, end, fraction)
        return Projection(fraction, distanceMeters(query, projected), projected)
    }

    fun distanceToSegmentMeters(query: GpxPoint, start: GpxPoint, end: GpxPoint): Double =
        projectToSegment(query, start, end).distanceMeters

    fun normalizeLongitude(longitude: Double): Double {
        var result = longitude
        while (result > 180.0) result -= 360.0
        while (result < -180.0) result += 360.0
        return result
    }

    fun shortestLongitudeDelta(from: Double, to: Double): Double = normalizeLongitude(to - from)

    fun wrappedBounds(points: Iterable<GpxPoint>): GpxBounds? {
        val list = points.toList()
        if (list.isEmpty()) return null
        val sortedLongitudes = list.map { (it.longitude + 360.0) % 360.0 }.sorted()
        var largestGap = -1.0
        var gapIndex = 0
        for (index in sortedLongitudes.indices) {
            val current = sortedLongitudes[index]
            val next = if (index == sortedLongitudes.lastIndex) sortedLongitudes.first() + 360.0 else sortedLongitudes[index + 1]
            if (next - current > largestGap) {
                largestGap = next - current
                gapIndex = index
            }
        }
        val west = normalizeLongitude(sortedLongitudes[(gapIndex + 1) % sortedLongitudes.size])
        val east = normalizeLongitude(sortedLongitudes[gapIndex])
        return GpxBounds(
            minLatitude = list.minOf { it.latitude },
            minLongitude = west,
            maxLatitude = list.maxOf { it.latitude },
            maxLongitude = east,
        )
    }

    fun metersToLatitudeDegrees(meters: Double): Double = meters / EARTH_RADIUS_METERS * 180.0 / PI
}

