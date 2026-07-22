package ch.cld9.velogpx.ui

/** Clears only map selection state; active tools and multi-select mode remain unchanged. */
internal fun EditorUiState.withMapSelectionCleared(): EditorUiState = copy(
    selectedTrackId = null,
    selectedTrackIds = emptySet(),
    selectedPoint = null,
    selectedCursor = null,
    currentLocationProjection = null,
)
