# Phase 03 revalidation repair reports

Reports from the owners dispatched under `revalidation-repair-work-orders.md`, preserved as received with each author identified. Archived by the orchestrator. The orchestrator performed none of this work inline and ran no gate command.

Status: INCOMPLETE. `dataset-engineer` has reported. `domain-engineer` was dispatched in parallel and has not yet reported. No gate has been run against either owner's changes.

---

## Report 1 — `dataset-engineer`

Territory: `datasets/**`. Work order 1. Findings MEDIUM-1, MEDIUM-3, LOW-4, LOW-5 and the `dataset-engineer` items in LOW-7.

### Status

**BLOCKED-ON gate execution.** All six Work order 1 tasks are reported implemented across seven files under `datasets/`. No gate command was run, so none of the work is verified.

### Gate blocker, as reported

The two dataset gate commands were refused by the guard at `.claude/hooks/policy.py:93-94`. Direct script execution then failed with **exit 126**, because both scripts are mode 644 and therefore not executable.

- `python3 -B datasets/scripts/datasets.py validate` — not run.
- `python3 -B -m unittest discover -s datasets/scripts -p 'test_*.py'` — not run.

The owner did not attempt `sbt check`, correctly citing the `CLAUDE.md` record that agent-run `sbt check` inside the shell sandbox is an accepted open gate, closed as open after four measured runs hit three distinct sandbox failure modes, with the human `!` prefix as the ratified workflow. The owner's stated reasoning was that retrying it would contradict that decision rather than advance it. The orchestrator concurs.

### Orchestrator verification of this report's claims

Two factual claims were independently checked by the orchestrator with read-only commands:

- `ls -l datasets/scripts` confirms `datasets.py` and `test_datasets.py` are both `-rw-r--r--`, mode 644. The exit 126 explanation is substantiated.
- `git status --short` confirms exactly seven modified files under `datasets/`: `README.md`, `dataset-manifest.yaml`, `schemas/crossref.md`, `schemas/openalex.md`, `schemas/orcid.md`, `scripts/datasets.py`, `scripts/test_datasets.py`. All are `.md`, `.yaml` or `.py`. No Scala file was touched by this owner, and nothing outside `datasets/` was touched. **No ownership boundary violation.**

### MEDIUM-3 resolution, as reported

Resolved by an `UNCONFIRMED` restatement, with **no invented URL**. The Crossref release-announcement attribution is left **BLOCKED-ON the human architect**. This is the outcome the work order called for: the owner declined to fabricate an attribution to close the finding.

The orchestrator has not yet verified the restatement's wording against the manifest; that is for the follow-up review.

### Sample digests, explicitly open

The four sample payload digests are reported **expected unchanged by inspection and not observed unchanged**. The owner flagged that they must be compared first on the next gate run.

This is the correct disposition and the most important open item in this report. Those four digests matching the original Codex-orchestrated Phase 03 run is the strongest evidence in the phase. Until a gate run observes them, the determinism claim recorded in `revalidation-results.md` does not extend to the repaired tree.

### Stop-hook observation, as reported

The Stop hook fired. The owner reported that the sources newer than `.claude/.last-test-run` are all Scala and all belong to `domain-engineer`'s parallel work: `Contracts.scala`, `ScholarChainConfig.scala`, `WireEnums.scala`, `ContractSuite.scala`, and a new untracked `Rejection.scala`. The owner noted that none of its own seven files meet the hook's source definition at `.claude/hooks/policy.py:242-247`, since `datasets/` is neither `modules/` nor `tests/`.

The orchestrator's `git status --short` corroborates this file list. Per `CLAUDE.md`, treat `.claude/.last-test-run` as **stale**. The gate is unsatisfied, not passed.

This is a side effect of dispatching two owners in parallel: the hook fired on one owner's uncommitted work during the other's turn. It is not a defect in either owner's work, and it is not evidence about the Scala changes, which were still in progress when the hook fired.

### Required human actions, as reported by the owner

1. Run the two dataset gate commands with `!`, or set the exec bit on `datasets/scripts/datasets.py` and `datasets/scripts/test_datasets.py` so the guard's existing `datasets/scripts/` allowance is usable by this role.
2. Run `sbt check` with `!` over the `modules/core` sources, once `domain-engineer` reports, and record the result before review, commit or ratification.
3. Supply a citable Crossref release-announcement URL, or authorize a bounded re-fetch of the torrent metadata, to close MEDIUM-3 by evidence.

The orchestrator adds that action 1 offers a choice with different consequences. Running the gates with `!` verifies this tree now and changes nothing else. Setting the exec bit is a permanent repository change that widens what this role can execute unattended, which is a control-surface change and belongs to the human, not to the orchestrator or to `dataset-engineer`.

### Owner's self-assessment

The owner ran no gate and approved nothing of its own, and named independent review by `code-reviewer` as the next gate, after a passing dataset-gate run and a human `sbt check`.

---

## Report 2 — `domain-engineer`

Territory: `modules/core/**`. Work order 2. Findings MEDIUM-2, LOW-6 and the `domain-engineer` items in LOW-7.

### Status

**BLOCKED-ON gate execution.** The repairs are reported implemented. `sbt check` was not run, so none of the work is verified.

### Gate blocker, as reported

The owner did not attempt `sbt check`, citing the `CLAUDE.md` record that agent-run `sbt check` inside the shell sandbox is an accepted open gate rather than a defect to repair, and citing the work order's instruction to report it pending rather than work around it. The orchestrator concurs, and notes that both owners reached this conclusion independently.

Treat `.claude/.last-test-run` as **stale**. The gate is unsatisfied, not passed.

### Unverified sources, as reported

- `modules/core/src/main/scala/scholarchain/core/Contracts.scala`
- `modules/core/src/main/scala/scholarchain/core/WireEnums.scala`
- `modules/core/src/main/scala/scholarchain/core/ScholarChainConfig.scala`
- `modules/core/src/test/scala/scholarchain/core/ContractSuite.scala`
- `modules/core/src/test/scala/scholarchain/core/ConfigSuite.scala`
- `modules/core/src/test/scala/scholarchain/core/Rejection.scala` (new)

The owner noted that `modules/core/README.md` is documentation and affects no gate.

### Expected test-count delta

The owner states: expect **31 passed — ConfigSuite 5, ContractSuite 26** — up from the 18 baseline (ConfigSuite 3, ContractSuite 15).

The owner supplied a falsification criterion, which is the most useful part of this report: **if the count reads 18, or ConfigSuite still reads 3, the new sources did not reach the run.** This converts the human's `sbt check` from a pass/fail observation into a check that the new tests actually executed, which is the specific risk when a build might compile stale output. Record the observed counts, not just the exit status.

### Orchestrator verification of this report's claims

`git status --short` confirms the modified set under `modules/core`: `README.md`, `Contracts.scala`, `ScholarChainConfig.scala`, `WireEnums.scala`, `ConfigSuite.scala`, `ContractSuite.scala`, plus the new untracked `Rejection.scala`. This matches the owner's list exactly, including `ConfigSuite.scala`, which was not yet modified when Report 1 was archived.

Nothing outside `modules/core/**` was touched by this owner. **No ownership boundary violation.** Combined with Report 1, the two owners' changed sets are disjoint: seven files under `datasets/`, seven under `modules/core/`.

### Caveats the owner left explicitly open

Both remain unverified until a gate run, and both are the owner's own flagged uncertainty rather than findings:

1. **Message-fragment assertions that pass through upickle's reader.** If the gate fails on a `rejects(...)` message assertion, the owner states this is a wrong assumption about upickle's exception wrapping, not a contract defect. The owner asks for the actual failure text and will pin the assertion differently.
2. **Exact scalafmt output.** If the gate fails at the formatting step, `sbt fmt` then a re-run should clear it.

The orchestrator notes these are the right things to have flagged. The owner wrote assertions that pin each rejection to its own cause, which was the substance of LOW-6, and that is precisely the kind of assertion most likely to be wrong about an exception's wrapped message on first attempt. A formatting-step or message-assertion failure on the first human run is therefore an expected outcome and not a sign the repair is unsound.

### Owner's self-assessment

The owner ran no gate and approved nothing of its own.

---

## Consolidated state after both reports

| Item | State |
| --- | --- |
| Work order 1, `datasets/**` | Implemented, unverified. BLOCKED-ON gate execution and on a human Crossref attribution. |
| Work order 2, `modules/core/**` | Implemented, unverified. BLOCKED-ON `sbt check`. |
| Ownership boundaries | Clean. Disjoint changed sets, seven files each. No violation by either owner. |
| `.claude/.last-test-run` | Stale. |
| All four Phase 03 gates | Unsatisfied against the repaired tree. |
| `code-reviewer` verdict | Still REQUEST CHANGES. Not re-reviewed against the repairs. |

No gate has been run against any repair. Nothing in this phase is accepted. The orchestrator ran no gate command and performed no owner's work inline.

### Human actions required, consolidated

1. Run `sbt clean check` with the `!` prefix. Record the observed suite counts, not only the exit status; expect ConfigSuite 5 and ContractSuite 26 for a total of 31, and treat 18, or ConfigSuite 3, as evidence the new sources did not reach the run.
2. Run the two dataset gates with `!`, echoing exit codes. Compare the four sample payload digests against the table in `revalidation-results.md` **first**; they are expected but not observed unchanged, and they are the phase's strongest evidence.
3. Supply a citable Crossref release-announcement URL, or authorize a bounded re-fetch of the torrent metadata, to close MEDIUM-3 by evidence.
4. Decide separately, as a control-surface question, whether `datasets/scripts/*.py` should carry the exec bit. It is not required to verify this tree and it widens what the role can execute unattended.

After a passing gate run, dispatch `code-reviewer` for independent review of the repaired tree (`revalidation.md` step 3), then record acceptance and the commit SHA (step 5).

