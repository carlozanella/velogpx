package ch.cld9.velogpx.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ch.cld9.velogpx.data.project.ProjectSummary
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Local-project browser. All persistence and asynchronous feedback stays with the caller.
 */
@Composable
fun ProjectBrowserDialog(
    state: EditorUiState,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onOpen: (String) -> Unit,
    onRenameCurrent: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSnapshot: () -> Unit,
) {
    var prompt by remember { mutableStateOf<ProjectBrowserPrompt?>(null) }
    val actionsEnabled = !state.loadingProject && !state.busy
    val projects = remember(state.projects, state.projectId) {
        state.projects.sortedWith(
            compareByDescending<ProjectSummary> { it.id == state.projectId }
                .thenByDescending { it.lastOpenedAt },
        )
    }

    LaunchedEffect(state.projects) {
        val deletePrompt = prompt as? ProjectBrowserPrompt.Delete
        if (deletePrompt != null && state.projects.none { it.id == deletePrompt.project.id }) prompt = null
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 720.dp).imePadding(),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
        ) {
            Column {
                ProjectBrowserHeader(
                    projectCount = projects.size,
                    loading = state.loadingProject,
                    actionsEnabled = actionsEnabled,
                    hasCurrentProject = state.projectId != null,
                    onNewProject = { prompt = ProjectBrowserPrompt.Create },
                    onSnapshot = onSnapshot,
                    onDismiss = onDismiss,
                )
                HorizontalDivider()
                if (projects.isEmpty()) {
                    EmptyProjectBrowser(
                        enabled = actionsEnabled,
                        onCreate = { prompt = ProjectBrowserPrompt.Create },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    ) {
                        items(projects, key = ProjectSummary::id) { project ->
                            val current = project.id == state.projectId
                            ProjectBrowserRow(
                                project = project,
                                current = current,
                                enabled = actionsEnabled,
                                onOpen = {
                                    if (current) onDismiss() else {
                                        onOpen(project.id)
                                        onDismiss()
                                    }
                                },
                                onRename = if (current) {
                                    { prompt = ProjectBrowserPrompt.Rename(project.title) }
                                } else null,
                                onDuplicate = {
                                    onDuplicate(project.id)
                                    onDismiss()
                                },
                                onDelete = { prompt = ProjectBrowserPrompt.Delete(project) },
                            )
                        }
                    }
                }
                HorizontalDivider()
                Text(
                    text = "Projects are stored locally on this device and autosaved while you edit.",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    when (val activePrompt = prompt) {
        ProjectBrowserPrompt.Create -> ProjectNameDialog(
            title = "New project",
            explanation = "Create a separate workspace for another tour or route assembly.",
            initialName = "Untitled bicycle tour",
            confirmationLabel = "Create project",
            onDismiss = { prompt = null },
            onConfirm = { title ->
                prompt = null
                onCreate(title)
                onDismiss()
            },
        )

        is ProjectBrowserPrompt.Rename -> ProjectNameDialog(
            title = "Rename project",
            explanation = "The GPX tracks inside the project keep their existing names.",
            initialName = activePrompt.currentName,
            confirmationLabel = "Rename project",
            onDismiss = { prompt = null },
            onConfirm = { title ->
                prompt = null
                onRenameCurrent(title)
            },
        )

        is ProjectBrowserPrompt.Delete -> DeleteProjectDialog(
            project = activePrompt.project,
            current = activePrompt.project.id == state.projectId,
            onDismiss = { prompt = null },
            onConfirm = {
                prompt = null
                onDelete(activePrompt.project.id)
            },
        )

        null -> Unit
    }
}

@Composable
private fun ProjectBrowserHeader(
    projectCount: Int,
    loading: Boolean,
    actionsEnabled: Boolean,
    hasCurrentProject: Boolean,
    onNewProject: () -> Unit,
    onSnapshot: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.padding(start = 20.dp, top = 14.dp, end = 8.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Projects", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "$projectCount ${if (projectCount == 1) "project" else "projects"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loading) CircularProgressIndicator(Modifier.padding(10.dp).size(22.dp), strokeWidth = 2.dp)
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close projects") }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            if (hasCurrentProject) {
                FilledTonalButton(onClick = onSnapshot, enabled = actionsEnabled) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Snapshot")
                }
            }
            Button(onClick = onNewProject, enabled = actionsEnabled) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("New project")
            }
        }
    }
}

@Composable
private fun ProjectBrowserRow(
    project: ProjectSummary,
    current: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRename: (() -> Unit)?,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember(project.id) { mutableStateOf(false) }
    val container = if (current) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    else MaterialTheme.colorScheme.surface
    val border = if (current) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        color = container,
        border = border,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(14.dp),
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (current) Icons.Default.FolderOpen else Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (current) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = project.title,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (current) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Current project",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = projectContentSummary(project),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Updated ${formatProjectTime(project.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, enabled = enabled) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Actions for ${project.title}")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(if (current) "Return to editor" else "Open") },
                        leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                        onClick = { menuExpanded = false; onOpen() },
                    )
                    if (onRename != null) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menuExpanded = false; onRename() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = { menuExpanded = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text("Move to trash", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyProjectBrowser(enabled: Boolean, onCreate: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(36.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Folder, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text("No projects yet", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Create a project to start assembling your bicycle route.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Button(onClick = onCreate, enabled = enabled, modifier = Modifier.padding(top = 8.dp)) { Text("Create project") }
    }
}

@Composable
private fun ProjectNameDialog(
    title: String,
    explanation: String,
    initialName: String,
    confirmationLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val valid = name.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(explanation)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Project name") },
                    singleLine = true,
                    isError = !valid,
                    supportingText = if (!valid) ({ Text("Enter a project name") }) else null,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim()) }, enabled = valid) { Text(confirmationLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteProjectDialog(
    project: ProjectSummary,
    current: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Move project to trash?") },
        text = {
            Text(
                buildString {
                    append("\u201c${project.title}\u201d contains ${projectContentSummary(project)}. ")
                    append("It will be removed from this project list.")
                    if (current) append(" Another project will open automatically.")
                },
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("Move to trash") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private sealed interface ProjectBrowserPrompt {
    data object Create : ProjectBrowserPrompt
    data class Rename(val currentName: String) : ProjectBrowserPrompt
    data class Delete(val project: ProjectSummary) : ProjectBrowserPrompt
}

private fun projectContentSummary(project: ProjectSummary): String {
    val number = NumberFormat.getIntegerInstance()
    val geometry = buildList {
        if (project.trackCount > 0) add("${number.format(project.trackCount)} ${if (project.trackCount == 1) "track" else "tracks"}")
        if (project.routeCount > 0) add("${number.format(project.routeCount)} ${if (project.routeCount == 1) "route" else "routes"}")
        if (project.waypointCount > 0) add("${number.format(project.waypointCount)} ${if (project.waypointCount == 1) "POI" else "POIs"}")
    }
    val contents = geometry.ifEmpty { listOf("empty project") }.joinToString(" \u00b7 ")
    return "$contents \u00b7 ${number.format(project.pointCount)} ${if (project.pointCount == 1) "point" else "points"}"
}

private fun formatProjectTime(instant: Instant): String = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault())
    .format(instant)
