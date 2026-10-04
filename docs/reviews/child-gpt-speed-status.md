# Child GPT speed controls — uncompiled reviewed candidate

## Delivery
- Repository: `/workspace/Eta-child-gpt-speed`, branch `feat/child-gpt-speed`, based on `eae39d9c`.
- UI artifact `cc1882a7`: settings model-row control before arrow; collaboration card control between model information and task-tier/arrow.
- Data artifact `5ff50baa`: preserved in its sealed tree; reviewed separately, integrated without changing persistence/runtime logic.
- Integrated artifact `859efd77`: asynchronous editor/provider lookup, keyed button scope, fixture provider injection and boundary tests. Passed explicit workspace review/merge gate into this candidate only.
- Original `/workspace/Eta` was not modified by this task; latest observed main `de40f761`. This candidate has not been merged there, pushed, compiled or installed.

## Behavior implemented
- Single click cycles NORMAL / FAST / ULTRA_FAST / NORMAL, separate from reasoning.
- Both controls share the conversation animation policy: 450ms, 360-degree rotation, 1.0 -> 1.12 -> 1.0 scale, light pink / burgundy / original tint. Pending lookup and animation reject repeated clicks without queuing.
- Each profile remembers each provider/model binding independently. Existing conversation/draft ownership and persistence remain separate; changing to an ineligible model hides the button and removes the effective request mode without deleting saved GPT preferences.
- Provider qualification is checked against actual enabled provider/model and text protocol, not display name. Media roles are excluded.
- Child runtime explicitly uses its own binding's saved speed and never inherits the parent's speed. Effective speed participates in the configuration revision; retained runs/continuations keep frozen snapshots.
- Provider lookup suspends outside the owner transaction, avoiding the prior runBlocking database wait. After lookup the editor revalidates ownership and the captured profile before saving. Only Saved produces a success Toast and animation; cancellation propagates and releases the gate. Changing owner/model disposes the old coroutine scope.

## Verification
- Parent inspected actual Git artifacts, diff ranges, call wiring and clean trees.
- UI review: `76664dea-d496-45f7-8466-7cb6e83df3d2`; fixture-provider integration issue corrected in the integrated artifact.
- Data review: `d8666e71-ec85-4162-a2df-6c22ac101586`; no new blocking findings. Known synchronous database wait corrected during integration.
- Final asynchronous integration review: `df097a19-7541-420b-95dd-655568b255b2`; no new static blocking findings.
- Parent executed `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s app/src/test/python -p 'test_*.py'`: 341 tests in 22.532s, OK (331 existing + 10 new source contracts).
- `git diff --check` passed.
- Added Kotlin persistence, request, editor and Compose tests; NONE executed. No Gradle/Android build, CI, real provider request or device visual verification performed.

## Remaining validation boundaries
Static review/source contracts do not establish Kotlin compilation, rendered spacing/animation or asynchronous Robolectric scheduling. Explicit disposal while a lookup is pending and ordinary lookup-exception UI feedback are recommended additional rendered combinations; existing tests cover stale binding, cancellation, no false Toast, gate recovery and binding changes separately. Provider acceptance, price and latency are not validated.
