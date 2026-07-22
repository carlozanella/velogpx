package ch.cld9.velogpx.share

import ch.cld9.velogpx.model.GpxDocument
import ch.cld9.velogpx.model.GpxVersion

/** Geometry to materialize as a standalone GPX file for another application. */
sealed interface GpxShareRequest {
    val document: GpxDocument
    val suggestedFileName: String?

    data class Document(
        override val document: GpxDocument,
        override val suggestedFileName: String? = null,
    ) : GpxShareRequest

    data class Track(
        override val document: GpxDocument,
        val trackId: String,
        override val suggestedFileName: String? = null,
    ) : GpxShareRequest

    /** Several tracks materialized as one GPX file, required by Android's open-file contract. */
    data class Tracks(
        override val document: GpxDocument,
        val trackIds: Set<String>,
        override val suggestedFileName: String? = null,
    ) : GpxShareRequest

    data class Segment(
        override val document: GpxDocument,
        val trackId: String,
        val segmentId: String,
        override val suggestedFileName: String? = null,
    ) : GpxShareRequest
}

internal data class MaterializedGpx(
    val document: GpxDocument,
    val displayName: String,
)

internal fun GpxShareRequest.materialize(): MaterializedGpx = when (this) {
    is GpxShareRequest.Document -> {
        val name = suggestedFileName ?: document.metadata?.name ?: document.sourceName ?: "VeloGPX project"
        MaterializedGpx(document.copy(version = GpxVersion.V1_1), sanitizeGpxFileName(name))
    }

    is GpxShareRequest.Track -> {
        val track = requireNotNull(document.tracks.firstOrNull { it.id == trackId }) {
            "Track $trackId is not part of the document"
        }
        val name = suggestedFileName ?: track.name ?: "Track"
        val selected = document.copy(
            version = GpxVersion.V1_1,
            metadata = document.metadata?.let { it.copy(name = track.name ?: it.name) },
            waypoints = emptyList(),
            routes = emptyList(),
            tracks = listOf(track),
            sourceName = null,
        )
        MaterializedGpx(selected, sanitizeGpxFileName(name))
    }

    is GpxShareRequest.Tracks -> {
        val selectedTracks = document.tracks.filter { it.id in trackIds }
        require(selectedTracks.isNotEmpty()) { "None of the requested tracks are part of the document" }
        require(selectedTracks.size == trackIds.size) { "At least one requested track is missing" }
        val name = suggestedFileName ?: document.metadata?.name ?: "Selected routes"
        val selected = document.copy(
            version = GpxVersion.V1_1,
            metadata = document.metadata?.copy(name = name.removeGpxSuffix()),
            waypoints = emptyList(),
            routes = emptyList(),
            tracks = selectedTracks,
            sourceName = null,
        )
        MaterializedGpx(selected, sanitizeGpxFileName(name))
    }

    is GpxShareRequest.Segment -> {
        val track = requireNotNull(document.tracks.firstOrNull { it.id == trackId }) {
            "Track $trackId is not part of the document"
        }
        val segmentIndex = track.segments.indexOfFirst { it.id == segmentId }
        require(segmentIndex >= 0) { "Segment $segmentId is not part of track $trackId" }
        val segmentName = suggestedFileName ?: "${track.name ?: "Track"} - segment ${segmentIndex + 1}"
        val displayName = sanitizeGpxFileName(segmentName)
        val selectedTrack = track.copy(name = displayName.removeGpxSuffix(), segments = listOf(track.segments[segmentIndex]))
        val selected = document.copy(
            version = GpxVersion.V1_1,
            metadata = document.metadata?.copy(name = selectedTrack.name),
            waypoints = emptyList(),
            routes = emptyList(),
            tracks = listOf(selectedTrack),
            sourceName = null,
        )
        MaterializedGpx(selected, displayName)
    }
}

internal fun sanitizeGpxFileName(value: String, maxBaseLength: Int = 96): String {
    require(maxBaseLength > 0) { "Maximum file-name length must be positive" }
    val base = value.removeGpxSuffix()
        .map { character ->
            when {
                character.isISOControl() -> '_'
                character in INVALID_FILE_NAME_CHARACTERS -> '_'
                else -> character
            }
        }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .replace(Regex("_+"), "_")
        .trim(' ', '.', '_')
        .take(maxBaseLength)
        .trimEnd(' ', '.', '_')
        .ifBlank { "route" }
    return "$base.gpx"
}

private fun String.removeGpxSuffix(): String = trim().replace(Regex("(?i)\\.gpx$"), "")

private val INVALID_FILE_NAME_CHARACTERS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
