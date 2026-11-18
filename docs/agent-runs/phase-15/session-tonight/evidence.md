# Phase 15 session 2 ("tonight"): evidence and ledger

Recorded by the orchestrator after the H.4 post-teardown check (S1 ended). Sources are the human's pasted outputs and the logs under `/Users/winnguyen/scholarchain-data/logs/phase15-tn-*` (outside the repository). Account ids are masked. Times are UTC unless marked local (UTC-6).

## Outcome

| Item | Result |
|---|---|
| Session window | A1 apply 2026-11-17T22:34:24Z (16:34 local) to A4 apply start 2026-11-18T02:14Z (20:14 local); hard stop 01:15 local not reached |
| Reload (graph-only, A's export) | SUCCEEDED, 5.2 min, `NEPTUNE_LOAD=LOADED vertices=32225 edges=135788` |
| Haiku smoke | SMOKE1 FAILED (abstained, 0 evidence: question set); SMOKE2 FAILED under the runbook rule (evidence 3 to 8 per request, but all abstained after synthesis; see finding 3) |
| Stub smoke (STUB1, after A3) | PASS: 4/4 status 200, `abstained: false`, evidence 4/3/3/8, every stage ran |
| D | `exp-D-20261118T002157Z-38421c6f` COMPLETED, 2896 s, est. 0.44 USD (ceiling 0.65); query scale-out 1 to 2 (18:43 local) to 3 (18:46), scale-in 3 to 2 (19:03, CPU low) to 1 (19:09), all `Successful` (`collected/app-autoscaling-activities.json`) |
| E | `exp-E-20261118T012347Z-38421c6f` COMPLETED, 673 s, est. 0.08 USD (ceiling 0.45); all three modes, `RAW_LINES=1053`, `GAP_NOTE_TRUE_COUNT=0`; labeled mixed (P15-44 D1 option 2) |
| F (first) | `exp-F-20261118T011431Z-38421c6f` COMPLETED by the harness, 245 s, est. 0.03 USD; INVALID as a recovery result (finding 4) |
| F2 (rerun) | `exp-F-20261118T013740Z-38421c6f` COMPLETED, 246 s, est. 0.03 USD; collected; outcome tally not shown (derive pending) |
| C | `exp-C-20261118T014553Z-38421c6f` COMPLETED, 509 s, est. 0.06 USD (ceiling 0.60); worker back at 1/1 at 20:04 local; collected; outcome tally not shown (derive pending) |
| Teardown (A4, `tn-s4`) | `DEPLOY_COMPLETE`, plan `aefd76b8` |
| Post-teardown (H.4, reduced form) | No active EMR, no running task, query 0/0 ACTIVE, worker 0/0 INACTIVE, no Neptune, only Gateway endpoints (s3, dynamodb), no NAT, EIP, ALB, unattached volume or running instance |

Estimated experiment spend (burn x time, not a bill): 0.44 + 0.08 + 0.03 + 0.03 + 0.06 = 0.64 USD, plus the reload 0.08. Billed cost: PENDING (read from Billing the next day against the section 2 gate: 6.18 + LAG 2.27 + 4.50 HIGH).

## Approval ledger

All records: environment `course`, region `us-east-2`, approved by `winnguyen`, 8 h expiry, single use. Deploy script digest `961ce19b...`, experiment script digest `e3787c42...`.

| Record | Action | Plan digest | Args digest | Cost bound | GIT_SHA | State ack | IAM line |
|---|---|---|---|---|---|---|---|
| tn-s1 (A1) | deploy | `99faef30` | (log) | 20 | 32e401a | no | `IAM DIFF APPROVED tn-s1 ...` (typed) |
| reload | pipeline | n/a | `2a942e58` | 20 (spend 1.70) | n/a | no | none |
| tn-s2 (A2) | deploy | `055b7855` | `b4d3fff9` | 20 | 32e401a | no | `IAM DIFF APPROVED tn-s2 ...` (typed) |
| tn-s2b (A2 re-plan at new HEAD) | deploy | `98a13413` | `9586ec21` | 20 | 38421c6 | no | IAM none |
| tn-s3 (A3, stub) | deploy | `a24588eb` | `9586ec21` | 20 | 38421c6 | no | IAM none |
| d | experiment | n/a | `4513b718` | 0.65 | 38421c6 | no | none |
| f | experiment | n/a | `e35cab71` | 0.25 | 38421c6 | no | none |
| e | experiment | n/a | `9cb63983` | 0.45 | 38421c6 | no | none |
| f2 | experiment | n/a | `e35cab71` | 0.25 | 38421c6 | no | none |
| c | experiment | n/a | `56873bc3` | 0.60 | 38421c6 | no | none |
| tn-s4 (A4 down) | deploy | `aefd76b8` | `9586ec21` | 20 | 38421c6 | yes | D5 line not typed in chat (deviation 9) |

`tn-s2b`, `tn-s3` and `tn-s4` share the args digest (same tag and contexts `a70d0d11...`, equal to A2's); each is bound to its own plan digest. The experiment args digests at `38421c6` equal the evening values (the git SHA is not in the experiment digest; `run-experiment.sh:249`).

Plans: `tn-s2b` 4/3/4, `tn-s3` 1/2/1, `tn-s4` 2/7/31 with IAM 3 in-place narrowings (loader, runtime, worker[0]) and `bulk_load` role and policy destroyed; stateful: Neptune cluster, writer, parameter group destroyed.

## Findings

1. **Auth secrets had no value.** The query service failed to start (`ResourceInitializationError ... ResourceNotFoundException ... AWSCURRENT`) on `scholarchain-course/auth-cursor-secret`. `modules/identity/main.tf:55-68` creates `auth-query-token`, `auth-read-token`, `auth-cursor-secret` with no version by design and points to a "Phase 14 runbook" step that does not exist in the repository. Follow-up: infra-engineer and deploy-engineer (a documented, scripted fill step).
2. **`builtin:windows` questions abstain deterministically.** "Which authors collaborated most ..." names no researcher and no rankable metric; the planner chooses `CollaborationMetrics` with `rankMetric=None` and abstains with `researcher-count` before retrieval (`Workflow.scala:392-413`, `Planner.scala:123-125, 307-330`; rag-engineer read-only diagnosis). Fixed by the human's commit `38421c6` (load-client question wording only).
3. **Haiku synthesis output is rejected.** SMOKE2: graph retrieval returned evidence; every Bedrock synthesis call returned `outcome=ok` (about 5,000 output chars, 7 to 11 s) and the answer was rejected three times per request (1 + 2 repairs), then abstained; the fourth request hit `cap-exceeded maxCalls=10`. Cause not diagnosed (format, citations or truncation suspected). Follow-up: rag-engineer, Phase 16. P15-27 is answered in part: Haiku is called in graph mode and repairs add calls.
4. **F's first run hit `ChangedVectorContent`.** The earlier ingest stored rag-template text under the same `summaryId` keys; F sends raw chunk text, so `textHash` differs and the worker refuses to overwrite (`EmbeddingWorker.scala:402-412`; storage-engineer read-only diagnosis; renderer difference is the leading explanation, not measured from a stored vector). F's good messages went to the DLQ after 3 receives; F (first) is invalid as a recovery result and is kept. It does show the poison, retry and DLQ path and the no-overwrite guarantee.
5. **The load client does not capture `abstentionReason`.** The smoke check passed SMOKE1 on status and counts while every answer abstained; the runbook's `evidence_items > 0` rule caught it by hand. Follow-up: experiment-engineer (capture the reason; add the evidence and abstention conditions to the smoke check).

## Deviations

1. Secrets filled by the human with `put-secret-value` (random 96-hex values, not logged) and the query service force-redeployed, outside the Phase 13 scripts (`phase15-tn-secret-fix.log`).
2. Repository commit `38421c6` during the session (P15-31 says no repo writes during a session); human ruling. Load-client image built and pushed at `38421c6` by hand; services and loader images rebuilt by `deploy.sh` at `38421c6` from the same contexts.
3. A2 re-planned as `tn-s2b` (not in the runbook) to move every task definition to `38421c6`; the pin file was moved to `38421c6` (old pin kept as `.32e401a`, if the copy ran: not shown).
4. Reader R output not shown in the chat for `tn-s2b`, `tn-s3` and `tn-s4`.
5. Order changed from P15-10 (D, E, F, C): A3, D, F, E, F2, C.
6. D's first start failed (`caffeinate` took `AWS_PROFILE=...` as the program; exit 127, record not consumed); restarted at 18:21 local.
7. Experiment digests recomputed into `-2` logs; values equal the evening ones.
8. Human deleted vectors in six batches (`p15-vector-delete/batch-*.json`, index `collaboration-community-v1` and others as listed in the batch files) with `s3vectors delete-vectors`, and purged the embedding DLQ twice (after F, after F2's collector), outside the runbook (`phase15-tn-vector-delete.log`, `phase15-tn-f2-collect.log`). F (first)'s collector run before the first purge was not shown.
9. `tn-s4`: the D5 line was not typed in the chat before the record.
10. C pre-check (keys deleted, sample get-vectors) and F2 and C outcome tallies not shown before the next step.
11. H.4 run in a reduced form (no EMR 24 h list, Step Functions or DLQ depth lines).
12. Earlier, recorded in the summary: 2.3, 3.2, 5.2 read checks; the B-only HEAD gate; the writer-class check; the A2 3-changes read.

## Pending

- Derive (Wave 3 step 1): DONE on the human side (the project guard blocks agent Python; not routed around), outputs only under `results-copy/`, logs `phase15-wave3-derive-{d,e,fc}.log`. D exit 0, 7,428 requests, p50/p95 by level c1 511/931 ms, c8 338/598, c24 508/3,808 (3 errors), c48 3,769/7,990 (14 errors, 0.42%). E exit 0, 1,053 requests by mode. F2 verdict PASS 9 / FAIL 0. F1 not derived (never collected; cite as invalid with `phase15-tn-f-worker-diag.log` and its raw poll and events). C: derived-queue peak backlog 1,804, drain 406 s, about 4.9 msg/s, workers max 4, DLQ max 0; its worker counts (2,000 / 51 / 3) include F2's lines because C's collect window starts 01:35:53Z; C's own (from 01:45:53Z) are 1,950 embedded + 50 already_done + 0 poison. B redacted copy `results-copy/p15-ab-b`. A not derived (no local reports; cite `session-ab/session-record.md`, a limitation). `SHA256SUMS` in every results copy are of the raw files, not the redacted copies.
- H.5 redaction copies: DONE for d, e, f1, f2, c (`results-copy/p15-tn-<label>`, `REDACTION_OK` for all five, `phase15-tn-redact.log`); the paste-back file is still pending.
- Release records (deploy-engineer): `deploy-tn-s1-a1.md`, `deploy-tn-s2-a2.md`, `deploy-tn-s2b`, `deploy-tn-s3-a3.md`, `deploy-tn-s4-down.md`, `smoke-tn.md`.
- Human decisions: RECORDED as P15-45 (C valid: 1,950 done + 50 already_done + 0 poison in C's window; F2 is the F result, F1 labeled invalid; deviation 9 accepted; findings 1 to 5 routed; the deletion removed C's 2,000 keys from three indexes, a limitation) and P15-46 (Wave 3 order).
- Billed cost reconciliation (next day).

## Wave 3 gate results (human runs, 2026-11-18)

- Harness: `RESULT pass=1935 fail=0`, `SKIPPED_CASES 0`, `0.real-repository-untouched PASS` (`phase15-wave3-harness.log`).
- `scripts/check.sh --skip-sbt`: `CHECK_RESULT=PASS_WITH_SKIPS` (terraform validate PASS, constitution-json, hook-syntax, shell-syntax, shellcheck PASS; sbt skipped by design) (`phase15-wave3-check.log`).
- `sbt clean check`: `[success]`, exit 0, 1310 s; last two suite groups `Passed: Total 99, Failed 0` and `Passed: Total 1162, Failed 0` (`phase15-wave3-sbt-clean-check.log`; earlier group totals not shown in the tail).
- Final review: code-reviewer (Opus) review 1 REQUEST CHANGES, re-review 2 APPROVE (`wave-3/review-1.md`, `review-2.md`); repair round 2 (N1-N4, wording only) not re-reviewed.
