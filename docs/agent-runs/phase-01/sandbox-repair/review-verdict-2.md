# Phase 1 sandbox-repair: independent re-review verdict

> **Archival note (orchestrator).** Author: `code-reviewer` (subagent), dispatched 2026-11-05. Tools: Read,
> Grep, Glob only — no shell, no repository write, no self-approval. The reviewer executed nothing; every
> runtime claim is pending with a named owner.
>
> **This was a deliberately fresh reviewer, not the first one resumed.** A reviewer confirming the repair of
> its own findings edges toward approving its own work, which `docs/agents.md` forbids. The orchestrator made
> that call and told the reviewer so, instructing it to treat [`review-verdict.md`](review-verdict.md) as
> evidence to verify rather than settled fact.
>
> Scope: the **new** changes only — `factory-engineer`'s F1-F5 repairs
> ([`review-repair-report.md`](review-repair-report.md)) and `build-engineer`'s `java.io.tmpdir` redirect
> ([`tmpdir-experiment-report.md`](tmpdir-experiment-report.md)). `.claude/settings.json` and `docs/agents.md`
> are byte-identical to what the first review examined and were explicitly out of scope. The orchestrator
> produced the scoped diff at [`review-diff-2.md`](review-diff-2.md) using the read-only allowance, since the
> reviewer has no Bash tool.
>
> The verdict is preserved as received, with its author identified. The orchestrator records it, has made no
> edit to its content, and neither endorses nor overrules it. HTML entity escapes applied in transit
> (`&amp;&amp;`, `&lt;`) are restored.
>
> Two orchestrator-owned items the reviewer routed: **B1 sequencing** (whether to ratify A alone first or hold
> both for a combined `CLAUDE.md:45` restatement) and routing of the B2/B4 decisions. Both are recorded as
> open and are the human's to settle; the orchestrator has taken no action on either.

- Date: 2026-11-05 · Phase 1 · Author: `code-reviewer` (subagent), fresh context · Uncommitted working tree at base `751d926`
- **Verdict: REQUEST CHANGES** — 6 findings (B1-B6). Nothing requires reverting.
- **Change set A (F1-F5 repairs): verified sound**, including the reverted `review_git` stub and the out-of-scope probe harness. Probe count independently hand-verified at **136**.
- Findings concentrate on change set B and on A↔B interaction created by parallel dispatch.
- P1: **cannot be closed.** Four runs, three failure modes, zero check steps ever executed.

---

## Verdict: REQUEST CHANGES

Two findings must be settled before the two change sets are ratified **together**; neither requires reverting anything. Change set A's F1-F5 repairs are, on their own terms, **sound** — I verified every one by reading, including the two the author flagged for verification (the reverted `review_git` stub and the out-of-scope harness). The findings below are almost entirely about change set B and about A↔B interaction.

I executed nothing. Every runtime claim is pending with a named owner.

---

## Part 1 — F1-F5 repairs: verified sound

**F2 — FIXED.** `/Users/winnguyen/ScholarChain/.claude/hooks/policy.py:56-58`. The comment now states the actual property ("tries every form and accepts if any one matches, so this table's order carries no meaning") and warns against a break-on-first-prefix refactor. That is a true statement about the loop at lines 76-81.

**F3 — FIXED.** `probe_policy.py:143`. `code-reviewer-orchestrator` is gone from the whole of `.claude/` (grep: no matches). Fixtures are now `(None, "build-engineer")`. `None` is correct for the orchestrator: `docs/agents.md` states its events carry no `agent_type`, and `guard()` reads it only through `payload.get("agent_type")` (`policy.py:166, 168, 183`), so both an omitted key and an explicit `null` yield `None`. The fixture encodes `"agent_type": null` rather than omitting the key — behaviourally identical under `.get()`, worth no action.

**F4 — FIXED.** All 11 commands in the `P3 allowed` loop (`probe_policy.py:144-155`) name a constitutional path, so each one depends on `review_git`; I traced two of the new ones by hand and they resolve to `deny` via the `CONSTITUTION` branch (`policy.py:116-126`) if `review_git` returns False. The 8 tokenless cases are relabeled `READ_GIT allowed, no constitutional path` with a comment (lines 158-159) that claims nothing for P3, and `git log --oneline -3` now sits in that group. The commit-count limit is both probed (4 refusals, lines 169-172, 2 commands × 2 fixtures, expect 2 — I traced both: `-3`/`-5` fail the tail check on every form, then hit `CONSTITUTION` → exit 2) and documented (`CLAUDE.md:49`, final three sentences). The costed `-\d+` proposal was correctly not implemented, and I agree with the author's recommendation against it: the flat "no argument after the prefix may start with `-`" invariant is worth more than the convenience, and `git log --oneline HEAD~3..HEAD -- CLAUDE.md` already covers the use case.

**The reverted stub — VERIFIED GONE.** `policy.py:67-81` is byte-identical to the baseline at `review-diff.md:68-82`: same docstring, same `if not tokens or tokens[0] != "git"`, same loop, same tail predicate `all(item == "--" or not item.startswith("-") …)`, same `return False`. `REVIEW_GIT` at `policy.py:59` is byte-identical to `review-diff.md:60` and still holds exactly four forms. No `return False` stub, no residual edit.

**F5 — FIXED.** `policy.py:275` is now `… .get("stop_hook_active") is True:`. I re-walked the full matrix from source: absent payload → `stop_payload()` returns `{}` → `deny` (2); empty/whitespace → `{}` → 2; malformed JSON → `ValueError` → `main()` → 2; non-object (`[]`) → explicit `ValueError` → 2; `False` → 2; `"false"` → 2; `1` → 2 (`1 is True` is False in CPython); `True` → stderr record, exit 1; satisfied gate + `True` → early `return 0` at line 266. New probes at lines 266-269 cover `"false"` and `1`. The non-blocking branch still only `print`s to stderr and writes no state, and `guard()` at `policy.py:181-182` still refuses any agent write to the marker. This is strictly a narrowing.

**The out-of-scope harness — VERIFIED HARMLESS.** `PASSES`/`record()` at `probe_policy.py:12-19`. `record()` only appends and prints. In `probe` (31-34), `raw_probe` (47-49) and `expect` (52-55), the `SystemExit` is raised *before* `record()` in every case, so `PASSES` grows only after an assertion has passed. No assertion, no expected exit code and no policy path changed. `SUMMARY` at line 322 is after all four suites and is unreachable once any probe raises. Confirmed harmless.

**Probe count — INDEPENDENTLY VERIFIED AT 136.** I hand-counted the current file: `test_commands` 43 (24 shell + 5 Read + 14 individual), `test_settings` 10, `test_review_git` 63 (11×2 allowed + 8 READ_GIT + 2×2 commit-count + 28 refused + 1 reviewer fence), `test_lifecycle` 20. **Total 136**, matching the author's self-reported figure exactly, and the +22 itemization (11+4+4+2+1) reconciles against the baseline 114. The `$?` caveat is honest and the reasoning holds from source: `probe-policy.sh:2` sets `set -euo pipefail` and line 4 `exec python3 -B probe_policy.py`, so the script's exit status *is* the interpreter's; `SystemExit("FAIL: …")` exits 1 and skips `SUMMARY`. A printed `SUMMARY: 136` therefore cannot coexist with a failure. I treat the count as trustworthy; the transcript itself remains the author's own evidence (pending below).

**Nothing widened.** Verified: `REVIEW_GIT` unchanged, `review_git` accept condition unchanged, and the sole behavioural delta in `policy.py` is F5's narrowing. Everything else in the A diff is comments, prose and probes.

**One residual note, not a finding.** `probe_policy.py:322` hardcodes `0 failed` rather than computing it. Correct today under fail-fast; it would lie if any future probe ever caught its own exception. Worth a comment, not a change.

---

## Part 2 — findings, most severe first

**B1 — MEDIUM/HIGH. `/Users/winnguyen/ScholarChain/CLAUDE.md:45` becomes inaccurate the moment change set B lands. Owner: `factory-engineer`; sequencing: orchestrator.**

The repaired line asserts: "`java.io.tmpdir` resolves to the macOS per-user directory under `/var/folders`, which is not writable in the sandbox, **so sbt's analysis-store write fails and `sbt check` cannot yet complete inside an agent turn.** Two measured runs failed this way with no check step executed."

With `/Users/winnguyen/ScholarChain/.jvmopts` present, `java.io.tmpdir` no longer resolves under `/var/folders` for sbt in this repo — the author observed the redirect taking effect, and the `FileAnalysisStore$BinaryFileStore.set` failure is gone (`tmpdir-experiment-report.md:77-81`). The *conclusion* ("`sbt check` cannot yet complete") stays true, but the stated *mechanism* and the stated *current blocker* are both superseded. This is the same class of defect F1 raised at HIGH — documentation that will mislead a future reader or an agent reasoning about whose fault a stale marker is. It is not `factory-engineer`'s error: the paragraph was accurate when written, and the author explicitly stated it "predicts nothing about `build-engineer`'s experiment." It is an artifact of parallel dispatch.

Two acceptable resolutions, the orchestrator's to choose: ratify A alone first and route a follow-on one-sentence `CLAUDE.md:45` edit with B; or hold both and have `factory-engineer` restate line 45 to say that the redirect removed the tmpdir blocker, that local socket binding is the current blocker, and that the marker is still stale. Either way the marker-is-stale sentence survives and stays correct.

**B2 — MEDIUM. `.jvmopts` is a new repository path with no owner in the ownership matrix. Owner: human (classification), then `factory-engineer` if it is to be added.**

`docs/agents.md` gives `build-engineer` an enumerated list — `build.sbt`, `project/**`, `.scalafmt.conf`, `.gitignore`, `compose.yaml`, `Dockerfile`, `packaging/**`, empty skeleton roots — and `.jvmopts` is not among them. The boundary rule is explicit: "For a new path, resolve its classification here before writing. Any unresolved collision or unowned artifact blocks the request until the human ratifies an assignment." The file is unambiguously build-configuration in substance and no other role has a competing claim, so the substantive risk is nil, but the gate was not walked. Note the interaction with B4: if the human chooses to gitignore it, the artifact becomes a per-machine local file rather than a repository artifact, and `.gitignore` *is* explicitly `build-engineer`'s — which resolves the ownership question as a side effect.

`target/sbt-tmp/.keep` raises no comparable question: `target/` is gitignored (`.gitignore:1`), so it is build output, not a tracked artifact. Creating it with Write crossed no boundary — I traced `guard()`: the path is inside the project, is not `.git`, is not `TEST_MARKER`, is not constitutional, and `guard()` applies no path fence to `build-engineer` at all (`policy.py:183-197` fences only factory, code-reviewer, test, deploy and service engineers). That last point is a **pre-existing** gap worth recording: the guard does not enforce `build-engineer`'s ownership row.

**B3 — MEDIUM. The redirect target lives inside `target/`, which `clean` deletes and a fresh clone lacks. A durable fix does exist inside `build-engineer`'s authority. Owner: `build-engineer`.**

The author's claim that a build-owned auto-create is impossible is **correct as stated**: `java.io.tmpdir` is consumed by the launcher JVM and by swoval/ipcsocket extraction long before `build.sbt` is evaluated, so no `build.sbt`-level task can create the directory in time. But the conclusion "no durable fix exists" does not follow — the fragility comes from the *choice of location*, not from the mechanism. Pointing `.jvmopts` at a **tracked, non-gitignored** directory (e.g. a repo-root `.sbt-tmp/.keep` committed to git) survives both `clean` and a fresh clone, keeps the zero-sandbox-surface property, and needs only a `.gitignore` entry for its contents — all within `build-engineer`'s row plus the same B2 classification question. I recommend that over the current `target/` location.

**B4 — the absolute path: diagnosis sound, defect acceptable for Phase 1, recommendation below. Decision: human.**

I verified both halves of the diagnosis independently, which is why I am comfortable calling it sound rather than plausible:
- `System.load` requiring an absolute path is standard JVM behaviour, and the quoted `UnsatisfiedLinkError: Expecting an absolute path of the library: target/sbt-tmp/…libswoval-files0.dylib` is that exact error. Observed, not inferred.
- The `$PWD` claim: `/opt/homebrew/Cellar/sbt/2.0.12/libexec/bin/sbt:808-816` (`loadConfigFile`) emits each line with `printf '%s\n' "$line"`, with an in-source comment saying it does so "to prevent shell expansion"; line 953 is `[[ -f ".jvmopts" ]] && export JAVA_OPTS="$JAVA_OPTS $(loadConfigFile .jvmopts)"`, a double-quoted assignment. A literal `$PWD` in `.jvmopts` would indeed reach the JVM unexpanded. The author's line citations are accurate.

For Phase 1 — one machine, no CI, nothing committed — the defect is acceptable, and leaving the file untracked-but-visible rather than silently gitignoring it was the right call for reviewability.

**My reasoning on the two remedies, not a ruling.** I lean to (a) gitignore it as a per-machine file, combined with B3's tracked directory and a documented line to paste. (b) keeps the mechanism reproducible from the repo but commits a path that is *wrong on every machine except this one*, including any future CI image — a tracked file that must be edited before use is worse than an absent one, because it fails confusingly rather than cleanly. The real cost of (a), which the human should weigh: an untracked `.jvmopts` silently alters every `sbt` invocation in this tree and will not appear in any future review diff. Mitigate by recording the required line in build documentation (`documentation-engineer`) so it is reproducible by instruction. If the project later needs a repo-reproducible redirect, that wants a launcher/wrapper mechanism, which is a different and wider order.

**B5 — LOW, but unreported. `.jvmopts` silently suppresses the launcher's default `JAVA_OPTS`. Owner: `build-engineer`.**

Launcher line 953 sets `JAVA_OPTS` from `.jvmopts`; line 956 then applies `default_java_opts` **only if `JAVA_OPTS` is empty**, and line 20 is `declare -r default_java_opts="-Dfile.encoding=UTF-8"`. So creating `.jvmopts` drops `-Dfile.encoding=UTF-8` from the sbt JVM. Practical impact here is nil — JEP 400 makes UTF-8 the default on the JDK 21 this build uses — so this is hygiene, not a bug. Either add `-Dfile.encoding=UTF-8` to `.jvmopts` or record the suppression. I note it mainly because it was not in the report and a reader would not predict it.

Two related corrections to the report's narrowness claim at `tmpdir-experiment-report.md:51`:
- "`.jvmopts` carries only JVM options for the sbt launcher's own JVM" understates it slightly: line 953 **exports** `JAVA_OPTS`, so the property reaches any child process that honours `JAVA_OPTS`. Still narrow, still read-as-system-property-only, but not strictly confined to one JVM.
- The forked-test claim is **correct today, verified**: `/Users/winnguyen/ScholarChain/build.sbt` sets neither `fork` nor `javaOptions` anywhere (I read all 146 lines; the only JVM-adjacent settings are `scalacOptions`/`javacOptions` `-release:17` at lines 5-6). Tests therefore run in the sbt JVM and inherit the property. It is a latent trap: the first module that sets `Test / fork := true` gets a fresh default `java.io.tmpdir` and the original failure returns, with no warning. Worth a comment in `.jvmopts` or `build.sbt`.
- Operational detail: line 953 tests `.jvmopts` relative to the **current directory**, not the project root. `sbt check` must be invoked from the repo root for the redirect to apply.

**B6 — LOW. The requested `sandbox.network.allowLocalBinding: true` would break a factory-owned probe. Owner: `factory-engineer`, same order as the settings change.**

`probe_policy.py:95-101` asserts `"network" not in sandbox and set(sandbox) == {"enabled", "failIfUnavailable", "autoAllowBashIfSandboxed", "allowUnsandboxedCommands", "filesystem"}`. Adding a `network` key fails that probe. Flagging so the widening order includes the probe update rather than discovering it as a red suite.

---

## Part 3 — the socket blocker: assessment

**"Pre-existing and previously masked" — I accept it, with one link labelled inference.** The classification that `.jvmopts` did not *cause* the denial is well supported: the frames are `UnixDomainServerSocket.bind` → `NativeErrorException: Error 1: Operation not permitted`, reached only once the ipcsocket JNI library could be extracted, which required a writable `java.io.tmpdir`. The sequencing is coherent even though `BootServerSocket` creation happens *before* the meta-build compile that failed in the earlier runs: in those runs the native library could not be extracted, so socket setup failed in a mode sbt tolerates and execution continued to the Zinc failure; in this run the library loaded, `bind()` was reached, and sbt misreported EPERM as `ServerAlreadyBootingException` and exited 2. The author also ruled out a stale socket by observing every socket directory empty. What is **inference**, correctly not claimed as observed, is that the sandbox has *always* refused local binding — no run has ever observed a bind succeed or be refused before this one, and there is no `network` key in `settings.json` to read, so the denial comes from a runtime default I cannot verify by reading.

**Is `allowLocalBinding` the minimum? Probably, but it is not demonstrated sufficient, and that distinction matters.** Three points:

1. The author's negative result on build-owned switches is supported by structure, not just by its own test: `BootServerSocket` is created in sbt's `xMain`/`getSocketOrExit` before any project loads, so nothing settable in `build.sbt` can reach it. The only levers are launcher-level properties — and `-Dsbt.io.virtual=false`, `-Dsbt.server.autostart=false` and `-Dsbt.boot.server=false` were tried with byte-identical failures. I cannot confirm from the repository whether some other launcher-level switch exists; that is a pending bounded check, not something I can rule out by reading.
2. **Security cost of local binding, stated plainly.** It adds no filesystem surface — the author is right about that, and it is materially narrower than making `/var/folders` writable. What it does cost is the property that a sandboxed process cannot create an endpoint reachable from outside the sandbox: any process running as the same user could connect to it, giving a bidirectional channel between sandboxed and unsandboxed code. Under this project's actual threat model — agent error and prompt injection, not a co-resident attacker — the marginal cost is low. It does not grant outbound network access.
3. **My recommendation on framing:** approve it, if at all, as a **bounded, revertible experiment** rather than a standing widening, with the explicit condition that it be reverted if `sbt check` still does not complete. Three runs have now each removed one blocker and revealed the next; there is no evidence this is the last one, and a widening that does not close P1 should not survive the experiment that failed to close it.

**P1 cannot be closed.** Four runs across three reports, three distinct failure modes, and `scalafmtCheck`, `scalafmtSbtCheck`, `Compile / compile` and `Test / test` have executed on zero modules in every one. `build.sbt` loading in the relative-path run is real progress and nothing more. **The sbt failures block closing P1; they do not block ratifying change set A or the `.jvmopts` mechanism in B.** Change set A is `.py` and `.md` only and owes no `sbt check` — I re-verified `source_path()` (`policy.py:242-247`) covers `.scala`/`.java`/`.sbt`, `.scalafmt.conf` and `project/build.properties`, and that `Path(".jvmopts").suffix` is `""`, so neither change set trips the Stop gate. The stale marker remains a standing condition.

---

## Pending checks, with owners

1. **`factory-engineer`** — the 136 PASS / exit 0 transcript is the author's own evidence from its own turn. My hand count independently confirms 136 is what the current file emits, and the fail-fast reasoning holds from source, but no third party captured the run. A human-run `.claude/hooks/probe-policy.sh` with a literally captured `$?` would close it.
2. **`factory-engineer`** — restart probes 10-12 (repeat-stop record; `git diff --stat` on a constitutional pathspec plus the adjacent refusal; `sbt check` in an agent turn) still do not exist; the human runs them in a fresh session.
3. **Human, live session only** — whether Claude Code surfaces exit-1 Stop-hook stderr to the operator. P2's entire "visible and attributable" property depends on it. Still the single most important live observation.
4. **`build-engineer`, after human approval** — bounded test of `sandbox.network.allowLocalBinding: true`, reporting whether `sbt check` then completes and what the next failure is if not. Also worth the same run: whether any launcher-level switch avoids `BootServerSocket` entirely.
5. **`build-engineer`** — a completed `sbt check` with real format/compile/test counts, so P1 is measured. Still pending after four runs.
6. **`build-engineer`** — re-verify the redirect after whichever B3/B4 remedy the human picks, since both change the directory's location or tracking.
7. **Human** — decisions on: B1 sequencing, B2 ownership classification of `.jvmopts`, B4 remedy choice, local socket binding, plus the carried-over Coursier entry (inert for a third time, still unproven in both directions) and P2 record durability.
8. **Unverifiable by reading** — whether the sandbox allowlist was in fact live during B's runs. The author's supporting evidence (in-tree writes to `project/target/config-classes` and `target/sbt-tmp`) is consistent with it but does not isolate the allowlist, since both paths are inside the project.

---

Files I read for this verdict: `/Users/winnguyen/ScholarChain/CLAUDE.md`, `/Users/winnguyen/ScholarChain/docs/agents.md`, `/Users/winnguyen/ScholarChain/.claude/hooks/policy.py`, `/Users/winnguyen/ScholarChain/.claude/hooks/probe_policy.py`, `/Users/winnguyen/ScholarChain/.claude/hooks/probe-policy.sh`, `/Users/winnguyen/ScholarChain/build.sbt`, `/Users/winnguyen/ScholarChain/.jvmopts`, `/Users/winnguyen/ScholarChain/.gitignore`, `/opt/homebrew/Cellar/sbt/2.0.12/libexec/bin/sbt` (lines 19-20, 800-830, 935-969), and all of `/Users/winnguyen/ScholarChain/docs/agent-runs/phase-01/sandbox-repair/`: `review-diff-2.md`, `review-diff.md`, `review-verdict.md`, `review-repair-report.md`, `tmpdir-experiment-report.md`, `corrections.md`.

Status: COMPLETE. Owner: `code-reviewer`. I wrote nothing, executed nothing, and fixed no finding. Next gate: orchestrator routes B1 and the B2/B4 decisions, then human ratification and a fresh orchestrator session.
