# Phase 1 sandbox-repair: final independent review verdict — APPROVE

> **Archival note (orchestrator).** Author: `code-reviewer` (subagent), dispatched 2026-11-05. Tools: Read,
> Grep, Glob only. The reviewer made no repository write, ran no shell command, and fixed no finding.
>
> This was the **third and final** review, and a deliberately fresh context — the third distinct reviewer
> instance on this change set. Each was fresh by design: a reviewer signing off on repairs to its own findings
> edges toward approving its own work, which `docs/agents.md` forbids. The human scoped this one to the
> **whole** change set rather than an incremental pass, and the orchestrator instructed it to re-derive P2 and
> P3 independently rather than inherit the two prior verdicts, to read `CLAUDE.md:45` adversarially (two prior
> HIGH/MEDIUM-HIGH findings were both in that paragraph), to look for what the earlier reviews missed, and
> **not** to re-litigate the human's decision — only whether the change set records it faithfully.
>
> The verdict is preserved as received, with its author identified. The orchestrator records it and has made
> no edit to its content; it neither endorses nor overrules it. HTML entity escapes applied in transit
> (`&lt;`) are restored.
>
> **A review verdict is not ratification.** `docs/agents.md` reserves that to the human, followed by a fresh
> orchestrator session before the change is relied upon.
>
> **One observation the orchestrator is carrying forward as a real operational item**, raised by the reviewer
> as a note rather than a finding: ten agent role files and the `CLAUDE.md` Project rules still instruct
> specialists to "Run `sbt check`" as their own gate. From Phase 2 on, each will attempt it, fail in the
> sandbox, and have to find `CLAUDE.md:45` to learn the failure is expected. That is a separate
> `factory-engineer` order, not a defect of this change set, and the reviewer was explicit it is not a reason
> to withhold approval.

- Date: 2026-11-05 · Phase 1 · Author: `code-reviewer` (subagent), fresh context, third reviewer · Uncommitted working tree at base `751d926`
- **Verdict: APPROVE.** No defect requiring a change before ratification. Three LOW observations, all optional.
- Change set: four files, 276 insertions, 8 deletions. P2 and P3 only; P1 withdrawn entirely; no new write surface.
- Probe count **136** independently hand-verified for the third time. Diffstat arithmetic independently reconciled.
- Ratification conditions: three, all procedural. Pending checks: seven, with owners.

---

I read the four changed files directly, plus the supplied diffs, the human decision record and both prior verdicts. I executed nothing.

# Verdict: APPROVE

The change set is sound. I found no defect that requires a change before human ratification. Three low-severity observations are below, all optional and none of them a condition of ratification. Every unexecuted check is listed as pending with its owner.

## What I verified by reading

**1. P3 (`review_git`) is as narrow as claimed, and correctly placed.** `/Users/winnguyen/ScholarChain/.claude/hooks/policy.py:67-81, 114-115`. Re-derived independently:

- Accept condition: `tokens[0] == "git"`, one of the four form prefixes in `REVIEW_GIT` (line 59), and every remaining argument is `--` or does not start with `-`. The tail predicate applies to arguments *after* `--` as well, so no option can ride along in any position. `--output`, `--ext-diff`, `--textconv`, `--exec-path`, `--no-index`, `--cached`, a bare `-3`, and leading global options (`git -c …`, `git --exec-path=… diff`) all fail — the last two because `arguments[0]` is then not the form verb.
- Placement: the allowance at line 114 comes *after* the metacharacter scan (86), the interpreter-wrapper scan (93), `SENSITIVE` on both the raw token and its resolved path (95-97), `DIRECT_DANGER` (98), the remote-ref-delete scan (100) and the AWS scan (102), and *before* the `CONSTITUTION` token scan (116-126). So the sensitive and destructive scans keep full effect, exactly as the docstring and `CLAUDE.md:49` state.
- It cannot reach a write, a checkout or command execution: only the literal verbs `diff`, `log`, `show` can match, and no argument may be an option. `git checkout`, `restore`, `add`, `apply`, `rm`, `stash`, `commit` and `difftool` all fail the prefix match and then hit the `CONSTITUTION` deny.
- Reviewer fence intact: `guard()` at `policy.py:166-167` denies Bash to `code-reviewer` before `guard_shell` is ever called, so I cannot reach this allowance.
- **Precise statement of the delta, which I re-derived rather than inherited:** `review_git`'s accepted set is a strict subset of what the pre-existing `READ_GIT` branch (lines 137-145) already allowed. The only behavioral change is that those four forms may now name a constitutional path. It adds no new arbitrary-file-read surface — the implicit-`--no-index` case the first review raised as F6(a) was already reachable through `READ_GIT` for non-constitutional paths and is unchanged in substance.

**2. P2 does not convert an unmet gate into a silent pass on any input.** `policy.py:257-298`. The only `return 0` paths are "no changed source files" (line 260) and "marker is newer than every changed source" (line 266) — both pre-existing. Walked from source: absent stdin, empty/whitespace stdin → `{}` → `deny` (2); malformed JSON → `ValueError` → `main()` handler → 2; non-object (`[]`) → explicit `ValueError` → 2; key absent or `False` → 2; `"false"` or `1` → 2, because `is True` is identity (line 275); `True` → stderr record, exit 1. Exit 1 is a non-blocking error, not a pass, and the branch only `print`s: it writes no state, and `guard()` at `policy.py:181-182` still refuses any agent write to the marker. `verify-tests-ran.sh:4` `exec`s with stdin inherited, so the payload does reach `stop_payload()`.

**3. `policy.py` is byte-identical to the state the second review verified.** I compared the file against the diff in `review-diff-2.md:73-187`: `REVIEW_GIT`, `review_git`, the corrected F2 comment, the `guard_shell` allowance, `verify_tests(payload)`, `stop_payload` and `is True` all match, and nothing else differs. `factory-engineer`'s two claims check out: there is no P1-specific code in `policy.py`, and `CHECK_SBT = {"check", "Test/compile"}` (line 60) appears as an unchanged **context** line in that diff, so it is pre-existing guard permission rather than P1 machinery. That distinction is correct.

**4. The P1 withdrawal is complete.** A repo-wide case-insensitive grep for `allowWrite`, `allowLocalBinding`, `jvmopts`, `sbt-tmp`, `/var/folders`, `coursier`, `ivy2` and `boot.lock` returns **nothing** outside `docs/agent-runs/**` (historical evidence, correctly preserved) except two lines in `probe_policy.py` (see observation C). `/Users/winnguyen/ScholarChain/.claude/settings.json` has no `allowWrite` key: `sandbox.filesystem` holds exactly `denyRead` and `denyWrite`, the four sandbox switches are unchanged, and `permissions.allow`/`deny` match the probe's exact-equality lists item for item. `.jvmopts` is absent. No agent file, `CLAUDE.md` or `docs/agents.md` references P1 machinery. **The change set grants no new write surface.**

**5. The inverted probe assertion is sound and is a tightening.** `probe_policy.py:67-70`: `"allowWrite" not in filesystem and set(filesystem) == {"denyRead", "denyWrite"}`. The key-set clause does genuinely preserve exact-equality discipline — re-adding `allowWrite`, or adding any other `filesystem` subkey, fails the suite. The first clause is redundant given the second, which is harmless. I checked the key-set clause against the actual file: `settings.json:73-110` has exactly those two keys. Correct.

**6. Probe count: 136, verified by my own hand count of the current file.** `test_commands` 43 (24 shell + 5 Read + 14 individual), `test_settings` 10 (7 `expect` + 3 `guard`), `test_review_git` 63 (11 commands × 2 fixtures + 8 `READ_GIT` + 2 × 2 commit-count refusals + 28 refusals + 1 reviewer fence), `test_lifecycle` 20. Total **136**. The zero delta across the withdrawal is right: one `expect` replaced one `expect`, one `guard` was renamed, and the docstring was restated — no arity change, no probe added or removed. `record()` is called only after the `SystemExit` in `probe`, `raw_probe` and `expect`, so `len(PASSES)` cannot over-count.

I also reconciled the diffstat arithmetic: 65 + 204 + 6 + 1 = 276 insertions, 6 + 1 + 1 = 8 deletions, which matches `276 insertions(+), 8 deletions(-)` across four files. The `probe_policy.py` 203 → 205 move with no new deletions is consistent with editing lines that are themselves additions.

**7. `CLAUDE.md:45` accurately records the accepted state.** It states that an agent cannot run `sbt check` in the sandbox; that this is an accepted open gate, "not a defect awaiting repair"; that the human runs it with the `!` prefix at the end of each phase and before every commit; and that `.claude/.last-test-run` is stale until a human run is recorded. All four required elements are present and match `repairs.md:9-11` verbatim in substance. It makes no mention of `.jvmopts`, `/var/folders`, the socket denial or `allowLocalBinding`; the withdrawn recorded-JVM advice is gone; and the "four measured runs / three distinct failure modes / no check step ever executed" summary matches the table in `repairs.md:36-40`. It does not contradict line 47 (both say treat the marker as stale), line 49, or the Phase 1 "build and check task are pending" rule earlier in the file. The two prior HIGH/MEDIUM-HIGH defects in this paragraph — a claimed capability (F1) and a superseded mechanism (B1) — are both gone, because the paragraph now claims no capability and describes no mechanism.

**8. `docs/agents.md:75` is pure P3 and is accurate.** One modified line within the existing orchestrator paragraph; the ownership matrix and lifecycle table are untouched. Its wording — "the guard now lets a **shell-bearing role** read constitutional files" — is more precise than `CLAUDE.md:49`'s "Reviews may read", and it correctly records that the allowance is read-only and moves no ownership boundary, and that `code-reviewer` cannot run these commands at all.

**9. This change set owes no `sbt check` — confirmed from `source_path()`, not from the prior reviews.** `policy.py:242-247`: `suffix in {".scala", ".java", ".sbt"}`, or posix path in `{".scalafmt.conf", "project/build.properties"}`, or `parts[0] in {"modules", "tests"}` with `"src"` in the parts. `.claude/hooks/policy.py` fails all three (`parts[0]` is `.claude`); `CLAUDE.md` and `docs/agents.md` fail all three. The Stop/SubagentStop gate does not fire on this change set. The stale marker is a standing documented condition, not a defect of this work.

**10. The `$?` reasoning is airtight as logic, and still owes a capture.** `probe-policy.sh:2-4` sets `set -euo pipefail` then `exec python3 -B probe_policy.py`, so the script's exit status *is* the interpreter's. `probe`/`raw_probe`/`expect` raise `SystemExit("FAIL: …")` on the first failure, which exits 1 with the message on stderr and skips the `SUMMARY` print at `probe_policy.py:324`, which is the last statement of `__main__`. A printed `SUMMARY: 136 probes passed, 0 failed.` with no `FAIL:` line therefore cannot coexist with a probe failure, and reaching it exits 0. The residual risk is not in the logic — it is that the observation of that line is the author's own transcript of its own turn. Only a third-party-run capture closes it. **Pending, owner: human.**

## Observations — all LOW, none a condition of ratification

**A. LOW, wording. `/Users/winnguyen/ScholarChain/CLAUDE.md:45`: "The sandbox write allowlist attempted during that work gained nothing."** The allowlist did remove one real blocker — the `~/.sbt/boot/sbt.boot.lock` failure (`sbt-check-measurement.md:88`, cited in `review-verdict.md:89`). "Gained nothing" is true of the *goal* and false of the *history*. The sentence's subject is the gate, so I read it as defensible rather than wrong, and the paragraph points at the evidence directory for the measurements. If `factory-engineer` ever edits this paragraph again, "removed one of three blockers and did not make the gate satisfiable" would be strictly accurate. Not worth an edit on its own. Owner if taken: `factory-engineer`.

**B. LOW, scope of a universal claim. `CLAUDE.md:45`: "An agent cannot run `sbt check` inside the shell sandbox."** This is stated as a general fact and is evidenced from four runs on one macOS workstation. It may not hold on a future Linux or CI runtime. Line 41 of the same section already says to "check sandbox availability … on the actual runtime before relying on it", which partly covers it. Recording this as environment-specific is optional. Owner if taken: `factory-engineer`.

**C. LOW, stale fixture — cosmetic, and I agree with leaving it. `/Users/winnguyen/ScholarChain/.claude/hooks/probe_policy.py:190`**, the refusal fixture `"rg allowWrite .claude/settings.json"`. I traced it: the guard denies this at `policy.py:116-126` on the `.claude/settings.json` token, and the search pattern is never examined, so the probe tests exactly what it should and its outcome is independent of whether `allowWrite` exists. It is cosmetic, not a staleness problem that could mask a regression. The only cost is that a future reader of this line might infer the key exists. `factory-engineer` flagged it rather than fixing it, which was the right call — churning a P3 fixture for a comment-level nit is worse. If reworded later, `rg sandbox .claude/settings.json` would read cleanly. Owner if taken: `factory-engineer`.

**One note on a risk the human's decision carries that I do not see recorded anywhere** (stated once, per my instruction, and not a finding): ten agent role files (`/Users/winnguyen/ScholarChain/.claude/agents/domain-engineer.md:22` and nine siblings) and the `CLAUDE.md` Project rules still instruct the specialist to "Run sbt check" as its own gate. From Phase 2 onward, every one of those specialists will attempt it, fail inside the sandbox, and have to discover `CLAUDE.md:45` to learn that the failure is expected and that the human owns the run. `CLAUDE.md:45` is shared project memory that every agent reads, so this resolves correctly on a careful read — but harmonizing the gate language in the role files with the accepted workflow would save each of those agents a wasted failing run. That is a separate order for `factory-engineer`, not a defect of this change set, and not a reason to withhold approval.

## Pending checks, with owners

1. **Human** — a third-party-captured `.claude/hooks/probe-policy.sh` run with a literal `$?`. The 136 PASS / exit 0 figure is the author's own evidence; I independently confirm 136 is what the current file emits and that the fail-fast logic makes a printed `SUMMARY` incompatible with a failure, but no third party has captured the run.
2. **Human, live session only** — whether Claude Code surfaces exit-1 Stop-hook stderr to the operator. P2's entire "visible and attributable" property depends on it; if it reaches neither the human nor the model, the trade reduces to a quieter silent override. Both prior reviews call this the most important remaining live observation and I agree.
3. **Human, fresh session** — observed live interception of the hooks and observed subagent tool enforcement, which `CLAUDE.md:53` already records as pending. Nothing in this change set closes it.
4. **`factory-engineer`, then human** — restart probes 10-12 (repeat-stop record; `git diff --stat` on a constitutional pathspec plus the adjacent refusal; the `sbt check` refusal/failure in an agent turn). `restart-probes.md` stops at 9. A separate order.
5. **Human** — the first `sbt check` under the newly accepted workflow, with real format/compile/test counts, so `.claude/.last-test-run` stops being stale. This change set does not owe it (point 9 above), but nothing has yet measured `check`.
6. **Human** — P2 unverified-stop record durability: the record exists only in that turn's hook stderr. Carried forward, unaddressed here.
7. **Pre-existing, recorded, not repaired here** — `guard()` applies no path fence to `build-engineer` (`policy.py:183-197` fences only factory, code-reviewer, test, deploy and service engineers), and F6's two read surfaces (`git diff` implicit `--no-index`; `git show --stat HEAD:<path>` printing blob content rather than a stat). I re-derived both: neither is widened by P3, and both are read-only against files every role can already `Read`. Closing either is a separate tightening order.

## Ratifiability

**Ready for human ratification.** Conditions that must hold first, all procedural rather than code changes:

- The human ratifies P3 as a constitutional widening. It is the only authority-affecting change in the set, it is read-only, and it is recorded in both `CLAUDE.md:49` and `docs/agents.md:75`. P1's withdrawal and P2 need no widening approval — P1 is a removal and P2 is a narrowing plus a loop-termination path that reports rather than passes.
- The three evidence items that are a single author's own self-report stay labelled as such: the 136 PASS / exit 0 transcript, the `validate-agent-files.sh` result, and the orchestrator's observation that `git diff -- .claude/settings.json` is now empty. I verified the *file contents* that back the first and third by reading; I did not verify the *transcripts*.
- A fresh orchestrator session after ratification, per `CLAUDE.md`'s one-session-per-phase rule.

My verdict is a review verdict only. It is not ratification, and it is not an `sbt check`.

---

Status: **COMPLETE**. Owner: `code-reviewer`. Work order: final independent whole-change-set review, Phase 1 sandbox-repair. Files created or modified: **none** — I made no repository write and no shell execution. Commands run: **none**. Test and validation results: none executed by me; all execution-dependent claims are in the pending list with owners. Assumptions: the orchestrator's diffs in `review-diff-final.md` and `review-diff-2.md` are faithful captures of `git diff` — I cross-checked them against the on-disk files and against the diffstat arithmetic, and found no inconsistency. Risks: items 1, 2 and 3 in the pending list are the only material unknowns, and none of them is introduced by this change set. Manual action required: pending items 1, 2, 3, 5 and 6 (human) and item 4 (`factory-engineer`, drafted on a separate order). Blocked-on: nothing. Next gate: human ratification, then a fresh orchestrator session.

Files I read for this verdict: `/Users/winnguyen/ScholarChain/CLAUDE.md`, `/Users/winnguyen/ScholarChain/docs/agents.md`, `/Users/winnguyen/ScholarChain/.claude/hooks/policy.py`, `/Users/winnguyen/ScholarChain/.claude/hooks/probe_policy.py`, `/Users/winnguyen/ScholarChain/.claude/hooks/probe-policy.sh`, `/Users/winnguyen/ScholarChain/.claude/hooks/verify-tests-ran.sh`, `/Users/winnguyen/ScholarChain/.claude/settings.json`, `/Users/winnguyen/ScholarChain/.claude/agents/` (grep), and under `/Users/winnguyen/ScholarChain/docs/agent-runs/phase-01/sandbox-repair/`: `review-diff-final.md`, `review-diff-2.md`, `review-diff.md`, `repairs.md`, `p1-withdrawal-report.md`, `review-verdict.md`, `review-verdict-2.md`, `corrections.md`.
