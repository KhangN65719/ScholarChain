# Work order: hadoop-engineer, R6 (DBLP named HTML entities)

Orchestrator to `hadoop-engineer`. Phase 5, step 1. Narrowed to modules/hadoop/** only.

## Background
`docs/agent-runs/phase-04/ratification.md` item R6: the full DBLP dump uses named HTML entities (for example `&uuml;`, `&eacute;`) declared in dblp.dtd. The no-DTD parsing policy rejects them, so real DBLP records with accented author names fail. Phase 5 needs real-data candidate pairs, so this blocks it.

## Task
Fix R6. Remedy recorded in the ratification: pre-resolve DBLP's published entity set (the named entities defined in dblp.dtd, mapped to their Unicode characters) before or during XML parsing, without enabling DTD loading or external entity resolution (keep XXE protection intact).

## Constraints
- F2 decision (phase-04/decisions.md): keep original characters in stored records; fold or transliterate only into a separate matching key.
- Do not touch other modules, datasets/** or tests/**.
- Unknown or undeclared entities must still be rejected with a counted reject, not silently dropped.
- Every behavior change needs a feature test. Before writing implementation code, list which specific test proves each gate item, in your report.
- Gate: `sbt check` (exactly that text). Report exact pass/fail counts.

## Report
Use the required report contract (STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE), plus the gate-item-to-test list and the source of the entity table (where the entity set came from and how verified).
