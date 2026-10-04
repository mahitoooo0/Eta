# Work-step expansion flash: current-main integration candidate

> Integration update: reviewed integration `082411773dca1cc532d4f16b3f133e4861c6b4ab`
> (prior independent review `6f8a3421` approved, per parent) is being replayed on
> current main `16bb1bc3`. Explicit group animation and retained-tail corrections
> are included while main's GPT speed wiring and newer placement-phase bottom-follow
> layer are preserved. A new bounded review remains pending. Integration scope and
> validation limits are recorded in `step-group-animation-status.md`.
> No compilation, Gradle, CI, push or installation is performed. The observations
> and validation history below concern the earlier flash candidate, not fresh
> runtime evidence or a completed main merge.

## Observed evidence

The user-provided 0.966-second recording shows a large upper group expanding above
an answer and a separately expanded four-step lower group. Browser-decoded frames
at 0.266 and 0.300 seconds show the lower content first moving out of the visible
viewport and then returning. This does not prove that the lower expansion state
was toggled: the projection assigns independent group override keys.

## Candidate changes

- Capture a measured stable row immediately below the upper group before inserting
  its flat lazy steps. Restore its measured displacement from the existing post-layout
  recovery callback, without waiting for the smooth-follow coroutine.
- If the old key is temporarily virtualized, use only measured bottoms of the exact
  inserted preceding step keys as conservative lower-bound evidence. Unknown geometry
  without that evidence authorizes no expansion correction.
- Bound work to at most 36 iterations; check expiry, ownership, scrolling, actual
  consumed delta and fresh layout on each iteration. Pointer, drag, fling, initial
  positioning, and navigation keep priority.
- Replace the old explicit owner before every new capture attempt, including rejected
  or expired attempts, so rapid toggles do not reuse a previous group's anchor.
- Retire repeated message-root fades for already-seen stable keys on work-group toggles.
  Preserve appearance for new messages, flat lazy rows, step animation, group override
  semantics and the earlier idle-composer transparency change.

## Historical sources and integration

Original flash project base: `5c997e4c`. Production workspace commit: `10ad4e2b`.
Independent group-projection test workspace: `ef1f8719`.
Those patches were originally applied to a separate candidate repository, not main.
The reviewed integration was based on `44cac24b`; the current replay is based on
`16bb1bc3`, as noted above. The parent handles fresh source-contract checks,
bounded independent review and main merge.

## Verification limits

The original flash candidate recorded nine passing new Python source-wiring contracts
and 235 passing tests in its then-current Python suite; these are historical results,
not fresh results for the current-main integration.
The pure Kotlin policy tests and group-projection tests are written but not run.
Real-draw Kotlin regression sources now cover 18/32 inserted steps, streaming,
negative control, ownership cancellation, rejected/expired capture, and per-key opacity.
The resumed Grok task reported writing a test, but its sealed worktree still had no
changes and the named file was absent. The parent therefore wrote and checked the
actual test source in this separate candidate, without treating that task's completion
as artifact evidence. No provider outage was established by this mismatch. Android/Kotlin
compilation, Gradle tests, device installation and real-device verification have not
been performed because the user requested no compilation.

Independent review delegation was refused with `TASK_GROUP_PAUSED`. No retry or
manual bypass of the workspace merge gate was used. Parent source inspection is
not a substitute for independent review or Compose draw-test execution. Before
merging/publishing, inspect the final combined candidate and run the Kotlin draw
regression tests when compilation is authorized.

## Unmounted pre-existing answer fade (review blocking risk)

Review found that `AgentChatBody.kt` latched `appearedMessageKeys` only during lazy
composition, so a pre-click answer that was never mounted still played the 180ms
`animateItem` fadeIn when a work collapse first brought it into the viewport. The fix
marks every existing ordinary message from data (the `timelineRows` projected rows
plus the raw `visibleMessages` list) inside `onToggle`, before the projection mutates,
via `markExistingMessages` in `MessageAppearancePolicy.kt`. A message created after the
click is absent from both sources, so it keeps its fade; the per-row latch still dedups
already-appeared keys. Rapid reverse toggles only ever add keys, list switching recreates
the whole per-conversation composition, and the fix touches no follow code.

The first-frame fixture previously mirrored the OLD production entrance (`< 500L` clock
window) and `ExitTransition.None`; it now uses the production `WorkStepEntranceAdmission`
cohort (sealed after one frame) and `tailDetailsExit`. Remaining coverage gap: that
fixture only expands, so the collapse/exit path is not exercised there, and its
always-mounted ANSWER cannot reproduce the never-mounted case. Those are covered by the
pure `MessageAppearancePolicyTest` and the static Python wiring contract, not by a real
draw. No Kotlin/JUnit/Compose, Python, compiler, Gradle or device run is claimed here.
