package ch.cld9.velogpx.ui

import android.app.Application
import android.location.Location
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.cld9.velogpx.VeloGpxApplication
import ch.cld9.velogpx.data.project.ProjectAutosaveSession
import ch.cld9.velogpx.data.project.ProjectCamera
import ch.cld9.velogpx.data.project.ProjectEditorState
import ch.cld9.velogpx.data.project.ProjectTrackGroup
import ch.cld9.velogpx.data.project.normalizeTrackGroups
import ch.cld9.velogpx.data.project.ProjectOpenResult
import ch.cld9.velogpx.data.project.ProjectRecoverySource
import ch.cld9.velogpx.data.project.ProjectSaveStatus
import ch.cld9.velogpx.data.project.ProjectSelection
import ch.cld9.velogpx.data.project.ProjectState
import ch.cld9.velogpx.data.project.ProjectSummary
import ch.cld9.velogpx.engine.GeoMath
import ch.cld9.velogpx.engine.GpxAnalytics
import ch.cld9.velogpx.engine.GpxOperations
import ch.cld9.velogpx.engine.JoinGapStrategy
import ch.cld9.velogpx.engine.JoinPlan
import ch.cld9.velogpx.engine.JoinPlanner
import ch.cld9.velogpx.engine.ReverseTimePolicy
import ch.cld9.velogpx.engine.StagePlanner
import ch.cld9.velogpx.engine.TrackLocation
import ch.cld9.velogpx.engine.TrackPosition
import ch.cld9.velogpx.engine.TrackPositionEngine
import ch.cld9.velogpx.engine.TrackSelectionProfile
import ch.cld9.velogpx.engine.TrackSelectionProfileEngine
import ch.cld9.velogpx.engine.TrackDeduplicator
import ch.cld9.velogpx.engine.TrackRangeEngine
import ch.cld9.velogpx.engine.TrackStatistics
import ch.cld9.velogpx.io.GpxParser
import ch.cld9.velogpx.io.GpxWriter
import ch.cld9.velogpx.location.DeviceLocationTracker
import ch.cld9.velogpx.model.GpxDocument
import ch.cld9.velogpx.model.GpxMetadata
import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import ch.cld9.velogpx.model.GpxVersion
import ch.cld9.velogpx.model.TrackStyle
import ch.cld9.velogpx.routing.BRouterClient
import ch.cld9.velogpx.routing.BicycleProfile
import ch.cld9.velogpx.routing.RouteAlternative
import ch.cld9.velogpx.routing.RouteCoordinate
import ch.cld9.velogpx.routing.RoutedPath
import ch.cld9.velogpx.routing.RoutingAnchor
import ch.cld9.velogpx.routing.RoutingOutcome
import ch.cld9.velogpx.routing.RoutingRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class EditMode { SELECT, DRAW_STRAIGHT, MOVE, SPLIT, WAYPOINT }
enum class EditorPanel { MAP, PROFILE, LAYERS }

enum class RouteApplyMode { NEW_TRACK, APPEND_TO_SELECTED, PREPEND_TO_SELECTED }

data class RouteCandidate(val alternative: RouteAlternative, val path: RoutedPath)

data class RoutePlannerDraft(
    val active: Boolean = false,
    val anchors: List<RoutingAnchor> = emptyList(),
    val candidates: List<RouteCandidate> = emptyList(),
    val selectedAlternative: RouteAlternative? = null,
    val applyMode: RouteApplyMode = RouteApplyMode.NEW_TRACK,
    val targetTrackId: String? = null,
    val sourceDocumentRevision: Long = 0,
    val requestToken: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

data class SplitDraft(val trackId: String, val cuts: List<TrackLocation>, val sourceDocumentRevision: Long)

data class JoinDraft(
    val plan: JoinPlan,
    val keepOriginals: Boolean = false,
    val name: String = "Merged route",
    val sourceDocumentRevision: Long,
)

data class MapTrackChoice(
    val trackIds: List<String>,
    val latitude: Double,
    val longitude: Double,
    val forSplit: Boolean,
)

data class TrackListFocusRequest(val generation: Long, val trackId: String)
private data class EditorHistoryState(val document: GpxDocument, val groups: List<ProjectTrackGroup>)

data class PointSelection(
    val trackId: String,
    val segmentIndex: Int,
    val pointIndex: Int,
)

enum class TrackCursorSource { MAP, PROFILE, RECORDED_POINT }

data class TrackCursor(
    val position: TrackPosition,
    val source: TrackCursorSource,
)

data class CurrentDeviceLocation(
    val point: GpxPoint,
    val accuracyMeters: Float,
    val recordedAtMillis: Long,
)

data class EditorUiState(
    val document: GpxDocument = GpxDocument(metadata = GpxMetadata(name = "Untitled bicycle tour")),
    val styles: Map<String, TrackStyle> = emptyMap(),
    val selectedTrackId: String? = null,
    val selectedTrackIds: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val lassoSelectionActive: Boolean = false,
    val selectedPoint: PointSelection? = null,
    val selectedCursor: TrackCursor? = null,
    val editMode: EditMode = EditMode.SELECT,
    val panel: EditorPanel = EditorPanel.MAP,
    val bicycleProfile: BicycleProfile = BicycleProfile.TOURING,
    val undoAvailable: Boolean = false,
    val redoAvailable: Boolean = false,
    val busy: Boolean = false,
    val dirty: Boolean = false,
    val message: String? = null,
    val recoveredAutosave: Boolean = false,
    val loadingProject: Boolean = true,
    val projectId: String? = null,
    val projectTitle: String = "Untitled bicycle tour",
    val projects: List<ProjectSummary> = emptyList(),
    val saveStatus: ProjectSaveStatus? = null,
    val groups: List<ProjectTrackGroup> = emptyList(),
    val camera: MapCameraState? = null,
    val focusRequest: MapFocusRequest? = null,
    val trackListFocusRequest: TrackListFocusRequest? = null,
    val splitDraft: SplitDraft? = null,
    val joinDraft: JoinDraft? = null,
    val routePlanner: RoutePlannerDraft = RoutePlannerDraft(),
    val mapTrackChoice: MapTrackChoice? = null,
    val currentLocation: CurrentDeviceLocation? = null,
    val currentLocationProjection: TrackPosition? = null,
    val locationTracking: Boolean = false,
    val importOfferUris: List<Uri> = emptyList(),
    val layersScrollIndex: Int = 0,
    val layersScrollOffset: Int = 0,
) {
    val selectedTrack: GpxTrack? get() = document.tracks.firstOrNull { it.id == selectedTrackId }
    val selectedStatistics: TrackStatistics? get() = selectedTrack?.let(GpxAnalytics::statistics)
    val mapStyles: Map<String, TrackStyle>
        get() {
            val hiddenByGroup = groups.filterNot(ProjectTrackGroup::visible).flatMapTo(mutableSetOf(), ProjectTrackGroup::trackIds)
            return styles.mapValues { (id, style) ->
                if (id in hiddenByGroup) style.copy(visible = false) else style
            }
        }

    val draftAnchors: List<GpxPoint>
        get() = when {
            routePlanner.active -> routePlanner.anchors.map { it.coordinate.toGpxPoint() }
            splitDraft != null -> splitDraft.cuts.map(TrackLocation::projectedPoint)
            else -> emptyList()
        }

    val draftLines: List<MapDraftLine>
        get() {
            if (routePlanner.active) return routePlanner.candidates.map { candidate ->
                MapDraftLine(
                    points = candidate.path.points.map { it.toGpxPoint() },
                    color = if (candidate.alternative == routePlanner.selectedAlternative) 0xFF7B1FA2 else 0xFF607D8B,
                    selected = candidate.alternative == routePlanner.selectedAlternative,
                )
            }
            splitDraft?.let { draft ->
                val track = document.tracks.firstOrNull { it.id == draft.trackId } ?: return emptyList()
                val parts = runCatching { TrackRangeEngine.splitAtLocations(track, draft.cuts).tracks }.getOrNull()
                    ?: return emptyList()
                val colors = listOf(0xFF7B1FA2, 0xFF00838F, 0xFFD84315, 0xFF1565C0)
                return parts.flatMapIndexed { index, part ->
                    part.segments.map { segment -> MapDraftLine(segment.points, colors[index % colors.size], selected = true) }
                }
            }
            val draft = joinDraft ?: return emptyList()
            val tracks = document.tracks.associateBy(GpxTrack::id)
            return buildList {
                draft.plan.order.forEachIndexed { index, reference ->
                    val track = tracks[reference.trackId] ?: return@forEachIndexed
                    track.segments.forEach { segment ->
                        add(MapDraftLine(segment.points, color = 0xFF7B1FA2, selected = true))
                    }
                    val edge = draft.plan.edges.getOrNull(index) ?: return@forEachIndexed
                    when (edge.strategy) {
                        JoinGapStrategy.PRESERVE_SEGMENT_GAP -> Unit
                        JoinGapStrategy.STRAIGHT_CONNECTOR -> {
                            val fromPoints = track.segments.flatMap { it.points }
                            val nextTrack = draft.plan.order.getOrNull(index + 1)?.let { tracks[it.trackId] }
                            val toPoints = nextTrack?.segments?.flatMap { it.points }.orEmpty()
                            val from = if (reference.reversed) fromPoints.firstOrNull() else fromPoints.lastOrNull()
                            val nextReversed = draft.plan.order.getOrNull(index + 1)?.reversed == true
                            val to = if (nextReversed) toPoints.lastOrNull() else toPoints.firstOrNull()
                            if (from != null && to != null) add(MapDraftLine(listOf(from, to), 0xFF7B1FA2, true))
                        }
                        JoinGapStrategy.ROUTED_CONNECTOR -> edge.routedConnector?.let { connector ->
                            add(MapDraftLine(connector.points, color = 0xFF7B1FA2, selected = true))
                        }
                    }
                }
            }
        }
}

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as VeloGpxApplication).projectRepository
    private val router = BRouterClient()
    private val undo = ArrayDeque<EditorHistoryState>()
    private val redo = ArrayDeque<EditorHistoryState>()
    private val parser = GpxParser()
    private val writer = GpxWriter()
    private val focusGeneration = AtomicLong()
    private var project: ProjectState? = null
    private var autosave: ProjectAutosaveSession? = null
    private var saveStatusJob: Job? = null
    private var routeJob: Job? = null
    private var joinJob: Job? = null
    private val pendingImports = mutableListOf<Uri>()
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()
    private var focusWhenLocationArrives = false
    private var locationRequested = false
    private val locationTracker by lazy {
        DeviceLocationTracker(
            getApplication(),
            onLocation = ::onDeviceLocation,
            onError = { message -> _state.update { it.copy(locationTracking = false, message = message) } },
        )
    }

    init {
        viewModelScope.launch {
            runCatching { repository.openLastOrCreate() }
                .onSuccess { loadProject(it) }
                .onFailure { error ->
                    _state.update { it.copy(loadingProject = false, message = "Could not open projects: ${error.message}") }
                }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null, recoveredAutosave = false) }

    fun setMode(mode: EditMode) {
        _state.update {
            it.copy(
                editMode = mode,
                lassoSelectionActive = false,
                message = null,
                splitDraft = if (mode == EditMode.SPLIT) it.splitDraft else null,
            )
        }
    }

    fun setPanel(panel: EditorPanel) {
        _state.update { state -> TracksListLayout.enterPanel(state, panel, focusGeneration::incrementAndGet) }
        persistEditorState()
    }

    fun rememberTracksScroll(index: Int, offset: Int) {
        val safeIndex = index.coerceAtLeast(0)
        val safeOffset = offset.coerceAtLeast(0)
        if (_state.value.layersScrollIndex == safeIndex && _state.value.layersScrollOffset == safeOffset) return
        _state.update { it.copy(layersScrollIndex = safeIndex, layersScrollOffset = safeOffset) }
        persistEditorState()
    }

    fun setProfile(profile: BicycleProfile) {
        if (_state.value.routePlanner.active && _state.value.bicycleProfile != profile) {
            routeJob?.cancel()
            _state.update {
                it.copy(
                    bicycleProfile = profile,
                    routePlanner = it.routePlanner.copy(
                        candidates = emptyList(), selectedAlternative = null, busy = false,
                        requestToken = null, error = null,
                    ),
                )
            }
        } else _state.update { it.copy(bicycleProfile = profile) }
        persistEditorState()
    }

    /** Track-list selection deliberately focuses the map; map selection deliberately does not. */
    fun selectTrack(id: String) = selectTrack(id, focus = true)

    fun selectTracksFromMap(ids: List<String>) {
        val id = ids.firstOrNull { candidate -> _state.value.document.tracks.any { it.id == candidate } } ?: return
        if (_state.value.selectionMode) toggleTrackSelection(id, focus = false) else selectTrack(id, focus = false)
    }

    fun onMapTracksTap(ids: List<String>, latitude: Double, longitude: Double) {
        val valid = ids.filter { id -> _state.value.document.tracks.any { it.id == id } }.distinct()
        if (valid.isEmpty()) return
        if (valid.size > 1) {
            _state.update {
                it.copy(mapTrackChoice = MapTrackChoice(valid, latitude, longitude, it.editMode == EditMode.SPLIT))
            }
        } else if (_state.value.editMode == EditMode.SPLIT) {
            addSplitCutOnTrack(valid, latitude, longitude)
        } else if (_state.value.selectionMode) selectTracksFromMap(valid)
        else selectPositionOnTrack(valid.first(), GpxPoint(latitude, longitude))
    }

    fun chooseMapTrack(trackId: String) {
        val choice = _state.value.mapTrackChoice ?: return
        _state.update { it.copy(mapTrackChoice = null) }
        if (choice.forSplit) addSplitCutOnTrack(listOf(trackId), choice.latitude, choice.longitude)
        else if (_state.value.selectionMode) selectTracksFromMap(listOf(trackId))
        else selectPositionOnTrack(trackId, GpxPoint(choice.latitude, choice.longitude))
    }

    fun dismissMapTrackChoice() = _state.update { it.copy(mapTrackChoice = null) }

    private fun selectTrack(id: String, focus: Boolean) {
        val track = _state.value.document.tracks.firstOrNull { it.id == id } ?: return
        _state.update { state ->
            val selectedIds = if (state.selectionMode) state.selectedTrackIds + id else setOf(id)
            state.copy(
                selectedTrackId = id,
                selectedTrackIds = selectedIds,
                selectedPoint = null,
                selectedCursor = null,
                panel = EditorPanel.MAP,
                styles = if (focus) state.styles + (id to (state.styles[id] ?: defaultStyle(state.styles.size)).copy(visible = true)) else state.styles,
                focusRequest = if (focus) MapFocusRequest(focusGeneration.incrementAndGet(), track.segments.flatMap { it.points }, PROFILE_MAP_INSET_DP) else state.focusRequest,
            )
        }
        refreshCurrentLocationProjection()
        persistEditorState()
    }

    fun enterSelectionMode(initialTrackId: String? = _state.value.selectedTrackId) {
        _state.update { state ->
            val valid = initialTrackId?.takeIf { id -> state.document.tracks.any { it.id == id } }
            state.copy(
                selectionMode = true,
                lassoSelectionActive = false,
                selectedTrackIds = valid?.let(::setOf) ?: emptySet(),
                selectedTrackId = valid,
                selectedPoint = null,
                selectedCursor = null,
                editMode = EditMode.SELECT,
            )
        }
        refreshCurrentLocationProjection()
        persistEditorState()
    }

    fun exitSelectionMode() {
        _state.update { state ->
            val primary = state.selectedTrackId?.takeIf { it in state.selectedTrackIds }
                ?: state.selectedTrackIds.firstOrNull()
            state.copy(
                selectionMode = false,
                lassoSelectionActive = false,
                selectedTrackIds = setOfNotNull(primary),
                selectedTrackId = primary,
            )
        }
        persistEditorState()
    }

    fun toggleTrackSelection(id: String, focus: Boolean = false) {
        val track = _state.value.document.tracks.firstOrNull { it.id == id } ?: return
        _state.update { state ->
            val selected = state.selectedTrackIds.toMutableSet().apply {
                if (!add(id)) remove(id)
            }
            val primary = if (id in selected) id else state.selectedTrackId?.takeIf(selected::contains) ?: selected.firstOrNull()
            state.copy(
                selectionMode = true,
                selectedTrackIds = selected,
                selectedTrackId = primary,
                selectedPoint = null,
                selectedCursor = null,
                focusRequest = if (focus && id in selected) MapFocusRequest(focusGeneration.incrementAndGet(), track.segments.flatMap { it.points }, PROFILE_MAP_INSET_DP) else state.focusRequest,
            )
        }
        persistEditorState()
    }

    fun toggleLassoSelection() {
        if (!_state.value.selectionMode) enterSelectionMode()
        _state.update {
            it.copy(
                lassoSelectionActive = !it.lassoSelectionActive,
                editMode = EditMode.SELECT,
                message = null,
            )
        }
    }

    fun selectTracksByLasso(trackIds: List<String>) {
        val state = _state.value
        val valid = state.document.tracks.map(GpxTrack::id).filter { it in trackIds }.toSet()
        if (valid.isEmpty()) {
            _state.update { it.copy(lassoSelectionActive = false, message = "No visible tracks crossed the lasso.") }
            return
        }
        val newlySelected = valid - state.selectedTrackIds
        _state.update {
            val selected = it.selectedTrackIds + valid
            it.copy(
                selectionMode = true,
                lassoSelectionActive = false,
                selectedTrackIds = selected,
                selectedTrackId = it.selectedTrackId?.takeIf(selected::contains) ?: valid.first(),
                selectedPoint = null,
                selectedCursor = null,
                message = if (newlySelected.isEmpty()) {
                    "Every track inside the lasso was already selected."
                } else {
                    "Selected ${newlySelected.size} track${if (newlySelected.size == 1) "" else "s"} with the lasso."
                },
            )
        }
        refreshCurrentLocationProjection()
        persistEditorState()
    }

    fun selectProfileDistance(distanceMeters: Double) {
        val state = _state.value
        val combined = combinedSelectionProfile(state)
        val position = combined?.sourcePositionAtDistance(distanceMeters)
            ?: state.selectedTrack?.let { TrackPositionEngine.atDistance(it, distanceMeters) }
            ?: return
        _state.update {
            it.copy(
                selectedTrackId = position.trackId,
                selectedCursor = TrackCursor(position, TrackCursorSource.PROFILE),
                selectedPoint = position.sourcePointIndex?.let { pointIndex ->
                    PointSelection(position.trackId, position.segmentIndex, pointIndex)
                },
            )
        }
        refreshCurrentLocationProjection()
        persistEditorState()
    }

    fun startLocationTracking(focusWhenAvailable: Boolean = true): Boolean {
        focusWhenLocationArrives = focusWhenAvailable
        if (!locationTracker.hasPermission()) return false
        locationRequested = true
        val started = locationTracker.start()
        _state.update { it.copy(locationTracking = started) }
        if (started && focusWhenAvailable) _state.value.currentLocation?.let { focusLocation(it.point) }
        return started
    }

    fun onLocationPermissionDenied() {
        _state.update { it.copy(message = "Location permission was not granted. Your routes remain fully usable offline.") }
    }

    fun stopLocationTracking() {
        locationRequested = false
        locationTracker.stop()
        _state.update { it.copy(locationTracking = false) }
    }

    fun pauseLocationForBackground() {
        locationTracker.stop()
        _state.update { it.copy(locationTracking = false) }
    }

    fun resumeLocationForForeground() {
        if (!locationRequested || !locationTracker.hasPermission()) return
        _state.update { it.copy(locationTracking = locationTracker.start()) }
    }

    fun focusCurrentLocation() {
        val point = _state.value.currentLocation?.point
        if (point == null) focusWhenLocationArrives = true else focusLocation(point)
    }

    fun selectAllTracks() {
        _state.update { state ->
            val ids = state.document.tracks.mapTo(linkedSetOf(), GpxTrack::id)
            state.copy(selectionMode = true, selectedTrackIds = ids, selectedTrackId = state.selectedTrackId ?: ids.firstOrNull())
        }
        persistEditorState()
    }

    fun focusSelectedTracks() {
        val ids = _state.value.selectedTrackIds.ifEmpty { setOfNotNull(_state.value.selectedTrackId) }
        val points = _state.value.document.tracks.filter { it.id in ids }.flatMap { it.segments }.flatMap { it.points }
        if (points.isEmpty()) return
        _state.update { it.copy(panel = EditorPanel.MAP, focusRequest = MapFocusRequest(focusGeneration.incrementAndGet(), points, PROFILE_MAP_INSET_DP)) }
        persistEditorState()
    }

    fun onCameraIdle(camera: MapCameraState) {
        val previous = _state.value.camera
        if (previous == camera) return
        _state.update { it.copy(camera = camera) }
        persistEditorState()
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (_state.value.loadingProject) {
            pendingImports += uris
            _state.update { it.copy(message = "GPX import queued until the project finishes opening.") }
            return
        }
        _state.update { it.copy(importOfferUris = uris.distinct()) }
    }

    fun dismissImportOffer() = _state.update { it.copy(importOfferUris = emptyList()) }

    fun confirmImportIntoCurrentProject() = confirmImport(intoNewProject = false)
    fun confirmImportAsNewProject() = confirmImport(intoNewProject = true)

    private fun confirmImport(intoNewProject: Boolean) {
        val uris = _state.value.importOfferUris
        if (uris.isEmpty()) return
        _state.update { it.copy(importOfferUris = emptyList()) }
        performImport(uris, intoNewProject)
    }

    private fun performImport(uris: List<Uri>, intoNewProject: Boolean) {
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
            val current = if (intoNewProject) GpxDocument() else _state.value.document
            val seenTracks = current.tracks.mapTo(mutableSetOf(), TrackDeduplicator::fingerprint)
            val seenRoutes = current.routes.mapTo(mutableSetOf(), TrackDeduplicator::routeFingerprint)
            val seenWaypoints = current.waypoints.mapTo(mutableSetOf(), TrackDeduplicator::waypointFingerprint)
            var duplicateTrackCount = 0
            var duplicateRouteCount = 0
            var duplicateWaypointCount = 0
            val filteredDocuments = documents.map { document ->
                val novelTracks = document.tracks.filter { track ->
                    if (seenTracks.add(TrackDeduplicator.fingerprint(track))) true
                    else { duplicateTrackCount++; false }
                }
                val novelRoutes = document.routes.filter { route ->
                    if (seenRoutes.add(TrackDeduplicator.routeFingerprint(route))) true
                    else { duplicateRouteCount++; false }
                }
                val novelWaypoints = document.waypoints.filter { waypoint ->
                    if (seenWaypoints.add(TrackDeduplicator.waypointFingerprint(waypoint))) true
                    else { duplicateWaypointCount++; false }
                }
                document.copy(tracks = novelTracks, routes = novelRoutes, waypoints = novelWaypoints)
            }
            val duplicateCount = duplicateTrackCount + duplicateRouteCount + duplicateWaypointCount
            val documentsWithData = filteredDocuments.filterNot { it.isEmpty }
            if (documentsWithData.isEmpty()) {
                _state.update {
                    it.copy(
                        busy = false,
                        message = if (duplicateCount > 0) {
                            "Nothing imported: $duplicateCount duplicate GPX item${if (duplicateCount == 1) " was" else "s were"} already present."
                        } else "No GPX geometry was found.",
                    )
                }
                return@launch
            }
            val base = if (current.isEmpty) documentsWithData.first() else current
            val rest = if (current.isEmpty) documentsWithData.drop(1) else documentsWithData
            val merged = GpxOperations.mergeDocuments(base, rest)
            val usedGroupNames = (if (intoNewProject) emptyList() else _state.value.groups.map(ProjectTrackGroup::name)).toMutableSet()
            val importedGroups = filteredDocuments.mapNotNull { document ->
                val ids = document.tracks.map(GpxTrack::id)
                if (ids.isEmpty()) null else ProjectTrackGroup(
                    name = uniqueGroupName(importGroupName(document), usedGroupNames),
                    trackIds = ids,
                )
            }
            val importedPoints = filteredDocuments.flatMap { document ->
                document.tracks.flatMap { track -> track.segments.flatMap { it.points } }
            }
            val firstImportedTrackId = filteredDocuments.asSequence().flatMap { it.tracks.asSequence() }.firstOrNull()?.id
            val message = buildString {
                append("Imported ${documents.size} file${if (documents.size == 1) "" else "s"}")
                if (importedGroups.isNotEmpty()) append(" into ${importedGroups.size} source group${if (importedGroups.size == 1) "" else "s"}")
                if (duplicateCount > 0) append("; skipped $duplicateCount duplicate GPX item${if (duplicateCount == 1) "" else "s"}")
                val warningCount = errors.size + merged.warnings.size
                if (warningCount > 0) append("; $warningCount warning${if (warningCount == 1) "" else "s"}")
                append('.')
            }
            if (intoNewProject) {
                val title = importGroupName(documents.first()).ifBlank { "Imported bicycle tour" }
                val created = runCatching { repository.create(title, merged.value) }.getOrElse { error ->
                    _state.update { it.copy(busy = false, message = "Could not create the imported project: ${error.message}") }
                    return@launch
                }
                loadProject(ProjectOpenResult(created, ProjectRecoverySource.NEW_PROJECT))
                _state.update {
                    it.copy(
                        groups = importedGroups,
                        message = message,
                        busy = false,
                        panel = EditorPanel.MAP,
                        focusRequest = importedPoints.takeIf { points -> points.isNotEmpty() }
                            ?.let { points -> MapFocusRequest(focusGeneration.incrementAndGet(), points, PROFILE_MAP_INSET_DP) },
                    )
                }
                persistEditorState()
            } else {
                val previousGroups = _state.value.groups
                commit(merged.value, selectedTrackId = firstImportedTrackId ?: _state.value.selectedTrackId, message = message)
                _state.update {
                    it.copy(
                        groups = normalizeTrackGroups(
                            it.document.tracks.map(GpxTrack::id),
                            previousGroups.map { group -> group.copy(trackIds = group.trackIds - importedGroups.flatMap(ProjectTrackGroup::trackIds).toSet()) } + importedGroups,
                        ),
                        busy = false,
                        panel = EditorPanel.MAP,
                        focusRequest = importedPoints.takeIf { points -> points.isNotEmpty() }
                            ?.let { points -> MapFocusRequest(focusGeneration.incrementAndGet(), points, PROFILE_MAP_INSET_DP) },
                    )
                }
                persistEditorState()
            }
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
                if (clearsDirty && _state.value.document == document) markProjectExported()
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

    fun newProject(title: String = "Untitled bicycle tour") {
        viewModelScope.launch {
            runCatching { repository.create(title.trim().ifBlank { "Untitled bicycle tour" }) }
                .onSuccess { loadProject(ProjectOpenResult(it, ProjectRecoverySource.NEW_PROJECT)) }
                .onFailure { error -> _state.update { it.copy(message = "Could not create project: ${error.message}") } }
        }
    }

    fun openProject(projectId: String) {
        if (projectId == _state.value.projectId) return
        viewModelScope.launch {
            _state.update { it.copy(loadingProject = true) }
            runCatching { repository.open(projectId) }
                .onSuccess { loadProject(it) }
                .onFailure { error -> _state.update { it.copy(loadingProject = false, message = "Could not open project: ${error.message}") } }
        }
    }

    fun renameProject(title: String) {
        val projectId = _state.value.projectId ?: return
        viewModelScope.launch {
            runCatching { autosave?.flush(); repository.rename(projectId, title) }
                .onSuccess { renamed -> loadProject(ProjectOpenResult(renamed)); refreshProjects() }
                .onFailure { error -> _state.update { it.copy(message = "Could not rename project: ${error.message}") } }
        }
    }

    fun duplicateProject(projectId: String) {
        viewModelScope.launch {
            runCatching {
                if (projectId == _state.value.projectId) autosave?.flush()
                repository.duplicate(projectId)
            }
                .onSuccess { duplicated -> loadProject(ProjectOpenResult(duplicated)); refreshProjects() }
                .onFailure { error -> _state.update { it.copy(message = "Could not duplicate project: ${error.message}") } }
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            runCatching {
                if (projectId == _state.value.projectId) autosave?.close()
                repository.moveToTrash(projectId)
                if (projectId == _state.value.projectId) repository.openLastOrCreate() else null
            }.onSuccess { replacement ->
                if (replacement != null) loadProject(replacement) else refreshProjects()
            }.onFailure { error -> _state.update { it.copy(message = "Could not move project to trash: ${error.message}") } }
        }
    }

    fun createSnapshot() {
        val projectId = _state.value.projectId ?: return
        viewModelScope.launch {
            runCatching { autosave?.flush(); repository.createSnapshot(projectId) }
                .onSuccess { _state.update { it.copy(message = "Recovery snapshot created.") } }
                .onFailure { error -> _state.update { it.copy(message = "Snapshot failed: ${error.message}") } }
        }
    }

    fun undo() {
        val previous = undo.removeLastOrNull() ?: return
        redo.addLast(EditorHistoryState(_state.value.document, _state.value.groups))
        replaceWithoutHistory(previous, "Undid last edit.")
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(EditorHistoryState(_state.value.document, _state.value.groups))
        replaceWithoutHistory(next, "Redid edit.")
    }

    fun onMapTap(latitude: Double, longitude: Double) {
        val point = GpxPoint(latitude, longitude)
        when (_state.value.editMode) {
            EditMode.DRAW_STRAIGHT -> appendPoint(point)
            EditMode.WAYPOINT -> addWaypoint(point)
            EditMode.MOVE -> moveSelectedPoint(point)
            EditMode.SPLIT -> addSplitCut(point)
            EditMode.SELECT -> clearMapSelection()
        }
    }

    /** Called only when MapLibre found no rendered track inside the map's tap corridor. */
    private fun clearMapSelection() {
        val state = _state.value
        if (state.selectedTrackId == null && state.selectedTrackIds.isEmpty() &&
            state.selectedPoint == null && state.selectedCursor == null
        ) return
        _state.update(EditorUiState::withMapSelectionCleared)
        persistEditorState()
    }

    private fun appendPoint(point: GpxPoint) {
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
        replaceTrack(track.copy(segments = segments.dropLast(1) + lastSegment.copy(points = lastSegment.points + point)), "Point added.")
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

    private fun selectPositionOnTrack(trackId: String, query: GpxPoint) {
        val track = _state.value.document.tracks.firstOrNull { it.id == trackId } ?: return
        val position = TrackPositionEngine.project(track, query) ?: return
        val segment = track.segments.getOrNull(position.segmentIndex)
        val sourceSelection = position.sourcePointIndex?.let { pointIndex ->
            PointSelection(trackId, position.segmentIndex, pointIndex)
        } ?: run {
            val startIndex = position.edgeStartPointIndex
            val endpoint = if (position.fraction <= 0.5) startIndex else startIndex + 1
            val endpointPoint = segment?.points?.getOrNull(endpoint)
            endpointPoint?.takeIf { GeoMath.distanceMeters(position.point, it) <= 25.0 }
                ?.let { PointSelection(trackId, position.segmentIndex, endpoint) }
        }
        _state.update {
            it.copy(
                selectedTrackId = trackId,
                selectedTrackIds = setOf(trackId),
                selectedPoint = sourceSelection,
                selectedCursor = TrackCursor(position, TrackCursorSource.MAP),
                message = null,
            )
        }
        refreshCurrentLocationProjection()
        persistEditorState()
    }

    private fun addSplitCut(query: GpxPoint) {
        val candidates = _state.value.selectedTrack?.let(::listOf) ?: _state.value.document.tracks
        val best = candidates.mapNotNull { track ->
            runCatching { track to TrackRangeEngine.project(track, query) }.getOrNull()
        }.minByOrNull { it.second.distanceFromQueryMeters }
        if (best == null || best.second.distanceFromQueryMeters > 500.0) {
            _state.update { it.copy(message = "No track line is within 500 m. Zoom in and tap closer to the route.") }
            return
        }
        val revision = project?.documentRevision ?: 0L
        val existing = _state.value.splitDraft
        val cuts = if (existing?.trackId == best.first.id && existing.sourceDocumentRevision == revision) existing.cuts + best.second else listOf(best.second)
        _state.update {
            it.copy(
                selectedTrackId = best.first.id,
                selectedTrackIds = setOf(best.first.id),
                splitDraft = SplitDraft(best.first.id, cuts, revision),
                message = "Cut ${cuts.size} positioned. Add more cuts or apply the split.",
            )
        }
    }

    fun addSplitCutOnTrack(trackIds: List<String>, latitude: Double, longitude: Double) {
        val id = trackIds.firstOrNull { candidate -> _state.value.document.tracks.any { it.id == candidate } } ?: return
        _state.update { it.copy(selectedTrackId = id, selectedTrackIds = setOf(id)) }
        addSplitCut(GpxPoint(latitude, longitude))
    }

    fun onSplitEmptyMapTap() {
        _state.update { it.copy(message = "Tap directly on the track you want to split. Zoom in if tracks overlap.") }
    }

    fun clearSplitDraft() = _state.update { it.copy(splitDraft = null, editMode = EditMode.SELECT) }

    fun undoLastSplitCut() {
        _state.update { state ->
            val draft = state.splitDraft ?: return@update state
            val remaining = draft.cuts.dropLast(1)
            state.copy(splitDraft = if (remaining.isEmpty()) null else draft.copy(cuts = remaining))
        }
    }

    fun applySplitDraft() {
        val draft = _state.value.splitDraft ?: return
        if (draft.sourceDocumentRevision != project?.documentRevision) {
            _state.update { it.copy(splitDraft = null, message = "The track changed after the split preview. Place the cuts again.") }
            return
        }
        val track = _state.value.document.tracks.firstOrNull { it.id == draft.trackId } ?: return
        val sourceGroup = groupForTrack(track.id)
        val result = runCatching { TrackRangeEngine.splitAtLocations(track, draft.cuts) }.getOrElse { error ->
            _state.update { it.copy(message = error.message ?: "The requested cuts could not be applied.") }
            return
        }
        val index = _state.value.document.tracks.indexOfFirst { it.id == track.id }
        val tracks = _state.value.document.tracks.toMutableList().apply {
            removeAt(index)
            addAll(index, result.tracks)
        }
        commit(
            _state.value.document.copy(tracks = tracks),
            selectedTrackId = result.tracks.firstOrNull()?.id,
            message = "Split into ${result.tracks.size} tracks in one undoable edit.",
        )
        assignTracksToGroup(result.tracks.mapTo(linkedSetOf(), GpxTrack::id), sourceGroup)
        _state.update { it.copy(splitDraft = null, editMode = EditMode.SELECT) }
    }

    fun extractSplitSpan() {
        val draft = _state.value.splitDraft ?: return
        if (draft.sourceDocumentRevision != project?.documentRevision) {
            _state.update { it.copy(splitDraft = null, message = "The track changed after the span preview. Place the markers again.") }
            return
        }
        if (draft.cuts.size != 2) {
            _state.update { it.copy(message = "Place exactly two cut markers to extract a span.") }
            return
        }
        val track = _state.value.document.tracks.firstOrNull { it.id == draft.trackId } ?: return
        val sourceGroup = groupForTrack(track.id)
        val result = runCatching { TrackRangeEngine.extractSpan(track, draft.cuts[0], draft.cuts[1], reverseWhenBackwards = true) }
            .getOrElse { error ->
                _state.update { it.copy(message = error.message ?: "The selected span could not be extracted.") }
                return
            }
        commit(
            _state.value.document.copy(tracks = _state.value.document.tracks + result.track),
            selectedTrackId = result.track.id,
            message = result.warnings.firstOrNull() ?: "Extracted the selected span as a new track.",
        )
        assignTracksToGroup(setOf(result.track.id), sourceGroup)
        _state.update { it.copy(splitDraft = null, editMode = EditMode.SELECT) }
    }

    private fun moveSelectedPoint(point: GpxPoint) {
        val selection = _state.value.selectedPoint ?: run {
            _state.update { it.copy(message = "Select a point before using move mode.") }
            return
        }
        updatePoint(selection) { old -> old.copy(latitude = point.latitude, longitude = point.longitude) }
        val updated = _state.value.document.tracks.firstOrNull { it.id == selection.trackId }
        val cursor = updated?.let { TrackPositionEngine.atSourcePoint(it, selection.segmentIndex, selection.pointIndex) }
        _state.update { it.copy(editMode = EditMode.SELECT, selectedCursor = cursor?.let { value -> TrackCursor(value, TrackCursorSource.RECORDED_POINT) }, message = "Point moved.") }
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
        _state.update { it.copy(selectedPoint = null, selectedCursor = null) }
    }

    fun splitSelected() {
        val selection = _state.value.selectedPoint ?: return
        val track = _state.value.document.tracks.firstOrNull { it.id == selection.trackId } ?: return
        val sourceGroup = groupForTrack(track.id)
        val segment = track.segments.getOrNull(selection.segmentIndex) ?: return
        if (segment.points.size < 2) return
        val (left, right) = GpxOperations.splitTrack(track, selection.segmentIndex, selection.pointIndex)
        val tracks = _state.value.document.tracks.toMutableList()
        val index = tracks.indexOfFirst { it.id == track.id }
        tracks.removeAt(index)
        tracks.addAll(index, listOf(left, right))
        commit(_state.value.document.copy(tracks = tracks), selectedTrackId = right.id, message = "Track split into two tracks.")
        assignTracksToGroup(setOf(left.id, right.id), sourceGroup)
        _state.update { it.copy(selectedPoint = null, selectedCursor = null, editMode = EditMode.SELECT) }
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
        _state.update { it.copy(selectedPoint = null, selectedCursor = null) }
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
        val destinationGroup = groupForTrack(_state.value.selectedTrackId ?: tracks.first().id)
        val merged = if (stitch) GpxOperations.stitch(tracks) else GpxOperations.combineAsSegments(tracks)
        commit(_state.value.document.copy(tracks = listOf(merged)), selectedTrackId = merged.id,
            message = if (stitch) "Tracks stitched into one continuous segment." else "Tracks combined while preserving segment gaps.")
        assignTracksToGroup(setOf(merged.id), destinationGroup)
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
        val sourceGroup = groupForTrack(track.id)
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
        assignTracksToGroup(plan.stages.mapTo(linkedSetOf(), GpxTrack::id), sourceGroup)
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

    fun deleteSelectedTracks() {
        val ids = _state.value.selectedTrackIds.ifEmpty { setOfNotNull(_state.value.selectedTrackId) }
        if (ids.isEmpty()) return
        val tracks = _state.value.document.tracks.filterNot { it.id in ids }
        commit(
            _state.value.document.copy(tracks = tracks),
            selectedTrackId = tracks.firstOrNull()?.id,
            message = "Deleted ${ids.size} track${if (ids.size == 1) "" else "s"}.",
        )
        _state.update { it.copy(selectionMode = false, selectedTrackIds = setOfNotNull(it.selectedTrackId)) }
    }

    fun setSelectedTracksVisible(visible: Boolean) {
        val ids = _state.value.selectedTrackIds.ifEmpty { setOfNotNull(_state.value.selectedTrackId) }
        _state.update { state ->
            state.copy(styles = state.styles + ids.associateWith { id ->
                (state.styles[id] ?: defaultStyle(state.styles.size)).copy(visible = visible)
            })
        }
        persistEditorState()
    }

    fun createGroup(name: String) {
        val ids = _state.value.selectedTrackIds.ifEmpty { setOfNotNull(_state.value.selectedTrackId) }
        if (ids.isEmpty() || name.isBlank()) return
        _state.update { state ->
            val uniqueName = uniqueGroupName(name.trim(), state.groups.mapTo(mutableSetOf(), ProjectTrackGroup::name))
            val group = ProjectTrackGroup(name = uniqueName, trackIds = ids.toList())
            state.copy(groups = normalizeTrackGroups(
                state.document.tracks.map(GpxTrack::id),
                state.groups.map { it.copy(trackIds = it.trackIds.filterNot(ids::contains)) } + group,
            ))
        }
        persistEditorState()
    }

    fun moveSelectedTracksToGroup(groupId: String) {
        val ids = _state.value.selectedTrackIds.ifEmpty { setOfNotNull(_state.value.selectedTrackId) }
        assignTracksToGroup(ids, _state.value.groups.firstOrNull { it.id == groupId })
    }

    fun selectGroup(groupId: String) {
        val group = _state.value.groups.firstOrNull { it.id == groupId } ?: return
        _state.update { state ->
            val ids = group.trackIds.filterTo(linkedSetOf()) { id -> state.document.tracks.any { it.id == id } }
            state.copy(selectionMode = true, selectedTrackIds = ids, selectedTrackId = ids.firstOrNull(), selectedPoint = null)
        }
        persistEditorState()
    }

    fun deleteGroup(groupId: String) {
        _state.update { state ->
            state.copy(groups = normalizeTrackGroups(
                state.document.tracks.map(GpxTrack::id),
                state.groups.filterNot { group -> group.id == groupId },
            ))
        }
        persistEditorState()
    }

    fun toggleGroupCollapsed(groupId: String) {
        _state.update { state ->
            state.copy(groups = state.groups.map { group ->
                if (group.id == groupId) group.copy(collapsed = !group.collapsed) else group
            })
        }
        persistEditorState()
    }

    fun toggleGroupVisibility(groupId: String) {
        _state.update { state ->
            state.copy(groups = state.groups.map { group ->
                if (group.id == groupId) group.copy(visible = !group.visible) else group
            })
        }
        persistEditorState()
    }

    private fun groupForTrack(trackId: String): ProjectTrackGroup? =
        _state.value.groups.firstOrNull { trackId in it.trackIds }

    private fun assignTracksToGroup(trackIds: Set<String>, destination: ProjectTrackGroup?) {
        if (trackIds.isEmpty() || destination == null) return
        _state.update { state ->
            val validIds = trackIds.filterTo(linkedSetOf()) { id -> state.document.tracks.any { it.id == id } }
            if (validIds.isEmpty()) return@update state
            val withoutMoved = state.groups.map { it.copy(trackIds = it.trackIds.filterNot(validIds::contains)) }.toMutableList()
            val destinationIndex = withoutMoved.indexOfFirst { it.id == destination.id }
            if (destinationIndex >= 0) {
                val current = withoutMoved[destinationIndex]
                withoutMoved[destinationIndex] = current.copy(trackIds = current.trackIds + validIds)
            } else {
                withoutMoved += destination.copy(trackIds = validIds.toList())
            }
            state.copy(groups = normalizeTrackGroups(state.document.tracks.map(GpxTrack::id), withoutMoved))
        }
        persistEditorState()
    }

    fun duplicateSelectedTrack() {
        val track = _state.value.selectedTrack ?: return
        val sourceGroup = groupForTrack(track.id)
        val duplicate = track.copy(
            id = UUID.randomUUID().toString(),
            name = (track.name ?: "Track") + " copy",
            segments = track.segments.map { it.copy(id = UUID.randomUUID().toString(), points = it.points.map { point -> point.copy(id = UUID.randomUUID().toString()) }) },
        )
        commit(_state.value.document.copy(tracks = _state.value.document.tracks + duplicate), selectedTrackId = duplicate.id, message = "Track duplicated.")
        assignTracksToGroup(setOf(duplicate.id), sourceGroup)
    }

    fun prepareJoin(strategy: JoinGapStrategy = JoinGapStrategy.PRESERVE_SEGMENT_GAP) {
        val ids = _state.value.selectedTrackIds.ifEmpty { setOfNotNull(_state.value.selectedTrackId) }
        if (ids.size < 2) {
            _state.update { it.copy(message = "Select at least two tracks to merge.") }
            return
        }
        val plan = runCatching { JoinPlanner.plan(_state.value.document.tracks, ids, strategy) }.getOrElse { error ->
            _state.update { it.copy(message = "Merge planning failed: ${error.message}") }
            return
        }
        val mergePoints = _state.value.document.tracks
            .filter { it.id in ids }
            .flatMap { it.segments }
            .flatMap { it.points }
        _state.update {
            it.copy(
                panel = EditorPanel.MAP,
                joinDraft = JoinDraft(
                    plan,
                    name = "${it.selectedTrack?.name ?: "Route"} — merged",
                    sourceDocumentRevision = project?.documentRevision ?: 0L,
                ),
                focusRequest = mergePoints.takeIf { points -> points.isNotEmpty() }
                    ?.let { points -> MapFocusRequest(focusGeneration.incrementAndGet(), points) },
                message = "Review the proposed order, directions, and endpoint gaps before applying.",
            )
        }
        if (strategy == JoinGapStrategy.ROUTED_CONNECTOR) routeJoinConnectors()
    }

    fun setJoinStrategy(strategy: JoinGapStrategy) {
        if (_state.value.busy) return
        val draft = _state.value.joinDraft ?: return
        _state.update { it.copy(joinDraft = draft.copy(plan = draft.plan.copy(edges = draft.plan.edges.map { edge ->
            edge.copy(strategy = strategy, routedConnector = edge.routedConnector.takeIf { strategy == JoinGapStrategy.ROUTED_CONNECTOR })
        }))) }
        if (strategy == JoinGapStrategy.ROUTED_CONNECTOR) routeJoinConnectors()
    }

    fun setJoinKeepOriginals(keep: Boolean) {
        if (_state.value.busy) return
        _state.update { it.copy(joinDraft = it.joinDraft?.copy(keepOriginals = keep)) }
    }

    fun setJoinName(name: String) {
        _state.update { it.copy(joinDraft = it.joinDraft?.copy(name = name)) }
    }

    fun cancelJoin() {
        joinJob?.cancel()
        _state.update { it.copy(joinDraft = null, busy = false) }
    }

    private fun routeJoinConnectors() {
        val original = _state.value.joinDraft ?: return
        if (original.sourceDocumentRevision != project?.documentRevision) {
            _state.update { it.copy(joinDraft = null, message = "The source tracks changed. Reopen the join preview.") }
            return
        }
        val tracks = _state.value.document.tracks.associateBy(GpxTrack::id)
        val profile = _state.value.bicycleProfile
        joinJob?.cancel()
        _state.update { it.copy(busy = true, message = "Routing ${original.plan.edges.size} connector${if (original.plan.edges.size == 1) "" else "s"}…") }
        joinJob = viewModelScope.launch {
            var plan = original.plan
            var failures = 0
            original.plan.edges.forEachIndexed { index, edge ->
                val fromTrack = tracks[edge.from.trackId]
                val toTrack = tracks[edge.to.trackId]
                val from = fromTrack?.endpoint(edge.from.reversed, atStart = false)
                val to = toTrack?.endpoint(edge.to.reversed, atStart = true)
                if (from == null || to == null) {
                    failures++
                    plan = plan.withGapStrategy(index, JoinGapStrategy.PRESERVE_SEGMENT_GAP)
                    return@forEachIndexed
                }
                when (val outcome = router.route(
                    RoutingRequest(
                        anchors = listOf(
                            RoutingAnchor(RouteCoordinate.from(from)),
                            RoutingAnchor(RouteCoordinate.from(to)),
                        ),
                        profile = profile,
                    ),
                )) {
                    is RoutingOutcome.Success -> {
                        val routed = outcome.path.points.map { it.toGpxPoint() }.toMutableList()
                        val exactFrom = from.copy(id = UUID.randomUUID().toString())
                        if (routed.firstOrNull()?.let { GeoMath.distanceMeters(from, it) > 0.001 } != false) routed.add(0, exactFrom)
                        else routed[0] = exactFrom
                        val exactTo = to.copy(id = UUID.randomUUID().toString())
                        if (routed.lastOrNull()?.let { GeoMath.distanceMeters(to, it) > 0.001 } != false) routed += exactTo
                        else routed[routed.lastIndex] = exactTo
                        plan = plan.withRoutedConnector(index, GpxTrackSegment(points = routed))
                    }
                    is RoutingOutcome.Failure -> {
                        failures++
                        plan = plan.withGapStrategy(index, JoinGapStrategy.PRESERVE_SEGMENT_GAP)
                    }
                }
            }
            if (_state.value.joinDraft?.plan != original.plan || project?.documentRevision != original.sourceDocumentRevision ||
                _state.value.bicycleProfile != profile
            ) {
                _state.update { it.copy(busy = false, message = "The join draft changed; routed connectors were discarded.") }
            } else {
                _state.update {
                    val current = it.joinDraft ?: return@update it.copy(busy = false)
                    it.copy(
                        busy = false,
                        joinDraft = current.copy(plan = plan),
                        message = if (failures == 0) "All join gaps routed." else "$failures connector${if (failures == 1) "" else "s"} could not be routed; choose another gap policy.",
                    )
                }
            }
        }
    }

    fun applyJoin() {
        val draft = _state.value.joinDraft ?: return
        if (_state.value.busy) return
        if (draft.sourceDocumentRevision != project?.documentRevision) {
            _state.update { it.copy(joinDraft = null, message = "The source tracks changed. Reopen the join preview.") }
            return
        }
        val joined = runCatching {
            JoinPlanner.assemble(draft.plan, _state.value.document.tracks, draft.name.trim().ifBlank { "Merged route" })
        }.getOrElse { error ->
            _state.update { it.copy(message = "Could not assemble joined track: ${error.message}") }
            return
        }
        val joinedIds = draft.plan.order.mapTo(linkedSetOf()) { it.trackId }
        val destinationGroup = joinedIds.firstNotNullOfOrNull(::groupForTrack)
        val old = _state.value.document.tracks
        val insertion = old.indexOfFirst { it.id in joinedIds }.coerceAtLeast(0)
        val tracks = if (draft.keepOriginals) {
            old.toMutableList().apply { add((insertion + joinedIds.size).coerceAtMost(size), joined) }
        } else {
            old.filterNot { it.id in joinedIds }.toMutableList().apply { add(insertion.coerceAtMost(size), joined) }
        }
        commit(
            _state.value.document.copy(tracks = tracks),
            selectedTrackId = joined.id,
            message = "Merged ${joinedIds.size} tracks${if (draft.keepOriginals) " and kept the sources" else ""}.",
        )
        assignTracksToGroup(setOf(joined.id), destinationGroup)
        _state.update { it.copy(joinDraft = null, selectionMode = false, selectedTrackIds = setOf(joined.id)) }
    }

    fun openRoutePlanner() {
        _state.update {
            it.copy(
                panel = EditorPanel.MAP,
                routePlanner = RoutePlannerDraft(
                    active = true,
                    sourceDocumentRevision = project?.documentRevision ?: 0L,
                ),
                message = "Tap a start, then an end point. No endpoint is preselected.",
            )
        }
    }

    fun useSelectedEndpointAsRouteStart() {
        val point = _state.value.selectedTrack?.segments?.lastOrNull()?.points?.lastOrNull() ?: return
        _state.update { state ->
            state.copy(routePlanner = state.routePlanner.copy(
                anchors = listOf(RoutingAnchor(RouteCoordinate.from(point))),
                candidates = emptyList(),
                selectedAlternative = null,
                applyMode = RouteApplyMode.APPEND_TO_SELECTED,
                targetTrackId = state.selectedTrackId,
                sourceDocumentRevision = project?.documentRevision ?: 0L,
            ))
        }
    }

    fun closeRoutePlanner() {
        routeJob?.cancel()
        _state.update { it.copy(routePlanner = RoutePlannerDraft()) }
    }

    fun onRoutePlannerTap(latitude: Double, longitude: Double) {
        routeJob?.cancel()
        val anchor = RoutingAnchor(RouteCoordinate(latitude, longitude))
        _state.update { state ->
            val draft = state.routePlanner
            val anchors = if (draft.anchors.size < 2) draft.anchors + anchor else draft.anchors.dropLast(1) + anchor + draft.anchors.last()
            state.copy(routePlanner = draft.copy(
                anchors = anchors, candidates = emptyList(), selectedAlternative = null,
                busy = false, requestToken = null, error = null,
            ))
        }
    }

    fun removeLastRouteAnchor() {
        routeJob?.cancel()
        _state.update { state ->
            val draft = state.routePlanner
            val anchors = if (draft.anchors.size > 2) {
                draft.anchors.toMutableList().apply { removeAt(lastIndex - 1) }
            } else draft.anchors.dropLast(1)
            state.copy(routePlanner = draft.copy(
                anchors = anchors, candidates = emptyList(), selectedAlternative = null,
                busy = false, requestToken = null,
            ))
        }
    }

    fun reverseRouteAnchors() {
        routeJob?.cancel()
        _state.update { state ->
            val draft = state.routePlanner
            val mode = when (draft.applyMode) {
                RouteApplyMode.APPEND_TO_SELECTED -> RouteApplyMode.PREPEND_TO_SELECTED
                RouteApplyMode.PREPEND_TO_SELECTED -> RouteApplyMode.APPEND_TO_SELECTED
                RouteApplyMode.NEW_TRACK -> RouteApplyMode.NEW_TRACK
            }
            state.copy(routePlanner = draft.copy(
                anchors = draft.anchors.reversed(), candidates = emptyList(), selectedAlternative = null,
                applyMode = mode, busy = false, requestToken = null,
            ))
        }
    }

    fun setRouteApplyMode(mode: RouteApplyMode) {
        _state.update { state ->
            state.copy(routePlanner = state.routePlanner.copy(
                applyMode = mode,
                targetTrackId = if (mode == RouteApplyMode.NEW_TRACK) null else state.selectedTrackId,
            ))
        }
    }

    fun selectRouteCandidate(alternative: RouteAlternative) {
        if (_state.value.routePlanner.candidates.any { it.alternative == alternative }) {
            _state.update { it.copy(routePlanner = it.routePlanner.copy(selectedAlternative = alternative)) }
        }
    }

    fun calculateRoutes() {
        val original = _state.value.routePlanner
        if (original.anchors.size < 2) {
            _state.update { it.copy(routePlanner = original.copy(error = "Choose at least a start and end point.")) }
            return
        }
        routeJob?.cancel()
        val token = UUID.randomUUID().toString()
        val profile = _state.value.bicycleProfile
        val documentRevision = project?.documentRevision ?: 0L
        val requestDraft = original.copy(
            busy = true,
            candidates = emptyList(),
            selectedAlternative = null,
            error = null,
            requestToken = token,
            sourceDocumentRevision = documentRevision,
        )
        _state.update { it.copy(routePlanner = requestDraft) }
        routeJob = viewModelScope.launch {
            val candidates = mutableListOf<RouteCandidate>()
            var firstError: String? = null
            RouteAlternative.entries.forEach { alternative ->
                when (val outcome = router.route(RoutingRequest(requestDraft.anchors, profile, alternative))) {
                    is RoutingOutcome.Success -> candidates += RouteCandidate(alternative, outcome.path)
                    is RoutingOutcome.Failure -> if (firstError == null) firstError = outcome.reason.message
                }
            }
            val current = _state.value.routePlanner
            if (current.requestToken != token || current.anchors.map { it.id } != requestDraft.anchors.map { it.id } ||
                _state.value.bicycleProfile != profile || project?.documentRevision != documentRevision
            ) {
                if (current.requestToken == token) {
                    _state.update {
                        it.copy(routePlanner = it.routePlanner.copy(
                            busy = false, requestToken = null,
                            error = "The route inputs changed; recalculate the preview.",
                        ))
                    }
                }
                return@launch
            }
            _state.update { state ->
                state.copy(routePlanner = state.routePlanner.copy(
                    busy = false,
                    candidates = candidates,
                    selectedAlternative = candidates.firstOrNull()?.alternative,
                    error = if (candidates.isEmpty()) firstError ?: "No route was returned." else null,
                ))
            }
        }
    }

    fun applyPlannedRoute(name: String = "Planned bicycle route") {
        val draft = _state.value.routePlanner
        val candidate = draft.candidates.firstOrNull { it.alternative == draft.selectedAlternative } ?: return
        if (draft.sourceDocumentRevision != project?.documentRevision) {
            _state.update {
                it.copy(routePlanner = it.routePlanner.copy(
                    candidates = emptyList(), selectedAlternative = null,
                    error = "The project changed; recalculate before applying.",
                ))
            }
            return
        }
        val points = candidate.path.points.map { it.toGpxPoint() }
        val selected = draft.targetTrackId?.let { id -> _state.value.document.tracks.firstOrNull { it.id == id } }
        when (draft.applyMode) {
            RouteApplyMode.NEW_TRACK -> {
                val track = GpxTrack(name = name, segments = listOf(GpxTrackSegment(points)))
                commit(_state.value.document.copy(tracks = _state.value.document.tracks + track), selectedTrackId = track.id, message = "Added planned route as a new track.")
            }
            RouteApplyMode.APPEND_TO_SELECTED -> {
                if (selected == null) {
                    _state.update { it.copy(routePlanner = it.routePlanner.copy(error = "The append target is no longer available.")) }
                    return
                }
                val segments = selected.segments.ifEmpty { listOf(GpxTrackSegment()) }
                val last = segments.last()
                val boundary = last.points.lastOrNull()
                val close = boundary != null && points.firstOrNull()?.let { GeoMath.distanceMeters(boundary, it) <= 2.0 } == true
                val updated = if (close) {
                    selected.copy(segments = segments.dropLast(1) + last.copy(points = last.points + points.drop(1)))
                } else selected.copy(segments = segments + GpxTrackSegment(points))
                replaceTrack(
                    updated,
                    if (close) "Appended the planned route." else "Added the planned route as a new segment, preserving the endpoint gap.",
                )
            }
            RouteApplyMode.PREPEND_TO_SELECTED -> {
                if (selected == null) {
                    _state.update { it.copy(routePlanner = it.routePlanner.copy(error = "The prepend target is no longer available.")) }
                    return
                }
                val segments = selected.segments.ifEmpty { listOf(GpxTrackSegment()) }
                val first = segments.first()
                val boundary = first.points.firstOrNull()
                val close = boundary != null && points.lastOrNull()?.let { GeoMath.distanceMeters(boundary, it) <= 2.0 } == true
                val updated = if (close) {
                    selected.copy(segments = listOf(first.copy(points = points.dropLast(1) + first.points)) + segments.drop(1))
                } else selected.copy(segments = listOf(GpxTrackSegment(points)) + segments)
                replaceTrack(
                    updated,
                    if (close) "Prepended the planned route." else "Added the planned route as a new leading segment, preserving the endpoint gap.",
                )
            }
        }
        _state.update { it.copy(routePlanner = RoutePlannerDraft()) }
    }

    fun toggleTrackVisibility(id: String) {
        _state.update { state ->
            val style = state.styles[id] ?: defaultStyle(state.styles.size)
            state.copy(styles = state.styles + (id to style.copy(visible = !style.visible)))
        }
        persistEditorState()
    }

    fun setTrackColor(id: String, color: Long) {
        _state.update { state ->
            val style = state.styles[id] ?: defaultStyle(state.styles.size)
            state.copy(styles = state.styles + (id to style.copy(color = color)))
        }
        persistEditorState()
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
        routeJob?.cancel()
        undo.addLast(EditorHistoryState(_state.value.document, _state.value.groups))
        while (undo.size > 75) undo.removeFirst()
        redo.clear()
        val styles = _state.value.styles.toMutableMap()
        document.tracks.forEachIndexed { index, track -> styles.putIfAbsent(track.id, defaultStyle(index)) }
        styles.keys.retainAll(document.tracks.map { it.id }.toSet())
        _state.update {
            it.copy(
                document = document,
                styles = styles,
                groups = normalizeTrackGroups(document.tracks.map(GpxTrack::id), it.groups),
                selectedTrackId = selectedTrackId,
                selectedTrackIds = setOfNotNull(selectedTrackId),
                selectionMode = false,
                selectedPoint = null,
                selectedCursor = null,
                undoAvailable = true,
                redoAvailable = false,
                dirty = true,
                message = message,
                splitDraft = null,
                joinDraft = null,
                routePlanner = RoutePlannerDraft(),
            )
        }
        refreshCurrentLocationProjection()
        persistProject(documentChanged = true)
    }

    private fun replaceWithoutHistory(snapshot: EditorHistoryState, message: String) {
        routeJob?.cancel()
        val document = snapshot.document
        val selected = _state.value.selectedTrackId?.takeIf { id -> document.tracks.any { it.id == id } }
            ?: document.tracks.firstOrNull()?.id
        _state.update {
            it.copy(
                document = document,
                styles = stylesFor(document.tracks, it.styles),
                groups = normalizeTrackGroups(document.tracks.map(GpxTrack::id), snapshot.groups),
                selectedTrackId = selected,
                selectedTrackIds = setOfNotNull(selected),
                selectionMode = false,
                selectedPoint = null,
                selectedCursor = null,
                undoAvailable = undo.isNotEmpty(),
                redoAvailable = redo.isNotEmpty(),
                dirty = true,
                message = message,
                splitDraft = null,
                joinDraft = null,
                routePlanner = RoutePlannerDraft(),
            )
        }
        refreshCurrentLocationProjection()
        persistProject(documentChanged = true)
    }

    private suspend fun loadProject(result: ProjectOpenResult) {
        routeJob?.cancel()
        joinJob?.cancel()
        val closeError = runCatching { autosave?.close() }.exceptionOrNull()
        if (closeError != null) {
            _state.update { it.copy(loadingProject = false, message = "Could not save the current project: ${closeError.message}") }
            return
        }
        saveStatusJob?.cancel()
        undo.clear()
        redo.clear()
        val opened = result.project
        val liveLocation = _state.value.currentLocation
        val trackingLocation = _state.value.locationTracking
        project = opened
        val session = repository.autosaveSession(opened, viewModelScope)
        autosave = session
        val editor = opened.editor
        val document = reorderTracks(opened.document, editor.layerOrder)
        val selectedIds = editor.selectedTrackIds.filterTo(linkedSetOf()) { id -> document.tracks.any { it.id == id } }
        val selected = editor.selectedTrackId?.takeIf { id -> document.tracks.any { it.id == id } }
        val restoredSelection = editor.selectedPoint?.let { PointSelection(it.trackId, it.segmentIndex, it.pointIndex) }
        val restoredCursor = restoredSelection?.let { selection ->
            document.tracks.firstOrNull { it.id == selection.trackId }
                ?.let { TrackPositionEngine.atSourcePoint(it, selection.segmentIndex, selection.pointIndex) }
                ?.let { TrackCursor(it, TrackCursorSource.RECORDED_POINT) }
        }
        _state.value = EditorUiState(
            document = document,
            styles = stylesFor(document.tracks, editor.styles),
            selectedTrackId = selected,
            selectedTrackIds = selectedIds.ifEmpty { setOfNotNull(selected) },
            selectionMode = selectedIds.size > 1,
            selectedPoint = restoredSelection,
            selectedCursor = restoredCursor,
            panel = runCatching { EditorPanel.valueOf(editor.panelId) }.getOrDefault(EditorPanel.MAP),
            bicycleProfile = BicycleProfile.entries.firstOrNull { it.id == editor.routingProfileId } ?: BicycleProfile.TOURING,
            dirty = opened.isDocumentDirty,
            recoveredAutosave = result.source == ProjectRecoverySource.SNAPSHOT || result.source == ProjectRecoverySource.LEGACY_AUTOSAVE,
            loadingProject = false,
            projectId = opened.id,
            projectTitle = opened.title,
            saveStatus = ProjectSaveStatus.Saved(opened.revision),
            groups = normalizeTrackGroups(document.tracks.map(GpxTrack::id), editor.groups),
            camera = editor.camera?.let { MapCameraState(it.latitude, it.longitude, it.zoom, it.bearing, it.pitch) },
            message = result.warnings.firstOrNull(),
            layersScrollIndex = editor.layersScrollIndex,
            layersScrollOffset = editor.layersScrollOffset,
            currentLocation = liveLocation,
            locationTracking = trackingLocation,
        )
        refreshCurrentLocationProjection()
        saveStatusJob = viewModelScope.launch {
            session.status.collectLatest { status -> _state.update { it.copy(saveStatus = status) } }
        }
        refreshProjects()
        if (pendingImports.isNotEmpty()) {
            val queued = pendingImports.toList()
            pendingImports.clear()
            importUris(queued)
        }
    }

    private suspend fun refreshProjects() {
        runCatching { repository.listProjects() }
            .onSuccess { projects -> _state.update { it.copy(projects = projects) } }
    }

    private fun persistEditorState() = persistProject(documentChanged = false)

    private fun markProjectExported() {
        val current = project ?: return
        val exported = current.nextRevision(documentChanged = false).copy(
            lastExportedDocumentRevision = current.documentRevision,
        )
        project = exported
        autosave?.submit(exported)
        _state.update { it.copy(dirty = false) }
    }

    private fun persistProject(documentChanged: Boolean) {
        val current = project ?: return
        val state = _state.value
        val next = current.nextRevision(
            document = state.document,
            editor = ProjectEditorState(
                layerOrder = state.document.tracks.map(GpxTrack::id),
                styles = state.styles,
                groups = state.groups,
                selectedTrackId = state.selectedTrackId,
                selectedTrackIds = state.selectedTrackIds.toList(),
                selectedPoint = state.selectedPoint?.let { selection ->
                    val point = state.document.tracks.firstOrNull { it.id == selection.trackId }
                        ?.segments?.getOrNull(selection.segmentIndex)?.points?.getOrNull(selection.pointIndex)
                    point?.let {
                        ProjectSelection(
                            selection.trackId,
                            selection.segmentIndex,
                            selection.pointIndex,
                            (it.latitude * 10_000_000).toLong(),
                            (it.longitude * 10_000_000).toLong(),
                            it.time?.toEpochMilli(),
                        )
                    }
                },
                camera = state.camera?.let { ProjectCamera(it.latitude, it.longitude, it.zoom, it.bearing, it.tilt) },
                routingProfileId = state.bicycleProfile.id,
                panelId = state.panel.name,
                layersScrollIndex = state.layersScrollIndex,
                layersScrollOffset = state.layersScrollOffset,
            ),
            documentChanged = documentChanged,
        )
        project = next
        autosave?.submit(next)
        _state.update { it.copy(projectTitle = next.title, dirty = next.isDocumentDirty) }
    }

    private fun reorderTracks(document: GpxDocument, order: List<String>): GpxDocument {
        if (order.isEmpty()) return document
        val positions = order.withIndex().associate { it.value to it.index }
        return document.copy(tracks = document.tracks.sortedBy { positions[it.id] ?: Int.MAX_VALUE })
    }

    private fun GpxTrack.endpoint(reversed: Boolean, atStart: Boolean): GpxPoint? {
        val points = segments.flatMap { it.points }
        if (points.isEmpty()) return null
        return if (atStart xor reversed) points.first() else points.last()
    }

    private fun onDeviceLocation(location: Location) {
        val current = _state.value.currentLocation
        if (current != null && location.time < current.recordedAtMillis && location.accuracy >= current.accuracyMeters) return
        val point = GpxPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            elevation = location.altitude.takeIf { location.hasAltitude() },
        )
        _state.update {
            it.copy(
                currentLocation = CurrentDeviceLocation(point, location.accuracy, location.time),
                locationTracking = true,
            )
        }
        refreshCurrentLocationProjection()
        if (focusWhenLocationArrives) {
            focusWhenLocationArrives = false
            focusLocation(point)
        }
    }

    private fun focusLocation(point: GpxPoint) {
        _state.update {
            it.copy(
                panel = EditorPanel.MAP,
                focusRequest = MapFocusRequest(focusGeneration.incrementAndGet(), listOf(point), PROFILE_MAP_INSET_DP),
            )
        }
    }

    private fun refreshCurrentLocationProjection() {
        val state = _state.value
        val location = state.currentLocation
        val selectedIds = state.selectedTrackIds.takeIf { state.selectionMode && it.size > 1 }
        val tracks = if (selectedIds != null) {
            state.document.tracks.filter { it.id in selectedIds }
        } else listOfNotNull(state.selectedTrack)
        val projection = location?.let { current ->
            tracks.mapNotNull { TrackPositionEngine.project(it, current.point) }
                .minByOrNull(TrackPosition::distanceToTrackMeters)
                ?.takeIf { it.distanceToTrackMeters <= LOCATION_ROUTE_THRESHOLD_METERS }
        }
        if (projection != state.currentLocationProjection) {
            _state.update { it.copy(currentLocationProjection = projection) }
        }
    }

    private fun combinedSelectionProfile(state: EditorUiState): TrackSelectionProfile? {
        val ids = state.selectedTrackIds
        if (!state.selectionMode || ids.size < 2) return null
        return runCatching { TrackSelectionProfileEngine.build(state.document.tracks, ids) }.getOrNull()
    }

    override fun onCleared() {
        locationTracker.stop()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    private fun importGroupName(document: GpxDocument): String =
        (document.sourceName ?: document.metadata?.name ?: "Imported GPX")
            .replace(Regex("(?i)\\.gpx$"), "")
            .trim()

    private fun uniqueGroupName(requested: String, usedNames: MutableSet<String>): String {
        val base = requested.ifBlank { "Imported GPX" }
        var candidate = base
        var suffix = 2
        while (!usedNames.add(candidate)) {
            candidate = "$base ($suffix)"
            suffix++
        }
        return candidate
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
        private const val LOCATION_ROUTE_THRESHOLD_METERS = 200.0
        // The map composable already excludes system bars; its center remains above the profile card.
        private const val PROFILE_MAP_INSET_DP = 0f
    }
}
