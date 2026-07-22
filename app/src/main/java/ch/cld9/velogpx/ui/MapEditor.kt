package ch.cld9.velogpx.ui

import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ch.cld9.velogpx.model.GpxDocument
import ch.cld9.velogpx.model.GpxPoint as ModelPoint
import ch.cld9.velogpx.model.TrackStyle
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val OFFLINE_STYLE = """{"version":8,"name":"Offline editing","sources":{},"layers":[{"id":"offline-background","type":"background","paint":{"background-color":"#edf2ef"}}]}"""
private const val WAYPOINT_SOURCE = "velogpx-waypoints-source"
private const val WAYPOINT_LAYER = "velogpx-waypoints-layer"
private const val HANDLE_SOURCE = "velogpx-handles-source"
private const val HANDLE_LAYER = "velogpx-handles-layer"
private const val DRAFT_SOURCE = "velogpx-draft-source"
private const val DRAFT_LAYER = "velogpx-draft-layer"
private const val DRAFT_ANCHOR_SOURCE = "velogpx-draft-anchors-source"
private const val DRAFT_ANCHOR_LAYER = "velogpx-draft-anchors-layer"
private const val CURSOR_SOURCE = "velogpx-cursor-source"
private const val CURSOR_HALO_LAYER = "velogpx-cursor-halo-layer"
private const val CURSOR_LAYER = "velogpx-cursor-layer"
private const val LOCATION_SOURCE = "velogpx-location-source"
private const val LOCATION_LAYER = "velogpx-location-layer"
private const val LOCATION_LINK_SOURCE = "velogpx-location-link-source"
private const val LOCATION_LINK_LAYER = "velogpx-location-link-layer"
private const val LOCATION_PROJECTION_SOURCE = "velogpx-location-projection-source"
private const val LOCATION_PROJECTION_LAYER = "velogpx-location-projection-layer"

data class MapCameraState(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val bearing: Double = 0.0,
    val tilt: Double = 0.0,
)

data class MapFocusRequest(
    val token: Long,
    val points: List<ModelPoint>,
    val bottomInsetDp: Float = 0f,
)

data class MapDraftLine(
    val points: List<ModelPoint>,
    val color: Long = 0xFF7B1FA2,
    val selected: Boolean = false,
)

private data class MapPayload(
    val document: GpxDocument,
    val styles: Map<String, TrackStyle>,
    val selectedTrackIds: Set<String>,
    val selectedPoint: PointSelection?,
    val selectedPosition: ModelPoint?,
    val currentLocation: ModelPoint?,
    val currentLocationProjection: ModelPoint?,
    val draftLines: List<MapDraftLine>,
    val draftAnchors: List<ModelPoint>,
)

@Composable
fun MapEditor(
    document: GpxDocument,
    styles: Map<String, TrackStyle>,
    selectedTrackIds: Set<String>,
    selectedPoint: PointSelection?,
    onMapTap: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
    selectedPosition: ModelPoint? = null,
    currentLocation: ModelPoint? = null,
    currentLocationProjection: ModelPoint? = null,
    onTracksTap: (List<String>, Double, Double) -> Unit = { _, latitude, longitude -> onMapTap(latitude, longitude) },
    trackPickingEnabled: Boolean = false,
    focusRequest: MapFocusRequest? = null,
    initialCamera: MapCameraState? = null,
    onCameraIdle: (MapCameraState) -> Unit = {},
    draftLines: List<MapDraftLine> = emptyList(),
    draftAnchors: List<ModelPoint> = emptyList(),
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(Bundle()) } }
    val renderer = remember { MapRenderer() }
    val latestPayload = remember {
        AtomicReference(MapPayload(document, styles, selectedTrackIds, selectedPoint, selectedPosition, currentLocation, currentLocationProjection, draftLines, draftAnchors))
    }
    latestPayload.set(MapPayload(document, styles, selectedTrackIds, selectedPoint, selectedPosition, currentLocation, currentLocationProjection, draftLines, draftAnchors))
    val latestFocus = remember { AtomicReference(focusRequest) }
    latestFocus.set(focusRequest)
    val latestCameraCallback = remember { AtomicReference(onCameraIdle) }
    latestCameraCallback.set(onCameraIdle)
    val latestTap = remember { AtomicReference(Triple(trackPickingEnabled, onMapTap, onTracksTap)) }
    latestTap.set(Triple(trackPickingEnabled, onMapTap, onTracksTap))
    val fallbackLoaded = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val callback = remember {
        MapLibreMap.OnMapClickListener { location ->
            mapView.getMapAsync { map ->
                val tap = latestTap.get()
                val ids = if (tap.first) {
                    renderer.trackIdsAt(map, location, 24f * mapView.resources.displayMetrics.density)
                } else emptyList()
                if (ids.isNotEmpty()) tap.third(ids, location.latitude, location.longitude)
                else tap.second(location.latitude, location.longitude)
            }
            true
        }
    }
    val cameraIdle = remember {
        MapLibreMap.OnCameraIdleListener {
            mapView.getMapAsync { map ->
                val camera = map.cameraPosition
                val target = camera.target ?: return@getMapAsync
                latestCameraCallback.get().invoke(
                    MapCameraState(target.latitude, target.longitude, camera.zoom, camera.bearing, camera.tilt),
                )
            }
        }
    }
    val loadFailure = remember {
        MapView.OnDidFailLoadingMapListener {
            if (fallbackLoaded.compareAndSet(false, true)) {
                mapView.getMapAsync { map ->
                    map.setStyle(Style.Builder().fromJson(OFFLINE_STYLE)) { style ->
                        renderer.sync(map, style, latestPayload.get())
                        renderer.applyInitialCamera(map, initialCamera, latestPayload.get().document, mapView)
                        latestFocus.get()?.let { renderer.focus(map, it, mapView) }
                    }
                }
            }
        }
    }

    DisposableEffect(lifecycle, mapView) {
        var started = false
        var resumed = false
        fun start() { if (!started) { mapView.onStart(); started = true } }
        fun resume() { if (!resumed) { start(); mapView.onResume(); resumed = true } }
        fun pause() { if (resumed) { mapView.onPause(); resumed = false } }
        fun stop() { if (started) { pause(); mapView.onStop(); started = false } }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> pause()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.getMapAsync { it.removeOnMapClickListener(callback) }
            mapView.getMapAsync { it.removeOnCameraIdleListener(cameraIdle) }
            mapView.removeOnDidFailLoadingMapListener(loadFailure)
            stop()
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = {
            mapView.apply {
                addOnDidFailLoadingMapListener(loadFailure)
                getMapAsync { map ->
                    map.addOnMapClickListener(callback)
                    map.addOnCameraIdleListener(cameraIdle)
                    map.setStyle(Style.Builder().fromUri(MAP_STYLE)) { style ->
                        renderer.sync(map, style, latestPayload.get())
                        renderer.applyInitialCamera(map, initialCamera, latestPayload.get().document, mapView)
                        latestFocus.get()?.let { renderer.focus(map, it, mapView) }
                    }
                }
            }
        },
        update = { view ->
            view.getMapAsync { map ->
                map.style?.let { style ->
                    renderer.sync(map, style, MapPayload(document, styles, selectedTrackIds, selectedPoint, selectedPosition, currentLocation, currentLocationProjection, draftLines, draftAnchors))
                    focusRequest?.let { renderer.focus(map, it, mapView) }
                }
            }
        },
        modifier = modifier,
    )
}

private class MapRenderer {
    private val trackIds = mutableSetOf<String>()
    private val visibleTrackIds = mutableSetOf<String>()
    private val trackGeometry = mutableMapOf<String, Pair<ch.cld9.velogpx.model.GpxTrack, FeatureCollection>>()
    private var initialCameraApplied = false
    private var lastFocusToken: Long? = null
    private var lastStyle: Style? = null
    private var lastPayload: MapPayload? = null
    private var handleTrack: ch.cld9.velogpx.model.GpxTrack? = null
    private var sampledHandles: List<ModelPoint> = emptyList()

    fun sync(map: MapLibreMap, style: Style, payload: MapPayload) {
        val previous = lastPayload
        if (style === lastStyle && previous != null &&
            previous.document === payload.document && previous.styles === payload.styles &&
            previous.selectedTrackIds === payload.selectedTrackIds && previous.selectedPoint === payload.selectedPoint &&
            previous.selectedPosition === payload.selectedPosition && previous.currentLocation === payload.currentLocation &&
            previous.currentLocationProjection === payload.currentLocationProjection &&
            previous.draftLines === payload.draftLines && previous.draftAnchors === payload.draftAnchors
        ) return
        lastStyle = style
        lastPayload = payload
        visibleTrackIds.clear()
        val wanted = payload.document.tracks.map { it.id }.toSet() + payload.document.routes.map { "route-${it.id}" }
        (trackIds - wanted).forEach { id ->
            style.removeLayer(haloLayerId(id))
            style.removeLayer(layerId(id))
            style.removeSource(sourceId(id))
            trackIds.remove(id)
            trackGeometry.remove(id)
        }
        payload.document.tracks.forEachIndexed { index, track ->
            val cached = trackGeometry[track.id]
            val geometryChanged = cached?.first !== track
            val collection = if (geometryChanged) {
                FeatureCollection.fromFeatures(track.segments.mapNotNull { segment ->
                    segment.points.takeIf { it.size >= 2 }?.let { points ->
                        Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })).apply {
                            addStringProperty("trackId", track.id)
                        }
                    }
                }).also { trackGeometry[track.id] = track to it }
            } else cached.second
            val source = style.getSourceAs<GeoJsonSource>(sourceId(track.id))
            if (source == null) {
                style.addSource(GeoJsonSource(sourceId(track.id), collection))
                trackIds += track.id
            } else if (geometryChanged) source.setGeoJson(collection)
            val trackStyle = payload.styles[track.id] ?: TrackStyle(0xFF176B45)
            if (trackStyle.visible) visibleTrackIds += track.id
            style.removeLayer(haloLayerId(track.id))
            style.removeLayer(layerId(track.id))
            if (track.id in payload.selectedTrackIds && trackStyle.visible) {
                style.addLayer(
                    LineLayer(haloLayerId(track.id), sourceId(track.id)).withProperties(
                        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                        lineColor("#FFFFFF"), lineWidth(trackStyle.widthDp + 7f), lineOpacity(0.9f),
                    ),
                )
            }
            style.addLayer(
                LineLayer(layerId(track.id), sourceId(track.id)).withProperties(
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                    lineColor(colorString(trackStyle.color)),
                    lineWidth(if (track.id in payload.selectedTrackIds) trackStyle.widthDp + 1f else trackStyle.widthDp),
                    lineOpacity(if (trackStyle.visible) if (track.id in payload.selectedTrackIds) 1f else 0.72f else 0f),
                ),
            )
        }
        payload.document.routes.forEach { route ->
            val key = "route-${route.id}"
            val feature = route.points.takeIf { it.size >= 2 }?.let { points ->
                FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })))
            } ?: FeatureCollection.fromFeatures(emptyList())
            val source = style.getSourceAs<GeoJsonSource>(sourceId(key))
            if (source == null) { style.addSource(GeoJsonSource(sourceId(key), feature)); trackIds += key } else source.setGeoJson(feature)
            style.removeLayer(layerId(key))
            style.addLayer(LineLayer(layerId(key), sourceId(key)).withProperties(
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND), lineColor("#F9A825"), lineWidth(4f), lineOpacity(0.9f),
            ))
        }
        syncWaypoints(style, payload.document.waypoints)
        val selectedTrack = payload.document.tracks.firstOrNull { it.id == payload.selectedTrackIds.firstOrNull() }
        syncHandles(style, payload.selectedPoint, selectedTrack)
        syncDraft(style, payload.draftLines, payload.draftAnchors)
        syncPositionMarkers(style, payload.selectedPosition, payload.currentLocation, payload.currentLocationProjection)
    }

    fun applyInitialCamera(map: MapLibreMap, camera: MapCameraState?, document: GpxDocument, mapView: MapView) {
        if (initialCameraApplied) return
        initialCameraApplied = true
        if (camera != null) {
            map.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(camera.latitude, camera.longitude))
                        .zoom(camera.zoom).bearing(camera.bearing).tilt(camera.tilt).build(),
                ),
            )
        } else {
            val points = document.tracks.flatMap { it.segments }.flatMap { it.points } +
                document.routes.flatMap { it.points } + document.waypoints
            fitPoints(map, points, animate = false, mapView = mapView)
        }
    }

    fun focus(map: MapLibreMap, request: MapFocusRequest, mapView: MapView) {
        if (request.token == lastFocusToken) return
        lastFocusToken = request.token
        fitPoints(map, request.points, animate = true, mapView = mapView, bottomInsetDp = request.bottomInsetDp)
    }

    fun trackIdsAt(map: MapLibreMap, location: LatLng, corridor: Float): List<String> {
        val point = map.projection.toScreenLocation(location)
        val area = android.graphics.RectF(point.x - corridor, point.y - corridor, point.x + corridor, point.y + corridor)
        return map.queryRenderedFeatures(area, *visibleTrackIds.map(::layerId).toTypedArray())
            .mapNotNull { feature -> feature.getStringProperty("trackId") }
            .distinct()
    }

    private fun syncDraft(style: Style, lines: List<MapDraftLine>, anchors: List<ModelPoint>) {
        val lineFeatures = lines.mapNotNull { line ->
            line.points.takeIf { it.size >= 2 }?.let { points ->
                Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })).apply {
                    addStringProperty("color", colorString(line.color))
                    addBooleanProperty("selected", line.selected)
                }
            }
        }
        val lineCollection = FeatureCollection.fromFeatures(lineFeatures)
        val lineSource = style.getSourceAs<GeoJsonSource>(DRAFT_SOURCE)
        if (lineSource == null) style.addSource(GeoJsonSource(DRAFT_SOURCE, lineCollection)) else lineSource.setGeoJson(lineCollection)
        style.removeLayer(DRAFT_LAYER)
        style.addLayer(LineLayer(DRAFT_LAYER, DRAFT_SOURCE).withProperties(
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            lineColor(org.maplibre.android.style.expressions.Expression.get("color")),
            lineWidth(6f),
            lineOpacity(0.92f),
        ))
        val anchorCollection = FeatureCollection.fromFeatures(anchors.map { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) })
        val anchorSource = style.getSourceAs<GeoJsonSource>(DRAFT_ANCHOR_SOURCE)
        if (anchorSource == null) style.addSource(GeoJsonSource(DRAFT_ANCHOR_SOURCE, anchorCollection)) else anchorSource.setGeoJson(anchorCollection)
        style.removeLayer(DRAFT_ANCHOR_LAYER)
        style.addLayer(CircleLayer(DRAFT_ANCHOR_LAYER, DRAFT_ANCHOR_SOURCE).withProperties(
            circleColor("#7B1FA2"), circleRadius(8f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f),
        ))
    }

    private fun syncWaypoints(style: Style, points: List<ModelPoint>) {
        val collection = FeatureCollection.fromFeatures(points.map { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) })
        val source = style.getSourceAs<GeoJsonSource>(WAYPOINT_SOURCE)
        if (source == null) {
            style.addSource(GeoJsonSource(WAYPOINT_SOURCE, collection))
        } else source.setGeoJson(collection)
        style.removeLayer(WAYPOINT_LAYER)
        style.addLayer(CircleLayer(WAYPOINT_LAYER, WAYPOINT_SOURCE).withProperties(
            circleColor("#F9A825"), circleRadius(7f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
        ))
    }

    private fun syncHandles(
        style: Style,
        selection: PointSelection?,
        track: ch.cld9.velogpx.model.GpxTrack?,
    ) {
        if (track !== handleTrack) {
            handleTrack = track
            val points = track?.segments?.flatMap { it.points }.orEmpty()
            val stride = (points.size / 500).coerceAtLeast(1)
            sampledHandles = points.filterIndexed { index, _ -> index % stride == 0 }
        }
        val selected = selection?.let { value ->
            track?.segments?.getOrNull(value.segmentIndex)?.points?.getOrNull(value.pointIndex)
        }
        val points = if (selected != null && sampledHandles.none { it.id == selected.id }) sampledHandles + selected else sampledHandles
        val features = points.map { point ->
            Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)).apply {
                addBooleanProperty("selected", selected?.id == point.id)
            }
        }
        val collection = FeatureCollection.fromFeatures(features)
        val source = style.getSourceAs<GeoJsonSource>(HANDLE_SOURCE)
        if (source == null) {
            style.addSource(GeoJsonSource(HANDLE_SOURCE, collection))
        } else source.setGeoJson(collection)
        style.removeLayer(HANDLE_LAYER)
        style.addLayer(CircleLayer(HANDLE_LAYER, HANDLE_SOURCE).withProperties(
            circleColor("#FFFFFF"), circleRadius(3.5f), circleStrokeColor("#176B45"), circleStrokeWidth(1.5f),
        ))
    }

    private fun syncPositionMarkers(
        style: Style,
        cursor: ModelPoint?,
        location: ModelPoint?,
        projection: ModelPoint?,
    ) {
        val cursorCollection = FeatureCollection.fromFeatures(
            cursor?.let { listOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))) }.orEmpty(),
        )
        val cursorSource = style.getSourceAs<GeoJsonSource>(CURSOR_SOURCE)
        if (cursorSource == null) style.addSource(GeoJsonSource(CURSOR_SOURCE, cursorCollection)) else cursorSource.setGeoJson(cursorCollection)
        style.removeLayer(CURSOR_HALO_LAYER)
        style.removeLayer(CURSOR_LAYER)
        style.addLayer(CircleLayer(CURSOR_HALO_LAYER, CURSOR_SOURCE).withProperties(
            circleColor("#FFFFFF"), circleRadius(11f), circleStrokeColor("#1B1B1F"), circleStrokeWidth(1.5f),
        ))
        style.addLayer(CircleLayer(CURSOR_LAYER, CURSOR_SOURCE).withProperties(
            circleColor("#C2185B"), circleRadius(6.5f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(1.5f),
        ))

        val locationCollection = FeatureCollection.fromFeatures(
            location?.let { listOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))) }.orEmpty(),
        )
        val locationSource = style.getSourceAs<GeoJsonSource>(LOCATION_SOURCE)
        if (locationSource == null) style.addSource(GeoJsonSource(LOCATION_SOURCE, locationCollection)) else locationSource.setGeoJson(locationCollection)
        style.removeLayer(LOCATION_LAYER)
        style.addLayer(CircleLayer(LOCATION_LAYER, LOCATION_SOURCE).withProperties(
            circleColor("#1565C0"), circleRadius(8f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f),
        ))

        val projectionCollection = FeatureCollection.fromFeatures(
            projection?.let { listOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))) }.orEmpty(),
        )
        val projectionSource = style.getSourceAs<GeoJsonSource>(LOCATION_PROJECTION_SOURCE)
        if (projectionSource == null) style.addSource(GeoJsonSource(LOCATION_PROJECTION_SOURCE, projectionCollection)) else projectionSource.setGeoJson(projectionCollection)
        style.removeLayer(LOCATION_PROJECTION_LAYER)
        style.addLayer(CircleLayer(LOCATION_PROJECTION_LAYER, LOCATION_PROJECTION_SOURCE).withProperties(
            circleColor("#00ACC1"), circleRadius(5f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
        ))

        val link = if (location != null && projection != null) {
            FeatureCollection.fromFeature(
                Feature.fromGeometry(LineString.fromLngLats(listOf(
                    Point.fromLngLat(location.longitude, location.latitude),
                    Point.fromLngLat(projection.longitude, projection.latitude),
                ))),
            )
        } else FeatureCollection.fromFeatures(emptyList())
        val linkSource = style.getSourceAs<GeoJsonSource>(LOCATION_LINK_SOURCE)
        if (linkSource == null) style.addSource(GeoJsonSource(LOCATION_LINK_SOURCE, link)) else linkSource.setGeoJson(link)
        style.removeLayer(LOCATION_LINK_LAYER)
        style.addLayer(LineLayer(LOCATION_LINK_LAYER, LOCATION_LINK_SOURCE).withProperties(
            lineColor("#1565C0"), lineWidth(2f), lineOpacity(0.65f),
        ))
    }

    private fun fitPoints(
        map: MapLibreMap,
        points: List<ModelPoint>,
        animate: Boolean,
        mapView: MapView,
        bottomInsetDp: Float = 0f,
    ) {
        if (points.isEmpty()) return
        val width = mapView.width.takeIf { it > 0 } ?: mapView.resources.displayMetrics.widthPixels
        val height = mapView.height.takeIf { it > 0 } ?: mapView.resources.displayMetrics.heightPixels
        val padding = 72.0 * mapView.resources.displayMetrics.density
        val usableWidth = (width - 2.0 * padding).coerceAtLeast(64.0)
        val bottomInset = bottomInsetDp * mapView.resources.displayMetrics.density
        val usableHeight = (height - 2.0 * padding - bottomInset).coerceAtLeast(64.0)

        val xs = points.map { point ->
            val raw = (point.longitude + 180.0) / 360.0
            ((raw % 1.0) + 1.0) % 1.0
        }.sorted()
        var largestGap = -1.0
        var intervalStart = xs.first()
        xs.indices.forEach { index ->
            val next = if (index == xs.lastIndex) xs.first() + 1.0 else xs[index + 1]
            val gap = next - xs[index]
            if (gap > largestGap) {
                largestGap = gap
                intervalStart = next % 1.0
            }
        }
        val spanX = (1.0 - largestGap).coerceAtLeast(0.0)
        val centerX = (intervalStart + spanX / 2.0) % 1.0

        val ys = points.map { point ->
            val latitude = point.latitude.coerceIn(-85.05112878, 85.05112878)
            val radians = Math.toRadians(latitude)
            (1.0 - ln(tan(radians) + 1.0 / kotlin.math.cos(radians)) / PI) / 2.0
        }
        val minimumY = ys.min()
        val maximumY = ys.max()
        val spanY = maximumY - minimumY
        val zoomX = if (spanX < 1e-12) 18.0 else log2(usableWidth / (256.0 * spanX))
        val zoomY = if (spanY < 1e-12) 18.0 else log2(usableHeight / (256.0 * spanY))
        val zoom = minOf(zoomX, zoomY).coerceIn(1.0, 18.0)
        val dataCenterY = (minimumY + maximumY) / 2.0
        val worldSize = 256.0 * 2.0.pow(zoom)
        val centerY = (dataCenterY + bottomInset / 2.0 / worldSize).coerceIn(0.0, 1.0)
        val latitude = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * centerY))))
        val longitude = centerX * 360.0 - 180.0
        val update = CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), zoom)
        if (animate) map.animateCamera(update) else map.moveCamera(update)
    }

    private fun sourceId(id: String) = "velogpx-source-$id"
    private fun layerId(id: String) = "velogpx-layer-$id"
    private fun haloLayerId(id: String) = "velogpx-halo-$id"
    private fun colorString(argb: Long) = String.format("#%06X", argb and 0xFFFFFF)
}
