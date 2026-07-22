package ch.cld9.velogpx.engine

import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinPlannerTest {
    @Test fun pairwiseCandidatesCoverBothOrdersAndAllOrientations() {
        val first = track("A", 0.0, 1.0)
        val second = track("B", 2.0, 3.0)
        val candidates = JoinPlanner.pairwiseCandidates(first, second)

        assertEquals(8, candidates.size)
        assertEquals(8, candidates.map { plan -> plan.order.map { it.trackId to it.reversed } }.toSet().size)
        assertTrue(candidates.all { it.exact && it.edges.size == 1 })
        assertTrue(candidates.zipWithNext().all { (a, b) -> a.totalGapMeters <= b.totalGapMeters })
    }

    @Test fun exactPlannerHonorsFixedEndsAndFindsOrientation() {
        val first = track("A", 0.0, 1.0)
        val backwardsMiddle = track("B", 3.0, 1.1)
        val last = track("C", 3.1, 4.0)

        val plan = JoinPlanner.plan(
            tracks = listOf(last, backwardsMiddle, first),
            constraints = JoinConstraints(fixedStartTrackId = "A", fixedEndTrackId = "C"),
        )

        assertEquals(listOf("A", "B", "C"), plan.order.map { it.trackId })
        assertFalse(plan.order[0].reversed)
        assertTrue(plan.order[1].reversed)
        assertFalse(plan.order[2].reversed)
        assertTrue(plan.exact)
    }

    @Test fun lockedOrientationIsPreserved() {
        val first = track("A", 0.0, 1.0)
        val second = track("B", 3.0, 1.1)
        val plan = JoinPlanner.plan(
            listOf(first, second),
            constraints = JoinConstraints(
                fixedStartTrackId = "A",
                lockedOrientation = mapOf("A" to false, "B" to false),
            ),
        )
        assertEquals(listOf(false, false), plan.order.map { it.reversed })
    }

    @Test fun selectedTracksAndEmptyTracksAreReported() {
        val first = track("A", 0.0, 1.0)
        val ignored = track("B", 10.0, 11.0)
        val empty = GpxTrack(id = "empty")
        val plan = JoinPlanner.plan(
            tracks = listOf(first, ignored, empty),
            selectedTrackIds = setOf("A", "empty", "missing"),
        )
        assertEquals(listOf("A"), plan.order.map { it.trackId })
        assertEquals(listOf("empty", "missing"), plan.excludedTrackIds)
        assertEquals(2, plan.warnings.size)
    }

    @Test fun gapStrategiesArePerEdgeAndAssemblyPreservesSource() {
        val first = track("A", 0.0, 1.0)
        val second = track("B", 2.0, 3.0)
        val firstSnapshot = first.copy()
        val base = JoinPlanner.plan(
            listOf(first, second),
            constraints = JoinConstraints(
                fixedStartTrackId = "A",
                lockedOrientation = mapOf("A" to false, "B" to false),
            ),
        )
        val preserve = JoinPlanner.assemble(base, listOf(first, second))
        val connectedPlan = base.withGapStrategy(0, JoinGapStrategy.STRAIGHT_CONNECTOR)
        val connected = JoinPlanner.assemble(connectedPlan, listOf(first, second))

        assertEquals(2, preserve.segments.size)
        assertEquals(3, connected.segments.size)
        assertEquals(2, connected.segments[1].points.size)
        assertEquals(firstSnapshot, first)
        assertNotEquals(first.id, connected.id)
        val allIds = connected.segments.flatMap { it.points }.map { it.id }
        assertEquals(allIds.size, allIds.toSet().size)
    }

    @Test fun routedConnectorMustExistBeforeAssembly() {
        val first = track("A", 0.0, 1.0)
        val second = track("B", 2.0, 3.0)
        val plan = JoinPlanner.plan(listOf(first, second), strategy = JoinGapStrategy.ROUTED_CONNECTOR)
        val failure = runCatching { JoinPlanner.assemble(plan, listOf(first, second)) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test fun largePlanUsesDeterministicHeuristic() {
        val tracks = (0..5).map { index -> track(index.toString(), index * 2.0, index * 2.0 + 1.0) }.reversed()
        val first = JoinPlanner.plan(tracks, exactLimit = 3)
        val second = JoinPlanner.plan(tracks.shuffled(java.util.Random(42)), exactLimit = 3)
        assertFalse(first.exact)
        assertEquals(first.order, second.order)
        assertEquals(first.totalGapMeters, second.totalGapMeters, 0.0)
    }

    private fun track(id: String, startLongitude: Double, endLongitude: Double): GpxTrack = GpxTrack(
        id = id,
        name = id,
        segments = listOf(
            GpxTrackSegment(
                points = listOf(
                    GpxPoint(latitude = 0.0, longitude = startLongitude),
                    GpxPoint(latitude = 0.0, longitude = endLongitude),
                ),
            ),
        ),
    )
}
