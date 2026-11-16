# Phase 14 release records

Owner: deploy-engineer (WO 115). Records only. These files were written after the fact from the logs and records in the operator data directory `scholarchain-data/` (logs, approvals, plans, packages, runs), which sits outside the repository. No AWS, Terraform, Docker, sbt or script command was run to write them. Nothing in them is a new approval.

Environment: course, us-east-2, account `<account-id>`. Cost ceiling per approval: 20 USD. Bucket names that embed the account id are written `<data-bucket>`.

## Index

| File | Contents |
|---|---|
| `deploy-s-session-12.md` | HEAD `02437a90`, package-7, plan-12, image tag change |
| `deploy-s-session-13.md` | HEAD `8cefc14d`, package-8, plan-13, loader 512/1024 to 2048/8192 |
| `deploy-s-session-14.md` | HEAD `c4ebc1fa`, package-9, plan-14, image tag change |
| `deploy-s-session-15.md` | HEAD `b456cd3f`, package-10, plan-15, Neptune writer `db.t4g.medium` to `db.r6g.xlarge` |
| `deploy-down-5.md` | HEAD `b456cd3f`, plan-down-5, teardown, stateful change acknowledged |
| `deploys-earlier.md` | Brief records for deploy-3, session-down 4, sessions 5 to 11, down-1 to down-4 |
| `runs-s-6-to-11.md` | One section per pipeline run 6 to 11, with the limits of the evidence |
| `idle-time-and-spend.md` | Phase-wide idle-time and spend arithmetic with every input and source; not a bill |

## Outcome in one table

| Item | Result |
|---|---|
| Deploys 12, 13, 14, 15 | DEPLOY_COMPLETE logged, steps 1 to 12 `ok` |
| deploy-down-5 | DEPLOY_COMPLETE logged; read-only confirm shows no Neptune cluster, only S3 and DynamoDB gateway endpoints, no active EMR cluster, no ECS tasks, no NAT |
| Run 6 | FAILED, loader step timed out |
| Run 7 | FAILED, loader exit 1, PutVectors rejected empty arrays |
| Run 8 | FAILED, OutOfMemoryError in the graph phase on the 1 GB loader task, after the vector ingest completed |
| Run 9 | FAILED, graph-only, `edges-COAUTHORED_WITH.csv` bulk load `LOAD_FAILED`, 3 insert errors |
| Run 10 | FAILED, graph-only, Neptune `OUT_OF_MEMORY_ERROR` on `db.t4g.medium`, 153,760 errors counted |
| Run 11 | SUCCEEDED, loader `LOADER_RESULT=SUCCESS`, `NEPTUNE_LOAD=LOADED`, 12 jobs `LOAD_COMPLETED`, 0 errors |

## What is and is not evidenced

- Deploy evidence is the deploy script's own log: plan digest bound, approval verified, identity check, apply, image push, revision verification. Services run at desired count 0, so no deploy was smoke-tested by serving a request.
- The graph load is evidenced only by the loader's own report lines. Neptune cannot be queried from outside the VPC and no independent query was made. The cluster no longer exists and was destroyed with `skip_final_snapshot = true`.
- Run 11 is the only run with a clean graph load. No single execution completed EMR, vector ingest and graph load together. Vector ingest finished in run 8, the graph load in run 11.

## Spend

- Per-run figures in `runs-s-6-to-11.md` are burn times elapsed. They are not a bill. They sum to 1.52 USD for runs 6 to 11 and cover executions only.
- They exclude the idle time while a session was up between runs. The phase-wide arithmetic, with every input and source written out, is in `idle-time-and-spend.md`. It gives roughly 10.8 to 13.3 USD for runs 1 to 11 plus idle time under two named cases (and a stress case of about 19.6 USD), for the five session windows from the session-5 deploy approval to the down-5 approval. It is arithmetic on human-supplied burn constants, several of which are marked UNVERIFIED in the repo. It is not a bill and not an estimate of record. The named cases (about 10.8 to 13.3 USD) approach the 15 USD operating stop and the stress case (about 19.6 USD, not expected) exceeds it; only AWS billing settles it. It leaves out Bedrock, S3 storage and requests, S3 Vectors, ECR, CloudWatch, data transfer, Secrets Manager, DynamoDB, and anything before the session-5 deploy approval.
- The single longest window runs from the session-11 deploy approval (2026-11-16T02:29:16Z) to the down-5 approval (2026-11-16T22:17:52Z), 19.81 hours, with no teardown in between. It is a window between those two approvals, not "between run 7 and run 11".
- Actual spend must come from AWS billing, which the human owes. I did not read any billing data.

## Discrepancies between the work order brief and the logs

1. Apply change counts. Plans 12, 13 and 14 say 3 to add, 3 to change, 3 to destroy; their apply logs say 3 added, 2 changed, 3 destroyed. Plan 15 says 4 to change; its apply says 3 changed. In each case the state machine is the plan's in-place update and has no "Modifying" line in the apply. Only down-5 logs it. Cause not established. The brief gave the plan counts.
   HYPOTHESIS, not established: the state machine references the loader task definition without a revision, so its definition diff is only "known after apply" because the new task definition ARN is unknown at plan time (comment in `infra/terraform/environments/course/experiment-size-s.tfvars`, lines 31 to 33: "changes in place only because the new ARN is unknown at plan time"). After apply that diff may resolve to no change, so Terraform would not modify it. The apply logs are consistent with that but do not show it. It does not by itself explain why down-5 logs a state machine modification, and I did not read what changed there.
2. Session-13 wording. The brief said services and state machine "in place". The plan says so; the apply log confirms only the services.
3. Down-5 IAM. The brief said IAM changes were Neptune-only. The plan summary also lists the loader and runtime role policies being changed in place, which removes the Bedrock invoke statements as well as the Neptune statements. All IAM changes are removals. The services' `SCHOLARCHAIN_BEDROCK` setting goes from `on` to `off`.
4. Session-15 `apply_immediately`. Not visible in the plan log (hidden among unchanged attributes), but the committed module sets `apply_immediately = true` on both the cluster (`infra/terraform/modules/neptune/main.tf:118`) and the writer instance (`:139`). `experiment-size-s.tfvars:46-47` and `docs/agent-runs/phase-14/decisions-d38.md:26` say the same. This is evidence from the committed source, not from the plan log. The in-place instance class change is confirmed, and the writer shows `db.r6g.xlarge`, `available`, no pending modification afterwards.
5. Run 6 is a full pipeline run (EMR then loader), not loader-only. The loader hit its 1,800 s cap and the execution failed with `States.Timeout` on the `RunLoader` step. Sources: `docs/agent-runs/phase-14/decisions-d36.md:10` and `experiment-size-s.tfvars:73-76` and `:101` (the S loader cap was 1,800 s until work order 86 raised it to 5,400 s). The loader log's first line is at 20:18:50Z and the execution ended at 20:48:22Z, consistent with 1,800 s.
6. Session-12 package. package-7 matches HEAD `02437a90`. Package records exist only for packages 5 to 10 (sessions 5 and 11 to 15). None was found for deploy-3 or sessions 6 to 10.
7. Down-5 confirm log (`logs/phase14-teardown-5-confirm.log`). It has section labels (`--neptune`, `--endpoints`, `--emr`, `--ecs`, `--nat`); an earlier version of this record said it had none, which was wrong. It has no timestamps and does not record the commands used. "No ECS tasks" is read from the empty `--ecs` section.
8. Run 8's pipeline approval and session-12's deploy approval carry the same timestamp (2026-11-16T08:32:51Z). The two approval files are separate. Noted only; I do not know how they were issued.

## Open items

- Billing figure owed by the human.
- `RUN_ID_MATCH=false` in runs 9 to 11 is expected, per `docs/agent-runs/phase-14/report-53-storage-engineer-loader-path-check.md` section 3 (also `decisions-d32.md:28`): no code enforces equality, the loader keys its work by the Spark run id from the export manifest (`run-v1-9f6e6fb0-...`), and the pipeline argument is `run-v1-192de0fd-...`. Consequence kept from that report: the graph in Neptune is named for the Spark run `9f6e6fb0` (CSV prefix `graph/neptune/v=v1/run=run-v1-9f6e6fb0-.../`), while the loader reports and run records sit under `reports/runs/run-v1-192de0fd-.../loader/`. Anyone reading the graph should not assume it is keyed by the `192de0fd` id.
- The duplicate counts in run 11 are not established by the logs, and whether every row of every file was inserted is UNVERIFIED. SHOWN: run 10's `graph-report.json` (`<loader prefix>/20261116T201756Z/graph-report.json`, read by the human with a status-only filter) lists 12 jobs, jobs 1 to 8 `LOAD_COMPLETED`, job 9 `LOAD_FAILED`, jobs 10 to 12 `LOAD_COMPLETED`; so the `LOAD_FAILED` return-instead-of-throw path ran on real Neptune in run 10. Its per-job counts were not read, and run 11's report (`20261116T213609Z`) is still unread. So jobs 1 to 8 and 10 to 12, that is each of the 11 files with duplicates equal to records in run 11, completed a load in run 10, before run 11; this does not depend on run 9. INFERENCE only: run 9 also completed jobs 1 to 8 (`failOnError` true, stopped at job 9; from the loader code and the run 9 log, run 9's report unread), and run 11's duplicate counts are caused by those earlier loads. The AWS docs define openCypher duplicates only for node-file rows, and `edges-COAUTHORED_WITH.csv` showing 0 duplicates in run 11 after run 9 processed 92,256 of its records and run 10's job 9 failed again is also unexplained (a failed job's partial data may not have been kept; not verified). Detail in `runs-s-6-to-11.md`, "Limits of this evidence".
- Plan-versus-apply count gap on the state machine (hypothesis above, cause not established).
- Process gap, deploy order and run timing. The deploy script applies Terraform first and builds and pushes the images afterwards, so for a short time the task definitions and services point at a tag not yet in ECR. In `logs/phase14-deploy-12.log` the apply is lines 35 to 93 and the image pushes are lines 97 to 214; deploy-13 apply 35 to 93, pushes 97 to 217; deploy-14 apply 35 to 93, pushes 97 to 222; deploy-15 apply 35 to 161 (the writer change took 11m1s), pushes 165 to 289. The deploy logs carry no timestamps. Run 8's pipeline approval has the same second as deploy-12's approval (2026-11-16T08:32:51Z), and run 8 started at 08:33:33Z, 27 seconds after the apply log's start stamp (08:33:06Z); run 9 started about 2.6 minutes and run 10 about 2.3 minutes after their deploy's apply start. That each deploy had finished before each run started is shown only by the `DEPLOY_COMPLETE` lines, which are unstamped, and by the read-only checks cited in the records (the ECS task records for runs 7 and 8 name the expected image digest). I did not find a timestamped completion marker. Recommend to the script owner, through the orchestrator: stamp `DEPLOY_COMPLETE`, and consider pushing images before the apply or having the run launcher check the deployed revision. I do not author scripts.
- Tolerance flag evidence. `scripts/run-bounded-pipeline.sh` prints a marker only for `--graph-only` (it sets `LOADER_GRAPH_ONLY=yes` at lines 224 to 227 and prints it at 266 to 268) and prints nothing for `--tolerate-load-errors`. That runs 10 and 11 used `--tolerate-load-errors` is evidenced only by the input file `s-graph-only-2.json` and its sha256 `b37ff40b51303eeb1528c4d38e4408dc57b8354b6595151f9ea344899899c302` (the file's loader command has `--graph-only` and `--tolerate-load-errors`; run 9's file has only `--graph-only`). The consequence matters: with `failOnError` false (`AwsIngestMain.scala:307`) the loader returned run 10's `LOAD_FAILED` job instead of throwing (`AwsNeptuneBulkLoader.scala:32` and `:466-470`), and the rag policy then refused the result because `OUT_OF_MEMORY_ERROR` is never tolerated. So the return-instead-of-throw path ran in run 10, while the acceptance path (`LOADED_WITH_ERRORS`) never ran on real Neptune, since run 11 had no errors.
- Items left running by design after down-5: S3 buckets and objects, DynamoDB tables, S3 Vectors bucket and indexes, ECR repositories and images, CloudWatch log groups, the Step Functions state machine, the ECS cluster and services at desired count 0, IAM roles. Cleanup of any of them is a separate human-approved operation. I did not list the live account.
- Earlier deploys (`deploys-earlier.md`) lack apply counts and smoke detail in this record.

## Next gate

Independent review of these records by the code-reviewer through the orchestrator. Any new deploy, rollback or cleanup needs a fresh plan, a new human approval record and the human-controlled execution path.
