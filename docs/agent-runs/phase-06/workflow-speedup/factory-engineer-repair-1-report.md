# factory-engineer report: repair round 1, F1 to F8

Author: factory-engineer (archived by the orchestrator, summarized from the interim and final messages). Status: F1 to F8 done; one question open (F6). Work order: `work-order-repair-1.md`. Not committed, not ratified.

## Done

- F1 to F8 edited per the work order. F3 and F4 wording updated, after the orchestrator's message, to the human fact in `human-facts.md`: the `quick` marker write is "measured once" (mtime 02:22 local, matching the 2:22:52 AM completion of the build-engineer's 65 s run). Caveats kept in the docs: one run, minute resolution, no mtime recorded before that run, no fresh-session agent run observed. The request for a human `stat` run is dropped; a before-and-after pair is noted as optional.
- The `probe_policy.py` comment still says "designed to write" (accurate; not updated).
- Not edited: `AGENTS.md`, `.codex/**`, `.agents/**`, `build.sbt`.

## Commands and results

- `probe-policy.sh`: 179 passed, 0 failed. `validate-agent-files.sh`: 4 PASS. Both ran BEFORE the final doc edits; those edits changed prose only, no assertions.
- A background `sbt check` started to clear the Stop gate exited 0. The agent read only the head and two slices of the log and did not read the final `Passed: Total` lines or the closing `[success]`, so no per-module counts. The marker mtime was not read; the Stop gate being clear is inferred from the exit code, not observed. The guard refused `grep` and `tail` for its role; no workaround.

## Open question (F6)

Confirm whether an agent's `sbt check` may serve for a re-review that depends on a full check, or remove that sentence from `CLAUDE.md` and `docs/agents.md`. (The human's rule says the full `sbt clean check` runs at each phase's final gate and before any re-review that depends on it; agents cannot run `sbt clean check`.)

## Pending

Re-review of changed items; human ratification; fresh orchestrator session; the human's `sbt clean check` at the Phase 7 gate; probe scripts re-run after the last prose edits (optional, prose only).
