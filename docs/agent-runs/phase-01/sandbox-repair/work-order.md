# Work order: Phase 1 sandbox and guard repair (P1, P2, P3)

- Date: 2026-11-05
- Phase: 1
- Owner: `factory-engineer`
- Dispatched by: orchestrator (main session)
- Source findings: [`../orchestrator-update/restart-results.md`](../orchestrator-update/restart-results.md) findings P1, P2, P3
- Base revision: `751d926` (branch `main`, clean tree)
- Gate: constitution audit, independent read-only review by `code-reviewer`, then human ratification and a fresh orchestrator session before use.

## Authority

Within the owned set only: `.claude/settings.json`, `.claude/hooks/**`, and `CLAUDE.md` / `docs/agents.md` where this repair changes documented behavior. No other paths. This order does not widen factory authority; it narrows the task to the three findings below.

The human has authorized exactly the changes stated per finding. **Do not widen beyond them.** If a finding cannot be fully repaired inside the stated bounds, implement what is in bounds, then report the remainder as an open item with the exact proposed change and the evidence for it, for human ratification. Do not self-approve and do not ratify.

## P1 — Sandboxed agents cannot run `sbt check`

Evidence: probe 5, session 3. sbt cannot write `~/.sbt/boot/sbt.boot.lock`; that path is outside the sandbox write allowlist. The agent's `dangerouslyDisableSandbox` workaround was refused by the guard. As configured, the Stop/SubagentStop gate in `.claude/hooks/policy.py:231` (`verify_tests`) cannot be satisfied from inside an agent turn, which blocks all Phase 4 implementation work.

Authorized change: add **only** sbt's cache directories to the sandbox write allowlist, using the documented Claude Code setting:

- `~/.sbt`
- `~/.cache/coursier`
- `~/.ivy2`

Documented setting (verified against the Claude Code sandboxing docs, 2026-11-05): `sandbox.filesystem.allowWrite`, an array under the existing `sandbox.filesystem` block in `.claude/settings.json`, alongside the current `denyRead` and `denyWrite`. Home-relative `~` prefixes are supported in these arrays (`"allowWrite": ["~/.kube", "/tmp/build"]` is the documented example); absolute paths are plain absolute. This differs from Read/Edit permission rules, which use `//path` for absolute. Scope arrays merge rather than replace.

Required verification and reporting:

1. Confirm `allowWrite` is the key this runtime actually honors, and that adding it is **additive** — that it does not replace the sandbox's existing implicit write allowance for the project directory and `$TMPDIR`. Report the observed effective write boundary.
2. Confirm the three paths are sufficient on this runtime. **Known risk:** on macOS, Coursier's default cache is commonly `~/Library/Caches/Coursier`, not `~/.cache/coursier`. If `sbt check` still fails on a cache path outside the three authorized directories, do **not** add it. Report the exact failing path, the exact minimal additional entry you propose, and why, as an open item for human approval.
3. Do not relax `allowUnsandboxedCommands`, `autoAllowBashIfSandboxed`, `failIfUnavailable`, any `denyRead`/`denyWrite` entry, or any network setting. Confirm in the report that none changed.
4. `sbt` is already permitted for most roles at `policy.py:120` (`sbt check` / `sbt Test/compile`, excluding `deploy-engineer` and `incident-responder`). Confirm whether the guard needs any change for P1; if it does not, say so rather than editing it.

## P2 — `verify-tests-ran.sh` ignores `stop_hook_active`

Evidence: probes 1–2, session 1. An unsatisfiable Stop block repeated until Claude Code overrode it after 9 consecutive blocks.

`verify-tests-ran.sh` execs `policy.py tests`, which reads no stdin payload at all (`policy.py:242`), so the `stop_hook_active` field in the Stop/SubagentStop payload never reaches the decision.

Authorized change: handle `stop_hook_active` **without silently dropping the gate**. A plain early `return 0` on `stop_hook_active` is not acceptable: it converts a real unmet gate into a silent pass. Implement a path that terminates the loop while leaving the unmet gate visible and attributable — for example, allowing the stop once `stop_hook_active` is true while emitting an explicit, non-blocking record that the test gate was not satisfied, so the orchestrator and the human can see that the marker is stale rather than inferring success.

Required: document the trade-off explicitly — loop termination versus gate strength — in the change itself and in the report, including what an operator must check when the non-blocking path fires, and whether any state is written (if a record is written, name the exact path and its owner; `.claude/.last-test-run` is build-generated and `policy.py:155` must keep refusing agent writes to it).

Reading the payload for the `tests` action changes the hook's input contract. Keep the existing direct-probe invocation working or update the probes accordingly, and keep the `main()` exception handler's fail-closed behavior (`policy.py:249`) intact for malformed input.

## P3 — Read-only `git diff` / `git log` refused to the orchestrator

Evidence: review rounds; see [`../orchestrator-update/review.md`](../orchestrator-update/review.md). Reviews currently depend on human-supplied diffs.

Orchestrator diagnosis, to verify independently before relying on it: `guard_shell` applies the constitutional-path token scan at `policy.py:90-100` **before** the read-only git allowance at `policy.py:111`. `CONSTITUTION` (`policy.py:22`) matches any token containing `.claude/`, `CLAUDE.md`, `.mcp.json` or `docs/agents.md`. So a read-only review command whose pathspec names a constitutional file — exactly the Phase 1 review case — is refused with "Use structured file tools for constitutional paths and the factory workflow for edits," because only two exact argv tuples are allowed through that branch. Two further refusal paths exist and should be checked as contributing causes: the shell-metacharacter rejection at `policy.py:65` (which blocks `|`, `&&`, `>`, and also `~`-free but brace/glob-bearing revision syntax), and the `SENSITIVE` token scan at `policy.py:75`. Reproduced live in this session: `grep -n ... .claude/hooks/policy.py` was refused by the constitutional branch, and chained `ls` commands by the metacharacter branch.

Authorized change: allow **exactly** these read-only forms, including with commit ranges, and nothing more:

- `git diff`
- `git diff --stat`
- `git log --oneline`
- `git show --stat`

Bounds:

- Ranges and pathspecs must be accepted, including pathspecs naming constitutional files, which is the point of the repair.
- Keep the existing refusals for `--output`, `--ext-diff`, `--textconv`, `--exec-path`, `--no-index` (`policy.py:115-118`) on these forms, and keep the `SENSITIVE` scan effective so a diff cannot be used to read a secret path.
- Grant read only. No write, no checkout, no branch change, no `git push`, no `git apply`, no new executables, and no general relaxation of the constitutional token scan for other commands or for structured writes. `policy.py:158` must keep refusing non-factory constitutional edits.
- State in the report whether the repair narrows or widens the pre-existing allowance for other `READ_GIT` verbs (`status`, `ls-files`, `rev-parse`, `branch`) and keep that allowance unchanged unless the change is forced; if it is, name it as an open item.
- `.claude/settings.json` currently pre-allows `Bash(git status --short)` and `Bash(git diff --stat)` under `permissions.allow`. Decide and state whether the permission allowlist also needs the new forms, or whether the guard change alone is sufficient, and justify it from observed behavior.

## P4

Out of scope for this order. `DIRECT_DANGER` matching the space-joined token string (`policy.py:77`) stays recorded as non-blocking in `restart-results.md`. Do not fix it here. If a P3 change alters that behavior as a side effect, report it.

## Policy probes

Add a probe for each of the three changes, in the existing probe style, under `.claude/hooks/` (probe scripts are factory-owned; `.claude/hooks/probe-policy.sh` is already an allowed argv at `policy.py:96`). Each probe must:

- state what it asserts and the exact expected message or exit status;
- cover both directions — the newly allowed case passes **and** the adjacent refusal still holds (for P1, that unsandboxed escape and the deny lists are unchanged; for P2, that a stale marker still blocks on the first stop; for P3, that writes and non-read-only git are still refused);
- be runnable without cloud credentials, without network and without real secrets.

Keep direct script probes and live Claude Code interception as separate evidence, per `CLAUDE.md`. Direct probe passes demonstrate script behavior only. Do not report automatic enforcement from a direct probe; mark live interception as pending a fresh-session run, which the human performs.

## Required report fields

Write the report to `docs/agent-runs/phase-01/sandbox-repair/repair-report.md` and return the same content. Include:

1. Exact files and line ranges changed, per finding, with the rationale.
2. For each of P1, P2, P3: the authorized change as implemented, and anything in the finding left unrepaired, with the reason.
3. Probe definitions, the exact commands run, and the verbatim observed output, including failures.
4. The observed effective sandbox write boundary after the change, and confirmation that no deny list, network setting or unsandboxing setting changed.
5. The P2 trade-off statement, and any state the non-blocking path writes.
6. The authority surface after the change: what each role may now run that it could not before, stated as a diff of capability, plus a statement that no ownership boundary moved.
7. Open items and gates, each with the exact proposed change and the evidence, including any macOS Coursier cache path and anything requiring human ratification.
8. `BLOCKED-ON` with evidence and the responsible owner for anything outside factory authority.

Verification bounds: local only, bounded inputs, no cloud credentials, no live AWS, no network-dependent checks. Do not commit. Do not ratify. Stop after the report for independent review.

## Correction (orchestrator, 2026-11-05, post-dispatch)

The instruction in "Required report fields" to write the report to `docs/agent-runs/phase-01/sandbox-repair/repair-report.md` was an orchestrator error. `docs/agent-runs/**` is orchestrator-owned under `docs/agents.md`; it is not in the factory's declared set. The live guard refused the author's Write with "The factory owns the declared constitutional files only. Route this path to its owner," and the author correctly declined to widen `policy.py:160` to let itself comply, returning `BLOCKED-ON orchestrator` instead. The orchestrator archived the returned report at that path with attribution. A future work order must ask the specialist to return its report, not to write it under `docs/agent-runs/**`.

Incidental evidence from that refusal: `agent_type == "factory-engineer"` reached the hook and was enforced on a subagent Write, independently reproducing what restart probe 9 tested. It remains trusted rather than attested.
