package ch.cld9.velogpx.ui

import ch.cld9.velogpx.data.project.ProjectTrackGroup
import ch.cld9.velogpx.model.GpxTrack

/** Deterministic layout calculations shared by the Tracks panel and its reveal behavior. */
internal object TracksListLayout {
    fun collapsedTrackIds(groups: List<ProjectTrackGroup>): Set<String> =
        groups.filter(ProjectTrackGroup::collapsed).flatMapTo(mutableSetOf(), ProjectTrackGroup::trackIds)

    fun groupTracks(tracks: List<GpxTrack>, group: ProjectTrackGroup): List<GpxTrack> {
        val memberIds = group.trackIds.toSet()
        return tracks.filter { it.id in memberIds }
    }

    fun visibleTracks(tracks: List<GpxTrack>, groups: List<ProjectTrackGroup>): List<GpxTrack> =
        groups.filterNot(ProjectTrackGroup::collapsed).flatMap { groupTracks(tracks, it) }

    /** LazyColumn item index for a visible track row. */
    fun itemIndex(
        tracks: List<GpxTrack>,
        groups: List<ProjectTrackGroup>,
        selectionMode: Boolean,
        trackId: String,
    ): Int? {
        if (groups.isEmpty()) {
            val trackIndex = tracks.indexOfFirst { it.id == trackId }
            return trackIndex.takeIf { it >= 0 }?.let { 1 + if (selectionMode) 1 + it else it }
        }
        var index = 1 + if (selectionMode) 1 else 0 // Tracks heading, then optional selection summary.
        groups.forEach { group ->
            index++ // Group header.
            val memberIndex = groupTracks(tracks, group).indexOfFirst { it.id == trackId }
            if (memberIndex >= 0) return if (group.collapsed) null else index + memberIndex
            if (!group.collapsed) index += groupTracks(tracks, group).size
        }
        return null
    }

    /** Expands only collapsed groups that would otherwise hide the requested track. */
    fun revealTrack(groups: List<ProjectTrackGroup>, trackId: String): List<ProjectTrackGroup> =
        groups.map { group ->
            if (group.collapsed && trackId in group.trackIds) group.copy(collapsed = false) else group
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
