package ch.cld9.velogpx.routing

import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.engine.GeoMath
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

enum class BicycleProfile(val id: String, val label: String) {
    TOURING("trekking", "Touring"),
    ROAD("fastbike", "Road bike"),
    GRAVEL("gravel", "Gravel"),
    SAFETY("safety", "Low traffic"),
    SHORTEST("shortest", "Shortest"),
}

interface RoutingProvider {
    suspend fun route(request: RoutingRequest): RoutingOutcome

    /** Compatibility adapter for the original point-based editor API. */
    suspend fun route(points: List<GpxPoint>, profile: BicycleProfile): List<GpxPoint> {
        val request = RoutingRequest(
            anchors = points.map { RoutingAnchor(RouteCoordinate.from(it)) },
            profile = profile,
        )
        return when (val outcome = route(request)) {
            is RoutingOutcome.Success -> outcome.path.points.map(RoutedPoint::toGpxPoint)
            is RoutingOutcome.Failure -> throw RoutingException(outcome.reason)
        }
    }
}

class BRouterClient(
    private val endpoint: String = "https://brouter.de/brouter",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val maximumResponseBytes: Long = 16L * 1024L * 1024L,
    private val maximumRoutePoints: Int = 1_000_000,
) : RoutingProvider {
    init {
        require(maximumResponseBytes > 0) { "Maximum response size must be positive" }
        require(maximumRoutePoints >= 2) { "Maximum route points must be at least two" }
    }

    override suspend fun route(request: RoutingRequest): RoutingOutcome {
        validate(request)?.let { return RoutingOutcome.Failure(it) }
        val lonLats = request.anchors.joinToString("|") { anchor ->
            "${anchor.coordinate.longitude},${anchor.coordinate.latitude}"
        }
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("lonlats", lonLats)
            .addQueryParameter("profile", request.profile.id)
            .addQueryParameter("alternativeidx", request.alternative.index.toString())
            .addQueryParameter("format", "geojson")
            .build()
        val httpRequest = Request.Builder().url(url).header("User-Agent", "VeloGPX/1.1").build()
        return execute(client.newCall(httpRequest), request)
    }

    private fun validate(request: RoutingRequest): RoutingFailure.InvalidRequest? = when {
        request.anchors.size < 2 -> RoutingFailure.InvalidRequest("Routing needs at least two anchors")
        request.anchors.any { !it.coordinate.isValid } -> RoutingFailure.InvalidRequest("Routing anchors must contain finite, valid coordinates")
        else -> null
    }

    private suspend fun execute(call: Call, routingRequest: RoutingRequest): RoutingOutcome =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive) return
                    continuation.resume(
                        RoutingOutcome.Failure(
                            RoutingFailure.Network(e.message ?: "BRouter request failed"),
                        ),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        val outcome = runCatching { parseResponse(response, routingRequest) }
                            .getOrElse { error ->
                                RoutingOutcome.Failure(
                                    if (error is ResponseLimitException) {
                                        RoutingFailure.ResponseTooLarge(maximumResponseBytes)
                                    } else {
                                        RoutingFailure.MalformedResponse(error.message ?: "Invalid BRouter response")
                                    },
                                )
                            }
                        if (continuation.isActive) continuation.resume(outcome)
                    }
                }
            })
        }

    private fun parseResponse(response: Response, request: RoutingRequest): RoutingOutcome {
        val body = readLimitedBody(response)
        if (!response.isSuccessful) {
            val detail = body.toString(Charsets.UTF_8).trim().take(500)
            return RoutingOutcome.Failure(
                RoutingFailure.Http(
                    response.code,
                    "BRouter returned HTTP ${response.code}${detail.takeIf(String::isNotEmpty)?.let { ": $it" }.orEmpty()}",
                ),
            )
        }
        val root = JSONObject(body.toString(Charsets.UTF_8))
        if (root.optString("type") != "FeatureCollection") error("Expected a GeoJSON FeatureCollection")
        val features = root.optJSONArray("features") ?: error("GeoJSON response has no features")
        val lineFeatures = buildList {
            for (index in 0 until features.length()) {
                val feature = features.optJSONObject(index) ?: continue
                val geometry = feature.optJSONObject("geometry") ?: continue
                if (geometry.optString("type") == "LineString") add(feature)
            }
        }
        if (lineFeatures.size != 1) error("Expected exactly one LineString, found ${lineFeatures.size}")
        val feature = lineFeatures.single()
        val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates")
        if (coordinates.length() < 2) error("The routed LineString contains fewer than two points")
        if (coordinates.length() > maximumRoutePoints) error("The routed LineString exceeds $maximumRoutePoints points")
        val points = List(coordinates.length()) { index -> parsePoint(coordinates.getJSONArray(index), index) }
        val metrics = parseMetrics(feature.optJSONObject("properties"))
        return RoutingOutcome.Success(
            RoutedPath(
                points = points,
                metrics = metrics,
                anchorSnaps = matchAnchors(request.anchors, points),
                alternative = request.alternative,
            ),
        )
    }

    private fun readLimitedBody(response: Response): ByteArray {
        val body = response.body
        val declaredLength = body.contentLength()
        if (declaredLength > maximumResponseBytes) throw ResponseLimitException()
        val stream = body.byteStream()
        val output = ByteArrayOutputStream(minOf(maximumResponseBytes, 64L * 1024L).toInt())
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maximumResponseBytes) throw ResponseLimitException()
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun parsePoint(coordinate: JSONArray, index: Int): RoutedPoint {
        if (coordinate.length() < 2) error("Coordinate $index has fewer than two values")
        val longitude = coordinate.getDouble(0)
        val latitude = coordinate.getDouble(1)
        val routeCoordinate = RouteCoordinate(latitude, longitude)
        if (!routeCoordinate.isValid) error("Coordinate $index is outside valid latitude/longitude bounds")
        val elevation = if (coordinate.length() > 2 && !coordinate.isNull(2)) coordinate.getDouble(2) else null
        if (elevation != null && !elevation.isFinite()) error("Coordinate $index contains a non-finite elevation")
        return RoutedPoint(routeCoordinate, elevation)
    }

    private fun parseMetrics(properties: JSONObject?): RouteMetrics {
        fun number(name: String): Double? {
            if (properties == null || !properties.has(name) || properties.isNull(name)) return null
            val value = properties.opt(name)
            return when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                null -> null
                else -> null
            }?.takeIf(Double::isFinite)
        }
        return RouteMetrics(
            distanceMeters = number("track-length"),
            filteredAscentMeters = number("filtered ascend"),
            plainAscentMeters = number("plain-ascend"),
            durationSeconds = number("total-time"),
            energy = number("total-energy"),
            cost = number("cost"),
        )
    }

    private fun matchAnchors(anchors: List<RoutingAnchor>, points: List<RoutedPoint>): List<AnchorSnap> {
        var minimumIndex = 0
        return anchors.mapIndexed { anchorIndex, anchor ->
            val pointIndex = when (anchorIndex) {
                0 -> 0
                anchors.lastIndex -> points.lastIndex
                else -> (minimumIndex..points.lastIndex).minBy { index ->
                    GeoMath.distanceMeters(anchor.coordinate.toGpxPoint(), points[index].toGpxPoint())
                }
            }
            minimumIndex = pointIndex
            AnchorSnap(
                anchorId = anchor.id,
                routePointIndex = pointIndex,
                distanceMeters = GeoMath.distanceMeters(anchor.coordinate.toGpxPoint(), points[pointIndex].toGpxPoint()),
            )
        }
    }

    private class ResponseLimitException : IllegalStateException()
}
