package ch.cld9.velogpx.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
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
import org.maplibre.android.style.expressions.Expression
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
private const val TRACK_SOURCE = "velogpx-tracks-source"
private const val TRACK_LAYER = "velogpx-tracks-layer"
private const val SELECTED_TRACK_SOURCE = "velogpx-selected-tracks-source"
private const val SELECTED_TRACK_HALO_LAYER = "velogpx-selected-tracks-halo-layer"
private const val SELECTED_TRACK_LAYER = "velogpx-selected-tracks-layer"
private const val ROUTE_SOURCE = "velogpx-routes-source"
private const val ROUTE_LAYER = "velogpx-routes-layer"
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

private fun documentOverviewPoints(document: GpxDocument): Sequence<ModelPoint> = sequence {
    val bounds = document.metadata?.bounds
    if (bounds != null) {
        yield(ModelPoint(bounds.minLatitude, bounds.minLongitude))
        yield(ModelPoint(bounds.maxLatitude, bounds.maxLongitude))
        return@sequence
    }
    document.waypoints.take(MAX_OVERVIEW_FEATURES).forEach { yield(it) }
    document.routes.take(MAX_OVERVIEW_FEATURES).forEach { route ->
        route.points.firstOrNull()?.let { yield(it) }
        route.points.lastOrNull()?.takeUnless { it === route.points.firstOrNull() }?.let { yield(it) }
    }
    document.tracks.take(MAX_OVERVIEW_FEATURES).forEach { track ->
        track.segments.take(MAX_OVERVIEW_FEATURES).forEach { segment ->
            segment.points.firstOrNull()?.let { yield(it) }
            segment.points.lastOrNull()?.takeUnless { it === segment.points.firstOrNull() }?.let { yield(it) }
        }
    }
}

private const val MAX_OVERVIEW_FEATURES = 10_000

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
    lassoSelectionEnabled: Boolean = false,
    onLassoSelection: (List<String>) -> Unit = {},
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
    val latestLassoSelection = remember { AtomicReference(onLassoSelection) }
    latestLassoSelection.set(onLassoSelection)
    val lassoOverlay = remember {
        LassoOverlayView(context).apply { onComplete = { polygon ->
            mapView.getMapAsync { map ->
                latestLassoSelection.get().invoke(renderer.trackIdsInsideLasso(map, polygon))
            }
        } }
    }
    lassoOverlay.active = lassoSelectionEnabled
    val mapContainer = remember {
        FrameLayout(context).apply {
            addView(mapView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(lassoOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }
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
            mapView.addOnDidFailLoadingMapListener(loadFailure)
            mapView.getMapAsync { map ->
                map.addOnMapClickListener(callback)
                map.addOnCameraIdleListener(cameraIdle)
                map.setStyle(Style.Builder().fromUri(MAP_STYLE)) { style ->
                    renderer.sync(map, style, latestPayload.get())
                    renderer.applyInitialCamera(map, initialCamera, latestPayload.get().document, mapView)
                    latestFocus.get()?.let { renderer.focus(map, it, mapView) }
                }
            }
            mapContainer
        },
        update = {
            lassoOverlay.active = lassoSelectionEnabled
            mapView.getMapAsync { map ->
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
    private val visibleTrackIds = mutableSetOf<String>()
    private var tracksById: Map<String, ch.cld9.velogpx.model.GpxTrack> = emptyMap()
    private var initialCameraApplied = false
    private var lastFocusToken: Long? = null
    private var lastStyle: Style? = null
    private var lastPayload: MapPayload? = null
    private var handleTrack: ch.cld9.velogpx.model.GpxTrack? = null
    private var sampledHandles: List<ModelPoint> = emptyList()

    fun sync(map: MapLibreMap, style: Style, payload: MapPayload) {
        val previous = lastPayload
        val styleChanged = style !== lastStyle
        if (style === lastStyle && previous != null &&
            previous.document === payload.document && previous.styles === payload.styles &&
            previous.selectedTrackIds === payload.selectedTrackIds && previous.selectedPoint === payload.selectedPoint &&
            previous.selectedPosition === payload.selectedPosition && previous.currentLocation === payload.currentLocation &&
            previous.currentLocationProjection === payload.currentLocationProjection &&
            previous.draftLines === payload.draftLines && previous.draftAnchors === payload.draftAnchors
        ) return
        lastStyle = style
        lastPayload = payload
        tracksById = payload.document.tracks.associateBy { it.id }
        visibleTrackIds.clear()
        payload.document.tracks.forEach { track ->
            if ((payload.styles[track.id] ?: TrackStyle(0xFF176B45)).visible) visibleTrackIds += track.id
        }
        val sourcePointCount = payload.document.tracks.sumOf { track -> track.segments.sumOf { it.points.size } } +
            payload.document.routes.sumOf { it.points.size }
        val globalRenderStride = ((sourcePointCount + MAX_TOTAL_RENDER_POINTS - 1) /
            MAX_TOTAL_RENDER_POINTS).coerceAtLeast(1)
        val baseChanged = styleChanged || previous == null || previous.document !== payload.document ||
            previous.styles !== payload.styles
        if (baseChanged) syncTrackBase(style, payload, globalRenderStride)
        if (baseChanged || previous.selectedTrackIds !== payload.selectedTrackIds) {
            syncSelectedTracks(style, payload, globalRenderStride)
        }
        if (styleChanged || previous?.document !== payload.document) {
            syncRoutes(style, payload.document, globalRenderStride)
        }
        syncWaypoints(style, payload.document.waypoints)
        val selectedTrack = visibleSelectedTrack(payload.document, payload.styles, payload.selectedTrackIds)
        syncHandles(style, payload.selectedPoint, selectedTrack)
        syncDraft(style, payload.draftLines, payload.draftAnchors)
        syncPositionMarkers(
            style,
            payload.selectedPosition.takeIf { selectedTrack != null },
            payload.currentLocation,
            payload.currentLocationProjection.takeIf { selectedTrack != null },
        )
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
            fitPoints(map, documentOverviewPoints(document), animate = false, mapView = mapView)
        }
    }

    fun focus(map: MapLibreMap, request: MapFocusRequest, mapView: MapView) {
        if (request.token == lastFocusToken) return
        lastFocusToken = request.token
        fitPoints(map, request.points.asSequence(), animate = true, mapView = mapView, bottomInsetDp = request.bottomInsetDp)
    }

    fun trackIdsAt(map: MapLibreMap, location: LatLng, corridor: Float): List<String> {
        val point = map.projection.toScreenLocation(location)
        val area = android.graphics.RectF(point.x - corridor, point.y - corridor, point.x + corridor, point.y + corridor)
        return map.queryRenderedFeatures(area, TRACK_LAYER, SELECTED_TRACK_LAYER)
            .mapNotNull { feature -> feature.getStringProperty("trackId") }
            .filter { it in visibleTrackIds }
            .distinct()
    }

    fun trackIdsInsideLasso(map: MapLibreMap, polygon: List<ScreenPoint>): List<String> {
        if (polygon.size < 3 || visibleTrackIds.isEmpty()) return emptyList()
        val left = polygon.minOf { it.x }.toFloat()
        val top = polygon.minOf { it.y }.toFloat()
        val right = polygon.maxOf { it.x }.toFloat()
        val bottom = polygon.maxOf { it.y }.toFloat()
        val candidates = map.queryRenderedFeatures(
            android.graphics.RectF(left, top, right, bottom),
            TRACK_LAYER,
        ).mapNotNull { it.getStringProperty("trackId") }.filter { it in visibleTrackIds }.toSet()
        return visibleTrackIds.filter { id ->
            if (id !in candidates) return@filter false
            val track = tracksById[id] ?: return@filter false
            track.segments.any { segment ->
                val line = segment.points.map { point ->
                    val screen = map.projection.toScreenLocation(LatLng(point.latitude, point.longitude))
                    ScreenPoint(screen.x.toDouble(), screen.y.toDouble())
                }
                LassoGeometry.lineIntersectsPolygon(line, polygon)
            }
        }
    }

    private fun syncTrackBase(style: Style, payload: MapPayload, stride: Int) {
        val features = payload.document.tracks.flatMap { track ->
            val trackStyle = payload.styles[track.id] ?: TrackStyle(0xFF176B45)
            track.segments.mapNotNull { segment ->
                segment.points.takeIf { it.size >= 2 }?.let { points ->
                    trackFeature(track.id, points, stride, trackStyle.color, trackStyle.widthDp, if (trackStyle.visible) 0.72f else 0f)
                }
            }
        }
        val collection = FeatureCollection.fromFeatures(features)
        val source = style.getSourceAs<GeoJsonSource>(TRACK_SOURCE)
        if (source == null) style.addSource(GeoJsonSource(TRACK_SOURCE, collection)) else source.setGeoJson(collection)
        style.removeLayer(TRACK_LAYER)
        style.addLayer(LineLayer(TRACK_LAYER, TRACK_SOURCE).withProperties(
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            lineColor(Expression.get("color")), lineWidth(Expression.get("width")), lineOpacity(Expression.get("opacity")),
        ))
    }

    private fun syncSelectedTracks(style: Style, payload: MapPayload, stride: Int) {
        val features = payload.document.tracks.asSequence()
            .filter { it.id in payload.selectedTrackIds }
            .flatMap { track ->
                val trackStyle = payload.styles[track.id] ?: TrackStyle(0xFF176B45)
                if (!trackStyle.visible) emptySequence() else track.segments.asSequence().mapNotNull { segment ->
                    segment.points.takeIf { it.size >= 2 }?.let { points ->
                        trackFeature(track.id, points, stride, trackStyle.color, trackStyle.widthDp + 1f, 1f).apply {
                            addNumberProperty("haloWidth", trackStyle.widthDp + 7f)
                        }
                    }
                }
            }.toList()
        val collection = FeatureCollection.fromFeatures(features)
        val source = style.getSourceAs<GeoJsonSource>(SELECTED_TRACK_SOURCE)
        if (source == null) style.addSource(GeoJsonSource(SELECTED_TRACK_SOURCE, collection)) else source.setGeoJson(collection)
        style.removeLayer(SELECTED_TRACK_HALO_LAYER)
        style.removeLayer(SELECTED_TRACK_LAYER)
        style.addLayer(LineLayer(SELECTED_TRACK_HALO_LAYER, SELECTED_TRACK_SOURCE).withProperties(
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            lineColor("#FFFFFF"), lineWidth(Expression.get("haloWidth")), lineOpacity(0.9f),
        ))
        style.addLayer(LineLayer(SELECTED_TRACK_LAYER, SELECTED_TRACK_SOURCE).withProperties(
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            lineColor(Expression.get("color")), lineWidth(Expression.get("width")), lineOpacity(1f),
        ))
    }

    private fun syncRoutes(style: Style, document: GpxDocument, stride: Int) {
        val features = document.routes.mapNotNull { route ->
            route.points.takeIf { it.size >= 2 }?.let { points ->
                Feature.fromGeometry(LineString.fromLngLats(renderPoints(points, stride).map { Point.fromLngLat(it.longitude, it.latitude) }))
            }
        }
        val collection = FeatureCollection.fromFeatures(features)
        val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)
        if (source == null) style.addSource(GeoJsonSource(ROUTE_SOURCE, collection)) else source.setGeoJson(collection)
        style.removeLayer(ROUTE_LAYER)
        style.addLayer(LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            lineColor("#F9A825"), lineWidth(4f), lineOpacity(0.9f),
        ))
    }

    private fun trackFeature(
        trackId: String,
        points: List<ModelPoint>,
        stride: Int,
        color: Long,
        width: Float,
        opacity: Float,
    ) = Feature.fromGeometry(
        LineString.fromLngLats(renderPoints(points, stride).map { Point.fromLngLat(it.longitude, it.latitude) }),
    ).apply {
        addStringProperty("trackId", trackId)
        addStringProperty("color", colorString(color))
        addNumberProperty("width", width)
        addNumberProperty("opacity", opacity)
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
        points: Sequence<ModelPoint>,
        animate: Boolean,
        mapView: MapView,
        bottomInsetDp: Float = 0f,
    ) {
        val width = mapView.width.takeIf { it > 0 } ?: mapView.resources.displayMetrics.widthPixels
        val height = mapView.height.takeIf { it > 0 } ?: mapView.resources.displayMetrics.heightPixels
        val padding = 72.0 * mapView.resources.displayMetrics.density
        val usableWidth = (width - 2.0 * padding).coerceAtLeast(64.0)
        val bottomInset = bottomInsetDp * mapView.resources.displayMetrics.density
        val usableHeight = (height - 2.0 * padding - bottomInset).coerceAtLeast(64.0)

        val xs = ArrayList<Double>()
        var minimumY = Double.POSITIVE_INFINITY
        var maximumY = Double.NEGATIVE_INFINITY
        points.forEach { point ->
            val raw = (point.longitude + 180.0) / 360.0
            xs += ((raw % 1.0) + 1.0) % 1.0
            val latitude = point.latitude.coerceIn(-85.05112878, 85.05112878)
            val radians = Math.toRadians(latitude)
            val y = (1.0 - ln(tan(radians) + 1.0 / kotlin.math.cos(radians)) / PI) / 2.0
            minimumY = minOf(minimumY, y)
            maximumY = maxOf(maximumY, y)
        }
        if (xs.isEmpty()) return
        xs.sort()
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

    /**
     * Keep the full immutable geometry in the model/export, but bound the native MapLibre payload.
     * Very large imported tracks otherwise allocate one native GeoJSON point per source point and
     * can make opening the project look hung (or exhaust the app heap) before the first frame.
     */
    private fun renderPoints(points: List<ModelPoint>, globalStride: Int): List<ModelPoint> {
        val localStride = ((points.lastIndex + MAX_RENDER_POINTS_PER_SEGMENT - 2) /
            (MAX_RENDER_POINTS_PER_SEGMENT - 1)).coerceAtLeast(1)
        val stride = maxOf(globalStride, localStride)
        if (stride == 1) return points
        return buildList(minOf(points.size, MAX_RENDER_POINTS_PER_SEGMENT)) {
            var index = 0
            while (index < points.lastIndex) {
                add(points[index])
                index += stride
            }
            add(points.last())
        }
    }

    private companion object {
        const val MAX_RENDER_POINTS_PER_SEGMENT = 20_000
        const val MAX_TOTAL_RENDER_POINTS = 80_000
    }
}

internal class LassoOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val points = mutableListOf<PointF>()
    private val lassoPath = Path()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(123, 31, 162)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(45, 123, 31, 162)
        style = Paint.Style.FILL
    }
    var onComplete: (List<ScreenPoint>) -> Unit = {}

    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            visibility = if (value) VISIBLE else GONE
            if (!value) {
                points.clear()
                invalidate()
            }
        }

    init {
        visibility = GONE
        isClickable = true
        contentDescription = "Draw a lasso around tracks"
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (points.size < 2) return
        lassoPath.reset()
        lassoPath.moveTo(points.first().x, points.first().y)
        for (index in 1 until points.size) lassoPath.lineTo(points[index].x, points[index].y)
        if (points.size >= 3) {
            lassoPath.close()
            canvas.drawPath(lassoPath, fill)
        }
        canvas.drawPath(lassoPath, stroke)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!active) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                points.clear()
                points += PointF(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val previous = points.lastOrNull()
                val dx = event.x - (previous?.x ?: event.x)
                val dy = event.y - (previous?.y ?: event.y)
                if (dx * dx + dy * dy >= 4f * density * density) {
                    points += PointF(event.x, event.y)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                points += PointF(event.x, event.y)
                val width = (points.maxOfOrNull { it.x } ?: 0f) - (points.minOfOrNull { it.x } ?: 0f)
                val height = (points.maxOfOrNull { it.y } ?: 0f) - (points.minOfOrNull { it.y } ?: 0f)
                val result = if (points.size >= 3 && width >= 12f * density && height >= 12f * density) {
                    points.map { ScreenPoint(it.x.toDouble(), it.y.toDouble()) }
                } else emptyList()
                points.clear()
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                onComplete(result)
            }
            MotionEvent.ACTION_CANCEL -> {
                points.clear()
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
