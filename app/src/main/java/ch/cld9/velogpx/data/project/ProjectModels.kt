package ch.cld9.velogpx.data.project

import ch.cld9.velogpx.model.GpxDocument
import ch.cld9.velogpx.model.GpxMetadata
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.TrackStyle
import java.time.Instant
import java.util.UUID

const val PROJECT_FORMAT = "ch.cld9.velogpx.project"
const val PROJECT_SCHEMA_VERSION = 1
const val PROJECT_MIME_TYPE = "application/vnd.cld9.velogpx.project+zip"

data class ProjectCamera(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val bearing: Double = 0.0,
    val pitch: Double = 0.0,
)

data class ProjectSelection(
    val trackId: String,
    val segmentIndex: Int,
    val pointIndex: Int,
    val latitudeE7: Long,
    val longitudeE7: Long,
    val timeEpochMillis: Long? = null,
)

data class ProjectLayerGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val layerIds: List<String> = emptyList(),
    val collapsed: Boolean = false,
)

data class ProjectEditorState(
    val layerOrder: List<String> = emptyList(),
    val styles: Map<String, TrackStyle> = emptyMap(),
    val groups: List<ProjectLayerGroup> = emptyList(),
    val selectedTrackId: String? = null,
    val selectedTrackIds: List<String> = emptyList(),
    val selectedPoint: ProjectSelection? = null,
    val camera: ProjectCamera? = null,
    val routingProfileId: String = "trekking",
    val panelId: String = "MAP",
    val layersScrollIndex: Int = 0,
    val layersScrollOffset: Int = 0,
)

data class ProjectState(
    val id: String,
    val title: String,
    val document: GpxDocument,
    val editor: ProjectEditorState,
    val revision: Long,
    val documentRevision: Long,
    val lastExportedDocumentRevision: Long? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isDocumentDirty: Boolean get() = lastExportedDocumentRevision != documentRevision

    fun nextRevision(
        document: GpxDocument = this.document,
        editor: ProjectEditorState = this.editor,
        title: String = this.title,
        documentChanged: Boolean = document != this.document,
        now: Instant = Instant.now(),
    ): ProjectState = copy(
        title = title,
        document = document,
        editor = editor,
        revision = revision + 1,
        documentRevision = documentRevision + if (documentChanged) 1 else 0,
        updatedAt = now,
    )

    companion object {
        fun create(
            title: String,
            document: GpxDocument = GpxDocument(metadata = GpxMetadata(name = title)),
            now: Instant = Instant.now(),
            id: String = UUID.randomUUID().toString(),
        ): ProjectState = ProjectState(
            id = id,
            title = title,
            document = document,
            editor = defaultEditorState(document),
            revision = 1,
            documentRevision = 1,
            createdAt = now,
            updatedAt = now,
        )
    }
}

private val DEFAULT_PROJECT_COLORS = listOf(
    0xFF176B45, 0xFF1565C0, 0xFFD84315, 0xFF6A1B9A,
    0xFF00838F, 0xFFAD1457, 0xFF5D4037, 0xFF455A64,
)

fun defaultEditorState(document: GpxDocument) = ProjectEditorState(
    layerOrder = document.tracks.map(GpxTrack::id),
    styles = document.tracks.mapIndexed { index, track ->
        track.id to TrackStyle(DEFAULT_PROJECT_COLORS[index % DEFAULT_PROJECT_COLORS.size])
    }.toMap(),
    selectedTrackId = document.tracks.firstOrNull()?.id,
    selectedTrackIds = document.tracks.firstOrNull()?.let { listOf(it.id) }.orEmpty(),
)

data class ProjectSummary(
    val id: String,
    val title: String,
    val revision: Long,
    val documentRevision: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastOpenedAt: Instant,
    val trackCount: Int,
    val routeCount: Int,
    val waypointCount: Int,
    val pointCount: Int,
)

data class ProjectCatalog(
    val schemaVersion: Int = PROJECT_SCHEMA_VERSION,
    val lastProjectId: String? = null,
    val projects: List<ProjectSummary> = emptyList(),
)

enum class SnapshotPolicy { NONE, AUTOMATIC, FORCE }

enum class ProjectRecoverySource { CURRENT, SNAPSHOT, LEGACY_AUTOSAVE, NEW_PROJECT }

data class ProjectOpenResult(
    val project: ProjectState,
    val source: ProjectRecoverySource = ProjectRecoverySource.CURRENT,
    val warnings: List<String> = emptyList(),
)

class ProjectFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal fun ProjectState.summary(lastOpenedAt: Instant = updatedAt) = ProjectSummary(
    id = id,
    title = title,
    revision = revision,
    documentRevision = documentRevision,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastOpenedAt = lastOpenedAt,
    trackCount = document.tracks.size,
    routeCount = document.routes.size,
    waypointCount = document.waypoints.size,
    pointCount = document.pointCount,
)
