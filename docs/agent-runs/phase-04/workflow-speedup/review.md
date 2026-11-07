# Phase 4 workflow-speedup independent review verdict

> **Archival note (orchestrator).** Author: `code-reviewer` (subagent), dispatched 2026-11-06 with the
> work order, the owner report, the doc evidence and the orchestrator-produced `git diff -- CLAUDE.md .claude`
> as evidence. Preserved as received, with harness HTML-escaping of shell metacharacters restored to readable
> form (`&&`, `>`, `<`). No wording, finding, reference or qualification was altered.
>
> The reviewer has Read, Grep and Glob only and ran no commands. It states so itself. Its nine "pending" items
> are claims it could read but not replay; the orchestrator replayed them in
> [`validation.md`](validation.md), which **confirms R1 by direct measurement** and clears pending items 1, 2,
> 3 and 4.
>
> This verdict is the reviewer's own. It is not a ratification. The reviewer did not approve its own work and
> did not repair its own findings.

---

## Verdict: REQUEST CHANGES

Two required corrections, both in `CLAUDE.md`, both text-accuracy defects rather than authority or widening defects. The substance of the change — the sandbox exclusion, the model pins, the four rules, the role reconciliation — is sound and stays inside `factory-engineer` authority.

Status: COMPLETE (review delivered) · Owner of the reviewed change: `factory-engineer` · Work order: `/Users/winnguyen/ScholarChain/docs/agent-runs/phase-04/workflow-speedup/work-order.md` · Files I wrote: none · Commands I ran: none (no Bash tool).

---

## Required changes

### R1 — `/Users/winnguyen/ScholarChain/CLAUDE.md:51` states a guard refusal that the guard does not make

The line reads:

> `sbt checkAll`, `sbt check --foo`, `sbt -batch check` and `sbt "check"` do not match, and the guard refuses them before the sandbox is consulted.

The first three are correct. `sbt "check"` is not. Evidence in `/Users/winnguyen/ScholarChain/.claude/hooks/policy.py`:

- `policy.py:86` — the pre-lex refusal scans `[$`*?\[\]{}\n\r<>;|&]`. Quote characters are not in that set.
- `policy.py:88-90` — `shlex.shlex(command, posix=True)` with `whitespace_split=True` strips the quotes, yielding `["sbt", "check"]`. The comment on `policy.py:85` states this normalization is intentional.
- `policy.py:146` — `executable == "sbt" and len(arguments) == 1 and arguments[0] in CHECK_SBT` then returns `0`.

So the guard **permits** `sbt "check"`, `sbt 'Test/compile'` and whitespace variants such as `sbt  check` (two spaces), while `sandbox.excludedCommands` is exact text match on the call text (doc evidence §2, `sandbox-exclusion-doc-evidence.md:47-49`), so none of those forms is excluded. They run sandboxed and fail exactly as the pre-edit baseline did (`sbt.boot.lock (Operation not permitted)`, report §3 row 2).

This is the one near-miss class where the two fences disagree, and it disagrees in the permissive-guard direction. It is **not** a security widening — the sandbox still contains those forms, so the failure mode is a confusing build error, not an escape. But the constitutional text asserts a refusal that does not happen, and that is the kind of claim a later phase will rely on. Fix the sentence to say that quoted and extra-whitespace forms pass the guard but are not excluded, and therefore fail inside the sandbox.

Related probe gap: the near-miss list at `/Users/winnguyen/ScholarChain/.claude/hooks/probe_policy.py:150-154` covers `sbt checkAll`, `sbt check --foo`, `sbt -batch check`, `sbt Test/compileAll`, `sbt clean check`, `sbt check Test/compile` — every one of which the guard refuses. It omits the only near-miss the guard allows. Add a probe pinning `guard(..., 0, "domain-engineer", command='sbt "check"')` with a comment that the sandbox does not exclude it, so the asymmetry is recorded rather than discovered.

### R2 — `/Users/winnguyen/ScholarChain/CLAUDE.md:18` now contradicts `CLAUDE.md:49`

`CLAUDE.md:18` ends "In Phase 1 the build and check task are pending." `CLAUDE.md:49` records `sbt check` completing with 148 passing tests. The author flagged this in report §7 ("Observation, no action taken (out of scope)") and routed it onward. I do not accept the scope argument: Item 1 is precisely the item that makes this clause false, the clause sits in the same file the author rewrote, and `CLAUDE.md` is factory-owned so there is no authority obstacle. It is a one-clause deletion. If the human prefers strict scope, that is the human's call to record at ratification — but shipping a constitution that contradicts itself about whether the check task exists is a defect, not a deferral.

---

## What I verified

**Silent widening — verified, none.** `/Users/winnguyen/ScholarChain/.claude/settings.json:68-115`: the `sandbox` object's only change is the `excludedCommands` key at lines 73-76. `enabled`, `failIfUnavailable`, `autoAllowBashIfSandboxed: false`, `allowUnsandboxedCommands: false` are unchanged (lines 69-72). `filesystem` contains exactly `denyRead` (25 entries) and `denyWrite` (7 entries); there is no `allowWrite` key anywhere in the file, so no sandbox write allowlist was reintroduced. `permissions.allow` is still the same six entries (lines 6-13) — no `Bash(sbt check)` was added, matching report §2. `permissions.deny` is 51 entries, `defaultMode: "default"`, `disableBypassPermissionsMode: "disable"`, `enableAllProjectMcpServers: false`, hooks block unchanged. `excludedCommands` is exactly two entries, no `*`, no `:*`, no third entry.

**Guard consistency — verified, the two sets agree.** `policy.py:60` `CHECK_SBT = {"check", "Test/compile"}` and `policy.py:146-148` admit a bare two-token form only, to every role except `deploy-engineer` and `incident-responder`. That maps one-to-one onto the two exclusion entries, with the quoting exception in R1. `guard-dangerous.sh` and `policy.py` are absent from the diff and from the session-start `git status`, confirming the "no guard change" claim. Chaining, redirection, substitution and glob all still hit `policy.py:86-87` before any allowance. For `deploy-engineer`/`incident-responder` the sandbox would exclude a command the guard refuses — refusal wins, which is the closed direction, and `probe_policy.py:144-145` pins both.

**Gate-closure honesty — verified, with the R1 exception.** `CLAUDE.md:49` quotes the before/after measurements and attributes them to a `factory-engineer` turn rather than presenting them as general. `CLAUDE.md:51` records the exact-match limit, the "read silence as no coverage" warning, and the **network** widening in bold as the intended cost — all required by the work order's risk section. `CLAUDE.md:53` preserves the human `!`-prefixed run as the recorded phase gate and keeps the marker-stale rule. Neither flagged inference is written as settled fact: the "runs outside the sandbox" phrasing on line 49 rests on the documented mechanism (doc evidence §1 verbatim quote), not on the environment-delta inference, and `agent_type` delivery is untouched — `CLAUDE.md:61` still says native hook execution and subagent tool enforcement are pending and that a missing verified `agent_type` is an open gate.

**Probe strength — verified, the new probes bite.** `probe_policy.py:105-109` compares `excludedCommands` against a hardcoded literal and rejects any `*`; `probe_policy.py:96-102` asserts exact key-set equality including `excludedCommands`, so adding a `network` key or a fifth switch fails the suite. The 20 new guard probes at `probe_policy.py:142-166` go through `subprocess` against `guard-dangerous.sh` (`probe_policy.py:22-38`), so they measure the guard rather than restate the file. `sbt publish` (line 146) is what detects a `CHECK_SBT` widening; `sbt checkAll` (line 151) detects a prefix-match regression. Separation of evidence is intact: the docstring at `probe_policy.py:58-63` states the OS boundary and the network widening are not observable from the script, and `probe_policy.py:256-258` keeps the `apply_patch` probe labelled as a defensive script probe that is not evidence of native enforcement. No probe claims enforcement from file existence.

**Validator — verified.** `validate.py:75-76` uses set equality on `{"name","model","description","tools"}`, so a missing `model`, an extra key, or a stray key all fail. `validate.py:80-81` pins the value per role against `APPROVED_MODELS`/`DEFAULT_MODEL` (`validate.py:47-48`), so `opus` on a non-reviewer or `haiku` anywhere fails. A malformed key name cannot slip past: the `([a-z]+): (.+)` match at `validate.py:62` falls through to the continuation branch at `validate.py:72-73`, which requires two-space indent under `description`. The reviewer fence (`validate.py:86-87`) and the 18-role matrix check (`validate.py:88`) are unchanged, as is the ownership cross-check against `docs/agents.md` (`validate.py:102-107`).

**Authority — verified.** 22 changed files = 18 under `.claude/agents/`, `.claude/settings.json`, `.claude/hooks/validate.py`, `.claude/hooks/probe_policy.py`, `CLAUDE.md`. Every one is in the `factory-engineer` row of `/Users/winnguyen/ScholarChain/docs/agents.md:9`. No `tools:`, `name:`, `description:` or `- Owned output:` line is modified anywhere in the diff — all appear as context only. `docs/agents.md` is genuinely untouched: it is absent from the session-start `git status` modified list, and its gate column (`docs/agents.md:10-19,26`) says only "Run sbt check", which the change makes achievable rather than contradicting. `model: opus` on `code-reviewer.md:3`, `model: sonnet` on the other 17, 18 files total, matching the matrix.

**Role reconciliation — verified complete.** A content search of `.claude/**/*.md` for `sbt`, `sandbox` and the `!` prefix returns the new bullet on exactly the 10 roles whose `docs/agents.md` gate names sbt, and no residual text anywhere telling a specialist that a human must relay the check. `.claude/commands/**` contains no sbt or sandbox text. `deploy-engineer` and `incident-responder` correctly did not receive the bullet, matching `policy.py:147`.

**Rule 3 and Rule 4 — verified, not weakened.** `CLAUDE.md:27` ends "Batching reduces rounds and nothing else: a review still never fixes its own findings and never approves its own work." `code-reviewer.md:16` ends "Batching changes the number of rounds, never the bar: never fix your own findings and never approve your own work." `CLAUDE.md:28` ends "Brevity never omits a blocker, a failure or an open gate; name each one in chat and put its evidence in the file." All three bars survive.

---

## Non-blocking observations

- **N1.** `probe_policy.py:105-109` and `policy.py:60` assert the same fact against two independent literals. A future edit to `CHECK_SBT` alone desyncs the guard from the exclusion with no probe failing. A derived assertion — `set(sandbox["excludedCommands"]) == {f"sbt {task}" for task in policy.CHECK_SBT}` — would close that.
- **N2.** The Stop/SubagentStop gate described at `CLAUDE.md:55` changed strength and the file does not say so. A passing `sbt check` updates `.claude/.last-test-run`, so an agent's own run can now satisfy that gate without a human. Report §5 P4-D flags the loop as closeable-but-unobserved; one clause at `CLAUDE.md:53` or `:55` should record it.
- **N3.** Item 3 rule 1 was added to the 7 module source owners but not to `build-engineer.md` or `dependency-updater.md`, which also have primary gates. `CLAUDE.md:25` binds "the owning specialist" generally, so there is no gap in authority, only in role-file prompting.
- **N4.** `domain-engineer.md:15` ("Use Bash only for bounded checks and owned work expressly permitted by the work order") sits directly above `:16` ("Run `sbt check` and `sbt Test/compile` yourself"). Same pattern in the other six owners. Harmless, since the check is the role's standing gate, but a work order that forgets to mention Bash now reads ambiguously.
- **N5.** `validate.py:47-48` silently defaults any future role to `sonnet`. Documented in the adjacent comment and acceptable; worth knowing that adding a role never forces a model decision.

---

## Pending — I have no Bash tool and verified none of the following

Each item below is a claim I could read but not replay. The orchestrator or the human must run these and report the results.

1. `.claude/hooks/probe-policy.sh` — report §3 row 15 claims `SUMMARY: 156 probes passed, 0 failed` against a 136 baseline. I traced the suite's logic and counted 20 new probes, which reconciles, but I did not execute it.
2. `.claude/hooks/validate-agent-files.sh` — report §3 row 14 claims 4/4 PASS with the new model line in the output string. Source review says it should pass; unexecuted.
3. **Confirmation of R1.** Run the guard directly on `sbt "check"`, `sbt 'Test/compile'` and `sbt  check` (two spaces). I predict exit 0 from `policy.py:146` in all three cases. If that is what happens, R1 is confirmed as written; if the guard refuses, R1 reduces to a wording nit and I withdraw it.
4. `sbt check` exiting 0 with `Passed: Total 148, Failed 0, Errors 0, Passed 148`, and the pre-edit `sbt.boot.lock` baseline. Only the author's report attests to these. The strongest claim in this change rests on an unreplayed transcript.
5. Whether the command actually executed outside the sandbox rather than inside a more permissive one — the author's own flagged inference. Not resolvable from files.
6. The network widening — unmeasured by the author (report §6.7, warm cache, nothing downloaded). Documented, not observed.
7. `agent_type` delivery on the author's `Edit` calls — unobserved, correctly left open.
8. Restart probes P4-A through P4-D (report §5) — all require a fresh session. P4-A in particular matters: the exclusion was observed live only for the `factory-engineer` turn that authored it, never for a non-authoring role.
9. That nothing outside the diff's `CLAUDE.md .claude` pathspec changed. I relied on the session-start `git status` snapshot, which lists exactly the 22 files plus untracked `docs/agent-runs/.DS_Store` and the orchestrator-owned `docs/agent-runs/phase-04/workflow-speedup/`. A `git status --short` at review time would confirm it still holds.

## Next gate

Repair round on R1 and R2 by `factory-engineer` — batched, both in `CLAUDE.md`, plus the optional probe addition in `probe_policy.py`. Then re-review limited to those items and anything the repair touches, then human ratification, then a fresh orchestrator session. I do not fix my own findings and I do not approve my own work.
