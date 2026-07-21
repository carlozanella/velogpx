package ch.cld9.velogpx.engine

import ch.cld9.velogpx.model.GpxPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {
    @Test fun equatorialDegreeUsesWgs84() {
        val distance = GeoMath.distanceMeters(GpxPoint(0.0, 0.0), GpxPoint(0.0, 1.0))
        assertEquals(111_319.490793, distance, 0.001)
    }

    @Test fun antimeridianTakesShortPath() {
        val distance = GeoMath.distanceMeters(GpxPoint(0.0, 179.9), GpxPoint(0.0, -179.9))
        assertEquals(22_263.898, distance, 1.0)
    }

    @Test fun antipodalDistanceIsFinite() {
        val distance = GeoMath.distanceMeters(GpxPoint(0.0, 0.0), GpxPoint(0.0, 180.0))
        assertEquals(20_003_931.459, distance, 1.0)
        assertTrue(distance.isFinite())
    }

    @Test fun interpolatesAcrossDateline() {
        val middle = GeoMath.interpolate(GpxPoint(0.0, 179.0), GpxPoint(0.0, -179.0), 0.5)
        assertTrue(kotlin.math.abs(kotlin.math.abs(middle.longitude) - 180.0) < 0.001)
    }

    @Test fun wrappedBoundsRemainNarrowAtDateline() {
        val bounds = GeoMath.wrappedBounds(listOf(GpxPoint(2.0, 179.8), GpxPoint(3.0, -179.7)))!!
        assertTrue(bounds.minLongitude > bounds.maxLongitude)
        assertEquals(2.0, bounds.minLatitude, 0.0)
    }
}

