# Phase 1 sandbox and guard repair report (P1, P2, P3)

> **Archival note (orchestrator).** Author: `factory-engineer` (subagent), dispatched 2026-11-05 against
> [`work-order.md`](work-order.md). The report is preserved as received. The author could not write this file
> itself: the live guard refused the Write with "The factory owns the declared constitutional files only.
> Route this path to its owner," because `docs/agent-runs/**` is orchestrator-owned under `docs/agents.md`.
> The guard and the author were correct and the work order's instruction to write here was an orchestrator
> error; see the correction note in `work-order.md`. The orchestrator archives it here with attribution and
> has made no edit to its content. Angle-bracket placeholders inside the quoted P2 message were escaped in
> transit and are restored as plain placeholders.
>
> This report is the author's own evidence and verdict. It is not ratified, not reviewed and not
> orchestrator-endorsed. Independent review by `code-reviewer` and human ratification are the next gates.

- Date: 2026-11-05 · Phase 1 · Author: `factory-engineer` (subagent) · Base revision `751d926` on `main`
- Work order: `/Users/winnguyen/ScholarChain/docs/agent-runs/phase-01/sandbox-repair/work-order.md`
- Status: **complete within bounds, not ratified.** Nothing committed. Open items below need human decision.
- Next gate: independent read-only review by `code-reviewer`, then human ratification, then a fresh orchestrator session.

## 1. Files and line ranges changed

| File | Finding | Change | Rationale |
|---|---|---|---|
| `.claude/settings.json` | P1 | `+101..105`: new `sandbox.filesystem.allowWrite` with exactly `~/.sbt`, `~/.cache/coursier`, `~/.ivy2`, before the existing `denyWrite` | The only authorized widening. Lets an agent satisfy the Stop test gate itself. |
| `.claude/hooks/policy.py` | P3 | `+56..58`: `REVIEW_GIT` form table | Declares the four allowed forms as data, longest prefix first, so `git diff --stat` matches as its own form rather than as `git diff` carrying a rejected option. |
| `.claude/hooks/policy.py` | P3 | `+66..81`: `review_git(tokens)` | Matches exactly the four forms; every remaining argument must be a revision, a pathspec or `--`. |
| `.claude/hooks/policy.py` | P3 | `+110..114`: call in `guard_shell`, after the AWS branch and **before** the constitutional token scan | That scan is the actual refusal. Placing the allowance after the sensitive-path and destructive-operation scans keeps those effective on the new forms. |
| `.claude/hooks/policy.py` | P2 | `256..282`: `verify_tests` takes a `payload`, computes `message` first, branches on `stop_hook_active` | Terminates the Stop loop without converting an unmet gate into a silent pass. |
| `.claude/hooks/policy.py` | P2 | `+285..295`: `stop_payload()` | Absent payload keeps the gate enforced; non-empty malformed input raises into the existing handler. |
| `.claude/hooks/policy.py` | P2 | `302`: `verify_tests(stop_payload())` | Wires the payload in. The `except` clause in `main()` is untouched. |
| `.claude/hooks/probe_policy.py` | all | `+31..48` helpers; `+51..119` `test_settings`; `+122..166` `test_review_git`; `+199..221` P2 probes; `+259..262` runner | 75 new two-directional probes. |
| `CLAUDE.md` | all | `+45..49` in "Hook operation" | Documents the three behavior changes, the P2 trade-off, the macOS and JVM limits. |
| `docs/agents.md` | P3 | line `166` amended | Records that the orchestrator can now produce a review diff itself, that `code-reviewer` still cannot (no Bash tool), and that no ownership boundary moved. |

`verify-tests-ran.sh`, `guard-dangerous.sh`, `probe-policy.sh`, `format-scala.sh` and `.mcp.json` are unchanged — the P2 repair lives entirely in `policy.py`, so the wrapper needed no edit. Final diffstat: 5 files, 235 insertions, 7 deletions.

## 2. Per-finding outcome

### P1 — sandboxed agents cannot run `sbt check`

**Reproduced before changing anything.** `sbt check` in this subagent's sandbox:

```
java.io.FileNotFoundException: /Users/winnguyen/.sbt/boot/sbt.boot.lock (Operation not permitted)
	at xsbt.boot.Locks$GlobalLock.withFileLock(Locks.scala:103)
[error] [launcher] error during sbt launcher: java.io.FileNotFoundException: /Users/winnguyen/.sbt/boot/sbt.boot.lock (Operation not permitted)
```

Exactly the reported symptom. Implemented the three authorized entries and nothing else.

**`allowWrite` is the key this runtime honors, it is additive, and it applied without a restart — observed, not inferred.** Before the edit this session's effective write allowlist ended `..., ".", "$TMPDIR"`. Immediately after, it was the same list **plus** three appended entries:

```
..., ".", "$TMPDIR",
"/Users/winnguyen/.sbt", "/Users/winnguyen/.cache/coursier", "/Users/winnguyen/.ivy2"
```

So the key is honored, `~` expands to the absolute home path, entries append rather than replace the implicit project (`.`) and `$TMPDIR` allowances, and the `denyWithinAllow` list protecting `.claude/settings.json`, `.claude/hooks` and the user-level Claude config is intact.

**After the change** the boot-lock failure is gone. sbt launches, loads the build, starts compiling the meta-build, then fails later:

```
[info] welcome to sbt 1.11.7 (Homebrew Java 27.0.1.0.3)
[info] loading settings for project scholarchain-build-build from build.sbt...
[info] compiling 1 Scala source to .../project/project/target/scala-2.12/sbt-1.0/classes ...
[error] java.lang.RuntimeException: Could not create temporary directory.
[error] sbt.io.IO$.createTemporaryDirectory(IO.scala:509)
[error] sbt.internal.inc.ZincComponentCompiler.compileAndInstall(ZincComponentCompiler.scala:259)
[error] (Compile / compileIncremental) Could not create temporary directory.
```

Writes into the newly allowed directories **did** succeed: `~/.sbt/1.0/zinc/.sbt.cache.lock` was created during this run. The residual failure is not one of the three authorized paths. Diagnosis from evidence:

- The cached bridge is `~/.sbt/1.0/zinc/org.scala-sbt/org.scala-sbt-compiler-bridge_2.12-1.11.0-bin_2.12.20__65.0-1.11.0_20250927T224111.jar`. Class-file major **65 is Java 21**.
- `~/.sbt/1.0/` holds both `java9-rt-ext-eclipse_adoptium_21_0_13` and `java9-rt-ext-homebrew_27_0_1_0_3`.
- `versions.md` records the working toolchain as Temurin 21 via `JAVA_HOME=$(/usr/libexec/java_home -v 21)`. My run picked up Homebrew Java 27.

A different JVM invalidates the cached bridge, so Zinc rebuilds it, and that needs `java.io.tmpdir`. On macOS the JVM takes `java.io.tmpdir` from the Darwin per-user temp directory under `/var/folders`, not from the sandbox's overridden `TMPDIR`, and `/var/folders` is not in the allowlist. Under the recorded JVM this step is skipped.

**I could not isolate the two factors.** The guard permits only bare `sbt check` (`policy.py:120`, one argument); `env`, interpreter wrappers and a `JAVA_HOME=... sbt` prefix are all refused, so I cannot run `sbt check` under Temurin 21 from inside an agent turn. Open items 1 and 2.

**Guard change for P1: none needed.** `sbt check` is already permitted at `policy.py:120` for every role except `deploy-engineer` and `incident-responder`. Probed in both directions rather than asserted. The guard was not edited for P1.

**Confirmed unchanged:** `sandbox.enabled: true`, `failIfUnavailable: true`, `autoAllowBashIfSandboxed: false`, `allowUnsandboxedCommands: false`, every sandbox `denyRead` and `denyWrite` entry, `permissions.allow`, all 51 `permissions.deny` entries, `defaultMode`, `disableBypassPermissionsMode`, `enableAllProjectMcpServers: false`. No network setting exists under `sandbox` and none was added. Each is asserted by an executable probe on **exact list equality**, so a later silent widening fails the suite.

### P2 — `verify-tests-ran.sh` ignores `stop_hook_active`

Confirmed by reading: `main()` dispatched `tests` before any `json.load(sys.stdin)`, so the payload never reached the decision.

The first Stop/SubagentStop still blocks with the original message. When `stop_hook_active` is true, it does **not** return a silent `0`: it prints an explicit record to stderr and returns **exit 1**, a non-blocking error for Stop — the loop terminates and the operator is told the gate was unmet:

```
ScholarChain policy: UNVERIFIED STOP. The test gate was not satisfied, and this hook already ran for
this stop, so it is not blocking again. [original block message] Operator action: treat
.claude/.last-test-run as stale, run sbt check over the changed sources listed below, and record the
result before this work is reviewed, committed or ratified. Changed sources: [sorted paths].
```

Both original block messages are preserved verbatim, so the wording quoted in `restart-probes.md` and `restart-results.md` still holds.

Input contract: `stop_payload()` reads stdin for the `tests` action. `sys.stdin.isatty()` keeps a direct terminal invocation from hanging. Empty/whitespace input yields `{}`, leaving the gate **fully enforced** — the closed direction. Non-empty non-object input raises `ValueError`, which the untouched `main()` handler converts to the fail-closed deny. The existing probes already sent JSON on stdin via `probe()`, so every pre-existing stop probe passes unmodified. Nothing left unrepaired in bounds; live interception pending (section 6).

### P3 — read-only `git diff` / `git log` refused

**Verified independently, not taken on trust.** The orchestrator's diagnosis is correct. Live refusals observed before any change:

```
$ ls -la .claude/hooks .../sandbox-repair/
ScholarChain policy: Use structured file tools for constitutional paths and the factory workflow for edits.
$ git diff --stat HEAD~1 -- .claude/hooks/policy.py          → same message
$ git diff --stat HEAD~1 -- docs/agents.md                   → same message
$ git log --oneline HEAD~3..HEAD -- CLAUDE.md                → same message
```

The constitutional token scan is the primary and sufficient cause: it precedes the `READ_GIT` allowance and admits only two exact argv tuples. Control case: `git log --oneline -3` and `git show --stat HEAD` succeeded *before* any change, because neither names a constitutional path.

On the two contributing causes:

- **Metacharacter branch (`policy.py:65`) — contributing, not blocking for review work.** Plain ranges pass it: `git log --oneline HEAD~3..HEAD` reached the constitutional branch, so `~` and `..` are fine. It does refuse brace-bearing revisions — `git log --oneline HEAD@{1}` was denied live with "Use explicit commands and paths...". I did **not** relax it; not authorized, and commit ranges work without it. Open item 4.
- **`SENSITIVE` scan (`policy.py:75`) — not a cause for constitutional paths, and deliberately still a cause for secret paths.** Kept, and placed before the new allowance. `git diff -- .env` is still refused live with "Sensitive file access is prohibited."
- **Chained commands** — refused by the metacharacter branch, as reported. Unchanged.

**Narrowing versus widening of other `READ_GIT` verbs:** neither. `status`, `ls-files`, `rev-parse` and `branch` keep their pre-existing allowance untouched and still hit the constitutional scan when naming a constitutional path — probed via `git status --short .claude/settings.json`, `git ls-files .claude/hooks`, `git rev-parse --show-toplevel .claude`, `git branch --contains .claude`, all still exit 2. The change is additive and strictly scoped to the four forms; for non-constitutional paths it adds nothing, since `READ_GIT` already allowed those.

**`permissions.allow` needs no new entries**, justified from observed behavior: it pre-allows `Bash(git status --short)` and `Bash(git diff --stat)`, yet `git log --oneline -3`, `git show --stat HEAD` and `git diff HEAD~1` all ran without a prompt despite not being listed, while `git diff --stat HEAD~1 -- docs/agents.md` — whose prefix *is* listed — was still refused by the hook. The PreToolUse hook, not the permission allowlist, is the deciding control, so the guard change alone suffices. Adding the forms would widen the permission surface unnecessarily and mask hook behavior behind a pre-approval.

**P4 side effect: none.** `DIRECT_DANGER` is evaluated at `policy.py:77`, before the new branch, so its space-joined matching is unchanged and stays recorded as non-blocking.

## 3. Probes

**Direct script probes only.** Per `CLAUDE.md` these demonstrate script behavior and are **not** evidence of automatic Claude Code enforcement. Added to `.claude/hooks/probe_policy.py`, run via the already-allowed `.claude/hooks/probe-policy.sh`. No cloud credentials, no network, no real secrets; P2 probes run in a disposable `tempfile` git checkout.

- **P1 — `test_settings()`**: exact-equality assertions that `allowWrite` is exactly the three entries; sandbox enabled with `failIfUnavailable` true and both unsandboxing switches false; `denyWrite` is the exact 7-entry constitutional list; `denyRead` the exact 25-entry secret list; `sandbox` has no `network` key and exactly five keys; `permissions.deny` the exact 51-entry list; `permissions.allow`, both modes and `enableAllProjectMcpServers` unchanged. Then guard both directions: `sbt check` exit 0 for `domain-engineer`, exit 2 for `deploy-engineer`, `sbt publish` exit 2.
- **P3 — `test_review_git()`**: 14 allowed cases (each form plain, with a range, with a constitutional pathspec) expecting 0; 29 refusals expecting 2, covering the five banned options on the new forms, near-miss forms naming a constitutional path (`git log -- CLAUDE.md`, `git show -- CLAUDE.md`, `git diff --cached -- CLAUDE.md`), the four other `READ_GIT` verbs, write/checkout/restore/add/apply/rm/stash/commit verbs, other executables naming constitutional paths (`ls`, `cat`, `rg`), the sensitive-path scan on all four new forms, and the reviewer's total lack of shell authority.
- **P2**: stale marker first stop → 2; `stop_hook_active: false` → 2; `true` → 1; missing marker first stop → 2 and repeat → 1; malformed `{not json` → 2; non-object `[]` → 2; empty stdin → 2; fresh marker → 0 both with and without `stop_hook_active`; plus an assertion that the non-blocking path wrote no file and did not touch the marker mtime.

### Commands run and observed results

| Command | Result |
|---|---|
| `.claude/hooks/probe-policy.sh` (baseline, pre-edit) | 52/52 PASS |
| `sbt check` (pre-P1-edit) | exit 1, `sbt.boot.lock (Operation not permitted)` — P1 reproduced |
| `sbt check` (post-P1-edit) | exit 1, boot lock resolved, fails later at `Could not create temporary directory` under the wrong JVM |
| `.claude/hooks/probe-policy.sh` (first post-edit run) | **1 FAIL** — `P1 sandbox denyRead still covers secret and credential paths`; my `len(...) == 26` assertion was wrong, the list has 25 entries. Replaced the brittle count with exact list equality. Reported because it ran and failed. |
| `.claude/hooks/probe-policy.sh` (final) | **118/118 PASS** |
| `.claude/hooks/validate-agent-files.sh` (post doc edits) | PASS ×4: 18 agents with explicit tools and reviewer fence; 18 ownership rows and 18 unique routes; 5 commands resolve to owners; 2 JSON files parsed, 5 shell scripts checked, executable hooks and event wiring verified |
| Live `git diff --stat HEAD~1 -- docs/agents.md`, `git log --oneline HEAD~3..HEAD -- CLAUDE.md`, `git diff --stat` | allowed after the change (refused before) |
| Live `ls -la .claude/hooks` | still refused — "Use structured file tools for constitutional paths..." |
| Live `git diff -- .env` | still refused — "Sensitive file access is prohibited." |
| Live `git log --oneline HEAD@{1}` | refused by the metacharacter branch; known limitation, not repaired |
| Live `Write docs/agent-runs/.../repair-report.md` | refused — "The factory owns the declared constitutional files only." See the blocker above. |

`sbt check` has **not** been observed to pass end to end from inside an agent turn. That remains the open P1 acceptance criterion.

## 4. Effective sandbox write boundary after the change

Allowed: `/dev/stdout`, `/dev/stderr`, `/dev/null`, `/dev/tty`, `/dev/dtracehelper`, `/dev/autofs_nowait`, `/tmp/claude`, `/private/tmp/claude`, `~/.npm/_logs`, `~/.claude/debug`, the project directory, `$TMPDIR`, and now `~/.sbt`, `~/.cache/coursier`, `~/.ivy2`. Within that, `.claude/settings.json`, `.claude/hooks`, `.claude/agents`, `.claude/commands` and the user-level Claude configuration remain write-denied to Bash.

Confirmed not changed: no deny list (sandbox `denyRead`, sandbox `denyWrite`, `permissions.deny`), no network setting, no unsandboxing setting. Each covered by an exact-equality probe.

## 5. P2 trade-off statement

The gate trades strength for termination **only on a repeat stop**. The first Stop/SubagentStop blocks exactly as before, so normal operation is unchanged. On the second pass, blocking again cannot help: the agent cannot satisfy the gate within that turn, and Claude Code overrode the block after 9 attempts anyway — the old behavior burned 9 turns and then passed with **no record at all**. The new behavior gives up blocking once but leaves a labeled record naming the unverified sources, which is strictly more informative than the old silent override.

**What an operator must check when it fires:** the stop was allowed with the test gate unmet. Treat `.claude/.last-test-run` as stale, run `sbt check` over the named sources, record the result before review, commit or ratification. Do not read a completed turn as a passing check.

**State written: none.** No file, no marker, and `.claude/.last-test-run` is not touched. `guard()` at `policy.py:155` still refuses any agent write to the marker, which stays build-generated. Asserted by probe `P2 the non-blocking path writes no state and leaves the marker untouched`. The cost is that the record lives only in that turn's hook stderr; a durable trail needs a named owner and a ratified path — open item 3.

## 6. Authority surface after the change

- **Every role with a Bash tool** gains read-only `git diff`, `git diff --stat`, `git log --oneline`, `git show --stat` on constitutional paths, with ranges and pathspecs. Read only.
- **`code-reviewer` gains nothing.** No Bash tool, so the fence verified by restart probe 2 holds and the guard's reviewer branches stay unreachable defense in depth. It still receives the diff as evidence from the orchestrator.
- **All roles except `deploy-engineer` and `incident-responder`** can now run the already-permitted `sbt check` far enough to write sbt's caches. Whether it completes is open item 1.
- **Agents lose nothing.** No previously allowed command became refused.
- **The Stop gate weakens on repeat stops only**, per section 5.
- **No ownership boundary moved.** The matrix is unchanged except one clarifying sentence. No role gained or lost an owned path, no writer was added to any artifact class, no agent `tools` list changed, and `policy.py:158` still refuses non-factory constitutional edits (probed). Only `factory-engineer` may write the constitutional set, and only within the declared files — enforced live against me, as the report-write refusal above demonstrates.

**Pending, not demonstrated:** live Claude Code interception of the P2 Stop path is unobserved. The exit-1 non-blocking contract, and whether Claude Code surfaces the stderr record to the operator, must be confirmed in a fresh session; it is plausible that exit-1 stderr reaches the human but not the model, and I did not verify this. Treat P2 as script-verified only. P1 and P3 behavior *was* observed live here, which is stronger than a script probe but is still a subagent observation, not a fresh-session orchestrator probe. Recommend restart probes 10 (repeat-stop record), 11 (`git diff --stat` on a constitutional pathspec plus the adjacent `ls` refusal) and 12 (`sbt check` inside an agent turn). Drafting them belongs to a follow-up order; the orchestrator inserts them.

## 7. Open items and gates

**1. Agent `sbt` runs must select the recorded JVM, and the guard currently prevents it.** Evidence in section 2. Two candidate remedies, neither implemented:
- *(preferred)* pin the toolchain so bare `sbt check` is correct — `.sbtopts` / `.jvmopts` or a `project/build.properties` JVM pin. **BLOCKED-ON `build-engineer`**: those are its owned outputs. I did not create them.
- *(factory-owned alternative)* widen `policy.py:120` to accept a fixed `JAVA_HOME` prefix for `sbt check`. Widens the guard, not authorized by this order. Not implemented.

**2. The macOS Coursier cache is outside the three authorized entries.** `~/.cache/coursier` **does not exist** on this machine, so that entry is inert here. The real cache is `~/Library/Caches/Coursier`, populated by the human's successful run at `.../v1/https`. `~/Library/Caches/coursier` and `~/Library/Caches/Coursier` are the **same directory** — both inode `23326420`, case-insensitive filesystem — so one entry suffices. Exact proposed addition, **not added**, for human approval:

```json
"~/Library/Caches/Coursier"
```

Why: without it, any sbt run that must fetch a dependency cannot write its cache. Not yet *proven* necessary — my run resolved from the existing cache and never downloaded — but it will bite on the first dependency change. It holds downloaded artifacts only, no credentials, and sits outside the repository, so it cannot bypass an ownership boundary. Keeping `~/.cache/coursier` alongside it is harmless and covers non-macOS runtimes.

**3. The P2 unverified-stop record is not durable.** It exists only in hook stderr for that turn. An append-only audit file under `docs/agent-runs/` is the obvious remedy, but that path is **not** factory-owned, so I did not create it. Needs a human decision on the exact path and owner before any hook writes state. Not implemented.

**4. Known limitation, not repaired.** `git log --oneline HEAD@{1}` is refused by the metacharacter branch at `policy.py:65`. Relaxing it was not authorized and commit ranges work without it.

**5. P4 remains open and untouched**, as directed.

## 8. `BLOCKED-ON`

- **`BLOCKED-ON orchestrator`** — archive this report at `docs/agent-runs/phase-01/sandbox-repair/repair-report.md`. The guard correctly refused my write; `docs/agent-runs/**` is not factory-owned.
- **`BLOCKED-ON build-engineer`** — open item 1(a). Evidence: the Java-21 bridge jar `..._2.12.20__65.0-...` versus the observed `welcome to sbt 1.11.7 (Homebrew Java 27.0.1.0.3)`, and the resulting `Could not create temporary directory` at `ZincComponentCompiler.compileAndInstall`. Until resolved, P1's acceptance criterion — an agent completing `sbt check` in its own turn — is **not met**, even though the specific boot-lock failure in the finding is fixed.
- **`BLOCKED-ON human`** — open items 2 and 3 require ratification before any further change.

---

Not committed. Not ratified. `factory-engineer` does not approve its own work. Hand to `code-reviewer` for independent read-only review, then to the human for ratification and a fresh orchestrator session.

**Files changed:**
- `/Users/winnguyen/ScholarChain/.claude/settings.json`
- `/Users/winnguyen/ScholarChain/.claude/hooks/policy.py`
- `/Users/winnguyen/ScholarChain/.claude/hooks/probe_policy.py`
- `/Users/winnguyen/ScholarChain/CLAUDE.md`
- `/Users/winnguyen/ScholarChain/docs/agents.md`
