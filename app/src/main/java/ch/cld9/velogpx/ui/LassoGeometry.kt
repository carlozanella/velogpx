package ch.cld9.velogpx.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class ScreenPoint(val x: Double, val y: Double)

internal object LassoGeometry {
    fun lineIntersectsPolygon(line: List<ScreenPoint>, polygon: List<ScreenPoint>): Boolean {
        if (line.isEmpty() || polygon.size < 3) return false
        if (line.any { contains(polygon, it) }) return true
        if (line.size < 2) return false
        val polygonEdges = polygon.indices.map { index -> polygon[index] to polygon[(index + 1) % polygon.size] }
        return line.zipWithNext().any { (start, end) ->
            polygonEdges.any { (edgeStart, edgeEnd) -> segmentsIntersect(start, end, edgeStart, edgeEnd) }
        }
    }

    fun contains(polygon: List<ScreenPoint>, point: ScreenPoint): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var previous = polygon.last()
        polygon.forEach { current ->
            if (onSegment(previous, current, point)) return true
            val crosses = (current.y > point.y) != (previous.y > point.y) &&
                point.x < (previous.x - current.x) * (point.y - current.y) /
                (previous.y - current.y) + current.x
            if (crosses) inside = !inside
            previous = current
        }
        return inside
    }

    private fun segmentsIntersect(a: ScreenPoint, b: ScreenPoint, c: ScreenPoint, d: ScreenPoint): Boolean {
        val o1 = orientation(a, b, c)
        val o2 = orientation(a, b, d)
        val o3 = orientation(c, d, a)
        val o4 = orientation(c, d, b)
        if (o1 * o2 < -EPSILON && o3 * o4 < -EPSILON) return true
        return abs(o1) <= EPSILON && onSegment(a, b, c) ||
            abs(o2) <= EPSILON && onSegment(a, b, d) ||
            abs(o3) <= EPSILON && onSegment(c, d, a) ||
            abs(o4) <= EPSILON && onSegment(c, d, b)
    }

    private fun orientation(a: ScreenPoint, b: ScreenPoint, c: ScreenPoint): Double =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun onSegment(a: ScreenPoint, b: ScreenPoint, point: ScreenPoint): Boolean =
        abs(orientation(a, b, point)) <= EPSILON &&
            point.x in min(a.x, b.x) - EPSILON..max(a.x, b.x) + EPSILON &&
            point.y in min(a.y, b.y) - EPSILON..max(a.y, b.y) + EPSILON

    private const val EPSILON = 1e-6
}
