package ch.cld9.velogpx.ui

import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LassoOverlayViewTest {
    @Test fun freehandGestureCollectsPolygonAndCompletesOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var completed: List<ScreenPoint>? = null
        instrumentation.runOnMainSync {
            val view = LassoOverlayView(ApplicationProvider.getApplicationContext()).apply {
                layout(0, 0, 500, 500)
                active = true
                onComplete = { completed = it }
            }
            val downTime = android.os.SystemClock.uptimeMillis()
            listOf(
                event(downTime, downTime, MotionEvent.ACTION_DOWN, 50f, 50f),
                event(downTime, downTime + 10, MotionEvent.ACTION_MOVE, 300f, 50f),
                event(downTime, downTime + 20, MotionEvent.ACTION_MOVE, 300f, 300f),
                event(downTime, downTime + 30, MotionEvent.ACTION_MOVE, 50f, 300f),
                event(downTime, downTime + 40, MotionEvent.ACTION_UP, 50f, 50f),
            ).forEach { motion ->
                assertTrue(view.dispatchTouchEvent(motion))
                motion.recycle()
            }
            assertTrue(view.active)
            assertTrue(view.isClickable)
        }

        val polygon = requireNotNull(completed)
        assertEquals(ScreenPoint(50.0, 50.0), polygon.first())
        assertEquals(ScreenPoint(50.0, 50.0), polygon.last())
        assertTrue(polygon.size >= 5)
        assertTrue(LassoGeometry.contains(polygon, ScreenPoint(150.0, 150.0)))
        assertFalse(LassoGeometry.contains(polygon, ScreenPoint(450.0, 450.0)))
    }

    private fun event(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
}
