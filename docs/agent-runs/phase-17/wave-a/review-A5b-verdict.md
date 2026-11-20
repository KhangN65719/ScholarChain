# Review A5b verdict (code-reviewer, Opus), as received 2026-11-19 (condensed by the orchestrator; findings kept)

Verdict: REQUEST CHANGES. Scope: WO-A1, A3a to A3d, A4. Git evidence came from reading `.git/logs/HEAD`; no git command was run.

- **F1 (test-engineer, HIGH).** `tests/scripts/hooks-guard.sh:102-104` matches the exact line `# 8. Vector index`, but the heading at `guard-dangerous.sh:67` is `# 8. Vector index (S3 Vectors)`. The case fails and H-3 cannot reach `fail=0`. Fix: match the exact heading; no prefix loosening.
- **F2 (test-engineer, MEDIUM).** `tests/scripts/run.sh:13033` `TOKEN = "-".join(...)` keeps the `NAME = "literal"` shape. Rename the variable, as A3d did (uses at 13033, 13091, 13125, 13128). `run.sh:12645` `..._QUERY_TOKEN = "plain-text"` was not flagged at b5a55c3; the V1 re-preview settles it.
- **F3 (documentation-engineer, MEDIUM).** The `README.md:10` K02 bullet still says the rewrite is planned. It is done (`P14LoaderRig.scala:22-32`).
- **F4 (orchestrator, MEDIUM).** `phase-00/human-gate.md:7` says Phase 0 was "accepted as the Phase 1 baseline", but `phase-01/ratification.md:4` names `2313f50`. `08b95c3` was committed after Phase 3's `9ee8b22`. Line 10 credits P17-2 for the hash, but the choice is plan D2 under P17-1. The gate should be built from the commit messages. Mark the choice for the human at H-4.
- **L1 (orchestrator).** In the 34 compiled files the label sits on line 3, after a title, not the first line.
- **L2 (orchestrator).** `phase-15/human-gate.md:5` should also cite the human's confirmation, `phase-16/decisions.md:19` (P16-14).
- **L3 (test-engineer, report only).** The hook-form case count is 33, not 35. The total of 97 is consistent with 33.
- **Verified:**
  - All 17 hashes are full, match the gates, and are phase commits in the reflog.
  - Phase 01 `027c9b7` traces to `phase-02/ratification.md:5`.
  - The agent-report format is right.
  - The K01 edits keep their names, values and users consistent, with no leftover `TOKEN` in `rehearse.py`.
  - No `AKIA[0-9A-Z]{16}` remains outside `docs/`.
  - The harness cases match `policy.py`.
  - The README citations check out, with no grader-directed text.
  - The three sbt runs succeeded as the quick set, but per-group counts are pending.
- **Pending:**
  - orchestrator `git diff` of the K01 files and the README
  - test-engineer `sbt quick` with all group totals after the repair
  - human H-3, including `hooks-guard.sh` (`run.sh` does not call it)
  - the execute bit
  - the V1 re-preview (F2, and the `FQoGZXIvYXdz` and `eyJ` fragments at `P14LoaderRig.scala:26,30`)
  - H-4 (Phase 00 and 03 hash choices)
