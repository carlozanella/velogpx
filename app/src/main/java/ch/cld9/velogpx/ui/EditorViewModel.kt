package ch.cld9.velogpx.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.cld9.velogpx.data.ProjectStore
import ch.cld9.velogpx.engine.GeoMath
import ch.cld9.velogpx.engine.GpxAnalytics
import ch.cld9.velogpx.engine.GpxOperations
import ch.cld9.velogpx.engine.ReverseTimePolicy
import ch.cld9.velogpx.engine.StagePlanner
import ch.cld9.velogpx.engine.TrackStatistics
import ch.cld9.velogpx.io.GpxParser
import ch.cld9.velogpx.io.GpxWriter
import ch.cld9.velogpx.model.GpxDocument
import ch.cld9.velogpx.model.GpxMetadata
import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import ch.cld9.velogpx.model.GpxVersion
import ch.cld9.velogpx.model.TrackStyle
import ch.cld9.velogpx.routing.BRouterClient
import ch.cld9.velogpx.routing.BicycleProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class EditMode { SELECT, DRAW_STRAIGHT, DRAW_ROUTED, MOVE, SPLIT, WAYPOINT }
enum class EditorPanel { MAP, PROFILE, LAYERS }

data class PointSelection(
    val trackId: String,
    val segmentIndex: Int,
    val pointIndex: Int,
)

data class EditorUiState(
    val document: GpxDocument = GpxDocument(metadata = GpxMetadata(name = "Untitled bicycle tour")),
    val styles: Map<String, TrackStyle> = emptyMap(),
    val selectedTrackId: String? = null,
    val selectedPoint: PointSelection? = null,
    val editMode: EditMode = EditMode.SELECT,
    val panel: EditorPanel = EditorPanel.MAP,
    val bicycleProfile: BicycleProfile = BicycleProfile.TOURING,
    val undoAvailable: Boolean = false,
    val redoAvailable: Boolean = false,
    val busy: Boolean = false,
    val dirty: Boolean = false,
    val message: String? = null,
    val recoveredAutosave: Boolean = false,
) {
    val selectedTrack: GpxTrack? get() = document.tracks.firstOrNull { it.id == selectedTrackId }
    val selectedStatistics: TrackStatistics? get() = selectedTrack?.let(GpxAnalytics::statistics)
}

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ProjectStore(application)
    private val router = BRouterClient()
    private val undo = ArrayDeque<GpxDocument>()
    private val redo = ArrayDeque<GpxDocument>()
    private val parser = GpxParser()
    private val writer = GpxWriter()
    private val autosaveMutex = Mutex()
    private val autosaveGeneration = AtomicLong()

    private val initialDocument = runCatching { store.loadAutosave() }.getOrNull()
    private val _state = MutableStateFlow(
        EditorUiState(
            document = initialDocument ?: GpxDocument(metadata = GpxMetadata(name = "Untitled bicycle tour")),
            styles = stylesFor(initialDocument?.tracks.orEmpty()),
            selectedTrackId = initialDocument?.tracks?.firstOrNull()?.id,
            recoveredAutosave = initialDocument != null,
        ),
    )
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    fun consumeMessage() = _state.update { it.copy(message = null, recoveredAutosave = false) }
    fun setMode(mode: EditMode) = _state.update { it.copy(editMode = mode, message = null) }
    fun setPanel(panel: EditorPanel) = _state.update { it.copy(panel = panel) }
    fun setProfile(profile: BicycleProfile) = _state.update { it.copy(bicycleProfile = profile) }

    fun selectTrack(id: String) = _state.update {
        it.copy(selectedTrackId = id, selectedPoint = null, panel = EditorPanel.MAP)
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.update { it.copy(busy = true, message = "Importing ${uris.size} file${if (uris.size == 1) "" else "s"}…") }
        viewModelScope.launch {
            val results = runCatching {
                withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "Imported GPX"
                        getApplication<Application>().contentResolver.openInputStream(uri)?.use { parser.parse(it, name) }
                            ?: error("Could not open $name")
                    }
                }
            }.getOrElse { error ->
                _state.update { it.copy(busy = false, message = "Import failed: ${error.message ?: "the file could not be opened"}") }
                return@launch
            }
            val documents = results.mapNotNull { it.document }
            val errors = results.flatMap { it.issues }.filter { it.severity.name != "INFO" }
            if (documents.isEmpty()) {
                _state.update { it.copy(busy = false, message = errors.firstOrNull()?.message ?: "No valid GPX data was found.") }
                return@launch
            }
            val current = _state.value.document
            val base = if (current.isEmpty) documents.first() else current
            val rest = if (current.isEmpty) documents.drop(1) else documents
            val merged = GpxOperations.mergeDocuments(base, rest)
            commit(
                merged.value,
                message = buildString {
                    append("Imported ${documents.size} file${if (documents.size == 1) "" else "s"}")
                    val warningCount = errors.size + merged.warnings.size
                    if (warningCount > 0) append(" with $warningCount warning${if (warningCount == 1) "" else "s"}")
                    append('.')
                },
            )
            _state.update { it.copy(busy = false) }
        }
    }

    fun export(uri: Uri, version: GpxVersion = _state.value.document.version) {
        exportDocument(uri, _state.value.document, version, clearsDirty = true)
    }

    fun exportSelected(uri: Uri, version: GpxVersion = _state.value.document.version) {
        val track = _state.value.selectedTrack ?: return
        exportDocument(
            uri,
            _state.value.document.copy(
                metadata = _state.value.document.metadata?.copy(name = track.name ?: "Selected track"),
                waypoints = waypointsNear(track, _state.value.document.waypoints),
                routes = emptyList(),
                tracks = listOf(track),
            ),
            version,
            clearsDirty = false,
        )
    }

    private fun exportDocument(uri: Uri, document: GpxDocument, version: GpxVersion, clearsDirty: Boolean) {
        _state.update { it.copy(busy = true, message = "Exporting GPX…") }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use {
                        writer.write(document, it, version)
                    }
                }
            }.onSuccess {
                _state.update {
                    it.copy(
                        busy = false,
                        dirty = if (clearsDirty && it.document == document) false else it.dirty,
                        message = "GPX exported successfully.",
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(busy = false, message = "Export failed: ${error.message}") }
            }
        }
    }

    fun exportTrackBundle(uri: Uri, version: GpxVersion = _state.value.document.version) {
        val snapshot = _state.value.document
        _state.update { it.copy(busy = true, message = "Exporting ${snapshot.tracks.size} track files…") }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { output ->
                        ZipOutputStream(output.buffered()).use { zip ->
                            snapshot.tracks.forEachIndexed { index, track ->
                                val base = (track.name ?: "Track-${index + 1}")
                                    .replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "Track-${index + 1}" }
                                zip.putNextEntry(ZipEntry("${(index + 1).toString().padStart(2, '0')}-$base.gpx"))
                                writer.write(
                                    snapshot.copy(
                                        metadata = snapshot.metadata?.copy(name = track.name ?: base),
                                        waypoints = waypointsNear(track, snapshot.waypoints),
                                        routes = emptyList(), tracks = listOf(track),
                                    ),
                                    zip,
                                    version,
                                )
                                zip.closeEntry()
                            }
                        }
                    }
                }
            }.onSuccess {
                _state.update { it.copy(busy = false, message = "Track bundle exported successfully.") }
            }.onFailure { error ->
                _state.update { it.copy(busy = false, message = "Bundle export failed: ${error.message}") }
            }
        }
    }

    fun newProject() {
        undo.clear(); redo.clear()
        val generation = autosaveGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            autosaveMutex.withLock {
                if (generation == autosaveGeneration.get()) store.clear()
            }
        }
        _state.value = EditorUiState(document = GpxDocument(metadata = GpxMetadata(name = "Untitled bicycle tour")))
    }

    fun undo() {
        val previous = undo.removeLastOrNull() ?: return
        redo.addLast(_state.value.document)
        replaceWithoutHistory(previous, "Undid last edit.")
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(_state.value.document)
        replaceWithoutHistory(next, "Redid edit.")
    }

    fun onMapTap(latitude: Double, longitude: Double) {
        val point = GpxPoint(latitude, longitude)
        when (_state.value.editMode) {
            EditMode.DRAW_STRAIGHT -> appendPoint(point, routed = false)
            EditMode.DRAW_ROUTED -> appendPoint(point, routed = true)
            EditMode.WAYPOINT -> addWaypoint(point)
            EditMode.MOVE -> moveSelectedPoint(point)
            EditMode.SPLIT -> splitAtMapPoint(point)
            EditMode.SELECT -> selectNearestPoint(point)
        }
    }

    private fun appendPoint(point: GpxPoint, routed: Boolean) {
        val state = _state.value
        var track = state.selectedTrack
        if (track == null) {
            track = GpxTrack(name = "New bicycle route", segments = listOf(GpxTrackSegment(listOf(point))))
            commit(state.document.copy(tracks = state.document.tracks + track), selectedTrackId = track.id, message = "Started a new route.")
            return
        }
        val segments = if (track.segments.isEmpty()) listOf(GpxTrackSegment()) else track.segments
        val lastSegment = segments.last()
        val lastPoint = lastSegment.points.lastOrNull()
        if (!routed || lastPoint == null) {
            replaceTrack(track.copy(segments = segments.dropLast(1) + lastSegment.copy(points = lastSegment.points + point)), "Point added.")
            return
        }
        _state.update { it.copy(busy = true, message = "Routing ${state.bicycleProfile.label.lowercase()} segment…") }
        viewModelScope.launch {
            runCatching { router.route(listOf(lastPoint, point), state.bicycleProfile) }
                .onSuccess { routedPoints ->
                    if (_state.value.document.tracks.firstOrNull { it.id == track.id } != track) {
                        _state.update { it.copy(busy = false, message = "The track changed while routing; the stale result was discarded. Tap again to retry.") }
                        return@onSuccess
                    }
                    val addition = routedPoints.dropWhile { GeoMath.distanceMeters(it, lastPoint) < 0.2 }
                    val updated = track.copy(segments = segments.dropLast(1) + lastSegment.copy(points = lastSegment.points + addition))
                    replaceTrack(updated, "Bicycle segment added (${addition.size} points).")
                    _state.update { it.copy(busy = false) }
                }
                .onFailure { error ->
                    _state.update { it.copy(busy = false, message = "Routing failed: ${error.message}. Tap straight-line mode to continue offline.") }
                }
        }
    }

    private fun addWaypoint(point: GpxPoint) {
        val numbered = point.copy(name = "POI ${_state.value.document.waypoints.size + 1}", symbol = "Flag")
        commit(_state.value.document.copy(waypoints = _state.value.document.waypoints + numbered), message = "Point of interest added.")
    }

    fun deleteWaypoint(id: String) {
        val remaining = _state.value.document.waypoints.filterNot { it.id == id }
        if (remaining.size == _state.value.document.waypoints.size) return
        commit(_state.value.document.copy(waypoints = remaining), message = "Point of interest deleted.")
    }

    private fun waypointsNear(track: GpxTrack, waypoints: List<GpxPoint>, maximumDistanceMeters: Double = 10_000.0): List<GpxPoint> {
        val segments = track.segments.filter { it.points.isNotEmpty() }
        if (segments.isEmpty()) return emptyList()
        return waypoints.filter { waypoint ->
            segments.any { segment ->
                when (segment.points.size) {
                    1 -> GeoMath.distanceMeters(waypoint, segment.points.single()) <= maximumDistanceMeters
                    else -> segment.points.zipWithNext().any { (start, end) ->
                        GeoMath.distanceToSegmentMeters(waypoint, start, end) <= maximumDistanceMeters
                    }
                }
            }
        }
    }

    private fun selectNearestPoint(query: GpxPoint) {
        var nearest: Pair<PointSelection, Double>? = null
        _state.value.document.tracks.forEach { track ->
            track.segments.forEachIndexed { segmentIndex, segment ->
                segment.points.forEachIndexed { pointIndex, point ->
                    val distance = GeoMath.distanceMeters(query, point)
                    if (nearest == null || distance < nearest!!.second) {
                        nearest = PointSelection(track.id, segmentIndex, pointIndex) to distance
                    }
                }
            }
        }
        val selection = nearest
        _state.update {
            if (selection == null) it.copy(message = "There are no track points to select.")
            else if (selection.second > 500.0) it.copy(message = "No track point is within 500 m. Zoom in and tap closer to the route.")
            else it.copy(
                selectedTrackId = selection.first.trackId,
                selectedPoint = selection.first,
                message = "Selected point ${selection.first.pointIndex + 1} (${formatDistance(selection.second)} away).",
            )
        }
    }

    private fun splitAtMapPoint(query: GpxPoint) {
        val candidates = _state.value.selectedTrack?.let(::listOf) ?: _state.value.document.tracks
        var best: Triple<GpxTrack, Int, GeoMath.Projection>? = null
        candidates.forEach { track ->
            track.segments.forEachIndexed { segmentIndex, segment ->
                segment.points.zipWithNext().forEach { (start, end) ->
                    val projection = GeoMath.projectToSegment(query, start, end)
                    if (best == null || projection.distanceMeters < best!!.third.distanceMeters) {
                        best = Triple(track, segmentIndex, projection)
                    }
                }
            }
        }
        val match = best
        if (match == null || match.third.distanceMeters > 500.0) {
            _state.update { it.copy(message = "No track line is within 500 m. Zoom in and tap closer to the route.") }
            return
        }
        val result = runCatching { GpxOperations.splitTrackAtProjectedPoint(match.first, match.second, query) }
            .getOrElse { error ->
                _state.update { it.copy(message = error.message ?: "This endpoint cannot be split.") }
                return
            }
        val tracks = _state.value.document.tracks.toMutableList()
        val index = tracks.indexOfFirst { it.id == match.first.id }
        tracks.removeAt(index)
        tracks.addAll(index, listOf(result.first, result.second))
        commit(_state.value.document.copy(tracks = tracks), selectedTrackId = result.second.id, message = "Track split at the projected map position.")
        _state.update { it.copy(editMode = EditMode.SELECT) }
    }

    private fun moveSelectedPoint(point: GpxPoint) {
        val selection = _state.value.selectedPoint ?: run {
            _state.update { it.copy(message = "Select a point before using move mode.") }
            return
        }
        updatePoint(selection) { old -> old.copy(latitude = point.latitude, longitude = point.longitude) }
        _state.update { it.copy(editMode = EditMode.SELECT, message = "Point moved.") }
    }

    fun deleteSelectedPoint() {
        val selection = _state.value.selectedPoint ?: return
        val track = _state.value.document.tracks.firstOrNull { it.id == selection.trackId } ?: return
        val segment = track.segments.getOrNull(selection.segmentIndex) ?: return
        val points = segment.points.toMutableList().apply { removeAt(selection.pointIndex) }
        val segments = track.segments.toMutableList().apply {
            if (points.isEmpty()) removeAt(selection.segmentIndex) else this[selection.segmentIndex] = segment.copy(points = points)
        }
        replaceTrack(track.copy(segments = segments), "Point deleted.")
        _state.update { it.copy(selectedPoint = null) }
    }

    fun splitSelected() {
        val selection = _state.value.selectedPoint ?: return
        val track = _state.value.document.tracks.firstOrNull { it.id == selection.trackId } ?: return
        val segment = track.segments.getOrNull(selection.segmentIndex) ?: return
        if (segment.points.size < 2) return
        val (left, right) = GpxOperations.splitTrack(track, selection.segmentIndex, selection.pointIndex)
        val tracks = _state.value.document.tracks.toMutableList()
        val index = tracks.indexOfFirst { it.id == track.id }
        tracks.removeAt(index)
        tracks.addAll(index, listOf(left, right))
        commit(_state.value.document.copy(tracks = tracks), selectedTrackId = right.id, message = "Track split into two tracks.")
        _state.update { it.copy(selectedPoint = null, editMode = EditMode.SELECT) }
    }

    fun trimBeforeSelected() = trimAtSelection(keepBefore = false)
    fun trimAfterSelected() = trimAtSelection(keepBefore = true)

    private fun trimAtSelection(keepBefore: Boolean) {
        val selection = _state.value.selectedPoint ?: return
        val track = _state.value.document.tracks.firstOrNull { it.id == selection.trackId } ?: return
        val segment = track.segments[selection.segmentIndex]
        val points = if (keepBefore) segment.points.take(selection.pointIndex + 1) else segment.points.drop(selection.pointIndex)
        val segments = if (keepBefore) {
            track.segments.take(selection.segmentIndex) + segment.copy(points = points)
        } else {
            listOf(segment.copy(points = points)) + track.segments.drop(selection.segmentIndex + 1)
        }
        replaceTrack(track.copy(segments = segments), if (keepBefore) "Track trimmed after selection." else "Track trimmed before selection.")
        _state.update { it.copy(selectedPoint = null) }
    }

    fun reverseSelected(policy: ReverseTimePolicy = ReverseTimePolicy.REASSIGN_MONOTONIC) {
        val track = _state.value.selectedTrack ?: return
        val result = GpxOperations.reverse(track, policy)
        replaceTrack(result.value, result.warnings.firstOrNull() ?: "Track direction reversed.")
    }

    fun simplifySelected(toleranceMeters: Double) {
        val track = _state.value.selectedTrack ?: return
        val result = GpxOperations.simplify(track, toleranceMeters)
        replaceTrack(result.value, "Removed ${result.removedPoints} redundant points at ${toleranceMeters.toInt()} m tolerance.")
    }

    fun deduplicateSelected(toleranceMeters: Double = 0.2) {
        val track = _state.value.selectedTrack ?: return
        val result = GpxOperations.removeDuplicates(track, toleranceMeters)
        replaceTrack(result.value, "Removed ${result.removedPoints} duplicate points.")
    }

    fun cleanSpeedSpikes(maximumKph: Double = 80.0) {
        val track = _state.value.selectedTrack ?: return
        val result = GpxOperations.removeSpeedSpikes(track, maximumKph)
        replaceTrack(result.value, "Removed ${result.removedPoints} likely GPS spikes.")
    }

    fun smoothElevation(radius: Int = 3) {
        val track = _state.value.selectedTrack ?: return
        replaceTrack(GpxOperations.smoothElevation(track, radius), "Elevation smoothed with a ${radius * 2 + 1}-point window.")
    }

    fun interpolateElevation() {
        val track = _state.value.selectedTrack ?: return
        replaceTrack(GpxOperations.interpolateMissingElevations(track), "Missing internal elevation samples interpolated.")
    }

    fun shiftTime(minutes: Long) {
        val track = _state.value.selectedTrack ?: return
        replaceTrack(GpxOperations.shiftTime(track, Duration.ofMinutes(minutes)), "Track time shifted by $minutes minutes.")
    }

    fun generateTime(start: Instant, speedKph: Double) {
        val track = _state.value.selectedTrack ?: return
        replaceTrack(GpxOperations.generateTime(track, start, speedKph), "Timestamps generated at $speedKph km/h.")
    }

    fun clearTime() {
        val track = _state.value.selectedTrack ?: return
        replaceTrack(GpxOperations.clearTime(track), "Timestamps removed from track.")
    }

    fun mergeAll(stitch: Boolean) {
        val tracks = _state.value.document.tracks
        if (tracks.size < 2) return
        val merged = if (stitch) GpxOperations.stitch(tracks) else GpxOperations.combineAsSegments(tracks)
        commit(_state.value.document.copy(tracks = listOf(merged)), selectedTrackId = merged.id,
            message = if (stitch) "Tracks stitched into one continuous segment." else "Tracks combined while preserving segment gaps.")
    }

    fun autoOrderAndOrientTracks() {
        val tracks = _state.value.document.tracks
        if (tracks.size < 2) return
        val start = _state.value.selectedTrack ?: tracks.first()
        val remaining = tracks.filterNot { it.id == start.id }.toMutableList()
        val ordered = mutableListOf(start)
        var totalGap = 0.0
        while (remaining.isNotEmpty()) {
            val current = ordered.last()
            val currentEnd = current.segments.lastOrNull()?.points?.lastOrNull() ?: break
            val choice = remaining.map { candidate ->
                val first = candidate.segments.firstOrNull()?.points?.firstOrNull()
                val last = candidate.segments.lastOrNull()?.points?.lastOrNull()
                val forward = first?.let { GeoMath.distanceMeters(currentEnd, it) } ?: Double.POSITIVE_INFINITY
                val reversed = last?.let { GeoMath.distanceMeters(currentEnd, it) } ?: Double.POSITIVE_INFINITY
                Triple(candidate, reversed < forward, minOf(forward, reversed))
            }.minBy { it.third }
            remaining.remove(choice.first)
            val oriented = if (choice.second) GpxOperations.reverse(choice.first, ReverseTimePolicy.REASSIGN_MONOTONIC).value else choice.first
            ordered += oriented
            totalGap += choice.third
        }
        if (remaining.isNotEmpty()) ordered += remaining
        commit(
            _state.value.document.copy(tracks = ordered),
            selectedTrackId = ordered.first().id,
            message = "Auto-ordered and oriented ${ordered.size} tracks; endpoint gaps total ${formatDistance(totalGap)}. Review before stitching.",
        )
    }

    fun moveSelectedTrack(delta: Int) {
        val id = _state.value.selectedTrackId ?: return
        val tracks = _state.value.document.tracks.toMutableList()
        val from = tracks.indexOfFirst { it.id == id }
        val to = (from + delta).coerceIn(0, tracks.lastIndex)
        if (from < 0 || from == to) return
        val item = tracks.removeAt(from)
        tracks.add(to, item)
        commit(_state.value.document.copy(tracks = tracks), selectedTrackId = id, message = "Track order updated.")
    }

    fun planDailyStages(targetKilometers: Double) {
        val track = _state.value.selectedTrack ?: return
        val plan = StagePlanner.byDistance(track, targetKilometers * 1000.0)
        val index = _state.value.document.tracks.indexOfFirst { it.id == track.id }
        val tracks = _state.value.document.tracks.toMutableList().apply {
            removeAt(index)
            addAll(index, plan.stages)
        }
        commit(
            _state.value.document.copy(tracks = tracks),
            selectedTrackId = plan.stages.firstOrNull()?.id,
            message = "Created ${plan.stages.size} daily stages around ${targetKilometers.toInt()} km each.",
        )
    }

    fun convertSelectedRouteToTrack(routeId: String) {
        val route = _state.value.document.routes.firstOrNull { it.id == routeId } ?: return
        val track = GpxOperations.routeToTrack(route)
        commit(
            _state.value.document.copy(routes = _state.value.document.routes.filterNot { it.id == routeId }, tracks = _state.value.document.tracks + track),
            selectedTrackId = track.id,
            message = "Route converted to an editable track.",
        )
    }

    fun renameSelected(name: String) {
        val track = _state.value.selectedTrack ?: return
        replaceTrack(track.copy(name = name), "Track renamed.")
    }

    fun deleteSelectedTrack() {
        val track = _state.value.selectedTrack ?: return
        val tracks = _state.value.document.tracks.filterNot { it.id == track.id }
        commit(_state.value.document.copy(tracks = tracks), selectedTrackId = tracks.firstOrNull()?.id, message = "Track deleted.")
    }

    fun duplicateSelectedTrack() {
        val track = _state.value.selectedTrack ?: return
        val duplicate = track.copy(
            id = UUID.randomUUID().toString(),
            name = (track.name ?: "Track") + " copy",
            segments = track.segments.map { it.copy(id = UUID.randomUUID().toString(), points = it.points.map { point -> point.copy(id = UUID.randomUUID().toString()) }) },
        )
        commit(_state.value.document.copy(tracks = _state.value.document.tracks + duplicate), selectedTrackId = duplicate.id, message = "Track duplicated.")
    }

    fun toggleTrackVisibility(id: String) {
        _state.update { state ->
            val style = state.styles[id] ?: defaultStyle(state.styles.size)
            state.copy(styles = state.styles + (id to style.copy(visible = !style.visible)))
        }
    }

    fun setTrackColor(id: String, color: Long) {
        _state.update { state ->
            val style = state.styles[id] ?: defaultStyle(state.styles.size)
            state.copy(styles = state.styles + (id to style.copy(color = color)))
        }
    }

    private fun updatePoint(selection: PointSelection, transform: (GpxPoint) -> GpxPoint) {
        val track = _state.value.document.tracks.first { it.id == selection.trackId }
        val segments = track.segments.toMutableList()
        val segment = segments[selection.segmentIndex]
        val points = segment.points.toMutableList()
        points[selection.pointIndex] = transform(points[selection.pointIndex])
        segments[selection.segmentIndex] = segment.copy(points = points)
        replaceTrack(track.copy(segments = segments), "Point updated.")
    }

    private fun replaceTrack(updated: GpxTrack, message: String) {
        val tracks = _state.value.document.tracks.map { if (it.id == updated.id) updated else it }
        commit(_state.value.document.copy(tracks = tracks), selectedTrackId = updated.id, message = message)
    }

    private fun commit(
        document: GpxDocument,
        selectedTrackId: String? = _state.value.selectedTrackId ?: document.tracks.firstOrNull()?.id,
        message: String? = null,
    ) {
        if (document == _state.value.document) return
        undo.addLast(_state.value.document)
        while (undo.size > 75) undo.removeFirst()
        redo.clear()
        val styles = _state.value.styles.toMutableMap()
        document.tracks.forEachIndexed { index, track -> styles.putIfAbsent(track.id, defaultStyle(index)) }
        styles.keys.retainAll(document.tracks.map { it.id }.toSet())
        _state.update {
            it.copy(
                document = document,
                styles = styles,
                selectedTrackId = selectedTrackId,
                selectedPoint = null,
                undoAvailable = true,
                redoAvailable = false,
                dirty = true,
                message = message,
            )
        }
        autosave(document)
    }

    private fun replaceWithoutHistory(document: GpxDocument, message: String) {
        val selected = _state.value.selectedTrackId?.takeIf { id -> document.tracks.any { it.id == id } }
            ?: document.tracks.firstOrNull()?.id
        _state.update {
            it.copy(
                document = document,
                styles = stylesFor(document.tracks, it.styles),
                selectedTrackId = selected,
                selectedPoint = null,
                undoAvailable = undo.isNotEmpty(),
                redoAvailable = redo.isNotEmpty(),
                dirty = true,
                message = message,
            )
        }
        autosave(document)
    }

    private fun autosave(document: GpxDocument) {
        val generation = autosaveGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                autosaveMutex.withLock {
                    if (generation == autosaveGeneration.get()) store.saveAutosave(document)
                }
            }.onFailure { error ->
                _state.update { it.copy(message = "Autosave failed: ${error.message}") }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    companion object {
        private val PALETTE = listOf(
            0xFF176B45, 0xFF1565C0, 0xFFD84315, 0xFF6A1B9A,
            0xFF00838F, 0xFFAD1457, 0xFF5D4037, 0xFF455A64,
        )
        private fun defaultStyle(index: Int) = TrackStyle(PALETTE[index % PALETTE.size])
        private fun stylesFor(tracks: List<GpxTrack>, existing: Map<String, TrackStyle> = emptyMap()): Map<String, TrackStyle> =
            tracks.mapIndexed { index, track -> track.id to (existing[track.id] ?: defaultStyle(index)) }.toMap()
        private fun formatDistance(meters: Double): String = if (meters < 1000) "${meters.toInt()} m" else "%.1f km".format(meters / 1000)
    }
}
