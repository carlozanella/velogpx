package ch.cld9.velogpx.elevation

import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenMeteoElevationClientTest {
    private lateinit var server: MockWebServer

    @Before fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After fun stopServer() {
        server.shutdown()
    }

    @Test fun plannerSamplesMissingEndpointsAndLongGapsButUsesKnownAnchors() {
        val track = track(
            point(0.0000),
            point(0.0005),
            point(0.0020),
            point(0.0025, elevation = 250.0),
            point(0.0030),
        )

        val locations = ElevationSamplePlanner.plan(track, spacingMeters = 180.0)

        assertEquals(listOf(0, 2, 4), locations.map { it.pointIndex })
    }

    @Test fun plannerRequestsOneTerrainPointForShortInternalMissingRun() {
        val track = track(
            point(0.0000, elevation = 100.0),
            point(0.0002),
            point(0.0004),
            point(0.0006, elevation = 110.0),
        )

        val locations = ElevationSamplePlanner.plan(track, spacingMeters = 180.0)

        assertEquals(listOf(1), locations.map { it.pointIndex })
    }

    @Test fun fillsAllMissingPointsAndPreservesRecordedElevation() = runTest {
        server.enqueue(MockResponse().setBody("""{"elevation":[100,400]}"""))
        val original = track(
            point(0.000),
            point(0.001),
            point(0.002, elevation = 999.0),
            point(0.003),
        )

        val result = client(sampleSpacingMeters = 1_000.0).fillMissing(original)

        val elevations = result.track.segments.single().points.map { it.elevation }
        assertEquals(100.0, elevations[0]!!, 0.0)
        assertTrue(elevations[1]!! in 549.0..550.0)
        assertEquals(999.0, elevations[2]!!, 0.0)
        assertEquals(400.0, elevations[3]!!, 0.0)
        assertEquals(2, result.requestedSamples)
        assertEquals(3, result.filledPoints)
        assertTrue(original.segments.single().points[0].elevation == null)

        val request = server.takeRequest()
        assertEquals("0.000000,0.000000", request.requestUrl!!.queryParameter("latitude"))
        assertEquals("8.000000,8.003000", request.requestUrl!!.queryParameter("longitude"))
    }

    @Test fun batchesAtProviderLimitAndKeepsCoordinateOrder() = runTest {
        server.enqueue(MockResponse().setBody("""{"elevation":[10,20]}"""))
        server.enqueue(MockResponse().setBody("""{"elevation":[30,40]}"""))
        server.enqueue(MockResponse().setBody("""{"elevation":[50]}"""))
        val original = track(*(0..4).map { point(it / 1_000.0) }.toTypedArray())

        val result = client(batchSize = 2, sampleSpacingMeters = 1.0).fillMissing(original)

        assertEquals(listOf(10.0, 20.0, 30.0, 40.0, 50.0), result.track.segments.single().points.map { it.elevation })
        assertEquals(3, server.requestCount)
        assertEquals("8.000000,8.001000", server.takeRequest().requestUrl!!.queryParameter("longitude"))
        assertEquals("8.002000,8.003000", server.takeRequest().requestUrl!!.queryParameter("longitude"))
        assertEquals("8.004000", server.takeRequest().requestUrl!!.queryParameter("longitude"))
    }

    @Test fun fullyElevatedTrackReturnsSameInstanceWithoutNetwork() = runTest {
        val original = track(point(0.0, 100.0), point(0.1, 200.0))

        val result = client().fillMissing(original)

        assertSame(original, result.track)
        assertEquals(0, result.requestedSamples)
        assertEquals(0, result.filledPoints)
        assertEquals(0, server.requestCount)
    }

    @Test fun rejectsIncompleteResponseWithoutModifyingSource() = runTest {
        server.enqueue(MockResponse().setBody("""{"elevation":[100]}"""))
        val original = track(point(0.0), point(0.1))

        val failure = runCatching { client().fillMissing(original) }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("returned 1 values for 2 coordinates"))
        assertTrue(original.segments.single().points.all { it.elevation == null })
    }

    @Test fun cancellationCancelsInFlightElevationRequest() = runTest {
        server.enqueue(MockResponse().setBodyDelay(30, TimeUnit.SECONDS).setBody("""{"elevation":[100,200]}"""))
        val call = async { client().fillMissing(track(point(0.0), point(0.1))) }
        testScheduler.runCurrent()
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))

        call.cancelAndJoin()

        assertTrue(call.isCancelled)
    }

    private fun client(
        batchSize: Int = 100,
        sampleSpacingMeters: Double = 180.0,
    ) = OpenMeteoElevationClient(
        endpoint = server.url("/v1/elevation").toString(),
        client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build(),
        batchSize = batchSize,
        sampleSpacingMeters = sampleSpacingMeters,
    )

    private fun track(vararg points: GpxPoint) = GpxTrack(
        id = "track",
        segments = listOf(GpxTrackSegment(points.toList())),
    )

    private fun point(longitudeOffset: Double, elevation: Double? = null) =
        GpxPoint(latitude = 0.0, longitude = 8.0 + longitudeOffset, elevation = elevation)
}
