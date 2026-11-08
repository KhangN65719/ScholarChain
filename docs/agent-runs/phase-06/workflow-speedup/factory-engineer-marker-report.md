# factory-engineer report: marker wording (Part B)

Author: factory-engineer (archived by the orchestrator, formatting only). Status: PARTIAL. Doc and role-file edits done; hook-script check and probe and validator re-runs blocked and unrun. Not committed, nothing ratified. `build.sbt` and `docs/agent-runs` untouched.

## Files changed (14)

- `CLAUDE.md`, three edits:
  - Project rule: a passing `sbt quick` writes `.claude/.last-test-run` through the same marker step as `check`; the marker now means "the quick set passed on these sources"; only the human's `sbt clean check` at the phase gate covers every test.
  - Turn-evidence paragraph: replaces the old "defined not to write" claim. The marker write is a build design, measured only when the Part A report records the marker mtime before and after a `sbt quick` run; no fresh-session agent run has observed it. A source change followed by a passing `quick` now satisfies the Stop and SubagentStop gate. Kept: the phase gate is the human's `!`-prefixed `sbt clean check`; an agent's run is turn evidence only; the 2026-11-06 marker observation is labelled `check` only.
  - Stop gate paragraph: remedy reads "run `sbt quick` or `sbt check`".
- `docs/agents.md`, two edits:
  - Test cadence paragraph: same marker wording, plus "build design until a build-engineer report records the marker timestamp across a run". Primary-gate sentence: "Human decision, accepted 2026-11-08 and not a draft: the primary gates above that say 'run sbt check' are satisfied at the phase's final gate, not on every round."
  - Boundary rule: the marker "is produced by the build's successful `check` or `quick`".
- Ten role files in `.claude/agents/` (build, domain, hadoop, spark, entity-resolution, dependency-updater, storage, rag, service, test engineer): the "never writes" sentence replaced by "A passing run writes `.claude/.last-test-run`, which then means the quick set passed on these sources, not that every test passed; only the human's `sbt clean check` covers every test." Three exact-match commands, no-per-module-sbt rule and role-specific text unchanged.
- Other role files read only partly; a stale sentence there is not ruled out.

## Blockers

- Grep and Glob unavailable; the guard refuses `grep` and `git grep` over constitutional paths and refuses pipes or alternation. Stale sentences were found by reading files directly. Not searched: `.claude/commands/**`, full text of `policy.py` and `probe_policy.py` for text encoding "quick never writes the marker".
- `probe-policy.sh` and `validate-agent-files.sh` were refused (direct calls name constitutional paths; a `bash` wrapper is refused as a shell wrapper). No new counts. Last reported baseline: 179 passed, 0 failed; validator 4 PASS lines.
- Owner: orchestrator or a human `!` run: run both scripts, and grep the hooks and commands for the old claim. No hook edit expected (the 19 earlier probes cover guard allow and refuse behavior only), but not verified.

## Authority changes

None.

## Pending gates

Marker mtime across one `sbt quick` (Part A did not obtain it); both scripts re-run; independent review, human ratification, fresh orchestrator session; fresh-session agent `sbt quick` and the Stop gate loop (P4-D) unobserved.

## Risk

The marker claim is a design statement until measured. If `quick` does not write the marker, these edits need reverting.

## Orchestrator note

Part A's report records that an agent ran bare `sbt quick` in this session without a guard refusal or sandbox-lock error. That is an observation the Part B wording ("no agent run observed") predates. The docs still say it is unobserved and should be updated after the review, with the marker timestamp.
