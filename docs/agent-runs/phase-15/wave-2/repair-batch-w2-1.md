# Wave 2 repair batch 1 (orchestrator, 2026-11-17)

Source: harness `phase15-w2-harness.log` (`RESULT pass=1742 fail=5`, `SKIPPED_CASES 0`, `real-repository-untouched` PASS) and R15 (two harness pins of the old b-burst values). Human direction: one batch; stale cases updated to the new design without weakening; fix retention and the unread variable. Decision P15-26 (missing tables).

| # | Case / item | Wrong side | Owner |
|---|---|---|---|
| 1 | 1297 `14.docs.loader-only.log-group-in-both-readmes...` pins `for_each toset(["fast","slow","loader"])` | stale case (WO-10 `service_layout = combined`) | test-engineer |
| 2 | 1595 `15.experiment.b-collect.a-first-run-makes-the-raw-files-and-ends-complete` expects `emr-list-instance-groups.json` | stale case (R14) | test-engineer |
| 3 | 1596 `15.experiment.b-collect.every-aws-call-...-there-are-nine` | stale case (R14: 8 calls) | test-engineer |
| 4 | 1664 `15.wave2.terraform.runtime-log-retention-is-at-least-seven-days` (resolves to 3) | Terraform | infra-engineer |
| 5 | 1666 `15.wave2.terraform.every-environment-variable-a-task-sets-is-read-by-the-code`: `SCHOLARCHAIN_QUERY_TOKEN` | see note | infra-engineer + test-engineer |
| 6 | R15 pins: run.sh:10903 conf needles; run.sh:10430-10431 `-D` options | stale cases (P15-23) | test-engineer |
| 7 | P15-26: six export tables | Spark export | storage-engineer (columns) then spark-engineer |
| 8 | P15-26: `graph_run_id` from A's run, documented | Terraform / SESSION2.md | infra-engineer |

Note on 5: `SCHOLARCHAIN_QUERY_TOKEN` is the bearer token the Python load client (`load-tests/query-load/load_client.py`) reads; the case scans only Scala mains. Infra first confirms the variable is set only on the load-client task and only through the ECS secrets block (if it is set anywhere else, or in plain environment, that is the Terraform fix). The case then accepts a name on the load-client task only if `load_client.py` reads it (proved by the case), which keeps it as strict for every Scala task.

sbt order: storage-engineer, then spark-engineer; nobody else runs sbt.
