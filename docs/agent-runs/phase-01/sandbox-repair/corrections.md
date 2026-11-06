# Phase 1 sandbox-repair: archive cross-reference notes

Author: orchestrator. Date: 2026-11-05.

These notes exist because `docs/agents.md` requires each report to be **preserved as received with its author
identified**. Where a later finding supersedes or corrects a figure in an earlier report, the correction is
recorded here and the preserved report is **not edited**. `code-reviewer` routed both notes below to the
orchestrator in [`review-verdict.md`](review-verdict.md) with the explicit instruction not to edit the
preserved reports.

Nothing here is ratified. These are bookkeeping cross-references, not new evidence and not a verdict.

## Note 1 — probe-suite count, per review finding F7

**Affected text:** [`repair-report.md`](repair-report.md) section 3, "Commands run and observed results",
the row recording `.claude/hooks/probe-policy.sh` (final) at **"118/118 PASS"**.

**Correction:** the figure for `probe-policy.sh` alone is **114**, not 118.

**Basis:** `code-reviewer` hand-counted every PASS-emitting call in the current
`.claude/hooks/probe_policy.py` — `test_commands` 43, `test_settings` 10, `test_review_git` 43,
`test_lifecycle` 18, total **114** — which matches the independent count of **114 PASS / 0 FAIL** in
[`coursier-addition-report.md`](coursier-addition-report.md). The follow-up Coursier order changed no probe
count (one retitle, one assertion's expected list), so the two runs should agree, and they do at 114.

**Probable cause**, identified by the reviewer: the adjacent row in the same table records
`validate-agent-files.sh` at "PASS ×4". 114 + 4 = 118, so the figure appears to conflate two suites.

**What this does and does not undermine:** it does not undermine the probe evidence. `probe-policy.sh` sets
`-e`, and `probe()` and `expect()` raise `SystemExit` on the first failure, so a run that reaches the final
probe and exits 0 means every probe passed regardless of the operator's count. The defect is in the recorded
figure, not in the verification.

**Still pending:** `factory-engineer` re-runs `.claude/hooks/probe-policy.sh` after the F1-F5 repairs and
records the verbatim final lines plus the exit code, so the count rests on captured output rather than on a
hand count or a recollection.

## Note 2 — superseded three-entry allowlist descriptions, per review finding F8

**Affected text:** [`repair-report.md`](repair-report.md) at the `.claude/settings.json` row of the section 1
table, the `test_settings()` bullet in section 3, and the "Allowed:" list in section 4 — each describing
`sandbox.filesystem.allowWrite` as **exactly three entries** (`~/.sbt`, `~/.cache/coursier`, `~/.ivy2`).

**Correction:** the allowlist now has **four** entries. `~/Library/Caches/Coursier` was added afterwards under
a separate, explicitly human-approved work order, which is section 7 open item 2 of that same report resolved.
See [`coursier-addition-report.md`](coursier-addition-report.md).

The three-entry descriptions were accurate when written and are preserved as the record of that work order.
They are superseded, not erroneous. The current state is four entries, confirmed in
[`review-diff.md`](review-diff.md) and verified by `code-reviewer`'s authority check.

## Related, recorded here for navigation rather than as a correction

- **The `.sbtopts` toolchain pin proposed in [`sbt-check-measurement.md`](sbt-check-measurement.md) section 5
  is withdrawn.** It was never created. The human confirmed their shell runs Temurin 21, `JAVA_HOME` was
  observed to propagate into a subagent's sandboxed Bash, and
  [`sbt-check-measurement-temurin21.md`](sbt-check-measurement-temurin21.md) records sbt running under Eclipse
  Adoptium 21.0.13. Repair-report open item 1 (toolchain drift) is closed with no build-file change. Treat any
  reference to that pin as superseded, and do not apply its literal path, which was never verified.
- **Repair-report open item 2 (the Coursier entry) remains unproven in both directions.** The entry was
  observed inert in both `sbt check` runs — nothing needed downloading. Approved and present, not yet
  exercised.
- **P1's acceptance criterion is not met**, in both measurements: no agent has completed `sbt check` inside
  its own turn. A new blocker replaced the original one — `java.io.tmpdir` under `/var/folders` is not
  writable. The reviewer proposes a narrower remedy than either agent named; see `review-verdict.md`.
- **The test marker `.claude/.last-test-run` is stale.** No run in this sequence wrote it, and agents may not
  write it directly. Per the reviewer, this change set does not itself owe an `sbt check`: `source_path()`
  covers `.scala`, `.java`, `.sbt`, `.scalafmt.conf` and `project/build.properties`, so the Stop gate does not
  fire on these five `.py`, `.json` and `.md` files. The stale marker is a standing condition, not a defect of
  this work.
