# Phase 1 sandbox-repair: human decision record

Recorded by the orchestrator on 2026-11-05, as instructed. **The decision below is the human's**, stated in
their own words in session; `docs/agents.md` reserves ratification decisions to the human, and the
orchestrator records rather than makes them. Nothing here is the orchestrator's judgment.

## The decision

> Decision: stop the P1 experiments. Do not enable allowLocalBinding. P1 is accepted as an open gate: agents
> cannot run sbt check in the sandbox, so the human runs it with `!` at the end of each phase and before every
> commit. I removed `.jvmopts`.

Consequent instruction, routed to `factory-engineer` as one final edit:

1. Remove the sbt cache entries from the sandbox `allowWrite` list — they do not achieve P1.
2. Restate `CLAUDE.md:45` to record P1 as an open gate, with the human `sbt check` workflow.
3. Update any probe that asserts those `allowWrite` entries.
4. Keep the P2 and P3 fixes unchanged.

Then one final independent re-review of the whole change set by `code-reviewer`.

## What the human did directly

Removed `/Users/winnguyen/ScholarChain/.jvmopts` with `rm`, ending change set B. Confirmed by
`git status --short`: the file no longer appears as untracked, and the five modified files remain.

`target/sbt-tmp/.keep` still exists. It is inside `target/`, which is gitignored at `.gitignore:1`, so it is
build output rather than a tracked artifact and is left in place as harmless. No agent recreated `.jvmopts`.

## Why P1 is accepted open rather than repaired

Four `sbt check` runs across three `build-engineer` reports, with **three distinct failure modes**, and in
every one `scalafmtCheck`, `scalafmtSbtCheck`, `Compile / compile` and `Test / test` executed on **zero
modules**:

| Run | JVM | Failure | Report |
|---|---|---|---|
| 1 | Homebrew Java 27 | `sbt.boot.lock (Operation not permitted)`, then Zinc bridge rebuild → `Could not create temporary directory` | [`sbt-check-measurement.md`](sbt-check-measurement.md) |
| 2 | Temurin 21 | `java.io.tmpdir` denial in Zinc's analysis-store write, `/var/folders/.../T` | [`sbt-check-measurement-temurin21.md`](sbt-check-measurement-temurin21.md) |
| 3-4 | Temurin 21 + `.jvmopts` redirect | `UnixDomainServerSocket.bind` → EPERM, exit 2 | [`tmpdir-experiment-report.md`](tmpdir-experiment-report.md) |

Each round removed a real blocker and exposed the next one underneath. The reviewer's framing in
[`review-verdict-2.md`](review-verdict-2.md) — that there was no evidence the socket denial was the last
blocker, and that a widening which fails to close P1 should not survive the experiment that failed to close
it — is consistent with this decision. The human declined the remaining widening rather than continue.

## Effect on the change set

- **P1 is withdrawn, not repaired.** With the `allowWrite` entries removed, `.claude/settings.json` returns to
  its committed state and leaves the change set entirely. The sandbox gains **no** write surface from this
  work. The reviewer's note that the four entries were "one-quarter demonstrated and three-quarters inert"
  stands as the record of why.
- **`sandbox.network.allowLocalBinding` is explicitly declined.** It was never added. Review finding B6 — that
  adding a `network` key would break the exact-key-set assertion at `probe_policy.py:95-101` — is therefore
  moot, and that probe stays as it is.
- **P2 and P3 survive unchanged**, as instructed. Both were verified sound by two independent reviews.
- **Review findings B1-B5 are moot.** All five concerned `.jvmopts` or the `CLAUDE.md:45` text describing the
  tmpdir blocker: B1 (line 45 superseded by the redirect), B2 (`.jvmopts` unowned in the matrix), B3
  (`target/sbt-tmp` fragility), B4 (hardcoded absolute path), B5 (suppressed `-Dfile.encoding=UTF-8`). With
  `.jvmopts` removed and line 45 being restated, none has a subject.
- **Repair-report open item 2 is closed by withdrawal, not by proof.** The `~/Library/Caches/Coursier` entry
  was observed inert in all three runs that reached sbt, so it was never demonstrated necessary *or*
  unnecessary. Removing the whole list retires the question.
- **The Stop test gate remains a standing open condition.** `.claude/.last-test-run` is stale and no run in
  this sequence wrote it. Per both reviews, this change set does not itself owe an `sbt check`:
  `source_path()` covers `.scala`, `.java`, `.sbt`, `.scalafmt.conf` and `project/build.properties`, and the
  remaining files are `.py` and `.md` only.

## The accepted workflow

Agents cannot run `sbt check` inside the sandbox. The human runs it with the `!` prefix at the **end of each
phase** and **before every commit**. `factory-engineer` is recording this in `CLAUDE.md:45` as part of this
order, so the constraint lives in project memory rather than only in this evidence file.

## Still open, and not addressed by this decision

- Whether Claude Code surfaces exit-1 Stop-hook stderr to the operator. P2's entire "visible and attributable"
  property depends on it, and it is observable only in a live session. Both reviews call this the single most
  important remaining live observation.
- Restart probes 10-12 do not exist yet (`restart-probes.md` stops at 9). `factory-engineer` drafts them on a
  separate order; the human runs them in a fresh session.
- A third-party-captured `probe-policy.sh` run with a literal `$?`. The 136 PASS figure is the author's own
  evidence, independently hand-verified by the reviewer against the file, but never captured by anyone else.
- Repair-report open item 3: the P2 unverified-stop record is not durable — it lives only in that turn's hook
  stderr.
- Review finding F6 and the pre-existing gap that the guard applies no path fence to `build-engineer`. Both
  recorded as known limitations; closing either would be a separate tightening order.
