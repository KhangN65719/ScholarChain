# B-only runbook review 2 (code-reviewer, received 2026-11-17; summary as received)

**REQUEST CHANGES.** Review-1 items B1, M1, m1-m6 closed (0.5 :116/:119 = 1.3d :258 = 1.4 :280; cents :211-216; 1.7a :334 fails closed; out-dirs :379, :599; :62, :311 closed; :439 fine). Two new minor defects from the repair:
1. :237 (m5 repair): `--skip-sbt` prints `GATE sbt=SKIP` (`scripts/check.sh:264`) but :237 stops on any non-PASS gate line; allow `GATE sbt=SKIP`, stop on any other non-PASS; say `SKIPPED_GATES=sbt`.
2. :379 (m3 repair): "and a new log name" breaks later commands (log names `phase15-bo-up-*` do not contain `p15-bo-up`), so 2.2a/2.2b (:399, :405) would read the old log and 2.4W (:458) copy the old `PLAN_DIGEST` (fails closed at the dry run, after a misleading 2.2 pass). Drop "new log name" or name the log substitution in 2.2-2.5; :599 replace the out-dir in every 4.x command.
Pending: fresh 0.5 and 1.3d run; regexes unrun; harness count; `LAG_USD` choice.
Orchestrator note: the allowlist is being extended to ten paths (experiment-engineer's `load_client.py`, `load-tests/query-load/README.md`, `experiments/e-retrieval-modes/run.sh`).
