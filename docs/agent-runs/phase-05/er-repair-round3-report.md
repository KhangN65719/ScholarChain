# entity-resolution-engineer report: last repair round (same-record ORCID CONFLICT, policy 1.4.0, cleanup)

Author: entity-resolution-engineer (archived by the orchestrator, condensed). Date: 2026-11-07. Edits only under modules/entity-resolution/**. No commit, network, LLM or credentials. No threshold or weight change. Ruling: an agreeing ORCID on two slots of the SAME work record is CONFLICT regardless of whether the work has a DOI or an arXiv id.

## Correction
The earlier report line "still ACCEPTED" held only for records with no DOI and no arXiv id; with an id the position rung already gave CONFLICT, so the outcome depended on whether the work carried an identifier. Fixed.

## Rule
Two mentions are two slots of one work record when both are researcher mentions, `workId` is equal (one source record), both `authorPosition` values are known and at least 1, and the positions differ. It uses neither the author list nor any DOI/arXiv id: `AuthorSlots.distinctSlotsOfOneRecord`, fired in the position CONFLICT rung in `Matcher.scala` before ID agreement. Reason code reused: `conflict.id-agreement-contradicted-by-position` (identical contradiction; one code keeps consumers and the evaluator unchanged). Rule codes: a shared DOI or arXiv id still records its own position rule code; otherwise `rule.same-record-distinct-position` (`Matcher.SameRecordRule`). Unchanged: same ORCID at the same position stays ACCEPTED; unknown position stays ACCEPTED; different `workId` with no shared key stays ACCEPTED; same record without an ORCID agreement stays REJECTED (`rule.same-work-distinct-mention`).
Open choice flagged by the agent: an agreeing aggregator ID on two slots of one record with no DOI or arXiv id stays REJECTED (the ruling named ORCID only); with a DOI or arXiv id it is already CONFLICT. To extend CONFLICT to aggregators: in `agreeingByPosition` change `else if sameRecord then ids.agreements` to add the supported aggregator agreements.

## Tests
New `SameRecordIdAgreementSuite.scala`, 11 synthetic tests, each also checked with sides swapped: same record, two slots, agreeing ORCID: with a DOI, with an arXiv id only, with neither: CONFLICT; with-id and no-id cases share the reason code; same slot: ACCEPTED, no CONFLICT rule; unknown position on one side: ACCEPTED; different records, no shared key: ACCEPTED; same record, no ORCID agreement (no ids; ORCID on one side only): REJECTED; same record, aggregator ID: REJECTED; two different ORCIDs on one record: still the ID-conflict rung; unit test of the slot predicate. The MatcherSuite test at ~265-271 (no ids) is unchanged. Independent regression test: `tests/adversarial/AdversarialErSameRecordSuite.scala` (9 tests, test-engineer).

## Policy 1.4.0
Policy.scala has a 1.4.0 history paragraph and a "Same work record" bullet under the position CONFLICT; `v1Version` = `scholarchain.er.policy/1.4.0`; updated MatcherSuite (frozen-score test) and IdentifierRulesSuite (version test); 1.3.0 text in the header describes history on purpose; docs/** and tests/** not searched for the old string (grep refused), everything passed, so no test pins 1.3.0. The gold-v3 re-run will print 1.4.0 against 1.3.0; `--compare-decisions` ignores the version.

## Finding 2 cleanup
`Matcher.scala` decision-order comment lists the seven rungs (ID conflict, position/same-record CONFLICT, ID agreement, distinct mentions, family name, same-title accept, scoring) as `-` bullets "Rung N" (the formatter mangled numbered lists); `AuthorSlots.distinctAuthorsOfOneDoi` removed (replaced by `distinctSlotsOfOneRecord`); Policy.scala header calls `rule.same-arxiv-distinct-position` the rule code and `rejected.same-arxiv-distinct-position` the reason code, same wording for DOI.

## Agent's `sbt check` (not the recorded gate)
`[success]`; read: entity-resolution `Passed: Total 366, Failed 0, Errors 0` (line 1314; previous 355, +11 = the new suite) and hadoop `Passed: Total 207` (line 1425); the core and tests summary lines were not read (128 KB output; grep/cp refused); `AdversarialErSameRecordSuite` ran and passed. scalafmt check passed; the post-edit hook modified two of the agent's edited files and reported no collateral outside them.

## Risks
The gold-v3 re-run may show more CONFLICT decisions (unknown how many frozen-sample pairs have an ORCID on two slots of one record without an id); two same-name coauthors listed in a different order by two sources can still be falsely REJECTED.
