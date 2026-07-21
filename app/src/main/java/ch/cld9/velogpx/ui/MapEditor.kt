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
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
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

private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val OFFLINE_STYLE = """{"version":8,"name":"Offline editing","sources":{},"layers":[{"id":"offline-background","type":"background","paint":{"background-color":"#edf2ef"}}]}"""
private const val WAYPOINT_SOURCE = "velogpx-waypoints-source"
private const val WAYPOINT_LAYER = "velogpx-waypoints-layer"
private const val HANDLE_SOURCE = "velogpx-handles-source"
private const val HANDLE_LAYER = "velogpx-handles-layer"

private data class MapPayload(
    val document: GpxDocument,
    val styles: Map<String, TrackStyle>,
    val selectedTrackId: String?,
    val selectedPoint: PointSelection?,
)

@Composable
fun MapEditor(
    document: GpxDocument,
    styles: Map<String, TrackStyle>,
    selectedTrackId: String?,
    selectedPoint: PointSelection?,
    onMapTap: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(Bundle()) } }
    val renderer = remember { MapRenderer() }
    val latestPayload = remember { AtomicReference(MapPayload(document, styles, selectedTrackId, selectedPoint)) }
    latestPayload.set(MapPayload(document, styles, selectedTrackId, selectedPoint))
    val fallbackLoaded = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val callback = remember { MapLibreMap.OnMapClickListener { location -> onMapTap(location.latitude, location.longitude); true } }
    val loadFailure = remember {
        MapView.OnDidFailLoadingMapListener {
            if (fallbackLoaded.compareAndSet(false, true)) {
                mapView.getMapAsync { map ->
                    map.setStyle(Style.Builder().fromJson(OFFLINE_STYLE)) { style ->
                        renderer.sync(map, style, latestPayload.get(), fit = true)
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
                    map.setStyle(Style.Builder().fromUri(MAP_STYLE)) { style ->
                        renderer.sync(map, style, latestPayload.get(), fit = true)
                    }
                }
            }
        },
        update = { view ->
            view.getMapAsync { map ->
                map.style?.let { style -> renderer.sync(map, style, MapPayload(document, styles, selectedTrackId, selectedPoint), fit = false) }
            }
        },
        modifier = modifier,
    )
}

private class MapRenderer {
    private val trackIds = mutableSetOf<String>()
    private var fitted = false

    fun sync(map: MapLibreMap, style: Style, payload: MapPayload, fit: Boolean) {
        val wanted = payload.document.tracks.map { it.id }.toSet() + payload.document.routes.map { "route-${it.id}" }
        (trackIds - wanted).forEach { id ->
            style.removeLayer(layerId(id))
            style.removeSource(sourceId(id))
            trackIds.remove(id)
        }
        payload.document.tracks.forEachIndexed { index, track ->
            val features = track.segments.mapNotNull { segment ->
                segment.points.takeIf { it.size >= 2 }?.let { points ->
                    Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) }))
                }
            }
            val collection = FeatureCollection.fromFeatures(features)
            val source = style.getSourceAs<GeoJsonSource>(sourceId(track.id))
            if (source == null) {
                style.addSource(GeoJsonSource(sourceId(track.id), collection))
                trackIds += track.id
            } else source.setGeoJson(collection)
            val trackStyle = payload.styles[track.id] ?: TrackStyle(0xFF176B45)
            style.removeLayer(layerId(track.id))
            style.addLayer(
                LineLayer(layerId(track.id), sourceId(track.id)).withProperties(
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                    lineColor(colorString(trackStyle.color)),
                    lineWidth(if (track.id == payload.selectedTrackId) trackStyle.widthDp + 2f else trackStyle.widthDp),
                    lineOpacity(if (trackStyle.visible) if (track.id == payload.selectedTrackId) 1f else 0.82f else 0f),
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
        val selectedTrack = payload.document.tracks.firstOrNull { it.id == payload.selectedTrackId }
        syncHandles(style, selectedTrack?.segments?.flatMap { it.points }.orEmpty(), payload.selectedPoint, selectedTrack)

        if ((fit || !fitted) && payload.document.pointCount > 0) {
            val points = payload.document.tracks.flatMap { it.segments }.flatMap { it.points } +
                payload.document.routes.flatMap { it.points } + payload.document.waypoints
            fitPoints(map, points)
            fitted = true
        }
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

    private fun syncHandles(style: Style, points: List<ModelPoint>, selection: PointSelection?, track: ch.cld9.velogpx.model.GpxTrack?) {
        val stride = (points.size / 500).coerceAtLeast(1)
        val features = points.mapIndexedNotNull { index, point ->
            if (index % stride == 0 || selection?.let { selected ->
                    val actual = track?.segments?.getOrNull(selected.segmentIndex)?.points?.getOrNull(selected.pointIndex)
                    actual?.id == point.id
                } == true
            ) Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)).apply {
                addBooleanProperty("selected", selection?.let { selected ->
                    track?.segments?.getOrNull(selected.segmentIndex)?.points?.getOrNull(selected.pointIndex)?.id == point.id
                } == true)
            } else null
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

    private fun fitPoints(map: MapLibreMap, points: List<ModelPoint>) {
        if (points.isEmpty()) return
        if (points.size == 1) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(points[0].latitude, points[0].longitude), 13.0))
            return
        }
        runCatching {
            val builder = LatLngBounds.Builder()
            points.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 96))
        }
    }

    private fun sourceId(id: String) = "velogpx-source-$id"
    private fun layerId(id: String) = "velogpx-layer-$id"
    private fun colorString(argb: Long) = String.format("#%06X", argb and 0xFFFFFF)
}
