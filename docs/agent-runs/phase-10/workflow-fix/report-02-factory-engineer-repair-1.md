# Report 02: factory-engineer, repair round 1

Author: factory-engineer. Archived by the orchestrator (condensed; verification section added by the orchestrator, labeled).

**Status (as reported):** F1 to F7 edited; the owner's guard refused `probe-policy.sh` and `validate-agent-files.sh`, so the owner ran neither (BLOCKED-ON verification; it did not try to bypass the guard).

## Files changed
`.claude/hooks/policy.py`, `.claude/hooks/probe_policy.py`, `CLAUDE.md`, `docs/agents.md`. Not committed.

## Repairs
- F1: `CLAUDE.md` formatter sentence now says the hook runs `sbt "root / scalafmtOnly <absolute edited files>"` over only the edited files; collateral-detection and serialize-edits guidance kept.
- F2: both `policy.py` deny messages say `sbt scalafmtOnly`.
- F3: whitespace probe asserts the guard's stderr message (via a module-level `LAST_STDERR` set by `probe()`) and that `sbt.args` was never created.
- F4: `policy.py` allowlists paths with `fullmatch([A-Za-z0-9._/-]+)`, rejects a leading `-`, applies the allowlist to the absolute path; four probes (whitespace, `a;b.scala`, `-x.scala`, `q'x.scala`) each assert the guard message and that sbt never ran.
- F5: `scala` in `format_scala` keeps only `is_file()` paths; new probe commits `Gone.scala`, deletes it, sends `apply_patch` "Delete File:", expects exit 0 and no `sbt.args`.
- F6: new `apply_patch` probe updates `B.scala` then `A.scala`; asserts one argv `root / scalafmtOnly <root>/A.scala <root>/B.scala`, both formatted, `Other.scala` unchanged.
- F7: `CLAUDE.md` and `docs/agents.md` say the first reading was through `ls`; the second reading's method is not recorded.
- F8: `validate.py` was inside work order 01's `.claude/hooks/**` list; only `rag-engineer.md` was the scope extension. Report 01 not edited.

## Pending-item decision
`sbt "root / scalafmtOnly <absolute paths>"`. `root /` matches the `fmt` alias at `build.sbt:350` (`root / scalafmtAll`); absolute paths avoid per-module `baseDirectory` resolution. sbt-scalafmt 2.5.6 pinned via `DependencyVersions`. Unverified against real sbt; plugin path handling recalled, not read. Needs a fresh-session probe (module main file, module test file, `tests/` file). Probes use a fake sbt and prove the argv only.

## Risks
Allowlist makes the formatter refuse every Scala edit if the repo path has characters outside `[A-Za-z0-9._/-]` (`/Users/winnguyen/ScholarChain` is fine). `source_path` ignores `prompts/**` (informational). Fresh-session hook interception unobserved.

## Verification run by the orchestrator after the repair (not by the author)
- `.claude/hooks/probe-policy.sh`: `SUMMARY: 199 probes passed, 0 failed.` (188 after work order 01; 11 new).
- `.claude/hooks/validate-agent-files.sh`: 4 PASS lines (18 agent names and tools and reviewer fence; 18 ownership rows and 18 routes; 5 workflow commands; 18 JSON files and 10 shell scripts and executable hooks and event wiring).
