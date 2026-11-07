# Fresh-session probe: agent-run `sbt check` outside the sandbox

- Date: 2026-11-06
- Session: fresh orchestrator session (first dispatch of the session)
- Dispatched agent: `build-engineer`
- Work order: run `sbt check` exactly once, no edits, report result and whether it ran outside the shell sandbox
- Recorded by: orchestrator
- Author of the report below: `build-engineer`

## Purpose

Verify in a fresh Claude Code session, with no session restart in between
and no prior ratification activity, that the `sandbox.excludedCommands`
entry for the literal command `sbt check` lets an ordinary subagent run it
successfully. The prior measurement in `CLAUDE.md` was taken inside the
`factory-engineer` turn that made the change; this probe tests the same
path from a new session and from a different role.

## Result summary

- Verdict: PASS. `sbt check` completed successfully in a `build-engineer`
  turn, with no sandbox failure markers observed.
- Test result (verbatim): `[info] Passed: Total 148, Failed 0, Errors 0, Passed 148`
- Final line (verbatim): `[success] Total time: 6 s, completed Nov 6, 2026, 11:17:30 PM`
- scalafmt ran before the tests ("Checking 1 Scala sources", then
  "Checking 3 Scala sources") and raised no errors.
- Totals match the earlier 2026-11-06 measurement recorded in `CLAUDE.md` (148).
- Zero edits were made by the agent.

## Outside-the-sandbox evidence

The agent reports this as **yes, inferred** — strong but not exhaustive.
Absent markers, each of which had previously indicated sandboxed execution:

| Marker | Observed |
|---|---|
| `/Users/winnguyen/.sbt/boot/sbt.boot.lock (Operation not permitted)` | absent |
| `java.io.tmpdir` / `FileAnalysisStore$BinaryFileStore.set` denial under `/var/folders/.../T/` | absent |
| `<sandbox_violations>` block | absent |
| Claude Code permission prompt | none appeared |

Positive signals: sbt started normally (`welcome to sbt 1.11.7`, Java
21.0.13) and loaded the project; dependency resolution over the network
completed, consistent with `CLAUDE.md`'s note that an exclusion removes the
network sandbox as well as the filesystem sandbox for the matched command.

Stated limit on the evidence: the agent read the saved output from its
start and from line 315 to the end (349 lines total) but did not read lines
18–314. Its one attempt to grep the whole file for the failure markers was
refused by the guard hook, because the command used chaining and a
pipeline. That refusal is the expected guard behavior and is not a sandbox
event. So the absence of markers is established over the start and tail of
the output, not over the full 349 lines.

## Exit code

Not observed directly. The tool result did not surface an exit code. The
run ended with `[success]`, which is exit 0 by sbt's convention, but the
code itself was not read. Recorded as inferred, not measured.

## Side effects

- A passing `sbt check` writes `.claude/.last-test-run`. This run therefore
  very likely refreshed that marker. That is a side effect of the build's
  own check task, not an edit by the agent.
- The agent wrote no files.

## Other observations

- Duration: sbt reported 6 s total. Subagent wall clock was ~30 s including
  dispatch and reading output.
- The output contains repeated Hadoop `WARN` lines from
  `MapReduceEndToEndSuite`: JobTracker metrics already initialized, no
  `ToolRunner`, and no job jar set. These are benign warnings from the
  local-runner tests.
- Full verbatim output (35.1 KB) was saved by the tool harness to
  `/Users/winnguyen/.claude/projects/-Users-winnguyen-ScholarChain/50623afa-00f4-4561-81ef-48d42a4b588d/tool-results/b3imvcqaa.txt`.
  That path is session-scoped and outside the repository, so it is not a
  durable archive. If a durable verbatim record is wanted, a human `!`-prefixed
  `sbt check` should be captured into this directory.

## Gate status

This run is evidence for the agent's own turn only. It is **not** the
recorded phase test gate. Per `CLAUDE.md`, the phase gate remains the
human's `!`-prefixed `sbt check` at the end of each phase and before every
commit. Restart probe P4-D — the full loop of a Stop/SubagentStop block
followed by an agent run that satisfies it — remains **unobserved**: no
block fired during this probe, so the loop was not exercised.

## Commit

Not committed, per the work order.
