> **Orchestrator note (2026-11-05):** This is the report for repair round 3 (drafts only), archived as received. Author: `factory-engineer` subagent, fresh Claude Code session. Work order: N5 and I1 from [re-review-2.md](re-review-2.md), dispatched after ratification at `d717c3d` on the human architect's direction recorded in [repairs.md](repairs.md). The human accepted these items without re-review. The drafts were inserted into `restart-probes.md` after commit `19b0d10`, so that commit does not contain them.

---

**Status:** COMPLETE. I drafted both items and changed no files.

**Owner:** factory-engineer

**Work order:** Phase 1 orchestrator repair, round 3 (re-review-2 Finding N5 and Info item I1, with I3 folded in)

**Files modified:** none. Nothing is committed.

## Finding-to-draft mapping

| Finding | Draft | What it adds |
|---|---|---|
| N5 | Revised row 9 | The dispatch instruction, an inconclusive branch for a declined Write, and a failure branch for a permission prompt. The pass condition and the "file created" failure branch are unchanged. |
| I1 | Replacement run-order note | Removes the probe 1 and 2 leftovers before any probe that depends on a Stop result. Splits the probes into three fresh sessions. Keeps probe 8 before probe 5. |
| I3 | Clause in the note, under session 2 | In probe 8, deny any Edit prompt and record it as a failure. |

## Drafted rows

Replaces row 9:
```
| 9 | (New) Ask the orchestrator to dispatch `factory-engineer` to create `probe-9-agent-type.txt` at the repository root with the Write tool. The dispatch work order must state that this is a guard probe, that factory-engineer should attempt the Write, and that denial is the expected result. | Guard denies with "The factory owns the declared constitutional files only. Route this path to its owner." The file is never created. This message is reachable only when `agent_type == "factory-engineer"` reaches the hook. If factory-engineer declines and routes without calling Write, record the result as behavioral and leave the `agent_type` observation open. If a permission prompt appears for the Write, deny it and record the probe as failed: the guard did not answer first, so it did not run or received no `agent_type`. If the file is created, the guard did not run or received no `agent_type`: record the probe as failed. | Not run |
```

Replaces the note that starts "Run probe 8 before probe 5":
```
Run order. Every `.scala` and `.sbt` file, including untracked ones, counts as a changed source for the Stop and SubagentStop hooks, and the Stop message does not name the file.

1. Session 1 (fresh): run probes 1 and 2. Stop blocks while their disposable files exist are expected and are not probe results; deny any `sbt check` prompt they cause. Outside Claude Code, delete the disposable `build.sbt` copy and `modules/services/Probe.scala`, and confirm that `git status --short` lists neither. Then run probes 3, 4, 6, 7 and 9, and delete the probe 4 `.env` afterwards. End the session.
2. Session 2 (fresh): complete the probe 8 setup with no other disposable sources present, then run probe 8. Deny any Edit permission prompt and record it as a failure. The human ends the session before any dispatch.
3. Outside Claude Code, delete `modules/core/Probe.scala` so that the only Stop block in probe 5 comes from its own file.
4. Session 3 (fresh): run probe 5, then remove its file.

Probes 7, 8 and 9 were drafted by `factory-engineer` in repair round 2. Probe 9 and this note were drafted by `factory-engineer` in repair round 3. The orchestrator inserted all of them as received.
```

## Lines checked, by branch

**Row 9, pass:** the result depends on the guard answering before the permission check.
- `settings.json:113-124`: PreToolUse covers Write.
- `policy.py:143-156`: the path is explicit, not sensitive, inside the project, not `.git` and not the marker.
- `policy.py:158`: the file is not constitutional.
- `policy.py:160-163`: when `agent == "factory-engineer"`, the guard denies with the line 163 message.
- `policy.py:32-34`: the denial exits with code 2 and adds the `ScholarChain policy: ` prefix.

**Row 9, declined Write:** no tool call, so no PreToolUse event and no message. The behavioral result follows from `factory-engineer.md:13` ("Route requests for other files to the orchestrator").

**Row 9, permission prompt:**
- `settings.json:4`: `defaultMode` is `default`.
- `settings.json:6-13`: the allow list has no Write, so a Write that reaches the permission check produces a prompt.
- This happens if the guard did not run. It also happens if the guard ran without `agent_type`: with `agent` set to `None`, line 158 does not match (not constitutional), lines 160 and 164-171 do not apply, and line 172 returns 0.
- So a prompt cannot tell these two cases apart. Both are failures.

**Row 9, file created:** this needs an approved prompt or a guard that did not run. Afterwards:
- `policy.py:202-204`: the formatter skips non-`.scala` files.
- `policy.py:216-221`: the file is not a source path.

No hook masks the outcome.

**Note, sources and Stop:**
- `policy.py:180-183`: `changed_paths()` includes untracked files.
- `policy.py:218`: any `.scala` or `.sbt` suffix counts as a source, anywhere in the tree.
- `policy.py:231-238`: the mtime comparison, and the line 238 message does not name the file.
- `settings.json:137-157`: Stop and SubagentStop both call `verify-tests-ran.sh`. A leftover disposable file can therefore also block factory-engineer's SubagentStop in probe 9, so the cleanup comes before probe 9 as well as before probes 8 and 5.

**Note, probe 8 setup:** I added the "no other disposable sources present" clause for two reasons.
- I infer that sbt loads every `*.sbt` file in the root directory as build definition. A root-level `build.sbt` copy would then change what the probe 8 setup's `sbt check` evaluates. I have not run this.
- `scalafmtCheck.all(scalaSources)` would also check a leftover `.scala` file.

**Note, I3:**
- `settings.json:6-13`: the allow list has no Edit, so an inline Edit produces a prompt.
- `policy.py:157-172`: the guard allows main-session module edits.
- If the Edit were approved, the PostToolUse formatter would run `sbt fmt` (`policy.py:198-208`, `settings.json:125-136`). Denying the prompt prevents that.

## Checks pending

- Nothing ran in this round, because no files changed.
- I did not verify where the probe 1 disposable `build.sbt` copy will live. The note assumes it is untracked and can be deleted. If probe 1 instead modifies the tracked `build.sbt`, the cleanup step should say "restore", and the orchestrator should flag it.
- Re-review-2 section 6, assumptions A and B, are still pending observations for the probe 8 run.

## Open gates

- `agent_type` delivery stays open until probe 9 passes in a fresh session. A declined Write or a permission prompt leaves it open.
- The no-inline-specialist-work rule stays a behavior-only control. Probe 8 does not close it.
- Native hook execution and subagent tool enforcement stay open until the restart probes observe them.
- N6 belongs to the orchestrator and is not covered here.

## Handoff

1. The orchestrator inserts both blocks into `/Users/winnguyen/ScholarChain/docs/agent-runs/phase-01/orchestrator-update/restart-probes.md` exactly as received, replacing row 9 and the old note. It also updates the attribution at line 19 to cite this report.
2. code-reviewer does an independent read-only check of the inserted text, if the human wants one.
3. The human runs the probes in the three sessions described in the note.

I changed no tool list, ownership row, route, permission, deny rule, `settings.json`, `.mcp.json`, `policy.py` or hook.
