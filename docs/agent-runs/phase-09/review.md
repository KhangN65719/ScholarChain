# Phase 9 review (code-reviewer, Opus, read-only)

## Round 1: REQUEST CHANGES (as received; condensed)
1 blocker, 2 major, 4 minor. One batched repair round.

- **B1 (blocker)** `Verification.scala` 369-375, 385-392, 441-460, 517, 572-593, 647-651: a model can bypass F1 through free-text claim kinds. A `semantic` claim citing only a `TextChunk` returns `Check.Ok(Vector.empty)` and is kept Verified, so structural claims without digits ("are direct coauthors and the main bridge") or spelled numbers above twenty ("ninety-nine coauthors") pass. Same gap for `abstention` claim wording, the model `analysis` paragraph, and per-claim `uncertainty`. Breaks F1 and the "removed, weakened or labeled" gate. Fix: semantic claim text must equal `Rendering.SemanticText`; abstention text and `analysis` fixed or system-written; `uncertainty` system-written; add negative tests in rag and `tests/`.
- **M1 (major)** `Verification.scala` 441-467, 517, 584-593: numbers in claim text are matched against any number in the cited fact (windowEnd, publicationCount, years, ranks), not the value the claim asserts. Example: assertion `distinct-coauthor-count = 3`, text "4 distinct coauthors" or "2009 coauthors" verifies. Fix: show `Rendering.render(kind, assertion, support)` for verified claims, or tie each number to the fields the assertion names; test a number matching the wrong field.
- **M2 (major)** `Synthesis.scala` 49, 57-61; `EvidenceBuilder.scala` 133, 146, 177, 259-261, 301-302, 491; `Workflow.scala` 396, 447, 453; `Model.scala` 65, 67: data strings (display names, coauthor names, titles) reach the model under trusted `facts`/`knownGaps`, outside the `untrusted` keys; `InjectionScreen` never runs on names or titles. Tests pass only because the stub does not interpret text. Fix: put data strings under an untrusted sub-object or widen prompt rule 3 to all string fields in facts and gaps; scan names and titles and flag hits.
- **m1** `Ports.scala` 106-114 and storage `Neptune.scala` 1108: allowlist checks the query name only. Defence in depth; compare query text to the catalog template text.
- **m2** `ModelPortSuite.scala` 18-31: `hash == sha256(text)` always holds, so a prompt edit without a version bump passes; report 02 overstated "pinned". Fix: pin literal hashes per version.
- **m3** `EvidenceCaps.maxBytes` 96 KiB (`EvidenceBuilder.scala` 28) vs `maxInputTokens` 12000 (about 48 KB; `Model.scala` 356, 404-409): a bundle within the cap can fail before any model call and the abstention says "the model output was invalid after 0 attempt(s)" (`Workflow.scala` 283-287). Fix: align limits, give budget failures their own abstention reason.
- **m4** `Model.scala` 152: synthesis schema `assertion` is `{"type":"object"}` so any keys pass. Fix: per-kind assertion schemas with `additionalProperties: false`.

Non-blocking notes: `stage("audit","stored")` added after the payload is built (`Workflow.scala` 594) and a failed audit write throws out of `answer` (fails closed; document it); `metricVersions` (`Workflow.scala` 543-545) mixes `rag-runtime/1.0.0` with Spark versions; a mention sharing a canonical researcher's name always makes the name Ambiguous (79,178 mention edges in the Phase 8 export, expect frequent abstentions on real data); untracked `.agents/`, `.codex/`, `AGENTS.md`, `datasets/slice/`, `readback-paths.py` are outside Phase 9 and not reviewed (`.codex/**` has no owner); Table 1 runs with semantic retrieval wired in, scoring still separate and `EvaluationSuite` has a graph-only world.

Per-gate verdict: 1 PASS (m1); 2 PASS; 3 FAIL (M1, B1); 4 FAIL (B1); 5 FAIL (B1; P2 mapping sound); 6 FAIL (M2); 7 PASS (m4); 8 PASS; 9 PASS; 10 PASS (m3); 11 PASS; 12 PENDING (needed `git diff --stat`); 13 PARTIAL (m2).

Pending from the reviewer: orchestrator to supply `git diff --stat` and `git status` for item 12.

## Orchestrator answer to the pending item (git, read-only)
`git diff --stat`: 3 tracked files modified, all additive in `modules/storage`: `DynamoDb.scala` (+154), `PathEvidence.scala` (+10/-2), `Support.scala` (+1); 165 insertions, 2 deletions. No tracked test file is modified or deleted. All other Phase 9 files are untracked/new and under owned paths (`modules/storage/**`, `modules/rag/**`, `tests/**`, `docs/agent-runs/phase-09/`). No constitutional file changed. Gate item 12 evidence: consistent with ownership; no weakened or deleted test.

## Round 2: APPROVE (fresh code-reviewer, Opus, read-only; condensed as received)
No blocker or major remains. Repairs verified from source, because the rag repair report never arrived (G3).

| ID | Result | Basis |
|---|---|---|
| B1 | fixed | `Verification.scala` 645-649: shown text is always system text (`SemanticText`, `AbstentionText`, `Glossary`, `Rendering.render`, `AnalysisText`); reworded semantic claim becomes PartiallySupported and is removed (675-680, 767-768); replaced analysis recorded (805-814, 841); per-claim uncertainty system-derived (682-694, 748-751); top-level model uncertainty read by nothing. Bypass attempts (other claim kinds, non-ASCII digits, "dozen", ordinals) fail because model wording only decides the label and is never shown. |
| M1 | fixed | `render` works from `support` narrowed to facts equal to the asserted value (578-589); a differing value is Conflicting. |
| M2 | fixed | `Facts.split` moves name/title keys under `untrustedData`; gaps under `untrusted.knownGaps`; prompt rule 3 covers every string field in `facts`; names and titles scanned and flagged. |
| m1 rag | fixed | `Ports.scala` 119-143 compares query text and parameter-name set to the catalog. Storage fake deferral (P11) accepted. |
| m2 | fixed | hashes computed from text; literals pinned independently in `ModelPortSuite` and `Phase9RepairSuite`; synthesis 1.1.0, `synthesis-output/2`. Reviewer cannot compute sha256 by reading; the literal match rests on the executed Phase9RepairSuite (24 passed). |
| m3 | fixed | `maxBytes` now 32 KiB; budget failure reason `input-over-budget`. |
| m4 | fixed | closed per-kind assertion schemas, `additionalProperties: false`. |

Tests: no suite shrank; rag Phase 9 suites 141 to 168; ModelPortSuite 13 to 14; the one changed root expectation is stricter. Regression check: audit fields, retry path and abstention codes intact; only new code is `input-over-budget`.

Five minor findings carried as follow-ups (none reopens a gate; not repaired, repair budget of 2 rounds is used):
- **m5** `Model.scala:276`, `Verification.scala:743,757-763,61,70`: `claimId` is model-written free text (up to 40 chars) copied into Verified `AnswerClaim.claimId`, provenance and audit. Fix: system-assigned claim IDs.
- **m6** `Workflow.scala:110-114`, `Resolver.scala:98`: resolver passes the raw Neptune port to `CoauthorContext.of`, skipping `AllowlistedNeptune` (query still the catalog's). Fix: wrap the port once in `CollaborationWorkflow`.
- **m7** `EvidenceBuilder.scala:151,509`, `Facts.scala:73-87`: publication `source` and `orcid` are under untrusted data but not passed through `InjectionScreen`.
- **m8** `Phase9RepairSuite.scala:654`: the cap test only requires the bundle to exceed half the cap; no escape-heavy 2000-character question test (would fail closed under the right reason).
- **n1** `Verification.scala:741-754,802,671,782`: if the only kept claim is an abstention claim the answer is not marked abstained and has no `abstentionReason`; unsupported numbers parsed from model text are repeated, labelled unsupported, in the weakened claim's uncertainty and the audit. Neither is presented as Verified.

Pending, not verified by the reviewer: rag module test edits are undocumented (G3; counts only); agent `sbt check` after the repair; human `sbt clean check`.
