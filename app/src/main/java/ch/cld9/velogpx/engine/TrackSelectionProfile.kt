package ch.cld9.velogpx.engine

import ch.cld9.velogpx.model.GpxTrack

data class TrackSelectionSection(
    val trackId: String,
    val reversed: Boolean,
    val startDistanceMeters: Double,
    val distanceMeters: Double,
)

/**
 * A non-destructive profile of selected tracks in the same optimized order and orientation used by
 * Merge. Geographic gaps between tracks remain GPX segment gaps and are deliberately not counted.
 */
class TrackSelectionProfile internal constructor(
    val previewTrack: GpxTrack,
    val plan: JoinPlan,
    val sections: List<TrackSelectionSection>,
    private val tracksById: Map<String, GpxTrack>,
) {
    val totalDistanceMeters: Double get() = sections.sumOf { it.distanceMeters }

    fun sourcePositionAtDistance(requestedDistanceMeters: Double): TrackPosition? {
        if (sections.isEmpty()) return null
        val target = requestedDistanceMeters.coerceIn(0.0, totalDistanceMeters)
        val section = sections.firstOrNull { target <= it.startDistanceMeters + it.distanceMeters + DISTANCE_EPSILON }
            ?: sections.last()
        val local = (target - section.startDistanceMeters).coerceIn(0.0, section.distanceMeters)
        val sourceDistance = if (section.reversed) section.distanceMeters - local else local
        return tracksById[section.trackId]?.let { TrackPositionEngine.atDistance(it, sourceDistance) }
    }

    fun displayDistance(position: TrackPosition): Double? {
        val section = sections.firstOrNull { it.trackId == position.trackId } ?: return null
        val local = position.distanceAlongMeters.coerceIn(0.0, section.distanceMeters)
        return section.startDistanceMeters + if (section.reversed) section.distanceMeters - local else local
    }

    fun sourceTrackName(trackId: String): String? = tracksById[trackId]?.name

    private companion object {
        const val DISTANCE_EPSILON = 1e-6
    }
}

object TrackSelectionProfileEngine {
    fun build(tracks: List<GpxTrack>, selectedTrackIds: Set<String>): TrackSelectionProfile {
        require(selectedTrackIds.size >= 2) { "A combined profile needs at least two selected tracks" }
        val plan = JoinPlanner.plan(tracks, selectedTrackIds, JoinGapStrategy.PRESERVE_SEGMENT_GAP)
        val tracksById = tracks.associateBy(GpxTrack::id)
        var offset = 0.0
        val sections = plan.order.map { reference ->
            val track = tracksById.getValue(reference.trackId)
            val distance = TrackPositionEngine.profile(track).totalDistanceMeters
            TrackSelectionSection(reference.trackId, reference.reversed, offset, distance).also { offset += distance }
        }
        val segments = plan.order.flatMap { reference ->
            val source = tracksById.getValue(reference.trackId)
            if (!reference.reversed) source.segments
            else source.segments.asReversed().map { segment -> segment.copy(points = segment.points.asReversed()) }
        }
        val signature = plan.order.joinToString("|") { "${it.trackId}:${if (it.reversed) 1 else 0}" }
        val preview = GpxTrack(
            id = "selection-profile:$signature",
            name = "${plan.order.size} selected tracks · optimized merge order",
            segments = segments,
        )
        return TrackSelectionProfile(preview, plan, sections, tracksById)
    }
}
