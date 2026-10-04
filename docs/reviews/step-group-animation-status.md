# Explicit work-step group animation: rollback-main integration candidate

## Integration source state

- Integration base: user-rolled-back main `67b0639a`.
- Replay source: `animation.patch`, applied by the parent via `git apply --3way`
  without conflicts (15 files staged; parent reports both diff --check modes clean).
- Prior candidate review and test counts belong to older baselines; none are
  validation of this replay. Final merge and test execution belong to the parent.
- No compilation, Gradle, CI, push, installation or device changes are authorized
  or performed by this implementation worker.

## Preservation of the user rollback

`AgentChatBody.kt` retains the inline `LazyColumn.graphicsLayer` tail translation
from `67b0639a`. The layer reads `followTailOverflow()`, caps lift at the measured
padding buffer, and holds its last measured lift while the tail is temporarily
unknown. `BottomFollowViewportStep.kt` contains pure step/ownership policies only:
there is no `bottomFollowLayer` modifier or placement-phase tail lift.
`BottomFollowDrawPhaseTest.kt` remains deleted. The integer
`snapFollowScrollStep`, rest clipping, idle transparency and GPT speed argument
forwarding remain intact.

Explicit expansion recovery runs from `onGloballyPositioned`, not measurement,
placement or drawing. It captures the old visible key before projecting new rows,
then consumes bounded, freshly measured deltas after layout. It cannot estimate an
unseen tail or authorize history following; pointer, navigation, initial positioning
and active-scroll guards still apply. The viewport regression fixture now models
main's capped inline `graphicsLayer` lift, not the reverted modifier.

## Behaviour implemented in source

Explicit clicks animate both expansion and collapse, including non-bottom/history
positions. Pinning selects direction rather than deciding whether to animate.
Collapsed rows remain projected until their mounted exit transition completes or
is disposed. Stable transition state allows reversal; generation guards reject
stale asynchronous completion. Scroll/recomposition alone must not restart entrance.
The entrance cohort is bounded at the existing maximum of 32 steps per group.

Header target state and visible card lifetime are separate. The final exterior
4dp gap is transferred once, not duplicated inside/outside the visibility animation.
During collapse, hidden appended messages do not steal the retained bottom; deletion
transfers the bottom cap to the final surviving projected row. Removing every retained
row restores a whole header immediately. Other group targets and keys are preserved.

The previous guarded measured viewport repair and idle-composer transparency remain
in source. No global follow or forced history scrolling was added.

## Verification and open risks

For this `67b0639a` replay, the parent reports clean three-way application of the
patch and clean staged and unstaged whitespace checks. The implementation worker
statically inspected the inline `graphicsLayer`, bounded recovery, and GPT speed
arguments in `AgentChatBody.kt`; the regression fixture and this note have been
updated since those initial checks. No current-baseline test result is claimed here.

The bounded recovery can only move from visible-key measurements while its explicit
owner is valid. A very large expansion may exhaust its 36-step callback budget or
its 680ms authorization window before the old key comes back into view; no guessed
jump is permitted. `dispatchRawDelta` rechecks ownership and active scrolling, but
frame-perfect visual continuity and Compose modifier ordering still require runtime
verification. The first-draw regression fixture records separate frame images, but
its clipped streaming variant approximates rather than fully reproduces the app's
composer/offscreen clipping and follow coroutine.

The implementation worker did not run Kotlin/JUnit/Compose, Python, compiler,
Gradle, CI, or device checks. Before release, verify first-draw animation, large
step groups, rapid reversal, scroll interruption, collapse-time append/delete,
card spacing, pinned viewport continuity, and GPT speed forwarding on the integrated
baseline. Older candidate review and test evidence does not certify this replay.
