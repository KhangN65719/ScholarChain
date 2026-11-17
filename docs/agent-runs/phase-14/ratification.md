# Phase 14 Ratification

- Decision: ACCEPTED by the human owner (Khang), 2026-11-16.
- Decisions ratified: D9 and D11 to D38, as stated by the human ("I accept phase 14 and ratify D9, D11-D38"). D10 (re-derive the M and L caps from the measured S runtime) is not ratified: M and L were not run, so it stays open.
- Basis: decisions D9 to D38, `results-s-tier.md`, the release records in `docs/releases/phase-14/`, the review rounds recorded in D33 to D38 (the last narrow re-review: no blocker; its wording findings applied before commit cc1a907).
- Phase gate: the human ran `sbt clean check` at cc1a907. It finished with Passed: Total 93 and 1,133, 0 failed, 0 errors, `[success]` in 1,216 s, exit 0. Log: `phase14-clean-check-3.log`.
- Script harness: 1,453 cases, 0 failed (`phase14-harness-script-2.log`, after work order 113). Static checks (`check.sh --skip-sbt`) passed with sbt skipped by design for the Terraform-only commit b456cd3.
- Account-ID scan: Cowork scanned every new Phase 14 doc immediately before commit cc1a907 for the account ID, AWS key patterns and 12-digit runs: no matches.

## Delivered (proven on real data, tier S, 3,472 input works)
- EMR pipeline on one single-node emr-7.10.0 cluster (S run 6): preflight 21 s, Hadoop MapReduce on YARN 727 s (9 applications, proven from the ResourceManager), entity resolution 104 s, Spark 4.0.5 on YARN 1,735 s, export 65 s. Outputs published: curated 238 objects, graph 201, semantic-context 26, reports 25.
- Vector ingest: 41,885 vectors written to S3 Vectors with Titan (256 dimensions) across S runs 6 to 8, 0 rejected in run 8; sampled retrieval (two queries per non-empty index, source row at rank 1).
- Neptune graph load (S run 11, graph-only, db.r6g.xlarge writer): `NEPTUNE_LOAD=LOADED vertices=32225 edges=135788`, 12 jobs `LOAD_COMPLETED`, 0 errors, `LOADER_RESULT=SUCCESS`. Evidence is the loader's own lines; there is no independent Neptune count.
- Tooling built to get there: Design B (emr-7.10.0 plus our Spark 4 archive), the on-node preflight and its local rehearsal, the loader-only and graph-only modes, SDK timeouts and progress logging, Neptune error naming, the bounded `--tolerate-load-errors` policy (human threshold: 0 errors LOADED; at most 0.1 % and all named LOADED_WITH_ERRORS, exit 0; otherwise FAILED), the local graph rehearsal, and the S3 Vectors wire-rule checks in the local fake.
- Teardown: session-down 5 applied; read-only confirmation shows no Neptune cluster, only the S3 and DynamoDB gateway endpoints, no active EMR cluster, no ECS tasks, no NAT gateway. Only storage remains.

## Actual AWS billing (human, settings.aws.com/billing, 2026-11-16 18:28 CST)
- Month-to-date (all of Phase 14, November 1 to 16): 3.94 USD. Amount due 0.00 USD; available credits 180.00 USD. Spend limit 20 USD, status "approaching limit", which is the month-end forecast (38.92 USD) extrapolating session hours that were torn down, not current spend.
- The billing view lags by several hours, so today's xlarge Neptune hours may not be fully included; the human re-checks the figure before the Phase 15 report quotes it. The orchestrator's burn-times-time estimates (about 10.8 to 13.3 USD) were upper bounds and are superseded by the billed figure.
- Against the limits: well under the human's 15 USD operating stop, the 20 USD AWS spend limit and the 25 USD course cap. No external (non-AWS) costs were found.

## Accepted-open, carried forward
- Not demonstrated: a second data size (D38, ratified), node elasticity (Experiment B), the AWS-hosted query service, an independent Neptune count, the `LOADED_WITH_ERRORS` path on real Neptune, a single run in which every stage ran in one execution (proven across runs 6 to 11 on the same export).
- Unreconciled counts that must not be quoted as one number: 3,472 input works, 4,118 work records indexed by ER, 3,556 rows in the export's `nodes-work` table. The report explains them or quotes them separately.
- D10 open (no M or L run). `experiment-size-s.tfvars` line 1 still says "5,000 works" (stale).
- Cost trap: the S overlay now sets `neptune_instance_class = "db.r6g.xlarge"`, so any future S session-up builds the large writer for the whole session. Set it back to `db.t4g.medium` (or move the large class to a graph-load-only overlay) before any further session, for example a demo.
- Advisories from the D34 to D38 reviews that were not repaired: the bash 3.2 skip for the whitespace-root case, A1 to A4 (README wording and counts), `upload-inputs.sh` leading-zero hardening, `rehearse-preflight.sh` uploaded to the bootstrap prefix, the loader's remaining unmasked error text on sample-query and Neptune paths, `--graph-only` accepted in pipeline mode.
- Work-order files 85 to 114 are summarized in D36 to D38; the individual work-order files were not all archived.

## Required in Phase 15
- Write the report from `results-s-tier.md` and this record: stage timings, dataset sizes, vector counts, graph counts, the billed cost and the "not demonstrated" list, keeping fixture-only and real-data validation separate.
- Present scaling honestly: one measured size plus an estimate, labelled as an estimate.

## Excluded from the commit
- AGENTS.md, .agents/ and .codex/ (local Codex setup), datasets/slice/, docs/agent-runs/phase-06/readback-paths.py, and the .DS_Store files.
