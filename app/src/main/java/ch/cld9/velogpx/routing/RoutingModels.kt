package ch.cld9.velogpx.routing

import ch.cld9.velogpx.model.GpxPoint
import java.util.UUID

/** A coordinate used by a routing provider, deliberately separate from persisted GPX payload. */
data class RouteCoordinate(
    val latitude: Double,
    val longitude: Double,
) {
    val isValid: Boolean
        get() = latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0

    fun toGpxPoint(elevation: Double? = null): GpxPoint = GpxPoint(
        latitude = latitude,
        longitude = longitude,
        elevation = elevation,
    )

    companion object {
        fun from(point: GpxPoint): RouteCoordinate = RouteCoordinate(point.latitude, point.longitude)
    }
}

enum class RouteAlternative(val index: Int, val label: String) {
    PRIMARY(0, "Primary"),
    ALTERNATIVE_1(1, "Alternative 1"),
    ALTERNATIVE_2(2, "Alternative 2"),
    ALTERNATIVE_3(3, "Alternative 3");

    companion object {
        fun fromIndex(index: Int): RouteAlternative = entries.firstOrNull { it.index == index }
            ?: throw IllegalArgumentException("BRouter alternative index must be between 0 and 3")
    }
}

data class RoutingAnchor(
    val coordinate: RouteCoordinate,
    val id: String = UUID.randomUUID().toString(),
)

data class RoutingRequest(
    val anchors: List<RoutingAnchor>,
    val profile: BicycleProfile,
    val alternative: RouteAlternative = RouteAlternative.PRIMARY,
    val requestId: String = UUID.randomUUID().toString(),
)

data class RoutedPoint(
    val coordinate: RouteCoordinate,
    val elevationMeters: Double? = null,
) {
    fun toGpxPoint(): GpxPoint = coordinate.toGpxPoint(elevationMeters)
}

data class RouteMetrics(
    val distanceMeters: Double? = null,
    val filteredAscentMeters: Double? = null,
    val plainAscentMeters: Double? = null,
    val durationSeconds: Double? = null,
    val energy: Double? = null,
    val cost: Double? = null,
)

data class AnchorSnap(
    val anchorId: String,
    val routePointIndex: Int,
    val distanceMeters: Double,
)

data class RoutedPath(
    val points: List<RoutedPoint>,
    val metrics: RouteMetrics,
    val anchorSnaps: List<AnchorSnap>,
    val alternative: RouteAlternative,
    val providerName: String = "BRouter",
)

sealed interface RoutingFailure {
    val message: String

    data class InvalidRequest(override val message: String) : RoutingFailure
    data class Http(val statusCode: Int, override val message: String) : RoutingFailure
    data class Network(override val message: String) : RoutingFailure
    data class ResponseTooLarge(val maximumBytes: Long) : RoutingFailure {
        override val message: String = "Routing response exceeded $maximumBytes bytes"
    }
    data class MalformedResponse(override val message: String) : RoutingFailure
}

sealed interface RoutingOutcome {
    data class Success(val path: RoutedPath) : RoutingOutcome
    data class Failure(val reason: RoutingFailure) : RoutingOutcome
}

class RoutingException(val failure: RoutingFailure) : IllegalStateException(failure.message)
