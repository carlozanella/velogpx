@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ch.cld9.velogpx.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.cld9.velogpx.engine.ReverseTimePolicy
import ch.cld9.velogpx.engine.TrackStatistics
import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxVersion
import ch.cld9.velogpx.routing.BicycleProfile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: EditorViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var toolsOpen by remember { mutableStateOf(false) }
    var simplifyDialog by remember { mutableStateOf(false) }
    var timeDialog by remember { mutableStateOf(false) }
    var stagesDialog by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }
    var exportVersion by remember { mutableStateOf(GpxVersion.V1_1) }
    var exportSelectedOnly by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importUris(uris)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        uri?.let { if (exportSelectedOnly) viewModel.exportSelected(it, exportVersion) else viewModel.export(it, exportVersion) }
    }
    val zipExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { viewModel.exportTrackBundle(it, exportVersion) }
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
                        Text(state.document.metadata?.name ?: "VeloGPX", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${state.document.tracks.size} tracks · ${state.document.pointCount} points${if (state.dirty) " · autosaved" else ""}",
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
                        DropdownMenuItem(text = { Text("New project") }, onClick = { menuOpen = false; confirmNew = true })
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
                EditorPanel.MAP -> MapPanel(state, viewModel, onTools = { toolsOpen = true })
                EditorPanel.PROFILE -> ProfilePanel(state, viewModel, onTools = { toolsOpen = true })
                EditorPanel.LAYERS -> LayersPanel(state, viewModel)
            }
            if (state.busy) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                    shape = RoundedCornerShape(24.dp), tonalElevation = 8.dp,
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp)); Text("Working…")
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
        autoOrder = { viewModel.autoOrderAndOrientTracks(); toolsOpen = false },
        mergeSegments = { viewModel.mergeAll(false); toolsOpen = false },
        stitch = { viewModel.mergeAll(true); toolsOpen = false },
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
    if (confirmNew) AlertDialog(
        onDismissRequest = { confirmNew = false },
        title = { Text("Start a new project?") },
        text = { Text("The current project is autosaved, but starting over will clear that recovery copy. Export anything you want to keep first.") },
        confirmButton = { Button(onClick = { viewModel.newProject(); confirmNew = false }) { Text("Start new") } },
        dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("Cancel") } },
    )
}

@Composable
private fun MapPanel(state: EditorUiState, viewModel: EditorViewModel, onTools: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        MapEditor(
            document = state.document,
            styles = state.styles,
            selectedTrackId = state.selectedTrackId,
            selectedPoint = state.selectedPoint,
            onMapTap = viewModel::onMapTap,
            modifier = Modifier.fillMaxSize(),
        )
        ModeBar(
            selected = state.editMode,
            onSelected = viewModel::setMode,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
        )
        Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalAlignment = Alignment.End) {
            state.selectedStatistics?.let { stats ->
                Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
                    Text("${formatDistance(stats.distanceMeters)} · ${stats.pointCount} pts", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(8.dp))
            }
            FloatingActionButton(onClick = onTools) { Icon(Icons.Default.AutoFixHigh, "Editing tools") }
        }
        if (state.editMode == EditMode.DRAW_ROUTED) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 86.dp),
                shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp,
            ) {
                LazyRow(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(BicycleProfile.entries) { profile ->
                        AssistChip(
                            onClick = { viewModel.setProfile(profile) },
                            label = { Text(profile.label) },
                            leadingIcon = if (state.bicycleProfile == profile) ({ Icon(Icons.AutoMirrored.Filled.AltRoute, null, Modifier.size(16.dp)) }) else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeBar(selected: EditMode, onSelected: (EditMode) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(24.dp), tonalElevation = 8.dp) {
        LazyRow(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            item { ModeButton("Select", Icons.Default.Straighten, selected == EditMode.SELECT) { onSelected(EditMode.SELECT) } }
            item { ModeButton("Line", Icons.Default.Polyline, selected == EditMode.DRAW_STRAIGHT) { onSelected(EditMode.DRAW_STRAIGHT) } }
            item { ModeButton("Route", Icons.AutoMirrored.Filled.AltRoute, selected == EditMode.DRAW_ROUTED) { onSelected(EditMode.DRAW_ROUTED) } }
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
private fun LayersPanel(state: EditorUiState, viewModel: EditorViewModel) {
    if (state.document.tracks.isEmpty() && state.document.routes.isEmpty() && state.document.waypoints.isEmpty()) {
        EmptyProject(Modifier.fillMaxSize())
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("Tracks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp, 18.dp, 20.dp, 6.dp))
        }
        items(state.document.tracks, key = { it.id }) { track ->
            val selected = track.id == state.selectedTrackId
            val style = state.styles[track.id]
            ListItem(
                headlineContent = { Text(track.name ?: "Unnamed track", fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                supportingContent = {
                    val stats = ch.cld9.velogpx.engine.GpxAnalytics.statistics(track)
                    Text("${track.segments.size} segment${if (track.segments.size == 1) "" else "s"} · ${formatDistance(stats.distanceMeters)} · ${stats.pointCount} points")
                },
                leadingContent = {
                    Box(Modifier.size(18.dp).background(Color(style?.color ?: 0xFF176B45), CircleShape))
                },
                trailingContent = {
                    Row {
                        IconButton(onClick = { viewModel.toggleTrackVisibility(track.id) }) {
                            Icon(if (style?.visible != false) Icons.Default.Visibility else Icons.Default.VisibilityOff, "Toggle visibility")
                        }
                    }
                },
                modifier = Modifier.clickable { viewModel.selectTrack(track.id) }
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent),
            )
            if (selected) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = viewModel::duplicateSelectedTrack) { Text("Duplicate") }
                    OutlinedButton(onClick = { viewModel.moveSelectedTrack(-1) }) { Text("Move up") }
                    OutlinedButton(onClick = { viewModel.moveSelectedTrack(1) }) { Text("Move down") }
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
private fun ProfilePanel(state: EditorUiState, viewModel: EditorViewModel, onTools: () -> Unit) {
    val track = state.selectedTrack
    if (track == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Select a track to inspect its profile.")
        }
        return
    }
    val stats = state.selectedStatistics!!
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(track.name ?: "Track profile", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(14.dp))
        ElevationChart(track.segments, Modifier.fillMaxWidth().height(220.dp))
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
        Button(onClick = onTools, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.AutoFixHigh, null); Spacer(Modifier.width(8.dp)); Text("Open editing tools") }
    }
}

@Composable
private fun ElevationChart(segments: List<ch.cld9.velogpx.model.GpxTrackSegment>, modifier: Modifier = Modifier) {
    data class Sample(val distance: Double, val elevation: Double)
    val runs = remember(segments) {
        buildList<List<Sample>> {
            var cumulative = 0.0
            segments.forEach { segment ->
                var run = mutableListOf<Sample>()
                segment.points.forEachIndexed { index, point ->
                    if (index > 0) cumulative += ch.cld9.velogpx.engine.GeoMath.distanceMeters(segment.points[index - 1], point)
                    if (point.elevation == null) {
                        if (run.isNotEmpty()) add(run)
                        run = mutableListOf()
                    } else run += Sample(cumulative, point.elevation)
                }
                if (run.isNotEmpty()) add(run)
            }
        }
    }
    val samples = runs.flatten()
    val elevations = samples.map { it.elevation }
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)) {
        if (elevations.size < 2) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No elevation profile in this track") }
            return@Surface
        }
        val minimum = elevations.min()
        val maximum = elevations.max()
        val range = (maximum - minimum).coerceAtLeast(1.0)
        val line = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxSize().padding(16.dp)) {
            val totalDistance = samples.maxOf { it.distance }.coerceAtLeast(1.0)
            runs.filter { it.size >= 2 }.forEach { run ->
                val path = Path()
                run.forEachIndexed { index, sample ->
                    val x = (sample.distance / totalDistance).toFloat() * size.width
                    val y = size.height - ((sample.elevation - minimum) / range).toFloat() * size.height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = line, style = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            }
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
    autoOrder: () -> Unit,
    mergeSegments: () -> Unit,
    stitch: () -> Unit,
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
            if (state.document.tracks.size > 1) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ToolRow("Auto-order and orient", "Greedily minimize endpoint gaps, then review", autoOrder, true)
                ToolRow("Combine as segments", "Preserve gaps between every source", mergeSegments, true)
                ToolRow("Stitch continuously", "Create explicit connecting edges", stitch, true)
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
    val base = (state.document.metadata?.name ?: "bicycle-route")
        .replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "bicycle-route" }
    return "$base.$extension"
}

private fun formatDistance(meters: Double): String = if (meters < 1000) "${meters.roundToInt()} m" else "%.1f km".format(meters / 1000)
private fun formatDuration(duration: java.time.Duration): String = "%d:%02d".format(duration.toHours(), duration.toMinutes() % 60)
