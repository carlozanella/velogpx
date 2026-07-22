package ch.cld9.velogpx.engine

import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import java.util.UUID
import kotlin.math.abs

enum class JoinGapStrategy {
    /** Keep a GPX segment boundary and do not count the geographic gap as travelled distance. */
    PRESERVE_SEGMENT_GAP,

    /** Add a two-point connector segment. */
    STRAIGHT_CONNECTOR,

    /** Add a caller-supplied routed connector segment. */
    ROUTED_CONNECTOR,
}

data class OrientedTrackRef(
    val trackId: String,
    val reversed: Boolean,
)

data class JoinEdgeSummary(
    val from: OrientedTrackRef,
    val to: OrientedTrackRef,
    val gapMeters: Double,
    val strategy: JoinGapStrategy,
    val routedConnector: GpxTrackSegment? = null,
)

data class JoinConstraints(
    val fixedStartTrackId: String? = null,
    val fixedEndTrackId: String? = null,
    val lockedOrientation: Map<String, Boolean> = emptyMap(),
)

data class JoinPlan(
    val order: List<OrientedTrackRef>,
    val edges: List<JoinEdgeSummary>,
    val totalGapMeters: Double,
    val exact: Boolean,
    val excludedTrackIds: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    fun withGapStrategy(edgeIndex: Int, strategy: JoinGapStrategy): JoinPlan {
        require(edgeIndex in edges.indices)
        return copy(edges = edges.mapIndexed { index, edge ->
            if (index == edgeIndex) edge.copy(
                strategy = strategy,
                routedConnector = edge.routedConnector.takeIf { strategy == JoinGapStrategy.ROUTED_CONNECTOR },
            ) else edge
        })
    }

    fun withRoutedConnector(edgeIndex: Int, connector: GpxTrackSegment): JoinPlan {
        require(edgeIndex in edges.indices)
        require(connector.points.size >= 2) { "A routed connector needs at least two points" }
        return copy(edges = edges.mapIndexed { index, edge ->
            if (index == edgeIndex) edge.copy(
                strategy = JoinGapStrategy.ROUTED_CONNECTOR,
                routedConnector = connector,
            ) else edge
        })
    }
}

/** Pure endpoint-ordering and orientation planner. It never modifies a source track. */
object JoinPlanner {
    private const val DEFAULT_EXACT_LIMIT = 12
    private const val COST_EPSILON_METERS = 1e-6

    fun plan(
        tracks: List<GpxTrack>,
        selectedTrackIds: Set<String> = tracks.mapTo(linkedSetOf()) { it.id },
        strategy: JoinGapStrategy = JoinGapStrategy.PRESERVE_SEGMENT_GAP,
        constraints: JoinConstraints = JoinConstraints(),
        exactLimit: Int = DEFAULT_EXACT_LIMIT,
    ): JoinPlan {
        require(selectedTrackIds.isNotEmpty()) { "Select at least one track to join" }
        require(exactLimit >= 1) { "The exact planning limit must be positive" }

        val tracksById = tracks.associateBy { it.id }
        val missing = selectedTrackIds.filterNot(tracksById::containsKey).sorted()
        val selected = selectedTrackIds.mapNotNull(tracksById::get)
        val usable = selected.filter { endpoints(it) != null }.sortedBy { it.id }
        val empty = selected.filter { endpoints(it) == null }.map { it.id }.sorted()
        require(usable.isNotEmpty()) { "Selected tracks contain no points" }

        validateConstraints(usable, constraints)
        val order = if (usable.size <= exactLimit) {
            exactOrder(usable, constraints)
        } else {
            greedyOrder(usable, constraints)
        }
        val warnings = buildList {
            if (missing.isNotEmpty()) add("${missing.size} selected track ID(s) were not present in the document.")
            if (empty.isNotEmpty()) add("${empty.size} empty selected track(s) were excluded from joining.")
            if (usable.size > exactLimit) add("Used deterministic nearest-endpoint ordering because ${usable.size} tracks exceed the exact limit of $exactLimit.")
            if (order.filter { it.reversed }.any { hasDirectionalPayload(tracksById.getValue(it.trackId)) }) {
                add("Reversed tracks retain timestamps and extension data; directional instructions may need review.")
            }
        }
        return buildPlan(
            order = order,
            tracksById = usable.associateBy { it.id },
            strategy = strategy,
            exact = usable.size <= exactLimit,
            excluded = (missing + empty).distinct().sorted(),
            warnings = warnings,
        )
    }

    /** Returns both possible orders and all four orientation combinations for each order. */
    fun pairwiseCandidates(
        first: GpxTrack,
        second: GpxTrack,
        strategy: JoinGapStrategy = JoinGapStrategy.PRESERVE_SEGMENT_GAP,
    ): List<JoinPlan> {
        require(first.id != second.id) { "Pairwise joining needs two distinct tracks" }
        require(endpoints(first) != null && endpoints(second) != null) { "Tracks must contain points" }
        val byId = mapOf(first.id to first, second.id to second)
        return buildList {
            listOf(first.id to second.id, second.id to first.id).forEach { (left, right) ->
                listOf(false, true).forEach { leftReversed ->
                    listOf(false, true).forEach { rightReversed ->
                        add(
                            buildPlan(
                                order = listOf(
                                    OrientedTrackRef(left, leftReversed),
                                    OrientedTrackRef(right, rightReversed),
                                ),
                                tracksById = byId,
                                strategy = strategy,
                                exact = true,
                            ),
                        )
                    }
                }
            }
        }.sortedWith(compareBy<JoinPlan> { it.totalGapMeters }.thenBy { pathKey(it.order) })
    }

    /**
     * Builds a new track while retaining source payload. All descendant IDs are regenerated, so the
     * result can safely coexist with its sources. Segment gaps remain gaps unless an edge supplies a
     * straight or routed connector segment.
     */
    fun assemble(plan: JoinPlan, sourceTracks: List<GpxTrack>, name: String? = null): GpxTrack {
        require(plan.order.isNotEmpty())
        val tracksById = sourceTracks.associateBy { it.id }
        val primary = tracksById[plan.order.first().trackId]
            ?: error("Join source ${plan.order.first().trackId} is missing")
        val segments = buildList {
            plan.order.forEachIndexed { index, reference ->
                val source = tracksById[reference.trackId] ?: error("Join source ${reference.trackId} is missing")
                addAll(orientedSegments(source, reference.reversed).map(::cloneSegment))
                val edge = plan.edges.getOrNull(index) ?: return@forEachIndexed
                when (edge.strategy) {
                    JoinGapStrategy.PRESERVE_SEGMENT_GAP -> Unit
                    JoinGapStrategy.STRAIGHT_CONNECTOR -> {
                        val from = orientedEndpoint(source, reference.reversed, atStart = false)
                        val nextReference = plan.order[index + 1]
                        val next = tracksById[nextReference.trackId] ?: error("Join source ${nextReference.trackId} is missing")
                        val to = orientedEndpoint(next, nextReference.reversed, atStart = true)
                        add(GpxTrackSegment(points = listOf(clonePoint(from), clonePoint(to))))
                    }
                    JoinGapStrategy.ROUTED_CONNECTOR -> add(
                        cloneSegment(edge.routedConnector ?: error("Routed join edge $index has no connector geometry")),
                    )
                }
            }
        }
        return primary.copy(
            name = name ?: primary.name ?: "Joined track",
            links = plan.order.flatMap { ref -> tracksById.getValue(ref.trackId).links }.distinct(),
            extensions = plan.order.flatMap { ref -> tracksById.getValue(ref.trackId).extensions },
            segments = segments,
            id = UUID.randomUUID().toString(),
        )
    }

    private data class Endpoints(val start: GpxPoint, val end: GpxPoint)
    private data class State(val cost: Double, val path: List<OrientedTrackRef>)
    private data class LastState(val trackIndex: Int, val reversed: Boolean)

    private fun exactOrder(tracks: List<GpxTrack>, constraints: JoinConstraints): List<OrientedTrackRef> {
        val size = tracks.size
        val tracksById = tracks.associateBy { it.id }
        val states = Array(1 shl size) { mutableMapOf<LastState, State>() }
        val possibleStarts = tracks.indices.filter { index ->
            constraints.fixedStartTrackId == null || tracks[index].id == constraints.fixedStartTrackId
        }
        possibleStarts.forEach { index ->
            orientations(tracks[index].id, constraints).forEach { reversed ->
                if (size > 1 && tracks[index].id == constraints.fixedEndTrackId) return@forEach
                val ref = OrientedTrackRef(tracks[index].id, reversed)
                states[1 shl index][LastState(index, reversed)] = State(0.0, listOf(ref))
            }
        }

        for (mask in 1 until (1 shl size)) {
            val currentStates = states[mask].values.toList()
            currentStates.forEach { state ->
                val last = state.path.last()
                tracks.indices.filter { mask and (1 shl it) == 0 }.forEach { nextIndex ->
                    val nextTrack = tracks[nextIndex]
                    val remainingAfter = size - Integer.bitCount(mask) - 1
                    if (nextTrack.id == constraints.fixedEndTrackId && remainingAfter > 0) return@forEach
                    orientations(nextTrack.id, constraints).forEach { reversed ->
                        val next = OrientedTrackRef(nextTrack.id, reversed)
                        val cost = state.cost + gap(last, next, tracksById)
                        val candidate = State(cost, state.path + next)
                        val nextMask = mask or (1 shl nextIndex)
                        val key = LastState(nextIndex, reversed)
                        val previous = states[nextMask][key]
                        if (previous == null || better(candidate, previous)) states[nextMask][key] = candidate
                    }
                }
            }
        }
        val complete = states.last().values.filter { state ->
            constraints.fixedEndTrackId == null || state.path.last().trackId == constraints.fixedEndTrackId
        }
        return complete.minWithOrNull(compareBy<State> { it.cost }.thenBy { pathKey(it.path) })?.path
            ?: error("No join order satisfies the constraints")
    }

    private fun greedyOrder(tracks: List<GpxTrack>, constraints: JoinConstraints): List<OrientedTrackRef> {
        val byId = tracks.associateBy { it.id }
        val startTracks = tracks.filter { constraints.fixedStartTrackId == null || it.id == constraints.fixedStartTrackId }
        val candidates = buildList {
            startTracks.forEach { start ->
                orientations(start.id, constraints).forEach { reversed ->
                    if (tracks.size > 1 && start.id == constraints.fixedEndTrackId) return@forEach
                    val remaining = tracks.map { it.id }.toMutableSet().apply { remove(start.id) }
                    val path = mutableListOf(OrientedTrackRef(start.id, reversed))
                    while (remaining.isNotEmpty()) {
                        val eligible = remaining.filter { id ->
                            id != constraints.fixedEndTrackId || remaining.size == 1
                        }
                        val choices = eligible.flatMap { id ->
                            orientations(id, constraints).map { orientation -> OrientedTrackRef(id, orientation) }
                        }
                        val next = choices.minWithOrNull(
                            compareBy<OrientedTrackRef> { gap(path.last(), it, byId) }.thenBy { it.trackId }.thenBy { it.reversed },
                        ) ?: error("No join order satisfies the constraints")
                        path += next
                        remaining.remove(next.trackId)
                    }
                    if (constraints.fixedEndTrackId == null || path.last().trackId == constraints.fixedEndTrackId) add(path)
                }
            }
        }
        return candidates.minWithOrNull(
            compareBy<List<OrientedTrackRef>> { candidate -> candidate.zipWithNext().sumOf { gap(it.first, it.second, byId) } }
                .thenBy(::pathKey),
        ) ?: error("No join order satisfies the constraints")
    }

    private fun buildPlan(
        order: List<OrientedTrackRef>,
        tracksById: Map<String, GpxTrack>,
        strategy: JoinGapStrategy,
        exact: Boolean,
        excluded: List<String> = emptyList(),
        warnings: List<String> = emptyList(),
    ): JoinPlan {
        val edges = order.zipWithNext().map { (from, to) ->
            JoinEdgeSummary(from, to, gap(from, to, tracksById), strategy)
        }
        return JoinPlan(order, edges, edges.sumOf { it.gapMeters }, exact, excluded, warnings)
    }

    private fun validateConstraints(tracks: List<GpxTrack>, constraints: JoinConstraints) {
        val ids = tracks.mapTo(mutableSetOf()) { it.id }
        constraints.fixedStartTrackId?.let { require(it in ids) { "Fixed start track is not selected or has no points" } }
        constraints.fixedEndTrackId?.let { require(it in ids) { "Fixed end track is not selected or has no points" } }
        require(tracks.size == 1 || constraints.fixedStartTrackId != constraints.fixedEndTrackId || constraints.fixedStartTrackId == null) {
            "Fixed start and end must be different when joining multiple tracks"
        }
        constraints.lockedOrientation.keys.forEach { require(it in ids) { "Locked-orientation track $it is not selected or has no points" } }
    }

    private fun orientations(trackId: String, constraints: JoinConstraints): List<Boolean> =
        constraints.lockedOrientation[trackId]?.let(::listOf) ?: listOf(false, true)

    private fun better(candidate: State, previous: State): Boolean =
        candidate.cost < previous.cost - COST_EPSILON_METERS ||
            abs(candidate.cost - previous.cost) <= COST_EPSILON_METERS && pathKey(candidate.path) < pathKey(previous.path)

    private fun gap(from: OrientedTrackRef, to: OrientedTrackRef, tracks: Map<String, GpxTrack>): Double {
        val fromPoint = orientedEndpoint(tracks.getValue(from.trackId), from.reversed, atStart = false)
        val toPoint = orientedEndpoint(tracks.getValue(to.trackId), to.reversed, atStart = true)
        return GeoMath.distanceMeters(fromPoint, toPoint)
    }

    private fun endpoints(track: GpxTrack): Endpoints? {
        val first = track.segments.firstNotNullOfOrNull { it.points.firstOrNull() } ?: return null
        val last = track.segments.asReversed().firstNotNullOfOrNull { it.points.lastOrNull() } ?: return null
        return Endpoints(first, last)
    }

    private fun orientedEndpoint(track: GpxTrack, reversed: Boolean, atStart: Boolean): GpxPoint {
        val endpoints = endpoints(track) ?: error("Track ${track.id} contains no points")
        return when {
            atStart && !reversed -> endpoints.start
            atStart && reversed -> endpoints.end
            !atStart && !reversed -> endpoints.end
            else -> endpoints.start
        }
    }

    private fun orientedSegments(track: GpxTrack, reversed: Boolean): List<GpxTrackSegment> =
        if (!reversed) track.segments
        else track.segments.asReversed().map { segment ->
            segment.copy(points = segment.points.asReversed().map { point ->
                point.copy(course = point.course?.let { (it + 180.0) % 360.0 })
            })
        }

    private fun clonePoint(point: GpxPoint): GpxPoint = point.copy(id = UUID.randomUUID().toString())

    private fun cloneSegment(segment: GpxTrackSegment): GpxTrackSegment = segment.copy(
        points = segment.points.map(::clonePoint),
        id = UUID.randomUUID().toString(),
    )

    private fun hasDirectionalPayload(track: GpxTrack): Boolean =
        track.extensions.isNotEmpty() || track.segments.any { segment ->
            segment.extensions.isNotEmpty() || segment.points.any { it.time != null || it.course != null || it.extensions.isNotEmpty() }
        }

    private fun pathKey(path: List<OrientedTrackRef>): String =
        path.joinToString("|") { "${it.trackId}:${if (it.reversed) 1 else 0}" }
}
