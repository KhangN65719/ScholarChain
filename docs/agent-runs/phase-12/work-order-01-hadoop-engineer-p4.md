# Work order 01: hadoop-engineer, Phase 11 P-4 (four carried defects)

Owner: hadoop-engineer. Scope: `modules/hadoop/**` only (and module-owned tests under it). Do not edit `tests/**`, `build.sbt`, `.claude/**` or any other module. Local only, no commit.

## Defects (each has a `.fail` twin whose body asserts the CORRECT behavior)
1. Comment and CDATA records read as live. `XmlRecordStream.seekRecordStart` skips nothing inside `<!-- -->` or `<![CDATA[ ]]>`. A commented-out `<article>` is emitted and parsed as a publication. Twin: tests/adversarial/AdversarialXmlSuite.scala:69 ("a commented-out region contributes no record"). Required: comment and CDATA regions contribute no record, including the tight `<!--<article` form, a CDATA section, and a region split across read buffers or input splits. Also correct the false comment at XmlRecordStream.scala:87-90 so it matches the behavior.
2. Zero-author works count as single-author. `TeamExpansion.emit` increments `SingleAuthorWorks` when a work has no authors. Twin: AdversarialMissingFieldSuite.scala:211. Required: SingleAuthorWorks is 0 for a zero-author work. Decide how a zero-author work is counted (keep an existing counter if one fits; a new counter needs the same wiring and docs as the others) and report the choice.
3. A shared ORCID is not reported as a conflict. Two authors of one work carrying the same ORCID: `LocalPipelineRunner.joinIdentifiers` returns `ConflictRecords == 0`. Twin: AdversarialDuplicateIdSuite.scala:290. Required: `ConflictRecords` rises, the conflicting authors are not silently merged, and provenance is kept. Do not decide match policy; entity resolution owns decisions, so emit the conflict record only.
4. Pair consolidation depends on arrival order. `TeamExpansion.consolidate("k", Vector(pair, replay))` keeps a different retained record than `Vector(replay, pair)`. Twin: AdversarialDuplicateIdSuite.scala:271. Required: the full result (retained pair, duplicate count, provenance) is identical for any arrival order. Use a documented total ordering (for example the lowest runId, then source record id) and keep the duplicate count unchanged.

## Procedure
1. Before writing code, list which specific test proves each item (the existing twin, plus any module test you add). Put the list in the report.
2. Fix production code only. Add module tests under `modules/hadoop/**` for edge cases the twins do not cover (split boundaries, order permutations of 3 or more records).
3. Each fix must record provenance for any new record it emits.
4. Run `sbt quick` once (never two sbt commands at once; stop and report a hang after 15 silent minutes). Expected: the four twins now FAIL because munit `.fail` fails on a passing body, and the pin tests that assert the old behavior fail too. Name every failing test and confirm each belongs to this set. Any other failure is a regression: fix it.
5. Do not run `sbt check`.

## Report (contract fields)
STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS (exact counts and exact failing test names), ASSUMPTIONS, RISKS, then the item-to-test list and the zero-author counter decision. State which pins and twins test-engineer must flip.
