# Phase 03 revalidation repair work orders

Issued by the orchestrator under `docs/agent-runs/phase-03/revalidation.md` step 3, routing the `code-reviewer` findings recorded in `revalidation-review.md`. Two owners, disjoint territories.

The orchestrator performs none of this work inline. Subagents do not invoke one another.

## Shared constraints for both owners

- Stay inside your own territory per the `docs/agents.md` ownership matrix. Report `BLOCKED-ON` rather than editing another owner's path.
- These are coverage and attribution repairs. Do not change shipped behavior to make a test pass. If a new test reveals a real defect, report it; do not silently alter the contract to accommodate the test.
- Never invent a license, URL, checksum, identifier or size figure. If a value cannot be substantiated from the repository or an already-recorded publisher observation, report `BLOCKED-ON` and name what a human must supply. The Phase 03 gate clause is "no invented licenses or identifiers," so fabricating an attribution would convert a reported finding into a real violation.
- You cannot run `sbt check` inside the shell sandbox. This is an accepted open gate per `CLAUDE.md`. Report it as pending; the human architect runs it with the `!` prefix.
- Record exactly which commands you ran, their outcomes, and anything you could not verify.

## Work order 1 — `dataset-engineer`

Territory: `datasets/**`. Findings: MEDIUM-1, MEDIUM-3, LOW-4, LOW-5, and the `dataset-engineer` items in LOW-7.

1. **MEDIUM-1, primary.** Add negative tests for `validate()` in `datasets/scripts/test_datasets.py`. At minimum: a sample SHA-256 mismatch, a non-HTTPS evidence URL, a mismatched `s3_prefixes` derivation, and a `not-published` checksum entry carrying a `value`. Each must fail for its own distinct reason, verified by asserting on the error, not by a bare "raises" check. Build them against a temporary modified copy of the manifest; do not mutate `datasets/dataset-manifest.yaml` to test it.
2. **MEDIUM-3, primary.** Resolve the Crossref size claim at `dataset-manifest.yaml:298`. Either cite a verifiable release-announcement URL, or restate the figure as derived from the 3,697,724-byte torrent metadata already recorded at `validation.md:35`. Do not invent an announcement URL. Separately, either substantiate the ORCID total at `manifest:156` from the archived figshare response or restate it as unconfirmed.
3. **LOW-4.** Make the license gate checkable: add an SPDX identifier field per source and validate its shape in `validate()`. Do not change any of the four license determinations; the reviewer checked all four and found them accurate and carefully hedged.
4. **LOW-5.** Align the receipts with the core `Provenance` contract: add a run identifier to the replay receipt (`datasets.py:176-181`) and a transform field plus observation timestamp to the download receipt (`:249-252`). Do not edit `modules/core`; if the contract itself needs a change, report `BLOCKED-ON` naming `domain-engineer`.
5. **LOW-7.** Reject unknown extra keys in a dataset entry (`datasets.py:121`). Implement the ORCID check-digit validation that `orcid.md:9` instructs consumers to perform, or soften the instruction to match what the code does. Resolve the `JCDL`/`jcdl` DOI case drift, or document it as intentional.
6. **Open item from the reviewer.** Archive the four raw sample-source API responses with digests, so the `updated_date` question at `openalex.jsonl:1,2` can be closed by evidence rather than inference. If those responses were not retained, say so plainly; do not re-fetch beyond the existing bounded caps.

Gate: `python3 -B datasets/scripts/datasets.py validate` and `python3 -B -m unittest discover -s datasets/scripts -p 'test_*.py'`. Report exact commands and results. Report `sbt check` as pending for the human.

## Work order 2 — `domain-engineer`

Territory: `modules/core/**`. Findings: MEDIUM-2, LOW-6, and the `domain-engineer` items in LOW-7.

1. **MEDIUM-2, primary.** Add contract tests for the 10 untested record types: `AuthorMention`, `CanonicalResearcher`, `AuthorshipRecord`, `PublicationTeam`, `ResearcherMetrics`, `Venue`, `Institution`, `Topic`, `GraphNode`, `QueryPlan`. Prioritize the mention-position coupling, which is the most intricate invariant in the file and wholly unexercised: `WorkRecord`'s `author.workId == workId && author.authorPosition == index + 1` (`Contracts.scala:110-115`), currently defeated by the `authors = Vector.empty` fixture at `ContractSuite.scala:30`; the same invariant in `PublicationTeam` (`:175-180`); and the `mentionId == StableIds.mention(workId, authorPosition)` checks at `:130-133` and `:160-163`. Cover `GraphNode`'s node-type-to-ID-kind dispatch (`:346-351`) including the permissive `case _ =>` fallthrough. Include rejection cases, not only valid construction.
2. **MEDIUM-2, documentation.** `modules/core/README.md:3` claims "Contract tests freeze representative wire values and member names." Either extend frozen wire values beyond `WorkRecord` and `MatchDecision`, or narrow the sentence to what is true. `modules/core/README.md` is inside your territory.
3. **LOW-6.** Replace the bare `intercept[Exception]` loop at `ConfigSuite.scala:40-42` with assertions that identify which validation fired, so one over-broad failure cannot satisfy all seven settings. Confirm the `NaN` case (`:35`) exercises the `.filter(_.isFinite)` guard at `ScholarChainConfig.scala:77` and not merely `ContractValidation.confidence`. Add a case for a required non-version field absent from the file.
4. **LOW-7.** Assert wire-value uniqueness in `EnumWire.codec` (`WireEnums.scala:6-15`) so a duplicate fails loudly instead of silently shadowing a case. Decide and record whether `EvidenceBundle` should require `items.nonEmpty` (`Contracts.scala:409-421`) and whether `AnswerClaim` should permit zero evidence for non-`Verified` statuses (`:454-457`); the current shape is permissive relative to the `CLAUDE.md` rule that every material runtime claim must cite an evidence bundle, so if you keep it permissive, record why. Resolve the ID-discipline inconsistency at `Contracts.scala:90, 284, 296, 307` or document the asymmetry. Remove the dead null checks at `ScholarChainConfig.scala:141`, `ContractWire.scala:7,12`, `Contracts.scala:257`.

Gate: `sbt check` with ConfigSuite and ContractSuite passing. You cannot run it in the sandbox; report it pending for the human and state the expected test-count delta.
