package ch.cld9.velogpx.routing

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class BRouterClientTest {
    private lateinit var server: MockWebServer

    @Before fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After fun stopServer() {
        server.shutdown()
    }

    @Test fun sendsOrderedAnchorsAndAlternativeAndParsesRoute() = runTest {
        server.enqueue(MockResponse().setBody(successResponse()))
        val anchors = listOf(
            RoutingAnchor(RouteCoordinate(47.0, 8.0), "start"),
            RoutingAnchor(RouteCoordinate(47.1, 8.1), "via"),
            RoutingAnchor(RouteCoordinate(47.2, 8.2), "end"),
        )
        val client = client()

        val outcome = client.route(
            RoutingRequest(anchors, BicycleProfile.GRAVEL, RouteAlternative.ALTERNATIVE_2),
        ) as RoutingOutcome.Success

        val recorded = server.takeRequest()
        assertEquals("8.0,47.0|8.1,47.1|8.2,47.2", recorded.requestUrl!!.queryParameter("lonlats"))
        assertEquals("gravel", recorded.requestUrl!!.queryParameter("profile"))
        assertEquals("2", recorded.requestUrl!!.queryParameter("alternativeidx"))
        assertEquals("geojson", recorded.requestUrl!!.queryParameter("format"))
        assertEquals(3, outcome.path.points.size)
        assertEquals(1234.0, outcome.path.metrics.distanceMeters!!, 0.0)
        assertEquals(45.0, outcome.path.metrics.filteredAscentMeters!!, 0.0)
        assertEquals(67.0, outcome.path.metrics.durationSeconds!!, 0.0)
        assertEquals(RouteAlternative.ALTERNATIVE_2, outcome.path.alternative)
        assertEquals(listOf("start", "via", "end"), outcome.path.anchorSnaps.map { it.anchorId })
        assertEquals(listOf(0, 1, 2), outcome.path.anchorSnaps.map { it.routePointIndex })
        assertEquals(512.5, outcome.path.points[1].elevationMeters!!, 0.0)
    }

    @Test fun findsLineStringWithoutAssumingFirstFeature() = runTest {
        server.enqueue(MockResponse().setBody(successResponse(pointFeatureFirst = true)))
        val result = client().route(request()) as RoutingOutcome.Success
        assertEquals(3, result.path.points.size)
    }

    @Test fun reportsHttpFailureWithBoundedServerDetail() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody("no route for those anchors"))
        val result = client().route(request()) as RoutingOutcome.Failure
        val failure = result.reason as RoutingFailure.Http
        assertEquals(422, failure.statusCode)
        assertTrue(failure.message.contains("no route"))
    }

    @Test fun rejectsInvalidRequestWithoutNetworkCall() = runTest {
        val invalid = RoutingRequest(
            anchors = listOf(
                RoutingAnchor(RouteCoordinate(91.0, 8.0)),
                RoutingAnchor(RouteCoordinate(47.0, 8.0)),
            ),
            profile = BicycleProfile.TOURING,
        )
        val result = client().route(invalid) as RoutingOutcome.Failure
        assertTrue(result.reason is RoutingFailure.InvalidRequest)
        assertEquals(0, server.requestCount)
    }

    @Test fun rejectsMultipleLinesAndPointLimit() = runTest {
        val duplicateLine = successResponse().replace(
            "\"features\":[",
            "\"features\":[${lineFeature()},",
        )
        server.enqueue(MockResponse().setBody(duplicateLine))
        val malformed = client().route(request()) as RoutingOutcome.Failure
        assertTrue(malformed.reason is RoutingFailure.MalformedResponse)

        server.enqueue(MockResponse().setBody(successResponse()))
        val tooMany = client(maximumRoutePoints = 2).route(request()) as RoutingOutcome.Failure
        assertTrue(tooMany.reason is RoutingFailure.MalformedResponse)
    }

    @Test fun enforcesResponseByteLimit() = runTest {
        server.enqueue(MockResponse().setBody(successResponse()))
        val result = client(maximumResponseBytes = 20).route(request()) as RoutingOutcome.Failure
        assertTrue(result.reason is RoutingFailure.ResponseTooLarge)
    }

    @Test fun coroutineCancellationCancelsInFlightCall() = runTest {
        server.enqueue(MockResponse().setBodyDelay(30, TimeUnit.SECONDS).setBody(successResponse()))
        val call = async { client().route(request()) }
        testScheduler.runCurrent()
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        call.cancelAndJoin()
        assertTrue(call.isCancelled)
    }

    @Test fun compatibilityAdapterReturnsGpxPoints() = runTest {
        server.enqueue(MockResponse().setBody(successResponse()))
        val points = client().route(
            listOf(
                RouteCoordinate(47.0, 8.0).toGpxPoint(),
                RouteCoordinate(47.2, 8.2).toGpxPoint(),
            ),
            BicycleProfile.TOURING,
        )
        assertEquals(3, points.size)
        assertEquals(8.1, points[1].longitude, 0.0)
    }

    private fun client(
        maximumResponseBytes: Long = 1_000_000,
        maximumRoutePoints: Int = 1000,
    ): BRouterClient = BRouterClient(
        endpoint = server.url("/brouter").toString(),
        client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build(),
        maximumResponseBytes = maximumResponseBytes,
        maximumRoutePoints = maximumRoutePoints,
    )

    private fun request(): RoutingRequest = RoutingRequest(
        anchors = listOf(
            RoutingAnchor(RouteCoordinate(47.0, 8.0)),
            RoutingAnchor(RouteCoordinate(47.2, 8.2)),
        ),
        profile = BicycleProfile.TOURING,
    )

    private fun successResponse(pointFeatureFirst: Boolean = false): String {
        val point = """{"type":"Feature","geometry":{"type":"Point","coordinates":[8.0,47.0]}}"""
        val features = if (pointFeatureFirst) "$point,${lineFeature()}" else lineFeature()
        return """{"type":"FeatureCollection","features":[$features]}"""
    }

    private fun lineFeature(): String = """
        {"type":"Feature","properties":{"track-length":"1234","filtered ascend":"45","plain-ascend":"46","total-time":"67","total-energy":"89.5","cost":"101"},"geometry":{"type":"LineString","coordinates":[[8.0,47.0,500],[8.1,47.1,512.5],[8.2,47.2,520]]}}
    """.trimIndent()
}
