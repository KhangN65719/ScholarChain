# Work order 01: domain-engineer (CoauthorEdge core ID for unresolved endpoints)

Owner: domain-engineer. Output: modules/core/** only.

## Background
Phase 6 ratification (docs/agent-runs/phase-06/ratification.md): edges whose endpoints are unresolved mentions use a Spark-local `coauthor:v1:` edge ID, and metrics rows have no core form. About 71% of unresolved nodes are affected. This is a required prerequisite before any graph load.

## Task
1. Find the current core CoauthorEdge contract and how Spark builds the `coauthor:v1:` ID (read modules/spark and modules/core; do not edit Spark).
2. Change the core contract so a CoauthorEdge whose endpoints are unresolved author mentions gets a deterministic core edge ID derived through core identifier rules, stable across runs, order-independent for the two endpoints, and distinct from resolved-researcher edges. Core must also have a core form for the unresolved-mention node ID if none exists.
3. Preserve provenance (source and transformation) and JSON serialization with a schema/version bump if the contract rules require it. Keep one JSON library.
4. Tests: feature tests for determinism, endpoint-order independence, resolved vs unresolved separation, collision resistance across kinds, serialization round-trip, and rejection of malformed endpoints. Do not weaken or delete existing tests.
5. Before implementing, list which test proves each primary-gate item.
6. Run `sbt quick` (never two sbt commands at once; logs under /Users/winnguyen/scholarchain-data/logs/, never target/). Report exact counts.

## Report must include
The exact new ID format and constructor/API names, so spark-engineer can adapt; schema version change; test list; counts; any BLOCKED-ON.
Do not edit CLAUDE.md, docs/agents.md or .claude/**. No commit. Read-only git only.
