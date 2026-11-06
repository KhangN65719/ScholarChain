# Final review diff: the whole change set (orchestrator-produced)

Produced by the orchestrator on 2026-11-05 for the **final** review, which the human scoped to the whole
change set rather than only the newest edit. `code-reviewer` has no Bash tool and cannot run git. Generated
with the read-only forms the P3 change permits, against the uncommitted working tree at base `751d926` on
`main`. Nothing is committed.

## The change set is now four files, and P1 is gone

```
 .claude/hooks/policy.py       |  71 +++++++++++++--
 .claude/hooks/probe_policy.py | 205 +++++++++++++++++++++++++++++++++++++++++-
 CLAUDE.md                     |   6 ++
 docs/agents.md                |   2 +-
 4 files changed, 276 insertions(+), 8 deletions(-)
```

**`.claude/settings.json` is no longer in the change set.** The sandbox write allowlist was removed under the
human's decision ([`repairs.md`](repairs.md)), returning the file to its committed state. The orchestrator
verified this two ways: `git diff -- .claude/settings.json` produces no output and the file is absent from
`git diff --stat`; and this session's own sandbox configuration was re-reported with the four sbt cache paths
gone from the write allowlist, which now ends at `"."` and `"$TMPDIR"`. **The change set grants no new write
surface anywhere.**

`.jvmopts` was removed by the human directly and is absent from the tree. `target/sbt-tmp/.keep` remains but
is inside gitignored `target/`, so it is build output, not a tracked artifact.

What remains is **P2** (the Stop-gate repeat-stop path) and **P3** (the read-only git review allowance), plus
the documentation that describes them and the record of P1 as an accepted open gate.

## Reading order for a whole-change-set review

| File | What is in it | Prior review status |
|---|---|---|
| `policy.py` | P3 `REVIEW_GIT`/`review_git`; P2 `verify_tests`/`stop_payload`/`is True` | Verified sound twice |
| `probe_policy.py` | 136 probes; P1 assertion now inverted to an absence assertion | Verified sound twice; the inversion is new |
| `CLAUDE.md` | Three added paragraphs: 45 (P1 open gate — **rewritten**), 47 (P2), 49 (P3) | 47 and 49 verified; 45 is new text |
| `docs/agents.md` | One line, pure P3 | Verified sound twice |

**Every `CLAUDE.md` paragraph here is an addition, not a revision.** The committed file has no paragraph at
lines 45, 47 or 49 — hence `6 ++` with zero deletions. Earlier reports' "before/after" texts are
working-tree to working-tree.

## `CLAUDE.md` — full diff

```diff
diff --git a/CLAUDE.md b/CLAUDE.md
index 314186d..f7b12a0 100644
--- a/CLAUDE.md
+++ b/CLAUDE.md
@@ -42,6 +42,12 @@ Use Bash, Git and Python 3 to run the policy probes. The checked-in settings con
 
 The Phase 1 guard keeps ordinary live deployment, rollback, pipeline and load script execution closed. Before opening those paths, ratify a launcher that verifies the approval record, script digest, exact arguments, target environment and bounds. Until then, approved live operations belong to the human-controlled execution path.
 
+An agent cannot run `sbt check` inside the shell sandbox. This is an accepted open gate, not a defect awaiting repair. The human runs `sbt check` with the `!` prefix at the end of each phase and before every commit; that is the accepted workflow for satisfying the test gate. The gate is closed as open because four measured runs hit three distinct sandbox-level failure modes, each revealed only after the previous one was removed, and no check step ever executed; `docs/agent-runs/phase-01/sandbox-repair/` holds the measurements and the human decision. The sandbox write allowlist attempted during that work gained nothing and no longer exists, so the sandbox has no write allowlist; the read and write deny lists, the unsandboxing switches and the permission entries are unchanged. Until a human-run `sbt check` is recorded, treat `.claude/.last-test-run` as stale.
+
+The test gate blocks the first Stop or SubagentStop when changed sources are newer than the marker. On a repeat stop, which Claude Code signals with `stop_hook_active`, it stops blocking so the turn cannot loop, and reports an unverified stop as a non-blocking error naming the unverified sources. That is a deliberate trade of gate strength for loop termination, and it is not a pass. When it fires, treat `.claude/.last-test-run` as stale, run `sbt check` over the named sources and record the result before review, commit or ratification. The non-blocking path writes no state. An absent stop payload keeps the gate fully enforced and a malformed one fails closed.
+
+Reviews may read constitutional files through exactly `git diff`, `git diff --stat`, `git log --oneline` and `git show --stat`, with ranges and pathspecs. These grant read only. Every other command naming a constitutional path, every other option on these forms, and every write, checkout or patch verb stay refused, and the sensitive-path scan still precedes the allowance. The option refusal includes a bare commit count: `git log --oneline -3 -- CLAUDE.md` and `git diff --stat -5 -- CLAUDE.md` are refused, while the same commands without a constitutional pathspec still pass through the ordinary read-only Git allowance. Omit the count when the command names a constitutional path.
+
 Keep a clean formatting baseline and serialize source edits while the formatter runs. The formatter runs project-wide `sbt fmt` and checks for Scala or sbt changes outside the tool's stated edit paths. If it detects collateral changes, route the diff to the affected owners. Do not continue until each owner has reviewed the changes. Hooks can run outside the shell sandbox, so use a trusted build and keep privileged credentials out of authoring sessions. Claude Code applies file read rules to broad searches on a best-effort basis. Verify secret isolation at the runtime boundary.
 
 `.claude/settings.json` configures the hooks to run natively, and each subagent's tool list declares its permitted tools. Treat native hook execution and subagent tool enforcement as pending until a fresh-session probe in `docs/agent-runs/phase-01/orchestrator-update/restart-probes.md` observes each one. Keep direct script probes and observed live interception as separate evidence, and do not report automatic enforcement from the existence of these files alone. If a hook event does not carry a verified `agent_type`, the guard refuses structured constitutional edits; record that as an open gate. Do not supply unrestricted cloud or secret credentials to authoring sessions.
```

## `.claude/hooks/policy.py` — unchanged since the second review

Its diff is byte-identical to the one in [`review-diff-2.md`](review-diff-2.md), which the second review
verified in full. `factory-engineer` reports making **no** `policy.py` change under the withdrawal order,
having read the working diff and found no P1-specific code there. Verify that claim against
`review-diff-2.md` rather than re-deriving the whole file.

## `.claude/hooks/probe_policy.py` and `docs/agents.md`

`docs/agents.md`'s one-line diff is in [`review-diff.md`](review-diff.md) and is unchanged since.

`probe_policy.py` changed by **+2 lines** since `review-diff-2.md` (203 → 205). The new edits are the inverted
`allowWrite` absence assertion and a probe rename. Read the current file directly;
[`p1-withdrawal-report.md`](p1-withdrawal-report.md) states the exact line ranges and quotes the new
assertion, which is the fastest route to the delta — verify it against the file rather than taking it on
trust.

## Full evidence set in this directory

Work order and original repair: `work-order.md`, `repair-report.md`, `coursier-addition-report.md`.
`sbt check` measurements: `sbt-check-measurement.md`, `sbt-check-measurement-temurin21.md`,
`tmpdir-experiment-report.md`. Reviews: `review-verdict.md`, `review-verdict-2.md`. Withdrawal:
`repairs.md` (the human's decision), `p1-withdrawal-report.md`. Bookkeeping: `corrections.md`. Diffs:
`review-diff.md`, `review-diff-2.md`, this file.
