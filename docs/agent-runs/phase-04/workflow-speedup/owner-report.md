# Phase 4 workflow-speedup owner report

> **Archival note (orchestrator).** Author: `factory-engineer` (subagent), dispatched 2026-11-06 against
> [`work-order.md`](work-order.md). Preserved as received, with one mechanical restoration: the harness
> HTML-escaped shell metacharacters in transit (`&amp;&amp;`, `&gt;`, `&lt;`), and those are restored here to
> `&&`, `>`, `<` so the recorded commands are readable and replayable. No wording, claim, number or
> qualification was altered.
>
> **This report is the author's own evidence. It is not reviewed, not ratified and not a commit record.** The
> author states the P1 sbt gate closed on observed evidence; that closure is subject to the independent review
> and the human ratification named in section 9. The author explicitly does not approve its own work.
>
> **Orchestrator note on the strongest and weakest claims.** Strongest: `sbt check` exit 0 with 148 passing
> tests inside a subagent turn, against a pre-edit baseline of exit 1 on the `sbt.boot.lock` EPERM, with one
> settings key as the only delta. Weakest, and flagged by the author itself: that the post-edit run executed
> *outside* the sandbox rather than inside a more permissive one is **inference** from an environment delta
> (the disappearance of the `JAVA_TOOL_OPTIONS` and `socks5h` proxy lines), and whether `agent_type` reached
> the hook on the author's `Edit` calls remains **unobserved**, because an allow produces no output.

---

All four items are done. Final verification state: `validate-agent-files.sh` 4/4 PASS, `probe-policy.sh` 156/156 PASS (baseline was 136).

---

# Phase 4 workflow-speedup report — `factory-engineer`

- Status: **COMPLETE within bounds, not ratified.** Nothing committed. No `BLOCKED-ON`.
- Owner: `factory-engineer` · Work order: `/Users/winnguyen/ScholarChain/docs/agent-runs/phase-04/workflow-speedup/work-order.md`
- Base: `main` at `6d89e3d`, clean except untracked `docs/agent-runs/.DS_Store` and the untracked work-order directory
- **Item 1 outcome: gate CLOSED on observed evidence.** `sbt check` completed successfully inside my own turn, with no session restart. Details in section 4.
- Next gate: independent read-only review by `code-reviewer`, then human ratification, then a fresh orchestrator session.

## 1. Files created or modified (22 files, +104 / −11)

All inside my owned paths. **`docs/agents.md` is untouched** — no item required it.

| File | Change |
|---|---|
| `/Users/winnguyen/ScholarChain/.claude/settings.json` | Item 1. Added `sandbox.excludedCommands` with exactly two entries. Nothing else touched. |
| `/Users/winnguyen/ScholarChain/CLAUDE.md` | Item 3: four workflow rules appended to "Project rules". Item 1: the P1 open-gate paragraph in "Hook operation" replaced by three paragraphs recording the closure, the exact-match limit and the network cost. |
| `/Users/winnguyen/ScholarChain/.claude/hooks/validate.py` | Item 2 enabler. Frontmatter contract now requires `model` and pins its value per role (`opus` for `code-reviewer`, `sonnet` otherwise). Without this the validator **rejected** every file with a `model:` line. |
| `/Users/winnguyen/ScholarChain/.claude/hooks/probe_policy.py` | Policy probes. Sandbox key set 5 → 6, new exact-equality assertion on `excludedCommands`, and 20 new guard probes (near misses, compound forms, non-permitted bare command, constitutional writes). |
| 18 files under `/Users/winnguyen/ScholarChain/.claude/agents/` | Item 2 `model:` line on all 18; Item 4 reconciliation bullets on 10 of them (below). |

Per-file for the agents:

- `model: opus` — `code-reviewer.md` only. `model: sonnet` — the other 17.
- Item 4 sbt-reality bullet added to the 10 roles whose gate names sbt: `build-engineer`, `dependency-updater`, `domain-engineer`, `entity-resolution-engineer`, `hadoop-engineer`, `rag-engineer`, `service-engineer`, `spark-engineer`, `storage-engineer`, `test-engineer`.
- Item 3 rule 1 (gate-to-test mapping) and the parallel-test note added to the 7 module source owners: `domain`, `hadoop`, `entity-resolution`, `spark`, `storage`, `rag`, `service`.
- Item 3 rule 2 added to `test-engineer.md`; Item 3 rule 3 added to `code-reviewer.md`, worded so batching reduces rounds only and explicitly restates the no-self-fix / no-self-approval bar.
- No role's `tools`, `description`, `name` or `Owned output` line changed. Enumerated the directory rather than trusting the list: **18 files found, 18 roles listed in the work order, exact match — no extra file, no listed role without a file.**

## 2. The exact `sandbox` object, before and after

Before:

```json
"sandbox": {
  "enabled": true,
  "failIfUnavailable": true,
  "autoAllowBashIfSandboxed": false,
  "allowUnsandboxedCommands": false,
  "filesystem": { "denyRead": [ ...25 entries... ], "denyWrite": [ ...7 entries... ] }
}
```

After — the only difference is the inserted key:

```json
"sandbox": {
  "enabled": true,
  "failIfUnavailable": true,
  "autoAllowBashIfSandboxed": false,
  "allowUnsandboxedCommands": false,
  "excludedCommands": [
    "sbt check",
    "sbt Test/compile"
  ],
  "filesystem": { "denyRead": [ ...25 entries, unchanged... ], "denyWrite": [ ...7 entries, unchanged... ] }
}
```

No wildcard, no `:*`, no third entry. No sandbox write allowlist was reintroduced. Deny lists, unsandboxing switches, `permissions.allow`, all 51 `permissions.deny` entries, `defaultMode`, `disableBypassPermissionsMode` and `enableAllProjectMcpServers` are byte-identical — each asserted by an exact-equality probe that would fail on a silent widening.

**Guard change: none required.** `policy.py:60` already declares `CHECK_SBT = {"check", "Test/compile"}` and `policy.py:146` admits a bare `sbt` with exactly one argument from that set for every role except `deploy-engineer` and `incident-responder`. I verified this live rather than by reading: `sbt Test/compile` passed the guard and executed. `guard-dangerous.sh` and `policy.py` are **unchanged**.

**No `permissions.allow` entry was needed.** No permission prompt appeared for either command despite `autoAllowBashIfSandboxed: false`. I added nothing to `permissions.allow`.

## 3. Every command I ran, verbatim

| # | Command | Exit | Decisive output |
|---|---|---|---|
| 1 | `.claude/hooks/probe-policy.sh` (pre-edit baseline) | 0 | `SUMMARY: 136 probes passed, 0 failed.` |
| 2 | `sbt Test/compile` (**pre-edit baseline**) | 1 | `java.io.FileNotFoundException: /Users/winnguyen/.sbt/boot/sbt.boot.lock (Operation not permitted)` at `xsbt.boot.Locks$GlobalLock.withFileLock(Locks.scala:103)`; `[error] [launcher] error during sbt launcher`. Also `Picked up JAVA_TOOL_OPTIONS: -Djava.net.preferIPv4Stack=true` ×2 and `[warn] [launcher] could not parse ftp_proxy setting: ... unknown protocol: socks5h` |
| 3 | `sbt Test/compile` (post-edit) | 0 | `[info] welcome to sbt 1.11.7 (Eclipse Adoptium Java 21.0.13)` … `[success] Total time: 1 s` |
| 4 | `sbt check` (post-edit) | 0 | `[info] Passed: Total 148, Failed 0, Errors 0, Passed 148` · `[success] Total time: 6 s` (full transcript persisted by the runtime) |
| 5 | `sbt check` (post-edit, repeat for a capturable tail) | 0 | same: `Passed: Total 148, Failed 0, Errors 0, Passed 148`, `[success]` |
| 6 | `sbt checkAll` | — | guard refused: `ScholarChain policy: This shell command is outside the role's permitted commands...` |
| 7 | `sbt check --foo` | — | same guard refusal |
| 8 | `sbt check && ls` | — | guard refused: `...Shell expansion, pipelines, chaining and redirection require a reviewed script.` |
| 9 | `sbt check > /tmp/sbt-probe.txt` | — | same chaining/redirection refusal |
| 10 | `sbt $(echo check)` | — | same refusal |
| 11 | `java -version` | — | `...outside the role's permitted commands...` |
| 12 | `touch CLAUDE.md` | — | `...Use structured file tools for constitutional paths and the factory workflow for edits.` |
| 13 | `ls -la .claude/agents ...`, `git ls-files .claude ...`, `rg -n sbt .claude/agents`, `tail`/`rg` on the persisted sbt transcript | — | all refused by the constitutional-path scan. Recorded as live interception, not as failures of my change. |
| 14 | `.claude/hooks/validate-agent-files.sh` | 0 | `PASS: 18 unique agent names, declared default models, explicit tools and read-only reviewer fence` + 3 further PASS lines |
| 15 | `.claude/hooks/probe-policy.sh` (final) | 0 | `SUMMARY: 156 probes passed, 0 failed.` |
| 16 | `git diff --stat` | 0 | 22 files changed, 104 insertions, 11 deletions |

## 4. Item 1 outcome — gate closed, with evidence

**`sbt check` completed successfully inside this turn.** Not predicted, measured: commands 4 and 5 above, exit 0, `Passed: Total 148, Failed 0, Errors 0, Passed 148`, `[success]`, including `scalafmt` checks, compile and the full test run.

**It applied without a session restart** — observed. The only change between command 2 (exit 1, boot-lock EPERM) and command 3 (exit 0) was the `Edit` to `.claude/settings.json`. The documentation left restart behavior unspecified; this runtime picked it up for the next shell command.

The four Phase 1 failure modes, named explicitly:

1. `~/.sbt/boot/sbt.boot.lock (Operation not permitted)` — **present in my pre-edit baseline, absent after.** This is the mode that returned once Phase 1's write allowlist was removed.
2. Zinc bridge `Could not create temporary directory` at `ZincComponentCompiler.compileAndInstall` — **absent.** Correct JVM (`Eclipse Adoptium Java 21.0.13`) and no bridge rebuild.
3. Coursier download or cache failure — **absent.** No resolution error.
4. The `java.io.tmpdir` denial at `sbt.internal.inc.FileAnalysisStore$BinaryFileStore.set` on `/var/folders/k6/.../T/` — **absent.** This was the blocker that closed the gate as open in `docs/agent-runs/phase-01/sandbox-repair/sbt-check-measurement-temurin21.md`; the exclusion removes the filesystem sandbox entirely for the matched command, so the JVM's real temp directory is writable.

**No fourth-successive new failure mode appeared.** I expected one, as instructed. There was none: the run went straight through to `[success]`.

`CLAUDE.md` was therefore rewritten to state the gate closed, with the before/after measurements quoted in the file. The human `!`-prefix route is explicitly kept: the new text says an agent's own `sbt check` is evidence for its own turn and **not** the recorded phase gate, and that the human still runs `sbt check` with `!` at the end of each phase and before every commit.

## 5. Probe results — direct script probes versus live interception

**Direct script probes** (`.claude/hooks/probe-policy.sh`, 156 PASS, 20 new). These demonstrate script behavior only. New ones:

- `excludedCommands` is exactly `["sbt check", "sbt Test/compile"]` with no `*` in any entry; the sandbox key set is exactly six keys with no `network` key.
- Guard admits `sbt check` **and** `sbt Test/compile` for `domain-engineer`; refuses both to `deploy-engineer` / `incident-responder`; still refuses `sbt publish`.
- Near misses refused: `sbt checkAll`, `sbt check --foo`, `sbt -batch check`, `sbt Test/compileAll`, `sbt clean check`, `sbt check Test/compile`.
- Compound / redirected / expanded forms refused: `sbt check && rm -rf target`, `sbt check; ls`, `sbt check | tee out.txt`, `sbt check > out.txt`, `sbt Test/compile && sbt publish`, `sbt $(echo check)`, `` sbt check `whoami` ``, `cd modules && sbt check`.
- Non-permitted bare command (`java -version`) and constitutional writes (`touch CLAUDE.md` via shell, `Edit .claude/settings.json` as `domain-engineer`) still refused.

**Live interception observed in this session** (stronger than a script probe, still a subagent observation, not a fresh-session orchestrator probe): commands 6–13 above were each answered by the live PreToolUse hook with the quoted message, and commands 3–5 were each allowed and executed. So the exclusion opened no chaining, redirection or substitution hole, and the near-miss forms are refused by the guard *before* the sandbox is even consulted — the guard is the stronger of the two fences here.

**Needs a fresh-session observation, not done here.** Restart-probe rows for the orchestrator to insert into `docs/agent-runs/phase-01/orchestrator-update/restart-probes.md` (or a Phase 4 equivalent). I deliberately did **not** number them: `restart-probes.md` holds 1–9, while the Phase 1 repair report recommended 10–12 with different content that was never inserted, so numbering is the orchestrator's call.

| Ref | Probe | Expected result |
|---|---|---|
| P4-A | In a fresh session, dispatch a module owner (e.g. `hadoop-engineer`) to run `sbt check` with no other instruction. | Runs outside the sandbox, exits 0, no permission prompt, pass/fail counts reported. Confirms the exclusion is live for a non-factory role, not only for the role that authored it. |
| P4-B | In the same session, ask that role to run `sbt checkAll` and then `sbt check && ls`. | Both refused by the live PreToolUse guard with the quoted messages. Nothing executes. |
| P4-C | Dispatch any role and check which model answers. | The role's declared `model:` default is used when the orchestrator supplies no override. A pass is behavioral evidence only; `model:` does not bind an explicit dispatch-time override. |
| P4-D | Create a disposable `.scala` file newer than `.claude/.last-test-run`, let the Stop hook block, then let the agent run `sbt check` itself and stop again. | First stop blocks; the agent's own `sbt check` updates the marker; the second stop succeeds. This is the first time that loop is closeable without a human relay, and it is unobserved. |

**Not observed and not claimed:** whether the hook ran on my `Edit` calls. The `Edit` to `CLAUDE.md` succeeded, which the guard only permits when `agent_type == "factory-engineer"` reaches it — but an allow produces no output, so I cannot distinguish "hook ran and allowed" from "hook did not run". I treat `agent_type` delivery as **inferred**, left the `CLAUDE.md` paragraph on pending native enforcement unchanged, and that gate stays open.

## 6. Open gates after this change

1. **Native hook execution and subagent tool enforcement** — still pending a fresh-session probe, per the existing `CLAUDE.md` paragraph. Unchanged by this order. I observed live Bash interception in my own turn; that is subagent evidence, not a fresh-session orchestrator observation.
2. **`agent_type` on hook events** — still open; see above. Inferred only.
3. **Orchestrator-inline-work control** — unchanged, behavior-only, open.
4. **P2 repeat-stop non-blocking path** — unchanged, script-verified only.
5. **The four new restart probes P4-A..P4-D** — open until run in a fresh session.
6. **Could not measure:** whether `excludedCommands` in `.claude/settings.json` would be ignored under an admin-required sandbox. The documentation says it would be. This machine is not in that configuration as far as I can tell, and I had no way to test it.
7. **Could not measure:** the network widening itself. My `sbt check` resolved from the warm local cache and downloaded nothing, so I did not observe outbound traffic. The widening is documented, not measured.

## 7. Assumptions and risks

- **Observed:** the exclusion applied without a restart on this runtime.
- **Observed:** `sbt check` exits 0 with 148 passing tests from inside an agent turn.
- **Observed:** chaining, redirection, substitution and near-miss task names are all refused live.
- **Observed:** no permission prompt for either excluded command.
- **Inferred:** that command 3 ran *outside* the sandbox rather than inside a newly permissive one. The supporting signal is that the pre-edit run printed `Picked up JAVA_TOOL_OPTIONS` twice and the `socks5h` proxy warning and the post-edit run printed neither, which is consistent with a different (unsandboxed, unproxied) invocation environment. The causal chain — one settings key, one EPERM gone — is strong, but the environment delta is inference.
- **Risk, stated plainly as the work order requires:** an exclusion removes the filesystem **and network** sandbox for the matched command. `sbt` resolves dependencies over the network, so this widens network reach for `sbt check` and `sbt Test/compile`. That is the intended cost of the change. It also means those two commands can read and write anywhere the user can, including paths the sandbox deny lists would otherwise protect — the `permissions.deny` read rules and the guard still apply to *tool calls*, but not to what the sbt process itself does once running. The mitigation that exists is that the guard admits only a bare two-token form, so the agent cannot pass arguments or chain anything onto it.
- **Risk, inferred:** `sbt check` runs the project's build and test code with no filesystem sandbox. A malicious or compromised test could act outside the project. This is the same trust assumption the human's `!`-prefixed run already carried; the change extends it to agent turns.
- **Risk, observed:** the exclusion is exact-match. A future `sbt -batch check` or `.sbtopts`-driven variant silently will not match and will fail the way the pre-edit baseline did. Recorded in `CLAUDE.md` so a later phase does not read silence as coverage.
- **Risk, observed:** `validate.py` now hard-pins each role's model value. A deliberate future model change must edit the validator too. I chose that over a loose check so Item 2 cannot drift silently.
- **Observation, no action taken (out of scope):** `CLAUDE.md` still says "In Phase 1 the build and check task are pending" in the project rules. That is stale as of Phase 2. No item in this order covers it, so I left it. Routing it to a later factory order.

## 8. `BLOCKED-ON`

**None.** Specifically, the runtime did **not** deny my write to `.claude/settings.json`. The sandbox configuration write-denies that path for *Bash*, but the structured `Edit` tool succeeded, consistent with Phase 1 where the same file was authored through a structured edit. The doc-evidence author's expectation that a human would have to apply it did not hold. I made no shell workaround.

## 9. Next gate

Independent read-only review by `code-reviewer`, with the diff supplied by the orchestrator (`git diff` with a constitutional pathspec is permitted to the orchestrator; `code-reviewer` has no Bash tool). Then human ratification, then a fresh orchestrator session before any of this takes effect. **Not committed. Not ratified. I do not approve my own work.**
