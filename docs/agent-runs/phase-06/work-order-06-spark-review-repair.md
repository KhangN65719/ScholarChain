# Work order 06: repair review round 1 (`modules/spark/**`)

Issued by: orchestrator, 2026-11-07. Owner: `spark-engineer`. Source: `docs/agent-runs/phase-06/review.md` (code-reviewer, REQUEST CHANGES). Local only; no commit; no AWS. Do not edit `tests/**`; do not weaken tests.

## Items (one repair round)
B1, B2, N2, N3, N4, N5, N6, N11 from `review.md`, plus the code half of B3 (the fragmentation limit stated next to path and bridge limitations in `DESIGN.md` and the bridge limitation text).

- B1: per input dataset count duplicate-key rows and conflicting duplicate keys; emit the counts in the run counts and `summary.txt`; record conflicting duplicates as integrity violations or a named warning invariant (your choice, state which and why).
- B2: make the run ID change when the code changes: bump `transformVersion` now and document the rule in `DESIGN.md`, or add the sources digest to the run-ID seed (state which).
- N2: unresolved mentions are not called "Researcher" in text and are excluded from "component of N researchers" or labeled; add `nodeKind` to the s3-vectors metadata; mark split-person uncertainty in the profile text where it is derivable.
- N3: distinguish "different component" from "beyond the hop limit" in path status or text.
- N4: fall back to the Spark-local ID only for mention endpoints.
- N5: write column header lines into `summary.txt`.
- N6: add distinct pair counts alongside the row counts or rename the row counts.
- N11: make `DESIGN.md` and the code agree about which numbers the summaries cite.
- Where a change breaks an existing test expectation (for example summary text or `summary.txt` shape), do not edit the independent `tests/**` suites; report the exact failing test and the hand derivation to the orchestrator. Update your own module tests only to match intended behavior and say so.

## B3 evidence (after the human re-runs the pipeline; second step, I will message you)
Read from the new run directory (`stage-metrics.json`, `plans/`, manifest, JSON/text files only): per-window community convergence (cap hit?), path-evidence status counts, `path-length-estimates`, shuffle read and write totals. Report them; do not invent values.

## Execution
List which test proves each item before editing. Run `sbt Test/compile` and `sbt check`; report exact counts per module (core, entityResolution, hadoop, sparkAnalytics, root). No other sbt invocation. Report fields as in work order 05, plus: the failing root tests verbatim if any.
