# Work order: repair round 2

Author: orchestrator. Source: `code-reviewer-verdict-2.md` and the rulings in `human-facts.md` (read both).

## Rulings that bind this round

- F6 allowed: an agent's `sbt check` may stand in for the full `sbt clean check` before a re-review that depends on it. The sentence stays in CLAUDE.md and docs/agents.md. Only the human's `sbt clean check` counts at the phase gate and before a phase commit.
- R2: no ratification trigger. The full `sbt clean check` is required at each phase's final gate and before each phase commit. This speed-up commit is a recorded exception covered by the human's Phase 7 deferral of the `build.sbt` clean check. State that exception plainly where the rule appears; do not weaken the rule for other changes.
- Do not edit `build.sbt`, `AGENTS.md`, `.codex/**` or `.agents/**`. Do not commit. Do not ratify.

## factory-engineer: R1 to R4

- R1: `CLAUDE.md:56`: move "and the result is recorded before review" back onto the `sbt quick` or `sbt check` sentence; the human's `sbt clean check` sentence must not imply it gates every review.
- R2: `CLAUDE.md:56`: drop ratification as a trigger; align with the "each phase's final gate and before each phase commit" wording. Where "before every commit" appears in CLAUDE.md, docs/agents.md or the role files, make it consistent with the human's ruling ("each phase commit", with this speed-up commit named as the recorded exception) without adding new triggers. Check every file you touched, not only CLAUDE.md.
- R3: `CLAUDE.md:54` and `docs/agents.md:61`: "the marker's mtime is 02:22" becomes "was 02:22 local after that run" (later runs rewrote the marker).
- R4: `CLAUDE.md:50`: fix "The measurements in this paragraph cover `sbt check` and `sbt Test/compile`" so it does not contradict the `sbt quick` measurements that follow; name the commands in "no allow rule was added for either command".
- Then run `probe-policy.sh` and `validate-agent-files.sh` AFTER the last edit and report exact counts. If the guard refuses, report BLOCKED-ON with the exact refusal and stop.
- Do not start a full `sbt check` unless the Stop gate requires it; if it does, run `sbt quick` first (it now clears the gate) and report.

## Report

Status, files changed, which finding each edit closes, commands and exact counts, blockers, open items. Do not write to `docs/agent-runs`.
