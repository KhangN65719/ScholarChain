# Phase 4 workflow-speedup batched repair round

> **Archival note (orchestrator).** Author: `factory-engineer` (subagent), dispatched 2026-11-06 as the single
> batched repair round against [`review.md`](review.md) (verdict REQUEST CHANGES) and the orchestrator's
> [`validation.md`](validation.md). Preserved as received. Not reviewed, not ratified, not committed.
>
> **Orchestrator verification of the author's three headline numbers, run independently after the repair:**
> `.claude/hooks/probe-policy.sh` → `SUMMARY: 160 probes passed, 0 failed.` (confirmed, up from 156);
> `git diff --stat -- CLAUDE.md .claude` → 22 files, `122 insertions(+), 13 deletions(-)` (was 104/11). The
> author's `sbt check` result is not re-replayed here; the orchestrator already replayed that command in
> `validation.md` and the repair touched no Scala source.
>
> **One upgrade to the evidence.** The author labelled the guard's verdicts on `sbt 'Test/compile'` and
> `sbt  check` as inferred. The orchestrator's probe run shows all three new probes passing at exit 0, so the
> **guard half** of those two forms is now measured, not inferred. The **sandbox half** — that those forms then
> fail inside the sandbox — remains inferred for both, and measured only for `sbt "check"`. The author's
> `(inferred)` probe-name tags are therefore conservative rather than wrong, and were left as written.

---

Status: COMPLETE for R1, R1 probe gap, R2, N1, N2 and N3. Nothing is blocked. I did not commit or ratify.

**Results**
- `.claude/hooks/probe-policy.sh` exited 0 with `SUMMARY: 160 probes passed, 0 failed.` The baseline was 156, so the 4 new probes account for the difference.
- `.claude/hooks/validate-agent-files.sh` exited 0 with 4/4 PASS: 18 agent names with models, 18 ownership rows, 5 workflow commands, 2 JSON files and 5 shell scripts.
- `sbt check` ran inside my own turn and exited 0 with no sandbox error. Suites reported `Passed: Total 31, Failed 0, Errors 0, Passed 31`, then `Total 81`, then `Passed: Total 148, Failed 0, Errors 0, Passed 148`. It ended `[success] Total time: 6 s, completed Nov 6, 2026, 10:47:39 PM`.

**Changes**

1. `/Users/winnguyen/ScholarChain/CLAUDE.md`, line 18 (R2).
   - Old: `... Every behavior change needs a feature test. In Phase 1 the build and check task are pending.`
   - New: `... Every behavior change needs a feature test.`

2. `/Users/winnguyen/ScholarChain/CLAUDE.md`, line 51 (R1).
   - Old: ``... `sbt -batch check` and `sbt "check"` do not match, and the guard refuses them before the sandbox is consulted. Read the silence ...``
   - New:
     - The guard refuses `sbt checkAll`, `sbt check --foo`, `sbt -batch check` and `sbt Test/compileAll` before the sandbox is consulted.
     - Quoted and extra-whitespace forms such as `sbt "check"` and `sbt  check` are different. The guard normalizes them with shlex and permits them, but the sandbox does not exclude them. They run sandboxed and fail with `/Users/winnguyen/.sbt/boot/sbt.boot.lock (Operation not permitted)`.
     - The text says `sbt "check"` was measured on 2026-11-06 (exit 1, that error, no guard refusal).
     - It says the outcomes for `sbt 'Test/compile'` and `sbt  check` are inferred from the same normalization, not measured.
     - It then says: "Write the bare two-token form, because a quoted variant looks permitted and then fails confusingly."
     - It ends with the existing "Read the silence ..." sentence.
   - R1's fix states what the orchestrator measured, not what was intended. The guard behavior was not changed.

3. `/Users/winnguyen/ScholarChain/CLAUDE.md`, line 53 (N2). I appended to the end of the marker-stale paragraph, after "Treat `.claude/.last-test-run` as stale until a run is recorded against the current sources.":
   - A passing `sbt check` writes that marker, so an agent's own run now satisfies the Stop and SubagentStop gate without a human relay.
   - This was observed when the orchestrator's `sbt check` updated the marker on 2026-11-06.
   - It is a change in the Stop gate's strength, not in the phase gate, which stays the human's `!`-prefixed run.
   - The full loop of a block, then an agent run that satisfies it, is unobserved (restart probe P4-D).

4. `/Users/winnguyen/ScholarChain/.claude/hooks/probe_policy.py`.
   - The import now reads `from policy import CHECK_SBT, ROOT`.
   - N1 probe: "P1 the sandbox exclusion matches the guard's CHECK_SBT set" asserts `set(sandbox["excludedCommands"]) == {f"sbt {task}" for task in CHECK_SBT}`. It sits after the existing exact-literal and no-wildcard assertion, which I kept.
   - R1 probe: `P1 guard permits quoted near miss that the sandbox does not exclude: sbt "check"` expects exit 0 and passed. A comment above it records that the guard permits the form while the sandbox does not exclude it.
   - Two further probes, `sbt 'Test/compile'` and `sbt  check` with two spaces, each expect exit 0 and passed. Their names are tagged `(inferred)`, and their outcomes come from the same shlex path, not from a run.

5. `/Users/winnguyen/ScholarChain/.claude/agents/build-engineer.md` and `dependency-updater.md` (N3). In each I added one bullet before "Apply the shared laws": "Before writing implementation code, list which specific test proves each item of your primary gate. Put that list in your report."

I did not touch `tools`, `name`, `description` or `Owned output`, nor `docs/agents.md`, `policy.py`, `guard-dangerous.sh` or `docs/agent-runs/**`.

**Not done on purpose**
- N4 and N5: the reviewer judged both acceptable, so I left them.

**Not verified**
- The guard's verdicts on `sbt 'Test/compile'` and `sbt  check` were checked by the probe suite, but whether the sandbox then fails them is inferred.
- Everything that needs a fresh session is still open: P4-A through P4-D, whether the commands run outside the sandbox rather than inside a looser one, the network widening, and `agent_type` delivery.

**Recommendation for a separate work order**
- The guard could refuse quoted and extra-whitespace sbt forms so the two fences agree and the confusing sandbox failure goes away. I did not do it because it changes guard behavior, which is outside this round.

**Next gate:** re-review of the changed items only, then human ratification, then a fresh orchestrator session.
