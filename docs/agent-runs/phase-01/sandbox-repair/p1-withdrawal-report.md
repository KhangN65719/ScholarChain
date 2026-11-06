# Phase 1 sandbox-repair: P1 withdrawal and open-gate record

> **Archival note (orchestrator).** Author: `factory-engineer` (subagent), dispatched 2026-11-05 to execute the
> human's decision recorded in [`repairs.md`](repairs.md): stop the P1 experiments, decline
> `allowLocalBinding`, accept P1 as an open gate, remove the sbt cache entries from the sandbox write
> allowlist, restate `CLAUDE.md:45`, update the affected probes, and keep the P2 and P3 fixes unchanged.
>
> This order withdrew work rather than repairing it. The author's P2 and P3 work had been verified sound by
> two independent reviews and was not touched.
>
> The report is preserved as received. The author was instructed not to write under `docs/agent-runs/**`,
> which is orchestrator-owned; it returned the report as its message and the orchestrator archives it here
> with no edit to its content. HTML entity escapes applied in transit (`&gt;`, `&lt;`) are restored.
>
> This report is the author's own evidence. It is **not ratified and not reviewed.** The author states it does
> not approve its own work.
>
> **Orchestrator verification, independent of the author's claims.** Both confirmed directly:
> 1. `.claude/settings.json` has left the change set. `git diff --stat` now lists four files, not five, and the
>    file produces no diff output.
> 2. The removal took effect live without a restart. This session's own sandbox configuration was re-reported
>    with the four sbt cache paths gone from the write allowlist, which now ends at `"."` and `"$TMPDIR"`.
>    That is a runtime observation from the orchestrator's own environment, not a reading of the file.
>
> **One correction the author volunteered, which the orchestrator confirms and which matters for reading the
> record:** the `CLAUDE.md:45` paragraph was **never committed**. All three paragraphs at lines 45, 47 and 49
> are additions in this change set — `git diff` shows `CLAUDE.md | 6 ++` with zero deletions. So every
> before/after in this and earlier reports is working-tree to working-tree, not a revision of ratified text.

- Date: 2026-11-05 · Phase 1 · Author: `factory-engineer` (subagent) · Working tree at base `751d926`
- Final change set: **four files**, 276 insertions, 8 deletions. P2 and P3 only; P1 withdrawn entirely
- Probe suite: **136 PASS, 0 FAIL** — unchanged count, as expected for a one-for-one assertion swap
- `BLOCKED-ON`: none

---

Done. All four items, nothing else.

## Files and line ranges changed

**`/Users/winnguyen/ScholarChain/.claude/settings.json`** — removed the `sandbox.filesystem.allowWrite` array and its key (all four entries). The file is now **byte-identical to its committed state**: `git diff -- .claude/settings.json` produces **no output at all**, `git diff --stat -- .claude/settings.json` produces no output, and the file no longer appears in `git status --short`. It has left the change set entirely, exactly as the order required. No forcing was needed — the committed-vs-working diff before the edit was precisely and only that six-line block.

The removal took effect **live, without a restart**: this session's sandbox configuration was re-reported mid-task with the four sbt cache paths gone from `write.allowOnly`, which now ends at `"."` and `"$TMPDIR"`. That is runtime-observed, not inferred.

**`/Users/winnguyen/ScholarChain/CLAUDE.md:45`** — paragraph replaced in place. One paragraph in, one out; lines 47 and 49 untouched.

**`/Users/winnguyen/ScholarChain/.claude/hooks/probe_policy.py`** — two edits:
- `58-70`: docstring restated for the open gate; the `allowWrite` equality assertion inverted to an absence assertion.
- `131-136`: comment and one probe name reworded.

**`/Users/winnguyen/ScholarChain/.claude/hooks/policy.py`** — **no change**, as the order anticipated. I read the full working diff: it contains `REVIEW_GIT`, `review_git`, the F2 comment, the `guard_shell` allowance, `verify_tests(payload)`, `stop_payload` and the `is True` tightening, and nothing else. There is **no P1-specific code in `policy.py`**. `CHECK_SBT = {"check", "Test/compile"}` is pre-existing, uncommitted-unchanged, and is guard permission rather than P1 machinery, so it stays.

**`/Users/winnguyen/ScholarChain/docs/agents.md`** — **no change**. Its one-line working diff is pure P3 (the git-forms sentence); it contains no P1 content and needed nothing.

## `CLAUDE.md:45` before and after

One correction to the order's framing, worth recording: the old paragraph **was never committed**. The committed `CLAUDE.md` contains no line-45, -47 or -49 paragraph at all — all three are additions in this change set, which is why `git diff --stat` shows `CLAUDE.md | 6 ++` with zero deletions. So this before/after is working-tree to working-tree, not a revision of ratified text.

Before:

> The sandbox write allowlist admits sbt's cache directories (`~/.sbt`, `~/.cache/coursier`, `~/Library/Caches/Coursier`, `~/.ivy2`). These four entries are the only widening; nothing else changed. Unsandboxed execution stays off, and the read and write deny lists are unchanged. On macOS the Coursier cache sits at `~/Library/Caches/Coursier`, which is now in the allowlist, so a dependency fetch can write its cache from an agent turn; `~/.cache/coursier` stays listed to cover non-macOS runtimes. The allowlist removed the boot-lock blocker only. It does not make the test gate satisfiable from an agent turn: `java.io.tmpdir` resolves to the macOS per-user directory under `/var/folders`, which is not writable in the sandbox, so sbt's analysis-store write fails and `sbt check` cannot yet complete inside an agent turn. Two measured runs failed this way with no check step executed. That is not conditional on the JVM, although agent `sbt` runs should still use the recorded JVM to avoid an unnecessary compiler-bridge rebuild. Until a completed `sbt check` is recorded, treat `.claude/.last-test-run` as stale.

After:

> An agent cannot run `sbt check` inside the shell sandbox. This is an accepted open gate, not a defect awaiting repair. The human runs `sbt check` with the `!` prefix at the end of each phase and before every commit; that is the accepted workflow for satisfying the test gate. The gate is closed as open because four measured runs hit three distinct sandbox-level failure modes, each revealed only after the previous one was removed, and no check step ever executed; `docs/agent-runs/phase-01/sandbox-repair/` holds the measurements and the human decision. The sandbox write allowlist attempted during that work gained nothing and no longer exists, so the sandbox has no write allowlist; the read and write deny lists, the unsandboxing switches and the permission entries are unchanged. Until a human-run `sbt check` is recorded, treat `.claude/.last-test-run` as stale.

No bullet lists, no mention of `.jvmopts`, `/var/folders`, the socket denial or `allowLocalBinding`; the blockers are characterized as sandbox-level and pointed at the evidence directory, not narrated. The recorded-JVM sentence is gone with the rest — it was advice for making an agent `sbt` run cheaper, which no longer has a subject.

## Probe changes

The inverted assertion keeps exact-equality discipline rather than loosening it. It asserts both halves:

```python
"allowWrite" not in filesystem and set(filesystem) == {"denyRead", "denyWrite"}
```

The key-set clause makes this *stronger* than the assertion it replaced: re-adding `allowWrite` fails it, and so does adding any other `filesystem` subkey. It mirrors the existing `set(sandbox) == {...}` pattern at lines 95-101.

The three `sbt check` guard probes are kept — the guard's behavior genuinely is unchanged and the regression value is real — but renamed and recommented so no probe name implies completion. The first was `"P1 sbt check permitted for an implementing role"`; it is now `"P1 guard permits an implementing role to request sbt check"`, with a comment stating plainly that guard permission is not completion and that the command cannot succeed in the sandbox. The other two were already refusal probes and needed no renaming.

One thing I left alone and am flagging rather than fixing: `probe_policy.py:188` contains the refusal fixture `"rg allowWrite .claude/settings.json"`. It names a key that no longer exists. The probe still tests exactly what it should — that `rg` on a constitutional path is refused — and the key name is incidental to it, so editing it would churn a P3 fixture for cosmetics. Your call if you want it reworded.

## Verification

`/Users/winnguyen/ScholarChain/.claude/hooks/probe-policy.sh`, verbatim tail:

```
PASS: Scala formatter flags collateral changes (exit 2)
PASS: existing deploy script still needs verified launcher (exit 2)
SUMMARY: 136 probes passed, 0 failed.
```

**Count: 136**, from the suite's own `SUMMARY` line. **Delta from 136: zero**, and that is expected — I replaced one `expect` with one `expect` and renamed one `guard` without changing its arity. No probe was added or removed. The four `test_settings` sbt/P1 names changed in wording only.

**Exit code: 0**, with the same honest caveat the reviewer recorded as pending check 1. The Bash tool reports a non-zero exit as an error and reported none, and `probe-policy.sh` sets `set -euo pipefail` then `exec python3 -B probe_policy.py`, so a printed `SUMMARY` cannot coexist with a failure. I could not capture a literal `$?`: the guard blocks chaining and redirection in a direct Bash call, and when I tried to write a one-line capture script to the scratchpad it correctly refused — `ScholarChain policy: Structured edits must stay inside the project and outside Git metadata.` That is the guard working as designed, and it means a third-party-captured `$?` remains open and is the human's to close.

`/Users/winnguyen/ScholarChain/.claude/hooks/validate-agent-files.sh` — all four checks pass: 18 unique agent names with explicit tools and the read-only reviewer fence; 18 ownership rows and 18 unique lifecycle routes; 5 workflow commands resolving to required owners; 2 JSON files parsed, 5 shell scripts checked, executable hooks and event wiring verified. The JSON parse covers the edited `settings.json`.

Final `git diff --stat`:

```
 .claude/hooks/policy.py       |  71 +++++++++++++--
 .claude/hooks/probe_policy.py | 205 +++++++++++++++++++++++++++++++++++++++++-
 CLAUDE.md                     |   6 ++
 docs/agents.md                |   2 +-
 4 files changed, 276 insertions(+), 8 deletions(-)
```

Four files, down from five. `git status --short` additionally shows the untracked `docs/agent-runs/phase-01/sandbox-repair/`, which is orchestrator-owned and which I did not touch. I did not run `sbt check`.

## Confirmations

- **P2 and P3 untouched.** `review_git`, `REVIEW_GIT`, the F2 comment, `verify_tests`, `stop_payload`, the `is True` tightening, the F3/F4 fixtures and the `CLAUDE.md:49` git-forms paragraph are all exactly as the second review found them. I verified by reading the full `policy.py` working diff and by the probe suite: all 63 `test_review_git` probes and all 20 `test_lifecycle` probes still pass, including the `is True` identity pair and the four commit-count refusals.
- **Nothing widened.** The only authority-affecting change is a removal. The sandbox gains no write surface from this work; `denyRead`, `denyWrite`, the four sandbox switches, `permissions.allow`, `permissions.deny`, both permission modes and `enableAllProjectMcpServers` are all unchanged and all still pinned by exact-equality probes. The one probe change is a tightening.
- **`sandbox.network.allowLocalBinding` was not added.** It never existed and I did not add it. `"network" not in sandbox` plus the exact `sandbox` key set still passes, so B6 stays moot and that probe is as it was.
- **No `BLOCKED-ON`.** Two guard refusals occurred during the work (the scratchpad script write, and `git diff -U1`); both are the guard behaving correctly and neither blocked the order.

## Status

Status: COMPLETE. Owner: `factory-engineer`. **This is my own evidence. It is not ratified and not reviewed, and I do not approve my own work.** Next gate: the orchestrator routes a final independent re-review of the whole change set to `code-reviewer`, then human ratification and a fresh orchestrator session.

Carried forward and not addressed here: the human-captured `$?` for the probe run; restart probes 10-12, which do not exist and are a separate order; whether Claude Code surfaces exit-1 Stop-hook stderr to the operator; P2 record durability; and the pre-existing gap that the guard applies no path fence to `build-engineer`. The stale `.claude/.last-test-run` is now a standing documented condition rather than an open repair.
