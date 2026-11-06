# Phase 1 sandbox-repair: repairs to review findings F1-F5

> **Archival note (orchestrator).** Author: `factory-engineer` (subagent), dispatched 2026-11-05 after the
> human approved routing findings F1 through F5 of [`review-verdict.md`](review-verdict.md) to it as one
> repair. F6 was placed explicitly out of scope (pre-existing, record only); F7 and F8 are archive bookkeeping
> the orchestrator recorded in [`corrections.md`](corrections.md).
>
> The work order carried the human's explicit constraint **"No widening."** On finding F4 the orchestrator
> resolved an ambiguity rather than asking: the human's instruction to "fix the `git log --oneline -N`
> handling" could mean documenting the limit or admitting bare `-<n>` into the guard, and admitting it would
> widen the authorized form set, which `docs/agents.md` reserves to a ratified revision. The order therefore
> required the documentation route and asked for the `-<n>` admission as a **costed proposal only**. The author
> delivered that proposal and recommends against authorizing it.
>
> The report is preserved as received. The author was instructed not to write under `docs/agent-runs/**`,
> which is orchestrator-owned; it returned the report as its message and the orchestrator archives it here
> with no edit to its content. HTML entity escapes applied in transit (`&gt;` blockquote markers, `&lt;` in
> placeholder brackets) are restored to plain Markdown.
>
> This report is the author's own evidence. It is **not ratified and not reviewed.** The author states it does
> not approve its own work. Independent re-review of the repaired lines is the next gate.
>
> **Orchestrator notes for the re-reviewer, on two things the author declared itself:**
> 1. It added a `PASSES` list, a `record()` helper and a `SUMMARY:` line to `probe_policy.py` — beyond the five
>    findings, declared voluntarily, motivated by F7's request that the count rest on captured output. Probe
>    harness only by the author's account; the re-review should confirm that.
> 2. It **temporarily stubbed `review_git` to `return False`** to prove empirically that the relabeled P3
>    probes actually discriminate, observed the expected first failure, then reverted before the final clean
>    run. That is sound method, and it is also a transient edit to a constitutional file that no third party
>    observed. The re-review should confirm the stub is gone and `review_git`'s accept condition is unchanged.

- Date: 2026-11-05 · Phase 1 · Author: `factory-engineer` (subagent) · Working tree at `751d926` plus the uncommitted sandbox repair, Coursier addition and these repairs
- Probe suite: **136 PASS, 0 FAIL** (prior adjudicated baseline 114; +22 accounted for). `validate-agent-files.sh`: PASS ×4
- `BLOCKED-ON`: none

---

## Report — `factory-engineer`, Phase 1 sandbox-repair review repairs (F1–F5)

**Status:** repairs complete, probe suite green. My own evidence, not ratified and not reviewed. I do not approve my own work; the orchestrator should route a re-review.

### Files and line ranges changed

- `/Users/winnguyen/ScholarChain/CLAUDE.md` — line 45 (rewritten), line 49 (one sentence appended). No lines added or removed, so numbering is stable.
- `/Users/winnguyen/ScholarChain/.claude/hooks/policy.py` — lines 56-58 (comment), 273-275 (comment + `is True`).
- `/Users/winnguyen/ScholarChain/.claude/hooks/probe_policy.py` — lines 12-19 (new `PASSES`/`record`), 28 and 48 (`probe` now calls `record`), 45 (`raw_probe`), 56 (`expect`), 137-172 (`test_review_git` allowed/READ_GIT/commit-count groups), 193 (refusal fixture), 265-269 (two new P2 probes), final line (`SUMMARY:`).

No other file touched. `docs/agents.md` needed no change: the line 49 limit is a guard detail documented in project memory, and nothing in the ownership matrix or lifecycle table is affected.

### F1 — HIGH, `CLAUDE.md:45`. Fixed.

Before:
> The sandbox write allowlist admits sbt's cache directories (`~/.sbt`, `~/.cache/coursier`, `~/Library/Caches/Coursier`, `~/.ivy2`) so that an agent can satisfy the test gate itself. It adds nothing else: unsandboxed execution stays off, and the read and write deny lists are unchanged. On macOS the Coursier cache sits at `~/Library/Caches/Coursier`, which is now in the allowlist, so a dependency fetch can write its cache from an agent turn; `~/.cache/coursier` stays listed to cover non-macOS runtimes. Agent `sbt` runs must use the recorded JVM, because a different JVM forces a compiler-bridge rebuild that needs the JVM temporary directory, which the sandbox denies.

After:
> The sandbox write allowlist admits sbt's cache directories (`~/.sbt`, `~/.cache/coursier`, `~/Library/Caches/Coursier`, `~/.ivy2`). These four entries are the only widening; nothing else changed. Unsandboxed execution stays off, and the read and write deny lists are unchanged. On macOS the Coursier cache sits at `~/Library/Caches/Coursier`, which is now in the allowlist, so a dependency fetch can write its cache from an agent turn; `~/.cache/coursier` stays listed to cover non-macOS runtimes. The allowlist removed the boot-lock blocker only. It does not make the test gate satisfiable from an agent turn: `java.io.tmpdir` resolves to the macOS per-user directory under `/var/folders`, which is not writable in the sandbox, so sbt's analysis-store write fails and `sbt check` cannot yet complete inside an agent turn. Two measured runs failed this way with no check step executed. That is not conditional on the JVM, although agent `sbt` runs should still use the recorded JVM to avoid an unnecessary compiler-bridge rebuild. Until a completed `sbt check` is recorded, treat `.claude/.last-test-run` as stale.

The "so that an agent can satisfy the test gate itself" claim is gone; the tmpdir denial is no longer attributed to JVM drift; the JVM guidance survives demoted to an efficiency note. The wording describes only the present state and predicts nothing about `build-engineer`'s `java.io.tmpdir` experiment. Style kept: one declarative paragraph, no bullets.

### F2 — `policy.py:56-58`. Fixed by deleting the false claim (the smaller route, no behavior change).

Before:
> `# Exactly the read-only review forms that may name a constitutional path. Longest prefix first so`
> `# `git diff --stat` is matched as its own form rather than as `git diff` with a rejected option.`

After:
> `# Exactly the read-only review forms that may name a constitutional path. review_git() tries every`
> `# form and accepts if any one matches, so this table's order carries no meaning: do not introduce a`
> `# break-on-first-prefix-match without also making the order explicit and tested.`

The replacement states the actual property (order-independent OR) and warns the maintainer the reviewer was worried about.

### F3 — `probe_policy.py`. Fixed. The invented `agent_type` is gone from the file entirely.

`code-reviewer-orchestrator` is replaced by two fixtures that are checkable against the matrix: `None` (the orchestrator, whose events carry no `agent_type` per `docs/agents.md`) and `build-engineer` (a real shell-bearing matrix role). The allowed set and the new commit-count refusals run under both. The remaining 28 refusals run under `None`. The `code-reviewer` fence probe is unchanged.

### F4 — `probe_policy.py:137-172` plus `CLAUDE.md:49`. Fixed, documentation route only.

(a) Every probe now labeled `P3 allowed` names a constitutional path, so each one depends on `review_git`. I verified this empirically rather than by argument: with `review_git` temporarily stubbed to `return False`, the suite failed at the first P3 case —

```
FAIL: P3 allowed (None): git diff -- CLAUDE.md: expected 0, got 2
ScholarChain policy: Use structured file tools for constitutional paths and the factory workflow for edits.
```

— exit 1. The stub was reverted immediately and the final clean run above is after the revert. I added pathspec-bearing variants for the forms that previously had none (`git diff --stat -- CLAUDE.md`, `git log --oneline -- CLAUDE.md`, `git show --stat -- CLAUDE.md`, `git diff -- CLAUDE.md`, `git diff HEAD~1..HEAD -- .claude/settings.json`).

(b) The eight tokenless cases are retained but relabeled `READ_GIT allowed, no constitutional path`, with a comment stating they predate the allowance and claim nothing for P3. `git log --oneline -3` sits in that group, so it is no longer described as proving P3. Four new explicit refusal probes pin the limit: `git log --oneline -3 -- CLAUDE.md` and `git diff --stat -5 -- CLAUDE.md`, each under both fixtures, expected exit 2.

`CLAUDE.md:49` appended:
> The option refusal includes a bare commit count: `git log --oneline -3 -- CLAUDE.md` and `git diff --stat -5 -- CLAUDE.md` are refused, while the same commands without a constitutional pathspec still pass through the ordinary read-only Git allowance. Omit the count when the command names a constitutional path.

### F5 — `policy.py:275`. Fixed, fail-closed tightening.

`if (payload or {}).get("stop_hook_active"):` → `if (payload or {}).get("stop_hook_active") is True:`, with a two-line comment stating why identity rather than truthiness. Two new probes: `stop_hook_active="false"` and `stop_hook_active=1`, both expected to keep blocking (exit 2), both pass. The existing `True`/`False`/absent probes are unchanged and still pass.

### One change beyond the five findings, declared

I added a `PASSES` list, a `record()` helper and a final `SUMMARY: N probes passed, 0 failed.` line to `probe_policy.py`. Rationale: F7 asks that the count rest on captured output rather than a hand count, and the suite had no self-reported total. This is probe-harness-only — `record()` just appends and prints what `print` already printed; no policy code path, no assertion and no expected exit code changed. Flagging it so the re-review does not have to discover it.

### Verification

`.claude/hooks/probe-policy.sh`, verbatim tail of the final run:

```
PASS: formatter detects Scala before Phase 2 build (exit 2)
PASS: Scala formatter invokes sbt fmt (exit 0)
PASS: Scala formatter flags collateral changes (exit 2)
PASS: existing deploy script still needs verified launcher (exit 2)
SUMMARY: 136 probes passed, 0 failed.
```

- **Count: 136 PASS, 0 FAIL**, emitted by the program's own counter. Prior adjudicated baseline 114; the delta of 22 is +11 duplicated allowed P3 cases under the second fixture, +4 new allowed pathspec variants, +4 commit-count refusals, +2 non-boolean `stop_hook_active` probes, +1 added range case.
- **Exit code 0.** Caveat on how I obtained it: the guard refuses pipelines and redirection in agent Bash commands, and the guard also refused writing a capture script outside the project, so I could not run `probe-policy.sh > file; echo $?`. The exit code is the one the tool reported for the command — it reported `Exit code 1` for the deliberately sabotaged run and no error for the clean runs. The suite also uses `set -euo pipefail` and raises `SystemExit` on the first failure, so reaching the `SUMMARY` line at all implies no probe failed. If the orchestrator wants a literally captured `$?`, that needs a human-run command or a permitted capture path.

`.claude/hooks/validate-agent-files.sh`, verbatim, exit 0:

```
PASS: 18 unique agent names, explicit tools and read-only reviewer fence
PASS: 18 ownership rows and 18 unique lifecycle routes, with shared namespace exceptions
PASS: 5 workflow commands resolve to required owners
PASS: parsed 2 JSON files, checked 5 shell scripts, executable hooks and event wiring
```

`sbt check` not run, per the work order. It is also not owed: `source_path()` covers `.scala`/`.java`/`.sbt`, `.scalafmt.conf` and `project/build.properties`, and this change set is `.py` and `.md` only, so the Stop gate does not fire on it. The marker remains stale as a standing condition (F1 now says so).

### F6 — recorded as a known limitation, not repaired

Two pre-existing read surfaces are wider than `CLAUDE.md:49` reads, both predating this change set and neither added nor removed by `review_git`:

1. `git diff <path> <path>` with at least one path outside the work tree triggers git's *implicit* `--no-index` and reads arbitrary files. The explicit `--no-index` refusal does not catch the implicit form. Already reachable through the pre-existing `READ_GIT` branch. The `SENSITIVE` scan still fences credential classes.
2. `git show --stat HEAD:<path>` prints blob content rather than a stat, so the allowance can print a constitutional file's full text. Read-only; `code-reviewer` can already `Read` those files, so confidentiality impact is nil.

Both are out of scope under this order and left unchanged. If the human wants them closed, that is a separate narrowing order (a refusal for two-path `git diff` where either path escapes the work tree, and a refusal for `<rev>:<path>` arguments) — a tightening, not a widening, but it would change behavior and belongs in its own review.

### Costed proposal, NOT implemented: admitting a bare `-<n>` commit count

Requested as a proposal only. I did not touch the code.

- **Minimal change:** one predicate in `review_git`'s tail check — accept an argument that fully matches `-\d+` in addition to `--` and non-`-` arguments. Roughly: `if all(item == "--" or not item.startswith("-") or re.fullmatch(r"-\d+", item) for item in arguments[len(form):])`. One line, no table change, no new form.
- **Exact authority cost:** it admits one new token class after the form prefix. The admitted set is exactly `-` followed by digits, which in git is a commit-count shorthand only; every option with semantics other than a count begins with a letter (`-U3`, `-M90%`, `-C`), so `-\d+` cannot alias into `--output`, `--ext-diff`, `--textconv`, `--exec-path` or `--no-index`. It stays read-only and moves no ownership boundary.
- **What it costs conceptually, which is the real cost:** today `review_git`'s tail rule is a single flat invariant — *no argument after the form prefix may start with `-`* — which is auditable in one sentence and has no exceptions. Admitting `-\d+` turns it into a rule with an allowlisted exception, which is the start of a list that will attract `-n 5`, `--max-count=5`, `--follow`, and so on. Each future addition then has to be argued on its own merits instead of being refused by the invariant.
- **What it buys:** convenience only. `git log --oneline -3` without a pathspec already passes through `READ_GIT`, and `git log --oneline -- CLAUDE.md` already passes, so the gap is narrow: capping output length *and* naming a constitutional path in the same command. A reviewer can get the same result with `git log --oneline HEAD~3..HEAD -- CLAUDE.md`, which is already allowed and now probed.
- **My recommendation:** do not authorize it. The documented limit plus the range form covers the use case at zero authority cost. If the human does authorize it, it needs its own ratification pass and probes on both sides (`-3` admitted; `-n`, `-n3`, `--max-count=3`, `-3x`, `--3` all still refused).

### No widening — explicit confirmation

Unchanged by this work: the sandbox `allowWrite` list (still exactly the four authorized sbt cache entries), `denyWrite`, `denyRead`, all four sandbox switches, the absence of any `network` key, `permissions.allow`, `permissions.deny`, `defaultMode`, `disableBypassPermissionsMode`, `enableAllProjectMcpServers`, the hook event wiring, every file under `.claude/agents/**` (no tool list moved), `.mcp.json`, `.claude/commands/**` and the `docs/agents.md` ownership matrix and lifecycle table. `.claude/settings.json` was not opened for edit in this turn.

`REVIEW_GIT` still contains exactly the four authorized forms and `review_git`'s accept condition is byte-for-byte unchanged, so no new permitted command form exists. The only behavioral change in `policy.py` is F5, which narrows the non-blocking Stop path from truthy to `is True`. Everything else is comments, prose and probes.

### BLOCKED-ON

None. I did not touch build files; `build-engineer`'s `java.io.tmpdir` work is untouched and the F1 wording does not presume its outcome. I wrote nothing under `docs/agent-runs/**` and edited no preserved report.

### Next gate

Independent re-review of the repaired lines, then human ratification, then a fresh orchestrator session. Still open and owned elsewhere: pending items 1-2 (`build-engineer`, `sbt check`), pending item 4 (restart probes 10-12, mine to draft on a separate order — not done here, it was not in this order's scope), pending item 5 (whether Claude Code surfaces exit-1 Stop stderr, observable only live), pending item 6 (human decisions on the Coursier entry and P2 record durability).
