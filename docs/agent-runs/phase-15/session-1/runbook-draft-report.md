# Session 1 runbook draft report, deploy-engineer (received 2026-11-17; summary of the report as received; author: deploy-engineer subagent)

Status: COMPLETE (draft only); nothing executed. File: `docs/releases/phase-15/session-1-runbook.md`.

| Step | Content | Expected USD | HIGH USD |
|---|---|---|---|
| 0 | Billing gate (P15-10), clean tree and commit | 0 | 0 |
| 1 | Identity, region, state check (DLQs, EMR clusters, running executions, EC2), raw inputs, manifest and pipeline input copies, digests | 0 | 0 |
| 2.0 | `package.sh`, `upload-inputs.sh` (digest, record, dry run, upload), S3 check against committed scripts | 0 | 0 |
| 2.1-2.5 | Plan baseline-s, read plan, rendered definition check, deploy, live definition check | 0.02 | 0.04 |
| 2.6-2.10 | A records, dry run, run, results, S3 report download, derive, optional collect | 0.55-0.70 | 0.75 |
| 3.1-3.6 | Plan elasticity-s, checks (ComputeLimits 1/3/3/3), deploy, B records | 0.06 | 0.12 |
| 3.7-3.11 | B run, collect, derive, redaction copies | 1.20-1.90 | 2.40 |
| 4 | Plan, deploy and confirm session-down | 0.06 | 0.12 |
| 5 | Redacted paste-back file | 0 | 0 |

Session 1 HIGH 3.45 USD (A 0.75 + B 2.40 + 0.30 idle-endpoint allowance: 8 endpoints x 0.015 USD/h x about 2.3 h). P15-10 passes at billed <= 10.55.

Open items: (1) `deploy.sh` and `run-experiment.sh` need a clean tree; the tree is dirty with Wave 2; if HEAD includes Wave 2 Terraform, plan A shows session-2 resources (a stop in the runbook). (2) Added step 2.0 (package and upload) so the runs use Phase 15 on-node code. (3) Spark profile reaches the node through the state machine (`workflow/main.tf:112,223`), so the profile check is on the rendered or live definition. (4) Plan A's definition may be unknown until apply (new loader task-definition ARN); fallback reads the deployed definition. (5) `b-burst` values in `spark-yarn.conf` still marked PROPOSED for the experiment owner; the human confirms A's 0.75 ceiling. (6) UNVERIFIED: Spark directory, `--snapshot phase5-dblp-subset`, upload limits, some CLI flag forms, macOS `date -v+8H`, `run-record.env` keys, scale-in within the 900 s Wait, credential validity over 75-90 min. (7) Billing lag: confirm Billing includes 2026-11-16 usage.

Gaps for owners: A README has no S3 report download or A collect command (runbook's are UNVERIFIED); no script makes the account-id/output-root manifest copy (runbook uses `sed`); B README redaction list omits `run-record.env`, `entrypoint.log`, `metrics/` (runbook copies the whole run directory).
