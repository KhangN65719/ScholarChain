# Work orders, afternoon 2026-11-17 (orchestrator)

Context: `session-ab/session-record.md`; decisions P15-34 to P15-37 in `docs/agent-runs/phase-15/decisions.md`. HEAD `5c69ba9` (A + B docs commit on Wave 2 `6cd7341`). Rules for every order: no AWS command, no terraform, no sbt, no git write (human, until the human is back tonight); read-only git only; no account ID in any file; us-east-2 only; never suggest changing the spend limit or "Activate advanced features".

## WO-B1 (deploy-engineer): B-only session runbook
Write `docs/releases/phase-15/session-b-only-runbook.md` (DRAFT for review) from `session-ab-runbook-draft.md`:
- Section 0 "Tonight, before the B-only session": the A + B leftovers in order: 4.1 (download A's export, skip if `rehearsal/a-export` already matches), 4.2, 4.2b, 4.3 rule (P15-30), 4.4 read as "no change outside `docs/`" (P15-36), the read-only 3.2 and 5.2 record reads against the saved logs, the 5.4 EBS and 24 h cluster reads, 2.8c, 2.8d, 2.8e (optional).
- B-only session: billing gate (HIGH for B-only from the A + B numbers; the human gives the billed figure), HEAD and clean-tree gates (accept docs-only commits after `5c69ba9`), state check, 1.10 re-check of the uploaded bootstrap and Spark jar (reuse if MATCH; otherwise stop, no new upload unless a reviewed 1.9 path is added), plan up from the down state with `experiment-size-s.tfvars` + `elasticity-s.tfvars` (state the expected counts from `SESSION2.md` 5a and session 1's B-only path, UNVERIFIED), read check, rendered and deployed definition (B column), deploy, new B records (reuse `p15-ab-b.json` run id `run-v1-730f95f0-ff71-4763-bba6-a0ddbc39e87f` only if no S3 prefix exists for it; new manifest copy and record names `p15-bo-*`, old `p15-ab-b-*` records unused and expired), B run, 3.8, 3.9, session-down (course + `session-down.tfvars` only), post-teardown check, wall-clock limits and stop rules.
- Report: changed files, expected plan counts with sources, every UNVERIFIED item, and the ordered list of human commands for tonight.

## WO-R (code-reviewer): session 2 readiness review (read-only)
Review readiness for session 2 against `infra/terraform/SESSION2.md`, `infra/terraform/environments/course/*.tfvars`, `modules/services/**`, `modules/storage/**`, `modules/rag/**`, `infra/terraform/modules/**`, `experiments/**`, `load-tests/**`, decisions P15-1 to P15-37. Items:
1. `session2.tfvars` (outside the repo) holds `export_run_id = "run-v1-b76e2d37-2ac1-40d4-923b-9418d5ff72c7"` and `graph_run_id = "run:v1:3cfb57b1-abd1-4913-8e53-da477d8d6027"`; the third line `load_client_image_tag` is added at session 2. Check layering and validations accept it.
2. `services-smoke.tfvars`: exactly 1 query task, at most 4 Haiku queries (P15-27), Bedrock model ID configuration (P15-13), smoke keys.
3. Neptune graph-only reload of A's export: is there a reviewed path (scripts, loader, Neptune sizing P15-5) that loads A's 17-table export graph-only into Neptune for session 2? Name the commands and gaps.
4. Service task memory: 4096 provisional; 2048 if the 4.2 measure meets P15-30. Where it is set and what changes.
5. D: the ECS CPU target-tracking target value, min/max tasks, cooldowns; consistency with P15-1 load levels.
6. Haiku smoke: 1 task, at most 4 queries, `bedrock_max_calls` per process.
7. IAM: runtime and worker policies for Neptune openCypher (`neptune-db:*` actions scoped to the cluster) and `bedrock:InvokeModel` scoped to the inference profile and model ARNs in us-east-2; least privilege; any gap.
Return BLOCKER/MAJOR/MINOR with file:line, and a list of what must change before session 2 and who owns it.

## WO-PE (spark-engineer): why `path-evidence` has 42 rows (read-only)
A's export manifest (local copy `/Users/winnguyen/scholarchain-data/logs/phase15-ab-a-export-manifest.json`, `tables.path-evidence.rows = 42` per the 2.10 report) on tier S. Read `modules/spark/**` (path-evidence generation, `ExportTables`, its inputs and filters) and the manifest's per-table rows. Explain whether 42 is expected for tier S (seed selection, hop limits, window filters, caps), cite file:line, and say whether the services' path queries in session 2 (E retrieval modes) have enough rows. No code change; propose a fix only as a recommendation with the test that would prove it.
