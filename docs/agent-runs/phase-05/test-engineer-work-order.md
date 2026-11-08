# Work order: test-engineer, entity-resolution adversarial tests (Phase 5, step 5)

Orchestrator to `test-engineer`. Owns tests/** only. Works from the specification, in parallel with the owner, not from the implementation.

## Specification
docs/agent-runs/phase-05/prompt.md (Required behavior, Evaluation requirements, Gate), the Phase 3 contracts in modules/core, and Phase 4 `MatchCandidate` in modules/hadoop (read-only).

## Task
Write adversarial and cross-module tests under tests/adversarial (or the existing tests/ layout) covering the Gate:
- Exact-ID conflicts (two different ORCIDs/DOIs for one pair) must yield CONFLICT, never ACCEPTED.
- Normalization edge cases: Unicode/diacritics, punctuation, initials, name order, DOI prefixes (https://doi.org/, doi:), title markup.
- Common-name traps: same name different people must not be accepted on name alone.
- Same person, name variants/initials, must not be rejected when strong evidence exists.
- LLM adjudication schema: abstain allowed, invented identifier not in candidates rejected, output outside the unresolved band not called, cache key changes with any of input hash / prompt / model / policy version. No real LLM or network.
- Leakage: matching package must not reference gold-set or suggestion types.
- Evaluation: precision/recall/F1/confusion matrix computed correctly on a hand-built gold set, reported per match path.

The entity-resolution API does not exist yet or is changing. Write tests against the spec and the contracts; where the API is unknown, record the assumed names in your report and stage them so `sbt check` stays green or the failing tests are clearly marked pending. Report exact pass and fail counts. A written but unexecuted test is not evidence. Route production fixes to the owner as BLOCKED-ON. Do not edit production code or module-owned tests.
