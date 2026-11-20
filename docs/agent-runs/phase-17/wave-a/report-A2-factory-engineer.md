# WO-A2 report (factory-engineer), as received 2026-11-19 (condensed by the orchestrator; facts and commands kept)

STATUS: COMPLETE as a draft, not self-approved. It needs independent review, human ratification and a fresh session.
OWNER: factory-engineer
WORK ORDER: WO-A2 (P17-3, P17-5, P17-11, P17-13)
FILES CREATED: none
FILES MODIFIED: `.claude/settings.json`, `.claude/hooks/guard-dangerous.sh`, `docs/agents.md`
AUTHORITY: widened. documentation-engineer now owns `reports/**`. Hook logic, matchers, timeouts, deny rules, sandbox and `policy.py` are unchanged.

## Diff summary
- `settings.json` lines 123, 135, 147, 158: `bash \"$CLAUDE_PROJECT_DIR/.claude/hooks/<name>.sh\"` becomes `bash \"$CLAUDE_PROJECT_DIR\"/.claude/hooks/<name>.sh` (PreToolUse guard, PostToolUse formatter, Stop and SubagentStop test gate).
- `docs/agents.md`, the documentation-engineer row: `README.md; reports/**; docs/** excluding ...`.
- `guard-dangerous.sh`: a comment-only header (mode 100755 unchanged). It covers the eight categories AWS delete and terminate, Terraform, Git, Database, S3, Neptune, DynamoDB and Vector index. Each entry gives refused forms and the rule that refuses it: the `\baws\b` branch of `guard_shell`, `DIRECT_DANGER`, the push-delete check, the READ_GIT allowlist, and the settings.json deny list as a second layer. The header states what is not enforced: script and SDK behaviour, SQL inside files, and local history rewrites, which have no rule of their own and are refused only by the allowlist fall-through. The last executable lines are unchanged.

## Commands and validation
- `git ls-files -s` (whole index; the guard refused the `.claude/hooks` pathspec): mode 100755 for `format-scala.sh`, `guard-dangerous.sh` and `verify-tests-ran.sh`. The worktree bit was not observed.
- `git diff` on the three files. No sbt, Python or AWS.
- Live reload observed: the Stop hook ran with the new command form in this session. It printed `[bash "$CLAUDE_PROJECT_DIR"/.claude/hooks/verify-tests-ran.sh]` and returned the policy message. The orchestrator observed the same. This is evidence for probe P3, not a substitute for it.
- Pending: JSON parse (human: `.claude/hooks/validate-agent-files.sh`), `bash -n .claude/hooks/guard-dangerous.sh` (human), and F05 and R03 under the real checker (V1 re-preview). R03 is a static scan, and a prose header may not satisfy it.

## Risks
- If a hook breaks in the fresh session, revert the four settings lines, keep R03 and D3, and record F05 as explained.
- Role-file drift: `.claude/agents/documentation-engineer.md:13` lists owned outputs without `reports/**`. This needs a follow-up order (WO-A2b).

## Manual action (human, `!` prefix)
1. `bash -n .claude/hooks/guard-dangerous.sh`
2. `.claude/hooks/validate-agent-files.sh`
3. `git ls-files -s .claude/hooks` (expect 100755 on the three `.sh` files)
4. Ratify, commit, start a fresh session.

## Restart probes (fresh session)
- P1, PreToolUse: `terraform apply` and `aws s3 rm s3://example-placeholder/x` are refused.
- P2, PostToolUse: a `.scala` edit triggers `scalafmtOnly`; a non-Scala edit does not.
- P3, Stop: an edit followed by a stop without sbt is blocked; after `sbt quick` it passes. The block-then-satisfy loop (P4-D) stays open.
- P4: a non-factory write under `.claude/` is refused.

## Harness cases for test-engineer (WO-A3c)
- Update the old command-text expectations.
- Hook-resolution cases: the four commands with a temporary `CLAUDE_PROJECT_DIR`, including one containing a space. Assert the path exists, is executable, has no trailing quote, and passes `bash -n`.
- One R03 refusal per category through `policy.py guard` (exit 2), with the forms listed in the agent's report.
- Allowed forms stay allowed: `aws s3 ls`, `aws neptune describe-db-clusters`.
- Header guard: mode 755, the eight headings, the last lines unchanged.
- D3: `reports/**` appears in exactly one matrix row.

NEXT GATE: A5 review using `git diff`, H-2 ratification, commit, fresh session with probes P1 to P3, H-3 harness, V1 re-preview.
