# Phase 6 Ratification

- Decision: ACCEPTED by the human owner (Khang), 2026-11-08.
- Basis: ratification-summary.md, review.md (round 3 APPROVE by code-reviewer, Opus), repairs.md R1-R4, validation.md.
- Gate: `sbt clean check` (log phase-06-clean-check-4.log) ended [success] in 1133 s. Core 41, entityResolution 389, hadoop 207, sparkAnalytics 61 and root 373 tests ran with 0 failures. The reviewer read the log.
- Final local Spark run: run:v1:fc4bbd1a-f751-49a3-8eb3-a068e4a1a60a exited 0, with all 13 invariants passing and _work removed. Convergence and shuffle totals are agent-reported.
- Inputs: ER batch over all match-candidates (policy 1.4.0, no threshold change), plus the Phase 5 subset-v6 Hadoop outputs.

## Accepted limits (detail in ratification-summary.md)
- Fragmented graph: the largest component in the all window is 0.85% of nodes, because conservative ER leaves most mentions unresolved.
- 896 mentions with ORCIDs in integrity-failed ER clusters get no canonical ID. Whether ORCID equality alone may merge is DEFERRED (an open policy question).
- Unresolved-endpoint edges use a Spark-local coauthor:v1 ID. A core CoauthorEdge contract change (domain-engineer) is a REQUIRED Phase 7 prerequisite before any graph load.
- The run ID does not track code outside a git checkout.
- The real-run Parquet tables were not read back independently (no reader installed).
- The bounded EMR run is DEFERRED; a bounded local Spark run replaced it.

## Excluded from the commit
- datasets/slice/ (the retired Phase 5 slice, already excluded in phase-05/ratification.md), docs/.DS_Store, docs/agent-runs/.DS_Store, docs/agent-runs/phase-06/readback-paths.py (never ran), .codex/, .agents/, AGENTS.md.
