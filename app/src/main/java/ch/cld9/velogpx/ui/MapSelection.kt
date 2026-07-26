package ch.cld9.velogpx.ui

import ch.cld9.velogpx.model.GpxDocument
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.TrackStyle

/** Clears only map selection state; active tools and multi-select mode remain unchanged. */
internal fun EditorUiState.withMapSelectionCleared(): EditorUiState = copy(
    selectedTrackId = null,
    selectedTrackIds = emptySet(),
    selectedPoint = null,
    selectedCursor = null,
    currentLocationProjection = null,
)

/** Selection overlays must never reveal a track hidden by its track or group visibility. */
internal fun visibleSelectedTrack(
    document: GpxDocument,
    styles: Map<String, TrackStyle>,
    selectedTrackIds: Set<String>,
): GpxTrack? {
    val selectedId = selectedTrackIds.firstOrNull() ?: return null
    if (styles[selectedId]?.visible == false) return null
    return document.tracks.firstOrNull { it.id == selectedId }
}
