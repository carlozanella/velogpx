package ch.cld9.velogpx.elevation

import ch.cld9.velogpx.engine.GeoMath
import ch.cld9.velogpx.engine.GpxOperations
import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxTrack
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ElevationLoadResult(
    val track: GpxTrack,
    val requestedSamples: Int,
    val filledPoints: Int,
)

internal data class ElevationSampleLocation(
    val segmentIndex: Int,
    val pointIndex: Int,
    val point: GpxPoint,
)

/**
 * Selects terrain anchors only where elevation is absent. Known samples reset the spacing window,
 * and missing segment endpoints are always selected so interpolation can fill leading/trailing gaps.
 */
internal object ElevationSamplePlanner {
    fun plan(track: GpxTrack, spacingMeters: Double): List<ElevationSampleLocation> {
        require(spacingMeters > 0.0 && spacingMeters.isFinite()) { "Elevation sample spacing must be positive" }
        return buildList {
            track.segments.forEachIndexed { segmentIndex, segment ->
                var distanceSinceAnchor = 0.0
                var missingRunStart: Int? = null
                var samplesAtRunStart = 0
                segment.points.forEachIndexed { pointIndex, point ->
                    if (pointIndex > 0) {
                        distanceSinceAnchor += GeoMath.distanceMeters(segment.points[pointIndex - 1], point)
                    }
                    if (point.elevation != null) {
                        missingRunStart?.let { start ->
                            if (size == samplesAtRunStart) {
                                val middle = (start + pointIndex - 1) / 2
                                add(ElevationSampleLocation(segmentIndex, middle, segment.points[middle]))
                            }
                        }
                        missingRunStart = null
                        distanceSinceAnchor = 0.0
                    } else {
                        if (missingRunStart == null) {
                            missingRunStart = pointIndex
                            samplesAtRunStart = size
                        }
                        if (
                            pointIndex == 0 ||
                            pointIndex == segment.points.lastIndex ||
                            distanceSinceAnchor >= spacingMeters
                        ) {
                            add(ElevationSampleLocation(segmentIndex, pointIndex, point))
                            distanceSinceAnchor = 0.0
                        }
                    }
                }
            }
        }
    }
}

class OpenMeteoElevationClient(
    private val endpoint: String = "https://api.open-meteo.com/v1/elevation",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build(),
    private val batchSize: Int = 100,
    private val sampleSpacingMeters: Double = 180.0,
    private val maximumResponseBytes: Long = 512L * 1024L,
) {
    init {
        require(batchSize in 1..100) { "Open-Meteo accepts at most 100 coordinates per elevation request" }
        require(sampleSpacingMeters > 0.0 && sampleSpacingMeters.isFinite()) { "Sample spacing must be positive" }
        require(maximumResponseBytes > 0) { "Maximum response size must be positive" }
    }

    suspend fun fillMissing(track: GpxTrack): ElevationLoadResult {
        val missingBefore = track.segments.sumOf { segment -> segment.points.count { it.elevation == null } }
        if (missingBefore == 0) return ElevationLoadResult(track, requestedSamples = 0, filledPoints = 0)

        val locations = ElevationSamplePlanner.plan(track, sampleSpacingMeters)
        if (locations.isEmpty()) return ElevationLoadResult(track, requestedSamples = 0, filledPoints = 0)
        val elevations = locations.chunked(batchSize).flatMap { batch -> fetch(batch.map(ElevationSampleLocation::point)) }
        check(elevations.size == locations.size) { "Elevation response did not match the requested coordinates" }

        val segments = track.segments.map { segment -> segment.copy(points = segment.points.toMutableList()) }.toMutableList()
        locations.zip(elevations).forEach { (location, elevation) ->
            val segment = segments[location.segmentIndex]
            val points = segment.points.toMutableList()
            if (points[location.pointIndex].elevation == null) {
                points[location.pointIndex] = points[location.pointIndex].copy(elevation = elevation)
                segments[location.segmentIndex] = segment.copy(points = points)
            }
        }
        val filled = GpxOperations.interpolateMissingElevations(track.copy(segments = segments))
        val missingAfter = filled.segments.sumOf { segment -> segment.points.count { it.elevation == null } }
        return ElevationLoadResult(
            track = filled,
            requestedSamples = locations.size,
            filledPoints = missingBefore - missingAfter,
        )
    }

    private suspend fun fetch(points: List<GpxPoint>): List<Double> {
        val latitudes = points.joinToString(",") { coordinate(it.latitude) }
        val longitudes = points.joinToString(",") { coordinate(it.longitude) }
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("latitude", latitudes)
            .addQueryParameter("longitude", longitudes)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "VeloGPX/1.4")
            .build()
        val bytes = execute(client.newCall(request))
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        if (root.optBoolean("error", false)) error(root.optString("reason", "Elevation service rejected the request"))
        val values = root.optJSONArray("elevation") ?: error("Elevation response has no elevation array")
        if (values.length() != points.size) error("Elevation response returned ${values.length()} values for ${points.size} coordinates")
        return List(values.length()) { index ->
            if (values.isNull(index)) error("Elevation response contains a missing value")
            values.getDouble(index).takeIf(Double::isFinite) ?: error("Elevation response contains a non-finite value")
        }
    }

    private suspend fun execute(call: Call): ByteArray = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    runCatching {
                        val bytes = readLimited(response)
                        if (!response.isSuccessful) {
                            val detail = bytes.toString(Charsets.UTF_8).trim().take(300)
                            error("Elevation service returned HTTP ${response.code}${detail.takeIf(String::isNotEmpty)?.let { ": $it" }.orEmpty()}")
                        }
                        bytes
                    }.onSuccess(continuation::resume).onFailure(continuation::resumeWithException)
                }
            }
        })
    }

    private fun readLimited(response: Response): ByteArray {
        val body = response.body
        if (body.contentLength() > maximumResponseBytes) error("Elevation response is too large")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        body.byteStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maximumResponseBytes) error("Elevation response is too large")
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun coordinate(value: Double): String = String.format(Locale.US, "%.6f", value)
}
