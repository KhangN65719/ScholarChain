# Phase 1 sandbox-repair addendum: macOS Coursier cache allowlist entry

> **Archival note (orchestrator).** Author: `factory-engineer` (subagent), dispatched 2026-11-05 as a narrow
> follow-up to [`work-order.md`](work-order.md) and [`repair-report.md`](repair-report.md). The work order
> carried one human-approved change: add `~/Library/Caches/Coursier` to the sandbox write allowlist and update
> the matching `CLAUDE.md` sentence. That is section 7, open item 2 of `repair-report.md`, which the author had
> recorded as `BLOCKED-ON human`.
>
> The report is preserved as received. The author was instructed not to attempt a write under
> `docs/agent-runs/**` — that path is orchestrator-owned under `docs/agents.md`, and the live guard refused the
> same write in the previous session. It returned the report as its message instead and the orchestrator
> archives it here. The orchestrator has made no edit to its content. HTML entity escapes applied in transit
> (`&gt;` for the blockquote markers in the before/after section) are restored to plain Markdown.
>
> This report is the author's own evidence and verdict. It is **not ratified, not reviewed and not
> orchestrator-endorsed.** Independent review by `code-reviewer` and human ratification are the next gates.
>
> **Orchestrator note on one discrepancy, for the reviewer's attention:** the author reports
> `probe-policy.sh` at **114 PASS / 0 FAIL**, while `repair-report.md` section 3 records **118/118 PASS** for
> the same suite. The author states it added and removed no probes and retitled one, and flags the gap as
> unresolved. This is an open verification question, not a settled result.

- Date: 2026-11-05 · Phase 1 · Author: `factory-engineer` (subagent) · Base revision `751d926` on `main`, atop the uncommitted P1/P2/P3 repair
- Status: complete within bounds, not ratified. Nothing committed.
- Next gate: independent read-only review by `code-reviewer`, then human ratification, then a fresh orchestrator session.

---

Work order complete within bounds. Report follows.

## Status

`factory-engineer`, Phase 1, work order: ratified addition of `~/Library/Caches/Coursier` to the sandbox write allowlist (section 7, open item 2 of the archived sandbox-repair report). Nothing committed, nothing ratified, no review performed.

## Files and line ranges changed (this session only)

1. `/Users/winnguyen/ScholarChain/.claude/settings.json` — one line added at **104**, inside the existing `sandbox.filesystem.allowWrite` array. The array is now exactly:
   `"~/.sbt"`, `"~/.cache/coursier"`, `"~/Library/Caches/Coursier"`, `"~/.ivy2"` (lines 102-105). Placed next to the other Coursier entry rather than appended, so the two cache paths read together. Nothing else in the file changed.

2. `/Users/winnguyen/ScholarChain/CLAUDE.md` — line **45**, one sentence in the "Hook operation" paragraph, plus "three" → "four" in the preceding sentence's path list. No bullets introduced; declarative prose preserved.

3. `/Users/winnguyen/ScholarChain/.claude/hooks/probe_policy.py` — docstring line **49** ("three" → "four") and the P1 assertion at **55-59**. The assertion remains **exact list equality** against a four-element literal, not a subset, membership or count check, so a later silent widening still fails the suite. The probe name was updated to match ("...exactly the four authorized sbt cache directories").

Not touched: `.claude/hooks/policy.py`, `docs/agents.md`, anything under `docs/agent-runs/**`, and no `.sbtopts` / `.jvmopts` / `project/build.properties`. The diffstat for `policy.py` (68 lines) and the bulk of `probe_policy.py` (163 lines) in the working tree is the previous session's uncommitted P1/P2/P3 repair, not this order.

## CLAUDE.md sentence, before and after

Before:

> On macOS the Coursier cache usually sits at `~/Library/Caches/Coursier`, which is not allowed; a dependency change that needs a fresh download therefore still belongs to the human until that path is ratified.

After:

> On macOS the Coursier cache sits at `~/Library/Caches/Coursier`, which is now in the allowlist, so a dependency fetch can write its cache from an agent turn; `~/.cache/coursier` stays listed to cover non-macOS runtimes.

The following sentence is unchanged and still true: agent `sbt` runs must use the recorded JVM, because a different JVM forces a compiler-bridge rebuild that needs the JVM temporary directory, which the sandbox denies.

## Verification — what actually ran

- `/Users/winnguyen/ScholarChain/.claude/hooks/probe-policy.sh`: **114 PASS, 0 FAIL**, exit 0. Counted from the PASS lines of the run; the script aborts on the first failure, and it ran to the final probe. Note a discrepancy I cannot resolve: the archived prior report states "118/118 PASS" for the same suite. I did not add or remove probes, only retitled one, so either that figure or my count is off by four. Treat the exact number as approximate; the load-bearing result is zero failures and that the P1 exact-equality probe now passes against four entries.
- `/Users/winnguyen/ScholarChain/.claude/hooks/validate-agent-files.sh`: **PASS ×4** — 18 unique agent names with explicit tools and the read-only reviewer fence; 18 ownership rows and 18 unique lifecycle routes; 5 workflow commands resolve to required owners; 2 JSON files parsed, 5 shell scripts checked, executable hooks and event wiring verified. The JSON parse covers my `settings.json` edit.
- I did not run `sbt check`, as directed.

## Does the new entry take effect in my own sandbox

**Observed, not inferred:** immediately after the edit, this session's effective sandbox write `allowOnly` list reported `/Users/winnguyen/Library/Caches/Coursier` alongside the three pre-existing sbt entries, with no restart. The `~` expanded to the absolute home path and the entry appended rather than replacing the implicit project and `$TMPDIR` allowances. This matches the P1 behavior the prior session observed for the first three entries.

**Not observed:** an actual successful write into that directory. I attempted `mkdir -p /Users/winnguyen/Library/Caches/Coursier/.scholarchain-sandbox-probe` and the guard refused it — "This shell command is outside the role's permitted commands" — because `mkdir` is not in my permitted executables, and every other write-capable path to that directory is either refused to me or is `sbt check`, which this order excludes. So the entry is present in the effective configuration; whether a real Coursier download completes under it is unverified and belongs to the `build-engineer` `sbt check` dispatch. I am not reporting enforcement from the existence of the setting alone.

Incidentally confirmed live, not from file contents: the guard refused four of my own commands during this task — `grep`/`rg` naming constitutional paths, a pipeline, and a Write to the scratchpad outside the project ("Structured edits must stay inside the project and outside Git metadata"). The P3 read-only allowance worked: `git diff --stat` over constitutional pathspecs was permitted.

## Confirmations

- No deny list changed: sandbox `denyRead` (25 entries), sandbox `denyWrite` (7 constitutional entries) and `permissions.deny` (51 entries) are byte-identical, each asserted by a passing exact-equality probe.
- No network setting changed, and none exists under `sandbox`; the "no sandbox network setting was added" probe passes, including the assertion that `sandbox` has exactly its five keys.
- No unsandboxing switch changed: `enabled: true`, `failIfUnavailable: true`, `autoAllowBashIfSandboxed: false`, `allowUnsandboxedCommands: false`.
- No `permissions` entry changed — neither `allow` nor `deny`, nor `defaultMode` or `disableBypassPermissionsMode`.
- No ownership boundary moved. No agent `tools` list changed, no owned-path assignment changed, no writer added to any artifact class, `docs/agents.md` untouched. The only capability delta is that a role already permitted to run `sbt check` can now write the macOS Coursier cache.
- Authority delta in full: `+1` writable directory outside the repository, holding downloaded artifacts only, no credentials. It cannot be used to reach a repository path or bypass an ownership boundary.

## BLOCKED-ON

- **`BLOCKED-ON build-engineer`** — open item 1 of the prior report, unchanged and untouched here. Agent `sbt` runs must select the recorded JVM (Temurin 21); a toolchain pin via `.sbtopts`, `.jvmopts` or `project/build.properties` is `build-engineer`'s owned output. Evidence: the Java-21 compiler-bridge jar (`..._2.12.20__65.0-...`) versus the observed `welcome to sbt 1.11.7 (Homebrew Java 27.0.1.0.3)` and the resulting `Could not create temporary directory` at `ZincComponentCompiler.compileAndInstall`. **P1's acceptance criterion — an agent completing `sbt check` inside its own turn — is still not met.** This addition removes one blocker on the path, not the remaining one.
- **`BLOCKED-ON human`** — open item 3 (durable record for the P2 unverified-stop path) remains open, as does open item 4 (the `HEAD@{1}` metacharacter refusal) and P4. Out of scope here, not addressed.
- Live Claude Code interception of the P2 Stop path remains unobserved and pending a fresh-session probe.

## Gate

This is my own evidence. It is **not ratified, not reviewed, and not approved** — I do not approve my own work. Next gate: independent read-only review by `code-reviewer`, then human ratification, then a fresh orchestrator session before the change is relied upon. The orchestrator should archive this report under `docs/agent-runs/phase-01/` with attribution; I did not attempt to write there.
