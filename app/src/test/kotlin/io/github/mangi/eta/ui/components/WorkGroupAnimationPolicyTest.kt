package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import org.junit.Assert.*
import org.junit.Test

/** Pure lifecycle/projection checks. No Compose clock or Android runtime required. */
class WorkGroupAnimationPolicyTest {
    private val keys = List(32) { "work-step:tool-$it" }

    private fun animation(generation: Long = 1, expanded: Boolean = false, bottom: Boolean = false) =
        newWorkGroupAnimation(generation, expanded, bottom, keys, keys.take(3).toSet())

    @Test fun pinnedAndHistoryClicksBothAdmitEntranceButKeepTheirOwnDirection() {
        val history = animation(expanded = true)
        val pinned = animation(expanded = true, bottom = true)
        assertFalse(history.fromBottom)
        assertTrue(pinned.fromBottom)
        assertTrue(history.entrance.claim(keys[0]))
        assertTrue(pinned.entrance.claim(keys[0]))
    }

    @Test fun recompositionAndVirtualizationCannotReplayAnEntrance() {
        val admission = WorkStepEntranceAdmission()
        assertTrue(admission.claim(keys[0]))
        assertFalse(admission.claim(keys[0]))
        admission.seal()
        assertFalse(admission.claim(keys[0]))
        assertFalse(admission.claim(keys[1]))
        // A later explicit click is a new cohort, not permanently suppressed.
        assertTrue(WorkStepEntranceAdmission().claim(keys[0]))
    }

    @Test fun zeroHeightEntranceCohortIsBoundedEvenForHugeInput() {
        val admission = WorkStepEntranceAdmission()
        assertEquals(32, (0 until 10_000).count { admission.claim("row-$it") })
    }

    @Test fun collapseWaitsForEveryMountedExitNotForATimeoutOrAnUnmeasuredRow() {
        val start = animation()
        assertEquals(keys.take(3).toSet(), start.pendingExitKeys)
        assertEquals(keys.take(3).toSet() + keys.last(), start.retainedStepKeys)
        // The virtualized last row owns spacing/footer but never blocks completion.
        assertSame(start, start.finishExit(keys.last(), 1))
        val one = checkNotNull(start.finishExit(keys[0], 1))
        val two = checkNotNull(one.finishExit(keys[1], 1))
        assertNull(two.finishExit(keys[2], 1))
    }

    @Test fun staleCompletionCannotDeleteRapidlyReversedOrRecollapsedGroup() {
        val collapse = animation(generation = 1)
        val reopened = animation(generation = 2, expanded = true)
        val recollapsed = animation(generation = 3)
        assertSame(reopened, reopened.finishExit(keys[0], collapse.generation))
        assertSame(recollapsed, recollapsed.finishExit(keys[0], collapse.generation))
        assertSame(recollapsed, recollapsed.finishExit("other-group-row", 3))
        assertEquals(3, recollapsed.pendingExitKeys.size)
    }

    @Test fun rapidReverseClickReopensWithAFreshCohortAndSurvivesTheAbandonedExit() {
        val firstExpand = animation(generation = 1, expanded = true)
        assertTrue(firstExpand.entrance.claim(keys[0]))
        firstExpand.entrance.seal()
        val collapse = animation(generation = 2)
        // The second click reverses before any mounted shrink exit has completed.
        val reopened = animation(generation = 3, expanded = true)
        // A completion owned by the abandoned collapse cannot retire the reopened group.
        assertSame(reopened, reopened.finishExit(keys[0], collapse.generation))
        assertTrue(reopened.pendingExitKeys.isEmpty())
        // The sealed first cohort cannot replay; the reopened click owns a fresh one.
        assertFalse(firstExpand.entrance.claim(keys[1]))
        assertTrue(reopened.entrance.claim(keys[1]))
    }

    @Test fun collapseFirstFrameKeepsEveryMountedRowUntilTheLastExitCompletes() {
        val mounted = keys.take(3).toSet()
        val firstFrame = newWorkGroupAnimation(2, false, false, keys, mounted)
        // The first collapsed frame still projects every mounted row plus the measured tail.
        assertEquals(mounted, firstFrame.pendingExitKeys)
        assertEquals(mounted + keys.last(), firstFrame.retainedStepKeys)
        val afterFirst = checkNotNull(firstFrame.finishExit(keys[1], 2))
        assertEquals(setOf(keys[0], keys[2]), afterFirst.pendingExitKeys)
        val afterSecond = checkNotNull(afterFirst.finishExit(keys[2], 2))
        assertEquals(setOf(keys[0]), afterSecond.pendingExitKeys)
        // Only the final mounted exit retires the group: not a frame, timeout or unmeasured row.
        assertNull(afterSecond.finishExit(keys[0], 2))
    }

    @Test fun noMountedStepsMeansNoExitRetention() {
        val closed = newWorkGroupAnimation(1, false, false, keys, emptySet())
        assertTrue(closed.pendingExitKeys.isEmpty())
        assertTrue(closed.retainedStepKeys.isEmpty())
    }

    @Test fun groupsHaveIndependentCohortsAndCompletionOwnership() {
        val upper = animation(generation = 1, expanded = true)
        val lower = animation(generation = 2, expanded = true)
        upper.entrance.seal()
        assertFalse(upper.entrance.claim(keys[0]))
        assertTrue(lower.entrance.claim(keys[0]))
        assertSame(lower, lower.finishExit(keys[0], 1))
    }

    private fun tool(id: String) = ToolActivityMessageUi(
        id = id, toolName = "terminal", status = ToolActivityStatusUi.Success,
        argumentsSummary = id, command = id, resultSummary = id,
    )

    @Test fun collapseProjectionRetainsOnlySelectedFlatRowsAndDoesNotTouchAnotherGroup() {
        val messages: List<AgentChatMessageUi> = List(32) { tool("tool-$it") } +
            AgentMessageUi("between", "answer") + List(4) { tool("lower-$it") }
        val groups = messages.toTimelineEntries()
        val upperKey = groups.first().key
        val lowerKey = groups.last().key
        val expanded = groups.toLazyTimelineRows(mapOf(upperKey to true, lowerKey to true), false)
        val closedTarget = mapOf(upperKey to false, lowerKey to true)
        val retained = animation().retainedStepKeys
        val exiting = groups.toLazyTimelineRows(closedTarget, false, mapOf(upperKey to retained))
        val upperSteps = exiting.filterIsInstance<AgentTimelineRow.WorkStep>().filter { it.groupKey == upperKey }
        assertEquals(retained, upperSteps.map { it.key }.toSet())
        assertTrue(upperSteps.none { it.expanded })
        assertTrue(upperSteps.last().isLast)
        assertFalse(exiting.filterIsInstance<AgentTimelineRow.WorkHeader>().first().expanded)
        fun lower(rows: List<AgentTimelineRow>) = rows.filterIsInstance<AgentTimelineRow.WorkStep>()
            .filter { it.groupKey == lowerKey }
        assertEquals(lower(expanded), lower(exiting))
        val reopened = groups.toLazyTimelineRows(mapOf(upperKey to true, lowerKey to true), false)
        assertEquals(expanded.map { it.key }, reopened.map { it.key })
        val settled = groups.toLazyTimelineRows(closedTarget, false)
        assertTrue(settled.filterIsInstance<AgentTimelineRow.WorkStep>().none { it.groupKey == upperKey })
    }
    @Test fun appendDuringCollapseKeepsExistingProjectedTailAndOtherGroup() {
        val old = List(4) { tool("old-$it") }
        val lower = List(2) { tool("lower-$it") }
        val key = "work-old-0"
        val retained = setOf("work-step:old-1", "work-step:old-3")
        fun rows(upper: List<AgentChatMessageUi>) =
            (upper + AgentMessageUi("separator", "answer") + lower).toTimelineEntries()
                .toLazyTimelineRows(mapOf(key to false, "work-lower-0" to true), true,
                    mapOf(key to retained))
        val before = rows(old)
        val after = rows(old + tool("newly-appended"))
        val projected = after.filterIsInstance<AgentTimelineRow.WorkStep>().filter { it.groupKey == key }
        assertEquals(listOf("work-step:old-1", "work-step:old-3"), projected.map { it.key })
        assertEquals(1, projected.count { it.isLast })
        assertEquals("work-step:old-3", projected.single { it.isLast }.key)
        assertTrue(projected.none { it.expanded })
        assertFalse(projected.first().isFirst) // Hidden actual first row must not gain spacing.
        assertTrue(after.none { it.key == "work-step:newly-appended" })
        assertEquals(before.filter { it.key.startsWith("work-step:lower-") },
            after.filter { it.key.startsWith("work-step:lower-") })
    }

    @Test fun deletedRetainedTailTransfersBottomToLastSurvivingProjectedRow() {
        val messages = List(4) { tool("old-$it") }
        val key = "work-old-0"
        val retained = mapOf(key to setOf("work-step:old-1", "work-step:old-3"))
        val rows = messages.dropLast(1).toTimelineEntries()
            .toLazyTimelineRows(mapOf(key to false), false, retained)
        val steps = rows.filterIsInstance<AgentTimelineRow.WorkStep>()
        assertEquals(1, steps.size)
        assertEquals("work-step:old-1", steps.single().key)
        assertTrue(steps.single().isLast)
        assertFalse(steps.single().isFirst)
        assertFalse(steps.single().expanded)
    }

    @Test fun deletingEveryRetainedRowLeavesOnlyCollapsedHeader() {
        val remaining = listOf(tool("old-0"), tool("old-2"), tool("new-hidden"))
        val key = "work-old-0"
        val rows = remaining.toTimelineEntries().toLazyTimelineRows(mapOf(key to false), true,
            mapOf(key to setOf("work-step:old-1", "work-step:old-3")))
        assertEquals(1, rows.size)
        assertFalse((rows.single() as AgentTimelineRow.WorkHeader).expanded)
    }

    @Test fun expandedProjectionStillUsesCompleteCurrentGroupBoundaries() {
        val messages = List(5) { tool("row-$it") }
        val key = "work-row-0"
        val rows = messages.toTimelineEntries().toLazyTimelineRows(mapOf(key to true), false,
            mapOf(key to setOf("work-step:row-1")))
        val steps = rows.filterIsInstance<AgentTimelineRow.WorkStep>()
        assertEquals(5, steps.size)
        assertEquals(1, steps.count { it.isFirst })
        assertEquals(1, steps.count { it.isLast })
        assertTrue(steps.first().isFirst)
        assertTrue(steps.last().isLast)
        assertTrue(steps.all { it.expanded })
    }

}
