package ch.cld9.velogpx.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import ch.cld9.velogpx.model.GpxTrack
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TracksListRevealEffectTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun deepSelectedTrackIsScrolledIntoTheVisibleViewport() {
        val tracks = (1..40).map { index -> GpxTrack(id = "track-$index", name = "Track $index") }
        val targetId = "track-35"
        lateinit var listState: LazyListState

        composeRule.setContent {
            listState = rememberLazyListState()
            Box(Modifier.height(240.dp)) {
                LazyColumn(state = listState) {
                    item { Box(Modifier.fillMaxWidth().height(48.dp)) }
                    items(tracks, key = GpxTrack::id) { Box(Modifier.fillMaxWidth().height(48.dp)) }
                }
                TracksListRevealEffect(
                    request = TrackListFocusRequest(1, targetId),
                    tracks = tracks,
                    groups = emptyList(),
                    selectionMode = false,
                    listState = listState,
                )
            }
        }

        val targetIndex = requireNotNull(
            TracksListLayout.itemIndex(tracks, emptyList(), selectionMode = false, trackId = targetId),
        )
        composeRule.waitUntil(timeoutMillis = 5_000) {
            listState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
        }
        composeRule.runOnIdle {
            assertTrue(listState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex })
        }
    }
}
