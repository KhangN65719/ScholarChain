# Phase 4 repair resumption

Dispatched by the orchestrator, 2026-11-06, on the human request to continue the prior session. Baseline commit: `74543bb`. Existing uncommitted work is preserved.

Authority and scope derive from [decisions.md](decisions.md) and [final-review.md](final-review.md). The final review requests changes despite validation run 10 passing 214 tests. Existing files may contain partial repairs newer than that review; owners must inspect and preserve those changes.

## Hadoop owner

`hadoop-engineer` owns `modules/hadoop/**` only. Complete F1 with F9/F10/F11 reader/parser handling, F3 and F7 together with the bound first, F4 with F13a, R1 and R2. Correct F8's misleading comment. Add module regression coverage and report per-stage counters, rejects and output contracts where supported by evidence. Deferred F2/F6 policy work and other carried findings remain outside this repair dispatch.

## Independent test owner

`test-engineer` owns `tests/**` only. Convert characterization tests for repaired defects into assertions of correct behavior. Pair still-open defects with meaningful `.fail` assertions where the approved decision defines a crisp expected result; retain diagnostic characterization cases. Do not introduce policy choices for deferred F2/F6.

## Execution and handoff

Owners do not invoke other agents, edit each other's paths, commit, or run live operations. Use bounded local inputs. Preserve preexisting changes. Serialize any project-wide formatting after both owners finish edits.

The human runs `!sbt clean check` after the repair cycle. No test success is inferred from source inspection, prior run 10, or a marker. Independent review follows executed validation. The approved minicluster dependency experiment remains held until the repair check passes, so it changes only one variable. Human ratification and commit remain pending.

## Clarifications during dispatch

- F13a's original report contains inconsistent prose: it calls three disagreeing titles one conflict but explicitly prescribes `titles.size - 1` in [test-report.md](test-report.md). Both owners are directed to the explicit formula: count distinct title disagreements beyond the first (three titles yields two). The final review confirms the defect without replacing that formula. Independent review should assess the documented unit.
- Reader failures must remain observable: a truncated final record is retained for a parser reject rather than silently dropped. Invalid UTF-8 must never become accepted replacement-character data; diagnostic reject text may include a lossy representation. Tests must distinguish diagnostic payloads from accepted records.
- Do not manufacture expected-failure tests for a naming-only finding or an undefined diagnostic policy. Report such limits explicitly.
