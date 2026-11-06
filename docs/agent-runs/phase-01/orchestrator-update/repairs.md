# Orchestrator repair: repairs

Status: IN PROGRESS. The review verdict in [review.md](review.md) is REQUEST CHANGES. Repair round 1 returned PARTIAL (see [repair-round-1-report.md](repair-round-1-report.md)). The re-review in [re-review.md](re-review.md) is REQUEST CHANGES, with new Findings N1–N4. Repair round 2 returned COMPLETE ([repair-round-2-report.md](repair-round-2-report.md)). The second re-review ([re-review-2.md](re-review-2.md)) returned **APPROVE** for ratification. No technical checks remain pending before ratification. Two Low findings are open: N5 (before the probe run) and N6 (done).

Recorded by the orchestrator, 2026-11-05. Each finding below records its owner and its repair.

## Human direction (round 1)

The human architect authorized a single repair work order to factory-engineer for Findings 1–5. The scope covered wording in `CLAUDE.md` and `docs/agents.md`, the probe label or comment in `.claude/hooks/probe_policy.py`, and a new probe 8 in `restart-probes.md`. The work order allowed no changes to tool lists, ownership, permissions, deny rules or guard logic. The orchestrator fixes Finding 6 in the README.

The work order also assigned `restart-probes.md` to factory-engineer. That file is orchestrator phase evidence, and under `docs/agents.md:77` a work order can narrow authority but not widen it. The assignment therefore had no effect, and the guard's refusal was consistent with the constitution. The human then directed the orchestrator to insert factory-engineer's drafted probe 8 row as received, with attribution.

## Human direction (round 2)

The human architect directed repair round 2 as described in the re-review's Next gate. N1, N2 and N3 go to factory-engineer. The scope is wording at `docs/agents.md:75` plus drafted probe rows only:
- revise probe 7's expected result;
- add a probe that dispatches factory-engineer to write a disposable non-constitutional path and expects the `policy.py:163` message;
- make probe 8 propose the delegation only, with the human stopping before dispatch and the file removed before probe 5.

No tool, ownership, permission, deny-rule or guard changes. The orchestrator inserts the drafted rows as received with attribution, fixes the N4 records, records the round 1 hook interception as observed live evidence, and updates this file. Then `code-reviewer` re-reviews. The human supplies the diff and the checks.

## Repair routing: review.md Findings 1–7

| Finding | Severity | Repair | Owner | Status |
|---|---|---|---|---|
| 1 | Medium | `CLAUDE.md:47`: describe native hook and subagent tool enforcement as configured, with verification pending in `restart-probes.md`. | factory-engineer | **Resolved** (re-review) |
| 2 | Medium | `docs/agents.md:75`: make the reviewer read-only claim conditional. | factory-engineer | Partly resolved; the rest moves to N1 |
| 3 | Medium | Record the unenforced "no inline specialist work" rule as an open gate. | factory-engineer | Partly resolved; the rest moves to N2 |
| 3 | Medium | Add probe 8 to `restart-probes.md`. | factory-engineer (draft); orchestrator (insertion) | Inserted as received after the guard refused factory-engineer's write. Re-review confirmed the insertion and attribution. Design gaps move to N3. |
| 4 | Low | `CLAUDE.md:23`: allow `/clear` only when no constitutional change was ratified since the last restart. | factory-engineer | **Resolved** (re-review) |
| 5 | Low (non-blocking) | `probe_policy.py:57-59`: label the `apply_patch` probe as a legacy, script-only probe. | factory-engineer | **Resolved** (re-review) |
| 6 | Low | README: list all 8 files, state who drafted, committed and reviewed the original edits, and record `factory-engineer.md` as the human's Phase 0 seed. | orchestrator | **Resolved** (re-review). Stale README items move to N4. |
| 6 | Low | Confirm the Phase 0 authorship of `factory-engineer.md`. | human architect | Open. The human directed the README wording; the confirmation itself goes in `ratification.md`. |
| 7 | Info | Fill in `ratification.md` only after the reviews and repairs, and record the repair commit identifier there. | human architect | Open |

## Repair routing: re-review.md Findings N1–N4

| Finding | Severity | Repair | Owner | Status |
|---|---|---|---|---|
| N1 | Medium | `docs/agents.md:75`: probe 2 verifies the reviewer tool-list fence, and the guard fence is defense in depth that the reviewer cannot reach. Revise probe 7's expected result to "main session denied a constitutional edit". Add a probe that checks `agent_type` reaches the hook directly. Record the round 1 interception as observed live evidence. | factory-engineer (wording, probe drafts); orchestrator (insert probe rows as received) | Repaired (round 2), awaiting re-review. `docs/agents.md:75` is reworded. Probe 7 is revised and new probe 9 is added; both are inserted as received. The round 1 interception is recorded as observed live evidence in `README.md`, rows L3–L4. |
| N2 | Medium | `docs/agents.md:75`: the no-inline-specialist-work rule stays an open, behavior-only control. A passing probe 8 is recorded as behavioral evidence only. The gate closes only through a ratified runtime control or an explicit human acceptance in `ratification.md`. | factory-engineer | Repaired (round 2), awaiting re-review |
| N3 | Low | Probe 8 proposes the delegation only. The human stops the session before dispatch, and the disposable file is removed before probe 5. | factory-engineer (draft); orchestrator (insertion) | Repaired (round 2): revised probe 8 and the run-order note inserted as received. Awaiting re-review. |
| N4 | Low | Correct the records. The codex match is at `probe_policy.py:57`, not `:58`. Remove the unsupported authorship pointer and record the assignment conflict. Mark the README validation section as covering the original range only, and update the stale README status and gate 1 wording. | orchestrator | **Done.** `repairs.md` and `README.md` (status line, validation scope note, gate 1). Awaiting re-review. |

## Repair routing: re-review-2.md Findings N5–N6 and Info items

| Finding | Severity | Repair | Owner | Status |
|---|---|---|---|---|
| N1–N4 | — | (see above) | — | **Resolved** (re-review-2) |
| N5 | Low | Probe 9 needs an inconclusive branch (factory-engineer declines without calling Write, so record it as behavioral and leave the `agent_type` observation open) and a failure branch (a permission prompt appears, so deny it and record a failure). The work order should say that denial is the expected result. | factory-engineer (draft); orchestrator (insertion) | Dispatched (round 3, draft only). The human architect accepted this Low item without re-review. |
| N6 | Low | L1 and L2 in `README.md`: record the exact refused argv, cite the transcript (not archived) as the only source, and narrow "cannot run git" to "the attempted git commands were refused". Leave the `review.md` note unchanged. | orchestrator | **Done** (`README.md` L1, L2, before ratification commit `d717c3d`). The human architect accepted this Low item without re-review. |
| I1 | Info | Leftover files from probes 1 and 2 can be mistaken for a Stop block in probe 5 or 8. Probe 8 ends its session, so the remaining probes need another fresh session. | factory-engineer (draft); orchestrator (insertion) | Dispatched (round 3, draft only). The human architect accepted this item without re-review. |
| I2 | Info | Probe 8's request hints at the expected answer. It is already labeled behavioral-only. | — | No action |
| I3 | Info | In probe 8, deny any Edit permission prompt and record the attempt as a failure. | human (during the probe run) | Note for the probe run |

## Human direction (round 3, after ratification)

After ratifying at `d717c3d`, the human architect routed N5 and I1 to factory-engineer, with a scope of drafted probe 9 and run-order rows only. The orchestrator inserts them as received with attribution and fixes N6. **The human architect accepted these Low and Info items without re-review.** They change only `restart-probes.md`, which is phase evidence, and no constitutional file.

## Pending checks

| # | Check | Owner | Status |
|---|---|---|---|
| 1 | `git status --short` | human architect | **Observed.** Only the expected files changed (re-review question 2). |
| 2 | `python3 -m json.tool` on `.claude/settings.json` and `.mcp.json` | human architect (`!`) | **Observed, pass** (2026-11-05, after repair round 2). Output: `settings.json ok` and `.mcp.json ok`. |
| 3 | `bash -n` on the five `.sh` hooks; `py_compile` on `policy.py`, `validate.py`, `probe_policy.py` | human architect (`!`) | **Observed, pass** (2026-11-05, after repair round 2). `bash -n` printed `ok` for `format-scala.sh`, `guard-dangerous.sh`, `probe-policy.sh`, `validate-agent-files.sh` and `verify-tests-ran.sh`. `py_compile` printed `py_compile ok`. The human removed `.claude/hooks/__pycache__` afterwards. |
| 4 | `validate-agent-files.sh` and `probe-policy.sh` with numeric exit codes | human architect (`!`) | **Observed, pass** (2026-11-05, after repair round 2). `validate-agent-files.sh` printed 4 PASS lines (18 agents with explicit tools and the reviewer fence; 18 ownership rows and 18 routes; 5 commands; 2 JSON files, 5 shell scripts, executable bits and event wiring) and `exit 0`. `probe-policy.sh` printed 52 PASS lines, no FAIL, and `exit 0`; the output includes `legacy apply_patch constitution edit (defensive script probe only) (exit 2)`. Factory-engineer had reported the same results in round 2 without an exit code. **Count correction:** `probe_policy.py` defines 52 cases. The "53/53" in round 1 and in the re-review's pending check 4 was factory-engineer's miscount ([repair-round-2-report.md](repair-round-2-report.md)). Those archived reports stay as received. |
| 5 | Codex search over the constitutional paths | code-reviewer (Grep) | **Observed.** One intentional match, at `probe_policy.py:57`. |
| 6 | Restart probes 1–9 (as revised under N1–N3) and whether `/clear` reloads settings, in a fresh session after ratification | human / orchestrator | Open |
| 7 | Full diff for `9ee8b22..8dbd141` has no changes beyond those the first reviewer saw | human architect | **Confirmed.** The human architect confirms that the range changes 8 files, with 14 changed lines (7 removed, 7 added) across the constitutional files, and no changes beyond those the reviewer saw. |

## Open gates, not blocking this repair (re-review question 5)

- **A.** The guard blocks interpreter calls and absolute constitutional paths for factory-engineer. This is the guard working as designed. A reviewed validation script on the guard's allowlist would need its own constitutional work order, if the human wants one.
- **B.** The guard ignores one-time file assignments. This is not a defect, because the constitution allows a work order to narrow authority but not widen it. Do not change the guard.

## Next steps

1. Done: round 2 archived, rows inserted, checks 2–4 observed, re-review-2 APPROVE archived, N6 fixed.
2. Human ratification in `ratification.md`: the decision, both verdicts, the Phase 0 `factory-engineer.md` authorship confirmation, and the repair commit identifier. Then commit.
3. Before the probe run: route N5, and optionally I1, to factory-engineer. The orchestrator inserts the drafted rows as received.
4. A fresh Claude Code session for restart probes 1–9, with the results in `restart-results.md`. Observe assumption A (`sbt check` with `Probe.scala` present: record the exit code and marker mtime), assumption B (Stop timing), and whether `/clear` reloads settings.
