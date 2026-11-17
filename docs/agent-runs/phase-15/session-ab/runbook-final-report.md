# Runbook finalization report (deploy-engineer, received 2026-11-17; summary as received)

Work order: `wo-runbook-final.md`. Only file written: `docs/releases/phase-15/session-ab-runbook-draft.md`, relabelled revision 3 FINAL (header had said revision 1 though it held revision 2 edits). No AWS, terraform, sbt or git write; git calls: `git log --oneline`, `git rev-parse 6cd7341`. Nothing executed.

- Item 1: `6cd73419c546b2205a2e2fde09b25a747c3efb8e` replaces every `REPLACE_WITH_WAVE2_COMMIT_SHA`. 0.3 (about lines 196-204) and 0.7 (about 245-253) gates require the commit as an ancestor of HEAD and `git diff --name-only` listing only `docs/` paths; git error fails closed.
- Item 2: 0.0 (169-172) OD-1, OD-7, OD-8 acknowledged with one typed record line; OD-3 ratified rule; same wording in 0.1 OD-7 note, HIGH paragraph, 4.3 heading/table/rule, 6.3 rows, carry-forward note.
- Item 3: new "Step B1" (B1.0-B1.9) before "Failure branches"; A-failure row and step index point to it.
  - Trigger: A not COMPLETED, export missing or fails 2.9/2.10, or not done by its caps (latest T0 + 2:15); a failed A-up apply also triggers b1 but then B does not run.
  - B under b1: default no B. B runs only if the human decides in chat (with an end time) that A's cause cannot affect B, 1.2 state check is clean, B can start by T0 + 3:15, and the B switch plan passes 3.2.
  - b1 steps run after 5.4, outside the session clock: B1.2 list `graph/runs/<run 6 slug>/` (refuse > 1 GiB or > 2,000 objects); B1.3 read-only download into new `rehearsal/b1-graph` inside `if mkdir`; B1.4a all 17 tables have `.parquet`; B1.4b read `sourceRunId` from run 6's S3 export manifest and compare with `run:v1:9f6e6fb0-2ec3-4148-85fc-f5ba7c56ad05`.
  - B1.8 `session2.tfvars`: `export_run_id` = run 6 slug, `graph_run_id` = manifest `sourceRunId`; gated on both BLOCKED-ON closing; not written now.
  - Cost up to 0.10 USD transfer (replaces the 4.1 allowance row); HIGH stays 3.90; T0 limits unchanged; download stopped after 45 min; stop conditions B1.9.
  - Heap under b1 would run on `rehearsal/b1-export` (does not exist, BLOCKED-ON 1); recorded only, 400/800 rule not applied, 4096 MiB stands unless the human decides otherwise.
- Item 4: lines 1-8 FINAL plus placeholder list; open-placeholder section, validation paragraph and next gate rewritten.

BLOCKED-ON
1. infra-engineer (scripts) with spark-engineer, B1.5: no committed local launcher for `scholarchain.spark.JsonlExport`; scripts run it only on the EMR node (`scripts/emr/run-s-pipeline.sh:520`); class at `JsonlExport.scala:30`. Session converts nothing and stops after B1.4; no sizing from b1.
2. infra-engineer (scripts), B1.7: no reviewed script writes a local export to `semantic-context/runs/<slug>/export/` (`upload-inputs.sh` fixed list; `archive-evidence.sh` evidence only). No S3 write offered; `session2.tfvars` not written under b1.

Remaining placeholders: `REPLACE_WITH_BILLED_TOTAL` (0.1); optional `REPLACE_WITH_CLUSTER_ID` (3.7 watch).

Unverified: new shell and 0.3/0.7 gate changes unrun; run 6's S3 layout `graph/runs/<slug>/run=<id>/tables/` and `sourceRunId` field name read from code; whether run 6's Parquet holds the six analytics tables (B1.4a decides); rendered-values table, A-up counts 11/7/3, `RunLoader` wording; transfer rate and b1 wall times.
