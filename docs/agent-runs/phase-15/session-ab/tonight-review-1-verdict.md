# Tonight runbook review 1 (code-reviewer, received 2026-11-17; summary as received)

**REQUEST CHANGES.** 4 BLOCKER, 5 MAJOR, 9 MINOR. File: `docs/releases/phase-15/session-tonight-runbook.md`; checked against `tonight-infra-report.md`, `tonight-experiment-report.md`, `SESSION2.md` 5b.

BLOCKER
- B1 :436-440, :17 (A1, D5): A1 also creates `aws_iam_role.bulk_load` + policy (`modules/neptune/main.tf:58-67`, trust `rds.amazonaws.com`); read step would stop; D5 "no new principal" wrong.
- B2 :595-597, :599, :625, :593 (A4): stop rules would halt the teardown (query and loader task definitions replaced; bulk-load role and policy destroyed; 2/7/31 with possible +1/+1). Keep stops only for memory validation error, CPU change, or destroy of log group, ECR repo, worker role or dashboard; "A2 never reached" teardown expects bulk-load destroys.
- B3 :14, :188, :198, :218: `load_client.py`, `load-tests/query-load/README.md`, `experiments/e-retrieval-modes/run.sh` missing from the E.8a add list and three allowlists; replace `^experiments/pipeline-inputs/` and `^tests/scripts/` prefixes with exact files.
- B4 :13, :549, :580-583 (E): D1 text stale (`run.sh:30-45` reads `SCHOLARCHAIN_E_MODES`, `SCHOLARCHAIN_E_LIMIT_QUERIES`); run line sets neither (1,053 requests); mixed smoke needs its own invocation and distinct record name; gap-note check greps a non-existent log key (sources: `evidence.uncertainty`, `vector_gap_note` in `LOADCLIENT_RAW`).

MAJOR
- M1 :488, :496, :556: delete the "expected mismatch" paragraph; add fail-closed `aws ecr describe-images` at HEAD after S.5 before smoke, D, E.
- M2 :520 (A3): expect 1/2/1.
- M3 :436, :482, :599: fixed EXP A1 11/6/3, A2 34/3/6, A3 1/2/1, A4 2/7/31 (+1/+1 variant).
- M4 :516 (D6): write the full smoke `run-task` command and pass check (four `LOADCLIENT_RAW`, `LOAD_CLIENT_DONE requests=4 stop_reason=none`); P15-27 UNVERIFIED until run.
- M5 :14, :201: the evening commit changes code (`tests/scripts/run.sh`, `load_client.py`, E `run.sh`, tfvars, `variables.tf`); CLAUDE.md requires a passing agent `sbt check` for a follow-up commit that changes code, or an explicit human ruling. Harness counts and test-engineer's cases pending before E.8.

MINOR: :158, :266 `cat` exit masks the append; :188 git error passes; :322, :367, :601 re-plan out-dirs; :80, :424 cents; :469 `filter-log-events` without `--start-time`, `FAILED_EVENTS` not in PASS; :169 no `terraform fmt -recursive` by the human (route to infra); :286 print `EVIDENCE` lines; :601 burn arithmetic (about 0.28, not 1.0); :16, :51-52, :411, :611, :636 D4 literal reading default, B at 20:40 rules out D, reference reviewed B-only commands instead of "rename while pasting".

Checked, no finding: billing arithmetic and drop order; UTC-6 07:15Z; Neptune xlarge A1 -> t4g.medium A2; smoke `query_max_tasks = 1`; A4 var files; E.5 lines; S.8 exports; caps and ceilings (`COST_ESTIMATE.md:137`); digest binds content (`run-experiment.sh:249`); records single-use; masking; no repository write after E.9.
