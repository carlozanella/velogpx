@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package ch.cld9.velogpx.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import ch.cld9.velogpx.data.project.ProjectSaveStatus
import ch.cld9.velogpx.engine.JoinGapStrategy
import ch.cld9.velogpx.engine.ReverseTimePolicy
import ch.cld9.velogpx.engine.TrackStatistics
import ch.cld9.velogpx.engine.TrackSelectionProfile
import ch.cld9.velogpx.engine.TrackSelectionProfileEngine
import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxVersion
import ch.cld9.velogpx.routing.BicycleProfile
import ch.cld9.velogpx.share.GpxShareRequest
import ch.cld9.velogpx.share.GpxShareService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun EditorScreen(viewModel: EditorViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectionProfile = remember(state.document.tracks, state.selectedTrackIds, state.selectionMode) {
        if (state.selectionMode && state.selectedTrackIds.size >= 2) {
            runCatching { TrackSelectionProfileEngine.build(state.document.tracks, state.selectedTrackIds) }.getOrNull()
        } else null
    }
    val snackbarHost = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var toolsOpen by remember { mutableStateOf(false) }
    var simplifyDialog by remember { mutableStateOf(false) }
    var timeDialog by remember { mutableStateOf(false) }
    var stagesDialog by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var projectBrowser by remember { mutableStateOf(false) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    var groupDialog by remember { mutableStateOf(false) }
    var shareDialog by remember { mutableStateOf(false) }
    var mergeDialog by remember { mutableStateOf(false) }
    var exportVersion by remember { mutableStateOf(GpxVersion.V1_1) }
    var exportSelectedOnly by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layersListState = rememberLazyListState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importUris(uris)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        uri?.let { if (exportSelectedOnly) viewModel.exportSelected(it, exportVersion) else viewModel.export(it, exportVersion) }
    }
    val zipExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { viewModel.exportTrackBundle(it, exportVersion) }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.resumeLocationForForeground()
                Lifecycle.Event.ON_STOP -> viewModel.pauseLocationForBackground()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.projectId) {
        val maximumLikelyIndex = state.document.tracks.size + state.groups.size +
            state.document.routes.size + state.document.waypoints.size + 10
        layersListState.scrollToItem(state.layersScrollIndex.coerceAtMost(maximumLikelyIndex), state.layersScrollOffset)
        snapshotFlow { layersListState.firstVisibleItemIndex to layersListState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .debounce(350)
            .collect { (index, offset) -> viewModel.rememberLayersScroll(index, offset) }
    }

    LaunchedEffect(state.message, state.recoveredAutosave) {
        when {
            state.recoveredAutosave -> snackbarHost.showSnackbar("Recovered your autosaved project.")
            state.message != null -> snackbarHost.showSnackbar(state.message!!)
        }
        if (state.message != null || state.recoveredAutosave) viewModel.consumeMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.projectTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${state.document.tracks.size} tracks · ${state.document.pointCount} points · ${saveStatusLabel(state.saveStatus)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = state.undoAvailable) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = viewModel::redo, enabled = state.redoAvailable) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    IconButton(onClick = { importer.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")) }) {
                        Icon(Icons.Default.FileOpen, "Import GPX")
                    }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Project menu") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Projects") },
                            leadingIcon = { Icon(Icons.Default.Layers, null) },
                            onClick = { menuOpen = false; projectBrowser = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Create recovery snapshot") },
                            onClick = { menuOpen = false; viewModel.createSnapshot() },
                        )
                        DropdownMenuItem(
                            text = { Text("Export GPX 1.1") },
                            leadingIcon = { Icon(Icons.Default.Save, null) },
                            onClick = {
                                menuOpen = false; exportVersion = GpxVersion.V1_1; exportSelectedOnly = false
                                exporter.launch(exportName(state, "gpx"))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Export GPX 1.0") },
                            onClick = {
                                menuOpen = false; exportVersion = GpxVersion.V1_0; exportSelectedOnly = false
                                exporter.launch(exportName(state, "gpx"))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Export selected track") },
                            onClick = {
                                menuOpen = false; exportSelectedOnly = true
                                exporter.launch(((state.selectedTrack?.name ?: "selected-track").replace(Regex("[^A-Za-z0-9._-]+"), "-") + ".gpx"))
                            },
                            enabled = state.selectedTrack != null,
                        )
                        DropdownMenuItem(
                            text = { Text("Export tracks as ZIP") },
                            onClick = {
                                menuOpen = false; exportVersion = GpxVersion.V1_1
                                zipExporter.launch(exportName(state, "zip"))
                            },
                            enabled = state.document.tracks.isNotEmpty(),
                        )
                        DropdownMenuItem(text = { Text("Rename selected track") }, onClick = { menuOpen = false; renameDialog = true }, enabled = state.selectedTrack != null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
            )
        },
        bottomBar = {
            BottomAppBar {
                NavigationBarItem(
                    selected = state.panel == EditorPanel.MAP,
                    onClick = { viewModel.setPanel(EditorPanel.MAP) },
                    icon = { Icon(Icons.AutoMirrored.Filled.AltRoute, null) }, label = { Text("Map") },
                )
                NavigationBarItem(
                    selected = state.panel == EditorPanel.PROFILE,
                    onClick = { viewModel.setPanel(EditorPanel.PROFILE) },
                    icon = { Icon(Icons.Default.QueryStats, null) }, label = { Text("Profile") },
                )
                NavigationBarItem(
                    selected = state.panel == EditorPanel.LAYERS,
                    onClick = { viewModel.setPanel(EditorPanel.LAYERS) },
                    icon = { Icon(Icons.Default.Layers, null) }, label = { Text("Layers") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.panel) {
                EditorPanel.MAP -> MapPanel(
                    state,
                    viewModel,
                    selectionProfile,
                    onTools = { toolsOpen = true },
                    onMerge = { mergeDialog = true },
                    onDelete = { confirmBulkDelete = true },
                )
                EditorPanel.PROFILE -> ProfilePanel(state, viewModel, selectionProfile, onTools = { toolsOpen = true })
                EditorPanel.LAYERS -> LayersPanel(
                    state,
                    viewModel,
                    selectionProfile,
                    listState = layersListState,
                    onShare = { shareDialog = true },
                    onDelete = { confirmBulkDelete = true },
                    onGroup = { groupDialog = true },
                    onMerge = { mergeDialog = true },
                )
            }
            if (state.busy || state.loadingProject) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                    shape = RoundedCornerShape(24.dp), tonalElevation = 8.dp,
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp)); Text(if (state.loadingProject) "Opening project…" else "Working…")
                    }
                }
            }
        }
    }

    if (toolsOpen) TransformSheet(
        state = state,
        dismiss = { toolsOpen = false },
        split = { viewModel.splitSelected(); toolsOpen = false },
        trimBefore = { viewModel.trimBeforeSelected(); toolsOpen = false },
        trimAfter = { viewModel.trimAfterSelected(); toolsOpen = false },
        deletePoint = { viewModel.deleteSelectedPoint(); toolsOpen = false },
        reverse = { viewModel.reverseSelected(ReverseTimePolicy.REASSIGN_MONOTONIC); toolsOpen = false },
        simplify = { toolsOpen = false; simplifyDialog = true },
        deduplicate = { viewModel.deduplicateSelected(); toolsOpen = false },
        clean = { viewModel.cleanSpeedSpikes(); toolsOpen = false },
        smooth = { viewModel.smoothElevation(); toolsOpen = false },
        interpolate = { viewModel.interpolateElevation(); toolsOpen = false },
        time = { toolsOpen = false; timeDialog = true },
        stages = { toolsOpen = false; stagesDialog = true },
        guidedJoin = { toolsOpen = false; mergeDialog = true },
    )
    if (simplifyDialog) SimplifyDialog(
        dismiss = { simplifyDialog = false },
        apply = { viewModel.simplifySelected(it); simplifyDialog = false },
    )
    if (timeDialog) TimeDialog(
        dismiss = { timeDialog = false },
        generate = { speed -> viewModel.generateTime(Instant.now(), speed); timeDialog = false },
        shift = { minutes -> viewModel.shiftTime(minutes); timeDialog = false },
        clear = { viewModel.clearTime(); timeDialog = false },
    )
    if (stagesDialog) StageDialog(
        dismiss = { stagesDialog = false },
        apply = { viewModel.planDailyStages(it); stagesDialog = false },
    )
    if (renameDialog) RenameDialog(
        current = state.selectedTrack?.name.orEmpty(), dismiss = { renameDialog = false },
        apply = { viewModel.renameSelected(it); renameDialog = false },
    )
    if (projectBrowser) ProjectBrowserDialog(
        state = state,
        onDismiss = { projectBrowser = false },
        onCreate = viewModel::newProject,
        onOpen = { viewModel.openProject(it); projectBrowser = false },
        onRenameCurrent = viewModel::renameProject,
        onDuplicate = viewModel::duplicateProject,
        onDelete = viewModel::deleteProject,
        onSnapshot = viewModel::createSnapshot,
    )
    if (state.importOfferUris.isNotEmpty()) AlertDialog(
        onDismissRequest = viewModel::dismissImportOffer,
        title = { Text("Import ${state.importOfferUris.size} GPX file${if (state.importOfferUris.size == 1) "" else "s"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Each file becomes a named source group. Exact duplicate tracks, routes, and waypoints are skipped, so reimporting is safe.")
                Button(onClick = viewModel::confirmImportIntoCurrentProject, modifier = Modifier.fillMaxWidth()) {
                    Text("Add to current project")
                }
                OutlinedButton(onClick = viewModel::confirmImportAsNewProject, modifier = Modifier.fillMaxWidth()) {
                    Text("Create a new project")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = viewModel::dismissImportOffer) { Text("Cancel") } },
    )
    if (confirmBulkDelete) AlertDialog(
        onDismissRequest = { confirmBulkDelete = false },
        title = { Text("Delete selected tracks?") },
        text = { Text("${state.selectedTrackIds.size} track${if (state.selectedTrackIds.size == 1) "" else "s"} will be removed in one undoable edit.") },
        confirmButton = { Button(onClick = { viewModel.deleteSelectedTracks(); confirmBulkDelete = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmBulkDelete = false }) { Text("Cancel") } },
    )
    if (groupDialog) GroupDialog(
        dismiss = { groupDialog = false },
        apply = { viewModel.createGroup(it); groupDialog = false },
    )
    if (mergeDialog) MergeMethodDialog(
        count = state.selectedTrackIds.ifEmpty { setOfNotNull(state.selectedTrackId) }.size,
        routeDistanceMeters = selectionProfile?.totalDistanceMeters
            ?: state.selectedTrack?.let { ch.cld9.velogpx.engine.GpxAnalytics.statistics(it).distanceMeters }
            ?: 0.0,
        endpointGapMeters = selectionProfile?.plan?.totalGapMeters ?: 0.0,
        dismiss = { mergeDialog = false },
        apply = { strategy -> mergeDialog = false; viewModel.prepareJoin(strategy) },
    )
    if (shareDialog) ShareToGarminDialog(
        state = state,
        dismiss = { shareDialog = false },
        share = { requests ->
            shareDialog = false
            scope.launch {
                runCatching {
                    val service = GpxShareService(context)
                    val prepared = withContext(Dispatchers.IO) { service.prepare(requests) }
                    context.startActivity(service.createOpenIntent(prepared).intent)
                }.onFailure { error ->
                    Toast.makeText(context, "Could not share GPX: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        },
    )
    state.mapTrackChoice?.let { choice ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMapTrackChoice,
            title = { Text(if (choice.forSplit) "Which track should be split?" else "Select a track") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Several tracks overlap here.")
                    choice.trackIds.forEach { id ->
                        val track = state.document.tracks.firstOrNull { it.id == id }
                        TextButton(onClick = { viewModel.chooseMapTrack(id) }, modifier = Modifier.fillMaxWidth()) {
                            Text(track?.name ?: "Unnamed track")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::dismissMapTrackChoice) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MapPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    selectionProfile: TrackSelectionProfile?,
    onTools: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) viewModel.startLocationTracking(focusWhenAvailable = true)
        else viewModel.onLocationPermissionDenied()
    }
    val showLocation = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.startLocationTracking(focusWhenAvailable = true)
            viewModel.focusCurrentLocation()
        } else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    val draftLines = remember(
        state.document,
        state.routePlanner.active,
        state.routePlanner.candidates,
        state.routePlanner.selectedAlternative,
        state.splitDraft,
        state.joinDraft,
    ) { state.draftLines }
    val draftAnchors = remember(state.routePlanner.anchors, state.splitDraft) { state.draftAnchors }
    Box(Modifier.fillMaxSize()) {
        MapEditor(
            document = state.document,
            styles = state.styles,
            selectedTrackIds = state.selectedTrackIds.ifEmpty { setOfNotNull(state.selectedTrackId) },
            selectedPoint = state.selectedPoint,
            selectedPosition = state.selectedCursor?.position?.point,
            currentLocation = state.currentLocation?.point,
            currentLocationProjection = state.currentLocationProjection?.point,
            onMapTap = when {
                state.routePlanner.active -> viewModel::onRoutePlannerTap
                state.editMode == EditMode.SPLIT -> { _, _ -> viewModel.onSplitEmptyMapTap() }
                else -> viewModel::onMapTap
            },
            onTracksTap = viewModel::onMapTracksTap,
            trackPickingEnabled = !state.routePlanner.active && !state.lassoSelectionActive && state.editMode in setOf(EditMode.SELECT, EditMode.SPLIT),
            lassoSelectionEnabled = state.lassoSelectionActive,
            onLassoSelection = viewModel::selectTracksByLasso,
            focusRequest = state.focusRequest,
            initialCamera = state.camera,
            onCameraIdle = viewModel::onCameraIdle,
            draftLines = draftLines,
            draftAnchors = draftAnchors,
            modifier = Modifier.fillMaxSize(),
        )
        if (state.routePlanner.active) {
            RoutePlannerControls(state, viewModel, Modifier.align(Alignment.TopCenter).padding(10.dp))
        } else {
            if (state.joinDraft == null && state.splitDraft == null) {
                ModeBar(
                    selected = state.editMode,
                    selectionMode = state.selectionMode,
                    onSelected = viewModel::setMode,
                    onPlanRoute = viewModel::openRoutePlanner,
                    onMultiSelect = { if (state.selectionMode) viewModel.exitSelectionMode() else viewModel.enterSelectionMode() },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                )
                if (state.selectionMode) {
                    Surface(
                        Modifier.align(Alignment.TopCenter).padding(top = 76.dp),
                        shape = RoundedCornerShape(20.dp),
                        tonalElevation = 8.dp,
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
                            Text(
                                "${state.selectedTrackIds.size} selected" + selectionProfile?.let { " · ${formatDistance(it.totalDistanceMeters)}" }.orEmpty(),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                TextButton(onClick = viewModel::toggleLassoSelection) { Text(if (state.lassoSelectionActive) "Cancel lasso" else "Lasso") }
                                TextButton(onClick = onMerge, enabled = state.selectedTrackIds.size >= 2) { Text("Merge") }
                                TextButton(onClick = onDelete, enabled = state.selectedTrackIds.isNotEmpty()) { Text("Delete") }
                                TextButton(onClick = viewModel::exitSelectionMode) { Text("Done") }
                            }
                            if (state.lassoSelectionActive) {
                                Text("Draw a closed shape around visible tracks", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                val profileTrack = selectionProfile?.previewTrack ?: state.selectedTrack
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(16.dp)
                        .padding(bottom = if (profileTrack != null) 220.dp else 0.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    FloatingActionButton(onClick = showLocation) { Icon(Icons.Default.MyLocation, "Show current location") }
                    Spacer(Modifier.height(8.dp))
                    FloatingActionButton(onClick = onTools) { Icon(Icons.Default.AutoFixHigh, "Editing tools") }
                }
                profileTrack?.let { track ->
                    TrackProfileCard(
                        track = track,
                        selectedCursor = state.selectedCursor,
                        currentLocationProjection = state.currentLocationProjection,
                        onDistanceSelected = viewModel::selectProfileDistance,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
                        profileTitle = selectionProfile?.let { "${it.sections.size} tracks · optimized merge order" },
                        positionDistanceMeters = selectionProfile?.let { profile -> profile::displayDistance },
                        positionTrackName = selectionProfile?.let { profile -> profile::sourceTrackName },
                    )
                }
            }
        }
        state.splitDraft?.let { draft ->
            SplitPreviewCard(draft.cuts.size, viewModel, Modifier.align(Alignment.BottomStart).padding(16.dp))
        }
        state.joinDraft?.let { draft ->
            JoinPreviewCard(
                draft,
                state.document.tracks.associateBy { it.id },
                state.busy,
                viewModel,
                Modifier.align(Alignment.BottomStart).padding(16.dp),
            )
        }
    }
}

@Composable
private fun ModeBar(
    selected: EditMode,
    selectionMode: Boolean,
    onSelected: (EditMode) -> Unit,
    onPlanRoute: () -> Unit,
    onMultiSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, shape = RoundedCornerShape(24.dp), tonalElevation = 8.dp) {
        LazyRow(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            item { ModeButton("Select", Icons.Default.Straighten, selected == EditMode.SELECT) { onSelected(EditMode.SELECT) } }
            item { ModeButton("Multi", Icons.Default.DoneAll, selectionMode, onMultiSelect) }
            item { ModeButton("Line", Icons.Default.Polyline, selected == EditMode.DRAW_STRAIGHT) { onSelected(EditMode.DRAW_STRAIGHT) } }
            item { ModeButton("Plan", Icons.AutoMirrored.Filled.AltRoute, false, onPlanRoute) }
            item { ModeButton("Move", Icons.Default.EditLocationAlt, selected == EditMode.MOVE) { onSelected(EditMode.MOVE) } }
            item { ModeButton("Split", Icons.Default.ContentCopy, selected == EditMode.SPLIT) { onSelected(EditMode.SPLIT) } }
            item { ModeButton("POI", Icons.Default.AddLocationAlt, selected == EditMode.WAYPOINT) { onSelected(EditMode.WAYPOINT) } }
        }
    }
}

@Composable
private fun ModeButton(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(20.dp)); Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RoutePlannerControls(state: EditorUiState, viewModel: EditorViewModel, modifier: Modifier = Modifier) {
    val draft = state.routePlanner
    Surface(modifier.fillMaxWidth().padding(horizontal = 6.dp), shape = RoundedCornerShape(22.dp), tonalElevation = 10.dp) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Route planner", style = MaterialTheme.typography.titleLarge)
                    Text(
                        when (draft.anchors.size) {
                            0 -> "Tap the map to choose a start"
                            1 -> "Start set · tap an end"
                            else -> "${draft.anchors.size - 2} via point${if (draft.anchors.size == 3) "" else "s"} · new taps add vias before the end"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = viewModel::closeRoutePlanner) { Text("Cancel") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(BicycleProfile.entries) { profile ->
                    FilterChip(
                        selected = state.bicycleProfile == profile,
                        onClick = { viewModel.setProfile(profile) },
                        label = { Text(profile.label) },
                    )
                }
                if (state.selectedTrack != null && draft.anchors.isEmpty()) {
                    item { AssistChip(onClick = viewModel::useSelectedEndpointAsRouteStart, label = { Text("Use track end") }) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::removeLastRouteAnchor, enabled = draft.anchors.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Undo point") }
                OutlinedButton(onClick = viewModel::reverseRouteAnchors, enabled = draft.anchors.size >= 2, modifier = Modifier.weight(1f)) { Text("Reverse") }
                Button(onClick = viewModel::calculateRoutes, enabled = draft.anchors.size >= 2 && !draft.busy, modifier = Modifier.weight(1f)) {
                    if (draft.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Route")
                }
            }
            draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (draft.candidates.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(draft.candidates, key = { it.alternative.name }) { candidate ->
                        val metrics = candidate.path.metrics
                        FilterChip(
                            selected = candidate.alternative == draft.selectedAlternative,
                            onClick = { viewModel.selectRouteCandidate(candidate.alternative) },
                            label = {
                                Text(buildString {
                                    append(candidate.alternative.label)
                                    metrics.distanceMeters?.let { append(" · ${formatDistance(it)}") }
                                    metrics.filteredAscentMeters?.let { append(" · ${it.roundToInt()} m ↑") }
                                })
                            },
                        )
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(selected = draft.applyMode == RouteApplyMode.NEW_TRACK, onClick = { viewModel.setRouteApplyMode(RouteApplyMode.NEW_TRACK) }, label = { Text("New track") }) }
                    if (state.selectedTrack != null) {
                        item { FilterChip(selected = draft.applyMode == RouteApplyMode.APPEND_TO_SELECTED, onClick = { viewModel.setRouteApplyMode(RouteApplyMode.APPEND_TO_SELECTED) }, label = { Text("Append") }) }
                        item { FilterChip(selected = draft.applyMode == RouteApplyMode.PREPEND_TO_SELECTED, onClick = { viewModel.setRouteApplyMode(RouteApplyMode.PREPEND_TO_SELECTED) }, label = { Text("Prepend") }) }
                    }
                    item {
                        Button(onClick = viewModel::applyPlannedRoute, enabled = draft.selectedAlternative != null) { Text("Apply") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitPreviewCard(cutCount: Int, viewModel: EditorViewModel, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), tonalElevation = 10.dp) {
        Column(Modifier.padding(14.dp)) {
            Text("Split preview · $cutCount cut${if (cutCount == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium)
            Text("Tap the map to add cuts. Nothing changes until Apply.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = viewModel::clearSplitDraft) { Text("Cancel") }
                OutlinedButton(onClick = viewModel::undoLastSplitCut) { Text("Undo cut") }
                if (cutCount == 2) OutlinedButton(onClick = viewModel::extractSplitSpan) { Text("Extract span") }
                Button(onClick = viewModel::applySplitDraft) { Text("Apply") }
            }
        }
    }
}

@Composable
private fun JoinPreviewCard(
    draft: JoinDraft,
    tracks: Map<String, GpxTrack>,
    busy: Boolean,
    viewModel: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    val sourceDistance = draft.plan.order.sumOf { reference ->
        tracks[reference.trackId]?.let { ch.cld9.velogpx.engine.GpxAnalytics.statistics(it).distanceMeters } ?: 0.0
    }
    val pendingConnections = draft.plan.edges.any {
        it.strategy == JoinGapStrategy.ROUTED_CONNECTOR && it.routedConnector == null
    }
    val connectionDistance = draft.plan.edges.sumOf { edge ->
        when (edge.strategy) {
            JoinGapStrategy.PRESERVE_SEGMENT_GAP -> 0.0
            JoinGapStrategy.STRAIGHT_CONNECTOR -> edge.gapMeters
            JoinGapStrategy.ROUTED_CONNECTOR -> edge.routedConnector?.let { segment ->
                ch.cld9.velogpx.engine.GpxAnalytics.statistics(GpxTrack(segments = listOf(segment))).distanceMeters
            } ?: 0.0
        }
    }
    Surface(modifier.fillMaxWidth(0.86f), shape = RoundedCornerShape(20.dp), tonalElevation = 10.dp) {
        Column(Modifier.padding(14.dp)) {
            Text("Merge preview · ${draft.plan.order.size} tracks", style = MaterialTheme.typography.titleMedium)
            Text("Endpoint gaps ${formatDistance(draft.plan.totalGapMeters)} · ${draft.plan.order.count { it.reversed }} reversed", style = MaterialTheme.typography.bodySmall)
            Text(
                if (pendingConnections) {
                    "Routes ${formatDistance(sourceDistance)} · routing connections…"
                } else {
                    "Routes ${formatDistance(sourceDistance)} + connections ${formatDistance(connectionDistance)} = ${formatDistance(sourceDistance + connectionDistance)}"
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(draft.plan.order) { ref ->
                    val name = draft.plan.order.indexOf(ref) + 1
                    AssistChip(onClick = {}, label = { Text("$name. ${if (ref.reversed) "↶ " else ""}${tracks[ref.trackId]?.name ?: "Unnamed"}") })
                }
            }
            OutlinedTextField(
                value = draft.name,
                onValueChange = viewModel::setJoinName,
                label = { Text("Output track name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(selected = draft.plan.edges.all { it.strategy == JoinGapStrategy.PRESERVE_SEGMENT_GAP }, onClick = { viewModel.setJoinStrategy(JoinGapStrategy.PRESERVE_SEGMENT_GAP) }, label = { Text("Keep gaps") }, enabled = !busy) }
                item { FilterChip(selected = draft.plan.edges.all { it.strategy == JoinGapStrategy.STRAIGHT_CONNECTOR }, onClick = { viewModel.setJoinStrategy(JoinGapStrategy.STRAIGHT_CONNECTOR) }, label = { Text("Straight") }, enabled = !busy) }
                item { FilterChip(selected = draft.plan.edges.all { it.strategy == JoinGapStrategy.ROUTED_CONNECTOR }, onClick = { viewModel.setJoinStrategy(JoinGapStrategy.ROUTED_CONNECTOR) }, label = { Text("Route gaps") }, enabled = !busy) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = viewModel::cancelJoin) { Text("Cancel") }
                Row(
                    Modifier.clickable(enabled = !busy) { viewModel.setJoinKeepOriginals(!draft.keepOriginals) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = draft.keepOriginals,
                        onCheckedChange = { viewModel.setJoinKeepOriginals(it) },
                        enabled = !busy,
                    )
                    Text("Keep sources", style = MaterialTheme.typography.labelLarge)
                }
                Button(onClick = viewModel::applyJoin, enabled = !busy) { Text(if (busy) "Routing…" else "Merge") }
            }
        }
    }
}

@Composable
private fun LayersPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    selectionProfile: TrackSelectionProfile?,
    listState: LazyListState,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onGroup: () -> Unit,
    onMerge: () -> Unit,
) {
    if (state.document.tracks.isEmpty() && state.document.routes.isEmpty() && state.document.waypoints.isEmpty()) {
        EmptyProject(Modifier.fillMaxSize())
        return
    }
    val collapsedTrackIds = state.groups.filter { it.collapsed }.flatMapTo(mutableSetOf()) { it.layerIds }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item {
            Row(Modifier.fillMaxWidth().padding(20.dp, 12.dp, 12.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Tracks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (state.selectionMode) viewModel.exitSelectionMode() else viewModel.enterSelectionMode() }) {
                    Text(if (state.selectionMode) "Done" else "Select")
                }
                if (state.selectionMode) TextButton(onClick = viewModel::selectAllTracks) { Text("All") }
            }
        }
        if (state.selectionMode) {
            item {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), shape = RoundedCornerShape(18.dp), tonalElevation = 3.dp) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            "${state.selectedTrackIds.size} selected" + selectionProfile?.let { " · ${formatDistance(it.totalDistanceMeters)} total" }.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = viewModel::focusSelectedTracks, enabled = state.selectedTrackIds.isNotEmpty()) { Text("Show on map") }
                            OutlinedButton(onClick = { viewModel.setSelectedTracksVisible(true) }, enabled = state.selectedTrackIds.isNotEmpty()) { Text("Show") }
                            OutlinedButton(onClick = { viewModel.setSelectedTracksVisible(false) }, enabled = state.selectedTrackIds.isNotEmpty()) { Text("Hide") }
                            OutlinedButton(onClick = onGroup, enabled = state.selectedTrackIds.isNotEmpty()) { Text("Group") }
                            OutlinedButton(onClick = onMerge, enabled = state.selectedTrackIds.size >= 2) { Text("Merge") }
                            OutlinedButton(onClick = onShare, enabled = state.selectedTrackIds.isNotEmpty()) { Text("Garmin") }
                            OutlinedButton(onClick = onDelete, enabled = state.selectedTrackIds.isNotEmpty()) { Text("Delete") }
                        }
                    }
                }
            }
        }
        if (state.groups.isNotEmpty()) {
            item { Text("Groups", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            items(state.groups, key = { it.id }) { group ->
                ListItem(
                    headlineContent = { Text(group.name) },
                    supportingContent = { Text("${group.layerIds.size} track${if (group.layerIds.size == 1) "" else "s"}") },
                    leadingContent = { Text(if (group.collapsed) "▸" else "▾") },
                    trailingContent = { IconButton(onClick = { viewModel.deleteGroup(group.id) }) { Icon(Icons.Default.Delete, "Delete group") } },
                    modifier = Modifier.clickable { viewModel.toggleGroupCollapsed(group.id) },
                )
            }
            item { HorizontalDivider() }
        }
        items(state.document.tracks.filterNot { it.id in collapsedTrackIds }, key = { it.id }) { track ->
            val selected = track.id in state.selectedTrackIds || (!state.selectionMode && track.id == state.selectedTrackId)
            val style = state.styles[track.id]
            val group = state.groups.firstOrNull { track.id in it.layerIds }
            ListItem(
                headlineContent = { Text(track.name ?: "Unnamed track", fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                supportingContent = {
                    val stats = ch.cld9.velogpx.engine.GpxAnalytics.statistics(track)
                    Text("${track.segments.size} segment${if (track.segments.size == 1) "" else "s"} · ${formatDistance(stats.distanceMeters)} · ${stats.pointCount} points${group?.let { " · ${it.name}" }.orEmpty()}")
                },
                leadingContent = {
                    if (state.selectionMode) {
                        Checkbox(checked = selected, onCheckedChange = { viewModel.toggleTrackSelection(track.id) })
                    } else Box(Modifier.size(18.dp).background(Color(style?.color ?: 0xFF176B45), CircleShape))
                },
                trailingContent = {
                    Row {
                        IconButton(onClick = { viewModel.toggleTrackVisibility(track.id) }) {
                            Icon(if (style?.visible != false) Icons.Default.Visibility else Icons.Default.VisibilityOff, "Toggle visibility")
                        }
                    }
                },
                modifier = Modifier.combinedClickable(
                    onClick = {
                        if (state.selectionMode) viewModel.toggleTrackSelection(track.id) else viewModel.selectTrack(track.id)
                    },
                    onLongClick = {
                        if (state.selectionMode) viewModel.toggleTrackSelection(track.id)
                        else viewModel.enterSelectionMode(track.id)
                    },
                    onLongClickLabel = "Start selecting tracks",
                )
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent),
            )
            if (selected && !state.selectionMode) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = viewModel::duplicateSelectedTrack) { Text("Duplicate") }
                    OutlinedButton(onClick = { viewModel.moveSelectedTrack(-1) }) { Text("Move up") }
                    OutlinedButton(onClick = { viewModel.moveSelectedTrack(1) }) { Text("Move down") }
                    OutlinedButton(onClick = onShare) { Text("Garmin") }
                    OutlinedButton(onClick = viewModel::deleteSelectedTrack) { Icon(Icons.Default.Delete, null); Text("Delete") }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    listOf(0xFF176B45, 0xFF1565C0, 0xFFD84315, 0xFF6A1B9A, 0xFF00838F, 0xFFAD1457).forEach { color ->
                        Box(
                            Modifier.size(30.dp).background(Color(color), CircleShape)
                                .clickable { viewModel.setTrackColor(track.id, color) },
                        )
                    }
                }
            }
            HorizontalDivider()
        }
        if (state.document.routes.isNotEmpty()) {
            item { Text("GPX routes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(20.dp)) }
            items(state.document.routes, key = { it.id }) { route ->
                ListItem(
                    headlineContent = { Text(route.name ?: "Unnamed route") },
                    supportingContent = { Text("${route.points.size} route points") },
                    trailingContent = {
                        TextButton(onClick = { viewModel.convertSelectedRouteToTrack(route.id) }) { Text("Edit as track") }
                    },
                )
            }
        }
        if (state.document.waypoints.isNotEmpty()) {
            item { Text("Points of interest (${state.document.waypoints.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(20.dp)) }
            items(state.document.waypoints.take(100), key = { it.id }) { point ->
                ListItem(
                    headlineContent = { Text(point.name ?: "Waypoint") },
                    supportingContent = { Text("%.5f, %.5f".format(point.latitude, point.longitude)) },
                    trailingContent = {
                        IconButton(onClick = { viewModel.deleteWaypoint(point.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete ${point.name ?: "waypoint"}")
                        }
                    },
                )
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun EmptyProject(modifier: Modifier = Modifier) {
    Column(modifier.padding(36.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.AutoMirrored.Filled.AltRoute, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text("Build your bicycle tour", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(
            "Import EuroVelo, Komoot, Garmin, or any GPX file. You can also open the map and tap Line or Route to draw from scratch.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ProfilePanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    selectionProfile: TrackSelectionProfile?,
    onTools: () -> Unit,
) {
    val track = selectionProfile?.previewTrack ?: state.selectedTrack
    if (track == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Select a track to inspect its profile.")
        }
        return
    }
    val stats = ch.cld9.velogpx.engine.GpxAnalytics.statistics(track)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(track.name ?: "Track profile", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(14.dp))
        TrackProfileCard(
            track = track,
            selectedCursor = state.selectedCursor,
            currentLocationProjection = state.currentLocationProjection,
            onDistanceSelected = viewModel::selectProfileDistance,
            modifier = Modifier.fillMaxWidth(),
            expanded = true,
            profileTitle = selectionProfile?.let { "${it.sections.size} tracks · optimized merge order" },
            positionDistanceMeters = selectionProfile?.let { profile -> profile::displayDistance },
            positionTrackName = selectionProfile?.let { profile -> profile::sourceTrackName },
        )
        Spacer(Modifier.height(18.dp))
        StatsGrid(stats)
        Spacer(Modifier.height(18.dp))
        Text("Routing profile", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(BicycleProfile.entries) { profile ->
                AssistChip(
                    onClick = { viewModel.setProfile(profile) },
                    label = { Text(profile.label) },
                    leadingIcon = if (state.bicycleProfile == profile) ({ Icon(Icons.AutoMirrored.Filled.AltRoute, null, Modifier.size(16.dp)) }) else null,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        if (selectionProfile == null) {
            Button(onClick = onTools, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.AutoFixHigh, null); Spacer(Modifier.width(8.dp)); Text("Open editing tools") }
        } else {
            Text("Connections are excluded until you choose a Merge method.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StatsGrid(stats: TrackStatistics) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard("Distance", formatDistance(stats.distanceMeters))
        StatCard("Ascent", "${stats.ascentMeters.roundToInt()} m")
        StatCard("Descent", "${stats.descentMeters.roundToInt()} m")
        stats.minimumElevationMeters?.let { StatCard("Elevation", "${it.roundToInt()}–${stats.maximumElevationMeters?.roundToInt()} m") }
        stats.elapsed?.let { StatCard("Elapsed", formatDuration(it)) }
        stats.averageSpeedKph?.let { StatCard("Average", "%.1f km/h".format(it)) }
        StatCard("Points", stats.pointCount.toString())
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransformSheet(
    state: EditorUiState,
    dismiss: () -> Unit,
    split: () -> Unit,
    trimBefore: () -> Unit,
    trimAfter: () -> Unit,
    deletePoint: () -> Unit,
    reverse: () -> Unit,
    simplify: () -> Unit,
    deduplicate: () -> Unit,
    clean: () -> Unit,
    smooth: () -> Unit,
    interpolate: () -> Unit,
    time: () -> Unit,
    stages: () -> Unit,
    guidedJoin: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).padding(bottom = 24.dp)) {
            Text("Edit ${state.selectedTrack?.name ?: "track"}", style = MaterialTheme.typography.headlineSmall)
            Text("Every operation is autosaved and undoable.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            ToolRow("Reverse direction", "Reverse segments and keep time chronological", reverse, state.selectedTrack != null)
            ToolRow("Split at selected point", "Tap Select or Split on the map first", split, state.selectedPoint != null)
            ToolRow("Trim before selection", "Remove everything before the selected point", trimBefore, state.selectedPoint != null)
            ToolRow("Trim after selection", "Remove everything after the selected point", trimAfter, state.selectedPoint != null)
            ToolRow("Delete selected point", "Remove one track point", deletePoint, state.selectedPoint != null)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ToolRow("Simplify geometry", "RDP reduction with protected named/cue points", simplify, state.selectedTrack != null)
            ToolRow("Remove duplicates", "Clean consecutive points within 20 cm", deduplicate, state.selectedTrack != null)
            ToolRow("Remove GPS spikes", "Remove implausible detours over 80 km/h", clean, state.selectedTrack != null)
            ToolRow("Smooth elevation", "Seven-point moving window", smooth, state.selectedTrack != null)
            ToolRow("Fill missing elevation", "Distance-weighted interpolation between known samples", interpolate, state.selectedTrack != null)
            ToolRow("Time tools", "Generate, shift, or clear timestamps", time, state.selectedTrack != null)
            ToolRow("Plan daily stages", "Split into complete day tracks by target distance", stages, state.selectedTrack != null)
            if (state.selectedTrackIds.size > 1) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ToolRow("Merge selected tracks", "Choose direct, separated, or bicycle-routed connections and review before applying", guidedJoin, true)
            }
        }
    }
}

@Composable
private fun ToolRow(title: String, subtitle: String, onClick: () -> Unit, enabled: Boolean) {
    ListItem(
        headlineContent = { Text(title) }, supportingContent = { Text(subtitle) },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun SimplifyDialog(dismiss: () -> Unit, apply: (Double) -> Unit) {
    var value by remember { mutableStateOf("5") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Simplify geometry") },
        text = {
            Column {
                Text("Maximum lateral deviation in metres. Endpoints and semantic cue points are always kept.")
                OutlinedTextField(value, { value = it }, label = { Text("Tolerance (m)") }, singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { value.toDoubleOrNull()?.takeIf { it > 0 }?.let(apply) }, enabled = value.toDoubleOrNull()?.let { it > 0 } == true) { Text("Simplify") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TimeDialog(dismiss: () -> Unit, generate: (Double) -> Unit, shift: (Long) -> Unit, clear: () -> Unit) {
    var speed by remember { mutableStateOf("18") }
    var minutes by remember { mutableStateOf("60") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Time tools") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Generate timestamps from now using a riding speed")
                OutlinedTextField(speed, { speed = it }, label = { Text("Speed (km/h)") }, singleLine = true)
                FilledTonalButton(onClick = { speed.toDoubleOrNull()?.let(generate) }, modifier = Modifier.fillMaxWidth()) { Text("Generate timestamps") }
                Spacer(Modifier.height(16.dp))
                Text("Shift all existing timestamps")
                OutlinedTextField(minutes, { minutes = it }, label = { Text("Minutes (+/−)") }, singleLine = true)
                FilledTonalButton(onClick = { minutes.toLongOrNull()?.let(shift) }, modifier = Modifier.fillMaxWidth()) { Text("Shift time") }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = clear, modifier = Modifier.fillMaxWidth()) { Text("Remove all timestamps") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = dismiss) { Text("Close") } },
    )
}

@Composable
private fun RenameDialog(current: String, dismiss: () -> Unit, apply: (String) -> Unit) {
    var name by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = dismiss, title = { Text("Rename track") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Track name") }, singleLine = true) },
        confirmButton = { Button(onClick = { apply(name.trim()) }, enabled = name.isNotBlank()) { Text("Rename") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun GroupDialog(dismiss: () -> Unit, apply: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Group selected tracks") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Group name") }, singleLine = true) },
        confirmButton = { Button(onClick = { apply(name) }, enabled = name.isNotBlank()) { Text("Create group") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MergeMethodDialog(
    count: Int,
    routeDistanceMeters: Double,
    endpointGapMeters: Double,
    dismiss: () -> Unit,
    apply: (JoinGapStrategy) -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Merge $count tracks") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("VeloGPX will first optimize track order and direction. You can review all endpoint gaps before applying.")
                Text(
                    "Selected routes ${formatDistance(routeDistanceMeters)} · optimized endpoint gaps ${formatDistance(endpointGapMeters)}. Connections are added only if you choose them.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(
                    onClick = { apply(JoinGapStrategy.PRESERVE_SEGMENT_GAP) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column { Text("Preserve gaps"); Text("One track with separate GPX segments", style = MaterialTheme.typography.bodySmall) }
                }
                OutlinedButton(
                    onClick = { apply(JoinGapStrategy.STRAIGHT_CONNECTOR) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column { Text("Connect directly"); Text("Draw straight lines across every gap", style = MaterialTheme.typography.bodySmall) }
                }
                Button(
                    onClick = { apply(JoinGapStrategy.ROUTED_CONNECTOR) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column { Text("Plan bicycle connections"); Text("Use BRouter to fill the gaps", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ShareToGarminDialog(
    state: EditorUiState,
    dismiss: () -> Unit,
    share: (List<GpxShareRequest>) -> Unit,
) {
    val ids = state.selectedTrackIds.ifEmpty { setOfNotNull(state.selectedTrackId) }
    val tracks = state.document.tracks.filter { it.id in ids }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Send to Garmin Connect") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("VeloGPX opens a real .gpx file. This is the Android contract Garmin Connect supports; choose Connect if Android asks which app to use.")
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { share(listOf(GpxShareRequest.Document(state.document))) },
                    enabled = !state.document.isEmpty,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Send whole project") }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        share(
                            if (tracks.size == 1) listOf(GpxShareRequest.Track(state.document, tracks.single().id))
                            else listOf(GpxShareRequest.Tracks(state.document, tracks.mapTo(linkedSetOf()) { it.id }))
                        )
                    },
                    enabled = tracks.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (tracks.size > 1) "Send ${tracks.size} selected tracks" else "Send track") }
                if (tracks.size == 1 && tracks.single().segments.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    Text("Or send one segment", style = MaterialTheme.typography.titleSmall)
                    tracks.single().segments.forEachIndexed { index, segment ->
                        TextButton(
                            onClick = { share(listOf(GpxShareRequest.Segment(state.document, tracks.single().id, segment.id))) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Segment ${index + 1} · ${segment.points.size} points") }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StageDialog(dismiss: () -> Unit, apply: (Double) -> Unit) {
    var kilometers by remember { mutableStateOf("80") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Plan daily stages") },
        text = {
            Column {
                Text("VeloGPX inserts exact boundary points and creates consecutive day tracks without losing route coverage.")
                OutlinedTextField(kilometers, { kilometers = it }, label = { Text("Target distance (km)") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(
                onClick = { kilometers.toDoubleOrNull()?.let(apply) },
                enabled = kilometers.toDoubleOrNull()?.let { it > 1 } == true,
            ) { Text("Create stages") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

private fun exportName(state: EditorUiState, extension: String): String {
    val base = state.projectTitle
        .replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "bicycle-route" }
    return "$base.$extension"
}

private fun formatDistance(meters: Double): String = if (meters < 1000) "${meters.roundToInt()} m" else "%.1f km".format(meters / 1000)
private fun formatDuration(duration: java.time.Duration): String = "%d:%02d".format(duration.toHours(), duration.toMinutes() % 60)
private fun saveStatusLabel(status: ProjectSaveStatus?): String = when (status) {
    is ProjectSaveStatus.Saved -> "saved"
    is ProjectSaveStatus.Pending -> "autosave pending"
    is ProjectSaveStatus.Saving -> "saving"
    is ProjectSaveStatus.Error -> "save failed"
    null -> "opening"
}
