package ch.cld9.velogpx.routing

import ch.cld9.velogpx.model.GpxPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class BicycleProfile(val id: String, val label: String) {
    TOURING("trekking", "Touring"),
    ROAD("fastbike", "Road bike"),
    GRAVEL("gravel", "Gravel"),
    SAFETY("safety", "Low traffic"),
    SHORTEST("shortest", "Shortest"),
}

interface RoutingProvider {
    suspend fun route(points: List<GpxPoint>, profile: BicycleProfile): List<GpxPoint>
}

class BRouterClient(
    private val endpoint: String = "https://brouter.de/brouter",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) : RoutingProvider {
    override suspend fun route(points: List<GpxPoint>, profile: BicycleProfile): List<GpxPoint> = withContext(Dispatchers.IO) {
        require(points.size >= 2) { "Routing needs at least two anchors" }
        val lonLats = points.joinToString("|") { "${it.longitude},${it.latitude}" }
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("lonlats", lonLats)
            .addQueryParameter("profile", profile.id)
            .addQueryParameter("alternativeidx", "0")
            .addQueryParameter("format", "geojson")
            .build()
        val request = Request.Builder().url(url).header("User-Agent", "VeloGPX/1.0").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("BRouter returned HTTP ${response.code}")
            val json = JSONObject(response.body.string())
            val coordinates = json.getJSONArray("features").getJSONObject(0)
                .getJSONObject("geometry").getJSONArray("coordinates")
            buildList {
                for (index in 0 until coordinates.length()) {
                    val coordinate = coordinates.getJSONArray(index)
                    add(
                        GpxPoint(
                            longitude = coordinate.getDouble(0),
                            latitude = coordinate.getDouble(1),
                            elevation = coordinate.optDouble(2).takeIf { coordinate.length() > 2 && it.isFinite() },
                        ),
                    )
                }
            }
        }
    }
}
