"""Source wiring checks, not a substitute for Compose frame/device validation."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
COMPONENTS = ROOT / 'main/kotlin/io/github/mangi/eta/ui/components'
BODY = (COMPONENTS / 'AgentChatBody.kt').read_text()
ROWS = (COMPONENTS / 'AgentTimelineRows.kt').read_text()
POLICY = (COMPONENTS / 'WorkGroupAnimationPolicy.kt').read_text()


class WorkGroupAnimationContractTest(unittest.TestCase):
    def test_flat_projection_separates_target_from_visual_lifetime(self):
        self.assertIn('retainedSteps: Map<String, Set<String>> = emptyMap()', ROWS)
        self.assertIn('val projectedMessages = if (expanded) entry.messages else entry.messages.filter', ROWS)
        self.assertIn('"work-step:${message.id}" in retainedSteps[entry.key].orEmpty()', ROWS)
        self.assertIn('projectedMessages.forEachIndexed', ROWS)
        self.assertIn('isLast = index == projectedMessages.lastIndex', ROWS)
        self.assertIn('expanded = expanded', ROWS)
        self.assertIn('items = timelineRows', BODY)
        self.assertIn('hasVisibleSteps = entry.expanded || entry.group.messages.any', BODY)
        self.assertIn('"work-step:${message.id}" in retainedWorkSteps[entry.key].orEmpty()', BODY)
        self.assertNotIn('entry.group.messages.forEach', BODY)

    def test_target_reverses_existing_visibility_state_and_waits_for_completion(self):
        steps = BODY.split('val animation = workAnimations[entry.groupKey]', 1)[1]
        self.assertIn('val appear = remember(entry.key)', steps)
        self.assertIn('SideEffect { appear.targetState = entry.expanded }', steps)
        self.assertIn('appear.isIdle && !appear.currentState && !appear.targetState', steps)
        self.assertIn('finishWorkExit(entry.groupKey, entry.key, animation.generation)', steps)
        self.assertIn('onDispose {', steps)
        self.assertIn('finishWorkExit(entry.groupKey, entry.key, current.generation)', steps)
        self.assertNotIn('ExitTransition.None', steps)
        self.assertNotIn('delay(', POLICY)
        self.assertIn('generation != expectedGeneration', POLICY)
        self.assertIn('pending.isEmpty()', POLICY)

    def test_old_clock_window_is_not_used_to_admit_virtualized_rows(self):
        self.assertNotIn('workExpandStarts', BODY)
        self.assertNotIn('WORK_STEP_APPEAR_WINDOW_NANOS', BODY)
        self.assertIn('animation.entrance.claim(entry.key)', BODY)
        self.assertIn('withFrameNanos { }\n                animation.entrance.seal()', BODY)
        self.assertIn('workAnimations.values.forEach { it.entrance.seal() }', BODY)
        self.assertIn('private val limit: Int = 32', POLICY)
        self.assertIn('admitted.add(key)', POLICY)

    def test_top_direction_has_no_new_forced_follow_or_collapse_recovery(self):
        toggle = BODY.split('val now = System.nanoTime()\n                                val alreadyPinned', 1)[1]
        toggle = toggle.split('is AgentTimelineRow.WorkStep ->', 1)[0]
        self.assertIn('val captured = if (!entry.expanded)', toggle)
        self.assertIn('viewportRecovery.cancelWorkExpansion(entry.key)', toggle)
        self.assertIn('fromBottom = pinned', toggle)
        self.assertNotIn('onBottomAnchorChanged(true)', toggle)
        self.assertNotIn('scrollToItem', toggle)

    def test_card_gap_is_transferred_not_doubled_or_animated_twice(self):
        self.assertIn('(placeable.height - 4.dp.roundToPx()).coerceAtLeast(0)', BODY)
        self.assertIn('if (entry.isLast) Spacer(Modifier.height(4.dp))', BODY)
        self.assertIn('pending + listOfNotNull(stepKeys.lastOrNull())', POLICY)
        self.assertIn('val pending = if (expanded) emptySet() else mountedStepKeys.intersect', POLICY)

    def test_pure_policy_regressions_exist(self):
        tests = (ROOT / 'test/kotlin/io/github/mangi/eta/ui/components/WorkGroupAnimationPolicyTest.kt').read_text()
        for name in (
            'pinnedAndHistoryClicksBothAdmitEntranceButKeepTheirOwnDirection',
            'recompositionAndVirtualizationCannotReplayAnEntrance',
            'zeroHeightEntranceCohortIsBoundedEvenForHugeInput',
            'collapseWaitsForEveryMountedExitNotForATimeoutOrAnUnmeasuredRow',
            'staleCompletionCannotDeleteRapidlyReversedOrRecollapsedGroup',
            'noMountedStepsMeansNoExitRetention',
            'groupsHaveIndependentCohortsAndCompletionOwnership',
            'collapseProjectionRetainsOnlySelectedFlatRowsAndDoesNotTouchAnotherGroup',
            'appendDuringCollapseKeepsExistingProjectedTailAndOtherGroup',
            'deletedRetainedTailTransfersBottomToLastSurvivingProjectedRow',
            'deletingEveryRetainedRowLeavesOnlyCollapsedHeader',
            'expandedProjectionStillUsesCompleteCurrentGroupBoundaries',
        ):
            self.assertIn('fun ' + name, tests)


if __name__ == '__main__':
    unittest.main()
