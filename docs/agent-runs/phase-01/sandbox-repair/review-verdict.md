# Phase 1 sandbox-repair: independent review verdict

> **Archival note (orchestrator).** Author: `code-reviewer` (subagent), dispatched 2026-11-05. Tools: Read,
> Grep, Glob only — no shell, no repository write, no self-approval. The reviewer executed nothing; every
> runtime claim in the verdict is marked pending with a named owner.
>
> The review was dispatched once, terminated early on a usage limit (HTTP 429, no verdict produced), and then
> **resumed from its own transcript** rather than restarted, after `build-engineer`'s second `sbt check`
> measurement under Temurin 21 became available. Both measurement reports were in evidence.
>
> Evidence supplied by the orchestrator: [`review-diff.md`](review-diff.md) (the orchestrator produced the
> diff itself with `git diff`, using the read-only allowance the P3 change adds, because `code-reviewer` has
> no Bash tool), [`work-order.md`](work-order.md), [`repair-report.md`](repair-report.md),
> [`coursier-addition-report.md`](coursier-addition-report.md),
> [`sbt-check-measurement.md`](sbt-check-measurement.md) and
> [`sbt-check-measurement-temurin21.md`](sbt-check-measurement-temurin21.md).
>
> The verdict is preserved as received, with its author identified. The orchestrator records it and has made no
> edit to its content; HTML entity escapes applied in transit (`&lt;` in placeholder brackets) are restored.
> The orchestrator does not endorse or overrule it. Human ratification is the next gate.
>
> Findings F7 and F8 route archive cross-reference notes to the orchestrator; those are recorded in
> [`corrections.md`](corrections.md), and the preserved reports themselves were **not** edited.

- Date: 2026-11-05 · Phase 1 · Author: `code-reviewer` (subagent) · Reviewing the uncommitted working tree at base `751d926`
- **Verdict: REQUEST CHANGES** — 8 findings. No finding requires reverting the change set.
- Routing: F1-F5, F7 re-run → `factory-engineer`. Pending 1-2 → `build-engineer`. F7/F8 notes → orchestrator. Ratification and pending 6 → human.

---

## Verdict: REQUEST CHANGES

The code changes (P2, P3) are, as far as I can verify by reading, correct and within the authorized widening. The blockers are documentation that overstates a capability, two misleading comments/labels, probe-fixture fidelity, and an unresolved verification record. No finding requires reverting the change set.

What I verified by reading, and what I could not: I read `policy.py`, `probe_policy.py`, `settings.json`, `verify-tests-ran.sh`, `probe-policy.sh`, both CLAUDE.md/agents.md paragraphs on disk, the review diff and all four reports. I executed nothing — every runtime claim below is marked pending.

### Authority check (passed)

Verified against `/Users/winnguyen/ScholarChain/docs/agent-runs/phase-01/sandbox-repair/review-diff.md` and the files: the only changes to `/Users/winnguyen/ScholarChain/.claude/settings.json` are the four `allowWrite` entries at lines 101-106 (6 insertions, 0 deletions). `denyRead` (lines 74-100), `denyWrite` (107-115), `permissions.allow` (6-13), `permissions.deny` (14-66), `defaultMode`, `disableBypassPermissionsMode`, `enableAllProjectMcpServers`, all four sandbox switches (69-72) and the hook wiring (118-165) are unchanged. No `network` key exists. No file under `.claude/agents/**` is in the 5-file diffstat, so no tool list moved. `docs/agents.md` changed exactly one prose line; the ownership matrix and lifecycle table are untouched. **No widening beyond the four allowlist entries and the four git forms.**

P3 placement verified from code, not comment: `review_git(tokens)` is called at `policy.py:113`, after the metacharacter scan (85), the interpreter-wrapper scan (92), the `SENSITIVE` scan (94-96), `DIRECT_DANGER` (97), the remote-ref-delete scan (99) and the AWS scan (101), and **before** the `CONSTITUTION` token scan (115-125) and the generic executable dispatch (127+). The sensitive-path and destructive scans therefore keep full effect on the new forms, as claimed.

P3 narrowness verified: `review_git` returns True only if, after a matched form prefix, every remaining argument is `--` or does not start with `-`. Because the check covers arguments after `--` as well, no option can ride along in any position. A leading global option (`git -c core.pager=…  diff -- CLAUDE.md`, `git --exec-path=/tmp diff -- CLAUDE.md`) fails the prefix match, then hits the CONSTITUTION branch and is denied. The allowed set of `review_git` is a strict subset of what `policy.py:136-144` already allowed, so the sole behavioral delta is: those four forms may now name a constitutional path. That matches the authorization exactly.

P2 control flow verified for every input: absent/empty/whitespace stdin → `{}` → `deny(message)`, exit 2 (gate fully enforced); malformed JSON → `ValueError` → `main()` handler → exit 2; non-object JSON → explicit `ValueError` → exit 2; `stop_hook_active` false or absent → exit 2; true → stderr record + exit 1. `verify-tests-ran.sh:4` `exec`s with stdin inherited, so the payload reaches `stop_payload()`. The non-blocking branch at `policy.py:272-281` only calls `print(..., file=sys.stderr)`; it writes no file, and `guard()` at `policy.py:180-181` still refuses any agent write to `TEST_MARKER`. **P2 does not convert an unmet gate into a silent pass on any input I can construct by reading.**

---

## Findings, most severe first

**F1 — HIGH. `CLAUDE.md:45` overstates the capability the allowlist delivers, and misattributes the remaining blocker. Owner: `factory-engineer`.**

The sentence reads "The sandbox write allowlist admits sbt's cache directories … *so that an agent can satisfy the test gate itself*." Two measured runs say no agent can. `sbt-check-measurement.md:17-18` (exit 1, Zinc bridge, wrong JVM) and `sbt-check-measurement-temurin21.md:23-24, 82` (exit 1, `java.nio.file.FileSystemException: /var/folders/k6/.../T/inc_compile_2.12.zip…tmp: Operation not permitted`) both record the acceptance criterion as not met. As written, a future reader — or an agent reasoning about whether a stale marker is its own fault — will conclude the gate is satisfiable.

The same line's closing sentence is now factually wrong: "a different JVM forces a compiler-bridge rebuild that needs the JVM temporary directory, which the sandbox denies" ties the tmpdir denial to JVM drift. Under the correct JVM the bridge rebuild did not occur (`sbt-check-measurement-temurin21.md:88`) and the denial happened anyway, in `FileAnalysisStore$BinaryFileStore.set` (line 73). The tmpdir need is not conditional on the wrong JVM.

Required: state that the allowlist removed the boot-lock blocker only, that `java.io.tmpdir` under `/var/folders` is not writable and `sbt check` therefore cannot yet complete inside an agent turn, and that the test marker must be treated as stale until a human-run or newly-unblocked `sbt check` is recorded. Until this line is corrected I cannot approve the documentation half of the change set.

**F2 — MEDIUM. `policy.py:56-57`: the "longest prefix first" comment is false and invites an unsafe refactor. Owner: `factory-engineer`.**

The comment asserts that `REVIEW_GIT` ordering matters so `git diff --stat` is "matched as its own form rather than as `git diff` with a rejected option." It does not matter. The loop at `policy.py:75-79` `continue`s on both a prefix mismatch *and* a failed tail check, returning True if any form succeeds; the result is a plain OR over the four forms and is order-independent. Trace: `git diff --stat` under a reversed table would fail the `("diff",)` tail check on `--stat`, then match `("diff","--stat")` and return True — same answer. The comment documents an invariant the code does not rely on, which is worse than no comment: a maintainer who later converts the loop to `break`-on-first-prefix-match will believe ordering protects them. Either delete the ordering claim or make the code actually depend on it.

**F3 — MEDIUM. `probe_policy.py:141, 162`: the P3 fixture uses an `agent_type` that does not exist, and so never exercises the role the repair was for. Owner: `factory-engineer`.**

`agent_type="code-reviewer-orchestrator"` is not one of the 18 agents in `docs/agents.md`, and it is not how the real caller appears: `docs/agents.md` records that the orchestrator's events carry **no** `agent_type`. I verified by reading that the outcome is nonetheless identical — `guard()` special-cases only the literal `"code-reviewer"` (`policy.py:165`), `review_git` never reads `agent`, and every refusal in that list resolves in an agent-independent branch — so this is a fidelity defect, not a false pass. But the suite currently proves the allowance for an invented role and proves nothing for `agent_type=None` (the orchestrator) or for a real shell-bearing specialist. Add both: `agent=None` and one matrix role (e.g. `build-engineer`), and drop the invented name so the fixtures stay checkable against the ownership matrix.

**F4 — MEDIUM. `probe_policy.py:131-141`: 8 of the 14 "P3 allowed" cases are non-discriminating, and one of them mislabels behavior the new code refuses. Owner: `factory-engineer`.**

`git diff`, `git diff --stat`, `git log --oneline`, `git show --stat`, `git diff HEAD~1..HEAD`, `git diff --stat HEAD~1 HEAD`, `git show --stat HEAD` and `git log --oneline -3` contain no constitutional token, so they already returned 0 before this change through the pre-existing `READ_GIT` branch (`policy.py:136-144`). They pass identically with `review_git` deleted; only the six pathspec cases at lines 134-139 actually test the new allowance. Specifically, `git log --oneline -3` (line 132) is labeled "P3 allowed" but `review_git` **rejects** it (`-3` starts with `-`); it passes only via the old branch. The consequence is an unprobed and undocumented practical limit on the review use case itself: `git log --oneline -3 -- CLAUDE.md` is **refused**, while `git log --oneline -3` is allowed. Add that refusal as an explicit probe, and either document the limit in `CLAUDE.md:49` or seek authorization to admit bare `-<n>`. (Related and lower: `CLAUDE.md:49` says "every other option on these forms … stay refused", which is accurate, but a reader will not predict that a commit count is one of them.)

**F5 — LOW/MEDIUM. `policy.py:272`: `stop_hook_active` is tested for truthiness, not identity. Owner: `factory-engineer`.**

`(payload or {}).get("stop_hook_active")` opens the non-blocking path for any truthy value, including the string `"false"`, `1`, or a non-empty list. The documented contract (`policy.py:286-288`, `CLAUDE.md:47`) is a boolean, and every other validation in `stop_payload()` fails closed. Use `is True` and add a non-boolean probe; the existing probes cover only `True`/`False`/absent (`probe_policy.py:230-241`). The payload comes from Claude Code rather than the agent, so exploitability is low — this is fail-closed hygiene on the one path that deliberately weakens a gate.

**F6 — LOW, and PRE-EXISTING, not a regression. Two read surfaces wider than `CLAUDE.md:49` implies.**

(a) `git diff <path> <path>` with at least one path outside the work tree triggers git's *implicit* `--no-index`, reading arbitrary files; the explicit `--no-index` refusal at `policy.py:141` does not catch it. This was already reachable via the `READ_GIT` branch before the change, and `review_git` neither adds nor removes it; the `SENSITIVE` scan still fences the credential classes. (b) `git show --stat HEAD:<path>` prints blob content, not a stat, so the new allowance can print the full text of a constitutional file. Read-only, and `code-reviewer` can already `Read` those files, so I rate the confidentiality impact nil — but it means "exactly … `git show --stat`" reads narrower than it behaves. Record both as known limitations rather than repairing them under this order; (a) is out of scope here.

**F7 — LOW. Probe-count discrepancy: adjudicated from source. 114 is right; 118 is wrong. Owner of the record correction: `factory-engineer` (re-run), orchestrator (archive note).**

I hand-counted every PASS-emitting call in the current `/Users/winnguyen/ScholarChain/.claude/hooks/probe_policy.py`: `test_commands` 43 (24 shell + 5 Read + 14 individual), `test_settings` 10 (7 `expect` + 3 `guard`), `test_review_git` 43 (14 allowed + 28 refused + 1 reviewer fence), `test_lifecycle` 18. **Total 114**, matching `coursier-addition-report.md:60` exactly. `repair-report.md:145`'s "118/118 PASS" cannot be correct for `probe-policy.sh` alone, since the coursier order changed no probe count (one retitle, one assertion). The most likely explanation, visible in the same table: `repair-report.md:146` records `validate-agent-files.sh` at "PASS ×4", and 114 + 4 = 118 — a conflation of two suites into one figure. This does **not** undermine the probe evidence: `probe-policy.sh:2` sets `-e` and `probe()`/`expect()` raise `SystemExit` on the first failure, so a run that reaches the final probe with exit 0 means all probes passed, whatever the operator's count. Pending: `factory-engineer` re-runs `.claude/hooks/probe-policy.sh` and records the verbatim final lines plus `$?`; the orchestrator adds a cross-reference note rather than editing the preserved report.

**F8 — LOW, bookkeeping. `repair-report.md:24, 133, 157` still describe `allowWrite` as "exactly the three entries" and list three in the effective boundary. Superseded by the human-approved fourth entry. Owner: orchestrator (archive note; do not edit the preserved report).**

---

## Verification adequacy, and whether the `sbt check` failures block ratification

What two runs establish, as single-run subagent evidence I could not reproduce: `~/.sbt` works (boot-lock mode gone; `~/.sbt/1.0/zinc/.sbt.cache.lock` written, `sbt-check-measurement.md:88`), `JAVA_HOME` propagates into a subagent's sandboxed Bash, and open item 1 (toolchain drift) is closed with no build-file change — the `.sbtopts` pin is withdrawn and I treat it as no action.

What remains unverified: **everything `check` was supposed to cover.** Across both runs, `build.sbt` never loaded, so `scalafmtCheck`, `scalafmtSbtCheck`, `Compile/compile` and `Test/test` ran on zero modules, no test count exists, and the marker was not written. The module graph and dependency pins are read-not-validated (`sbt-check-measurement-temurin21.md:132`). Three of the four allowlist entries are unexercised: `~/.cache/coursier` does not exist on this machine (`repair-report.md:186`), `~/Library/Caches/Coursier` was inert in both runs, and `~/.ivy2` is never mentioned as written. So the authorized P1 widening is **one-quarter demonstrated and three-quarters inert** — harmless, but unproven.

My judgment, split by component:

- **P1 is not closeable.** Its acceptance criterion is an agent completing `sbt check` in its own turn; that has now failed twice with zero check steps executed. P1 must be recorded as open with the new blocker, not as repaired.
- **The settings.json diff is still ratifiable**, with F1 fixed first. It is additive, it removed a real blocker, the four paths sit outside the repository and hold no credentials, and it cannot reach a repository path or an ownership boundary. Ratifying it does not imply the gate is satisfiable — provided the documentation stops saying it is.
- **P2 and P3 are independent of the sbt failure** and are verified to the level they claim (direct script probes; live interception pending). The `sbt check` failures therefore block **closing P1**, not ratification of the change set.
- One clarification the human should have: this change set does not itself owe an `sbt check`. `source_path()` (`policy.py:241-246`) covers `.scala`/`.java`/`.sbt`, `.scalafmt.conf` and `project/build.properties`; `.py`, `.json` and `.md` are excluded, so the Stop gate does not fire on these five files. The stale marker is a standing condition, not a defect of this work.

## Assessment of `build-engineer`'s `java.io.tmpdir` diagnosis — as a claim

**The observation is strong; one link is inference; the remedy framing is incomplete.**

Sound: the denied path and the frames are unambiguous — `Files.createTempFile` called from `FileAnalysisStore$BinaryFileStore.set` (`sbt-check-measurement-temurin21.md:72-73`), after `[info] done compiling` (line 65). The compile succeeded and only analysis persistence failed. The generalization "`java.io.tmpdir` is needed by *every* sbt compile, not just a bridge rebuild" follows directly: Zinc persists its analysis store through a temp file on every compile that writes analysis, so any compile in this sandbox fails at the same point. The author labels it load-bearing and labels the rest inference, which is honest.

Weak link, correctly labeled by the author (`sbt-check-measurement-temurin21.md:98`): the claim that the sandbox's `$TMPDIR` entry "points at the session scratch directory, so it does not cover the path the JVM actually chose." Nobody read `TMPDIR` inside the turn — all three env probes were guard-refused. On macOS `java.io.tmpdir` normally derives from `TMPDIR`, which by default *is* the `/var/folders/.../T` path, so the observation is equally consistent with the sandbox's `$TMPDIR` entry not expanding as expected, or with sbt/the launcher not inheriting a remapped `TMPDIR`. Those have different fixes. Unresolvable by reading.

**Is the proposed widening minimal? No — there is a narrower option neither agent named precisely.** `build-engineer` offers allowlisting the Darwin per-user temp dir (broad, shared scratch) or "redirecting `java.io.tmpdir` via a build file" (named only generically). The narrowest form of the second is to point `java.io.tmpdir` at a **project-local directory that is already inside the implicit project write allowance** — e.g. `-Djava.io.tmpdir=<project>/target/sbt-tmp` via `.jvmopts`/`SBT_OPTS`. That adds **zero** sandbox surface: no factory change, no new writable path outside the repo, and it leaves `/var/folders` denied. Caveats to test, not assume: `java.io.tmpdir` must already exist or `createTempFile` fails, so the directory needs to pre-exist (or be created by the launcher); `.jvmopts` affects the human's runs too; and the fix must be re-verified for the *main* build, not just the meta-build, since both runs died before `build.sbt` loaded. If that works, the `BLOCKED-ON factory-engineer` sandbox widening at `sbt-check-measurement-temurin21.md:124` is unnecessary — which is the outcome to prefer, since a shared scratch directory is a materially worse thing to make writable than a tool-specific cache. If it does not work, allowlisting the exact per-user `/var/folders/k6/<id>/T` path is still narrower than `/var/folders`.

I cannot execute anything, so this is an argument, not a result. It needs a run (below).

## Pending checks, with the owner who must run each

1. **`build-engineer`** — try `sbt check` with `java.io.tmpdir` redirected into an already-writable project-local directory, under an authorizing work order, and report whether the analysis-store write succeeds and whether `build.sbt` then loads. This determines whether any sandbox widening is needed at all. Also report the actual `TMPDIR` value inside the sandbox if a permitted probe can obtain it.
2. **`build-engineer`** — once unblocked, a full `sbt check` with the real counts (format, compile, test), so P1's acceptance criterion is measured rather than argued, and the marker is legitimately written.
3. **`factory-engineer`** — re-run `.claude/hooks/probe-policy.sh` after the F2-F5 repairs and record the verbatim tail, the exact PASS count and the exit code (F7).
4. **`factory-engineer`** — draft restart probes for live Claude Code interception; I confirmed by grep that `restart-probes.md` currently stops at probe 9, so the recommended probes 10 (repeat-stop record), 11 (`git diff --stat` on a constitutional pathspec plus the adjacent `ls` refusal) and 12 (`sbt check` in an agent turn) do not yet exist. Orchestrator inserts; the human runs them in a fresh session.
5. **Unverifiable by anyone without a live session** — whether Claude Code surfaces exit-1 stderr from a Stop hook to the operator. `CLAUDE.md:47` and the P2 design both depend on it. If it reaches neither the human nor the model, P2's "visible and attributable" property is not delivered and the trade reduces to a quieter silent override. Flagged by the author himself (`repair-report.md:178`) and still open; this is the single most important thing to observe in the fresh session.
6. **Human** — decision on repair-report open item 2 (the Coursier entry, inert in both runs, unproven in both directions) and open item 3 (durability of the P2 unverified-stop record, which lives only in that turn's stderr).

## Routing summary

- `factory-engineer`: F1 (`/Users/winnguyen/ScholarChain/CLAUDE.md:45`), F2 (`/Users/winnguyen/ScholarChain/.claude/hooks/policy.py:56-57`), F3 (`/Users/winnguyen/ScholarChain/.claude/hooks/probe_policy.py:141,162`), F4 (`probe_policy.py:131-141` plus possibly `CLAUDE.md:49`), F5 (`policy.py:272`), F7 re-run, pending item 4.
- `build-engineer`: pending items 1 and 2. No build file currently needs a change for the toolchain; the `.sbtopts` pin is withdrawn.
- Orchestrator: F7/F8 archive cross-reference notes. Do not edit the preserved reports.
- Human: ratification gates and pending item 6; also note that my earlier `CLAUDE.md` snapshot in this session predated the Coursier edit — I confirmed the on-disk text at `CLAUDE.md:45` matches `review-diff.md`, so there is no discrepancy there.

I do not approve my own work and I fixed nothing. Next gate: `factory-engineer` repairs F1-F5, re-runs the probe suite, returns the result to the orchestrator for a re-review of the repaired lines, then human ratification and a fresh orchestrator session.
