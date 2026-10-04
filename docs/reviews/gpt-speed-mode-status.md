# GPT speed mode — merged into local main (uncompiled)

## Scope and provenance

- Candidate repository: `/workspace/Eta-gpt-speed-candidate`; reviewed candidate commit `467eb56d`.
- Candidate-internal commits carried by that candidate: `fbd4a69f` (request field, Bundle transfer, tier injection, binding/snapshot policy), `c85c269a` (complete UI wiring and shared eligibility; background/draft invalidation; static contracts), `abec772e` (Compose manual-clock reentry/reset tests and invalid test-import cleanup).
- Final independent review: task `a1de9cf2-ede1-4f3f-9353-ea855a3ac266`, reviewed worktree `3acaa0e6ccb04921aea356324d5f09f5`; no substantiated blocking findings. That review's test-only delta was merged through the workspace review gate into the candidate (`467eb56d`) only; it did not touch main.
- Integration stage (this round): the user explicitly asked for this work to be merged into main. In the isolated integration repository the original main `44cac24b` was merged with the reviewed candidate `467eb56d`, producing merge commit `b28493bd`. The merge was conflict-free.
- Git provenance was independently verified by the parent: both `44cac24b` and `467eb56d` are ancestors of the merged local main. Non-overlapping candidate files match exactly; unrelated main files are preserved. The sole overlap, `AgentChatBody.kt`, adds only 12 speed-parameter/call lines relative to the previous main.

## Where the work has and has not landed

- CARRIED INTO THE INTEGRATION TREE: the reviewed candidate content is present in integration commit `b28493bd`; the speed-mode sources, Kotlin test classes, and Python contract tests are all visible in this integration worktree.
- LANDED ON LOCAL MAIN: `/workspace/Eta` on branch `main` was fast-forwarded from `44cac24b` to reviewed integration tip `ba75dede` (merge `b28493bd` plus integration-stage documentation). This status update is a subsequent documentation-only commit. No remote push was performed.
- Historical note (superseded): an earlier revision of this document stated that the original repository remained `/workspace/Eta` at baseline `5c997e4c` and that nothing had been merged there. That text described the earlier candidate-only stage; `5c997e4c` is no longer the mainline baseline recorded here.

## Implemented behavior

- Long press on the thinking icon cycles NORMAL -> FAST -> ULTRA_FAST -> NORMAL for eligible GPT text bindings.
- One accepted gesture rotates 360 degrees over 450 ms, scales 1.0 -> 1.12 -> 1.0, and transitions the icon color. FAST is light pink (`#F2A7BD`); ULTRA_FAST is burgundy (`#800020`). NORMAL retains the reasoning-dependent original color.
- Animation gate rejects repeated input without queuing; token checks prevent a cancelled old animation from unlocking a newer one.
- Single click keeps the existing reasoning picker. Speed does not change reasoning depth.
- Shared binding eligibility checks the actual configured model ID, enabled provider/model, exact supported OpenAI protocol, and non-media/text capabilities. The request builder additionally checks the final model after custom body merging.
- Leaving eligible GPT immediately clears the transient state and restores the ordinary tint, removing the speed long-press entry. Returning to GPT does not restore the former speed.
- Provider updates normalize all loaded conversations, including background ones without a usage receipt, plus the current draft. This does not wait for the archive-delayed picker refresh. Restored conversations are checked again.
- Each submitted run freezes its own speed configuration before coroutine launch; later UI/settings changes do not mutate the frozen configuration.
- Explicit NORMAL/FAST/ULTRA_FAST writes service_tier default/fast/ultrafast respectively; null preserves legacy/manual tiers. No model/reasoning changes or silent fallback retries are introduced.
- The preference is transient conversation state, not a provider body setting or database preference.

## Verification and limitations

Candidate-stage result (historical, pre-integration), executed by the parent with bytecode generation disabled against the candidate tree:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s app/src/test/python -p 'test_*.py'
git diff --check
```

- At the candidate stage, 237 Python source-contract tests passed: 226 baseline plus 11 new GPT-speed contracts. That count belongs to `467eb56d` and must not be quoted as the result of the current integration round.
- Also historical: the existing context-learning contract was updated there to assert the normalized `next` state while retaining route invalidation assertions.
- Six Kotlin test classes exist for model eligibility, state policy, request construction/JSON, Bundle transfer, animation policy, and Compose gestures.
- The final Compose test delta includes explicit manual-clock reentry rejection and switching away/back with owner-owned NORMAL reset.

Current round:

- The parent executed the full Python source-contract suite against integration tree `b28493bd`: **296 tests in 16.104s, OK**. `git diff --check 44cac24b..HEAD` passed. Subsequent changes are documentation only.
- Independent integration review `0a507885-d0be-4f3f-8cc7-a12990692a47` found no blocking issues, including the speed callback chain and preservation of placement-phase tail following. Worktree `9571547b86f442f6a6506b28bcf58bc3` passed the explicit review/merge gate before the original main was advanced.
- Kotlin/Compose tests have NOT been executed. No Gradle/Android compilation, CI build, push, APK installation, or real provider requests were performed.
- Static review and Python contracts do not prove rendered behavior, Kotlin compilation, provider acceptance, pricing, or actual latency. These remain later validation work once compilation/testing is authorized.
- This document was updated for this round only; no source, build, or test file was modified by that update.
