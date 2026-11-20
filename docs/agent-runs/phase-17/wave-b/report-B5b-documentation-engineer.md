# WO-B5b report (documentation-engineer), as received 2026-11-19 (condensed by the orchestrator)

STATUS: COMPLETE. FILES MODIFIED: `reports/final-report.md`: section 2, a new class-3 entry M11, and two adjacent list references. The Claude-in-chat edit at `:59-60` was not touched.

## Section 2
- Quotes the central problem and the three questions verbatim, cited to the `prompt.md` appendix (P17-29).
- A 13-row table maps each required measure to where it is computed and where a result is recorded, with citations (`DESIGN.md`, `Coauthorship.scala:309-351`, `CollaborationPipeline.scala:457-466`, `EVALUATION.md`).

## Measures stated as not met or not derived
- Betweenness is not computed (`bridgeScore` is the participation coefficient).
- Path length and effective diameter are computed but not exported or recorded.
- Coreness values are not reported.
- The size of temporal changes is NOT DERIVED.
- The largest-team papers are stored but not cited.
- No shortest path is validated (4 questions, all abstained).
- The component fraction is the Phase 6 subset figure.
- Topic, venue and institution filters return 422, and their edges are not loaded.
- No ranked list is recorded for the course's representative query.

## Class 3
- M11 (P17-29): the Claude-in-chat edit of the M4 and M5 rows, and the second `prompt.md` appendix.
- P17-29 is added to the human-decision list.

## Risk
Tests of coreness on hand-checkable graphs were not verified, so they are not claimed.
