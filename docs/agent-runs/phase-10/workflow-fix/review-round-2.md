# Review round 2: code-reviewer (Opus), read-only re-review

Verdict: **APPROVE.** Archived by the orchestrator in condensed form. Reviewer ran no commands and wrote no files; evidence is by reading.

## Verified by reading
- F1: `CLAUDE.md:60` describes `sbt "root / scalafmtOnly <absolute edited files>"`, keeps collateral and serialize-edits guidance.
- F2: `policy.py:234,249,251` say `scalafmtOnly`.
- F3: whitespace probe (`probe_policy.py:425-433`) checks the allowlist message and that `sbt.args` was never written; fails without the guard.
- F4: `fullmatch` allowlist, leading `-` rejected, absolute path checked; no hole found. The leading-dash probe only exercises the extra `startswith("-")` check, since the absolute path cannot start with `-`; acceptable.
- F5: `is_file()` filters only the edited set; snapshot and collateral check unchanged. Deleted-file probe catches the bug.
- F6: multi-file probe asserts sorted single-argument argv.
- `LAST_STDERR` does not affect other probes.
- sbt form is one argv element with no shell, consistent with the `fmt` alias at `build.sbt:350`; the real-sbt gap is honestly reported.
- F7 matches `human-facts.md` (`ls` for the first reading; no method for the second).
- No remaining `sbt fmt` formatter description in constitutional files. `.codex/**` and `AGENTS.md` are out of scope under Ruling 2.

## Pending
- Real-sbt `root / scalafmtOnly`: aggregation over seven modules may format the same paths several times or resolve in an unexpected configuration. The fake-sbt probes cannot catch a plugin that silently ignores a path (sbt exits 0, file unchanged). The fresh-session probe should edit a deliberately misformatted file in a module main file, a module test file and a `tests/` file, and confirm each actually changed. Owner: factory-engineer; the human runs the fresh session.
- Hook interception in a fresh session and the P4-D Stop-gate loop remain unobserved.
- Probe (199 passed) and validator (4 PASS) results were run by the orchestrator (`report-02`); the reviewer did not see the output.

## Risks (not blocking)
- A repository path with characters outside `[A-Za-z0-9._/-]` makes every Scala edit refused, with a message that says "Rename it" (points at the file, not the root).
- `source_path` (`policy.py:255-260`) still ignores `prompts/**`; informational.
