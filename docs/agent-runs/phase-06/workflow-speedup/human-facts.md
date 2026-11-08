# Human facts and rulings (2026-11-08)

Recorded by the orchestrator from the human's message. Source of fact 1 is the human relaying an observation by another Claude session (Cowork); this orchestrator session did not observe it.

## Fact 1: quick marker write measured once

`.claude/.last-test-run` has mtime 02:22 local. This matches the completion time (2:22:52 AM, Nov 8, 2026) of the build-engineer's 65 s `sbt quick` run made after `writeTestMarker` was added to `quickRun`. Observed by Claude (Cowork) via `ls` on the device. Treat the `quick` marker write as measured once: one run, one observed mtime, minute resolution. The mtime before that run was not recorded, so "the marker changed" rests on the match to the run's completion time, not on a before-and-after pair. A fresh-session agent run is still unobserved.

## Ruling 2: Codex setup is out of scope

`AGENTS.md`, `.codex/` and `.agents/` are the human's local Codex setup, untracked and never committed. No agent edits them. They are out of scope, so the reviewer's note about their stale two-command text needs no action.

## Rulings on review 2 (2026-11-08)

- F6: an agent's `sbt check` may stand in for the full `sbt clean check` before a re-review that depends on it. Keep that sentence. Only the human's `sbt clean check` counts at the phase gate and before a phase commit.
- R2: no ratification trigger. The `sbt clean check` is required at each phase's final gate and before each phase commit. This speed-up commit is a recorded exception covered by the human's Phase 7 deferral of the `build.sbt` clean check.

## Fact 3: second marker observation (2026-11-08)

Claude (Cowork) observed `.claude/.last-test-run` at 02:31 local after the agent's 71 s `sbt quick` run completed at 2:31:45 AM. Earlier reading was 02:22 for the 65 s run. So the `quick` marker rewrite is observed on two runs (minute resolution). Source is the human relaying the Cowork observation; this orchestrator session did not observe it. Caveats that remain: minute resolution only, no fresh-session agent run observed. Note: the constitution (`CLAUDE.md:54`, `docs/agents.md:61`) still says the later rewrite is "expected; not measured". That wording is now out of date. Per the human's instruction no constitution edit was made; correcting it is a future factory-engineer item.

## Ruling 3: meaning of "phase commit" (2026-11-08)

"Phase commit" means the commit recording the human's acceptance of a phase. Follow-up commits that change code need a passing agent `sbt check`. Docs-only follow-ups need none. This answers the reviewer's open note in `code-reviewer-verdict-3.md`. The constitution does not yet define the term. Defining it there is a future factory-engineer item, not done in this round.

## Probe and validator runs on the final text (2026-11-08)

Run by the human with `!` after the last prose edit (repair round 3, approved in `code-reviewer-verdict-4.md`):

- `.claude/hooks/probe-policy.sh`: `SUMMARY: 179 probes passed, 0 failed.` Only the last five lines were shown (`tail -n 5`); the 179/0 summary is the count.
- `.claude/hooks/validate-agent-files.sh`: 4 PASS lines: 18 unique agent names with declared default models, explicit tools and the read-only reviewer fence; 18 ownership rows and 18 unique lifecycle routes; 5 workflow commands resolving to required owners; 18 JSON files parsed, 10 shell scripts checked, executable hooks and event wiring. No FAIL line in the output shown.

These are direct script runs, not live interception. Fresh-session interception, an agent `sbt quick` in a fresh session and the Stop-gate loop (P4-D) remain unobserved.

## Instructions

Continue repair round 1, then a re-review of the changed items only. Do not commit.
