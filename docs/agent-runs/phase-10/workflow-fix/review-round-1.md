# Review round 1: code-reviewer (Opus), read-only

Verdict: **REQUEST CHANGES.** Archived by the orchestrator in condensed form. Reviewer ran no commands (no shell) and wrote no files.

## Findings
- **F1 (medium)** `CLAUDE.md:60` still says the formatter "runs project-wide `sbt fmt`". Rewrite to `sbt "scalafmtOnly <edited files>"`; keep collateral-detection and serialize-edits guidance.
- **F2 (low)** `.claude/hooks/policy.py:242,244` deny messages still say `sbt fmt`. Change both to `scalafmtOnly`.
- **F3 (medium)** `probe_policy.py:395-399` whitespace probe asserts only exit 2; it passes even without the guard (collateral check denies anyway). Assert the guard's stderr message, or that `sbt.args` was not rewritten and no `Space.scala` exists.
- **F4 (medium)** `policy.py:235` denylist (whitespace, quotes, backslash) lets sbt syntax through: `;` splits sbt commands, leading `-` passes. Use an allowlist such as `[A-Za-z0-9._/-]+` with no leading `-`; add a `;` path probe.
- **F5 (low-medium)** `changed_paths()` (`policy.py:207`) includes deleted files and `apply_patch` "Delete File:" and "Move to:" old sides, so `edited` can name a missing file. Filter to `is_file()`; add a deleted-file probe.
- **F6 (low)** No probe covers 2+ files. Add an `apply_patch` probe over two `.scala` files asserting argv `scalafmtOnly A.scala B.scala`.
- **F7 (low)** `CLAUDE.md:54` and `docs/agents.md:61` say Claude (Cowork) observed both "through `ls`"; `human-facts.md` Fact 3 (02:31) gives no method. Say `ls` for the first reading only, or drop the method.
- **F8 (report accuracy)** `report-01-factory-engineer.md` says `validate.py` is outside the work order. It is inside (`.claude/hooks/**`); only `.claude/agents/rag-engineer.md` is outside.

## Checked, no defect
Item 2 times, attribution and caveats match human-facts (apart from F7). Item 3 faithful. Item 4 has no collision (`prompts/` holds only `.gitkeep` skeleton roots). Scope extension to `rag-engineer.md` is necessary; no other authority widened. Snapshot/collateral logic, Stop gate, argv-without-shell all sound. Probes otherwise non-vacuous.

## Pending
- Real-sbt `scalafmtOnly` behavior. Concern: root aggregates seven modules, so unscoped `scalafmtOnly` may run in each; paths may resolve against each project's `baseDirectory`; may be config-scoped. Consider `root / scalafmtOnly` to match the alias at `build.sbt:350`, or absolute paths. Probe a module main file, a module test file and a `tests/` file in a fresh session.
- Re-run probes and validator after repair. Fresh-session hook interception unobserved.
- Informational: `source_path` (`policy.py:248-253`) does not count `prompts/**`, so a later edit there will not trigger the Stop gate.
