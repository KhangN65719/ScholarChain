# Work order 12: spark-engineer (D5: bump transformVersion for the ID change)

Human ruling (2026-11-08): D5 fix. The meaning of mention-edge IDs changed (`coauthor:v1:` Spark-local to core `mention-coauthor:v1:`), so Spark's `transformVersion` must change.

Task:
1. Find where Spark defines its transform version (currently `spark-analytics/1.0.0`) and bump it to the next appropriate version (use a minor bump, `spark-analytics/1.1.0`, unless the code's versioning convention says otherwise; state your reasoning). Keep it a single source of truth.
2. Update every place in modules/spark/** (code, module tests, DESIGN.md, load-manifest text) that pins the old string. Record in DESIGN.md that 1.1.0 is the first version emitting core mention-coauthor IDs and that older runs carry legacy IDs.
3. List every file OUTSIDE modules/spark that pins the old version string (read; for modules/storage and tests/** report them, do not edit): the orchestrator routes them to their owners.
4. Do not weaken any test. Run `sbt quick` only (never two sbt at once). Report exact counts. Do not rerun the real-data pipeline. Edit modules/spark/** only. Read-only git. No commit. No AWS.
