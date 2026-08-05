package ch.cld9.velogpx.model

import java.time.Instant
import java.util.UUID

enum class GpxVersion(val value: String, val namespace: String) {
    V1_0("1.0", "http://www.topografix.com/GPX/1/0"),
    V1_1("1.1", "http://www.topografix.com/GPX/1/1");

    companion object {
        fun from(value: String?, namespace: String?): GpxVersion = when {
            value == "1.0" || namespace?.endsWith("/1/0") == true -> V1_0
            else -> V1_1
        }
    }
}

data class XmlName(
    val namespaceUri: String? = null,
    val localName: String,
    val prefix: String? = null,
) {
    val qualifiedName: String get() = if (prefix.isNullOrBlank()) localName else "$prefix:$localName"
}

data class XmlAttribute(val name: XmlName, val value: String)

sealed interface XmlContent
data class XmlText(val value: String) : XmlContent
data class XmlCData(val value: String) : XmlContent
data class XmlComment(val value: String) : XmlContent
data class XmlElement(
    val name: XmlName,
    val namespaceDeclarations: Map<String, String> = emptyMap(),
    val attributes: List<XmlAttribute> = emptyList(),
    val children: List<XmlContent> = emptyList(),
) : XmlContent

data class GpxLink(
    val href: String,
    val text: String? = null,
    val type: String? = null,
)

data class GpxPerson(
    val name: String? = null,
    val emailId: String? = null,
    val emailDomain: String? = null,
    val link: GpxLink? = null,
)

data class GpxCopyright(
    val author: String,
    val year: Int? = null,
    val license: String? = null,
)

data class GpxBounds(
    val minLatitude: Double,
    val minLongitude: Double,
    val maxLatitude: Double,
    val maxLongitude: Double,
) {
    operator fun contains(point: GpxPoint): Boolean =
        point.latitude in minLatitude..maxLatitude &&
            if (minLongitude <= maxLongitude) point.longitude in minLongitude..maxLongitude
            else point.longitude >= minLongitude || point.longitude <= maxLongitude
}

data class GpxMetadata(
    val name: String? = null,
    val description: String? = null,
    val author: GpxPerson? = null,
    val copyright: GpxCopyright? = null,
    val links: List<GpxLink> = emptyList(),
    val time: Instant? = null,
    val timeText: String? = null,
    val keywords: String? = null,
    val bounds: GpxBounds? = null,
    val extensions: List<XmlElement> = emptyList(),
)

data class GpxPoint(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double? = null,
    val time: Instant? = null,
    val timeText: String? = null,
    val magneticVariation: Double? = null,
    val geoidHeight: Double? = null,
    val name: String? = null,
    val comment: String? = null,
    val description: String? = null,
    val source: String? = null,
    val links: List<GpxLink> = emptyList(),
    val symbol: String? = null,
    val type: String? = null,
    val fix: String? = null,
    val satellites: Int? = null,
    val hdop: Double? = null,
    val vdop: Double? = null,
    val pdop: Double? = null,
    val ageOfDgpsData: Double? = null,
    val dgpsId: Int? = null,
    val course: Double? = null,
    val speed: Double? = null,
    val extensions: List<XmlElement> = emptyList(),
    val id: String = UUID.randomUUID().toString(),
) {
    val hasSemanticContent: Boolean
        get() = name != null || comment != null || description != null || source != null ||
            links.isNotEmpty() || symbol != null || type != null || extensions.isNotEmpty()
}

data class GpxRoute(
    val name: String? = null,
    val comment: String? = null,
    val description: String? = null,
    val source: String? = null,
    val links: List<GpxLink> = emptyList(),
    val number: Int? = null,
    val type: String? = null,
    val points: List<GpxPoint> = emptyList(),
    val extensions: List<XmlElement> = emptyList(),
    val id: String = UUID.randomUUID().toString(),
)

data class GpxTrackSegment(
    val points: List<GpxPoint> = emptyList(),
    val extensions: List<XmlElement> = emptyList(),
    val id: String = UUID.randomUUID().toString(),
)

data class GpxTrack(
    val name: String? = null,
    val comment: String? = null,
    val description: String? = null,
    val source: String? = null,
    val links: List<GpxLink> = emptyList(),
    val number: Int? = null,
    val type: String? = null,
    val segments: List<GpxTrackSegment> = emptyList(),
    val extensions: List<XmlElement> = emptyList(),
    val id: String = UUID.randomUUID().toString(),
)

data class GpxDocument(
    val version: GpxVersion = GpxVersion.V1_1,
    val creator: String = "VeloGPX",
    val metadata: GpxMetadata? = null,
    val waypoints: List<GpxPoint> = emptyList(),
    val routes: List<GpxRoute> = emptyList(),
    val tracks: List<GpxTrack> = emptyList(),
    val rootExtensions: List<XmlElement> = emptyList(),
    val namespaceDeclarations: Map<String, String> = emptyMap(),
    val sourceName: String? = null,
) {
    // A loaded document is immutable. Cache this frequently displayed aggregate so unrelated UI
    // recompositions and catalog writes do not walk every point again.
    private val cachedPointCount: Lazy<Int> = lazy(LazyThreadSafetyMode.NONE) {
        waypoints.size + routes.sumOf { it.points.size } +
            tracks.sumOf { track -> track.segments.sumOf { it.points.size } }
    }
    val pointCount: Int get() = cachedPointCount.value
    val isEmpty: Boolean get() = waypoints.isEmpty() && routes.isEmpty() && tracks.isEmpty()
}

enum class IssueSeverity { INFO, WARNING, ERROR }

data class GpxIssue(
    val severity: IssueSeverity,
    val code: String,
    val message: String,
    val path: String? = null,
)

data class GpxParseResult(
    val document: GpxDocument?,
    val issues: List<GpxIssue> = emptyList(),
) {
    val isSuccess: Boolean get() = document != null && issues.none { it.severity == IssueSeverity.ERROR }
}

data class TrackStyle(
    val color: Long,
    val visible: Boolean = true,
    val widthDp: Float = 5f,
)
