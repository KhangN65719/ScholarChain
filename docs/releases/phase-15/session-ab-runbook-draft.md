# Phase 15 combined A + B session runbook (DRAFT, revision 1, nothing executed)

Author: deploy-engineer. Status: DRAFT for independent read-only review, then the human. Basis: `session-1-runbook.md` (revision 3, approved; structure, stash and clean-tree procedure, approval records, digests, timeouts, redaction and teardown checks are reused), `session-1b-a-only-draft.md` (A numbers), `docs/agent-runs/phase-15/decisions.md` (P15-10, P15-11, P15-16, P15-20 to P15-29), `docs/agent-runs/phase-15/session-1/session-record.md` and `b-diagnosis-hadoop-engineer.md` (B failure and the P15-23 profile), `runbook-review-2.md` (its six minor notes, applied, see the table below), `infra/terraform/SESSION2.md` (sections 6a and 8), `scripts/emr/spark-yarn.conf`, `experiments/a-baseline` and `experiments/b-burst` (READMEs and `run.sh`).

I ran nothing: no AWS call, no Terraform, no script, no git write, no sbt. Every command below is a `!` paste for the human. Nothing here is an approval: the human writes each approval record by running the W command of that action, after reading the plan or arguments it names. Statements read from a file are cited; a statement no run has confirmed is marked UNVERIFIED. `scripts/emr/README.md` does not exist (the emr directory holds only scripts and `spark-yarn.conf`); I relied on the `scripts/README.md` citations of the approved runbook and did not re-read the scripts, except two reads for revision 1: `scripts/emr/run-s-pipeline.sh` lines 85 and 580 and `infra/terraform/modules/workflow/main.tf` lines 100 to 112.

Revision 1 applies `docs/agent-runs/phase-15/session-ab/runbook-review-1.md` (B1, M1 to M3, minors 1 to 7), `docs/agent-runs/phase-15/wave-2/review-2-verdict.md` (MAJOR-1), `docs/agent-runs/phase-15/wave-2/open-decisions.md` (OD-1, OD-2, OD-3, OD-7; each marked "human to confirm" there stays a human confirmation here), `docs/agent-runs/phase-15/wave-2/reports/w2rb4-infra-engineer.md` (gating and expected plans), `w2rb2-storage-engineer.md` (heap command) and the updated `infra/terraform/SESSION2.md` (sections 5a and 6a). Section by section: M1 and B1 in the table below, 0.0, 2.1, 2.2, 2.4R, 3.1, 3.2, 3.4R, 5.1 to 5.4; M2 in 1.7; M3 in 4.2 and 4.3; MAJOR-1 in 2.9; minor 1 in 2.10; minor 2 in 4.1; minor 3 in the order note, S1 and section 4; minor 4 in 0.1; minor 5 in the conventions and every watch command; minor 6 in 5.4; minor 7 in 2.7 and 3.7. The expected plans are read from the Terraform code by infra-engineer, whose report is UNVALIDATED (no `terraform fmt`, `validate` or `plan` was run): the first real plan (2.1) is their first test.

Scope: one session, one same-day session-down. A runs first on one primary node and produces the 17-table export. B reruns with the P15-23 profile, goal scale-in. Then a read-only heap measure on A's export. Account and Region: profile `scholarchain`, `us-east-2` only. Never use "Activate advanced features" and never change the spend limit.

## What differs from approved session 1 (revision 3)

| Topic | Session 1 (revision 3) | This session |
|---|---|---|
| Code pin | HEAD equals 085b5ec outside `docs/`; Wave 2 parked in a stash | Wave 2 is committed (SP-2 Spark change and the P15-23 profile are in the uploaded jar and script). Gate: HEAD equals the Wave 2 commit outside `docs/`. The stash only parks leftovers |
| Experiments | B only | A first (`baseline-s`), then B (`elasticity-s`), two applies, one session-down |
| B profile | `PARTITION_COUNT=16`, `SPLIT_MAX_BYTES=131072`, MAP/REDUCE 3072 (P15-21), failed at the 1,500 s Hadoop limit | `SPLIT_MAX_BYTES=262144`, `PARTITION_COUNT=8`, `MAP_MEMORY_MB=2048`, `REDUCE_MEMORY_MB=3072`; Hadoop limit 1,500 s unchanged (P15-23) |
| Plan content | Up: 11 add, 3 change, 3 destroy, `IAM_CHANGES: none` | A-up: 11 add, 7 change, 3 destroy. A to B: 0 add, 1 change, 0 destroy. B to down: 0 add, 1 change, 8 destroy. `IAM_CHANGES: none` in all three. The up plan differs from session 1 by four in-place log-group retention changes, 3 to 7 days, that need the human's acknowledgement (0.0). No Wave 2 resource is created (OD-1: `enable_services_stack`, `enable_worker_role` and `enable_scaling_dashboard` default false, `service_layout` default `split`). Source: `w2rb4-infra-engineer.md` and `SESSION2.md` section 5a, read from code and UNVERIFIED until the first plan |
| Teardown var files | `course` + `session-down` | `course` + `session-down` only. `session-down-services.tfvars` is for session 2's A4 and sets the stack flag on: here it would plan to create the services stack, so it is never used |
| Names | `phase15-s1-*`, `p15-s1-*` | `phase15-ab-*`, `p15-ab-*` (new manifest copies, inputs, run ids, plans, records: nothing from session 1 is reused or overwritten) |
| New outputs | none | `session2.tfvars` (outside the repository), A export size check, A export download and the heap measure (P15-29) |
| Sbt | none except `package.sh` | the human's own heap measure in section 4 (still no agent sbt) |

Review-2 minor notes (`runbook-review-2.md`), applied: 1 and 2 (stale-jar check) are the two-command remote listing and `LC_ALL=C` comparison of 1.8c; 3 is the exactly-one-cluster guard of E1 terminate in S4; 4 is the redaction `sed` on the 1.8c, 1.10, 2.8, 3.8 and 5.4 outputs; 5 is gate 0.7 (whole tree outside `docs/`, branch checked); 6 is the single statement of the profile precondition in 0.3 before the docs commit.

## Order and step index

| Step | What | Billable |
|---|---|---|
| 0.0 to 0.7 | acknowledgements in the chat, billing gate, risk check, Wave 2 HEAD check, docs-only commit, state backup, stash, hard gate | no |
| 1.1 to 1.10 | identity, state check, raw listing, inputs, A and B digests, package, SP-2 jar check, upload and its checks | S3 requests only |
| 2.1 to 2.5 | A plan, read, definition check, deploy (clock T0 starts at 2.4R), live definition check | from 2.4R |
| 2.6 to 2.11 | A records, A run, A results, A collector, slug and `session2.tfvars`, 17-table size check, gate to B | A run |
| 3.1 to 3.9 | B switch plan, read, definition check, deploy, live check, records, B run, results, collector, derive | B run |
| 4.1 to 4.4 | download A's export, heap measure, sizing rule, tree check | no (4.1: transfer only) |
| 5.1 to 5.5 | session-down plan, read, deploy, post-teardown check, stash pop | ends billing |
| 6.1 to 6.4 | redaction copies, paste-back, evidence | no |

Order (review-1 minor 3): section 4 needs no session resources. 4.1 may run in a second terminal while B runs, or after 5.4. 4.2 to 4.4 ALWAYS run after 5.4 and before 5.5, never before the session-down: the sbt run then cannot dirty the tree before 5.3R and cannot delay the teardown. The run order is 0, 1, 2, 3, 5.1 to 5.4, then 4.2 to 4.4, then 5.5.

## Wall-clock limit and HIGH for A + B together

Session clock: T0 is the start of 2.4R (the A session-up apply). Expected timeline from T0 (my arithmetic, UNVERIFIED durations from sessions 1 and the A-only draft):

| Segment | Duration | Ends at |
|---|---|---|
| A apply and image push | 20 min | T0 + 0:20 |
| A live check, records, dry runs | 30 min | T0 + 0:50 (A must start by T0 + 1:00) |
| A run, cap 75 min (pipeline stop 70 min) | 75 min | T0 + 2:05 (latest T0 + 2:15) |
| A results, then B switch plan, read, records, apply (images exist), live check, dry runs | 45 min | T0 + 2:50 (B must start by T0 + 3:15) |
| B run, cap 90 min (pipeline stop 88 min), up to 5 min teardown check | 95 min | T0 + 4:25 (latest T0 + 4:50) |
| Session-down plan, read, records | 25 min | latest T0 + 5:15 |
| Session-down apply (5.3R) | | must start by T0 + 6:00 |
| 5.4, then 4.2 to 4.4 | after 5.3R | outside the session clock: no session resource is up |

Limits (S3): A (2.7) starts no later than T0 + 1 h; B (3.7) starts no later than T0 + 3 h 15 min; the session-down apply (5.3R) starts no later than T0 + 6 h. Past a limit, go to step 5 without running the next experiment. A run in progress is never interrupted by hand: the script caps end it. Each hour beyond 6 h costs about 0.12 USD more (HIGH).

HIGH (USD; rates UNVERIFIED, `COST_ESTIMATE.md`; 8 interface endpoints x 0.015 = 0.12 USD/h):

| Item | USD | Arithmetic |
|---|---|---|
| A entry window | 0.75 | manifest `SPEND_CEILING_USD`; 0.60 USD/h x 75 min / 60 |
| A cluster time after A's 70-minute stop | 0.05 | 0.60 x 300 s / 3600 (`run-bounded-pipeline.sh` teardown check up to 300 s) |
| B entry window | 2.40 | manifest ceiling; 1.55 USD/h x 90 min / 60 = 2.325, rounded to the ceiling |
| B cluster time after B's 88-minute stop (P15-20) | 0.13 | 1.55 x 300 / 3600 = 0.129 |
| Idle endpoints outside the two run windows | 0.39 | 0.12 USD/h x (6.0 h - 1.25 h - 1.5 h) = 0.12 x 3.25 (the burn constants already contain the endpoints inside a window) |
| A export download to the local disk (4.1) | 0.10 | allowance: about 0.5 GB out at about 0.09 USD/GB (UNVERIFIED) = 0.045, rounded up |
| Itemized total | 3.82 | 0.75 + 0.05 + 2.40 + 0.13 + 0.39 + 0.10 |
| Sum of the two approved single-session HIGHs | 3.90 | A-only 1.07 (`session-1b-a-only-draft.md`: 0.75 + 0.05 + 0.27) plus B-only 2.83 (`session-1-runbook.md`: 2.40 + 0.13 + 0.30); this double-counts idle endpoint hours (4.75 h against 3.25 h) and is the larger |
| **Session HIGH used for the gate** | **3.90** | the sum, 0.08 above the itemized total, so the gate is the conservative one |
| Plan figure (ceilings only) | 3.15 | 0.75 + 2.40 (P15-16: "session 1 HIGH about 3.15") |

Revision 1 leaves the HIGH at 3.90: infra's gating adds no resource (`COST_ESTIMATE.md` gating paragraph: A and B add 0.00 USD, as quoted in `w2rb4-infra-engineer.md`), the four retention changes are free, and moving 4.2 to 4.4 after the teardown only removes endpoint hours (the 0.39 idle row stays as the conservative figure).

P15-10 for this session: the effective billed figure (the Billing reading plus the OD-7 allowance below) plus 3.90 must be at most 14 USD, so the effective figure must be at most 10.10 (10.85 on the plan figure). OD-7 (human to confirm): while Billing has not caught up with session 1, a fixed allowance of 1.15 USD is added to the reading. Session 1 ended with a billed 3.94 before it ran plus the failed B run (estimated 1.01 USD of cluster time and about 1.1 h of endpoints, 1.01 + 0.13 = 1.14, my arithmetic, rounded up to the OD-7 figure 1.15); the real figure is the human's reading of Billing. With the allowance the Billing reading itself must be at most 8.95 (9.70 on the plan figure); with no lag (allowance 0) 10.10 and 10.85. Carry-forward for session 2: the rule applies again before it (HIGH 4.5 in `SESSION2.md` section 9, 4.3 in the plan), so after this session billed must be at most 9.50 (9.70 on the plan figure).

Expected cost per step (not a bill):

| Step | What bills | Expected | HIGH |
|---|---|---|---|
| 0, 1 | nothing (reads, local files, S3 PUT of about 1 GB) | 0 | 0 |
| 2.1 to 2.6 | endpoints from the apply, about 50 min | 0.10 | 0.15 |
| 2.7 A run | one primary node, about 58 to 70 min | 0.55 to 0.70 | 0.80 |
| 3.1 to 3.6 | endpoints, about 45 min | 0.09 | 0.12 |
| 3.7 B run | 1 to 4 instances, about 72 to 88 min plus up to 300 s | 1.20 to 1.90 | 2.53 |
| 5 | endpoints idle until 5.3R completes (4.2 to 4.4 follow 5.4 and bill nothing; 4.1 is the transfer row above) | 0.12 | 0.45 |
| Reserve to the 6 h limit | endpoints idle | 0 | about 0.10 |
| **Total** | | **about 2.1 to 3.0** | **3.90 gate** |

## Read this first

Conventions in every command (as in session 1):
- Each command is self-contained: it sets its own variables and relies on no shell state of an earlier `!` line. Files written by earlier steps (logs, run-directory files) are read by name.
- `set -o pipefail;` first, output `tee`'d to `/Users/winnguyen/scholarchain-data/logs/phase15-ab-<step>.log`, ending `| tail -n 60; echo "exit: $?"` (revision 1: every command, including the read-only watch commands of 2.7 and 3.7, which use `tee -a`; where a long output is cut by the 60 lines, the head is in the log). Only braced variables (zsh-safe). No `!` inside double quotes.
- The account id is never typed. A command reads it with `ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"`. The scripts compare it with the caller identity and the approval record.
- Redaction. Output that can carry the account id (ARNs, bucket names) passes `sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g"` before `tail`; the `tee` sits before the `sed`, so the unredacted log stays only under `/Users/winnguyen/scholarchain-data/logs/` (keep those logs local).
- Exit lines. In the two-part commands (1.5A and 1.5B digests, 2.6 and 3.6 dry runs) the last `exit: 0` is the status of the last pipeline; the verdict is the `part1 exit:` and `part2 exit:` lines, both 0.
- Noclobber. A file written with `set -C` that exists fails with `file exists` (zsh) or `cannot overwrite existing file` (bash); either means the file is already there.
- `SCD=/Users/winnguyen/scholarchain-data`, `ENVDIR=/Users/winnguyen/ScholarChain/infra/terraform/environments/course`. Name prefix `scholarchain-course`.
- The record writer uses macOS `date -u -v+8H` (UNVERIFIED on this machine; if it fails the W command writes nothing).
- A placeholder in capitals (`REPLACE_WITH_...`) or in angle brackets is OPEN: replace it before pasting. A command pasted with an unreplaced `REPLACE_WITH_...` fails closed (the command checks the value) and one with an angle-bracket placeholder is not a command at all.

Prerequisites the scripts impose (unchanged from session 1): a clean tree on `main` (`lib.sh` `tree_status` counts untracked files; `deploy.sh` and `run-experiment.sh` refuse a dirty tree, including the session-down); Terraform state is local to this checkout (ignored `terraform.tfstate*`), so the session runs in this checkout; `deploy.sh` needs `--services-context` and `--loader-context` holding the output of `package.sh`, so 1.7 precedes every deploy.

### Approval record, exact fields

Every approval-gated script reads one record: a plain `KEY=VALUE` file, no spaces in values, every key once, 14 keys: `APPROVAL_VERSION ACTION ENVIRONMENT ACCOUNT_ID REGION COST_CEILING_USD PLAN_DIGEST SCRIPT_DIGEST ARGS_DIGEST GIT_SHA STATEFUL_CHANGE_ACKNOWLEDGED APPROVED_BY APPROVED_AT EXPIRES_AT`.

| Action | `ACTION` | `PLAN_DIGEST` | `GIT_SHA` | `COST_CEILING_USD` (equals `--cost-ceiling-usd`) |
|---|---|---|---|---|
| upload inputs | `upload` | `n/a` | `n/a` | 20 |
| deploy (up, switch, down) | `deploy` | `PLAN_DIGEST=` printed by `plan.sh` | HEAD, 40 hex | 20 |
| pipeline | `pipeline` | `n/a` | `n/a` | 20 |
| experiment | `experiment` | `n/a` | HEAD, 40 hex | A 0.75, B 2.40 |

`ENVIRONMENT=course`, `REGION=us-east-2`, `STATEFUL_CHANGE_ACKNOWLEDGED=no` unless `plan.sh` printed `STATEFUL_CHANGE=yes` (none is expected: then stop). `APPROVED_BY` must not look like an agent or role (the W commands write `winnguyen`). `EXPIRES_AT` is 8 hours after `APPROVED_AT` (maximum 24 h). A record is single use: a failed run consumes it. Any edit to a script or `lib.sh` after the digest is printed voids the record. Each action has up to four commands: P prints the digests, W writes the record, D is a `--dry-run` (consumes nothing), R is the real run.

### Rendered values to expect (checked at 2.3, 2.5, 3.3, 3.5)

The profile is not in the execution input; it reaches the node as the sixth step argument the state machine adds when `pipeline_profile` is non-empty (`workflow/main.tf` lines 112 and 223). So the check is on the rendered or deployed state machine definition.

| Value in the definition | A (`baseline-s`) | B (`elasticity-s`) |
|---|---|---|
| `ComputeLimits` | none (core 0, no `ManagedScalingPolicy`) | exactly one: `MaximumCapacityUnits` 3, `MaximumCoreCapacityUnits` 3, `MaximumOnDemandCapacityUnits` 3, `MinimumCapacityUnits` 1, `UnitType` `Instances` |
| `IdleTimeout` | 900 | 1800 |
| Wait state | none | one (`WaitBeforeTerminate`), `Seconds` 900 |
| `TimeoutSeconds` values (eight, compare as a set) | 7200, 1440, 300, 4800, 4800, 900, 600, 900 | 7200, 1440, 300, 4200, 4200, 900, 600, 900 |
| `AfterTerminate` state | absent | absent |
| `RunLoader` state | present | present |
| profile string | `a-baseline` present, `b-burst` absent | `b-burst` present, `a-baseline` absent |

Any difference is a stop. These values are those of the approved session 1 runbook, which also matched the live definition of session 1 (`session-record.md`, step 3.5). Read for revision 1: `modules/workflow/main.tf` lines 104 to 108 say that with `skip_loader_after_pipeline` the `AfterTerminate` state is not in the definition and `RunLoader` stays (reached only in mode `loader-only`), which matches the table; `SESSION2.md` section 5a calls the A-up definition "no loader state", which I read as loose wording for `AfterTerminate` (UNVERIFIED: if the live A definition really lacks `RunLoader`, 2.5 stops and the human decides, a false alarm of the table and not of the deploy). OPEN PLACEHOLDER (narrowed): infra-engineer or the orchestrator confirms that the Wave 2 commit left the rest of the table (the eight `TimeoutSeconds`, `IdleTimeout`, the Wait) unchanged.

## Session rules (apply from the end of 0.7 until 5.5 is done)

S1. No repository writes and no agent sbt. From the end of 0.7 until 5.5 the orchestrator and every agent make no repository write (no evidence file, no edit, no commit) and run no sbt. The only exceptions are the human's own `package.sh` run (1.6) and the human's own heap measure (4.2); the human checks the tree is clean again after each (1.6 states it; 4.4 checks it before 5.5). The heap measure runs after 5.4, so it can never dirty the tree before 5.3R (review-1 minor 3). A dirty tree makes `deploy.sh` refuse, including the session-down at 5.3R, which would leave the endpoints billing. Evidence goes to `/Users/winnguyen/scholarchain-data/logs/` or the chat and is recorded in the repository only after 5.5. The docs the session needs (this runbook, wave evidence) are committed in 0.4; do not edit them afterwards.

S2. Any stop from the A session-up apply onward (2.4R, and 3.4R, and later) means "go to step 5". From 2.4R the endpoints bill about 0.12 USD/h. A stop that says "report" from that point also means: report, then go to step 5 (session-down) unless the human decides otherwise within 15 minutes, in writing in the chat, with a stated end time. Stops before 2.4R (steps 0 to 2.3 and the S3 uploads) change nothing billable, so they mean report and wait; if 0.6 has already run and the session is abandoned, finish with 5.5.

S3. Session wall-clock limit (section above). T0 is the start of 2.4R. A starts by T0 + 1 h, B by T0 + 3 h 15 min, 5.3R by T0 + 6 h. Past a limit, go to step 5.

S4. Emergency action, a leftover EMR cluster. Console mutation is prohibited. If a command prints `EMR cluster still active after the run`, or the 1.2 state check shows an active `scholarchain-course-pipeline` cluster when none should run, the human runs these two commands in order and the orchestrator records a human emergency action (log, UTC time, cluster id, reason).

E1 lookup (read-only):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; AWS_PROFILE=scholarchain aws emr list-clusters --active --region us-east-2 --query "Clusters[?Name=='scholarchain-course-pipeline'].[Id,Status.State]" --output text 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-emergency-lookup-${STAMP}.log | tail -n 60; echo "exit: $?"
```

E1 terminate (terminates only when exactly one active cluster of that name exists and its id looks like `j-...`; otherwise nothing is terminated and the exit is non-zero, and the human decides by hand):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; IDS="$(AWS_PROFILE=scholarchain aws emr list-clusters --active --region us-east-2 --query "Clusters[?Name=='scholarchain-course-pipeline'].Id" --output text)"; N="$(printf '%s' "${IDS}" | wc -w | tr -d ' ')"; if [[ "${N}" == "1" && "${IDS}" =~ ^j-[A-Z0-9]+$ ]]; then AWS_PROFILE=scholarchain aws emr terminate-clusters --cluster-ids "${IDS}" --region us-east-2; echo "terminate requested for ${IDS} at ${STAMP}"; else echo "found ${N} active cluster(s) named scholarchain-course-pipeline: nothing terminated (exactly one is required)"; false; fi 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-emergency-terminate-${STAMP}.log | tail -n 60; echo "exit: $?"
```

UNVERIFIED: `aws emr terminate-clusters --cluster-ids` is the current CLI verb from my knowledge; the repository's scripts use `terminate-job-flows --job-flow-ids`. If `terminate-clusters` is rejected as unknown, swap in the repository's form on the same line. Afterwards E1 lookup must print nothing.

S5. No persistent permission prompts. A persistent "always allow" writes `.claude/settings.local.json` (a repository path); a write there is a repository write (S1). The 0.2 check shows whether the file is ignored.

---

## Step 0. Preconditions

### 0.0 Human acknowledgements in the chat (no command; in writing, before 0.1)

1. The A-up plan will show 7 changes, not session 1's 3: four log groups (runtime `fast`, `slow`, `loader` and the workflow pipeline group) change retention from 3 to 7 days in place. This is free, deletes no log event (retention only rises) and is not IAM, but it differs from the shape session 1 approved. Source: `w2rb4-infra-engineer.md` and `SESSION2.md` section 5a (the alternative, an A and B overlay pinning retention 3, was not made because the validation `log_retention_days >= 7` states a human requirement). No acknowledgement, no 2.4W.
2. Confirmations the runbook is written on (`open-decisions.md`, each "human to confirm: yes"): OD-1 (A and B create no Wave 2 resource), OD-3 (the 400/800 MiB heap rule is not yet ratified, so memory stays 4096 MiB, see 4.3) and OD-7 (the 1.15 USD billing-lag allowance of 0.1). The human states "confirmed" or names the change. OD-2 (load-client image tag equals the commit SHA) concerns session 2 only (2.9).

### 0.1 Billing gate (P15-10)

Read the month-to-date billed total in the AWS Billing console (gross, before credits). Replace `REPLACE_WITH_BILLED_TOTAL` with the plain number and paste. OD-7 (human to confirm): `LAG_USD=1.15` is a fixed allowance for session 1's usage that Billing may not show yet (billing can lag, UNVERIFIED). Leave it at 1.15 unless the human states in the chat that the Billing reading already includes all of session 1's usage, in which case change it to `LAG_USD=0` before pasting; the command accepts no other value. Do not change the Region or the spend limit and do not use "Activate advanced features".

```
set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs; BILLED_USD=REPLACE_WITH_BILLED_TOTAL; LAG_USD=1.15; if [[ "${BILLED_USD}" =~ ^[0-9]+(\.[0-9]+)?$ ]] && [[ "${LAG_USD}" == "1.15" || "${LAG_USD}" == "0" ]]; then awk -v b="${BILLED_USD}" -v l="${LAG_USD}" 'BEGIN { e = b + l; h1 = 3.90; h0 = 3.15; printf "P15-10 billed=%.2f lag_allowance=%.2f effective=%.2f limit=14.00\n  ab_high=%.2f total=%.2f -> %s\n  plan_high=%.2f total=%.2f -> %s\n", b, l, e, h1, e + h1, (e + h1 <= 14 ? "GO" : "STOP"), h0, e + h0, (e + h0 <= 14 ? "GO" : "STOP"); exit (e + h1 <= 14 ? 0 : 3) }'; else echo "BILLED_USD is not a plain number, or LAG_USD is neither 1.15 nor 0: STOP"; false; fi 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-billing-gate.log | tail -n 60; echo "exit: $?"
```

Expected: `lag_allowance=1.15` (or 0 if the human said so), `-> GO` on the `ab_high` line and `exit: 0` (3.90 is the gate constant, 3.15 the plan figure; `effective` is the reading plus the allowance and the 14 USD limit applies to it). Stop: `STOP`, `exit: 3` or a non-number. Then nothing below runs; report. Nothing needs restoring yet.

### 0.2 Risk check: the local settings file

A persistent permission prompt writes `.claude/settings.local.json`; if it is tracked, or untracked and not ignored, the write dirties the tree and `deploy.sh` refuses, including the session-down. This prints whether it is ignored and tracked, and lists uncommitted changes under `infra`, `scripts`, `experiments` and `modules` (information: after the Wave 2 commit it should be empty; 0.3 and 0.7 decide).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs; ( echo "--check-ignore"; git check-ignore -v .claude/settings.local.json; if git check-ignore -q .claude/settings.local.json; then echo "SETTINGS_LOCAL_IGNORED=yes"; else echo "SETTINGS_LOCAL_IGNORED=NO: STOP and tell the human"; fi; echo "--ls-files (expect empty)"; T="$(git ls-files .claude/settings.local.json)"; echo "${T}"; if [[ -n "${T}" ]]; then echo "SETTINGS_LOCAL_TRACKED=YES: STOP and tell the human"; else echo "SETTINGS_LOCAL_TRACKED=no"; fi; echo "--uncommitted changes under infra scripts experiments modules (information; expect none after the Wave 2 commit)"; git status --short -- infra scripts experiments modules | head -n 20; [[ -z "${T}" ]] && git check-ignore -q .claude/settings.local.json ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-risk-checks.log | tail -n 60; echo "exit: $?"
```

Expected: a `.gitignore` or `info/exclude` rule line, `SETTINGS_LOCAL_IGNORED=yes`, an empty ls-files section, `SETTINGS_LOCAL_TRACKED=no`, `exit: 0`. Stop: tracked, or not ignored. Rule S5 applies in every case.

### 0.3 Wave 2 is committed: the HEAD check (before the docs commit)

Stated preconditions, not commands (review-2 note 6, said once here): (a) the human has committed Wave 2, including the SP-2 Spark change (six analytics tables in `ExportTables.all`, P15-26), the P15-23 `b-burst` profile lines of `scripts/emr/spark-yarn.conf`, and the Wave 2 Terraform; (b) the orchestrator has copied this runbook to `docs/agent-runs/phase-15/session-ab/human-commands.md`; (c) the human has the full 40-hex SHA of the Wave 2 commit. OPEN PLACEHOLDER: `REPLACE_WITH_WAVE2_COMMIT_SHA`, filled by the human or orchestrator after the commit. No commit, no session: the jar uploaded in step 1 is built from HEAD.

The check proves, from the committed tree and not the working tree: the Wave 2 commit is HEAD or an ancestor; all six SP-2 table names are in `JsonlExport.scala`; the four P15-23 profile lines are exactly `PARTITION_COUNT=8`, `SPLIT_MAX_BYTES=262144`, `MAP_MEMORY_MB=2048`, `REDUCE_MEMORY_MB=3072`; and the heap-measure suite file exists in HEAD (it is needed in 4.2).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs; W2=REPLACE_WITH_WAVE2_COMMIT_SHA; ( echo "HEAD=$(git rev-parse HEAD)"; BR="$(git symbolic-ref --short -q HEAD)"; echo "BRANCH=${BR:-DETACHED}"; echo "LAST_COMMIT=$(git log --oneline -1)"; if [[ "${W2}" =~ ^[0-9a-f]{40}$ ]]; then git merge-base --is-ancestor "${W2}" HEAD; ANC=$?; else echo "WAVE2 SHA PLACEHOLDER NOT REPLACED"; ANC=9; fi; echo "WAVE2_IS_ANCESTOR_OF_HEAD_EXIT=${ANC} (0 = yes)"; SP2=0; for T in path-evidence components component-membership communities community-membership community-cross-edges; do C="$(git show HEAD:modules/spark/src/main/scala/scholarchain/spark/JsonlExport.scala | grep -c "\"${T}\"")"; if [[ "${C}" -ge 1 ]]; then echo "SP2_TABLE_IN_HEAD ${T}"; else echo "SP2_TABLE_MISSING ${T}"; SP2=1; fi; done; P="$(git show HEAD:scripts/emr/spark-yarn.conf | grep -E '^b-burst\.(PARTITION_COUNT|SPLIT_MAX_BYTES|MAP_MEMORY_MB|REDUCE_MEMORY_MB)=' | LC_ALL=C sort | tr '\n' ' ')"; echo "B_PROFILE_IN_HEAD=${P}"; PR=1; if [[ "${P}" == "b-burst.MAP_MEMORY_MB=2048 b-burst.PARTITION_COUNT=8 b-burst.REDUCE_MEMORY_MB=3072 b-burst.SPLIT_MAX_BYTES=262144 " ]]; then PR=0; fi; git cat-file -e HEAD:modules/storage/src/test/scala/scholarchain/storage/ExportHeapMeasureSuite.scala; HS=$?; echo "HEAP_SUITE_IN_HEAD_EXIT=${HS} (0 = yes)"; if [[ "${ANC}" == "0" && "${SP2}" == "0" && "${PR}" == "0" && "${HS}" == "0" && "${BR}" == "main" ]]; then echo "WAVE2_HEAD_CHECK=PASS"; else echo "WAVE2_HEAD_CHECK=FAIL: STOP"; false; fi ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-wave2-head.log | tail -n 60; echo "exit: $?"
```

Expected: `WAVE2_IS_ANCESTOR_OF_HEAD_EXIT=0`, six `SP2_TABLE_IN_HEAD` lines, `B_PROFILE_IN_HEAD=b-burst.MAP_MEMORY_MB=2048 b-burst.PARTITION_COUNT=8 b-burst.REDUCE_MEMORY_MB=3072 b-burst.SPLIT_MAX_BYTES=262144 `, `HEAP_SUITE_IN_HEAD_EXIT=0`, `BRANCH=main`, `WAVE2_HEAD_CHECK=PASS`, `exit: 0`. Stop: `FAIL`. Report; the commit is missing a piece (never edit files in this session).

### 0.4 Docs-only commit

The two docs paths are the only ones added. The first command stages them and checks that every staged path starts with `docs/`; the second commits only what is staged (no `-a`). The runbook must be in its final form: edits after this commit dirty the tree.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs; git add docs/releases/phase-15 docs/agent-runs/phase-15; NON_DOCS="$(git diff --cached --name-only | grep -v '^docs/')"; if [[ -z "${NON_DOCS}" ]]; then echo "STAGED_ONLY_DOCS=yes files=$(git diff --cached --name-only | wc -l | tr -d ' ')"; git diff --cached --name-only | head -n 40; else echo "STAGED_NON_DOCS=STOP, these staged paths are not under docs/:"; echo "${NON_DOCS}"; false; fi 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-docs-stage.log | tail -n 60; echo "exit: $?"
```

Expected: `STAGED_ONLY_DOCS=yes`, a list under `docs/releases/phase-15/` and `docs/agent-runs/phase-15/`, `exit: 0`. Stop: `STAGED_NON_DOCS=STOP` (unstage that path by hand, for example `git restore --staged <path>`, and rerun; never commit it).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; git commit -m "Phase 15 A + B session preparation: runbook and phase evidence (docs only)" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-docs-commit.log | tail -n 60; echo "exit: $?"
```

Expected: a commit summary listing only files under `docs/`, `exit: 0`. If there is nothing to commit the command says so and exits 1: fine, go on.

### 0.5 State backup (optional, costs nothing)

`cp -p -n` keeps modes and times and refuses to overwrite; the destination is outside the repository; names and sizes only are printed.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs /Users/winnguyen/scholarchain-data/state-backup-p15-ab; ( cp -p -n /Users/winnguyen/ScholarChain/infra/terraform/environments/course/terraform.tfstate* /Users/winnguyen/scholarchain-data/state-backup-p15-ab/; echo "cp exit=$?"; ls -l /Users/winnguyen/scholarchain-data/state-backup-p15-ab/ ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-state-backup.log | tail -n 60; echo "exit: $?"
```

Expected: `cp exit=0`, a listing of `terraform.tfstate` and `terraform.tfstate.backup`. Do not paste the files. The state is not touched by the stash (ignored files stay).

### 0.6 Park leftovers (stash)

`-u` includes untracked files; ignored files (state, `target/`, the test marker) stay. If Wave 2 is fully committed there is nothing to stash and git says so.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; git stash push -u -m p15-ab-leftover 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-stash-push.log | tail -n 60; echo "exit: $?"
```

Expected: `Saved working directory and index state On main: p15-ab-leftover` or `No local changes to save`, `exit: 0`. Stop: any error; run `git stash list` and `git status --short` and report. Never discard anything.

### 0.7 Hard gate

It must print `GATE=PASS`. It needs a completely empty `git status --short`, no difference outside `docs/` between the Wave 2 commit and HEAD, the branch `main`, and (again, from HEAD) the six SP-2 table names and the exact P15-23 profile lines.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs; W2=REPLACE_WITH_WAVE2_COMMIT_SHA; ( S="$(git status --short | wc -l | tr -d ' ')"; git diff --quiet "${W2}" HEAD -- . ':(exclude)docs'; D=$?; BR="$(git symbolic-ref --short -q HEAD)"; echo "HEAD=$(git rev-parse HEAD)"; echo "BRANCH=${BR:-DETACHED}"; echo "LAST_COMMIT=$(git log --oneline -1)"; echo "STATUS_SHORT_LINES=${S}"; git status --short | head -n 20; echo "CODE_DIFF_WAVE2_TO_HEAD_EXIT=${D} (0 = no difference outside docs/)"; git diff --stat "${W2}" HEAD -- . ':(exclude)docs' | tail -n 20; SP2=0; for T in path-evidence components component-membership communities community-membership community-cross-edges; do C="$(git show HEAD:modules/spark/src/main/scala/scholarchain/spark/JsonlExport.scala | grep -c "\"${T}\"")"; if [[ "${C}" -lt 1 ]]; then SP2=1; fi; done; P="$(git show HEAD:scripts/emr/spark-yarn.conf | grep -E '^b-burst\.(PARTITION_COUNT|SPLIT_MAX_BYTES|MAP_MEMORY_MB|REDUCE_MEMORY_MB)=' | LC_ALL=C sort | tr '\n' ' ')"; PR=1; if [[ "${P}" == "b-burst.MAP_MEMORY_MB=2048 b-burst.PARTITION_COUNT=8 b-burst.REDUCE_MEMORY_MB=3072 b-burst.SPLIT_MAX_BYTES=262144 " ]]; then PR=0; fi; echo "SP2_ALL_SIX_IN_HEAD_EXIT=${SP2} (0 = yes)"; echo "B_PROFILE_EXACT_EXIT=${PR} (0 = yes)"; echo "STASH_LIST:"; git stash list | head -n 5; if [[ "${S}" == "0" && "${D}" == "0" && "${BR}" == "main" && "${SP2}" == "0" && "${PR}" == "0" ]]; then echo "GATE=PASS"; else echo "GATE=FAIL: STOP (needs an empty status, no non-docs difference from the Wave 2 commit, branch main, SP-2 and the exact P15-23 profile in HEAD)"; false; fi ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-tree-head.log | tail -n 60; echo "exit: $?"
```

Expected: `BRANCH=main`, `STATUS_SHORT_LINES=0`, `CODE_DIFF_WAVE2_TO_HEAD_EXIT=0`, an empty diff stat, `SP2_ALL_SIX_IN_HEAD_EXIT=0`, `B_PROFILE_EXACT_EXIT=0`, the stash list empty or one `p15-ab-leftover`, `GATE=PASS`, `exit: 0`. HEAD printed here (the docs commit on top of the Wave 2 commit) is the commit every plan, image tag, deploy record and experiment record of this session is bound to. Stop: `GATE=FAIL`; report the status lines or diff stat. Do not delete, reset or discard anything. After a pass, S1 applies.

---

## Step 1. Package and upload (no session resources)

Nothing in step 1 changes a cloud resource except the S3 objects of 1.9. Credentials must stay valid for the whole session (A up to 75 min, B up to 90 min, the heap download): refresh them before each run if the profile is a session (UNVERIFIED how it is configured).

### 1.1 Identity, profile, Region

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs; (echo "profile=scholarchain"; echo "profile_region=$(AWS_PROFILE=scholarchain aws configure get region)"; CALLER="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; if [[ "${CALLER}" =~ ^[0-9]{12}$ ]]; then echo "caller_account=12-digit id (not printed)"; else echo "caller_account=NOT A 12-DIGIT ID: STOP"; fi; echo "approved_region=us-east-2") 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-identity.log | tail -n 60; echo "exit: $?"
```

Expected: `profile_region=us-east-2` (blank is not a stop: every command passes `--region us-east-2`) and `caller_account=12-digit id (not printed)`. Stop: not a 12-digit id.

Quotas (cited, not re-run; `wave-1/doc-verification.md`): EC2 `L-1216C47A` applied 32 vCPU: A uses 4 vCPU, B peaks at 4 x m5.xlarge = 16 vCPU, never together. Fargate `L-3032A538` applied 30 vCPU: no Fargate task starts in this session (`service_desired_count=0`, `pipeline_skip_loader`). Bedrock is off. The P15-13 Haiku availability re-check belongs to session 2.

### 1.2 State check (dead-letter queues, clusters, executions, EC2)

Run now, and again immediately before each run (2.6 and 3.6). The experiments run with `--stop-on first-failure,dlq-nonempty`; the pipeline script refuses to start with a RUNNING execution or an active `scholarchain-course-pipeline` cluster, after the experiment record is already consumed.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; mkdir -p /Users/winnguyen/scholarchain-data/logs; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; echo "--utc ${STAMP}"; echo "--dlq-run-requests (expect 0 0)"; aws sqs get-queue-attributes --queue-url "$(aws sqs get-queue-url --queue-name scholarchain-course-run-requests-dlq --region us-east-2 --query QueueUrl --output text)" --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible --region us-east-2 --output text; echo "--dlq-embedding-work (expect 0 0)"; aws sqs get-queue-attributes --queue-url "$(aws sqs get-queue-url --queue-name scholarchain-course-embedding-work-dlq --region us-east-2 --query QueueUrl --output text)" --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible --region us-east-2 --output text; echo "--emr-active-clusters (expect empty)"; aws emr list-clusters --active --region us-east-2 --query 'Clusters[].[Id,Name,Status.State]' --output text; echo "--sfn-running-executions (expect empty)"; aws stepfunctions list-executions --state-machine-arn "$(aws stepfunctions list-state-machines --region us-east-2 --query "stateMachines[?name=='scholarchain-course-pipeline'].stateMachineArn | [0]" --output text)" --status-filter RUNNING --region us-east-2 --query 'executions[].[name,status]' --output text; echo "--ec2-instances-pending-or-running (expect empty)"; aws ec2 describe-instances --region us-east-2 --filters "Name=instance-state-name,Values=pending,running" --query 'Reservations[].Instances[].[InstanceId,InstanceType]' --output text ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-state-check-${STAMP}.log | tail -n 60; echo "exit: $?"
```

Expected: `0 0` under both DLQ labels, every other section empty. Stop: a non-zero DLQ depth, an active cluster, a running execution or a running instance; report, do not purge or terminate here (a leftover cluster during or after the session is S4). From 2.4R on, S2 applies.

### 1.3 Raw inputs exist in S3 (read-only)

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; DATA_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw data_bucket)"; echo "--raw/phase5-dblp-subset/ (expect dblp-subset.xml, openalex.jsonl, crossref.jsonl, orcid.jsonl)"; aws s3 ls "s3://${DATA_BUCKET}/raw/phase5-dblp-subset/" --region us-east-2 ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-raw-listing.log | tail -n 60; echo "exit: $?"
```

Expected: four objects (2111266, 940131, 174011 and 19675 bytes per `datasets/tier-s/snapshot-s.json`). Stop: fewer, or a `terraform output` error (local state; `.terraform/` must exist from an earlier `plan.sh`). The bucket name is not printed by this command (only keys).

### 1.4 Prepare the manifest copies and the pipeline inputs (local files only)

The committed manifests hold placeholder `ACCOUNT_ID` and `OUTPUT_ROOT`; `run-experiment.sh` binds the manifest by sha256, so the human runs from a copy with the real account id and an output root outside the repository. Pipeline input templates hold a placeholder run id: A and B each get a NEW lower-case UUID (the failed session 1 B run id `run-v1-2d680606-...` must not be reused: the same id would write into its S3 prefixes). All four files are new names (`p15-ab-*`); session 1's copies and records stay untouched and are void for this session.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; REPO=/Users/winnguyen/ScholarChain; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; mkdir -p ${SCD}/logs ${SCD}/experiments/manifests ${SCD}/experiments/runs ${SCD}/pipeline-inputs ${SCD}/approvals ${SCD}/plans; if [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ ]]; then (set -C; sed -e "s/^ACCOUNT_ID=.*/ACCOUNT_ID=${ACCOUNT_ID}/" -e "s#^OUTPUT_ROOT=.*#OUTPUT_ROOT=${SCD}/experiments/runs#" ${REPO}/experiments/manifests/experiment-a-baseline.manifest > ${SCD}/experiments/manifests/p15-ab-a-baseline.manifest) && (set -C; sed -e "s/^ACCOUNT_ID=.*/ACCOUNT_ID=${ACCOUNT_ID}/" -e "s#^OUTPUT_ROOT=.*#OUTPUT_ROOT=${SCD}/experiments/runs#" ${REPO}/experiments/manifests/experiment-b-burst.manifest > ${SCD}/experiments/manifests/p15-ab-b-burst.manifest) && A_RUN="run-v1-$(uuidgen | tr 'A-Z' 'a-z')" && B_RUN="run-v1-$(uuidgen | tr 'A-Z' 'a-z')" && (set -C; sed "s/run-v1-REPLACE-WITH-A-NEW-LOWERCASE-UUID/${A_RUN}/" ${REPO}/experiments/pipeline-inputs/s-a-baseline.template.json > ${SCD}/pipeline-inputs/p15-ab-a.json) && (set -C; sed "s/run-v1-REPLACE-WITH-A-NEW-LOWERCASE-UUID/${B_RUN}/" ${REPO}/experiments/pipeline-inputs/s-b-burst.template.json > ${SCD}/pipeline-inputs/p15-ab-b.json) && echo "A_RUN=${A_RUN}" && echo "B_RUN=${B_RUN}" && grep -HE '^(EXPERIMENT_ID|DATASET_SNAPSHOT|SIZE_TIER|MAX_MINUTES|MAX_CONCURRENCY|MAX_TASKS|SPEND_CEILING_USD|HOURLY_BURN_USD|OUTPUT_ROOT|ENTRY_POINT)=' ${SCD}/experiments/manifests/p15-ab-a-baseline.manifest ${SCD}/experiments/manifests/p15-ab-b-burst.manifest; else echo "no 12-digit account id from STS: STOP"; false; fi 2>&1 | tee ${SCD}/logs/phase15-ab-prepare-inputs.log | tail -n 60; echo "exit: $?"
```

Expected: two different `run-v1-<uuid>` lines and the limits below, `exit: 0`. The command prints no account id (the `ACCOUNT_ID=` line is not in the grep). Every file is written with noclobber: a second run fails rather than overwrite a file a record binds; if you must redo it, delete the four `p15-ab-*` files by hand first and treat every digest and record made from them as void.

| Manifest copy | `MAX_MINUTES` | `MAX_CONCURRENCY` / `MAX_TASKS` | `SPEND_CEILING_USD` | `HOURLY_BURN_USD` | `ENTRY_POINT` |
|---|---|---|---|---|---|
| `p15-ab-a-baseline.manifest` | 75 | 1 / 1 | 0.75 | 0.60 | `script:experiments/a-baseline/run.sh` |
| `p15-ab-b-burst.manifest` | 90 | 1 / 1 | 2.40 | 1.55 | `script:experiments/b-burst/run.sh` |

Stop: an STS failure, or `DATASET_SNAPSHOT` other than `scholarchain-s-20261107-10dd7f34121b`.

### 1.5 Validate A and B before spending, and print the digests (no AWS call)

`--print-digest` exits before any cloud or Terraform call and needs no record; it runs the full manifest and input validation. The pipeline arguments are those `experiments/lib/exp-lib.sh` passes with the numbers committed in each `run.sh`: A `medium 70 0.75 0.60` (`a-baseline/run.sh` line 35), B `medium 88 2.30 1.55` (`b-burst/run.sh` line 49). B's digests here are NEW (new input file, new run id, new manifest copy): none of session 1's digests or records applies. The verdict is the `part1 exit` and `part2 exit` lines.

1.5A (A):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; { bash scripts/run-experiment.sh --print-digest --manifest ${SCD}/experiments/manifests/p15-ab-a-baseline.manifest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 0.75 2>&1 | tee ${SCD}/logs/phase15-ab-a-experiment-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g"; echo "part1 exit: $?"; bash scripts/run-bounded-pipeline.sh --print-digest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --size medium --max-minutes 70 --spend-ceiling-usd 0.75 --hourly-burn-usd 0.60 --stop-on first-failure,dlq-nonempty --input-file ${SCD}/pipeline-inputs/p15-ab-a.json 2>&1 | tee ${SCD}/logs/phase15-ab-a-pipeline-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g"; echo "part2 exit: $?"; } | tail -n 60; echo "exit: $?"
```

1.5B (B):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; { bash scripts/run-experiment.sh --print-digest --manifest ${SCD}/experiments/manifests/p15-ab-b-burst.manifest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 2.40 2>&1 | tee ${SCD}/logs/phase15-ab-b-experiment-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g"; echo "part1 exit: $?"; bash scripts/run-bounded-pipeline.sh --print-digest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --size medium --max-minutes 88 --spend-ceiling-usd 2.30 --hourly-burn-usd 1.55 --stop-on first-failure,dlq-nonempty --input-file ${SCD}/pipeline-inputs/p15-ab-b.json 2>&1 | tee ${SCD}/logs/phase15-ab-b-pipeline-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g"; echo "part2 exit: $?"; } | tail -n 60; echo "exit: $?"
```

Expected: each part prints `ACTION=`, `SCRIPT_DIGEST=`, `ARGS_CANONICAL=`, `ARGS_DIGEST=` (account shown as `000000000000`), `part1 exit: 0`, `part2 exit: 0`. Check `ARGS_CANONICAL`:
- A experiment: `action=experiment;environment=course;account=<id>;region=us-east-2;cost=0.75;extra=manifest=<sha>;experiment=A;size=S;max_minutes=75;max_concurrency=1;max_tasks=1;spend_ceiling=0.75`; A pipeline: `action=pipeline;...;cost=20;extra=size=medium;max_minutes=70;spend_ceiling=0.75;hourly_burn=0.60;stop_on=first-failure,dlq-nonempty;input=<sha>`.
- B experiment: `...cost=2.40;extra=manifest=<sha>;experiment=B;size=S;max_minutes=90;max_concurrency=1;max_tasks=1;spend_ceiling=2.40`; B pipeline: `...extra=size=medium;max_minutes=88;spend_ceiling=2.30;hourly_burn=1.55;stop_on=first-failure,dlq-nonempty;input=<sha>`.

Projected spend checks: A 0.60 x 70 / 60 = 0.70 at most 0.75; B 1.55 x 88 / 60 = 2.27 at most 2.30 and 2.33 at most 2.40. Stop: any `EVIDENCE:` refusal. The B experiment `ARGS_DIGEST` must differ from session 1's (`cbf66f79...`), and the B pipeline digest from `628795c6...` (new input and manifest copy).

### 1.6 Build the jars and the image contexts (`package.sh`)

Needs Docker running, JDK 21, sbt and the clean tree of 0.7; HEAD is the Wave 2 commit plus docs, so the Spark jar contains SP-2. Runs one `sbt packageJobs dockerLocal` and two `docker build`s; no AWS call, no push. `--out-dir` must not exist. `deploy.sh` later digests `target/docker-local` as both contexts: do not rebuild after the first deploy digest (2.4P).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; HEAD12="$(git rev-parse HEAD | cut -c1-12)"; mkdir -p /Users/winnguyen/scholarchain-data/logs /Users/winnguyen/scholarchain-data/package; bash scripts/package.sh --platform linux/amd64 --out-dir /Users/winnguyen/scholarchain-data/package/p15-ab-${HEAD12} 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-package.log | tail -n 60; echo "exit: $?"
```

Expected: `IMAGE_BUILT` for both images, `PACKAGE_COMPLETE git_sha=<HEAD> image_tag=<HEAD> platform=linux/amd64`, `exit: 0`. Stop: a compile or test failure, a dirty-tree refusal, or an image tag ending `-dirty`. Afterwards `git status --short` must still be empty (the build writes only ignored output).

### 1.7 SP-2 is in the built Spark jar (local, read-only)

The export of 17 tables comes from the Spark jar the node downloads. Review-1 M2: the earlier check searched all `scholarchain/spark/` classes for `community-cross-edges`, which also sits in `CollaborationPipeline.scala` (lines 732 and 735), so a stale jar passed. This check reads ONLY the `ExportTables` class files (`scholarchain/spark/ExportTables*.class`) of every top-level jar in `modules/spark/target/job/` and requires ALL six SP-2 names in them: `path-evidence`, `components`, `component-membership`, `communities`, `community-membership`, `community-cross-edges` (`ExportTables.all` lives in `JsonlExport.scala`, per 0.3 and the WO-7c report). Names are matched as plain substrings of the class bytes, so `components` and `communities` could also match a longer constant; the requirement that all six sit together in `ExportTables` keeps that weakness small.

UNVERIFIED by a run: (a) the jar layout (main jar at the top level, libraries under `lib/`, as in session 1); (b) that the object is named `ExportTables` in package `scholarchain.spark` in the jar; (c) WHICH class file holds the string constants: I expect `ExportTables$.class` (the Scala 3 module class) and not `ExportTables.class` (the static-forwarder mirror), so the glob takes every `ExportTables*.class` and the command lists the entries it used. Fallback (only if no `ExportTables` class entry exists, that is, (a) or (b) fails and not when a name is missing): the human may accept in the chat the pair `PACKAGE_COMPLETE git_sha=<HEAD>` from 1.6 together with `JAR_MATCH` for every top-level Spark jar from 1.10, since 0.3 proves HEAD holds all six names in the source. The fallback is never automatic and never applies to a class that is found with a name missing.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; ( OK=0; NOCLASS=1; for J in /Users/winnguyen/ScholarChain/modules/spark/target/job/*.jar; do N="$(unzip -l "${J}" 'scholarchain/spark/ExportTables*.class' 2>/dev/null | awk '$4 ~ /ExportTables[^\/]*\.class$/ { print $4 }' | wc -l | tr -d ' ')"; echo "${J##*/} export_tables_class_files=${N}"; if [[ "${N}" -ge 1 ]]; then NOCLASS=0; unzip -l "${J}" 'scholarchain/spark/ExportTables*.class' 2>/dev/null | awk '$4 ~ /ExportTables[^\/]*\.class$/ { print "  class_file " $4 }'; MISS=0; for T in path-evidence components component-membership communities community-membership community-cross-edges; do C="$(unzip -p "${J}" 'scholarchain/spark/ExportTables*.class' 2>/dev/null | grep -a -c -F -e "${T}")"; echo "  ${T} lines=${C}"; if [[ "${C}" -lt 1 ]]; then MISS=1; fi; done; if [[ "${MISS}" == "0" ]]; then OK=1; fi; fi; done; if [[ "${OK}" == "1" ]]; then echo "SP2_ALL_SIX_IN_EXPORTTABLES=yes"; elif [[ "${NOCLASS}" == "1" ]]; then echo "EXPORTTABLES_CLASS_NOT_FOUND: layout or class-name assumption failed, no verdict on SP-2: STOP; only the human's chat decision may use the fallback PACKAGE_COMPLETE git_sha plus JAR_MATCH (1.10)"; false; else echo "SP2_ALL_SIX_IN_EXPORTTABLES=NO: STOP (stale or wrong jar: do not upload)"; false; fi ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-sp2-jar.log | tail -n 60; echo "exit: $?"
```

Expected: one jar with `export_tables_class_files=` 1 or more, the listed `class_file` entries, six name lines each with `lines=` 1 or more, `SP2_ALL_SIX_IN_EXPORTTABLES=yes`, `exit: 0`. Stop: `SP2_ALL_SIX_IN_EXPORTTABLES=NO` (the build missed SP-2 or the jar is stale: nothing is uploaded; report) or `EXPORTTABLES_CLASS_NOT_FOUND` (the layout or class name differs from this command's assumption: report; the fallback above needs the human's written decision).

### 1.8 Upload, P: print the digests

`upload-inputs.sh` copies the bootstrap scripts, the job jars and the Spark 4.0.5 archive; the list is bound by digest. `--spark-dir` and the limits are those session 1 used successfully (upload plan 42 objects, 1,038,128,626 bytes).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ENVDIR=/Users/winnguyen/ScholarChain/infra/terraform/environments/course; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; DATA_BUCKET="$(terraform -chdir=${ENVDIR} output -raw data_bucket)"; ART_BUCKET="$(terraform -chdir=${ENVDIR} output -raw artifacts_bucket)"; AWS_PROFILE=scholarchain bash scripts/upload-inputs.sh --print-digest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --snapshot phase5-dblp-subset --data-bucket "${DATA_BUCKET}" --artifacts-bucket "${ART_BUCKET}" --spark-dir ${SCD}/spark-4.0.5 --max-objects 120 --max-bytes 1500000000 2>&1 | tee ${SCD}/logs/phase15-ab-upload-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `SPARK_CHECKSUMS_VERIFIED`, `UPLOAD_PLAN objects=<about 42> bytes=<about 1.04 GB>`, then `ACTION=upload`, `SCRIPT_DIGEST=`, `ARGS_CANONICAL=`, `ARGS_DIGEST=`, `exit: 0`. Stop: `cannot build the file list` (rerun 1.6), a checksum mismatch, a count or byte refusal.

### 1.8b Raw inputs equal the registered snapshot (read-only)

The upload overwrites `raw/phase5-dblp-subset/` (versioning off), so a changed local file would silently replace the input Phase 14 ran on. This compares the four `WOULD_UPLOAD ... sha256=` lines with `datasets/tier-s/snapshot-s.json` (dblp `9076e857...`, openalex `fdeddfa3...`, crossref `c403ccbf...`, orcid `719a1cdb...`).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; python3 -I -c 'import json,re,sys; m={x["path"].rsplit("/",1)[1]: x["sha256"] for x in json.load(open(sys.argv[1]))["members"]}; got={}; [got.__setitem__(g.group(1), g.group(2)) for g in (re.search(r"/raw/phase5-dblp-subset/([^ /]+) bytes=[0-9]+ sha256=([0-9a-f]{64})", l) for l in open(sys.argv[2])) if g]; bad=[k for k in m if got.get(k) != m[k]]; print("expected", len(m), "found_in_upload_plan", len(got), "mismatch_or_missing", bad); print("RAW_INPUTS_MATCH_SNAPSHOT=" + ("yes" if not bad and len(got) == len(m) else "NO: STOP")); sys.exit(1 if bad or len(got) != len(m) else 0)' /Users/winnguyen/ScholarChain/datasets/tier-s/snapshot-s.json /Users/winnguyen/scholarchain-data/logs/phase15-ab-upload-digest.log 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-raw-compare.log | tail -n 60; echo "exit: $?"
```

Expected: `expected 4 found_in_upload_plan 4 mismatch_or_missing []`, `RAW_INPUTS_MATCH_SNAPSHOT=yes`, `exit: 0`. Stop: anything else; write no record, upload nothing, report.

### 1.8c No stale jars in S3 (read-only; review-2 notes 1, 2, 4)

The upload never deletes; the node copies every object under `jars/<module>/`, so an old jar left in S3 stays on the class path. (i) lists the remote keys, checks each `aws s3 ls` status and refuses an empty list; (ii) compares with `LC_ALL=C` against the local `modules/<module>/target/job` that 1.6 built.

(i) Remote listing:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; ART_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw artifacts_bucket)"; OK=1; for M in hadoop entity-resolution spark; do if aws s3 ls "s3://${ART_BUCKET}/jars/${M}/" --recursive --region us-east-2 > ${SCD}/logs/phase15-ab-jars-remote-${M}.ls; then awk '{ print $4 }' ${SCD}/logs/phase15-ab-jars-remote-${M}.ls | sed "s#^jars/${M}/##" | LC_ALL=C sort > ${SCD}/logs/phase15-ab-jars-remote-${M}.txt; N="$(wc -l < ${SCD}/logs/phase15-ab-jars-remote-${M}.txt | tr -d ' ')"; echo "${M}: remote keys=${N}"; if [[ "${N}" -lt 1 ]]; then echo "${M}: EMPTY remote listing: STOP"; OK=0; fi; else echo "${M}: aws s3 ls FAILED: STOP"; OK=0; fi; done; if [[ "${OK}" == "1" ]]; then echo "REMOTE_LISTING_OK=yes"; else echo "REMOTE_LISTING_OK=NO: STOP"; false; fi ) 2>&1 | tee ${SCD}/logs/phase15-ab-jars-remote.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: non-zero key counts for the three modules (session 1 saw 10, 10 and 9), `REMOTE_LISTING_OK=yes`, `exit: 0`.

(ii) Comparison:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( EXTRA=0; for M in hadoop entity-resolution spark; do if [[ ! -s ${SCD}/logs/phase15-ab-jars-remote-${M}.txt ]]; then echo "${M}: remote list missing or empty: STOP"; EXTRA=1; continue; fi; echo "--${M}: remote-only keys (stale; expect none)"; STALE="$(LC_ALL=C comm -23 ${SCD}/logs/phase15-ab-jars-remote-${M}.txt <(cd /Users/winnguyen/ScholarChain/modules/${M}/target/job && find . -name '*.jar' | sed 's#^\./##' | LC_ALL=C sort))"; echo "${STALE}"; if [[ -n "${STALE}" ]]; then EXTRA=1; fi; echo "--${M}: local-only keys (will be added; informational)"; LC_ALL=C comm -13 ${SCD}/logs/phase15-ab-jars-remote-${M}.txt <(cd /Users/winnguyen/ScholarChain/modules/${M}/target/job && find . -name '*.jar' | sed 's#^\./##' | LC_ALL=C sort) | head -n 5; done; if [[ "${EXTRA}" == "0" ]]; then echo "NO_STALE_JARS=yes"; else echo "NO_STALE_JARS=NO: STOP"; false; fi ) 2>&1 | tee ${SCD}/logs/phase15-ab-jars-compare.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: empty remote-only lists, `NO_STALE_JARS=yes`, `exit: 0`. Stop: any remote-only key. Do not upload. Removing an object is data deletion: it needs the human's written approval of the exact keys through a reviewed path, not a step here; report the key names.

### 1.9 Upload, W then D then R

Run W only after 1.7, 1.8b and 1.8c passed. By running W the human approves exactly the file list named in `phase15-ab-upload-digest.log`.

1.9W:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; REC=${SCD}/approvals/p15-ab-upload.env; dg() { awk -F= -v k="${2}" '$1 == k { print $2; exit }' "${1}"; }; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; EXP="$(date -u -v+8H +%Y-%m-%dT%H:%M:%SZ)"; ( (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=upload" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=20" "PLAN_DIGEST=n/a" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-ab-upload-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-ab-upload-digest.log ARGS_DIGEST)" "GIT_SHA=n/a" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > "${REC}") && sed "s/^ACCOUNT_ID=.*/ACCOUNT_ID=<masked>/" "${REC}" ) 2>&1 | tee ${SCD}/logs/phase15-ab-upload-record.log | tail -n 60; echo "exit: $?"
```

Expected: 14 lines, account masked, non-empty digests, `exit: 0`. Stop: an empty digest, or `file exists` (move the old record aside by hand only if it was not written for this digest).

1.9D (dry run, consumes nothing):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ENVDIR=/Users/winnguyen/ScholarChain/infra/terraform/environments/course; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; DATA_BUCKET="$(terraform -chdir=${ENVDIR} output -raw data_bucket)"; ART_BUCKET="$(terraform -chdir=${ENVDIR} output -raw artifacts_bucket)"; AWS_PROFILE=scholarchain bash scripts/upload-inputs.sh --dry-run --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-ab-upload.env --snapshot phase5-dblp-subset --data-bucket "${DATA_BUCKET}" --artifacts-bucket "${ART_BUCKET}" --spark-dir ${SCD}/spark-4.0.5 --max-objects 120 --max-bytes 1500000000 2>&1 | tee ${SCD}/logs/phase15-ab-upload-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `APPROVAL_VERIFIED action=upload ...`, `APPROVAL_NOT_CONSUMED dry-run`, `DRY_RUN_COMPLETE`, `exit: 0`.

1.9R (real; one `aws s3 cp` per file, no sync, no delete; versioning off, so a re-uploaded key replaces the old object and there is no old copy):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ENVDIR=/Users/winnguyen/ScholarChain/infra/terraform/environments/course; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; DATA_BUCKET="$(terraform -chdir=${ENVDIR} output -raw data_bucket)"; ART_BUCKET="$(terraform -chdir=${ENVDIR} output -raw artifacts_bucket)"; AWS_PROFILE=scholarchain bash scripts/upload-inputs.sh --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-ab-upload.env --snapshot phase5-dblp-subset --data-bucket "${DATA_BUCKET}" --artifacts-bucket "${ART_BUCKET}" --spark-dir ${SCD}/spark-4.0.5 --max-objects 120 --max-bytes 1500000000 2>&1 | tee ${SCD}/logs/phase15-ab-upload.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `UPLOADED` and `VERIFIED` per object, `UPLOAD_COMPLETE`, `exit: 0`. A failed upload consumed the record: re-run 1.9W with a fresh `APPROVED_AT` after reading why.

### 1.10 The node scripts and the Spark jar in S3 are the committed ones (read-only)

Four `MATCH` lines for the node scripts; the exact P15-23 `b-burst` values in the uploaded `spark-yarn.conf`; and, for every top-level local Spark jar, the same sha256 in S3 (so the node runs the SP-2 jar). UNVERIFIED: the S3 key of the jar is `jars/spark/<file name>` (as 1.8c assumes).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; ART_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw artifacts_bucket)"; for F in lib-emr.sh run-s-pipeline.sh capability-check.sh spark-yarn.conf; do L="$(shasum -a 256 < /Users/winnguyen/ScholarChain/scripts/emr/${F} | cut -d' ' -f1)"; R="$(aws s3 cp "s3://${ART_BUCKET}/bootstrap/${F}" - --region us-east-2 | shasum -a 256 | cut -d' ' -f1)"; if [[ "${L}" == "${R}" ]]; then echo "MATCH ${F}"; else echo "DIFFERENT ${F}"; fi; done; echo "--profile lines in the uploaded spark-yarn.conf (expect 4 a-baseline and 9 b-burst)"; aws s3 cp "s3://${ART_BUCKET}/bootstrap/spark-yarn.conf" - --region us-east-2 | grep -E '^(a-baseline|b-burst)\.'; P="$(aws s3 cp "s3://${ART_BUCKET}/bootstrap/spark-yarn.conf" - --region us-east-2 | grep -E '^b-burst\.(PARTITION_COUNT|SPLIT_MAX_BYTES|MAP_MEMORY_MB|REDUCE_MEMORY_MB)=' | LC_ALL=C sort | tr '\n' ' ')"; if [[ "${P}" == "b-burst.MAP_MEMORY_MB=2048 b-burst.PARTITION_COUNT=8 b-burst.REDUCE_MEMORY_MB=3072 b-burst.SPLIT_MAX_BYTES=262144 " ]]; then echo "B_PROFILE_P15_23_IN_S3=yes"; else echo "B_PROFILE_P15_23_IN_S3=NO: STOP"; fi; echo "--spark jars: local sha256 against S3 (expect MATCH)"; for J in /Users/winnguyen/ScholarChain/modules/spark/target/job/*.jar; do L="$(shasum -a 256 < "${J}" | cut -d' ' -f1)"; R="$(aws s3 cp "s3://${ART_BUCKET}/jars/spark/${J##*/}" - --region us-east-2 | shasum -a 256 | cut -d' ' -f1)"; if [[ "${L}" == "${R}" ]]; then echo "JAR_MATCH ${J##*/}"; else echo "JAR_DIFFERENT ${J##*/}"; fi; done ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-bootstrap-check.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: four `MATCH` lines; the 13 profile lines (`a-baseline.`: `SPARK_EVENT_LOG`, `MR_COUNTERS`, `DATA_PATH`, `SPARK_MASTER`; `b-burst.`: `DATA_PATH`, `SPARK_MASTER`, `SPARK_EVENT_LOG`, `MR_COUNTERS`, `SHUFFLE_PARTITIONS`, `PARTITION_COUNT`, `SPLIT_MAX_BYTES`, `MAP_MEMORY_MB`, `REDUCE_MEMORY_MB`); `B_PROFILE_P15_23_IN_S3=yes`; a `JAR_MATCH` for each top-level Spark jar. Stop: any `DIFFERENT`, `JAR_DIFFERENT` or `B_PROFILE_P15_23_IN_S3=NO`: redo the upload (a new 1.9W) after reading why.

---

## Step 2. Experiment A first (single node, produces the 17-table export)

Every stop from 2.4R on is under S2. Stops in 2.1 to 2.4D change nothing billable: report and wait.

### 2.1 Plan A (session-up on `baseline-s`, from the down state)

Layering: `course.tfvars`, then `experiment-size-s.tfvars`, then `baseline-s.tfvars` (later wins; `--extra-var-file` repeats and keeps order). `plan.sh` applies nothing.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ENVDIR=/Users/winnguyen/ScholarChain/infra/terraform/environments/course; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; mkdir -p ${SCD}/logs ${SCD}/plans; AWS_PROFILE=scholarchain bash scripts/plan.sh --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --extra-var-file ${ENVDIR}/experiment-size-s.tfvars --extra-var-file ${ENVDIR}/baseline-s.tfvars --out-dir ${SCD}/plans/p15-ab-a-up 2>&1 | tee ${SCD}/logs/phase15-ab-a-plan.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected (from infra-engineer's `w2rb4-infra-engineer.md` and `SESSION2.md` section 5a, read from the code; UNVERIFIED until this plan runs): `11 to add, 7 to change, 3 to destroy` and `IAM_CHANGES: none`. With OD-1 A and B create no Wave 2 resource, so the plan is session 1's shape plus four retention changes:
- ADD 8: the interface endpoints `ecr.api`, `ecr.dkr`, `logs`, `secretsmanager`, `sts`, `elasticmapreduce`, `s3vectors`, `sqs` (no `bedrock-runtime`).
- REPLACE 3 (`-/+`, new image tag and environment; they make the 3 destroys and 3 of the 11 adds): `module.runtime.aws_ecs_task_definition.service["fast"]`, `["slow"]` and `.loader`. No pure destroy line.
- UPDATE in place 7: `module.runtime.aws_ecs_service.this["fast"]` and `["slow"]` (task definition; desired count stays 0), `module.workflow.aws_sfn_state_machine.pipeline[0]` (profile `a-baseline`, timeouts), and four log groups `module.runtime.aws_cloudwatch_log_group.this["fast"]`, `["slow"]`, `["loader"]` and `module.workflow.aws_cloudwatch_log_group.pipeline[0]` with `retention_in_days` 3 to 7. These four are the difference from session 1's 3 changes and need the human's acknowledgement given at 0.0.
- Residual UNVERIFIED (`w2rb4`): the explicit `health_check_grace_period_seconds = 0` on `fast` and `slow` may show as noise on services that are already in the update list; it cannot change the counts.
Expected markers: `PLAN_EXIT=2`, `STATEFUL_CHANGE=no`, `IAM_CHANGES:` followed on the next line by `  none` (two leading spaces; `scripts/lib.sh:856-857`), `PLAN_DIGEST=<hex>`, `exit: 0`; `HIGH_COST_CREATIONS` lists the endpoints. Any other count is a stop at 2.2.

### 2.2 Read plan A before approving

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ( LOG=${SCD}/logs/phase15-ab-a-plan.log; ACT="$(sed '/^PLAN_SUMMARY_BEGIN/,$d' ${LOG} | grep -nE '^[[:space:]]*# .*(will be created|will be destroyed|must be replaced|will be replaced|will be updated in-place)')"; echo "--resource actions"; printf '%s\n' "${ACT}"; echo "--summary"; sed -n '/^PLAN_SUMMARY_BEGIN/,/^PLAN_SUMMARY_END/p' ${LOG}; echo "--meta"; grep -E '^(GIT_SHA|DIRTY|STATEFUL_CHANGE|PLAN_EXIT|CPU_ARCHITECTURE|IMAGE_TAG)=' ${SCD}/plans/p15-ab-a-up/course-*.tfplan.meta; echo "--iam (expect IAM_CHANGES: none)"; grep -A1 -x 'IAM_CHANGES:' ${LOG}; echo "--terraform counts (expect 11 to add, 7 to change, 3 to destroy)"; grep -E '[0-9]+ to add, [0-9]+ to change, [0-9]+ to destroy' ${LOG}; FORBID='aws_iam_|aws_lb|aws_alb|aws_appautoscaling_|aws_ecs_service\.worker|aws_ecs_service\.this\["(worker|query)"\]|aws_ecr_|load-client|load_client|task_definition\.worker|aws_cloudwatch_dashboard|aws_cloudwatch_metric_alarm|aws_ecs_cluster|aws_security_group|aws_neptune|bedrock|aws_s3_|aws_dynamodb_|aws_s3vectors_|aws_secretsmanager_|log_group\.this\["(query|worker)"\]'; BAD="$(printf '%s\n' "${ACT}" | grep -E "${FORBID}")"; DC="$(grep -E 'desired_count.*[1-9]' ${LOG})"; echo "--forbidden addresses (expect none)"; printf '%s\n' "${BAD}"; echo "--desired_count above 0 (expect none; the plan log may not print attribute lines, UNVERIFIED)"; printf '%s\n' "${DC}"; N_CR="$(printf '%s\n' "${ACT}" | grep -c 'will be created')"; N_RP="$(printf '%s\n' "${ACT}" | grep -cE 'must be replaced|will be replaced')"; N_UP="$(printf '%s\n' "${ACT}" | grep -c 'will be updated in-place')"; N_DS="$(printf '%s\n' "${ACT}" | grep -c 'will be destroyed')"; echo "ACTION_LINES created=${N_CR} replaced=${N_RP} updated=${N_UP} destroyed=${N_DS} (expect 8 3 7 0)"; if [[ -z "${BAD}" && -z "${DC}" && "${N_CR}" == "8" && "${N_RP}" == "3" && "${N_UP}" == "7" && "${N_DS}" == "0" ]] && grep -qE '11 to add, 7 to change, 3 to destroy' ${LOG} && grep -A1 -x 'IAM_CHANGES:' ${LOG} | tail -n 1 | grep -qx '  none'; then echo "PLAN_A_UP_CHECK=PASS"; else echo "PLAN_A_UP_CHECK=FAIL: STOP (counts, a forbidden address, desired_count or IAM differ from SESSION2.md section 5a)"; false; fi ) 2>&1 | tee ${SCD}/logs/phase15-ab-a-plan-actions.log | tail -n 60; echo "exit: $?"
```

The command prints the actions, the summary, the meta lines and an automated verdict `PLAN_A_UP_CHECK` (counts 8 created, 3 replaced, 7 updated, 0 destroyed; the Terraform counts line; `IAM_CHANGES: none`; no forbidden address; no `desired_count` above 0). The verdict does not replace the human's reading of the action lines against 2.1. The log is `plan.sh`'s stdout, so it holds Terraform's plan text and then the `PLAN_SUMMARY_BEGIN` block that `plan_summary` (`scripts/lib.sh:852-869`) prints: the `Plan: N to add, ...` line (Terraform prints it and the summary repeats it), `IAM_CHANGES:` alone on a line with `  none` (two spaces) on the next line, and the summary re-prints the high-cost `will be created` lines. The IAM test therefore reads the line after `IAM_CHANGES:` (`grep -A1 -x`), and the action lines are taken only from the text before `PLAN_SUMMARY_BEGIN` (`sed '/^PLAN_SUMMARY_BEGIN/,$d'`) so the endpoint lines are not counted twice (counted twice they would give 16 created, not 8). Both forms are read from the code and stay UNVERIFIED until this plan runs; if a grep prints nothing, the verdict is `FAIL` and the human reads the summary by eye before deciding. If an `aws_iam_*` change exists, the line after `IAM_CHANGES:` is that change line, which is not `  none`, so the verdict fails as intended.

Stop conditions (no record is written; report and wait). Any one of these is a stop, and any of the first five means a session-2 flag leaked into a var file (`enable_services_stack`, `service_layout` or an alarm flag): do not approve, route to infra-engineer through the orchestrator.
- An `aws_iam_*` line, or any `IAM_CHANGES` other than `none`. This session has NO privilege-diff approval (the worker role and the runtime policy split belong to session 2's A2, `SESSION2.md` section 6): per `docs/agents.md` ("Security, IAM, secrets") an IAM line needs the human's approval of principals and blast radius, which this runbook does not carry, so it is a stop.
- An `aws_lb*` resource, any `aws_appautoscaling_*` resource, `aws_ecs_service.worker`, or `aws_ecs_service.this["query"]`; any scaling alarm; the scaling dashboard `aws_cloudwatch_dashboard.scaling`.
- The load-client ECR repository (`aws_ecr_repository.this["load-client"]` or its lifecycle policy) or the load-client task definition (`aws_ecs_task_definition.load_client`), the worker task definition, the `alb` and `load_client` security groups and their rules, or the `query`, `worker` or `load-client` log groups.
- A change of the cluster `containerInsights` setting (the cluster `aws_ecs_cluster` line would be an update).
- A service with `desired_count` above 0.
- Any destroy beyond the three replaced task definitions (a pure `will be destroyed` line), or a count line other than `11 to add, 7 to change, 3 to destroy`.
- Any Neptune resource; any `bedrock-runtime` endpoint or Bedrock IAM statement; any change to a bucket, DynamoDB table, S3 Vectors bucket or index, ECR repository or secret (`STATEFUL_DESTROY_OR_REPLACE` or `STATEFUL_CHANGE=yes`).
- `DIRTY=yes`, `GIT_SHA` not equal to the HEAD of 0.7, or `CPU_ARCHITECTURE` other than `X86_64`.

There is no IAM statement to write in this session. Rollback for a stop here: nothing is applied; plan again after the owner's fix. The human's 0.0 acknowledgement of the 7 changes must be in the chat before 2.4W.

### 2.3 Rendered state machine of plan A (read-only)

The extract needs the definition to be known at plan time. With a new image tag the loader task definition ARN is unknown until apply, so the expected result is "the definition is unknown until apply"; then rely on 2.5 and say so in the evidence (an honest "cannot be checked before apply", not a pass; session 1 saw exactly this). One command extracts, validates (`stepfunctions validate-state-machine-definition` is non-mutating) and summarizes; it stops at the first failure.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; PLAN_FILE="$(ls ${SCD}/plans/p15-ab-a-up/course-*.tfplan)"; ( terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course show -json "${PLAN_FILE}" | python3 -I -c 'import json,sys; p=json.load(sys.stdin); rc=[r for r in p["resource_changes"] if r["type"]=="aws_sfn_state_machine"]; assert len(rc)==1, "expected exactly one aws_sfn_state_machine change, found %d" % len(rc); c=rc[0]["change"]; assert not (c.get("after_unknown") or {}).get("definition"), "the definition is unknown until apply: this check cannot run before the apply"; open(sys.argv[1], "w").write(c["after"]["definition"]); print("definition written", len(c["after"]["definition"]), "characters")' ${SCD}/logs/phase15-ab-a-sfn-definition-plan.json && AWS_PROFILE=scholarchain aws stepfunctions validate-state-machine-definition --definition file://${SCD}/logs/phase15-ab-a-sfn-definition-plan.json --type STANDARD --region us-east-2 --output json && python3 -I -c 'import re,sys; s=open(sys.argv[1]).read(); print("definition_chars", len(s)); [print(n, re.findall(p, s)) for n, p in (("compute_limits", r"\x22ComputeLimits\x22\s*:\s*\{[^}]*\}"), ("idle_timeout", r"\x22IdleTimeout\x22\s*:\s*\d+"), ("wait_states", r"\x22Type\x22\s*:\s*\x22Wait\x22"), ("wait_seconds", r"\x22Seconds\x22\s*:\s*\d+"), ("timeouts", r"\x22TimeoutSeconds\x22\s*:\s*\d+"), ("run_loader_state", r"\x22RunLoader\x22\s*:"), ("after_terminate_state", r"\x22AfterTerminate\x22\s*:"), ("profile_a_baseline", r"a-baseline"), ("profile_b_burst", r"b-burst"))]' ${SCD}/logs/phase15-ab-a-sfn-definition-plan.json ) 2>&1 | tee ${SCD}/logs/phase15-ab-a-sfn-plan-check.log | tail -n 60; echo "exit: $?"
```

Expected either "the definition is unknown until apply" (then go on; 2.5 is the check), or: `"result": "OK"` (field names UNVERIFIED) and the A column of the rendered-values table (`compute_limits []`, `idle_timeout` 900, `wait_states []`, `after_terminate_state []`, one `run_loader_state`, `profile_a_baseline` present, `profile_b_burst []`, eight `timeouts` 7200, 1440, 300, 4800, 4800, 900, 600, 900 in any order). Stop: a mismatch (before the apply: report and wait).

### 2.4 Deploy A (the session-up apply; T0 starts at 2.4R)

`deploy.sh` applies the saved plan (never `-auto-approve`), logs in to ECR, builds and pushes both images for the HEAD tag (new tag, so built not reused) and checks the revision. Docker must be running. Known process gap (Phase 14 README): the apply runs before the image push, so for a short time the task definitions name a tag not yet in ECR; services are at desired count 0.

2.4P print the deploy digests:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PLAN_FILE="$(ls ${SCD}/plans/p15-ab-a-up/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --print-digest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --plan-file "${PLAN_FILE}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-ab-a-up 2>&1 | tee ${SCD}/logs/phase15-ab-a-deploy-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

2.4W write the record (the human approves the plan digest, script revision, account, Region, cost ceiling 20 and the rollback below; after reading 2.2, with `IAM_CHANGES: none`, and after the 0.0 acknowledgement of the 7 changes):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; REC=${SCD}/approvals/p15-ab-a-deploy.env; dg() { awk -F= -v k="${2}" '$1 == k { print $2; exit }' "${1}"; }; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; EXP="$(date -u -v+8H +%Y-%m-%dT%H:%M:%SZ)"; ( (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=deploy" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=20" "PLAN_DIGEST=$(dg ${SCD}/logs/phase15-ab-a-plan.log PLAN_DIGEST)" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-ab-a-deploy-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-ab-a-deploy-digest.log ARGS_DIGEST)" "GIT_SHA=$(git rev-parse HEAD)" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > "${REC}") && sed "s/^ACCOUNT_ID=.*/ACCOUNT_ID=<masked>/" "${REC}" ) 2>&1 | tee ${SCD}/logs/phase15-ab-a-deploy-record.log | tail -n 60; echo "exit: $?"
```

2.4D dry run (record, plan binding, clean tree; consumes nothing):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PLAN_FILE="$(ls ${SCD}/plans/p15-ab-a-up/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --dry-run --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-ab-a-deploy.env --plan-file "${PLAN_FILE}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-ab-a-up 2>&1 | tee ${SCD}/logs/phase15-ab-a-deploy-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `CLEAN_TREE`, `APPROVAL_VERIFIED action=deploy ...`, `APPROVAL_NOT_CONSUMED dry-run`, `DRY_RUN_COMPLETE`, `exit: 0`, no `APPROVAL_SKIPPED_FIELDS` naming `PLAN_DIGEST` or `ARGS_DIGEST`. Stop: any refusal (still before the apply).

2.4R the apply. This is the first billable step; write down the UTC time printed as `S3_CLOCK_START_UTC` (it is T0; it is not in the log).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PLAN_FILE="$(ls ${SCD}/plans/p15-ab-a-up/course-*.tfplan)"; date -u +"S3_CLOCK_START_UTC=%Y-%m-%dT%H:%M:%SZ"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-ab-a-deploy.env --plan-file "${PLAN_FILE}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-ab-a-up 2>&1 | tee ${SCD}/logs/phase15-ab-a-deploy.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: steps 1 to 12 `ok`, `IMAGE_*` lines for both repositories, `DEPLOY_COMPLETE`, `DEPLOYED_REVISION=<HEAD>`, `exit: 0`; apply counts equal to the plan within the known state-machine plan-versus-apply gap. Review-1 B1, resolved by OD-1: `deploy.sh` runs `verify_revision` (`scripts/lib.sh:941-956`) after the apply and push and requires every ACTIVE `scholarchain-course*` task definition family to use an image ending `:<HEAD>`. A load-client family with the tag `unset` would end every deploy in `REVISION_MISMATCH` after the endpoints already bill. In this session no load-client family exists (the task definition is gated off with the stack), so `verify_revision` sees only `fast`, `slow` and the loader, all at the HEAD tag, as in session 1, and `DEPLOYED_REVISION=<HEAD>` is expected. A `REVISION_MISMATCH` that names a `load-client` or `worker` family means the stack flag leaked past 2.2: that is a failed apply, not a smoke finding (the endpoints bill). Stop (S2): any failure; do not re-run the record. Rollback gate: a plan with `session-down.tfvars` (step 5); pushed image tags are immutable and stay. A half-applied plan: run `plan.sh` again with the same overlays to see the drift and report; no second apply without a new plan, a new record and a new human decision.

### 2.5 Deployed definition of A (read-only; the check that always works)

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; SM="$(aws stepfunctions list-state-machines --region us-east-2 --query "stateMachines[?name=='scholarchain-course-pipeline'].stateMachineArn | [0]" --output text)"; (set -C; aws stepfunctions describe-state-machine --state-machine-arn "${SM}" --region us-east-2 --query definition --output text > ${SCD}/logs/phase15-ab-a-sfn-definition-live.json) && python3 -I -c 'import re,sys; s=open(sys.argv[1]).read(); print("definition_chars", len(s)); [print(n, re.findall(p, s)) for n, p in (("compute_limits", r"\x22ComputeLimits\x22\s*:\s*\{[^}]*\}"), ("idle_timeout", r"\x22IdleTimeout\x22\s*:\s*\d+"), ("wait_states", r"\x22Type\x22\s*:\s*\x22Wait\x22"), ("wait_seconds", r"\x22Seconds\x22\s*:\s*\d+"), ("timeouts", r"\x22TimeoutSeconds\x22\s*:\s*\d+"), ("run_loader_state", r"\x22RunLoader\x22\s*:"), ("after_terminate_state", r"\x22AfterTerminate\x22\s*:"), ("profile_a_baseline", r"a-baseline"), ("profile_b_burst", r"b-burst"))]' ${SCD}/logs/phase15-ab-a-sfn-definition-live.json ) 2>&1 | tee ${SCD}/logs/phase15-ab-a-sfn-summary-live.log | tail -n 60; echo "exit: $?"
```

Expected: the A column (including the eight `timeouts`). Stop (S2: no run starts): any mismatch. The definition file holds the account id in ARNs: keep it out of the repository.

### 2.6 A records for the run

Check the clock: A must start no later than T0 + 1 h (S3). Immediately before, re-run command 1.2 (state check) and read it.

2.6W (the human approves the A manifest copy and the A pipeline limits by running this):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; dg() { awk -F= -v k="${2}" '$1 == k { print $2; exit }' "${1}"; }; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; EXP="$(date -u -v+8H +%Y-%m-%dT%H:%M:%SZ)"; ( (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=pipeline" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=20" "PLAN_DIGEST=n/a" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-ab-a-pipeline-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-ab-a-pipeline-digest.log ARGS_DIGEST)" "GIT_SHA=n/a" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > ${SCD}/approvals/p15-ab-a-pipeline.env) && (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=experiment" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=0.75" "PLAN_DIGEST=n/a" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-ab-a-experiment-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-ab-a-experiment-digest.log ARGS_DIGEST)" "GIT_SHA=$(git rev-parse HEAD)" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > ${SCD}/approvals/p15-ab-a-experiment.env) && sed "s/^ACCOUNT_ID=.*/ACCOUNT_ID=<masked>/" ${SCD}/approvals/p15-ab-a-pipeline.env ${SCD}/approvals/p15-ab-a-experiment.env ) 2>&1 | tee ${SCD}/logs/phase15-ab-a-run-records.log | tail -n 60; echo "exit: $?"
```

2.6D (dry runs of both scripts; the pipeline record is read only after the experiment record is consumed, so the pipeline dry run matters most; the verdict is the two `part` exit lines):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; { AWS_PROFILE=scholarchain bash scripts/run-bounded-pipeline.sh --dry-run --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-ab-a-pipeline.env --size medium --max-minutes 70 --spend-ceiling-usd 0.75 --hourly-burn-usd 0.60 --stop-on first-failure,dlq-nonempty --input-file ${SCD}/pipeline-inputs/p15-ab-a.json 2>&1 | tee ${SCD}/logs/phase15-ab-a-pipeline-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g"; echo "part1 exit: $?"; AWS_PROFILE=scholarchain bash scripts/run-experiment.sh --dry-run --manifest ${SCD}/experiments/manifests/p15-ab-a-baseline.manifest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 0.75 --approval-record ${SCD}/approvals/p15-ab-a-experiment.env 2>&1 | tee ${SCD}/logs/phase15-ab-a-experiment-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g"; echo "part2 exit: $?"; } | tail -n 60; echo "exit: $?"
```

Expected: both `APPROVAL_VERIFIED`, `DRY_RUN_COMPLETE`, `part1 exit: 0`, `part2 exit: 0`. Stop (S2): any refusal or non-zero part exit.

### 2.7 Run A (the billable run)

`run-experiment.sh` consumes the experiment record at `start-run`, starts `experiments/a-baseline/run.sh` once under `MAX_MINUTES` 75; that script starts one pipeline execution (70-minute stop, `--stop-on first-failure,dlq-nonempty`), which consumes the pipeline record. Nothing is tuned and nothing is retried. The screen shows nothing until it ends; run the same line in an ordinary terminal if the `!` prompt cannot hold it (UNVERIFIED; Phase 14 ran 87-minute pipelines through this path).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; SCHOLARCHAIN_PIPELINE_INPUT_FILE=${SCD}/pipeline-inputs/p15-ab-a.json SCHOLARCHAIN_PIPELINE_APPROVAL_RECORD=${SCD}/approvals/p15-ab-a-pipeline.env AWS_PROFILE=scholarchain bash scripts/run-experiment.sh --manifest ${SCD}/experiments/manifests/p15-ab-a-baseline.manifest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 0.75 --approval-record ${SCD}/approvals/p15-ab-a-experiment.env 2>&1 | tee ${SCD}/logs/phase15-ab-a-run.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Optional watch (second terminal, read-only; the POLL line every 30 s; review-1 minor 7: the directory is selected by this session's A run id, found inside each run directory's `raw/pipeline-input.json` as in 2.8a, never by newest time, so a session 1 or other `exp-A-*` directory is never picked; repeat the line to poll; it fails until the run directory exists):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; A_RUN="$(grep -o 'run-v1-[0-9a-f-]*' ${SCD}/pipeline-inputs/p15-ab-a.json | head -n 1)"; F="$(grep -l "${A_RUN}" ${SCD}/experiments/runs/exp-A-*/raw/pipeline-input.json | head -n 1)"; if [[ -n "${A_RUN}" && -n "${F}" ]]; then tail -n 5 "${F%/raw/pipeline-input.json}/raw/pipeline-run.log"; else echo "no run directory holds this session's A run id yet: wait and repeat"; false; fi 2>&1 | tee -a ${SCD}/logs/phase15-ab-a-watch.log | tail -n 60; echo "exit: $?"
```

Expected at the end: `RUN_ID=exp-A-<UTC>-<sha8>`, `ENTRY_EXIT=0 FINAL_STATUS=COMPLETED elapsed_s=<about 3,500 to 4,200>`, `exit: 0`; pipeline `FINAL_STATUS=SUCCEEDED`, `NO_ACTIVE_CLUSTER`, `RECORD_COMPLETE=yes`. Stop (S2; see the failure branches): any other final status (`FAILED`, `STOPPED_WALL_CLOCK`, `STOPPED_BY_SCRIPT`, `INTERRUPTED`) is kept and reported, never deleted or edited; `EMR cluster still active after the run` means S4 at once; spend watch: `ESTIMATED_SPEND_USD` above 0.75.

### 2.8 What A left (read-only)

2.8a locate this session's A run directory by the run id inside its pipeline input (so an older `exp-A-*` directory is never picked). The result is a two-line file that later commands read.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; A_RUN="$(grep -o 'run-v1-[0-9a-f-]*' ${SCD}/pipeline-inputs/p15-ab-a.json | head -n 1)"; F="$(grep -l "${A_RUN}" ${SCD}/experiments/runs/exp-A-*/raw/pipeline-input.json | head -n 1)"; if [[ -n "${A_RUN}" && -n "${F}" ]]; then A_DIR="${F%/raw/pipeline-input.json}"; printf 'A_RUN=%s\nA_DIR=%s\n' "${A_RUN}" "${A_DIR}" > ${SCD}/logs/phase15-ab-a-rundir.txt; cat ${SCD}/logs/phase15-ab-a-rundir.txt; else echo "no run directory holds this session's A run id: STOP"; false; fi 2>&1 | tee ${SCD}/logs/phase15-ab-a-rundir-find.log | tail -n 60; echo "exit: $?"
```

Expected: `A_RUN=run-v1-<uuid>` and `A_DIR=/Users/winnguyen/scholarchain-data/experiments/runs/exp-A-<UTC>-<sha8>`, `exit: 0`. Stop: no directory (A did not start; see the failure branches).

2.8b the run result:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; A_DIR="$(awk -F= '$1 == "A_DIR" { print $2; exit }' ${SCD}/logs/phase15-ab-a-rundir.txt)"; (echo "RUN_DIR_NAME=${A_DIR##*/}"; echo "--run-record.env"; grep -E '^(RUN_ID|EXPERIMENT_ID|GIT_SHA|DATASET_SNAPSHOT|MANIFEST_SHA256|START_UTC|END_UTC|ELAPSED_SECONDS|EXIT_STATUS|FINAL_STATUS|ESTIMATED_SPEND_USD|SPEND_ESTIMATE_EXCEEDED|METRIC_EXPORT_FAILURES|ENTRY_PROCESSES_STRAY|ENTRY_PROCESSES_REMAINING|RECORD_COMPLETE)=' ${A_DIR}/run-record.env; echo "--pipeline record"; grep -E '^(RUN_ID|SIZE|MAX_MINUTES|START_UTC|END_UTC|FINAL_STATUS|ELAPSED_MINUTES|ESTIMATED_SPEND_USD|RECORD_COMPLETE)=' ${A_DIR}/raw/pipeline/pipeline-*.record.env; echo "--events (last 20)"; tail -n 20 ${A_DIR}/raw/events.jsonl) 2>&1 | tee ${SCD}/logs/phase15-ab-a-result.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

2.8c the on-node reports of run A (read-only S3 get; `--only-show-errors` keeps bucket names out of the log). Key layout `reports/runs/<run id>/`: `run-record.env`, `run-record.json`, `spark-run-manifest.json`, `export-manifest.json`, `mapreduce-counters/`, `spark-events/` (UNVERIFIED by a run for A: the README does not give this command).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; A_RUN="$(awk -F= '$1 == "A_RUN" { print $2; exit }' ${SCD}/logs/phase15-ab-a-rundir.txt)"; DATA_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw data_bucket)"; mkdir -p ${SCD}/runs/p15-ab-a && aws s3 cp "s3://${DATA_BUCKET}/reports/runs/${A_RUN}/" ${SCD}/runs/p15-ab-a/reports/ --recursive --only-show-errors --region us-east-2; echo "files: $(find ${SCD}/runs/p15-ab-a/reports -type f | wc -l | tr -d ' ')"; echo "--top level"; ls ${SCD}/runs/p15-ab-a/reports; echo "--mapreduce-counters"; ls ${SCD}/runs/p15-ab-a/reports/mapreduce-counters; echo "--spark-events"; ls -l ${SCD}/runs/p15-ab-a/reports/spark-events; echo "--selected run-record.env keys (names UNVERIFIED)"; grep -E '^(MAPREDUCE|MAPREDUCE_JOB_COUNT|SPARK_ON_YARN|SPARK_MASTER_USED|PREFLIGHT_RESULT|RUN_SCOPE|SPARK_EVENT_LOG_PUBLISHED|SPARK_RUN_MANIFEST_COPY|EXPORT_MANIFEST_COPY|INPUT_LINES_[A-Za-z_]+|STAGE_SECONDS_[A-Z_]+)=' ${SCD}/runs/p15-ab-a/reports/run-record.env ) 2>&1 | tee ${SCD}/logs/phase15-ab-a-reports.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `reports/` holds `run-record.env`, `run-record.json`, `spark-run-manifest.json`, `export-manifest.json`, `mapreduce-counters/` (one file per MapReduce job), `spark-events/` (unless over the 256 MiB scan cap). Keys `MAPREDUCE=yarn`, `SPARK_ON_YARN=client:yes`, `PREFLIGHT_RESULT=ok`. A missing file is a finding for the results, not a runbook stop.

2.8d the course field table (no AWS; `derive-table.py` refuses an existing `--out-dir`):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; A_DIR="$(awk -F= '$1 == "A_DIR" { print $2; exit }' ${SCD}/logs/phase15-ab-a-rundir.txt)"; PIPE_REC="$(ls ${A_DIR}/raw/pipeline/pipeline-*.record.env)"; python3 -I /Users/winnguyen/ScholarChain/experiments/a-baseline/derive-table.py --reports-dir ${SCD}/runs/p15-ab-a/reports --pipeline-record "${PIPE_REC}" --counters-dir ${SCD}/runs/p15-ab-a/reports/mapreduce-counters --spark-eventlog-dir ${SCD}/runs/p15-ab-a/reports/spark-events --out-dir ${SCD}/derived/p15-ab-a-fields 2>&1 | tee ${SCD}/logs/phase15-ab-a-derive.log | tail -n 60; echo "exit: $?"
```

Expected: `FIELDS_DIR=...`, `RECORDED=<n>`, `DERIVED=<n>`, `NOT_RECORDED=<n>`, `exit: 0`. List the NOT_RECORDED fields for the limitations; keep the commit difference from run 6 (P15-2).

2.8e optional capacity collection for A (same read-only collector as B; UNVERIFIED for A):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; A_DIR="$(awk -F= '$1 == "A_DIR" { print $2; exit }' ${SCD}/logs/phase15-ab-a-rundir.txt)"; AWS_PROFILE=scholarchain bash /Users/winnguyen/ScholarChain/experiments/b-burst/collect.sh --run-dir "${A_DIR}" 2>&1 | tee ${SCD}/logs/phase15-ab-a-collect.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `COLLECT_COMPLETE cluster=<id> window=...`, `exit: 0`. A failed required call keeps `collect-failed.<stamp>/` and the command can be repeated.

### 2.9 The two run identifiers for session 2, and `session2.tfvars` (read-only S3, one local file outside the repository)

The services need BOTH identifiers, from A's run (P15-26; `SESSION2.md` section 6a; review 1 finding B1):
- `export_run_id` = the pipeline slug `run-v1-<uuid>`, the S3 directory `semantic-context/runs/<slug>/export/` that the services read (here: the `run_id` of A's pipeline input);
- `graph_run_id` = the export manifest field `sourceRunId`, `run:v1:<uuid>` (the manifest check and the metric pin).

Review-2 MAJOR-1: the two identifiers are independent and normally differ; they do NOT share a UUID, and no check here expects them to. The slug is the operator-supplied `run_id` (`scripts/emr/run-s-pipeline.sh:85`, positional argument 5; its form is enforced by `validate_run_id`, `scripts/emr/lib-emr.sh:372-379`), and the pipeline publishes the export under `semantic-context/runs/$run_id/` (`run-s-pipeline.sh:580`), so the slug is the S3 directory name. `sourceRunId` is a SHA-256-derived ID computed over the inputs (`modules/spark/.../Inputs.scala:113-117`, as in `SESSION2.md` section 6a), not from the slug; in run 6 the two differed. The command reads each from its own source, never derives one from the other and does not compare them.

It writes `/Users/winnguyen/scholarchain-data/session2.tfvars` (outside the repository, never committed) with TWO lines, `export_run_id` and `graph_run_id`, only if both match their expected forms. The file ends up with three lines: per `SESSION2.md` section 6a (OD-2) the third line is `load_client_image_tag = "<git rev-parse HEAD>"`, the 40-hex commit SHA of session 2, and it is written at session 2, not here: section 6a says to write it "after the last commit of the session" with no commit between then and A4, and the repository gets commits after this session (the evidence and results records), so a SHA written here would be stale and `verify_revision` would end session 2's deploys in `REVISION_MISMATCH`. Session 2 appends it (`printf '%s\n' "load_client_image_tag = \"$(git rev-parse HEAD)\"" >> /Users/winnguyen/scholarchain-data/session2.tfvars`, owned by the session 2 runbook, not run here). A two-line file fails session 2's A2 plan validation (the stack needs a 40-hex tag), which is the intended fail-closed behavior. The A and B session does not use the file at all. The manifest `tables` listing is printed as well (count and names; the field names `tables` and `sourceRunId` are from `SESSION2.md`, UNVERIFIED by a run).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; A_RUN="$(awk -F= '$1 == "A_RUN" { print $2; exit }' ${SCD}/logs/phase15-ab-a-rundir.txt)"; DATA_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw data_bucket)"; echo "EXPORT_RUN_ID_SLUG=${A_RUN}"; aws s3 cp "s3://${DATA_BUCKET}/semantic-context/runs/${A_RUN}/export/export-manifest.json" - --region us-east-2 | python3 -I -c 'import json,sys; m=json.load(sys.stdin); t=m.get("tables"); names=sorted(t) if isinstance(t,dict) else sorted(str(x.get("table") or x.get("name")) for x in (t or [])); print("sourceRunId=" + str(m.get("sourceRunId"))); print("tables_in_manifest=%d" % len(names)); print("tables=" + ",".join(names))' > ${SCD}/logs/phase15-ab-a-export-manifest-fields.txt; cat ${SCD}/logs/phase15-ab-a-export-manifest-fields.txt; SRC="$(awk -F= '$1 == "sourceRunId" { print $2; exit }' ${SCD}/logs/phase15-ab-a-export-manifest-fields.txt)"; echo "GRAPH_RUN_ID_SOURCE_RUN_ID=${SRC}"; if [[ "${A_RUN}" =~ ^run-v1-[0-9a-f-]{36}$ && "${SRC}" =~ ^run:v1:[0-9a-f-]{36}$ ]]; then (set -C; printf '%s\n' "export_run_id = \"${A_RUN}\"" "graph_run_id = \"${SRC}\"" > ${SCD}/session2.tfvars) && echo "--session2.tfvars (written, outside the repository)" && cat ${SCD}/session2.tfvars; else echo "SLUG_OR_SOURCERUNID_HAS_AN_UNEXPECTED_FORM: STOP and report; nothing written"; false; fi ) 2>&1 | tee ${SCD}/logs/phase15-ab-a-run-ids.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `EXPORT_RUN_ID_SLUG=run-v1-<uuid>`, `sourceRunId=run:v1:<uuid>` (a different UUID from the slug is normal and is not a finding), `tables_in_manifest=17`, the 17 names, two lines of `session2.tfvars`, `exit: 0`. Stop: a failed `aws s3 cp` (the A run did not publish its export: by `run-s-pipeline.sh:85, 580` the slug is the S3 directory name, so a missing object means the publish stage did not run or the key under the slug is not `export/export-manifest.json`; report and do not guess another name), an unexpected form, or `file exists` for `session2.tfvars` (an earlier file: the human moves it aside by hand and reruns; never overwrite silently). The file is for session 2: `--extra-var-file /Users/winnguyen/scholarchain-data/session2.tfvars` after `services-session.tfvars` in the A2 and A3 plans and after `session-down-services.tfvars` in A4 (`SESSION2.md` section 6a), once its third line exists. The values are identifiers and a commit hash, not secrets.

### 2.10 The 17 tables, their files and bytes, against the runtime limits (read-only)

Limits from `SESSION2.md` and the WO-7c report: the runtime refuses (exit 3 `READ_MODELS_FAILED`) an export over 134,217,728 bytes (128 MiB) of export files or over 1,000 objects (`ExportLimits.Default`). After WO-7c the services download and count ONLY `_SUCCESS`, the manifest and the nine tables they use (the byte bound counts those listed bytes; the object bound counts the whole listing): `researcher-metrics`, `nodes-researcher`, `nodes-work` and the six SP-2 tables `path-evidence`, `components`, `component-membership`, `communities`, `community-membership`, `community-cross-edges`. The other eight tables (`semantic-summaries`, `nodes-venue`, `nodes-topic`, `nodes-institution`, `edges-authored`, `edges-published-in`, `edges-coauthored`, `edge-support`) are listed and counted as objects but not loaded. The earlier local export (11 tables, 26 files, 324,158,336 bytes, P15-25) predates SP-2; the six new tables' size is unknown until this run (SP-2 report, "Open").

First the listing, then the table-by-table byte and object check (it also confirms that the manifest and `_SUCCESS` are present and that all 17 tables have objects), then the row check.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; A_RUN="$(awk -F= '$1 == "A_RUN" { print $2; exit }' ${SCD}/logs/phase15-ab-a-rundir.txt)"; DATA_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw data_bucket)"; aws s3 ls "s3://${DATA_BUCKET}/semantic-context/runs/${A_RUN}/export/" --recursive --summarize --region us-east-2 > ${SCD}/logs/phase15-ab-a-export-ls.txt; echo "listing exit=$?"; tail -n 3 ${SCD}/logs/phase15-ab-a-export-ls.txt ) 2>&1 | tee ${SCD}/logs/phase15-ab-a-export-listing.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; python3 -I -c 'import sys,collections; need=["researcher-metrics","nodes-researcher","nodes-work","path-evidence","components","component-membership","communities","community-membership","community-cross-edges"]; all17=["semantic-summaries","nodes-work","researcher-metrics","nodes-researcher","nodes-venue","nodes-topic","nodes-institution","edges-authored","edges-published-in","edges-coauthored","edge-support","path-evidence","components","component-membership","communities","community-membership","community-cross-edges"]; rows=[r for r in (l.split() for l in open(sys.argv[1])) if len(r)==4 and "/export/" in r[3]]; parts=[(r[3].split("/export/",1)[1].split("/"), int(r[2])) for r in rows]; fc=collections.Counter(); bc=collections.Counter(); [(fc.update([p[0]]), bc.update({p[0]: s})) for p, s in parts if len(p) > 1]; roots=sorted(p[0] for p, s in parts if len(p) == 1); rootb=sum(s for p, s in parts if len(p) == 1); tf=sum(fc.values())+len(roots); tb=sum(bc.values())+rootb; nf=sum(fc[t] for t in need); nbt=sum(bc[t] for t in need); nb=nbt+rootb; miss=[t for t in all17 if fc[t]==0]; extra=sorted(t for t in fc if t not in all17); [print("%-24s files=%-4d bytes=%-12d %s" % (t, fc[t], bc[t], "LOADED" if t in need else "listed-only")) for t in sorted(set(fc)|set(all17))]; print("ROOT_OBJECTS", roots, "bytes", rootb); print("TABLES_WITH_OBJECTS", len(all17)-len(miss), "of 17; missing", miss, "; unexpected", extra); print("OBJECTS_TOTAL", tf, "limit 1000", "OK" if tf<=1000 else "OVER"); print("BYTES_TOTAL_ALL_TABLES", tb, "(%.1f MiB)" % (tb/1048576.0), "(information: not the loaded bytes)"); print("BYTES_NINE_TABLES", nbt, "ROOT_BYTES", rootb, "SUM_NINE_PLUS_ROOT", nb, "(%.1f MiB)" % (nb/1048576.0), "limit 134217728", "OK" if nb<=134217728 else "OVER", "; files in the nine", nf); print("NEAR_LIMIT=" + ("WITHIN 1 MiB: not a pass, ask storage-engineer whether the manifest and _SUCCESS count" if abs(134217728-nb) < 1048576 else "no")); ok=(not miss) and tf<=1000 and nb<=134217728 and "_SUCCESS" in roots and "export-manifest.json" in roots; print("EXPORT_SIZE_CHECK=" + ("PASS" if ok else "FAIL: session 2 is blocked on this; BLOCKED-ON storage-engineer (limit) or service-engineer (settings), not an infra change")); sys.exit(0 if ok else 1)' ${SCD}/logs/phase15-ab-a-export-ls.txt 2>&1 | tee ${SCD}/logs/phase15-ab-a-export-size.log | tail -n 60; echo "exit: $?"
```

Expected: 17 table lines each with at least one file, `ROOT_OBJECTS ['_SUCCESS', 'export-manifest.json']`, `TABLES_WITH_OBJECTS 17 of 17; missing []`, `OBJECTS_TOTAL` at most 1000, `SUM_NINE_PLUS_ROOT` at most 134,217,728, `EXPORT_SIZE_CHECK=PASS`. Review-1 minor 1: the sum now counts the root bytes (`_SUCCESS` and `export-manifest.json`) together with the nine loaded tables. The WO-7c report says the byte bound counts "those listed bytes" of the downloaded set, which includes both root objects; whether the code counts them is UNVERIFIED, so counting them is the conservative reading (a few KiB at most). Information for the heap and the sizing: the loaded bytes (about 56 MiB for the three old tables: `researcher-metrics` 42.6 MB, `nodes-researcher` 10.5 MB, `nodes-work` 2.9 MB in the local 11-table export, plus the six new tables) and the whole-export total (above 128 MiB is expected and is not a failure after WO-7c).

The row bound (review-1 minor 1; `ExportLimits.Default`: 250,000 rows in one table, 1,000,000 rows in all, `AwsReadModels.scala:36-37`) is checked from the manifest's per-table row counts. It requires 17 tables with integer row counts, the largest table at most 250,000 rows (all 17 tables, conservative) and the nine loaded tables at most 1,000,000 rows in all; the all-17 sum is printed as information because which tables the code counts is UNVERIFIED. The manifest shape `tables.<name>.rows` (as a mapping or as a list of objects with `table` or `name`) is UNVERIFIED by a run: an unknown row count fails closed.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; A_RUN="$(awk -F= '$1 == "A_RUN" { print $2; exit }' ${SCD}/logs/phase15-ab-a-rundir.txt)"; DATA_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw data_bucket)"; aws s3 cp "s3://${DATA_BUCKET}/semantic-context/runs/${A_RUN}/export/export-manifest.json" - --region us-east-2 | python3 -I -c 'import json,sys; need=["researcher-metrics","nodes-researcher","nodes-work","path-evidence","components","component-membership","communities","community-membership","community-cross-edges"]; m=json.load(sys.stdin); t=m.get("tables"); rows=({k:(v.get("rows") if isinstance(v,dict) else None) for k,v in t.items()} if isinstance(t,dict) else {str(x.get("table") or x.get("name")):x.get("rows") for x in (t or [])}); unknown=sorted(k for k,v in rows.items() if not isinstance(v,int)); known={k:v for k,v in rows.items() if isinstance(v,int)}; [print("%-24s rows=%s %s" % (k, rows[k], "LOADED" if k in need else "listed-only")) for k in sorted(rows)]; mx=max(known.values()) if known else -1; s9=sum(v for k,v in known.items() if k in need); s17=sum(known.values()); print("TABLES_IN_MANIFEST", len(rows), "rows_unknown", unknown); print("MAX_ROWS_ONE_TABLE", mx, "limit 250000", "OK" if 0<=mx<=250000 else "OVER_OR_UNKNOWN"); print("ROWS_NINE_LOADED", s9, "limit 1000000", "OK" if s9<=1000000 else "OVER"); print("ROWS_ALL_17 (information)", s17); ok=(len(rows)==17 and not unknown and 0<=mx<=250000 and s9<=1000000); print("EXPORT_ROWS_CHECK=" + ("PASS" if ok else "FAIL: session 2 is blocked on this; BLOCKED-ON storage-engineer (limit) or service-engineer (settings), not an infra change")); sys.exit(0 if ok else 1)' ) 2>&1 | tee ${SCD}/logs/phase15-ab-a-export-rows.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: 17 table lines with integer `rows=`, `MAX_ROWS_ONE_TABLE` at most 250000 `OK`, `ROWS_NINE_LOADED` at most 1000000 `OK`, `EXPORT_ROWS_CHECK=PASS`, `exit: 0`. "2.10 PASS" means both `EXPORT_SIZE_CHECK=PASS` and `EXPORT_ROWS_CHECK=PASS`; 4.2 needs it. Stop for session 2 (not for this session): `FAIL`. Record it as the P15-25 follow-up; B is not affected.

### 2.11 Gate between A and B

The human reads 2.8b (and 2.9, 2.10 may still be running or may be done in a second terminal while B runs). Proceed to B only if A finished `COMPLETED` with pipeline `SUCCEEDED`, `NO_ACTIVE_CLUSTER` and the clock allows (B must start by T0 + 3 h 15 min). If A did not succeed, the default is step 5 (S2), with the failure branches below; the human may decide in the chat, with an end time, to continue to B when the cause cannot affect B (the A and B runs share the on-node scripts, so a script defect likely fails B too). The next billable item is the B switch apply; the endpoints stay up meanwhile (0.12 USD/h).

---

## Step 3. Experiment B rerun (managed scaling, P15-23 profile)

Goal of the rerun (P15-23): scale-in. The failed session 1 run already showed the scale-out (core 1 to 3 at 05:43:58Z, `collected/emr-list-instances.json`); with the new profile (8 reducers, 256 KiB splits, 2 GiB map and 3 GiB reduce containers, about 800 containers instead of 1,590) a scale-in may occur before the Wait, because the core nodes go idle during entity resolution, Spark and the export on the primary. The Hadoop stage limit stays at the literal `timeout -k 30 1500` of `run-s-pipeline.sh` (no profile key); the diagnosis estimates the stage at about 1,000 s (range 800 to 1,400) from one run's 15 s per container (`b-diagnosis-hadoop-engineer.md`). Risks, UNVERIFIED: that rate and the intermediate sizes are estimates; the Spark `local[4]` time with 8 shuffle partitions is unmeasured (over about 1,700 s breaks the 88-minute cap); whether the lower pending ratio still triggers scale-out (policy internals unpublished). B is one run: nothing is tuned during it and a failed B is not retried in this session. B's evidence comes from the MapReduce stage only (P15-12); the P15-20 limitation (the 88-minute stop leaves less than the up-to-300 s teardown check) applies.

Every stop from 3.4R on is under S2. Stops in 3.1 to 3.4D change nothing billable beyond the endpoints already up: report and wait.

### 3.1 Plan B (the switch from `baseline-s` to `elasticity-s`)

Layering: `course.tfvars`, `experiment-size-s.tfvars`, `elasticity-s.tfvars` last. `elasticity-s.tfvars` turns Neptune and Bedrock off, sets `service_desired_count = 0`, core 1 with scaling cap, the 900 s Wait, `emr_idle_timeout_seconds = 1800`, the 4200 s step cap, `pipeline_skip_loader = true` and `pipeline_profile = "b-burst"`.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ENVDIR=/Users/winnguyen/ScholarChain/infra/terraform/environments/course; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; AWS_PROFILE=scholarchain bash scripts/plan.sh --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --extra-var-file ${ENVDIR}/experiment-size-s.tfvars --extra-var-file ${ENVDIR}/elasticity-s.tfvars --out-dir ${SCD}/plans/p15-ab-b-switch 2>&1 | tee ${SCD}/logs/phase15-ab-b-plan.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected (infra-engineer, `w2rb4-infra-engineer.md` and `SESSION2.md` section 5a; read from the code, UNVERIFIED until this plan runs): exactly `0 to add, 1 to change, 0 to destroy`, and `IAM_CHANGES:` followed on the next line by `  none`: `module.workflow.aws_sfn_state_machine.pipeline[0]` updated in place (Wait state 900 s, `ManagedScalingPolicy` with `ComputeLimits` 1 / 3 / 3 / 3, `IdleTimeout` 1800, `TimeoutSeconds` 4200 for both step states, the `b-burst` profile as the sixth step argument, no `AfterTerminate`), nothing else: same image tag, so no task definition, service or log group change. `PLAN_EXIT=2`, `STATEFUL_CHANGE=no`, `PLAN_DIGEST=<hex>`, `exit: 0`. The core instance group is in the `CreateCluster` parameters (no EMR cluster exists in Terraform).

### 3.2 Read plan B

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ( LOG=${SCD}/logs/phase15-ab-b-plan.log; ACT="$(sed '/^PLAN_SUMMARY_BEGIN/,$d' ${LOG} | grep -nE '^[[:space:]]*# .*(will be created|will be destroyed|must be replaced|will be replaced|will be updated in-place)')"; echo "--resource actions"; printf '%s\n' "${ACT}"; echo "--summary"; sed -n '/^PLAN_SUMMARY_BEGIN/,/^PLAN_SUMMARY_END/p' ${LOG}; echo "--meta"; grep -E '^(GIT_SHA|DIRTY|STATEFUL_CHANGE|PLAN_EXIT|CPU_ARCHITECTURE|IMAGE_TAG)=' ${SCD}/plans/p15-ab-b-switch/course-*.tfplan.meta; echo "--iam (expect IAM_CHANGES: none)"; grep -A1 -x 'IAM_CHANGES:' ${LOG}; echo "--terraform counts (expect 0 to add, 1 to change, 0 to destroy)"; grep -E '[0-9]+ to add, [0-9]+ to change, [0-9]+ to destroy' ${LOG}; FORBID='aws_iam_|aws_lb|aws_alb|aws_appautoscaling_|aws_ecs_service\.worker|aws_ecs_service\.this\["(worker|query)"\]|aws_ecr_|load-client|load_client|task_definition\.worker|aws_cloudwatch_dashboard|aws_cloudwatch_metric_alarm|aws_ecs_cluster|aws_security_group|aws_neptune|bedrock|aws_s3_|aws_dynamodb_|aws_s3vectors_|aws_secretsmanager_|log_group\.this\["(query|worker)"\]'; BAD="$(printf '%s\n' "${ACT}" | grep -E "${FORBID}")"; DC="$(grep -E 'desired_count.*[1-9]' ${LOG})"; echo "--forbidden addresses (expect none)"; printf '%s\n' "${BAD}"; echo "--desired_count above 0 (expect none; the plan log may not print attribute lines, UNVERIFIED)"; printf '%s\n' "${DC}"; N_CR="$(printf '%s\n' "${ACT}" | grep -c 'will be created')"; N_RP="$(printf '%s\n' "${ACT}" | grep -cE 'must be replaced|will be replaced')"; N_UP="$(printf '%s\n' "${ACT}" | grep -c 'will be updated in-place')"; N_DS="$(printf '%s\n' "${ACT}" | grep -c 'will be destroyed')"; N_SM="$(printf '%s\n' "${ACT}" | grep -cE 'aws_sfn_state_machine\.pipeline.*will be updated in-place')"; echo "ACTION_LINES created=${N_CR} replaced=${N_RP} updated=${N_UP} destroyed=${N_DS} state_machine_updated=${N_SM} (expect 0 0 1 0 1)"; if [[ -z "${BAD}" && -z "${DC}" && "${N_CR}" == "0" && "${N_RP}" == "0" && "${N_UP}" == "1" && "${N_DS}" == "0" && "${N_SM}" == "1" ]] && grep -qE '0 to add, 1 to change, 0 to destroy' ${LOG} && grep -A1 -x 'IAM_CHANGES:' ${LOG} | tail -n 1 | grep -qx '  none'; then echo "PLAN_B_SWITCH_CHECK=PASS"; else echo "PLAN_B_SWITCH_CHECK=FAIL: STOP (anything beyond the one state machine update differs from SESSION2.md section 5a)"; false; fi ) 2>&1 | tee ${SCD}/logs/phase15-ab-b-plan-actions.log | tail -n 60; echo "exit: $?"
```

The command ends with the automated verdict `PLAN_B_SWITCH_CHECK` (same shape as 2.2: exactly one action line, the state machine updated in place; counts line `0 to add, 1 to change, 0 to destroy`; `IAM_CHANGES:` then `  none`; no forbidden address; no `desired_count` above 0). The UNVERIFIED notes of 2.2 on the log lines apply.

Stop conditions (nothing applied yet; report and wait; the endpoints are up, so decide within 15 minutes and say so in the chat): anything beyond the state machine change in place, in particular any add, any replacement, any destroy, a task definition, service or log group change (the image tag is unchanged); any `aws_iam_*` line or any `IAM_CHANGES` other than `none`; `aws_lb*`, `aws_appautoscaling_*`, `aws_ecs_service.worker` or `["query"]`, the load-client ECR repository or task definition, a `containerInsights` (`aws_ecs_cluster`) change, the scaling dashboard, an alarm, or a service with desired count above 0; any Neptune resource or `bedrock-runtime` endpoint; any destroy or replacement of a bucket, table, vector bucket, index, repository or secret (`STATEFUL_CHANGE=yes`); `DIRTY=yes`; `GIT_SHA` not equal to the HEAD of 0.7; `CPU_ARCHITECTURE` other than `X86_64`.

### 3.3 Rendered state machine of plan B (read-only)

The image tag exists (A-up pushed it) and the plan changes only the state machine, so the definition may be known now. Same one-command shape as 2.3, with the B file names.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; PLAN_FILE="$(ls ${SCD}/plans/p15-ab-b-switch/course-*.tfplan)"; ( terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course show -json "${PLAN_FILE}" | python3 -I -c 'import json,sys; p=json.load(sys.stdin); rc=[r for r in p["resource_changes"] if r["type"]=="aws_sfn_state_machine"]; assert len(rc)==1, "expected exactly one aws_sfn_state_machine change, found %d" % len(rc); c=rc[0]["change"]; assert not (c.get("after_unknown") or {}).get("definition"), "the definition is unknown until apply: this check cannot run before the apply"; open(sys.argv[1], "w").write(c["after"]["definition"]); print("definition written", len(c["after"]["definition"]), "characters")' ${SCD}/logs/phase15-ab-b-sfn-definition-plan.json && AWS_PROFILE=scholarchain aws stepfunctions validate-state-machine-definition --definition file://${SCD}/logs/phase15-ab-b-sfn-definition-plan.json --type STANDARD --region us-east-2 --output json && python3 -I -c 'import re,sys; s=open(sys.argv[1]).read(); print("definition_chars", len(s)); [print(n, re.findall(p, s)) for n, p in (("compute_limits", r"\x22ComputeLimits\x22\s*:\s*\{[^}]*\}"), ("idle_timeout", r"\x22IdleTimeout\x22\s*:\s*\d+"), ("wait_states", r"\x22Type\x22\s*:\s*\x22Wait\x22"), ("wait_seconds", r"\x22Seconds\x22\s*:\s*\d+"), ("timeouts", r"\x22TimeoutSeconds\x22\s*:\s*\d+"), ("run_loader_state", r"\x22RunLoader\x22\s*:"), ("after_terminate_state", r"\x22AfterTerminate\x22\s*:"), ("profile_a_baseline", r"a-baseline"), ("profile_b_burst", r"b-burst"))]' ${SCD}/logs/phase15-ab-b-sfn-definition-plan.json ) 2>&1 | tee ${SCD}/logs/phase15-ab-b-sfn-plan-check.log | tail -n 60; echo "exit: $?"
```

Expected: either "the definition is unknown until apply" (then rely on 3.5), or `"result": "OK"` and the B column: one `compute_limits` object (Min 1, Max 3, MaxCore 3, MaxOnDemand 3, `UnitType` `Instances`), `idle_timeout` 1800, one Wait with `Seconds` 900, `after_terminate_state []`, one `run_loader_state`, `profile_b_burst` present and `profile_a_baseline []`, and eight `timeouts` 7200, 1440, 300, 4200, 4200, 900, 600, 900 (any order; no 4800). Stop: any difference.

### 3.4 Deploy B (the switch apply; new plan digest, new approval record)

The images exist for the HEAD tag, so `deploy.sh` reports `IMAGE_EXISTS`; Docker is still needed only if it would rebuild. Do not rebuild between 3.4P and 3.4R, and `target/docker-local` must be unchanged since 1.6.

3.4P:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PLAN_FILE="$(ls ${SCD}/plans/p15-ab-b-switch/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --print-digest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --plan-file "${PLAN_FILE}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-ab-b-switch 2>&1 | tee ${SCD}/logs/phase15-ab-b-deploy-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

3.4W:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; REC=${SCD}/approvals/p15-ab-b-deploy.env; dg() { awk -F= -v k="${2}" '$1 == k { print $2; exit }' "${1}"; }; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; EXP="$(date -u -v+8H +%Y-%m-%dT%H:%M:%SZ)"; ( (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=deploy" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=20" "PLAN_DIGEST=$(dg ${SCD}/logs/phase15-ab-b-plan.log PLAN_DIGEST)" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-ab-b-deploy-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-ab-b-deploy-digest.log ARGS_DIGEST)" "GIT_SHA=$(git rev-parse HEAD)" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > "${REC}") && sed "s/^ACCOUNT_ID=.*/ACCOUNT_ID=<masked>/" "${REC}" ) 2>&1 | tee ${SCD}/logs/phase15-ab-b-deploy-record.log | tail -n 60; echo "exit: $?"
```

3.4D:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PLAN_FILE="$(ls ${SCD}/plans/p15-ab-b-switch/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --dry-run --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-ab-b-deploy.env --plan-file "${PLAN_FILE}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-ab-b-switch 2>&1 | tee ${SCD}/logs/phase15-ab-b-deploy-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

3.4R:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PLAN_FILE="$(ls ${SCD}/plans/p15-ab-b-switch/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-ab-b-deploy.env --plan-file "${PLAN_FILE}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-ab-b-switch 2>&1 | tee ${SCD}/logs/phase15-ab-b-deploy.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `IMAGE_EXISTS` for both repositories, `DEPLOY_COMPLETE`, `DEPLOYED_REVISION=<HEAD>`, `exit: 0`; apply counts equal to the plan. Review-1 B1 (resolved by OD-1, see 2.4R): `verify_revision` runs after this apply too and passes because no load-client family exists; a `REVISION_MISMATCH` naming a `load-client` or `worker` family means the stack flag leaked and is a failed apply. Rollback gate for the switch: a plan with `baseline-s.tfvars` back (not needed in this session) or the session-down of step 5; the apply changes only the state machine definition. Stop (S2): any failure; the record is consumed.

### 3.5 Deployed definition of B (read-only)

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; SM="$(aws stepfunctions list-state-machines --region us-east-2 --query "stateMachines[?name=='scholarchain-course-pipeline'].stateMachineArn | [0]" --output text)"; (set -C; aws stepfunctions describe-state-machine --state-machine-arn "${SM}" --region us-east-2 --query definition --output text > ${SCD}/logs/phase15-ab-b-sfn-definition-live.json) && python3 -I -c 'import re,sys; s=open(sys.argv[1]).read(); print("definition_chars", len(s)); [print(n, re.findall(p, s)) for n, p in (("compute_limits", r"\x22ComputeLimits\x22\s*:\s*\{[^}]*\}"), ("idle_timeout", r"\x22IdleTimeout\x22\s*:\s*\d+"), ("wait_states", r"\x22Type\x22\s*:\s*\x22Wait\x22"), ("wait_seconds", r"\x22Seconds\x22\s*:\s*\d+"), ("timeouts", r"\x22TimeoutSeconds\x22\s*:\s*\d+"), ("run_loader_state", r"\x22RunLoader\x22\s*:"), ("after_terminate_state", r"\x22AfterTerminate\x22\s*:"), ("profile_a_baseline", r"a-baseline"), ("profile_b_burst", r"b-burst"))]' ${SCD}/logs/phase15-ab-b-sfn-definition-live.json ) 2>&1 | tee ${SCD}/logs/phase15-ab-b-sfn-summary-live.log | tail -n 60; echo "exit: $?"
```

Expected: the B column. Stop (S2: no run starts): any difference.

### 3.6 B records for the run (new digests, new records)

Check the clock: B must start no later than T0 + 3 h 15 min (S3). Re-run command 1.2 (state check) immediately before and read it (A's cluster must be gone). Then write, dry-run and run, with the NEW B digests of 1.5B. The B manifest binds `MAX_MINUTES=90`; the pipeline stops at 88 minutes.

3.6W:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; dg() { awk -F= -v k="${2}" '$1 == k { print $2; exit }' "${1}"; }; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; EXP="$(date -u -v+8H +%Y-%m-%dT%H:%M:%SZ)"; ( (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=pipeline" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=20" "PLAN_DIGEST=n/a" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-ab-b-pipeline-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-ab-b-pipeline-digest.log ARGS_DIGEST)" "GIT_SHA=n/a" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > ${SCD}/approvals/p15-ab-b-pipeline.env) && (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=experiment" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=2.40" "PLAN_DIGEST=n/a" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-ab-b-experiment-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-ab-b-experiment-digest.log ARGS_DIGEST)" "GIT_SHA=$(git rev-parse HEAD)" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > ${SCD}/approvals/p15-ab-b-experiment.env) && sed "s/^ACCOUNT_ID=.*/ACCOUNT_ID=<masked>/" ${SCD}/approvals/p15-ab-b-pipeline.env ${SCD}/approvals/p15-ab-b-experiment.env ) 2>&1 | tee ${SCD}/logs/phase15-ab-b-run-records.log | tail -n 60; echo "exit: $?"
```

3.6D (the verdict is the two `part` exit lines):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; { AWS_PROFILE=scholarchain bash scripts/run-bounded-pipeline.sh --dry-run --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-ab-b-pipeline.env --size medium --max-minutes 88 --spend-ceiling-usd 2.30 --hourly-burn-usd 1.55 --stop-on first-failure,dlq-nonempty --input-file ${SCD}/pipeline-inputs/p15-ab-b.json 2>&1 | tee ${SCD}/logs/phase15-ab-b-pipeline-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g"; echo "part1 exit: $?"; AWS_PROFILE=scholarchain bash scripts/run-experiment.sh --dry-run --manifest ${SCD}/experiments/manifests/p15-ab-b-burst.manifest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 2.40 --approval-record ${SCD}/approvals/p15-ab-b-experiment.env 2>&1 | tee ${SCD}/logs/phase15-ab-b-experiment-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g"; echo "part2 exit: $?"; } | tail -n 60; echo "exit: $?"
```

Expected: both `APPROVAL_VERIFIED`, `DRY_RUN_COMPLETE`, `part1 exit: 0`, `part2 exit: 0`. Stop (S2): any refusal or non-zero part exit.

### 3.7 Run B (the billable run)

`experiments/b-burst/run.sh` starts one pipeline execution (88-minute stop) with the `b-burst` profile: MapReduce on HDFS across the core nodes, Spark and export as `local[4]` on the primary (P15-12), then the 900 s Wait with the cluster idle so a scale-in can happen, then terminate. Limitations on the record, as written: P15-12, P15-20, P15-11.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; SCHOLARCHAIN_PIPELINE_INPUT_FILE=${SCD}/pipeline-inputs/p15-ab-b.json SCHOLARCHAIN_PIPELINE_APPROVAL_RECORD=${SCD}/approvals/p15-ab-b-pipeline.env AWS_PROFILE=scholarchain bash scripts/run-experiment.sh --manifest ${SCD}/experiments/manifests/p15-ab-b-burst.manifest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 2.40 --approval-record ${SCD}/approvals/p15-ab-b-experiment.env 2>&1 | tee ${SCD}/logs/phase15-ab-b-run.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Optional, read-only, second terminal (the node counts change; replace `REPLACE_WITH_CLUSTER_ID` with the id the first command prints after `CreateCluster`; review-1 minor 5: pipefail, `tee -a` and `tail -n 60` on each, repeat a line to poll):

```
set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; aws emr list-clusters --active --region us-east-2 --query 'Clusters[].[Id,Name,Status.State]' --output text ) 2>&1 | tee -a ${SCD}/logs/phase15-ab-b-watch-clusters.log | tail -n 60; echo "exit: $?"
```

```
set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; CLUSTER_ID="REPLACE_WITH_CLUSTER_ID"; date -u +%H:%M:%SZ; aws emr list-instance-groups --cluster-id "${CLUSTER_ID}" --region us-east-2 --query 'InstanceGroups[].[InstanceGroupType,RequestedInstanceCount,RunningInstanceCount,Status.State]' --output text ) 2>&1 | tee -a ${SCD}/logs/phase15-ab-b-watch-groups.log | tail -n 60; echo "exit: $?"
```

Session 1 found that the installed AWS CLI has no `emr list-instance-groups` ("invalid choice", `experiments/b-burst/README.md`); if it is rejected, use the README's form:

```
set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; CLUSTER_ID="REPLACE_WITH_CLUSTER_ID"; date -u +%H:%M:%SZ; aws emr describe-cluster --cluster-id "${CLUSTER_ID}" --region us-east-2 --query 'Cluster.InstanceGroups[].[InstanceGroupType,RequestedInstanceCount,RunningInstanceCount,Status.State]' --output text ) 2>&1 | tee -a ${SCD}/logs/phase15-ab-b-watch-groups.log | tail -n 60; echo "exit: $?"
```

The pipeline's own poll line (review-1 minor 7: selected by this session's B run id inside each run directory, never by newest time; it fails until the run directory exists):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; B_RUN="$(grep -o 'run-v1-[0-9a-f-]*' ${SCD}/pipeline-inputs/p15-ab-b.json | head -n 1)"; F="$(grep -l "${B_RUN}" ${SCD}/experiments/runs/exp-B-*/raw/pipeline-input.json | head -n 1)"; if [[ -n "${B_RUN}" && -n "${F}" ]]; then tail -n 5 "${F%/raw/pipeline-input.json}/raw/pipeline-run.log"; else echo "no run directory holds this session's B run id yet: wait and repeat"; false; fi 2>&1 | tee -a ${SCD}/logs/phase15-ab-b-watch.log | tail -n 60; echo "exit: $?"
```

The `REPLACE_WITH_CLUSTER_ID` placeholders are OPEN and not checked by the commands (read-only; a wrong id only makes the read fail).

Expected at the end: `ENTRY_EXIT=0 FINAL_STATUS=COMPLETED`, pipeline `SUCCEEDED`, `NO_ACTIVE_CLUSTER`, `RECORD_COMPLETE=yes`, `exit: 0`. Stop conditions (S2): as 2.7; a failed or stopped B is kept and reported, with no retry in this session; `EMR cluster still active after the run` means S4 at once. In particular the Hadoop stage can again hit `timeout -k 30 1500` (exit 124, as in session 1) or fail if no core NodeManager registers within `NM_WAIT_SECONDS=300`: that is a finding, not something to fix mid-run.

### 3.8 What B left (read-only)

3.8a locate this session's B run directory by its run id (never session 1's failed `exp-B-20261117T053516Z-8a6beaae`):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; B_RUN="$(grep -o 'run-v1-[0-9a-f-]*' ${SCD}/pipeline-inputs/p15-ab-b.json | head -n 1)"; F="$(grep -l "${B_RUN}" ${SCD}/experiments/runs/exp-B-*/raw/pipeline-input.json | head -n 1)"; if [[ -n "${B_RUN}" && -n "${F}" ]]; then B_DIR="${F%/raw/pipeline-input.json}"; printf 'B_RUN=%s\nB_DIR=%s\n' "${B_RUN}" "${B_DIR}" > ${SCD}/logs/phase15-ab-b-rundir.txt; cat ${SCD}/logs/phase15-ab-b-rundir.txt; else echo "no run directory holds this session's B run id: STOP"; false; fi 2>&1 | tee ${SCD}/logs/phase15-ab-b-rundir-find.log | tail -n 60; echo "exit: $?"
```

3.8b the run result:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; B_DIR="$(awk -F= '$1 == "B_DIR" { print $2; exit }' ${SCD}/logs/phase15-ab-b-rundir.txt)"; (echo "RUN_DIR_NAME=${B_DIR##*/}"; echo "--run-record.env"; grep -E '^(RUN_ID|EXPERIMENT_ID|GIT_SHA|DATASET_SNAPSHOT|MANIFEST_SHA256|START_UTC|END_UTC|ELAPSED_SECONDS|EXIT_STATUS|FINAL_STATUS|ESTIMATED_SPEND_USD|SPEND_ESTIMATE_EXCEEDED|METRIC_EXPORT_FAILURES|ENTRY_PROCESSES_STRAY|ENTRY_PROCESSES_REMAINING|RECORD_COMPLETE)=' ${B_DIR}/run-record.env; echo "--pipeline record"; grep -E '^(RUN_ID|SIZE|MAX_MINUTES|START_UTC|END_UTC|FINAL_STATUS|ELAPSED_MINUTES|ESTIMATED_SPEND_USD|RECORD_COMPLETE)=' ${B_DIR}/raw/pipeline/pipeline-*.record.env; echo "--events (last 20)"; tail -n 20 ${B_DIR}/raw/events.jsonl) 2>&1 | tee ${SCD}/logs/phase15-ab-b-result.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

3.8c the on-node reports of run B (the MapReduce counters are B's task and shuffle evidence; on a failed run the failed logs are under `reports/runs/<run id>/failed/<stamp>/`):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; B_RUN="$(awk -F= '$1 == "B_RUN" { print $2; exit }' ${SCD}/logs/phase15-ab-b-rundir.txt)"; DATA_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw data_bucket)"; mkdir -p ${SCD}/runs/p15-ab-b && aws s3 cp "s3://${DATA_BUCKET}/reports/runs/${B_RUN}/" ${SCD}/runs/p15-ab-b/reports/ --recursive --only-show-errors --region us-east-2; echo "files: $(find ${SCD}/runs/p15-ab-b/reports -type f | wc -l | tr -d ' ')"; echo "--top level"; ls ${SCD}/runs/p15-ab-b/reports; echo "--mapreduce-counters"; ls ${SCD}/runs/p15-ab-b/reports/mapreduce-counters; echo "--selected run-record.env keys (names UNVERIFIED)"; grep -E '^(MAPREDUCE|MAPREDUCE_JOB_COUNT|SPARK_ON_YARN|SPARK_MASTER_USED|PREFLIGHT_RESULT|RUN_SCOPE|DATA_PATH[A-Z_]*|NM_[A-Z_]*|HADOOP_EXTRA_OPTIONS|INPUT_LINES_[A-Za-z_]+|STAGE_SECONDS_[A-Z_]+)=' ${SCD}/runs/p15-ab-b/reports/run-record.env ) 2>&1 | tee ${SCD}/logs/phase15-ab-b-reports.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected keys (UNVERIFIED names): `RUN_SCOPE` text for HDFS plus local Spark, `SPARK_ON_YARN=local-master:yes`, `SPARK_MASTER_USED=local[4]`, `MAPREDUCE=yarn`, and `HADOOP_EXTRA_OPTIONS` showing the P15-23 values (`-Dscholarchain.run.partitionCount=8 -Dmapreduce.input.fileinputformat.split.maxsize=262144 -Dmapreduce.map.memory.mb=2048 -Dmapreduce.reduce.memory.mb=3072`). The `STAGE_SECONDS_HADOOP` value answers whether the stage fit its 1,500 s limit.

### 3.9 B collector and derive (read-only)

The collector prints execution ARNs, so its screen output is redacted. It writes `<run dir>/collected/` once, through a staging directory (a failed required call keeps `collect-failed.<stamp>/` and the command can be repeated). The cluster may already be gone; EMR keeps it describable for about two months and CloudWatch keeps 5-minute data for 63 days (UNVERIFIED). If the clock (S3) is short, do step 5 first: 3.8 and 3.9 need no session resources.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; B_DIR="$(awk -F= '$1 == "B_DIR" { print $2; exit }' ${SCD}/logs/phase15-ab-b-rundir.txt)"; AWS_PROFILE=scholarchain bash /Users/winnguyen/ScholarChain/experiments/b-burst/collect.sh --run-dir "${B_DIR}" 2>&1 | tee ${SCD}/logs/phase15-ab-b-collect.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `CALL ...` lines, `CLUSTER_ID=...`, `COLLECTED_DIR=`, `COLLECT_COMPLETE cluster=<id> window=...`, `exit: 0`. An optional call that fails is named in `CALL_STATUS.txt`. Stop: a required failure that repeats (report).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; B_DIR="$(awk -F= '$1 == "B_DIR" { print $2; exit }' ${SCD}/logs/phase15-ab-b-rundir.txt)"; python3 -I /Users/winnguyen/ScholarChain/experiments/b-burst/derive-capacity.py derive --collected-dir "${B_DIR}/collected" --out-dir "${B_DIR}/derived" --title "Experiment B: EMR capacity over time" 2>&1 | tee ${SCD}/logs/phase15-ab-b-derive.log | tail -n 60; echo "exit: $?"
```

Expected: `DERIVED_DIR=...` and the line `scale_out_observed=<yes|unconfirmed|no> scale_in_observed=<yes|no|unknown>`, `exit: 0`. The goal of this rerun is `scale_in_observed=yes` (with `scale_out_observed=yes` if the lower pending ratio still triggers it; session 1's failed run already holds a demand-linked scale-out). `unconfirmed`, `no` or `unknown` is not a stop for the session: it is the result, reported as is, and P15-11 applies (D's ECS scaling is the second source). Do not re-run B to chase a better result.

---

## Step 4. Heap measure on A's export (P15-29; read-only download, then a local measurement)

Why: the local tier-S export (`/Users/winnguyen/scholarchain-data/rehearsal/s-export`) predates SP-2 and lacks the six analytics tables, so the services' heap need is measured only on A's 17-table export (P15-29). The task memory of the query service stays 4096 MiB until this is measured. Do not touch `s-export`: the download goes to a NEW directory `/Users/winnguyen/scholarchain-data/rehearsal/a-export/` (the command refuses an existing one). The measure needs no session resources; it is the human's own run (S1 exception). Timing (review-1 minor 3): 4.1 may start during B (second terminal) or run after 5.4; 4.2 to 4.4 ALWAYS run after 5.4 and before 5.5, never before the session-down. Refresh credentials before 4.1 if the profile is a session (UNVERIFIED how it is configured).

### 4.1 Download A's export (read-only S3; a new local directory)

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; A_RUN="$(awk -F= '$1 == "A_RUN" { print $2; exit }' ${SCD}/logs/phase15-ab-a-rundir.txt)"; DATA_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw data_bucket)"; DEST=${SCD}/rehearsal/a-export; if mkdir -p ${SCD}/rehearsal && mkdir "${DEST}"; then aws s3 cp "s3://${DATA_BUCKET}/semantic-context/runs/${A_RUN}/export/" "${DEST}/" --recursive --only-show-errors --region us-east-2; DL=$?; echo "download exit=${DL}"; F="$(find ${DEST} -type f | wc -l | tr -d ' ')"; B="$(find ${DEST} -type f -exec stat -f %z {} + | awk '{ s += $1 } END { print s + 0 }')"; TO="$(awk '$1 == "Total" && $2 == "Objects:" { print $3 }' ${SCD}/logs/phase15-ab-a-export-ls.txt)"; TS="$(awk '$1 == "Total" && $2 == "Size:" { print $3 }' ${SCD}/logs/phase15-ab-a-export-ls.txt)"; echo "local files=${F} bytes=${B}"; echo "s3 listing objects=${TO} bytes=${TS}"; if [[ "${DL}" == "0" && -n "${TO}" && "${F}" == "${TO}" && "${B}" == "${TS}" ]]; then echo "DOWNLOAD_MATCHES_S3_LISTING=yes"; else echo "DOWNLOAD_MATCHES_S3_LISTING=NO: STOP (do not measure)"; false; fi; else echo "MKDIR_FAILED: ${DEST} exists or cannot be created: STOP (nothing downloaded, nothing measured; never overwrite, never use s-export)"; false; fi ) 2>&1 | tee ${SCD}/logs/phase15-ab-a-export-download.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `download exit=0`, equal file counts and byte totals, `DOWNLOAD_MATCHES_S3_LISTING=yes`, `exit: 0`. The local copy is the export of the run being measured (this session's A). Review-1 minor 2: everything after the `mkdir` sits inside `if mkdir ...; then`, so a failed `mkdir` (a directory `a-export` already exists) downloads and compares nothing and cannot pass an older directory off as this export (`MKDIR_FAILED`, `exit: 1`). Stop: `MKDIR_FAILED` (the human decides by hand; never overwrite and never use `s-export`), a non-zero download exit, a partial download, or a mismatch. Cost: about 0.5 GB out of S3 (HIGH allowance 0.10 USD, UNVERIFIED rate).

### 4.2 HEAP-MEASURE (runs after 5.4; the human's own sbt run, no agent runs it)

Preconditions, all required: step 2.10 PASSED (both `EXPORT_SIZE_CHECK=PASS` and `EXPORT_ROWS_CHECK=PASS`), 4.1 printed `DOWNLOAD_MATCHES_S3_LISTING=yes`, 5.4 is done, and the tree is clean. Why 2.10 comes first (review-1 M3): the suite silently substitutes an empty zero-byte table for any missing table (`ExportHeapMeasureSuite.scala:26-42`), so a download that lacks a table would still print a `HEAP-MEASURE` line; and with the variable unset the suite skips and passes with no `HEAP-MEASURE` line at all (`:102-103`).

The command is the storage-engineer's w2rb2 form (`SCHOLARCHAIN_S_EXPORT_DIR=<dir> sbt "storage/testOnly scholarchain.storage.ExportHeapMeasureSuite"`) with this session's choices: the directory is A's export `rehearsal/a-export` (never `s-export`, which predates SP-2), `-J-Xmx1g` caps the heap of the sbt JVM and so of the test, because the suite runs inside sbt's JVM (no `Test / fork`), and the project id `storage` is the one in `build.sbt:380`.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCHOLARCHAIN_S_EXPORT_DIR=/Users/winnguyen/scholarchain-data/rehearsal/a-export sbt -J-Xmx1g "storage/testOnly scholarchain.storage.ExportHeapMeasureSuite" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-heap-measure.log | tail -n 60; echo "exit: $?"
```

Require a `HEAP-MEASURE` line in the output (the human reads it on screen; 4.2b counts it). Its `rows=` entries must be NON-ZERO for each of the six SP-2 tables `path-evidence`, `components`, `component-membership`, `communities`, `community-membership`, `community-cross-edges`; the line's layout is read from `ExportHeapMeasureSuite.scala:125-133`: `HEAP-MEASURE load=<n> ms, retained after GC=<x> MiB, peak (sum of pool peaks, an upper bound)=<x> MiB, max heap=<x> MiB, objects=<n>, bytes loaded=<n>, skipped tables=<a,b>, skipped bytes=<n>, rows=<table>:<n>,<table>:<n>,...` with `rows=` last. The table names in `rows=` are the manifest table names (the export directory names, as listed above); a table the export lacks is served as an empty overlay table and so shows `:0`, which is why a zero voids the measure. A zero or absent entry for any of the six, or no `HEAP-MEASURE` line, voids the measure: it is not a pass, 4096 MiB stands, and the cause goes to the orchestrator. An `OutOfMemoryError` at `-Xmx1g` means 4096 MiB (the measure needed more than the cap, so nothing smaller is supported); record the error line and do not retry with a larger cap here. If sbt or the test compile fails, nothing else changes: 4096 MiB stands. Expected (UNVERIFIED, from the WO-7c estimate in `docs/agent-runs/phase-15/wave-2/reports/wo-07c-storage-engineer.md`): retained about 200 to 300 MiB and a peak below about 600 MiB on the 11-table local export; A's 17-table export is larger by the six new tables.

4.2b the line is present and the six SP-2 tables are non-zero (read-only; the screen output above is the evidence, this counts and parses it). It takes the last `HEAP-MEASURE` line, cuts the text after `rows=`, splits on commas and reads each `<table>:<n>`:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; ( LOGF=/Users/winnguyen/scholarchain-data/logs/phase15-ab-heap-measure.log; N="$(grep -a -c 'HEAP-MEASURE' ${LOGF})"; echo "HEAP_MEASURE_LINES=${N}"; grep -a 'HEAP-MEASURE' ${LOGF}; BADT=""; if [[ "${N}" -ge 1 ]]; then ROWS="$(grep -a 'HEAP-MEASURE' ${LOGF} | sed -n 's/.*rows=//p' | tail -n 1)"; for T in path-evidence components component-membership communities community-membership community-cross-edges; do V="$(printf '%s\n' "${ROWS}" | tr ',' '\n' | awk -F: -v t="${T}" '$1 == t { print $2; exit }')"; echo "rows ${T}=${V:-MISSING}"; if [[ ! "${V}" =~ ^[0-9]+$ ]] || [[ "${V}" -le 0 ]]; then BADT="${BADT} ${T}"; fi; done; fi; if [[ "${N}" -ge 1 && -z "${BADT}" ]]; then echo "HEAP_MEASURE_ROWS_CHECK=PASS (line present, six SP-2 tables non-zero)"; elif [[ "${N}" -ge 1 ]]; then echo "HEAP_MEASURE_ROWS_CHECK=FAIL: zero or missing:${BADT}: void, 4096 MiB stands"; false; else echo "HEAP_MEASURE_ROWS_CHECK=FAIL: no HEAP-MEASURE line (an unset variable skips and passes): void, 4096 MiB stands"; false; fi ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-heap-measure-check.log | tail -n 60; echo "exit: $?"
```

### 4.3 The sizing rule (human to ratify, OD-3; the human reads the `HEAP-MEASURE` line)

Source (review-1 M3, OD-3): the 400/800 MiB thresholds come from the storage-engineer's WO-7c report, `docs/agent-runs/phase-15/wave-2/reports/wo-07c-storage-engineer.md` line 15 ("if retained <= 400 and peak <= 800 MiB, 2048 MiB task memory suffices; keep 4096 provisional"), and NOT from P15-29. P15-29 says only that the memory stays 4096 MiB until A's 17-table export is measured. The rule is therefore a proposal: HUMAN TO RATIFY (OD-3). Until the human ratifies it in the chat, memory stays 4096 MiB whatever the measure shows.

| Measured on A's 17-table export (valid line, six SP-2 tables non-zero) | Task memory for the query service (session 2) |
|---|---|
| retained at most 400 MiB AND peak at most 800 MiB, and the rule ratified | 2048 MiB would be sufficient (512 CPU allows 1024 to 4096 MiB; the JVM heap is 75 percent of the container, 1.5 GiB at 2048 MiB) |
| otherwise, or the rule not ratified, or an `OutOfMemoryError` at `-Xmx1g`, or a void measure | stays 4096 MiB |

The decision and the pasted line go to the orchestrator; infra-engineer then sets `service_memory` for session 2 (not this session: no repository write, S1). With the 4096 MiB provisional and 300 s grace values of `SESSION2.md` section 7, the measure is also the evidence for the start-time rule there, if the command prints a start time (UNVERIFIED; the w2rb2 line lists a load time, not a container start time).

### 4.4 The tree is still clean (before 5.5)

A local sbt run writes only ignored output; this proves it before the stash pop of 5.5 (the session-down at 5.3R is already done, so a dirty tree here no longer blocks the teardown).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; ( S="$(git status --short | wc -l | tr -d ' ')"; echo "HEAD=$(git rev-parse HEAD)"; echo "STATUS_SHORT_LINES=${S}"; git status --short | head -n 20; if [[ "${S}" == "0" ]]; then echo "TREE_CLEAN=yes"; else echo "TREE_CLEAN=NO: STOP, report; do not discard anything"; false; fi ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-tree-after-heap.log | tail -n 60; echo "exit: $?"
```

Expected: `STATUS_SHORT_LINES=0`, `TREE_CLEAN=yes`, `exit: 0`. Stop: not clean (report before 5.5; do not discard anything and do not pop the stash).

---

## Step 5. Session-down (same day), verification and stash pop

Do this as soon as B has ended and its cluster is gone (or as soon as S2 or S3 sends you here). 3.8, 3.9, 4 and 6 need no session resources, so they can wait until after the teardown (4.2 to 4.4 always do: they run after 5.4 and before 5.5). The session-down apply (5.3R) starts no later than T0 + 6 h. Teardown var files for this session: `course.tfvars` (the environment default of `plan.sh`) plus `session-down.tfvars` ONLY. Never add `session-down-services.tfvars` (session 2's A4 file: it sets `enable_services_stack = true`, so here it would plan to CREATE the services stack and would also need a 40-hex `load_client_image_tag`), never use `session2.tfvars`, and never combine `session-down.tfvars` with a size overlay. The tree must still be clean (S1); if `deploy.sh` refuses, something wrote to the repository: find it with `git status --short`, report, and do not discard anything.

### 5.1 Plan the session-down

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ENVDIR=/Users/winnguyen/ScholarChain/infra/terraform/environments/course; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; AWS_PROFILE=scholarchain bash scripts/plan.sh --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --extra-var-file ${ENVDIR}/session-down.tfvars --out-dir ${SCD}/plans/p15-ab-down 2>&1 | tee ${SCD}/logs/phase15-ab-down-plan.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected (infra-engineer, `w2rb4-infra-engineer.md` and `SESSION2.md` section 5a; read from the code, UNVERIFIED until this plan runs; the same shape as session 1's down plan): exactly `0 to add, 1 to change, 8 to destroy`, and `IAM_CHANGES:` followed on the next line by `  none`. DESTROY 8: the eight interface endpoints (`aws_vpc_endpoint` interface). UPDATE in place 1: `module.workflow.aws_sfn_state_machine.pipeline[0]` back to its default definition. `STATEFUL_CHANGE=no`, `exit: 0`. No Wave 2 resource exists to be destroyed or kept (OD-1), and the plan must not destroy a bucket, table, vector bucket, index, repository or secret.

### 5.2 Read plan down

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ( LOG=${SCD}/logs/phase15-ab-down-plan.log; ACT="$(sed '/^PLAN_SUMMARY_BEGIN/,$d' ${LOG} | grep -nE '^[[:space:]]*# .*(will be created|will be destroyed|must be replaced|will be replaced|will be updated in-place)')"; echo "--resource actions"; printf '%s\n' "${ACT}"; echo "--summary"; sed -n '/^PLAN_SUMMARY_BEGIN/,/^PLAN_SUMMARY_END/p' ${LOG}; echo "--meta"; grep -E '^(GIT_SHA|DIRTY|STATEFUL_CHANGE|PLAN_EXIT|IMAGE_TAG)=' ${SCD}/plans/p15-ab-down/course-*.tfplan.meta; echo "--iam (expect IAM_CHANGES: none)"; grep -A1 -x 'IAM_CHANGES:' ${LOG}; echo "--terraform counts (expect 0 to add, 1 to change, 8 to destroy)"; grep -E '[0-9]+ to add, [0-9]+ to change, [0-9]+ to destroy' ${LOG}; FORBID='aws_iam_|aws_lb|aws_alb|aws_appautoscaling_|aws_ecs_service\.worker|aws_ecs_service\.this\["(worker|query)"\]|aws_ecr_|load-client|load_client|task_definition|aws_cloudwatch_dashboard|aws_cloudwatch_metric_alarm|aws_ecs_cluster|aws_security_group|aws_neptune|bedrock|aws_s3_|aws_dynamodb_|aws_s3vectors_|aws_secretsmanager_|aws_cloudwatch_log_group'; BAD="$(printf '%s\n' "${ACT}" | grep -E "${FORBID}")"; N_CR="$(printf '%s\n' "${ACT}" | grep -c 'will be created')"; N_RP="$(printf '%s\n' "${ACT}" | grep -cE 'must be replaced|will be replaced')"; N_UP="$(printf '%s\n' "${ACT}" | grep -c 'will be updated in-place')"; N_DS="$(printf '%s\n' "${ACT}" | grep -c 'will be destroyed')"; N_EP="$(printf '%s\n' "${ACT}" | grep -cE 'aws_vpc_endpoint.*will be destroyed')"; N_SM="$(printf '%s\n' "${ACT}" | grep -cE 'aws_sfn_state_machine\.pipeline.*will be updated in-place')"; echo "--forbidden addresses (expect none)"; printf '%s\n' "${BAD}"; echo "ACTION_LINES created=${N_CR} replaced=${N_RP} updated=${N_UP} destroyed=${N_DS} endpoints_destroyed=${N_EP} state_machine_updated=${N_SM} (expect 0 0 1 8 8 1)"; if [[ -z "${BAD}" && "${N_CR}" == "0" && "${N_RP}" == "0" && "${N_UP}" == "1" && "${N_DS}" == "8" && "${N_EP}" == "8" && "${N_SM}" == "1" ]] && grep -qE '0 to add, 1 to change, 8 to destroy' ${LOG} && grep -A1 -x 'IAM_CHANGES:' ${LOG} | tail -n 1 | grep -qx '  none'; then echo "PLAN_DOWN_CHECK=PASS"; else echo "PLAN_DOWN_CHECK=FAIL: STOP (anything other than the eight endpoint destroys and the state machine update differs from SESSION2.md section 5a)"; false; fi ) 2>&1 | tee ${SCD}/logs/phase15-ab-down-plan-actions.log | tail -n 60; echo "exit: $?"
```

The command ends with the automated verdict `PLAN_DOWN_CHECK` (eight `aws_vpc_endpoint` destroys, one state machine update, nothing created or replaced, counts line `0 to add, 1 to change, 8 to destroy`, `IAM_CHANGES:` then `  none`, no forbidden address; the UNVERIFIED notes of 2.2 apply).

Stop: any add or replacement, any destroy line other than the eight `aws_vpc_endpoint` interface endpoints (a destroy of a Wave 2 resource, a log group, a task definition or a service would mean a stack flag leaked or `session-down-services.tfvars` was wrongly added or left out); any `aws_iam_*` line or `IAM_CHANGES` other than `none`; `STATEFUL_CHANGE=yes` (the record would need `STATEFUL_CHANGE_ACKNOWLEDGED=yes` and the human must first check what is destroyed); `DIRTY=yes`. A stop here does not mean "go to step 5" (you are in it): the endpoints are still billing, so decide within 15 minutes and say so in the chat.

### 5.3 Deploy down

5.3P:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PLAN_FILE="$(ls ${SCD}/plans/p15-ab-down/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --print-digest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --plan-file "${PLAN_FILE}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-ab-down 2>&1 | tee ${SCD}/logs/phase15-ab-down-deploy-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

5.3W:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; REC=${SCD}/approvals/p15-ab-down-deploy.env; dg() { awk -F= -v k="${2}" '$1 == k { print $2; exit }' "${1}"; }; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; EXP="$(date -u -v+8H +%Y-%m-%dT%H:%M:%SZ)"; ( (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=deploy" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=20" "PLAN_DIGEST=$(dg ${SCD}/logs/phase15-ab-down-plan.log PLAN_DIGEST)" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-ab-down-deploy-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-ab-down-deploy-digest.log ARGS_DIGEST)" "GIT_SHA=$(git rev-parse HEAD)" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > "${REC}") && sed "s/^ACCOUNT_ID=.*/ACCOUNT_ID=<masked>/" "${REC}" ) 2>&1 | tee ${SCD}/logs/phase15-ab-down-deploy-record.log | tail -n 60; echo "exit: $?"
```

5.3D:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PLAN_FILE="$(ls ${SCD}/plans/p15-ab-down/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --dry-run --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-ab-down-deploy.env --plan-file "${PLAN_FILE}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-ab-down 2>&1 | tee ${SCD}/logs/phase15-ab-down-deploy-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

5.3R:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PLAN_FILE="$(ls ${SCD}/plans/p15-ab-down/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-ab-down-deploy.env --plan-file "${PLAN_FILE}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-ab-down 2>&1 | tee ${SCD}/logs/phase15-ab-down-deploy.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `IMAGE_EXISTS` for both, `DEPLOY_COMPLETE`, `DEPLOYED_REVISION=<HEAD>`, `exit: 0`. Review-1 B1 (resolved by OD-1): `verify_revision` also runs after this destroy and passes because no load-client family exists (2.4R); before OD-1 it would have died here with `REVISION_MISMATCH` after the endpoints were already destroyed. A `REVISION_MISMATCH` naming a `load-client` or `worker` family would mean the stack flag leaked. Rollback gate: there is no reversal of the endpoint destroy; the way back is a new session-up plan with a new record. Storage is untouched. Stop: a failure; the endpoints may still be up (about 0.12 USD/h at HIGH, 2.88 USD a day): report at once, run a new plan to see the drift, decide within 15 minutes.

### 5.4 Post-teardown read-only check

Labeled sections with UTC stamps; every call is a describe-, list- or get- read (the `ec2`, `elbv2`, `ecs` and `neptune` flag forms are UNVERIFIED against the CLI reference). Run it once right after 5.3R and again later in the day if you wish.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; mkdir -p /Users/winnguyen/scholarchain-data/logs; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; echo "--utc ${STAMP}"; echo "--emr-active-clusters (expect empty)"; aws emr list-clusters --active --region us-east-2 --query 'Clusters[].[Id,Name,Status.State]' --output text; echo "--emr-clusters-last-24h (expect this session's A and B clusters TERMINATED; session 1's failed B cluster may also be listed if it is within 24 h)"; aws emr list-clusters --created-after "$(date -u -v-24H +%Y-%m-%dT%H:%M:%SZ)" --region us-east-2 --query 'Clusters[].[Id,Name,Status.State,Status.StateChangeReason.Code]' --output text; echo "--ecs-running-tasks (expect none under every cluster)"; for C in $(aws ecs list-clusters --region us-east-2 --query 'clusterArns[]' --output text); do echo "cluster=${C##*/}"; aws ecs list-tasks --cluster "${C}" --desired-status RUNNING --region us-east-2 --query 'taskArns[]' --output text; done; echo "--ecs-service-desired-counts (expect only fast and slow, desired 0 and running 0; no worker or query service)"; for C in $(aws ecs list-clusters --region us-east-2 --query 'clusterArns[]' --output text); do echo "cluster=${C##*/}"; aws ecs describe-services --cluster "${C}" --services $(aws ecs list-services --cluster "${C}" --region us-east-2 --query 'serviceArns[]' --output text) --region us-east-2 --query 'services[].[serviceName,desiredCount,runningCount]' --output text; done; echo "--neptune-clusters (expect empty)"; aws neptune describe-db-clusters --region us-east-2 --query 'DBClusters[].[DBClusterIdentifier,Status]' --output text; echo "--neptune-instances (expect empty)"; aws neptune describe-db-instances --region us-east-2 --query 'DBInstances[].[DBInstanceIdentifier,DBInstanceClass,DBInstanceStatus]' --output text; echo "--vpc-endpoints (expect only Gateway rows for s3 and dynamodb, available)"; aws ec2 describe-vpc-endpoints --region us-east-2 --query 'VpcEndpoints[].[VpcEndpointType,ServiceName,State]' --output text; echo "--nat-gateways (expect empty)"; aws ec2 describe-nat-gateways --region us-east-2 --filter "Name=state,Values=pending,available" --query 'NatGateways[].[NatGatewayId,State]' --output text; echo "--elastic-ips (expect empty)"; aws ec2 describe-addresses --region us-east-2 --query 'Addresses[].[AllocationId,AssociationId]' --output text; echo "--load-balancers (expect empty)"; aws elbv2 describe-load-balancers --region us-east-2 --query 'LoadBalancers[].[LoadBalancerName,Type,State.Code]' --output text; echo "--ebs-volumes-unattached (expect empty)"; aws ec2 describe-volumes --region us-east-2 --filters "Name=status,Values=available" --query 'Volumes[].[VolumeId,Size]' --output text; echo "--ec2-instances-pending-or-running (expect empty)"; aws ec2 describe-instances --region us-east-2 --filters "Name=instance-state-name,Values=pending,running" --query 'Reservations[].Instances[].[InstanceId,InstanceType]' --output text; echo "--sfn-running-executions (expect empty)"; aws stepfunctions list-executions --state-machine-arn "$(aws stepfunctions list-state-machines --region us-east-2 --query "stateMachines[?name=='scholarchain-course-pipeline'].stateMachineArn | [0]" --output text)" --status-filter RUNNING --region us-east-2 --query 'executions[].[name,status]' --output text; echo "--sfn-last-executions (expect this session's A and B executions with their real status)"; aws stepfunctions list-executions --state-machine-arn "$(aws stepfunctions list-state-machines --region us-east-2 --query "stateMachines[?name=='scholarchain-course-pipeline'].stateMachineArn | [0]" --output text)" --max-results 5 --region us-east-2 --query 'executions[].[name,status,startDate,stopDate]' --output text; echo "--dlq-run-requests (expect 0 0)"; aws sqs get-queue-attributes --queue-url "$(aws sqs get-queue-url --queue-name scholarchain-course-run-requests-dlq --region us-east-2 --query QueueUrl --output text)" --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible --region us-east-2 --output text; echo "--dlq-embedding-work (expect 0 0)"; aws sqs get-queue-attributes --queue-url "$(aws sqs get-queue-url --queue-name scholarchain-course-embedding-work-dlq --region us-east-2 --query QueueUrl --output text)" --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible --region us-east-2 --output text ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-down-confirm-${STAMP}.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: no active EMR cluster; the last-24h list shows this session's A and B clusters TERMINATED (any other cluster that is not session 1's `j-00913722ZZCSPE7LXKE8` is a finding); no running ECS task; the ECS services are `fast` and `slow` only (review-1 minor 6: with OD-1 no `worker` or `query` service is created, so none is expected; one that appears is a finding and means a stack flag leaked), each at desired 0 and running 0; no Neptune cluster or instance; only `Gateway` endpoints for S3 and DynamoDB, `available`; no NAT gateway, Elastic IP, load balancer or unattached EBS volume; no running EC2 instance or execution; DLQs `0 0`. A cluster with no services makes `describe-services` print a usage error: read it as "no services" (UNVERIFIED). Stop (report at once): any active cluster, interface endpoint, running instance, Elastic IP, load balancer or unattached volume; a leftover EMR cluster is S4. After the teardown the idle items are storage only (S3, DynamoDB, ECR, S3 Vectors, secrets, log groups). No Wave 2 dashboard exists and the cluster's Container Insights setting stays disabled in this session (OD-1), so neither is a cost item here.

### 5.5 Restore leftovers (stash pop)

Only after 5.4 passed and after 4.2 to 4.4 (which always run after 5.4, with 4.4 showing a clean tree). If 0.6 saved nothing there is no stash and the command says so.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; ( git stash list | head -n 5; N="$(git stash list | grep -c 'p15-ab-leftover')"; if [[ "${N}" == "1" ]]; then git stash pop; echo "STASH_POP_EXIT=$?"; else echo "no stash named p15-ab-leftover (count ${N}): nothing to restore"; fi; echo "--git status --short"; git status --short | head -n 60 ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-stash-pop.log | tail -n 60; echo "exit: $?"
```

Expected: `STASH_POP_EXIT=0` and the leftovers listed again, or "nothing to restore". Stop: a stash-pop conflict or any other exit: never discard anything (no `git checkout`, `git reset`, `git stash drop` or `git clean`); leave the stash in place and wait for the orchestrator and the human. After 5.5 S1 ends and the orchestrator may write evidence into the repository.

---

## Step 6. Redaction copies, evidence to paste back

### 6.1 Redaction copies before any results commit

The raw run directories stay exactly as written. The copy replaces the 12-digit account id with `000000000000` in a COPY of the whole run directory (a superset of the b-burst README's list: it also covers `run-record.env`, `entrypoint.log` and `metrics/`). The final grep is the control. The copies live under `scholarchain-data/` and are committed only after 5.5. The copy's `SHA256SUMS` no longer matches after redaction: the raw directory is the source of truth.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; A_DIR="$(awk -F= '$1 == "A_DIR" { print $2; exit }' ${SCD}/logs/phase15-ab-a-rundir.txt)"; COPY=${SCD}/results-copy/p15-ab-a; if [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ && -d "${A_DIR}" ]]; then mkdir -p ${SCD}/results-copy && cp -R "${A_DIR}" "${COPY}" && chmod -R u+w "${COPY}" && find "${COPY}" -type f -exec sed -i.bak "s/${ACCOUNT_ID}/000000000000/g" {} + && find "${COPY}" -name '*.bak' -delete; if grep -rq "${ACCOUNT_ID}" "${COPY}"; then echo "REDACTION_FAILED: the account id is still in ${COPY}"; false; else echo "REDACTION_OK: the account id is not in ${COPY}"; fi; else echo "no 12-digit account id from STS, or no A run directory: STOP"; false; fi 2>&1 | tee ${SCD}/logs/phase15-ab-a-redact.log | tail -n 60; echo "exit: $?"
```

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; B_DIR="$(awk -F= '$1 == "B_DIR" { print $2; exit }' ${SCD}/logs/phase15-ab-b-rundir.txt)"; COPY=${SCD}/results-copy/p15-ab-b; if [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ && -d "${B_DIR}" ]]; then mkdir -p ${SCD}/results-copy && cp -R "${B_DIR}" "${COPY}" && chmod -R u+w "${COPY}" && find "${COPY}" -type f -exec sed -i.bak "s/${ACCOUNT_ID}/000000000000/g" {} + && find "${COPY}" -name '*.bak' -delete; if grep -rq "${ACCOUNT_ID}" "${COPY}"; then echo "REDACTION_FAILED: the account id is still in ${COPY}"; false; else echo "REDACTION_OK: the account id is not in ${COPY}"; fi; else echo "no 12-digit account id from STS, or no B run directory: STOP"; false; fi 2>&1 | tee ${SCD}/logs/phase15-ab-b-redact.log | tail -n 60; echo "exit: $?"
```

Expected: `REDACTION_OK`, `exit: 0`. Stop: `REDACTION_FAILED` (nothing from that copy is committed). The derived outputs of 2.8d and 3.9 carry no account id (stated in the scripts' headers); the on-node reports under `runs/p15-ab-*/reports` and the downloaded `rehearsal/a-export` are not part of the copy and are not committed as they are.

### 6.2 Redacted copy of the logs for pasting

One redacted file with the last 30 lines of every session log; paste from it, not from the raw logs. It writes only under `scholarchain-data/`; the output is `.txt` so a second run does not include the first copy.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; if [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ ]]; then (for F in ${SCD}/logs/phase15-ab-*.log; do echo "=== ${F##*/}"; tail -n 30 "${F}"; done) | sed "s/${ACCOUNT_ID}/000000000000/g" > ${SCD}/logs/phase15-ab-paste-back.txt; wc -l ${SCD}/logs/phase15-ab-paste-back.txt; else echo "no 12-digit account id from STS: STOP"; false; fi 2>&1 | tee ${SCD}/logs/phase15-ab-paste-back-run.txt | tail -n 60; echo "exit: $?"
```

### 6.3 What to paste, per step

| Step | Paste (from `phase15-ab-paste-back.txt`) | Why |
|---|---|---|
| 0.0 | the human's chat acknowledgements (0.0) | the 7-change acknowledgement, OD-1, OD-3, OD-7 |
| 0.1 to 0.7 | `billing-gate`, `risk-checks`, `wave2-head`, `docs-stage`, `docs-commit`, `stash-push`, `tree-head` | P15-10 gate, Wave 2 in HEAD, the docs-only commit, the code pin |
| 1.1 to 1.5 | `identity`, `state-check-<stamp>`, `raw-listing`, `prepare-inputs`, the four `-digest` logs | pre-checks, A and B run ids, the new digests |
| 1.6 to 1.10 | `package`, `sp2-jar`, `upload-digest`, `raw-compare`, `jars-compare`, `upload-record` (masked), `upload-dry`, `upload`, `bootstrap-check` | SP-2 in the jar, raw inputs equal the snapshot, no stale jars, the P15-23 profile and jars in S3 |
| 2.1 to 2.5 | `a-plan`, `a-plan-actions` (`PLAN_A_UP_CHECK`), `a-sfn-*`, `a-deploy-digest`, `a-deploy-record`, `a-deploy-dry`, `a-deploy`, `a-sfn-summary-live` | plan digest, `IAM_CHANGES: none`, apply, rendered and deployed definition |
| 2.6 to 2.8 | `a-run-records`, `a-pipeline-dry`, `a-experiment-dry`, `a-run`, `a-result`, `a-reports`, `a-derive`, `a-collect` | the A run, its records and the course field table |
| 2.9, 2.10 | `a-run-ids`, `a-export-manifest-fields.txt`, `a-export-size`, `a-export-rows` | both run ids, the 17 tables and the limits check (bytes with root, objects, rows) |
| 3.1 to 3.5 | `b-plan`, `b-plan-actions` (`PLAN_B_SWITCH_CHECK`), `b-sfn-*`, `b-deploy-*`, `b-sfn-summary-live` | the switch |
| 3.6 to 3.9 | `b-run-records`, `b-pipeline-dry`, `b-experiment-dry`, `b-run`, `b-result`, `b-reports`, `b-collect`, `b-derive` | the B run, `scale_out_observed` and `scale_in_observed` |
| 4 (4.2 to 4.4 after 5.4) | `a-export-download`, the `HEAP-MEASURE` line(s) with the `rows=` of the six SP-2 tables, `heap-measure-check`, `tree-after-heap` | the P15-29 sizing (the 400/800 MiB rule is OD-3, human to ratify) |
| 5 | `down-plan`, `down-plan-actions` (`PLAN_DOWN_CHECK`), `down-deploy-*`, `down-confirm-<stamp>`, `stash-pop` | the teardown and its confirmation |
| any | `emergency-lookup-*`, `emergency-terminate-*` if S4 was used | the human emergency action |
| after | the billed total after the session (next day) | actual spend against the 3.90 HIGH |

Do not paste: approval files (paste the W logs only), the raw `sfn-*` or definition files, the on-node reports, `session2.tfvars` contents beyond the two lines shown by 2.9 (the third line is written at session 2), or any `s3://` bucket name. Keep those under `scholarchain-data/`.

### 6.4 Where the orchestrator records it (only after 5.5)

The orchestrator creates `docs/agent-runs/phase-15/session-ab/` content, preserving each item as received with its author: `evidence.md` (the pasted tails per step with UTC times and a ledger with one row per approval record: action, plan digest, script digest, args digest, cost bound, rollback gate, approved-by and approved-at), `a-run-result.md` and `b-run-result.md` (experiment-engineer's results work order; limitations P15-2, P15-12, P15-16, P15-20, P15-23 and P15-11 if B does not show both events). Deploy-engineer writes the release records afterwards from the logs and records, with no command run: `docs/releases/phase-15/deploy-ab-a-up.md`, `deploy-ab-b-switch.md`, `deploy-ab-down.md` and an outcome README, with the fields HEAD, plan digest, script and args digests, approval file name and window, apply counts, image digests, smoke evidence, rollback gate and open items.

---

## Failure branches

| Situation | Branch |
|---|---|
| A-up apply or its checks fail (2.4R, 2.5) | S2: report, then step 5 unless the human decides otherwise within 15 minutes. No A, no B in that case; the failed record is not re-run. A `REVISION_MISMATCH` naming a `load-client` or `worker` family (review-1 B1) means a services-stack flag leaked into the plan: the apply already happened and the endpoints bill, so go to step 5 at once and report to infra-engineer through the orchestrator |
| A-up plan shows any 2.2 stop (a forbidden address, a count other than 11 / 7 / 3, any `aws_iam_*`) | Nothing applied, nothing billing: report and wait for infra-engineer's fix and a new plan; no record is written |
| A does not finish `SUCCEEDED` (2.7, 2.8b) | The run is kept and reported (never deleted or edited), collected (2.8e) and derived as far as possible. Default: step 5 (S2). The 17-table export may not exist, so session 2 has no A export. Fallback b1 (P15-26): the services' export comes from a local export built from run 6's Parquet, `export_run_id` and `graph_run_id` of run 6 (slug `run-v1-192de0fd-...`, `sourceRunId` `run:v1:9f6e6fb0-2ec3-4148-85fc-f5ba7c56ad05`), each needing its own recorded human decision (`SESSION2.md` section 6a). OPEN PLACEHOLDER: the b1 procedure is not authored in this runbook (owner through the orchestrator: spark-engineer with storage-engineer). P15-29's heap measure is for A's export only; whether the b1 export (all 17 tables) can be measured instead is an open decision, and until then 4096 MiB stands. The human may choose in the chat (with an end time) to run B anyway when the failure cannot affect it |
| A succeeded but 2.9 fails (slug or `sourceRunId` form, S3 directory name) | Stop `session2.tfvars`; report; B is not affected; session 2 is blocked until the identifiers are settled (infra-engineer owns the variables) |
| A succeeded but `EXPORT_SIZE_CHECK=FAIL` or `EXPORT_ROWS_CHECK=FAIL` (2.10) | B is not affected; session 2 is blocked on the P15-25 limit: `BLOCKED-ON` storage-engineer (limit) or service-engineer (settings). 4.2 needs 2.10 to PASS first (a failed table check makes the heap measure void), so the measure is skipped unless the human decides otherwise in the chat; 4096 MiB stands |
| B-switch apply or its checks fail (3.1 to 3.5) | S2: step 5. A's results stay; B is not run |
| B fails again (`FAILED`, `STOPPED_WALL_CLOCK`, the Hadoop 1,500 s limit again, no NodeManager registered) | Keep and report the failed run (never deleted or edited), collect and derive it (3.8, 3.9). No tuning and no third B in this session. P15-11 applies: D's ECS scaling in session 2 is the second source of scale-out and scale-in evidence. Both results are recorded either way. Then step 5 |
| B completes but `scale_in_observed` is `no` or `unknown`, or `scale_out_observed` is `unconfirmed` or `no` | The result as is; P15-11 applies for whatever the gate still lacks; no rerun |
| EMR cluster still active after a run, or leftover at 1.2 | S4 (E1 lookup, then E1 terminate only when exactly one cluster is active) |
| Past an S3 limit (A start, B start, session-down start) | go to step 5 without running the next experiment |
| A command prints a failed pre-check before 2.4R | report and wait; nothing is billing |
| Heap measure cannot run, is void (no `HEAP-MEASURE` line, a zero `rows=` for a SP-2 table), or hits an `OutOfMemoryError` at `-Xmx1g` | 4096 MiB stands; record the line or error; carry the item to session 2. Nothing here delays the session-down, because 4.2 runs after 5.4 |

## Carry-forward for session 2

- Haiku smoke (P15-27, review 1 m3): the Bedrock call cap is per process and the planner and synthesis both call Haiku, so 10 calls cover about 5 queries. The session 2 runbook runs the Haiku smoke with exactly 1 service task and at most 4 Haiku queries. Documented, no code change.
- P15-13: the human submits the Anthropic first-time use case form and re-runs the read-only Bedrock availability check (expect `AVAILABLE`) before session 2.
- P15-10 again before session 2: billed after this session plus its HIGH (4.5 in `SESSION2.md` section 9, 4.3 in the plan) at most 14 USD.
- `session2.tfvars` (2.9) is the input of the A2, A3 and A4 plans (A4 also takes `session-down-services.tfvars`); it has two lines after this session and session 2 appends the third, `load_client_image_tag = "<git rev-parse HEAD>"` (OD-2, `SESSION2.md` section 6a), after its own last commit; the heap decision (4.3, rule human to ratify, OD-3) sets `service_memory`; the 17-table size and row checks (2.10) must be `PASS` before A1.
- P15-28 (accepted limitation): the live read path no longer checks path-evidence hop edges against `edges-coauthored`; the check moves to the Phase 16 graph-correctness evaluation, offline on the export.

## Open placeholders (explicit)

Closed in revision 1 (no longer placeholders): the heap command (4.2, filled from the w2rb2 report with the human-supplied form), the expected A-up counts and `IAM_CHANGES` lines (2.1, 2.2: 11 / 7 / 3 and `none`, with A to B 0 / 1 / 0 and B to down 0 / 1 / 8), the UNVERIFIED slug-versus-`sourceRunId` form in 2.9 (`run-s-pipeline.sh:85, 580`), and the six table names in 4.2b (review 4: `rows=` prints `TableStats.table`, `JsonlExportSource.scala:29-36`, and the six names are literal entries in `AwsReadModels.RequiredTables`, `AwsReadModels.scala:135-140`).

1. `REPLACE_WITH_BILLED_TOTAL` (0.1): the human's Billing reading; the `LAG_USD` choice (1.15 or 0).
2. `REPLACE_WITH_WAVE2_COMMIT_SHA` (0.3, 0.7): the 40-hex SHA of the Wave 2 commit, after the human commits.
3. The rendered-values table (A and B columns) is the approved session 1 set, now read as consistent with `workflow/main.tf:104-108`; confirmation that the Wave 2 commit left the eight `TimeoutSeconds`, `IdleTimeout` and the Wait unchanged is open, and so is the `SESSION2.md` section 5a wording "no loader state" (read here as "no `AfterTerminate`").
4. The b1 fallback procedure (P15-26) is not authored here.
5. `REPLACE_WITH_CLUSTER_ID` in the optional 3.7 watch commands (read-only, the human fills it from the first command).
6. Pre-conditions that are actions of others: the Wave 2 commit including SP-2, the P15-23 profile and infra's repair batch 4 (which is UNVALIDATED: no `terraform fmt`, `validate` or `plan`, harness cases `15.wave2.terraform.insights` and others expected to change, per `w2rb4-infra-engineer.md`); the orchestrator's copy of this file to `docs/agent-runs/phase-15/session-ab/human-commands.md` before 0.4; the human's deletion of the stray file `/Users/winnguyen/ScholarChain/scratch-unused.tmp` that infra-engineer's report says it created by mistake (untracked, so 0.6 would park it in the stash and 5.5 would restore it; delete it before 0.2 so it is neither stashed nor restored); the 0.0 acknowledgement and confirmations of OD-1, OD-3 and OD-7.
7. UNVERIFIED by nature of not having run: the jar layout, the class name `ExportTables` and which class file (`ExportTables$.class` or `ExportTables.class`) holds the constants (1.7); `jars/spark/<name>` as the S3 key of the jar (1.10); the `<table>/part-*.jsonl` key layout and the `aws s3 ls --recursive` columns used in 2.10; the manifest field names `tables`, `sourceRunId` and `tables.<name>.rows` (2.9, 2.10); that the plan log carries attribute lines such as `desired_count` (the `Plan:` line and the two-line `IAM_CHANGES:` / `  none` form are now read from `scripts/lib.sh:852-869` and `plan.sh`, and the 2.2, 3.2 and 5.2 checks match them; a missing line makes the verdict `FAIL`, which is the safe direction); whether the byte bound counts `_SUCCESS` and the manifest (counted here, conservatively) and which tables the row bounds count (2.10); `aws emr terminate-clusters` (S4); `aws emr list-instance-groups` (3.7, rejected in session 1); the flag forms of the 5.4 reads; macOS `date -v`; the `run-record.env` key names the greps select; whether credentials outlast a 75 to 90 minute run; EMR CloudWatch metric names other than `ContainerPendingRatio`; the transfer rate of 4.1; the `health_check_grace_period_seconds = 0` plan noise (infra-engineer's own residual).
8. Open decisions that are the human's: the 0.0 acknowledgement of the 7 changes, OD-1, OD-3 (heap rule), OD-7 (billing lag allowance), continuing to B after an A failure, any S2 decision otherwise. OD-2 (load-client tag equals the commit SHA) is settled for session 2 by infra and lands in the session 2 runbook.

## Validation evidence, separate from the pending gates

Nothing in this file was executed by me. Evidence that exists, read from files and not re-verified: the approved session 1 runbook (revision 3, code-reviewer review 2 APPROVE) whose commands are reused with new names; the session 1 record (identity, state checks, upload plan 42 objects and 1,038,128,626 bytes, plan `11 to add, 3 to change, 3 to destroy` with `IAM_CHANGES: none`, live definition values, the B failure at the 1,500 s Hadoop limit, the scale-out core 1 to 3 at 05:43:58Z, the down plan `0 to add, 1 to change, 8 to destroy`, the teardown confirmation); the diagnosis behind P15-23; the SP-2 report (`JsonlExportSuite` 26/0/0, 17 tables); the WO-7c selective load (nine tables, `sbt quick` 73/0/0 and 1041/0/0). The session 1 B digests were `cbf66f79...` (experiment) and `628795c6...` (pipeline); this session's are new.

Revision 1 adds no executed evidence either: its new text (the plan-verdict checks in 2.2, 3.2 and 5.2, the ExportTables jar check, the rows check, the 4.1 restructure, the watch commands, the 0.1 awk) was written by reading and not run, and has had no review yet. The Terraform behind the new expected plans is infra-engineer's UNVALIDATED repair batch 4.

Revision 2 (this edit, per `docs/agent-runs/phase-15/wave-2/review-3-verdict.md`: MAJOR-1, MINOR-3 and the `rows=` low note) was also written by reading and not run: the IAM test and the action-line scope in 2.2, 3.2 and 5.2, the run 6 identifiers in the b1 branch, and the 4.2b `rows=` check have had no execution and no review.

Pending gates: the 0.0 acknowledgements; P15-10 (0.1); Wave 2 HEAD check and the hard gate (0.3, 0.7); infra repair batch 4 validation (`terraform fmt` and `validate`, harness, independent review, the human's `sbt clean check`) before the Wave 2 commit; plan reviews and every W command (human approvals); there is no IAM privilege-diff approval in this session, and an `aws_iam_*` line is a stop (2.2); rendered-definition checks (2.3, 3.3) and live checks (2.5, 3.5); the size and row checks against the 128 MiB, 1,000-object, 250,000-row and 1,000,000-row limits (2.10); the heap measure (4.2) and the ratification of its sizing rule (4.3, OD-3); the independent read-only re-review of this revision; a fresh `sbt quick` after Wave 2 stops changing (after 5.5).

## Next gate

Independent read-only re-review of this revision by the code-reviewer through the orchestrator (no execution authority), covering only the changed items and what they newly touch; the orchestrator fills the SHA placeholder after the Wave 2 commit and copies the final text to `docs/agent-runs/phase-15/session-ab/human-commands.md` before the docs commit of 0.4. Then the human runs it in order and pastes the evidence of 6.3. The deploy-engineer writes the release records only after the evidence exists and 5.5 is done.
