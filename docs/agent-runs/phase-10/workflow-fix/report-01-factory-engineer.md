# Report 01: factory-engineer (workflow fix, work order 01)

Author: factory-engineer. Archived by the orchestrator as received (the diff section is the agent's condensed form; the full diff is `git diff` over the files listed).

**Status:** COMPLETE for drafting. Not ratified. The new formatter command has not been run against real sbt, so that part is unverified.

## Files changed
- `.claude/hooks/policy.py`: item 1, hook change
- `.claude/hooks/probe_policy.py`: item 1, new probes
- `CLAUDE.md`: items 2 and 3
- `docs/agents.md`: items 2 and 4
- `.claude/hooks/validate.py` and `.claude/agents/rag-engineer.md`: not named in the work order. Item 4 requires both, because `validate.py` pins each owner's ownership string and `rag-engineer.md` must carry the same "Owned output" line. Without them `validate-agent-files.sh` fails (`FAIL: Ownership for rag-engineer differs from the approved scope`). Reviewer should confirm this scope extension.

## Authority changes
- `prompts/**` assigned to `rag-engineer`. No collision found.
- No tools, permissions or autonomy changed. No change to `settings.json`.

## Diff (condensed)
- `policy.py` `format_scala`: `edited = sorted(scala ∩ candidates)`, return 0 if empty; deny paths containing whitespace, quotes or backslash; run `["sbt", "scalafmtOnly " + " ".join(names)]` instead of `["sbt", "fmt"]`; snapshot and collateral deny unchanged; no-build message says `sbt scalafmtOnly`.
- `validate.py`: `'rag-engineer': 'modules/rag/**; prompts/**'`.
- `rag-engineer.md`: `- Owned output: modules/rag/**; prompts/**.`
- `docs/agents.md`: rag-engineer row; marker sentence rewritten as observed on two runs (02:22 / 02:31 local, 2026-11-08, Claude (Cowork), human relayed, minute resolution).
- `CLAUDE.md`: phase-commit definition added at line 25; line-25 "(measured once, ...)" became "(observed on two runs, ...)"; line-54 paragraph uses the same two-run wording and "observed on two runs for `sbt quick`".
- `probe_policy.py` `test_lifecycle`: fake `sbt` records argv to `sbt.args`, accepts only a single `scalafmtOnly <files>` argument, appends `// formatted` to named files only. Probes: exact argv `scalafmtOnly Probe.scala`; Probe formatted; `Other.scala` (misformatted, another agent's) byte-identical; absolute path same single-file command; editing Other formats only Other; whitespace path refused (exit 2); existing collateral probe kept (exit 2); project-wide formatter flagged as collateral (exit 2).

## Commands run and observed results
- `.claude/hooks/probe-policy.sh` after the last edit: `SUMMARY: 188 probes passed, 0 failed.` (previously 179).
- `.claude/hooks/validate-agent-files.sh` after the last edit: PASS for 18 unique agent names and tools and the reviewer fence; PASS for 18 ownership rows and 18 lifecycle routes; PASS for 5 workflow commands; PASS for 18 JSON files, 10 shell scripts, executable hooks and event wiring.
- An earlier validator run failed on ownership before the `validate.py` and `rag-engineer.md` edits.
- `git diff --stat`: 6 files changed, 63 insertions, 17 deletions. No sbt was run; no Scala source changed.

## Assumptions and risks
1. `scalafmtOnly` is untested against real sbt (multi-project root scope, relative path). If it is project-scoped, the hook could fail or skip files. Needs a fresh-session probe with a Scala edit in a Test source and a module source. Hook runtime behavior stays pending.
2. Concurrent agents can still trip the collateral check: the snapshot hashes all Scala files, so a parallel write between the before and after hashes causes a false deny. Fails closed; kept because the work order says to keep the detection.
3. The collateral deny message still says "sbt fmt touched files outside this edit" (cosmetic).
4. Only `.scala` edits trigger the formatter, as before.
5. Hooks take effect only after human ratification and a fresh orchestrator session.

## Blockers
None.

## Next gate
Independent read-only review by `code-reviewer` (orchestrator supplies the diff); human ratification and a fresh session; fresh-session probe of the PostToolUse `scalafmtOnly` call. No commit made.
