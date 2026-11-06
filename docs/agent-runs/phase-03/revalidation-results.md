# Phase 03 revalidation gate results

Recorded for `docs/agent-runs/phase-03/revalidation.md` step 1. Author: orchestrator (main Claude Code session), transcribing output produced by the human architect.

## Who ran these commands

The human architect ran all three gate commands in this Claude Code session using the `!` prefix, so the output below is part of the session transcript rather than a report relayed from elsewhere. The orchestrator did not run them: `sbt check` is the accepted human gate under `CLAUDE.md`, and the orchestrator was instructed not to run the gate commands. The output is transcribed verbatim and abridged only where noted.

## Baseline

- Reviewed tree is byte-identical to the accepted Phase 03 commit `9ee8b222466b0d920dc895dfb02372b681d1fe5f`. `git diff --stat 9ee8b22 HEAD -- modules/core datasets` produced no output.
- `git status --short` reported one untracked path, `docs/agent-runs/.DS_Store`. It is an OS artifact, not a source or evidence file, and it affects no gate.

## Gate 1: sbt check

Command, as run:

```
SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false sbt clean check
```

This is `clean check`, not `check`, so compilation and tests ran from scratch rather than from cached output or a stale test marker.

Reported result: `[success] Total time: 7 s, completed Nov 5, 2026, 11:30:09 PM`, preceded by `[success]` for `clean`.

```
[info] welcome to sbt 1.11.7 (Eclipse Adoptium Java 21.0.13)
[info] scalafmt: Checking 5 Scala sources (modules/core)...
[info] scalafmt: Checking 2 Scala sources (modules/core)...
[info] scalafmt: Checking 1 Scala sources (.)...
[info] scalafmt: Checking 3 Scala sources (.)...
[info] compiling 5 Scala sources to modules/core/target/scala-3.3.7/classes ...
[info] compiling 2 Scala sources to modules/core/target/scala-3.3.7/test-classes ...
[info] Passed: Total 18, Failed 0, Errors 0, Passed 18
```

Formatting check, compile and tests all ran. No scalafmt violation and no compile warning escalation was reported.

### ConfigSuite: 3 of 3 passed

| Test | Time |
| --- | ---: |
| load versioned local YAML with explicit environment precedence | 0.017s |
| reject invalid environment numbers, windows, thresholds and secret-bearing endpoints | 0.003s |
| reject unknown configuration keys and missing or unsupported versions | 0.003s |

### ContractSuite: 15 of 15 passed

| Test | Time |
| --- | ---: |
| freeze work and match-decision wire shape, values, and option representation | 0.024s |
| reject absent, null, incomplete and unversioned provenance | 0.004s |
| reject absent and unsupported top-level and nested versions | 0.001s |
| reject fractional and text schema versions | 0.001s |
| reject malformed stable IDs including noncanonical base64url | 0.001s |
| reject invalid confidence through constructors and wire decoding | 0.001s |
| canonical edge key is independent of pair order, but the contract requires sorted distinct IDs | 0.005s |
| candidate pairs sort mention IDs while preserving author positions | 0.009s |
| reject reversed year windows through the wire and invalid edge observation years | 0.002s |
| stable internal IDs use framed fields and bind identity, text, versions, and graph evidence | 0.003s |
| stable IDs reject isolated UTF-16 surrogates and preserve supplementary characters | 0.003s |
| run IDs are random UUIDv4 and the immutable manifest preserves the run and snapshot IDs | 0.002s |
| claims resolve to evidence in the wire response | 0.006s |
| reject unknown enum wire values | 0.0s |
| coauthor graph edges require matching collaboration statistics | 0.003s |

The gate in `revalidation.md` requires ConfigSuite and ContractSuite to pass. Both passed in full, 18 tests total. The suites are majority rejection tests rather than happy-path tests, which is the behavior the constitution's contract requirements call for.

## Gate 2: manifest validation

```
python3 -B datasets/scripts/datasets.py validate
```

Output, in full:

```
Validated manifest, sample checksums and row counts: dblp, openalex, orcid, crossref
```

All four required sources validated, with sample checksums and row counts. No stderr output.

## Gate 3: dataset tool tests

```
python3 -B -m unittest discover -s datasets/scripts -p 'test_*.py'
```

Output:

```
.......
----------------------------------------------------------------------
Ran 7 tests in 0.104s

OK
```

7 tests, `OK`, no failures, errors or skips. This matches the 7-test count recorded after the original Phase 03 independent-review repairs in `validation.md`, so no test was lost or disabled between runs.

The run also emitted bounded-replay diagnostics to stdout, including a download-then-reuse of a 41-byte bounded artifact under a temporary directory, confirming the fetch cache path is exercised rather than stubbed:

| Dataset | Rows | Output bytes | Output SHA-256 |
| --- | ---: | ---: | --- |
| dblp | 1 | 332 | fdab8bb8d6acf0495d8c0ce529ab2f1ff5d67d192a0f7710e34236571e198edb |
| openalex | 1 | 906 | 843de1a18456f50c504f6d93d38c6a0b36961fdcf0949d452fc17dcee5fc6128 |
| orcid | 1 | 196 | faf3c9303a2791718057d3482922a169f95936f4d7587ea6a0a99a6ca9e30c3c |
| crossref | 1 | 274 | ea3ba7ee6dfd4ecd9f303ad6b0bba16d69337f89e00a1dabd98f424a69a2b849 |

Each digest was printed twice, once per replay, and the two values agree for every source.

### Cross-runtime determinism

All four digests, row counts and byte counts match the table recorded in `docs/agent-runs/phase-03/validation.md` exactly. That table was produced by the original Codex-orchestrated Phase 03 run; this one was produced under the Claude Code orchestrator. Identical digests across two different orchestration runtimes is the substantive finding of this revalidation: the dataset tooling is deterministic and runtime-independent, which is what `revalidation.md` existed to confirm.

## Gate 4: provenance and no invented facts

This gate is not a command and was not settled here. It is a review judgment, delegated to `code-reviewer` under step 2 and recorded in `revalidation-review.md`.

## Status

| Gate | Required | Result |
| --- | --- | --- |
| `sbt check`, ConfigSuite and ContractSuite passing | exit 0 | `[success]`, 18 passed, 0 failed, 0 errors |
| `datasets.py validate`, all four sources | exit 0 | all four validated |
| `unittest discover` over `datasets/scripts` | exit 0 | `OK`, 7 tests |
| Provenance present, no invented licenses or identifiers | review | see `revalidation-review.md` |

## Post-repair gate run

Run by the human architect with the `!` prefix after both repair owners reported (`revalidation-repair-reports.md`), satisfying `revalidation.md` step 3's requirement to rerun the gate after repair. Transcribed by the orchestrator. Tree: the two owners' repairs, uncommitted.

### Gate 1 post-repair

```
SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false sbt clean check
```

`[success]`, completed Nov 6, 2026, 12:05:13 AM. **`Passed: Total 31, Failed 0, Errors 0`.**

| Suite | Baseline | Post-repair |
| --- | ---: | ---: |
| ConfigSuite | 3 | 5 |
| ContractSuite | 15 | 26 |
| **Total** | **18** | **31** |

`domain-engineer` predicted exactly 31, ConfigSuite 5 and ContractSuite 26, and supplied a falsification criterion: a reading of 18, or a ConfigSuite of 3, would mean the new sources never reached the run. The observed counts match the prediction exactly and fail the falsification criterion in the intended direction, so the new tests demonstrably executed rather than a stale build reporting success. Test sources compiled rose from 2 to 3, consistent with the new `Rejection.scala`.

Both caveats that owner left open are closed by this run:

- scalafmt passed over 5 main and 3 test sources. No formatting failure, so no `sbt fmt` cycle was needed.
- No `rejects(...)` message assertion failed, so the owner's assumption about upickle's exception wrapping was correct.

New tests mapping to the findings they repair:

| New test | Finding |
| --- | --- |
| work records bind author mentions to the work and its one-based sequence | MEDIUM-2, mention-position invariant |
| publication teams follow the work's one-based mention sequence | MEDIUM-2, same invariant in `PublicationTeam` |
| freeze author-mention wire shape, values, and collection members | MEDIUM-2 |
| canonical researchers and authorship records bind mentions to researchers | MEDIUM-2 |
| researcher metrics reject negative, nonfinite and absent aggregates | MEDIUM-2 |
| venue, institution and topic records carry external vocabulary IDs | MEDIUM-2, LOW-7 ID discipline |
| graph nodes dispatch identifier discipline by node type | MEDIUM-2, `GraphNode` dispatch |
| query plans require a positive result limit and researcher IDs | MEDIUM-2 |
| reject a configuration file missing a required non-version field | LOW-6 |
| configuration invariants reject each malformed field distinctly | LOW-6, the core of it |
| evidence bundles reject duplicates and unevidenced claims explain why | LOW-7 evidence permissiveness |
| enum wire values are unique and a duplicate codec fails loudly | LOW-7 `EnumWire.codec` |
| null members are rejected rather than silently accepted | LOW-7 dead null checks |

All 10 previously untested record types now appear in a test name.

### Gate 2 post-repair

```
python3 -B datasets/scripts/datasets.py validate
```

Output, in full, identical to the pre-repair run:

```
Validated manifest, sample checksums and row counts: dblp, openalex, orcid, crossref
```

All four sources still validate after the manifest, SPDX and receipt changes.

### Gate 3 post-repair

```
python3 -B -m unittest discover -s datasets/scripts -p 'test_*.py'
```

```
..........
----------------------------------------------------------------------
Ran 10 tests in 0.125s

OK
```

**10 tests, up from the 7 baseline.** No failures, errors or skips.

### Sample digests: unchanged

This was the critical check. `dataset-engineer` reported the four digests as expected unchanged by inspection but **not observed**, and flagged that they must be compared first. They are now observed, and all four match:

| Dataset | Rows | Bytes | SHA-256 | vs. pre-repair and original run |
| --- | ---: | ---: | --- | --- |
| dblp | 1 | 332 | fdab8bb8d6acf0495d8c0ce529ab2f1ff5d67d192a0f7710e34236571e198edb | unchanged |
| openalex | 1 | 906 | 843de1a18456f50c504f6d93d38c6a0b36961fdcf0949d452fc17dcee5fc6128 | unchanged |
| orcid | 1 | 196 | faf3c9303a2791718057d3482922a169f95936f4d7587ea6a0a99a6ca9e30c3c | unchanged |
| crossref | 1 | 274 | ea3ba7ee6dfd4ecd9f303ad6b0bba16d69337f89e00a1dabd98f424a69a2b849 | unchanged |

Each digest printed twice per replay and agreed. The determinism evidence now holds across three dimensions: two orchestration runtimes (Codex and Claude Code), and before and after the repairs. The repairs changed validation and provenance receipts without perturbing sample payload output, which is what a coverage-only repair should do.

### Post-repair status

| Gate | Result |
| --- | --- |
| `sbt check`, ConfigSuite and ContractSuite | `[success]`, 31 passed, 0 failed, 0 errors |
| `datasets.py validate`, four sources | all four validated |
| `unittest discover` | `OK`, 10 tests |
| Provenance, no invented licenses or identifiers | partially open, see below |

### Open after the post-repair run

1. **MEDIUM-3 is not closed.** `dataset-engineer` resolved it with an `UNCONFIRMED` restatement and no invented URL, which is the correct interim disposition, but the Crossref release-announcement attribution remains BLOCKED-ON a human-supplied citable URL or an authorized bounded re-fetch. A passing gate does not close an attribution finding, because `validate()` cannot check whether a cited figure is real.
2. **MEDIUM-1's test count is not itself verified against the work order.** The work order asked for at least four distinct negative cases: a sha256 mismatch, a non-HTTPS evidence URL, a mismatched `s3_prefixes`, and a `not-published` checksum carrying a `value`. The suite grew by three test methods. Several assertions can legitimately live in one method, so this is not necessarily a shortfall, but the orchestrator has not read the tests and does not assert that all four cases exist or that each fails for its own distinct reason. The re-review should confirm this directly; it is the same weakness LOW-6 identified in `ConfigSuite`.
3. **Exit codes again rest on success signals**, not literal `$?` values. See the recording limits below, which apply unchanged to this run.
4. **The `code-reviewer` verdict is still REQUEST CHANGES.** It has not been re-run against the repaired tree. Gates passing is necessary but not sufficient; `revalidation.md` step 2's verdict must be refreshed before step 5.

## Recording limits

- Exit codes were not echoed separately. No command printed `$?`. Each result above is recorded on the tool's own success signal: sbt's `[success]` line, the validator's success line with empty stderr, and unittest's `OK`. These are reliable success indicators for these three tools, but they are not literal observed exit codes, and this record should not be read as reporting one.
- The three gates together satisfy the command portion of the revalidation gate. The provenance gate remains open until the reviewer's verdict is recorded, so this file alone is not a Phase 03 revalidation pass.
- `.claude/.last-test-run` state is governed by the build, not by this record. Because the human ran `sbt check` directly, the marker should be treated per the `CLAUDE.md` hook-operation rules rather than assumed fresh from this file.
