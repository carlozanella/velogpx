package ch.cld9.velogpx.ui

import ch.cld9.velogpx.data.project.ProjectLayerGroup
import ch.cld9.velogpx.model.GpxTrack

/** Deterministic layout calculations shared by the Tracks panel and its reveal behavior. */
internal object TracksListLayout {
    fun collapsedTrackIds(groups: List<ProjectLayerGroup>): Set<String> =
        groups.filter(ProjectLayerGroup::collapsed).flatMapTo(mutableSetOf(), ProjectLayerGroup::layerIds)

    fun visibleTracks(tracks: List<GpxTrack>, groups: List<ProjectLayerGroup>): List<GpxTrack> {
        val collapsedIds = collapsedTrackIds(groups)
        return tracks.filterNot { it.id in collapsedIds }
    }

    /** LazyColumn item index for a visible track row. */
    fun itemIndex(
        tracks: List<GpxTrack>,
        groups: List<ProjectLayerGroup>,
        selectionMode: Boolean,
        trackId: String,
    ): Int? {
        val trackOffset = visibleTracks(tracks, groups).indexOfFirst { it.id == trackId }
        if (trackOffset < 0) return null
        val selectionSummaryItems = if (selectionMode) 1 else 0
        val groupItems = if (groups.isEmpty()) 0 else groups.size + 2 // heading + rows + divider
        return 1 + selectionSummaryItems + groupItems + trackOffset // Tracks heading is item zero.
    }

    /** Expands only collapsed groups that would otherwise hide the requested track. */
    fun revealTrack(groups: List<ProjectLayerGroup>, trackId: String): List<ProjectLayerGroup> =
        groups.map { group ->
            if (group.collapsed && trackId in group.layerIds) group.copy(collapsed = false) else group
        }

    /** Creates one reveal request only while crossing into Tracks from another panel. */
    fun enterPanel(
        state: EditorUiState,
        panel: EditorPanel,
        nextGeneration: () -> Long,
    ): EditorUiState {
        val enteringTracks = panel == EditorPanel.LAYERS && state.panel != EditorPanel.LAYERS
        val selectedId = state.selectedTrackId?.takeIf { id -> state.document.tracks.any { it.id == id } }
        return state.copy(
            panel = panel,
            lassoSelectionActive = state.lassoSelectionActive && panel == EditorPanel.MAP,
            groups = if (enteringTracks && selectedId != null) revealTrack(state.groups, selectedId) else state.groups,
            trackListFocusRequest = if (enteringTracks && selectedId != null) {
                TrackListFocusRequest(nextGeneration(), selectedId)
            } else state.trackListFocusRequest,
        )
    }
}
