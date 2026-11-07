# Phase 4 workflow-speedup scoped re-review verdict

> **Archival note (orchestrator).** Author: `code-reviewer` (subagent), dispatched 2026-11-06 as the scoped
> re-review of the batched repair round, covering only the six repaired items plus collateral, per the
> project's new rule 3. Preserved as received. The reviewer has Read, Grep and Glob only and ran no commands.
>
> **This is an independent review verdict, not a ratification.** `APPROVE` here means the reviewer found the
> repair sound; the human's ratification is still the gate that activates the change.
>
> **Orchestrator note.** The reviewer's pending item 1 — the sandbox half for `sbt 'Test/compile'` and
> `sbt  check`, which it judged cheap enough to close before ratification — was measured immediately after
> this verdict and is **closed**. Pending items 2 and 4 are also closed. See
> [`validation.md`](validation.md) §"Post-re-review closures". Pending items 3 and 5 remain open and are
> named in the ratification request.

---

## Verdict: APPROVE

Scope: the six repair items only (R1 text, R1 probe gap, R2, N1, N2, N3) plus what the repair touched. Items cleared in round 1 were re-checked only for disturbance. I wrote nothing and ran nothing — I have no Bash tool.

Status: COMPLETE (re-review delivered) · Owner of the reviewed change: `factory-engineer` · Work order: `/Users/winnguyen/ScholarChain/docs/agent-runs/phase-04/workflow-speedup/work-order.md` · Files I wrote: none · Commands I ran: none.

---

### R1 — closed correctly

`/Users/winnguyen/ScholarChain/CLAUDE.md:51` now reads as three separable claims and each matches evidence:

- Refusal list is now `sbt checkAll`, `sbt check --foo`, `sbt -batch check`, `sbt Test/compileAll`. All four are guard-refused — `/Users/winnguyen/ScholarChain/.claude/hooks/probe_policy.py:156-160` pins them at exit 2 and the orchestrator's 160/0 run measured that. `sbt "check"` is gone from the list, which was the false claim.
- Guard verdict and sandbox outcome are now separate sentences: the guard "normalizes them with shlex and permits them" (matches `/Users/winnguyen/ScholarChain/.claude/hooks/policy.py:86`, `:88-90`, `:146-148`), "but the sandbox does not exclude them, so they run sandboxed and fail with … `sbt.boot.lock (Operation not permitted)`".
- The measured sentence states exactly what `validation.md:15-30` recorded: `sbt "check"`, exit 1, that error, no guard refusal. No claim that the guard refused it, and no claim that the sandbox boundary was observed directly.

The added imperative ("Write the bare two-token form…") is a usage rule, not an enforcement claim, so it does not reintroduce the R1 defect class.

### R2 — closed

`CLAUDE.md:18` is now "Run `sbt check` for implementation changes. It must verify formatting, compile, run tests and update the test marker only after success. Every behavior change needs a feature test." The stale clause is gone, the rule still reads as complete sentences, and nothing else in the file re-asserts it. The contradiction with `CLAUDE.md:49` is resolved.

### N2 — accurate, no overstatement

`CLAUDE.md:53` keeps the human `!`-prefixed run as "what satisfies the phase test gate", then records the marker effect, attributes it to the observed orchestrator run (corroborated at `validation.md:75-79`), and closes with "The full loop of a block, then an agent run that satisfies it, is still unobserved (restart probe P4-D)." Observed labelled observed, unobserved labelled unobserved, phase gate untouched. It does not read as a strengthening.

### N1 — the derived assertion bites, and the literal was kept

`probe_policy.py:112-115` asserts `set(sandbox["excludedCommands"]) == {f"sbt {task}" for task in CHECK_SBT}`, with the exact-literal and no-wildcard assertion retained immediately above at `probe_policy.py:105-109`. Four-way check:

- `CHECK_SBT` edited alone → derived fails.
- `excludedCommands` edited alone → derived and literal both fail.
- Both drift together (e.g. `publish` added to each) → derived passes, **literal at :107 fails**. So the derived assertion alone cannot carry a widening through.
- Set equality is order- and duplicate-blind; the literal at :107 covers order and duplicates. The pairing is deliberate and the comment at `probe_policy.py:110-111` says so.

Import is safe: `probe_policy.py:9` `from policy import CHECK_SBT, ROOT`. `policy.py` imports stdlib only, so no cycle; `validate.py:9` already imported from `policy` the same way. `ROOT` is unchanged (`policy.py:11`, `parents[2]` = repo root) and `probe_policy.py` already took `ROOT` from there, so `probe(..., root=ROOT)` semantics are identical.

### The three near-miss probes — correct polarity

`probe_policy.py:166`, `:167`, `:168` all expect exit 0, i.e. guard permits, not exit 2. The comment at `probe_policy.py:161-165` is accurate: it states the shlex normalization cause, states that `excludedCommands` is exact text match and does not cover these forms, attributes the `sbt "check"` measurement to 2026-11-06, and labels the other two as inferred rather than run. The suite-level caveat at `probe_policy.py:58-63` still says the OS boundary is not observable from the script. No probe claims a sandbox observation as its own result.

### N3 — clean

`/Users/winnguyen/ScholarChain/.claude/agents/build-engineer.md:17` and `/Users/winnguyen/ScholarChain/.claude/agents/dependency-updater.md:17` each carry the bullet, byte-identical to the seven module owners and in the same position (after the sbt bullet, before "Apply the shared laws"). `name`, `model`, `description`, `tools` and `- Owned output:` are intact in both (`build-engineer.md:1-7,13`, `dependency-updater.md:1-7,13`), and the owned-output text still matches `/Users/winnguyen/ScholarChain/docs/agents.md`. The bullet now appears on exactly 9 role files and nowhere else.

### Collateral — accounted for exactly

`docs/agents.md`, `policy.py` and `guard-dangerous.sh` are absent from the session-start `git status` modified list; `policy.py:146-148` and `:60` are unchanged from what I read in round 1. `settings.json` and `validate.py` appear in the diff only from round 1. I reconciled the orchestrator's measured growth (+18 insertions, +2 deletions) line by line against the six items:

- deletions: `CLAUDE.md:18` rewritten (1), `probe_policy.py:9` import rewritten (1) = 2.
- insertions: `CLAUDE.md:18` (1), `probe_policy.py:9` (1), N1 comment + assertion `probe_policy.py:110-115` (6), R1 comment `probe_policy.py:161-165` (5), three guard probes `:166-168` (3), two role bullets (2) = 18.
- `CLAUDE.md:51` and `:53` consume no new count because both lines were already insertions in the round-1 diff.

Exact match, so the growth is fully explained and no seventeenth role file or other path rode along.

---

## Non-blocking nits (do not block ratification)

- **`CLAUDE.md:51` is now slightly too conservative.** "The outcomes for `sbt 'Test/compile'` and `sbt  check` are inferred from the same normalization, not measured" reads as covering both halves, but the guard half is measured — `probe_policy.py:167-168` ran at exit 0 inside the orchestrator's 160/0 run. Accurate phrasing would be "the guard permits both, measured by the probe suite; that they then fail inside the sandbox is inferred." Under-claiming errs safe, so this is a wording improvement, not a defect — fold it into the next factory touch of this paragraph.
- **`CLAUDE.md:53` says "a change in the Stop gate's strength" without saying which direction.** The surrounding sentences make the reduction unambiguous, so no reader is misled, but "a reduction in the Stop gate's strength" would be plainer for a documented gate weakening.
- **`probe_policy.py:166-168` probe names each contain "that the sandbox does not exclude."** That clause is a file-readable and documented-mechanism fact rather than something the subprocess observes; the attribution lives in the comment at `:161-165` and the docstring at `:58-63`. Adequate, but a reader scanning only the PASS lines sees a sandbox claim.

## Pending — I verified none of the following

Measured and treated as settled: `.claude/hooks/probe-policy.sh` → 160/0, and `git diff --stat -- CLAUDE.md .claude` → 22 files, 122/13 (both orchestrator, post-repair).

1. **Sandbox half for `sbt 'Test/compile'` and `sbt  check`.** Still inferred. Two commands close it; the orchestrator can run them and record exit code plus error text. Cheap enough that I would do it before ratification rather than carry the inference forward.
2. `.claude/hooks/validate-agent-files.sh` post-repair — the author reports 4/4 PASS; the orchestrator replayed it pre-repair only. Role files changed since, so re-run it. Owner: orchestrator.
3. `sbt check` post-repair (author reports exit 0, 148 passed). Not re-replayed; the repair touched no Scala source, so low risk. The recorded phase gate remains the human's `!`-prefixed run. Owner: human.
4. `git status --short` at review time, confirming nothing outside the `CLAUDE.md .claude` pathspec changed. I relied on the session-start snapshot plus the diff-stat arithmetic. Owner: orchestrator.
5. Restart probes P4-A..P4-D, whether the excluded commands run outside the sandbox rather than inside a looser one, the network widening, and `agent_type` delivery. All require a fresh session after ratification. Owner: orchestrator in a fresh session, with human acceptance for anything left open.

## On the author's recommendation

Right call to split it out, and leaving the asymmetry documented is acceptable for ratification now. The failure direction is closed — a permitted-but-not-excluded form runs *inside* the sandbox and dies on EPERM, so the cost is a confusing build error, never an escape. It is now pinned in three places (`CLAUDE.md:51`, `probe_policy.py:166-168` and the comment above them), which converts a trap into documented behavior. Changing `policy.py` mid-round would reopen the guard for review and force a fresh probe baseline for no safety gain.

If that work order happens, I would tighten rather than add refusals: make the sbt allowance at `policy.py:146` compare the **raw command text** against the exclusion entries instead of the shlex-normalized tokens, so guard permission and sandbox exclusion are the same predicate by construction and cannot drift again. That is a narrower change than enumerating quote and whitespace variants, and the existing near-miss probes plus the N1 derived assertion would cover it.

Next gate: human ratification of the Phase 4 workflow-speedup change, then a fresh orchestrator session before use. I did not fix my own findings and did not approve my own work.
