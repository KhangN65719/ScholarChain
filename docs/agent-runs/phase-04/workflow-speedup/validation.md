# Phase 4 workflow-speedup orchestrator validation

- Date: 2026-11-06 · Phase 4 · Author: orchestrator (main session) · Branch `main` at `6d89e3d` plus the
  uncommitted Phase 4 change
- Purpose: replay the claims `code-reviewer` could read but not execute, because it has no Bash tool, and
  settle its finding R1 by measurement rather than by source reading.
- Scope limit: these are **main-session** measurements. The main session carries no `agent_type`, so they are
  not a substitute for the fresh-session restart probes P4-A..P4-D, which remain open.

## R1 — CONFIRMED by direct measurement, more sharply than the reviewer predicted

The reviewer predicted from `policy.py:146` that the guard permits `sbt "check"` while
`sandbox.excludedCommands` does not exclude it. One command settles both halves at once.

Command run: `sbt "check"`. Exit code **1**.

```
java.io.FileNotFoundException: /Users/winnguyen/.sbt/boot/sbt.boot.lock (Operation not permitted)
	at xsbt.boot.Locks$GlobalLock.withFileLock(Locks.scala:103)
[error] [launcher] error during sbt launcher: java.io.FileNotFoundException: /Users/winnguyen/.sbt/boot/sbt.boot.lock (Operation not permitted)
```

Both halves are proven by this single result:

1. **The guard permitted it.** No `ScholarChain policy:` refusal was returned. sbt actually launched — the
   launcher banner and the JVM stack frames could not exist otherwise. So `CLAUDE.md:51`'s claim that the
   guard refuses `sbt "check"` is **false as written**, exactly as R1 states.
2. **The sandbox did not exclude it.** The failure is the `sbt.boot.lock` EPERM, which is the pre-edit
   baseline failure mode recorded in the owner report §3 row 2. An excluded command has no filesystem
   sandbox and cannot produce this error; a sandboxed one does.

This also **independently corroborates the owner report's pre-edit baseline**, which the reviewer listed as
pending item 4 and which until now rested only on the author's own transcript. The same EPERM, the same
frame, reproduced in a different session by a different caller.

R1 therefore stands as written and is not reducible to a wording nit. The reviewer's offer to withdraw it
does not apply.

## Reviewer pending items 1, 2 and 4 — replayed, all confirmed

| Reviewer pending item | Command run by the orchestrator | Result |
|---|---|---|
| 1. Probe suite, claimed 156/156 | `.claude/hooks/probe-policy.sh` | **`SUMMARY: 156 probes passed, 0 failed.`** Confirmed. The 20 new P1 probes all appear by name in the output, including the exclusion-exactness assertion and the eight compound/expanded refusals. |
| 2. Agent-file validator, claimed 4/4 | `.claude/hooks/validate-agent-files.sh` | **4/4 PASS**, with the new wording: `PASS: 18 unique agent names, declared default models, explicit tools and read-only reviewer fence`; plus 18 ownership rows, 5 workflow commands, 2 JSON files and 5 shell scripts. Confirmed. |
| 4. `sbt check` exit 0 with 148 tests | `sbt check` | **Exit 0.** `[info] Passed: Total 148, Failed 0, Errors 0, Passed 148` · `[success] Total time: 6 s, completed Nov 6, 2026, 10:41:33 PM`. Confirmed, in a different session from the author's. |

The `sbt check` run also exercised the full chain the author claimed: `scalafmt: Checking 1 Scala sources`,
`scalafmt: Checking 3 Scala sources`, then the suites — including
`scholarchain.tests.adversarial.AdversarialGiantTeamSuite` (`Passed: Total 81`) and
`scholarchain.hadoop.MapReduceEndToEndSuite` — to the 148-test total.

## What this adds beyond the owner's own evidence

The author's `sbt check` ran inside a `factory-engineer` turn, which is the role that authored the exclusion.
This run was made by the **main session**, a different caller, and still succeeded. That widens the evidence
from "works for the authoring role" toward "works for this runtime", though it does **not** close P4-A, which
asks specifically for a non-authoring **subagent** role in a **fresh** session.

## Reviewer pending items not settled here

- **3.** The quoted form is settled above. The two further variants the reviewer named, `sbt 'Test/compile'`
  and `sbt  check` (two spaces), were **not** run. The mechanism is the same `shlex` normalization at
  `policy.py:88-90`, so the same outcome is expected, but it is inference, not measurement.
- **5.** Whether the command executed outside the sandbox rather than inside a more permissive one. Still
  inference. The contrast between the excluded `sbt check` (exit 0) and the non-excluded `sbt "check"`
  (EPERM) in the same session is strong circumstantial evidence that exclusion is what changes the outcome,
  but neither run observed the sandbox boundary directly.
- **6.** The network widening. Still unmeasured. This run also resolved from a warm cache and downloaded
  nothing.
- **7.** `agent_type` delivery on the author's `Edit` calls. Unobserved, and not observable from here.
- **8.** Restart probes P4-A..P4-D. All require a fresh session after ratification.
- **9.** Confirmed in passing: `git diff --stat -- CLAUDE.md .claude` returns exactly the 22 files,
  `104 insertions(+), 11 deletions(-)`, matching the owner report.

## Side effect to note

This session's successful `sbt check` wrote `.claude/.last-test-run`. That is the build's own marker write,
not an orchestrator edit. It is also a concrete instance of reviewer observation **N2**: the Stop-gate marker
can now be satisfied without a human relay, and `CLAUDE.md` does not yet say so.

## Next gate (at the time of writing)

Batched repair round by `factory-engineer` on R1, R2 and the R1 probe gap, with N1 and N2 included because
they are one-line changes in the same files. Then a re-review limited to the changed items, then human
ratification, then a fresh orchestrator session.

---

# Post-repair and post-re-review closures

Added after the repair round and after the scoped re-review returned `APPROVE`
([`re-review.md`](re-review.md)). All commands below were run by the orchestrator in the main session.

## Post-repair verification

| Claim | Command | Result |
|---|---|---|
| Repair's probe count | `.claude/hooks/probe-policy.sh` | **`SUMMARY: 160 probes passed, 0 failed.`** Confirmed, up from 156. The four new probes appear by name, including `P1 the sandbox exclusion matches the guard's CHECK_SBT set`. |
| Repair's diff size | `git diff --stat -- CLAUDE.md .claude` | 22 files, **`122 insertions(+), 13 deletions(-)`**. Confirmed, up from 104/11. |

## Re-review pending item 1 — CLOSED by measurement

The reviewer judged this cheap enough to close before ratification rather than carry forward. Two commands,
both run by the orchestrator:

| Command | Exit | Result |
|---|---|---|
| `sbt 'Test/compile'` | **1** | No guard refusal; sbt launched. Failed with `/Users/winnguyen/.sbt/boot/sbt.boot.lock (Operation not permitted)` at `xsbt.boot.Locks$GlobalLock.withFileLock(Locks.scala:103)`. |
| `sbt  check` (two spaces) | **1** | Identical: no guard refusal, sbt launched, same `sbt.boot.lock` EPERM at the same frame. |

Both halves are now **measured** for all three near-miss forms, not just for `sbt "check"`:

- **Guard half:** the guard permits quoted and extra-whitespace forms. Measured directly here (no refusal,
  sbt launched) and independently by probes `probe_policy.py:166-168` at exit 0.
- **Sandbox half:** `sandbox.excludedCommands` does not exclude them, so they run sandboxed and fail with the
  boot-lock EPERM. Measured here for all three.

Consequence for the constitution: the re-review's first non-blocking nit is now **understated in the other
direction**. `CLAUDE.md:51` says the outcomes for `sbt 'Test/compile'` and `sbt  check` are inferred; after
these two runs, both are measured. The paragraph is conservative rather than wrong, so it is not a defect,
but a future factory touch of that paragraph should upgrade the labelling to measured and cite this section.

## Re-review pending items 2 and 4 — CLOSED

| Item | Command | Result |
|---|---|---|
| 2. Validator, post-repair | `.claude/hooks/validate-agent-files.sh` | **4/4 PASS**, unchanged wording: 18 agent names with declared default models, 18 ownership rows and 18 unique lifecycle routes, 5 workflow commands, 2 JSON files and 5 shell scripts. |
| 4. Nothing outside the pathspec | `git status --short` | Exactly the **22** modified files of the diff, plus untracked `docs/agent-runs/.DS_Store` and the orchestrator-owned `docs/agent-runs/phase-04/workflow-speedup/`. No other path changed. |

## Re-review pending items still open at ratification

- **3.** `sbt check` post-repair. Not re-replayed by the orchestrator. The repair touched no Scala source —
  only `CLAUDE.md`, `probe_policy.py` and two role files — so the risk is low, but it is not zero and it is
  not measured. The recorded phase gate remains the human's `!`-prefixed run.
- **5.** Restart probes P4-A..P4-D; whether the excluded commands execute outside the sandbox rather than
  inside a looser one; the network widening; and `agent_type` delivery on hook events. All require a fresh
  session after ratification, and the first three are the substantive remaining unknowns about this change.

## Next gate

Human ratification. Then a fresh orchestrator session, in which P4-A..P4-D are the first work.
