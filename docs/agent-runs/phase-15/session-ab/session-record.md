# Phase 15 combined A + B session, record (orchestrator, 2026-11-17; from the human's pasted outputs)

Runbook: `docs/releases/phase-15/session-ab-runbook-draft.md` revision 3 FINAL (runbook review 6 APPROVE). Logs: `/Users/winnguyen/scholarchain-data/logs/phase15-ab-*`. Account IDs are masked in every quoted line. Times UTC.

## Outcome
- A: COMPLETED, pipeline SUCCEEDED, 17-table export published; 2.9 and 2.10 PASS.
- B: NOT RUN in this session (human decision P15-35); moved to a B-only session tonight.
- Teardown: done (5.3R `DEPLOY_COMPLETE`, plan `a08dd0d2...`); post-teardown check clean.
- Heap measure (section 4): pending tonight (4.1 to 4.4).

## Step results
| Step | Result |
|---|---|
| 0.0 | Acknowledgement typed in chat: "P15-30 acknowledged: OD-1, OD-7, OD-8; OD-3 as ratified" |
| 0.1 | `billed=5.33 lag_allowance=1.15 effective=6.48`; `ab_high=3.90 total=10.38 -> GO`; exit 0 |
| 0.2 | `SETTINGS_LOCAL_IGNORED=yes` (`.git/info/exclude:8`), `SETTINGS_LOCAL_TRACKED=no`; exit 0 |
| 0.3 | `WAVE2_HEAD_CHECK=PASS` (HEAD `6cd7341`, six SP-2 tables, P15-23 profile, heap suite in HEAD) |
| 0.4 | Docs commit `5c69ba9` (15 files, `docs/` only); exit 0 |
| 0.5 | State backup `cp exit=0` (`terraform.tfstate`, `.backup`) |
| 0.6 | `No local changes to save` |
| 0.7 | `GATE=PASS`, HEAD `5c69ba9006b9f4a3b83d4e103d6db5710483f4e0`, status empty |
| 1.1 | `profile_region=us-east-2`, 12-digit caller |
| 1.2 | 16:00:30Z, 16:16:48Z, 17:28:19Z: DLQs `0 0`, nothing active |
| 1.3 | Four raw objects (2111266, 940131, 174011, 19675 bytes) |
| 1.4 | `A_RUN=run-v1-b76e2d37-2ac1-40d4-923b-9418d5ff72c7`, `B_RUN=run-v1-730f95f0-ff71-4763-bba6-a0ddbc39e87f`; limits as the runbook table |
| 1.5A | experiment `ARGS_DIGEST=2680af41...`, pipeline `66924f72...`; exits 0 |
| 1.5B | experiment `ARGS_DIGEST=cbf66f79...` (equal to session 1: manifest and scripts unchanged since `085b5ec`; human ruling: expected, records single-use), pipeline `8e9e60cf...` (new); exits 0 |
| 1.6 | `PACKAGE_COMPLETE git_sha=5c69ba9...`; services and loader images built; tree clean after |
| 1.7 | `SP2_ALL_SIX_IN_EXPORTTABLES=yes` (`ExportTables$.class`, `ExportTables.class`) |
| 1.8 | `UPLOAD_PLAN objects=42 bytes=1038131018`; `ARGS_DIGEST=a072c3d8...` |
| 1.8b | `RAW_INPUTS_MATCH_SNAPSHOT=yes` |
| 1.8c | remote keys 10/10/9; `NO_STALE_JARS=yes` |
| 1.9W/D/R | record 16:05:08Z; dry run OK; `UPLOAD_COMPLETE objects=42 bytes=1038131018`, all `VERIFIED sse=AES256` |
| 1.10 | four `MATCH`, 13 profile lines, `B_PROFILE_P15_23_IN_S3=yes`, `JAR_MATCH sparkanalytics_3-0.1.0-SNAPSHOT.jar` (run while 1.9R was still uploading; the checked objects precede the Spark archives in upload order) |
| 2.1 | `11 to add, 7 to change, 3 to destroy`, `IAM_CHANGES: none`, `STATEFUL_CHANGE=no`, `PLAN_DIGEST=e5063c37...`; output-only changes (`role_arns.worker = null`, `egress_mode`, new outputs) |
| 2.2 | `ACTION_LINES created=8 replaced=3 updated=7 destroyed=0`, `PLAN_A_UP_CHECK=PASS`, `DIRTY=no`, `X86_64` |
| 2.3 | not run (expected "unknown until apply"; 2.5 is the check) |
| 2.4P/W/D | deploy `SCRIPT_DIGEST=961ce19b...`, `ARGS_DIGEST=b3014947...`; record 16:12:46Z; dry run OK |
| 2.4R | `S3_CLOCK_START_UTC=2026-11-17T16:13:20Z` (T0); `DEPLOY_COMPLETE`, `DEPLOYED_REVISION=5c69ba9...`, `REVISION_OK` fast, slow, loader |
| 2.5 | A column matched (`IdleTimeout` 900, no Wait, eight timeouts incl. two 4800, `a-baseline`) |
| 2.6W/D | records 16:17:07Z; both dry runs `APPROVAL_VERIFIED`, part exits 0 |
| 2.7 | `RUN_ID=exp-A-20261117T161757Z-5c69ba90`, `ENTRY_EXIT=0 FINAL_STATUS=COMPLETED elapsed_s=3682`, `estimated_spend_usd=0.61` (ceiling 0.75); exit 0 |
| 2.8b | pipeline `FINAL_STATUS=SUCCEEDED`, 16:18:00Z to 17:19:19Z, 61.3 min, `RECORD_COMPLETE=yes` both records; `NO_ACTIVE_CLUSTER` line not in the 20 events shown; EMR active list empty (human's check) |
| 2.8c/d/e | not run (do not block B; pending) |
| 2.9 | `sourceRunId=run:v1:3cfb57b1-abd1-4913-8e53-da477d8d6027`, `tables_in_manifest=17`; `session2.tfvars` written (two lines) |
| 2.10 | listing 34 objects, 373,888,058 bytes; size and rows computed by another Claude session from the saved listing and manifest (human-reported): nine loaded + root 100.9 MiB (limit 128 MiB), max rows 101,478, nine loaded rows 140,022; `EXPORT_SIZE_CHECK` and `EXPORT_ROWS_CHECK` PASS. Finding: `path-evidence` 42 rows |
| 3.1 | `0 to add, 1 to change, 0 to destroy`, `IAM_CHANGES: none`, `PLAN_DIGEST=6e494fbf...` |
| 3.2 | not shown before 3.4R (deviation; 3.1 summary already showed 0/1/0, IAM none, `STATEFUL_CHANGE=no`) |
| 3.4P/W/D/R | record 17:26:40Z; dry run OK; `DEPLOY_COMPLETE` plan `6e494fbf...`, `IMAGE_EXISTS` both |
| 3.5 | B column matched (`ComputeLimits` 1/3/3/3 Instances, `IdleTimeout` 1800, one Wait 900, `b-burst`, timeouts incl. two 4200) |
| 3.6W/D | records 17:28:48Z (pipeline `8e9e60cf...`, experiment `cbf66f79...`); dry runs OK. Unused: B not started (P15-35); records expire 2026-11-18T01:28:48Z |
| 5.1 | `0 to add, 1 to change, 8 to destroy`, `IAM_CHANGES: none`, `STATEFUL_CHANGE=no`, `PLAN_DIGEST=a08dd0d2...` |
| 5.2 | not shown before 5.3R (deviation; 5.1 summary already showed 0/1/8, IAM none) |
| 5.3P/W/D/R | record 17:33:44Z; dry run OK; apply log `course-20261117T173358Z.apply.log`; `DEPLOY_COMPLETE` plan `a08dd0d2...`, `DEPLOYED_REVISION=5c69ba9...` |
| 5.4 | short form (human): no active EMR cluster; fast and slow 0/0; only Gateway endpoints (s3, dynamodb); no running EC2, NAT, EIP, LB, Neptune. Not checked: unattached EBS, 24 h cluster list |
| 4.1 | `download exit=0`, `local files=34 bytes=373888058` equal to the S3 listing, `DOWNLOAD_MATCHES_S3_LISTING=yes` (`rehearsal/a-export`) |
| 5.5 | no `p15-ab-leftover` stash (0.6 saved nothing); tree clean. Run before 4.1 to 4.4 (harmless: nothing to pop) |

Billable window (endpoints): 2.4R at 16:13:20Z to 5.3R apply about 17:34Z to 17:36Z.

## Other evidence
- Bedrock (P15-13): `get-foundation-model-availability` for `anthropic.claude-haiku-4-5-20251001-v1:0` in us-east-2: logs `-3` and `-4` `agreementAvailability NOT_AVAILABLE`; log `-5` `AVAILABLE`, `AUTHORIZED`, entitlement and region `AVAILABLE`. Marketplace agreement created 16:52Z (AWS email). Playground call 10:55 local answered (8 input, 12 output tokens, 389 ms): a small Bedrock charge outside this session's HIGH.
- Approval reuse check (read-only, `scripts/lib.sh`): records are single-use (`:428-449`, `:542`), bound to `GIT_SHA` = HEAD (`:514`) and at most 24 h (`:533-537`); `SCHOLARCHAIN_OPS_STATE_DIR` pointed at an empty directory would bypass the consumed check (`:26-33`).
- A manual `aws s3 cp` of the export manifest (human, outside the runbook) printed the bucket name with the real account ID on screen; not in any repository file.

## Deviations
1. 2.3 not run. 2. 3.2 and 5.2 outputs not shown before their applies. 3. 1.10 run during 1.9R. 4. 2.10 size and rows computed outside this session (human-reported). 5. 5.4 short form. 6. 5.5 before 4.1 to 4.4. 7. Gate change for 2.10 (P15-34). 8. B not run (P15-35).

## Open
4.1 to 4.4 tonight (4.4 read per P15-36); 2.8c, 2.8d, 2.8e; 3.2 and 5.2 for the record; 5.4 EBS and 24 h list; `path-evidence` 42 rows; B-only session; actual billed cost.
