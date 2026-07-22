package ch.cld9.velogpx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LassoGeometryTest {
    private val square = listOf(
        ScreenPoint(0.0, 0.0), ScreenPoint(10.0, 0.0),
        ScreenPoint(10.0, 10.0), ScreenPoint(0.0, 10.0),
    )

    @Test fun selectsLinesInsideOrCrossingLasso() {
        assertTrue(LassoGeometry.lineIntersectsPolygon(listOf(ScreenPoint(2.0, 2.0), ScreenPoint(8.0, 8.0)), square))
        assertTrue(LassoGeometry.lineIntersectsPolygon(listOf(ScreenPoint(-2.0, 5.0), ScreenPoint(12.0, 5.0)), square))
        assertTrue(LassoGeometry.lineIntersectsPolygon(listOf(ScreenPoint(0.0, 4.0)), square))
    }

    @Test fun rejectsLinesCompletelyOutsideLasso() {
        assertFalse(LassoGeometry.lineIntersectsPolygon(listOf(ScreenPoint(20.0, 20.0), ScreenPoint(30.0, 30.0)), square))
    }
}
