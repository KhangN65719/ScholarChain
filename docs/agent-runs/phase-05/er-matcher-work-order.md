# Work order: entity-resolution-engineer, matcher (Phase 5, step 5)

Orchestrator to `entity-resolution-engineer`. Owns modules/entity-resolution/** only.

## Task
Implement the entity-resolution matcher per docs/agent-runs/phase-05/prompt.md ("Required behavior") against the Phase 3 contracts (modules/core) and Phase 4 `MatchCandidate` records (modules/hadoop output; read-only for you).

Include: exact-identifier rules; normalization; feature functions; versioned scoring policy; separate accepted/rejected/unresolved thresholds; conflict detection when authoritative identifiers disagree (never silently merge); output of ACCEPTED/REJECTED/UNRESOLVED/CONFLICT decisions with full provenance fields; review-queue generation; evaluation module (precision, recall, F1, confusion matrix, coverage, per-match-path metrics, threshold sensitivity) that reads a gold file but is separate from matching.

LLM adjudication: implement only the strict JSON schema, bounded-band selection, abstain outcome, cache key (input hash, prompt version, model version, policy version) and an interface with an offline stub/fake. **Make no LLM API call, read no credential, add no HTTP client. Stop and report if the work seems to need one.**

## Hard constraints
- Gold labels and external suggestions must NEVER feed matching logic. Matching code must not import, read or reference the gold-set or suggestion types/files; the evaluator is a separate package that depends on matcher output, not the reverse. Add a test that enforces this structurally (for example a source scan of the matching package for gold/suggestion references).
- Module boundaries per CLAUDE.md: depend on core only (and the existing allowed deps in build.sbt); no dependency on hadoop/spark modules. Do not edit build.sbt (report to build-engineer if needed).
- Keep the one JSON library. Keep original characters in records; fold only in matching keys (F2).
- Before writing implementation code, list which specific test proves each gate item (Gate list in prompt.md). Put the list in your report.
- test-engineer is writing adversarial tests in parallel under tests/**; do not edit tests/**.
- Gate: `sbt check` (exact text); report exact pass/fail counts.

## Do NOT yet
Do not build the pairs export or the labeling tool; that is a separate later work order (needs the real slice).

## Report
Required contract, plus gate-item-to-test list, policy version, feature definitions, thresholds with the rationale, and what is stubbed.
