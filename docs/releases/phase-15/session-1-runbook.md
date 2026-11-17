# Phase 15 session 1 runbook (DRAFT, revision 3: B-only tonight, nothing executed)

Author: deploy-engineer. Status: DRAFT for re-review, then the human. Revision 2 repairs review 1 (`docs/agent-runs/phase-15/session-1/runbook-review-1.md`: B1, M1 to M7, m1 to m5). The orchestrator copies this file into `docs/agent-runs/phase-15/session-1/human-commands.md` before the docs commit of step 0.2a.

I ran nothing: no AWS call, no Terraform, no script, no git write, no sbt. Every command below is a `!` paste for the human. Every behavior statement is read from a file and marked UNVERIFIED where no run has confirmed it. Nothing here is an approval: the human writes each approval record by running the W command of that action, after reading the plan or arguments it names.

Scope of session 1 (plan.md, decisions.md P15-2, P15-10, P15-12, P15-16, P15-20): Experiment A re-run on one primary node (verdict RE-RUN, `wave-1/reports/wo-03-experiment-engineer.md`), Experiment B on tier S data with EMR managed scaling, then the same-day session-down. Account, Region: us-east-2 only, profile `scholarchain`.

## B-only path (tonight, P15-22): the authoritative order

Decision P15-22 (`docs/agent-runs/phase-15/decisions.md`, 2026-11-17): tonight's session 1 is B only, from the down state straight to `elasticity-s`. A re-runs in a short separate session the next morning; its draft is `docs/releases/phase-15/session-1b-a-only-draft.md`. Where any text below disagrees with this section, this section wins. The A steps stay in this file for reference, each marked "NOT TONIGHT (A-only session, P15-22)".

Decision P15-21 (same file) records that the human confirmed the `b-burst` values `PARTITION_COUNT=16`, `SPLIT_MAX_BYTES=131072`, `MAP_MEMORY_MB=3072`, `REDUCE_MEMORY_MB=3072`. That satisfies the 0.1b precondition for tonight.

Order for tonight, exactly these step ids in this order (a step not listed is not run):

| # | Step | Note |
|---|---|---|
| 1 | 0.1 | billing gate with the B-only constants |
| 2 | 0.1b | precondition satisfied by P15-21; run only the risk-check command |
| 3 | 0.2a (stage and check, then commit), 0.2a2, 0.2b, 0.2c | docs-only commit (it includes both runbook files), state backup, stash, hard gate. Session rules S1 to S5 apply from the end of 0.2c |
| 4 | 1.1, 1.2 (cited, not run), 1.3, 1.4 | identity, quotas, state check, raw inputs |
| 5 | 1.5 | creates the B files and also the A manifest copy and A pipeline input, which are kept for tomorrow and not used tonight |
| 6 | 1.6B | B digests. 1.6A is skipped. 1.7 is only a pointer |
| 7 | 2.0.1, 2.0.2, 2.0.2b, 2.0.2c (i) and (ii), 2.0.3, 2.0.4 (D then R), 2.0.5 | build and upload, with the raw-input hash check (2.0.2b) and the stale-jar check (2.0.2c). All of 2.0 runs tonight and needs no session resources |
| 8 | 3.1, 3.2, 3.3 | plan the session-up from the down state to `elasticity-s`, read it, rendered-definition check |
| 9 | 3.4P, 3.4W, 3.4D, 3.4R | deploy. 3.4R starts the session clock S3 |
| 10 | 3.5 | live definition check |
| 11 | 3.6 (1.3 again, then W, then D) | B records and dry runs |
| 12 | 3.7 | the B run |
| 13 | 3.8a, 3.8b, 3.9, 3.10 | read-only; may wait until after step 4 (S3) |
| 14 | 3.11 (the B block only) | redaction copy of B |
| 15 | 4.1, 4.2, 4.3P, 4.3W, 4.3D, 4.3R, 4.4 | session-down and the confirmation |
| 16 | 4.5 | stash pop |
| 17 | 5.1, 5.2, 5.3 | evidence; ignore every A row |

Skipped tonight (all marked NOT TONIGHT in the headings): 1.6A, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.9b, 2.10, and the A block of 3.11. 2.0 stays because the node scripts and jars in S3 must be the Phase 15 ones before B runs, and because `deploy.sh` needs both build contexts: 2.0.1 stages `target/docker-local` and 3.4P, 3.4D, 3.4R and 4.3P to 4.3R pass it as `--services-context` and `--loader-context` (`deploy.sh` lines 64 to 66). Tonight 3.4R builds and pushes both images (the HEAD tag is new, so no `IMAGE_EXISTS`), so Docker must be running at 3.4R.

Session-up from the down state to `elasticity-s` (3.1). Nothing in tonight's order assumes an earlier `baseline-s` apply: the state is the Phase 14 session-down state (`deploy-down-5.md`: no Neptune, only the S3 and DynamoDB gateway endpoints, services at desired count 0), and 3.1 plans `course.tfvars` + `experiment-size-s.tfvars` + `elasticity-s.tfvars`. The expected plan is in 3.1 and the stop conditions in 3.2.

B-only HIGH and wall-clock limit (replaces the revision 2 figures, which included A):

| Item | USD (HIGH) | Arithmetic |
|---|---|---|
| B entry-point window | 2.40 | manifest `SPEND_CEILING_USD`; 1.55 USD/h x 90 min / 60 = 2.325, rounded to the ceiling |
| Cluster billing after B's stop (P15-20) | 0.13 | 1.55 USD/h x 300 s / 3600 = 0.129 |
| Idle endpoints outside the B window | 0.30 | 8 interface endpoints x 0.015 USD/h = 0.12 USD/h (UNVERIFIED rate), for up to 2.5 h = the 4 h wall-clock limit minus the 1.5 h B window (the B burn constant already contains its endpoints) = 0.30 |
| **B-only session HIGH** | **2.83** | 2.40 + 0.13 + 0.30 |

Wall-clock limit for B only: 4 hours, counted from the start of 3.4R. Arithmetic: expected timeline is the apply and image push about 20 min, live check, records and dry runs about 30 min, B run at most 90 min, then collect-free session-down (plan, read, records, apply) about 45 min = 185 min = 3.1 h, so 4 h leaves about 55 min of slack. B (3.7) must start no later than 1 h 30 min after the 3.4R start (1.5 h + the 1.5 h B window + 0.75 h session-down = 3.75 h, inside 4 h). The session-down apply (4.3R) must start no later than 4 hours after the 3.4R start. Each hour beyond costs about 0.12 USD more.

P15-10 for B only: billed spend plus 2.83 must be at most 14 USD, so billed must be at most 11.17. The ratification.md figure of 3.94 USD gives 6.77, a GO. The plan's own figure for B alone is its ceiling 2.40 (limit 11.60). The 0.1 gate command uses 2.83 and 2.40.

## Read this first

Conventions used in every command:
- Each command is self-contained: it sets its own variables and does not rely on shell state from an earlier `!` line.
- `set -o pipefail;` first, output `tee`'d to `/Users/winnguyen/scholarchain-data/logs/phase15-s1-<step>.log`, ending `| tail -n 60; echo "exit: $?"`. Only braced variables are used (zsh-safe).
- The account id is never typed. A command that needs it reads it with `ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"` and passes `"${ACCOUNT_ID}"`. The scripts compare it with the caller identity and the approval record (`lib.sh` `verify_identity`, `verify_approval`).
- Redaction (review m1). Script output carries the account id (`ARGS_CANONICAL ... account=`, `APPROVAL_VERIFIED ... account=`, `IDENTITY_OK account=`, `TARGET_ACCOUNT=`: `lib.sh` lines 368, 548, 730, 773) and bucket names (upload and deploy output). Every command whose output can contain it pipes through `sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g"` before `tail`, so the screen and the chat never show it. The `tee` sits before the `sed`, so the unredacted log goes only to `/Users/winnguyen/scholarchain-data/logs/`. Keep those logs local. (`${ACCOUNT_ID:-NOACCOUNT}` stops an empty variable from turning the sed into an empty pattern.)
- Exit lines (review m2). In the four two-part commands (1.6A, 1.6B, 2.6D, 3.6D) the final `exit: 0` is the status of the last pipeline, so it is not the verdict. The verdict is the pair of `part1 exit:` and `part2 exit:` lines printed before it; both must be 0.
- Noclobber (review m3). A file written with `set -C` that already exists fails with `cannot overwrite existing file` in bash and `file exists` in zsh. Either message means the same thing: the file is already there.
- `REPO=/Users/winnguyen/ScholarChain`, `SCD=/Users/winnguyen/scholarchain-data`, `ENVDIR=/Users/winnguyen/ScholarChain/infra/terraform/environments/course`. The name prefix is `scholarchain-course` (`lib.sh` `SC_NAME_PREFIX="${SC_PROJECT}-${SC_ENV}"`).
- The record writer uses macOS `date -u -v+8H` for the expiry. UNVERIFIED on this machine; `iso_epoch` in `lib.sh` tries GNU `date -d` first, then BSD `date -j -f`, which suggests the operator machine is BSD. If `-v` fails, the W command prints an error and writes nothing.

Prerequisites that are not in the brief but that the scripts require (each is a step below, not an assumption):
1. A clean working tree at a commit whose code equals 085b5ec (the Wave 1 commit). `lib.sh` `tree_status` runs `git status --porcelain --untracked-files=normal`, so untracked files count as dirty, and `deploy.sh` (lines 105 to 106) and `run-experiment.sh` refuse a dirty tree, including the session-down at 4.3R. Terraform state is local to this checkout (no backend block in `infra/terraform`; `.gitignore` lines 7 to 9 ignore `*.tfstate`, per review B1), so a second checkout would plan to recreate everything, and `package.sh` (lines 146 to 156) refuses a detached HEAD. So the session runs in this checkout on `main`, with Wave 2 code parked in a stash (step 0.2). This is the default procedure and a human decision point: the human may choose another way, but the hard gate of 0.2c is not optional.
2. The on-node scripts and job jars must be uploaded to S3 again. `scripts/emr/*.sh`, `spark-yarn.conf` (the `a-baseline` and `b-burst` profiles) and the Hadoop and Spark jars changed in Phase 15. The cluster reads `s3://<artifacts>/bootstrap/` (`workflow/main.tf` `script_prefix`, `FetchScripts`). Without step 2.0 the run uses Phase 14 scripts and no profile. Step 2.0 needs no session resources, so it runs before session-up and costs no endpoint hours.
3. `deploy.sh` needs `--services-context` and `--loader-context` directories holding `lib/` (`deploy.sh` lines 64 to 66), so a local build (`package.sh`, step 2.0.1) precedes every deploy even though session 1 serves no request.

## Session rules (apply from step 0.2c until step 4.5 is done)

S1. No repository writes and no sbt by anyone but the human's own `package.sh` run (review M1). From the end of 0.2c until 4.5 the orchestrator and every agent make no repository write (no evidence file, no edit, no commit) and run no sbt. A write makes the tree dirty and `deploy.sh` then refuses, including the session-down at 4.3R, which would leave the endpoints billing. During the session the paste-back evidence goes to `/Users/winnguyen/scholarchain-data/logs/` (command 5.1) or into the chat. It is recorded in the repository only after 4.5. The docs the session needs (this runbook, the wave evidence) are committed in 0.2a, before the gate; do not edit them afterwards.

S2. Any stop from the session-up apply onward (3.4R tonight; 2.4R in the A-only session) means "go to step 4" (review M2). From the session-up apply the endpoints bill about 0.12 USD/h (8 interface endpoints x 0.015, HIGH, UNVERIFIED rate). A stop that says "report" from that point on also means: report, then go to step 4 (session-down) unless the human decides otherwise within 15 minutes. A "decision otherwise" is the human's, in writing in the chat, with a stated end time. Stops before the session-up apply (tonight: steps 0 to 3.3; 2.0 only writes S3 objects) change nothing billable in the cloud, so they mean report and wait, with one exception: if 0.2b has already run, finish with 4.5 (restore the Wave 2 stash) when the session is abandoned.

S3. Session wall-clock limit (review m5; B-only values for tonight, P15-22). The session clock starts when 3.4R starts. The session-down apply (4.3R) must start no later than 4 hours after that. B (3.7) must not start later than 1 hour 30 minutes after it; if that time has passed, go to step 4 without running B. The HIGH assumes 4 hours; each hour beyond costs about 0.12 USD more (HIGH). A run in progress is never interrupted by hand: the script cap (88 min for B) ends it. The A-only session has its own limits (3.5 h, A start within 1 h) in `session-1b-a-only-draft.md`.

S4. Emergency action, a leftover EMR cluster (review M6). Console mutation is prohibited. If a command prints `EMR cluster still active after the run` or the 1.3 state check shows an active `scholarchain-course-pipeline` cluster when none should run, the human runs these two commands, in this order, and the orchestrator records them as a human emergency action (log, UTC time, cluster id, reason).

E1 lookup (read-only):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; AWS_PROFILE=scholarchain aws emr list-clusters --active --region us-east-2 --query "Clusters[?Name=='scholarchain-course-pipeline'].[Id,Status.State]" --output text 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-emergency-lookup-${STAMP}.log | tail -n 60; echo "exit: $?"
```

E1 terminate (one exact line; it terminates the single active cluster of that name and nothing else; it counts the active clusters named `scholarchain-course-pipeline` and terminates only when the count is exactly one and the id looks like `j-...`; with zero or two or more it terminates nothing and exits non-zero, and the human decides by hand):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; IDS="$(AWS_PROFILE=scholarchain aws emr list-clusters --active --region us-east-2 --query "Clusters[?Name=='scholarchain-course-pipeline'].Id" --output text)"; N="$(printf '%s' "${IDS}" | wc -w | tr -d ' ')"; if [[ "${N}" == "1" && "${IDS}" =~ ^j-[A-Z0-9]+$ ]]; then AWS_PROFILE=scholarchain aws emr terminate-clusters --cluster-ids "${IDS}" --region us-east-2; echo "terminate requested for ${IDS} at ${STAMP}"; else echo "found ${N} active cluster(s) named scholarchain-course-pipeline: nothing terminated (exactly one is required)"; false; fi 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-emergency-terminate-${STAMP}.log | tail -n 60; echo "exit: $?"
```

UNVERIFIED: `aws emr terminate-clusters --cluster-ids` is the current CLI verb from my knowledge; the repository does not use it (the scripts call `aws emr terminate-job-flows --job-flow-ids`, `run-bounded-pipeline.sh` lines 409 and 488, which is the older name of the same API). If `terminate-clusters` is rejected as an unknown command, replace `terminate-clusters --cluster-ids` with `terminate-job-flows --job-flow-ids` in the same line (the repository's own form). Afterwards re-run E1 lookup: it must print nothing.

S5. No persistent permission prompts (review 2, risk). During the session accept only one-time permission prompts. A persistent "always allow" writes `.claude/settings.local.json` (a repository path); the 0.1b risk check shows whether it is ignored, but the rule holds anyway, because a write there is a repository write (S1).

## Session 1 HIGH and the P15-10 arithmetic

| Item | USD (HIGH) | Basis |
|---|---|---|
| A, entry-point window | 0 tonight | NOT TONIGHT (A-only session, P15-22); its 0.75 is in `session-1b-a-only-draft.md` |
| B, entry-point window | 2.40 | manifest `SPEND_CEILING_USD=2.40` (P15-16); 1.55 x 90 / 60 = 2.325. The pipeline stops at 88 min = 2.27 (`experiments/b-burst/run.sh`) |
| Plan figure (B ceiling alone, tonight) | 2.40 | decisions.md P15-16 gave 3.15 for A + B; B alone is its 2.40 ceiling |
| Idle-endpoint allowance outside the B window | 0.30 | my arithmetic, not in the plan: 8 interface endpoints x 0.015 USD/h (COST_ESTIMATE U6, UNVERIFIED rate, HIGH) = 0.12 USD/h, for up to 2.5 h = the 4 h B-only wall-clock limit (S3) minus the 1.5 h B window (whose endpoint cost is already inside the burn constant) |
| Cluster billing after B's stop (P15-20) | 0.13 | review m5: if B's 88-minute stop fires, the cluster is terminated but the teardown check can run up to 300 s (`run-bounded-pipeline.sh` lines 482 to 491); 1.55 USD/h x 300 / 3600 = 0.13 |
| **B-only session HIGH used for the gate** | **2.83** | 2.40 + 0.30 + 0.13 (revision 2 with A: 3.68) |

The burn constants 0.60 and 1.55 already contain the 8 endpoints (0.12/h) and a loader task (0.0247/h) that never starts (`pipeline_skip_loader`), so inside a run window the figure is conservative. Bedrock is off, so there is no ninth endpoint. Neptune is off.

Stop rule P15-10 (decisions.md): billed spend plus the session HIGH is at most 14 USD, so for tonight (B only) billed must be at most 11.17 (with 2.83) or 11.60 (with B's plan ceiling 2.40). Ratification.md recorded 3.94 USD billed after Phase 14, which gives 6.77 and a GO. Two cautions for the human reading Billing:
- Billing data can lag by up to about a day (UNVERIFIED general AWS behavior). Phase 14's own arithmetic in `docs/releases/phase-14/README.md` ("Spend") gives roughly 10.8 to 13.3 USD for runs 1 to 11 under named cases, against 3.94 billed. If the Billing figure you paste does not yet include 2026-11-16 usage, say so. The rule is written on billed spend; the gap is the human's judgement, not mine.
- Paste the gross month-to-date figure (before credits) so the number is conservative. Whether the 20 USD spend limit counts gross or net is UNVERIFIED (`COST_ESTIMATE.md`, "What the figures leave out").

Expected cost per step (my arithmetic on UNVERIFIED rates; not a bill):

| Step | What bills | Expected | HIGH |
|---|---|---|---|
| 0, 1 | nothing (reads, local files) | 0 | 0 |
| 2.0 | local build; S3 PUT of about 0.7 GB (negligible, rate UNVERIFIED) | 0 | 0 |
| 3.1 to 3.5 B plan, session-up deploy | endpoints from the apply (about 20 min incl. image push) | 0.02 | 0.04 |
| 2.1 to 2.10 A steps | NOT TONIGHT (A-only session, P15-22) | 0 | 0 |
| 3.5 to 3.6 live check, B records, dry runs | endpoints idle about 30 min | 0.03 | 0.06 |
| 3.x B run | primary + 1 to 3 core nodes, about 72 min realistic (WO-3, `elasticity-s.tfvars`: about 4,300 s with the 900 s Wait) to 88 min cap, plus up to 300 s of cluster time after a stop | 1.20 to 1.90 | 2.53 |
| 4.x session-down plan, records and apply (3.8 to 3.11 collection is read-only and may follow it) | endpoints idle about 45 min | 0.05 | 0.09 |
| Wall-clock reserve (S3) | endpoints idle up to the 4 h limit | 0 | about 0.11 |
| **Total (B only)** | | **about 1.3 to 2.0** | **2.83** |

## Approval record, exact fields

Every approval-gated script reads one record: a plain `KEY=VALUE` file, `#` comments allowed, no spaces in values, every key exactly once, no other keys (`scripts/README.md`, "Approval record"; `lib.sh` line 393). The 14 keys are `APPROVAL_VERSION ACTION ENVIRONMENT ACCOUNT_ID REGION COST_CEILING_USD PLAN_DIGEST SCRIPT_DIGEST ARGS_DIGEST GIT_SHA STATEFUL_CHANGE_ACKNOWLEDGED APPROVED_BY APPROVED_AT EXPIRES_AT`.

| Action | `ACTION` | `PLAN_DIGEST` | `GIT_SHA` | `COST_CEILING_USD` (must equal `--cost-ceiling-usd`) |
|---|---|---|---|---|
| upload inputs | `upload` | `n/a` | `n/a` | 20 |
| deploy (up, switch, down) | `deploy` | `PLAN_DIGEST=` printed by `plan.sh` | HEAD, 40 hex | 20 |
| pipeline (inside an experiment) | `pipeline` | `n/a` | `n/a` | 20 (hard-coded in `experiments/lib/exp-lib.sh` line 87) |
| experiment | `experiment` | `n/a` | HEAD, 40 hex | A 0.75, B 2.40 (the manifest `SPEND_CEILING_USD`; the human confirms, see open items) |

`ENVIRONMENT=course`, `REGION=us-east-2`, `ACCOUNT_ID` from STS, `STATEFUL_CHANGE_ACKNOWLEDGED=no` unless `plan.sh` printed `STATEFUL_CHANGE=yes` (none is expected in session 1; `deploy.sh` refuses with "destroy removes stateful resources" if the plan says yes and the record says no, in which case stop). `APPROVED_BY` must not look like an agent or role (`lib.sh` lines 519 to 523); the W commands write `winnguyen`, change it if you prefer another name without spaces. `APPROVED_AT` is now (not in the future). `EXPIRES_AT` is at most 24 hours later (`SC_APPROVAL_MAX_SECONDS=86400`); the W commands use 8 hours, as Phase 14 did. A record is single use: a failed run still consumes it, so a retry needs a new record. Any edit to a script or to `lib.sh` after the digest is printed voids the record (`SCRIPT_DIGEST` is the script followed by `lib.sh`).

Each action has up to four commands: P prints the digests, W writes the record, D is a `--dry-run` (checks the record and arguments, calls no cloud command, consumes nothing: `lib.sh` `dry_guard`), R is the real run.

## Rendered values to expect (checked at 2.3, 2.5, 3.3, 3.5)

The profile is not in the execution input. The input contract refuses unknown keys (`run-bounded-pipeline.sh` lines 169 to 178). The profile reaches the node as the optional sixth step argument that the state machine adds when the Terraform variable `pipeline_profile` is non-empty (`workflow/main.tf` line 112 `profile_arg`, line 223 `"Args.$" = "States.Array('bash', ..., $.run_id${local.profile_arg})"`). So the check is on the rendered or deployed state machine definition, not on the input file.

| Value in the definition | A (`baseline-s`) | B (`elasticity-s`) |
|---|---|---|
| `ComputeLimits` object | none (core 0: no `ManagedScalingPolicy`, R4 report table) | exactly one: `MaximumCapacityUnits` 3, `MaximumCoreCapacityUnits` 3, `MaximumOnDemandCapacityUnits` 3, `MinimumCapacityUnits` 1, `UnitType` `Instances` |
| `IdleTimeout` | 900 (the course default, `course.tfvars` line 31 `emr_idle_timeout_seconds = 900`; `baseline-s.tfvars` does not set it) | 1800 |
| Wait state | none | one (`WaitBeforeTerminate`), `Seconds` 900 |
| `TimeoutSeconds` values (review M5: eight values, `workflow/main.tf` lines 116, 147, 173, 209, 228, 239, 281, 296; the regex lists them in the file's order, so compare them as a set) | 7200 (execution), 1440 (create), 300 (fetch), 4800 (capability step), 4800 (pipeline step), 900 (terminate), 600 (loader), 900 (cleanup) | the same, with 4200 for both step states: 7200, 1440, 300, 4200, 4200, 900, 600, 900 |
| `AfterTerminate` state | absent (`pipeline_skip_loader`) | absent |
| `RunLoader` state | present (loader-only path) | present |
| profile string in the `PipelineStep` Args | `a-baseline` present, `b-burst` absent | `b-burst` present, `a-baseline` absent |

Any difference is a stop. The B limits come from `modules/emr/main.tf` lines 80 to 88 and the R4 report; the Wait and skip-loader logic from `workflow/main.tf` lines 93 to 108.

---

## Step 0. Gate and pin the commit

### 0.1 Billing gate (P15-10)

Read the month-to-date billed total in the AWS Billing console (gross, before credits). Replace `REPLACE_WITH_BILLED_TOTAL` with the plain number (for example `3.94`) and paste. Do not change the Region or the spend limit and do not use "Activate advanced features" (Phase 14 constraint).

```
set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs; BILLED_USD=REPLACE_WITH_BILLED_TOTAL; if [[ "${BILLED_USD}" =~ ^[0-9]+(\.[0-9]+)?$ ]]; then awk -v b="${BILLED_USD}" 'BEGIN { h1 = 2.83; h0 = 2.40; printf "P15-10 billed=%.2f limit=14.00\n  session1_high=%.2f total=%.2f -> %s\n  plan_high=%.2f total=%.2f -> %s\n", b, h1, b + h1, (b + h1 <= 14 ? "GO" : "STOP"), h0, b + h0, (b + h0 <= 14 ? "GO" : "STOP"); exit (b + h1 <= 14 ? 0 : 3) }'; else echo "BILLED_USD is not a plain number: STOP"; false; fi 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-billing-gate.log | tail -n 60; echo "exit: $?"
```

Expected: `-> GO` on the session1_high line and `exit: 0` (3.94 gives 6.77; the plan_high line uses B's 2.40 ceiling and gives 6.34). Stop condition: `STOP`, `exit: 3`, or a non-number. Then no step below runs; report to the orchestrator. This is before any git change, so nothing needs restoring.

### 0.1b Preconditions and risk checks (before 0.2a)

Satisfied tonight by decision P15-21 (`docs/agent-runs/phase-15/decisions.md`: the human confirmed `PARTITION_COUNT=16`, `SPLIT_MAX_BYTES=131072`, `MAP_MEMORY_MB=3072`, `REDUCE_MEMORY_MB=3072`). The rule stays for later sessions. Stated precondition, not a command (review 2 note 6): the experiment owner has confirmed the `b-burst` profile values in writing in the chat before 0.2a. `scripts/emr/spark-yarn.conf` still calls `b-burst.PARTITION_COUNT=16`, `SPLIT_MAX_BYTES=131072`, `MAP_MEMORY_MB=3072` and `REDUCE_MEMORY_MB=3072` "PROPOSED values, to be confirmed by the experiment owner before the run". The file is uploaded from the committed tree in 2.0 and S1 forbids a later commit, so a change after 0.2a is not possible in this session. No confirmation, no 0.2a.

Risk check 1, the local settings file. A persistent permission prompt ("always allow") writes `.claude/settings.local.json`; if that file is tracked, or is untracked and not ignored, the write dirties the tree and `deploy.sh` then refuses, including the session-down. This prints whether the file is ignored and whether it is tracked, and whether `course.tfvars` differs from 085b5ec (information only: a difference here is Wave 2 work that the stash of 0.2b will park; the gate of 0.2c is what decides).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs; ( echo "--check-ignore"; git check-ignore -v .claude/settings.local.json; if git check-ignore -q .claude/settings.local.json; then echo "SETTINGS_LOCAL_IGNORED=yes"; else echo "SETTINGS_LOCAL_IGNORED=NO: STOP and tell the human"; fi; echo "--ls-files (expect empty)"; T="$(git ls-files .claude/settings.local.json)"; echo "${T}"; if [[ -n "${T}" ]]; then echo "SETTINGS_LOCAL_TRACKED=YES: STOP and tell the human"; else echo "SETTINGS_LOCAL_TRACKED=no"; fi; echo "--course.tfvars versus 085b5ec (information only)"; git diff --stat 085b5ec -- infra/terraform/environments/course/course.tfvars; [[ -z "${T}" ]] && git check-ignore -q .claude/settings.local.json ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-risk-checks.log | tail -n 60; echo "exit: $?"
```

Expected: a `.gitignore` rule line, `SETTINGS_LOCAL_IGNORED=yes`, an empty ls-files section, `SETTINGS_LOCAL_TRACKED=no`, an empty `course.tfvars` diff (or a stat line, informational), `exit: 0`. Stop: the file is tracked, or not ignored. Tell the human; do not go on until they decide. In every case, rule S5 below applies: accept no persistent permission prompts during the session.

### 0.2 Pin the session to the Wave 1 code (review B1; the human's decision point)

Why: the working tree holds Wave 2 files (modified and untracked), the tree must be clean for `deploy.sh` and `run-experiment.sh`, and the Terraform state is local to this checkout. The default procedure commits only docs, parks all Wave 2 code in a stash, and proves by a hard gate that HEAD's code equals 085b5ec. The human may choose a different procedure; whichever is used, the gate in 0.2c must pass before step 1. Nothing else in this runbook edits the repository.

0.2a Docs-only commit. The two paths named are the only ones added. The command stages them and then checks that every staged path starts with `docs/`; the commit command (second block) is run only if the check says `STAGED_ONLY_DOCS=yes`. It commits only what is staged (no `-a`), so the modified Wave 2 files stay unstaged. The orchestrator must already have copied this runbook to `docs/agent-runs/phase-15/session-1/human-commands.md`, and `docs/releases/phase-15/session-1-runbook.md` must be in its final form: edits after this commit dirty the tree.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs; git add docs/releases/phase-15 docs/agent-runs/phase-15; NON_DOCS="$(git diff --cached --name-only | grep -v '^docs/')"; if [[ -z "${NON_DOCS}" ]]; then echo "STAGED_ONLY_DOCS=yes files=$(git diff --cached --name-only | wc -l | tr -d ' ')"; git diff --cached --name-only | head -n 40; else echo "STAGED_NON_DOCS=STOP, these staged paths are not under docs/:"; echo "${NON_DOCS}"; false; fi 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-docs-stage.log | tail -n 60; echo "exit: $?"
```

Expected: `STAGED_ONLY_DOCS=yes`, a file list that is only `docs/releases/phase-15/...` and `docs/agent-runs/phase-15/...`, `exit: 0`. Stop: `STAGED_NON_DOCS=STOP` (something outside `docs/` was already staged: unstage that path by hand, for example `git restore --staged <path>`, and run the check again; never commit it).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; git commit -m "Phase 15 session 1 preparation: runbook and phase evidence (docs only)" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-docs-commit.log | tail -n 60; echo "exit: $?"
```

Expected: a commit summary listing only files under `docs/`, `exit: 0`. If there is nothing to commit (the docs were already committed), the command says so and exits 1: that is fine, go on.

0.2a2 Optional backup of the local Terraform state (before 0.2b). The state lives in the ignored files `terraform.tfstate*` of the course environment and is not touched by the stash, but a copy costs nothing. `cp -p` keeps the modes and times, `-n` refuses to overwrite an existing copy, the destination is outside the repository, and the command prints names and sizes only, never contents.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs /Users/winnguyen/scholarchain-data/state-backup-p15-s1; ( cp -p -n /Users/winnguyen/ScholarChain/infra/terraform/environments/course/terraform.tfstate* /Users/winnguyen/scholarchain-data/state-backup-p15-s1/; echo "cp exit=$?"; ls -l /Users/winnguyen/scholarchain-data/state-backup-p15-s1/ ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-state-backup.log | tail -n 60; echo "exit: $?"
```

Expected: `cp exit=0` and the listing shows `terraform.tfstate` and `terraform.tfstate.backup`. If the copies already exist from an earlier try, `-n` skips them silently (the listing then shows the older times: read them). Do not paste the files. Skipping this step is allowed; nothing later depends on it.

0.2b Park all Wave 2 work (modified and untracked). `-u` includes untracked files; ignored files (the Terraform state, `target/`, the test marker) are not stashed and stay where they are (`.gitignore` lines 7 to 9, per review B1).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; git stash push -u -m p15-wave2-wip 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-stash-push.log | tail -n 60; echo "exit: $?"
```

Expected: `Saved working directory and index state On main: p15-wave2-wip`, `exit: 0`. Stop: any error. If the stash fails part way, do not continue: run `git stash list` and `git status --short` and report.

0.2c Hard gate. It must print `GATE=PASS`. It requires a completely empty `git status --short`, no difference at all between 085b5ec and HEAD outside `docs/` (`git diff --quiet 085b5ec HEAD -- . ':(exclude)docs'`; it exits 0 when there is no difference, and the pathspec covers every path, not a list), and the branch to be `main` (a detached HEAD or another branch fails: `package.sh` refuses a detached HEAD).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs; ( S="$(git status --short | wc -l | tr -d ' ')"; git diff --quiet 085b5ec HEAD -- . ':(exclude)docs'; D=$?; BR="$(git symbolic-ref --short -q HEAD)"; echo "HEAD=$(git rev-parse HEAD)"; echo "BRANCH=${BR:-DETACHED}"; echo "LAST_COMMIT=$(git log --oneline -1)"; echo "STATUS_SHORT_LINES=${S}"; git status --short | head -n 20; echo "CODE_DIFF_085b5ec_TO_HEAD_EXIT=${D} (0 = no difference)"; git diff --stat 085b5ec HEAD -- . ':(exclude)docs' | tail -n 20; echo "STASH_LIST:"; git stash list | head -n 5; if [[ "${S}" == "0" && "${D}" == "0" && "${BR}" == "main" ]]; then echo "GATE=PASS"; else echo "GATE=FAIL: STOP (needs an empty status, no non-docs difference from 085b5ec, and branch main, not detached)"; false; fi ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-tree-head.log | tail -n 60; echo "exit: $?"
```

Expected: `BRANCH=main`, `STATUS_SHORT_LINES=0`, `CODE_DIFF_085b5ec_TO_HEAD_EXIT=0`, an empty diff stat, one stash entry named `p15-wave2-wip`, `GATE=PASS`, `exit: 0`. HEAD printed here is the commit every plan, image tag, deploy record and experiment record in this session is bound to (it is the docs commit on top of 085b5ec, so its Terraform and scripts are 085b5ec's). Stop: `GATE=FAIL`. Do not delete, reset or discard anything; report the status lines or the diff stat to the orchestrator. After a pass, session rule S1 applies until 4.5.

---

## Step 1. Read-only pre-checks and local preparation

Nothing in step 1 changes a cloud resource. 1.5 writes files under `scholarchain-data/` only. Credentials must stay valid for the whole session: the A run is up to 75 min and B up to 90 min, and a credential that expires mid-run stops the script's polling and its wall-clock stop (the state machine's own 7200 s timeout is then the only bound).

### 1.1 Identity, profile, Region

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs; (echo "profile=scholarchain"; echo "profile_region=$(AWS_PROFILE=scholarchain aws configure get region)"; CALLER="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; if [[ "${CALLER}" =~ ^[0-9]{12}$ ]]; then echo "caller_account=12-digit id (not printed)"; else echo "caller_account=NOT A 12-DIGIT ID: STOP"; fi; echo "approved_region=us-east-2") 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-identity.log | tail -n 60; echo "exit: $?"
```

Expected: `profile_region=us-east-2` (blank means the profile has no default Region; every command here passes `--region us-east-2` anyway, so blank is not a stop) and `caller_account=12-digit id (not printed)`. Stop: not a 12-digit id (expired or wrong credentials). The scripts re-check the account against the approval record at each real step. UNVERIFIED how the `scholarchain` profile is configured (static keys or a session); if it is a session, refresh it before each run.

### 1.2 Quotas (already done, cited, not re-run)

Human read-only checks of 2026-11-17 (`wave-1/checks.md`, `wave-1/doc-verification.md`):
- EC2 `L-1216C47A` Running On-Demand Standard instances: applied 32.0 vCPU. B peaks at 4 x m5.xlarge = 16 vCPU (4 vCPU each); A uses 4. Fits.
- Fargate `L-3032A538`: applied 30.0 vCPU. Session 1 starts no Fargate task (`pipeline_skip_loader`, `service_desired_count=0`).
- Bedrock is off in session 1 (`enable_bedrock=false`); the P15-13 re-check is for session 2.

### 1.3 State check (dead-letter queues, clusters, executions, EC2)

Run this now, and again immediately before each run (2.6 and 3.6). It writes a new stamped log each time. Why it matters: both experiments run with `--stop-on first-failure,dlq-nonempty` (`experiments/lib/exp-lib.sh` line 93), and the monitor loop stops the execution at the first poll if a dead-letter queue holds a message (`run-bounded-pipeline.sh` lines 450 to 457); the pipeline script also refuses to start if an execution is RUNNING or an EMR cluster named `scholarchain-course-pipeline` is active (lines 324 to 327), after the experiment record has already been consumed (`run-experiment.sh` start-run step).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; mkdir -p /Users/winnguyen/scholarchain-data/logs; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; echo "--utc ${STAMP}"; echo "--dlq-run-requests (expect 0 0)"; aws sqs get-queue-attributes --queue-url "$(aws sqs get-queue-url --queue-name scholarchain-course-run-requests-dlq --region us-east-2 --query QueueUrl --output text)" --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible --region us-east-2 --output text; echo "--dlq-embedding-work (expect 0 0)"; aws sqs get-queue-attributes --queue-url "$(aws sqs get-queue-url --queue-name scholarchain-course-embedding-work-dlq --region us-east-2 --query QueueUrl --output text)" --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible --region us-east-2 --output text; echo "--emr-active-clusters (expect empty)"; aws emr list-clusters --active --region us-east-2 --query 'Clusters[].[Id,Name,Status.State]' --output text; echo "--sfn-running-executions (expect empty)"; aws stepfunctions list-executions --state-machine-arn "$(aws stepfunctions list-state-machines --region us-east-2 --query "stateMachines[?name=='scholarchain-course-pipeline'].stateMachineArn | [0]" --output text)" --status-filter RUNNING --region us-east-2 --query 'executions[].[name,status]' --output text; echo "--ec2-instances-pending-or-running (expect empty)"; aws ec2 describe-instances --region us-east-2 --filters "Name=instance-state-name,Values=pending,running" --query 'Reservations[].Instances[].[InstanceId,InstanceType]' --output text ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-state-check-${STAMP}.log | tail -n 60; echo "exit: $?"
```

The DLQ commands are the README items 4a and 4b with the course prefix filled in (`scripts/README.md` "Phase 15 pre-session checks", item 4; queue names `<name_prefix>-run-requests-dlq` and `<name_prefix>-embedding-work-dlq`). The state-machine lookup is the one the pipeline script uses (`run-bounded-pipeline.sh` line 322). `ec2 describe-instances`, `emr list-clusters --active` and `stepfunctions list-executions --status-filter RUNNING` are standard read verbs; their flags are UNVERIFIED against the CLI reference in this session (the aws-documentation tools were not available to me), but `emr list-clusters --active` and `--status-filter RUNNING` appear in `run-bounded-pipeline.sh` lines 324 and 326.

Expected: `0 0` under both DLQ labels (an `ATTRIBUTES` line, values `0 0`), every other section empty. Stop: a non-zero DLQ depth, an active cluster, a running execution or a running instance. Report to the orchestrator; do not purge or terminate anything here (data deletion needs a human-approved runbook; a leftover cluster during or after the session is handled by S4). If this runs after 2.4R, S2 applies.

### 1.4 Input files are in S3 (read-only)

The A and B pipeline inputs name snapshot `phase5-dblp-subset` (`experiments/pipeline-inputs/s-a-baseline.template.json`). That is the S3 prefix Phase 14 wrote: `datasets/tier-s/snapshot-s.json` line 2 ("The S3 keys written by the Phase 14 upload are raw/phase5-dblp-subset/<file>") and line 51 (`subset_id`). Confirm the raw prefix exists before spending.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; DATA_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw data_bucket)"; echo "--raw/phase5-dblp-subset/ (expect dblp-subset.xml, openalex.jsonl, crossref.jsonl, orcid.jsonl)"; aws s3 ls "s3://${DATA_BUCKET}/raw/phase5-dblp-subset/" --region us-east-2 ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-raw-listing.log | tail -n 60; echo "exit: $?"
```

Expected: four objects (sizes 2111266, 940131, 174011 and 19675 bytes per `snapshot-s.json`). Stop: fewer, or an error from `terraform output` (it reads local state; `.terraform/` must exist from an earlier `plan.sh`).

### 1.5 Prepare the manifest copies and the pipeline inputs (local files only)

The committed manifests hold placeholder `ACCOUNT_ID=123456789012` and placeholder `OUTPUT_ROOT=/abs/outside/repo/scholarchain-experiments` (their headers say so). `run-experiment.sh` compares `ACCOUNT_ID` with `--account-id` (line 153) and binds the manifest by sha256 (line 249), so the human runs from a copy with the real account id and an output root outside the repository. The copy is bound by the record; the committed file is not edited. The pipeline input templates hold a placeholder run id; a new lower-case UUID is required (`run-bounded-pipeline.sh` lines 194 to 211; `scripts/README.md` "Execution input contract"). A and B each get their own run id.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; REPO=/Users/winnguyen/ScholarChain; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; mkdir -p ${SCD}/logs ${SCD}/experiments/manifests ${SCD}/experiments/runs ${SCD}/pipeline-inputs ${SCD}/approvals ${SCD}/plans; if [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ ]]; then (set -C; sed -e "s/^ACCOUNT_ID=.*/ACCOUNT_ID=${ACCOUNT_ID}/" -e "s#^OUTPUT_ROOT=.*#OUTPUT_ROOT=${SCD}/experiments/runs#" ${REPO}/experiments/manifests/experiment-a-baseline.manifest > ${SCD}/experiments/manifests/p15-s1-a-baseline.manifest) && (set -C; sed -e "s/^ACCOUNT_ID=.*/ACCOUNT_ID=${ACCOUNT_ID}/" -e "s#^OUTPUT_ROOT=.*#OUTPUT_ROOT=${SCD}/experiments/runs#" ${REPO}/experiments/manifests/experiment-b-burst.manifest > ${SCD}/experiments/manifests/p15-s1-b-burst.manifest) && A_RUN="run-v1-$(uuidgen | tr 'A-Z' 'a-z')" && B_RUN="run-v1-$(uuidgen | tr 'A-Z' 'a-z')" && (set -C; sed "s/run-v1-REPLACE-WITH-A-NEW-LOWERCASE-UUID/${A_RUN}/" ${REPO}/experiments/pipeline-inputs/s-a-baseline.template.json > ${SCD}/pipeline-inputs/p15-s1-a.json) && (set -C; sed "s/run-v1-REPLACE-WITH-A-NEW-LOWERCASE-UUID/${B_RUN}/" ${REPO}/experiments/pipeline-inputs/s-b-burst.template.json > ${SCD}/pipeline-inputs/p15-s1-b.json) && echo "A_RUN=${A_RUN}" && echo "B_RUN=${B_RUN}" && grep -HE '^(EXPERIMENT_ID|DATASET_SNAPSHOT|SIZE_TIER|MAX_MINUTES|MAX_CONCURRENCY|MAX_TASKS|SPEND_CEILING_USD|HOURLY_BURN_USD|OUTPUT_ROOT|ENTRY_POINT)=' ${SCD}/experiments/manifests/p15-s1-a-baseline.manifest ${SCD}/experiments/manifests/p15-s1-b-burst.manifest; else echo "no 12-digit account id from STS: STOP"; false; fi 2>&1 | tee ${SCD}/logs/phase15-s1-prepare-inputs.log | tail -n 60; echo "exit: $?"
```

Expected: `A_RUN=run-v1-<uuid>` and `B_RUN=run-v1-<uuid>` (different), the manifest limits as in the table below, `exit: 0`. The A manifest copy and the A pipeline input are for tomorrow's A-only session (P15-22) and are not used tonight. The two run ids are not secrets; the command prints no account id (the `ACCOUNT_ID=` line is not in the grep). Every file is written with noclobber (`set -C`), so a second run of this command fails (`file exists` or `cannot overwrite existing file`) rather than overwriting a file a record already binds: if you must redo it, delete the four files by hand first and treat every digest and record made from them as void.

| Manifest copy | `MAX_MINUTES` | `MAX_CONCURRENCY` / `MAX_TASKS` | `SPEND_CEILING_USD` | `HOURLY_BURN_USD` | `ENTRY_POINT` |
|---|---|---|---|---|---|
| `p15-s1-a-baseline.manifest` | 75 | 1 / 1 | 0.75 | 0.60 | `script:experiments/a-baseline/run.sh` |
| `p15-s1-b-burst.manifest` | 90 | 1 / 1 | 2.40 | 1.55 | `script:experiments/b-burst/run.sh` |

Stop: an STS failure, or `DATASET_SNAPSHOT` other than `scholarchain-s-20261107-10dd7f34121b` (the value WO-4 reported and the human verified, per the manifest headers; `snapshot-s.json` line 50).

### 1.6A Validate A before spending, and print its digests (no AWS call): NOT TONIGHT (A-only session, P15-22)

`--print-digest` exits before any cloud or Terraform call and needs no record (`lib.sh` line 217: `needs_approval` is enforced only when `SC_PRINT_DIGEST` is 0). It runs the full manifest validation (tier S caps 90 min, 64, 20,000; spend, burn, stop list) and the full pipeline-input validation (run id shape, keys), so a bad limit is found now. The pipeline arguments are exactly the ones `experiments/lib/exp-lib.sh` lines 83 to 95 pass, with the numbers `experiments/a-baseline/run.sh` line 35 commits (`medium 70 0.75 0.60`); the digest binds the text, so `0.75` and `0.60` are typed exactly as in `run.sh`. The verdict is the `part1 exit` and `part2 exit` lines (conventions, m2).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; { bash scripts/run-experiment.sh --print-digest --manifest ${SCD}/experiments/manifests/p15-s1-a-baseline.manifest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 0.75 2>&1 | tee ${SCD}/logs/phase15-s1-a-experiment-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g"; echo "part1 exit: $?"; bash scripts/run-bounded-pipeline.sh --print-digest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --size medium --max-minutes 70 --spend-ceiling-usd 0.75 --hourly-burn-usd 0.60 --stop-on first-failure,dlq-nonempty --input-file ${SCD}/pipeline-inputs/p15-s1-a.json 2>&1 | tee ${SCD}/logs/phase15-s1-a-pipeline-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g"; echo "part2 exit: $?"; } | tail -n 60; echo "exit: $?"
```

Expected: each part prints four lines (`ACTION=`, `SCRIPT_DIGEST=`, `ARGS_CANONICAL=`, `ARGS_DIGEST=`; the account shows as `000000000000`), `part1 exit: 0`, `part2 exit: 0`. Check the `ARGS_CANONICAL` lines against:
- experiment: `action=experiment;environment=course;account=<id>;region=us-east-2;cost=0.75;extra=manifest=<sha>;experiment=A;size=S;max_minutes=75;max_concurrency=1;max_tasks=1;spend_ceiling=0.75` (`run-experiment.sh` line 249)
- pipeline: `action=pipeline;environment=course;account=<id>;region=us-east-2;cost=20;extra=size=medium;max_minutes=70;spend_ceiling=0.75;hourly_burn=0.60;stop_on=first-failure,dlq-nonempty;input=<sha>` (`run-bounded-pipeline.sh` line 252)

Stop: any `EVIDENCE:` refusal (a limit above its cap, a projected spend above the ceiling, an input shape error).

### 1.6B Validate B before spending, and print its digests (no AWS call)

Same as 1.6A with B's committed numbers: `experiments/b-burst/run.sh` line 49 `exp_run_pipeline medium 88 2.30 1.55`, manifest ceiling 2.40 (P15-16). B's old records are void (b-burst README "Open prerequisites", item 3).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; { bash scripts/run-experiment.sh --print-digest --manifest ${SCD}/experiments/manifests/p15-s1-b-burst.manifest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 2.40 2>&1 | tee ${SCD}/logs/phase15-s1-b-experiment-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g"; echo "part1 exit: $?"; bash scripts/run-bounded-pipeline.sh --print-digest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --size medium --max-minutes 88 --spend-ceiling-usd 2.30 --hourly-burn-usd 1.55 --stop-on first-failure,dlq-nonempty --input-file ${SCD}/pipeline-inputs/p15-s1-b.json 2>&1 | tee ${SCD}/logs/phase15-s1-b-pipeline-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g"; echo "part2 exit: $?"; } | tail -n 60; echo "exit: $?"
```

Expected: `ARGS_CANONICAL` experiment: `...cost=2.40;extra=manifest=<sha>;experiment=B;size=S;max_minutes=90;max_concurrency=1;max_tasks=1;spend_ceiling=2.40`; pipeline: `...cost=20;extra=size=medium;max_minutes=88;spend_ceiling=2.30;hourly_burn=1.55;stop_on=first-failure,dlq-nonempty;input=<sha>`. The projected spend check (1.55 x 88 / 60 = 2.27 at most 2.30; 1.55 x 90 / 60 = 2.33 at most 2.40) must pass. Both `part` exits 0.

### 1.7 Where the plan-dependent checks live

The rendered state machine and `ComputeLimits` checks need a saved plan, which `plan.sh` makes in step 2.1 (A, baseline-s) and 3.1 (B, elasticity-s). The commands are in 2.3 and 3.3; the values to expect are in "Rendered values to expect" above; the post-apply live check is 2.5 and 3.5.

---

## Step 2. Experiment A (single node): only 2.0 runs tonight; 2.1 to 2.10 are NOT TONIGHT (A-only session, P15-22)

Every limit, clock and number in 2.1 to 2.10 (including the 2.4R clock start and the 6 h figure) is superseded by `session-1b-a-only-draft.md`. Tonight skip from the end of 2.0.5 to step 3.

### 2.0 Build, upload (no session resources; do this before session-up)

#### 2.0.1 Build the jars and the image contexts

Needs Docker running, JDK 21, sbt, and the clean tree of 0.2c. `package.sh` builds 085b5ec's code (Wave 2 is in the stash). Runs one `sbt packageJobs dockerLocal` and two `docker build`s; no AWS call, no push (`scripts/package.sh` header). Takes several minutes to tens of minutes (UNVERIFIED). Ignored build output left in `target/` by earlier Wave 2 builds is not stashed; sbt recompiles from the sources on disk, which are now 085b5ec's (UNVERIFIED that no stale Wave 2 class file or jar survives in `modules/*/target/job`: the 2.0.2c check compares the jar names). `--out-dir` must not exist yet. `deploy.sh` later takes `target/docker-local` as both contexts and digests it, so do not rebuild after the deploy digest in 2.4P. `package.sh` writes only ignored build output and refuses a build that moved the tree (README).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; HEAD12="$(git rev-parse HEAD | cut -c1-12)"; mkdir -p /Users/winnguyen/scholarchain-data/logs /Users/winnguyen/scholarchain-data/package; bash scripts/package.sh --platform linux/amd64 --out-dir /Users/winnguyen/scholarchain-data/package/p15-s1-${HEAD12} 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-package.log | tail -n 60; echo "exit: $?"
```

Expected: `IMAGE_BUILT` for both images, `PACKAGE_COMPLETE git_sha=<HEAD> image_tag=<HEAD> platform=linux/amd64`, `exit: 0` (`scripts/README.md`, Phase 14 "Real build through scripts/package.sh"). Stop: a compile or test failure, a dirty-tree refusal (the gate of 0.2c was bypassed or the tree changed), or an image tag ending `-dirty`.

#### 2.0.2 Upload, P: print the digests

`upload-inputs.sh` copies the Phase 15 bootstrap scripts, the job jars and the Spark 4.0.5 archive. The list is bound by digest (`upload-inputs.sh` line 179). Arguments follow `upload-inputs.sh` lines 54 to 59 and the header example. UNVERIFIED and for the human to confirm before running:
- `--spark-dir`: the directory that holds `spark-4.0.5-bin-hadoop3.tgz`, `spark-4.0.5-jars.zip` and `spark-4.0.5.sha256` (the Phase 14 location; `scripts/README.md` "Rehearsal helper" names `/Users/winnguyen/scholarchain-data/spark-4.0.5` and says it held only the tarball when written). Replace the path if yours differs.
- `--max-objects 120 --max-bytes 1500000000`: the README's "first guess". The script refuses with the real count if it is exceeded; raise only after reading `UPLOAD_PLAN`.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ENVDIR=/Users/winnguyen/ScholarChain/infra/terraform/environments/course; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; DATA_BUCKET="$(terraform -chdir=${ENVDIR} output -raw data_bucket)"; ART_BUCKET="$(terraform -chdir=${ENVDIR} output -raw artifacts_bucket)"; AWS_PROFILE=scholarchain bash scripts/upload-inputs.sh --print-digest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --snapshot phase5-dblp-subset --data-bucket "${DATA_BUCKET}" --artifacts-bucket "${ART_BUCKET}" --spark-dir ${SCD}/spark-4.0.5 --max-objects 120 --max-bytes 1500000000 2>&1 | tee ${SCD}/logs/phase15-s1-upload-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `SPARK_CHECKSUMS_VERIFIED`, `UPLOAD_PLAN objects=<n> bytes=<n>`, then `ACTION=upload`, `SCRIPT_DIGEST=`, `ARGS_CANONICAL=`, `ARGS_DIGEST=`, `exit: 0` (bucket names and the account show as `000000000000` on screen; the log is unredacted, keep it local). Stop: `cannot build the file list` (a missing `modules/*/target/job`: rerun 2.0.1), a checksum mismatch, a count or byte refusal.

#### 2.0.2b The four raw files must equal the registered snapshot (read-only; review M3)

The upload overwrites `raw/phase5-dblp-subset/` and the bucket has versioning off (`upload-inputs.sh` lines 113 to 115, header), so a changed local file would silently replace the input Phase 14 ran on. This compares the four `WOULD_UPLOAD ... sha256=` lines of the 2.0.2 log with the `members[].sha256` values in `datasets/tier-s/snapshot-s.json` (dblp `9076e857...`, openalex `fdeddfa3...`, crossref `c403ccbf...`, orcid `719a1cdb...`). No AWS call.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; python3 -I -c 'import json,re,sys; m={x["path"].rsplit("/",1)[1]: x["sha256"] for x in json.load(open(sys.argv[1]))["members"]}; got={}; [got.__setitem__(g.group(1), g.group(2)) for g in (re.search(r"/raw/phase5-dblp-subset/([^ /]+) bytes=[0-9]+ sha256=([0-9a-f]{64})", l) for l in open(sys.argv[2])) if g]; bad=[k for k in m if got.get(k) != m[k]]; print("expected", len(m), "found_in_upload_plan", len(got), "mismatch_or_missing", bad); print("RAW_INPUTS_MATCH_SNAPSHOT=" + ("yes" if not bad and len(got) == len(m) else "NO: STOP")); sys.exit(1 if bad or len(got) != len(m) else 0)' /Users/winnguyen/ScholarChain/datasets/tier-s/snapshot-s.json /Users/winnguyen/scholarchain-data/logs/phase15-s1-upload-digest.log 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-raw-compare.log | tail -n 60; echo "exit: $?"
```

Expected: `expected 4 found_in_upload_plan 4 mismatch_or_missing []`, `RAW_INPUTS_MATCH_SNAPSHOT=yes`, `exit: 0`. Stop: any other result. Do not write the upload record (2.0.3) and do not upload; report. The regex assumes the `WOULD_UPLOAD s3://<bucket>/raw/<snapshot>/<file> bytes=<n> sha256=<hex>` shape printed by `upload-inputs.sh` line 162 (read from the source, UNVERIFIED by a run).

#### 2.0.2c No stale jars in S3 (read-only; review M4)

The upload never deletes. The node copies every object under `jars/<module>/` (`run-s-pipeline.sh` line 132), `lib_jars_csv` takes every `lib/*.jar` (`lib-emr.sh` lines 877 to 888), and a renamed main jar trips `main_jar` (`lib-emr.sh` lines 903 to 911). So an old jar left in S3 from an earlier run stays on the class path. This lists the keys under `jars/hadoop/`, `jars/entity-resolution/` and `jars/spark/` and compares them with the local `modules/<module>/target/job` that 2.0.1 built (main jar plus `lib/*.jar`). It is two commands (review 2 notes 1 and 2): (i) lists the remote keys into files, checks the exit status of each `aws s3 ls` and requires a non-empty list; (ii) compares them, with `LC_ALL=C` on `comm` as well as on `sort`, and stops if a remote list is missing or empty.

(i) Remote listing:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; ART_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw artifacts_bucket)"; OK=1; for M in hadoop entity-resolution spark; do if aws s3 ls "s3://${ART_BUCKET}/jars/${M}/" --recursive --region us-east-2 > ${SCD}/logs/phase15-s1-jars-remote-${M}.ls; then awk '{ print $4 }' ${SCD}/logs/phase15-s1-jars-remote-${M}.ls | sed "s#^jars/${M}/##" | LC_ALL=C sort > ${SCD}/logs/phase15-s1-jars-remote-${M}.txt; N="$(wc -l < ${SCD}/logs/phase15-s1-jars-remote-${M}.txt | tr -d ' ')"; echo "${M}: remote keys=${N}"; if [[ "${N}" -lt 1 ]]; then echo "${M}: EMPTY remote listing: STOP"; OK=0; fi; else echo "${M}: aws s3 ls FAILED: STOP"; OK=0; fi; done; if [[ "${OK}" == "1" ]]; then echo "REMOTE_LISTING_OK=yes"; else echo "REMOTE_LISTING_OK=NO: STOP"; false; fi ) 2>&1 | tee ${SCD}/logs/phase15-s1-jars-remote.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: a non-zero key count for each of the three modules, `REMOTE_LISTING_OK=yes`, `exit: 0`. Stop: a failed listing or an empty one (an empty list would make every comparison look clean, so it is refused; the Phase 14 uploads put jars there, so empty means something is wrong; report). The `.ls` and `.txt` files hold key names only.

(ii) Comparison:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( EXTRA=0; for M in hadoop entity-resolution spark; do if [[ ! -s ${SCD}/logs/phase15-s1-jars-remote-${M}.txt ]]; then echo "${M}: remote list missing or empty: STOP"; EXTRA=1; continue; fi; echo "--${M}: remote-only keys (stale; expect none)"; STALE="$(LC_ALL=C comm -23 ${SCD}/logs/phase15-s1-jars-remote-${M}.txt <(cd /Users/winnguyen/ScholarChain/modules/${M}/target/job && find . -name '*.jar' | sed 's#^\./##' | LC_ALL=C sort))"; echo "${STALE}"; if [[ -n "${STALE}" ]]; then EXTRA=1; fi; echo "--${M}: local-only keys (will be added; informational)"; LC_ALL=C comm -13 ${SCD}/logs/phase15-s1-jars-remote-${M}.txt <(cd /Users/winnguyen/ScholarChain/modules/${M}/target/job && find . -name '*.jar' | sed 's#^\./##' | LC_ALL=C sort) | head -n 5; done; if [[ "${EXTRA}" == "0" ]]; then echo "NO_STALE_JARS=yes"; else echo "NO_STALE_JARS=NO: STOP"; false; fi ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-jars-compare.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: empty remote-only lists, `NO_STALE_JARS=yes`, `exit: 0`; the local-only lists show only jars the new build adds (at most 5 names each are printed). The `aws s3 ls --recursive` columns (date, time, size, key) are assumed, UNVERIFIED by a run. Stop: any remote-only key. Do not upload. Removing an object is a data deletion: it needs the human's written approval of the exact keys through a reviewed path (docs/agents.md, "Data deletion"), not a step in this runbook; report the key names. (An unreadable `target/job` directory also fails the check, which is correct: run 2.0.1 first.)

#### 2.0.3 Upload, W: write the record

Run only after 2.0.2b and 2.0.2c passed.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; REC=${SCD}/approvals/p15-s1-upload.env; dg() { awk -F= -v k="${2}" '$1 == k { print $2; exit }' "${1}"; }; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; EXP="$(date -u -v+8H +%Y-%m-%dT%H:%M:%SZ)"; ( (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=upload" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=20" "PLAN_DIGEST=n/a" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-s1-upload-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-s1-upload-digest.log ARGS_DIGEST)" "GIT_SHA=n/a" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > "${REC}") && sed "s/^ACCOUNT_ID=.*/ACCOUNT_ID=<masked>/" "${REC}" ) 2>&1 | tee ${SCD}/logs/phase15-s1-upload-record.log | tail -n 60; echo "exit: $?"
```

Expected: the 14 lines with the account masked and non-empty digests, `exit: 0`. By running this command the human approves exactly the file list named in `phase15-s1-upload-digest.log`. Stop: an empty digest, or `file exists` / `cannot overwrite existing file` (a record of that name exists: use the existing one only if it was written for this digest; otherwise move it aside by hand).

#### 2.0.4 Upload, D then R

D (dry run, consumes nothing):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ENVDIR=/Users/winnguyen/ScholarChain/infra/terraform/environments/course; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; DATA_BUCKET="$(terraform -chdir=${ENVDIR} output -raw data_bucket)"; ART_BUCKET="$(terraform -chdir=${ENVDIR} output -raw artifacts_bucket)"; AWS_PROFILE=scholarchain bash scripts/upload-inputs.sh --dry-run --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-s1-upload.env --snapshot phase5-dblp-subset --data-bucket "${DATA_BUCKET}" --artifacts-bucket "${ART_BUCKET}" --spark-dir ${SCD}/spark-4.0.5 --max-objects 120 --max-bytes 1500000000 2>&1 | tee ${SCD}/logs/phase15-s1-upload-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `APPROVAL_VERIFIED action=upload ...`, `APPROVAL_NOT_CONSUMED dry-run`, `DRY_RUN_COMPLETE`, `exit: 0`. Stop: any refusal.

R (real, consumes the record; one `aws s3 cp` per file, no sync, no delete, default bucket encryption; versioning is off, so a re-uploaded key replaces the old object and there is no old copy: `upload-inputs.sh` header):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ENVDIR=/Users/winnguyen/ScholarChain/infra/terraform/environments/course; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; DATA_BUCKET="$(terraform -chdir=${ENVDIR} output -raw data_bucket)"; ART_BUCKET="$(terraform -chdir=${ENVDIR} output -raw artifacts_bucket)"; AWS_PROFILE=scholarchain bash scripts/upload-inputs.sh --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-s1-upload.env --snapshot phase5-dblp-subset --data-bucket "${DATA_BUCKET}" --artifacts-bucket "${ART_BUCKET}" --spark-dir ${SCD}/spark-4.0.5 --max-objects 120 --max-bytes 1500000000 2>&1 | tee ${SCD}/logs/phase15-s1-upload.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `UPLOADED`, `VERIFIED` for each object, `UPLOAD_COMPLETE`, `exit: 0` (README marker list). Rollback gate: none to roll back (the previous objects were replaced); a failed upload still consumed the record, so re-run 2.0.3 with a fresh `APPROVED_AT` after reading why it failed. Spend: S3 requests and transfer only (negligible; rates UNVERIFIED).

#### 2.0.5 Confirm the node scripts in S3 are the committed ones (read-only)

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; ART_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw artifacts_bucket)"; for F in lib-emr.sh run-s-pipeline.sh capability-check.sh spark-yarn.conf; do L="$(shasum -a 256 < /Users/winnguyen/ScholarChain/scripts/emr/${F} | cut -d' ' -f1)"; R="$(aws s3 cp "s3://${ART_BUCKET}/bootstrap/${F}" - --region us-east-2 | shasum -a 256 | cut -d' ' -f1)"; if [[ "${L}" == "${R}" ]]; then echo "MATCH ${F}"; else echo "DIFFERENT ${F}"; fi; done; echo "--profile lines in the uploaded spark-yarn.conf (expect 4 a-baseline and 9 b-burst)"; aws s3 cp "s3://${ART_BUCKET}/bootstrap/spark-yarn.conf" - --region us-east-2 | grep -E '^(a-baseline|b-burst)\.' ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-bootstrap-check.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: four `MATCH` lines and the 13 profile lines (`a-baseline.`: `SPARK_EVENT_LOG`, `MR_COUNTERS`, `DATA_PATH`, `SPARK_MASTER`; `b-burst.`: `DATA_PATH`, `SPARK_MASTER`, `SPARK_EVENT_LOG`, `MR_COUNTERS`, `SHUFFLE_PARTITIONS`, `PARTITION_COUNT`, `SPLIT_MAX_BYTES`, `MAP_MEMORY_MB`, `REDUCE_MEMORY_MB`, counted from `scripts/emr/spark-yarn.conf`). `aws s3 cp <s3 uri> -` streams the object to stdout (UNVERIFIED flag form against the CLI reference, standard usage). Stop: any `DIFFERENT`.

### 2.1 Plan A (session-up on baseline-s): NOT TONIGHT (A-only session, P15-22)

Layering: `course.tfvars`, then `experiment-size-s.tfvars`, then `baseline-s.tfvars` (later wins; `baseline-s.tfvars` header and `plan.sh` lines 99 to 100: `PLAN_ARGS="-var-file=$SC_TFVARS -var=image_tag=$IMAGE_TAG"; for f in $EXTRA_VAR_FILES; do PLAN_ARGS="$PLAN_ARGS -var-file=$f"; done`). `--extra-var-file` repeats and keeps order (`plan.sh` line 29). The saved plan is `<out-dir>/course-<sha12>.tfplan` (line 97), so each plan gets its own `--out-dir`. `plan.sh` applies nothing; it calls STS and reads Terraform state.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ENVDIR=/Users/winnguyen/ScholarChain/infra/terraform/environments/course; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; mkdir -p ${SCD}/logs ${SCD}/plans; AWS_PROFILE=scholarchain bash scripts/plan.sh --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --extra-var-file ${ENVDIR}/experiment-size-s.tfvars --extra-var-file ${ENVDIR}/baseline-s.tfvars --out-dir ${SCD}/plans/p15-s1-a-up 2>&1 | tee ${SCD}/logs/phase15-s1-a-plan.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected (UNVERIFIED, from `down-5` reversed and the overlay contents): about 11 to add (8 interface endpoints: sqs, s3vectors, secretsmanager, ecr.api, ecr.dkr, logs, elasticmapreduce, sts; and the three task definitions for the new image tag), a few in-place changes (the pipeline state machine: loader skipped, profile `a-baseline`; the two services), 3 destroyed (old task definitions). `PLAN_EXIT=2`, `STATEFUL_CHANGE=no`, `IAM_CHANGES: none`, `PLAN_DIGEST=<hex>`, `exit: 0`. `HIGH_COST_CREATIONS` lists the endpoints.

### 2.2 Read plan A before approving: NOT TONIGHT (A-only session, P15-22)

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; (echo "--resource actions"; grep -nE '^[[:space:]]*# .*(will be created|will be destroyed|must be replaced|will be replaced|will be updated in-place)' ${SCD}/logs/phase15-s1-a-plan.log; echo "--summary"; sed -n '/^PLAN_SUMMARY_BEGIN/,/^PLAN_SUMMARY_END/p' ${SCD}/logs/phase15-s1-a-plan.log; echo "--meta"; grep -E '^(GIT_SHA|DIRTY|STATEFUL_CHANGE|PLAN_EXIT|CPU_ARCHITECTURE|IMAGE_TAG)=' ${SCD}/plans/p15-s1-a-up/course-*.tfplan.meta) 2>&1 | tee ${SCD}/logs/phase15-s1-a-plan-actions.log | tail -n 60; echo "exit: $?"
```

Stop conditions (do not write a record; nothing is applied yet, so report and wait; S2 does not apply before 2.4R):
- Any Neptune resource, any Bedrock endpoint (`bedrock-runtime`) or Bedrock IAM statement, any Terraform change to a bucket, DynamoDB table, S3 Vectors bucket or index, ECR repository or secret (anything in `STATEFUL_DESTROY_OR_REPLACE`).
- Any `aws_iam_*` line under `IAM_CHANGES`: IAM changes need the human's privilege-diff approval (docs/agents.md, "Security, IAM, secrets").
- An internal load balancer, ECS scaling target or policy, a worker service, Container Insights: that means Wave 2 Terraform is in HEAD (session 2 resources). The 0.2c gate should have made that impossible; if it shows, the gate was bypassed. Do not deploy them in session 1.
- `DIRTY=yes`, `GIT_SHA` not equal to the HEAD of 0.2c, or `CPU_ARCHITECTURE` other than `X86_64`.
- Counts that differ a lot from the expected line above with no explanation.

### 2.3 Rendered state machine and `ComputeLimits` from the plan A (read-only; closes review-2 pending gate 4 for A): NOT TONIGHT (A-only session, P15-22)

5a: extract the definition the plan would apply (`terraform show -json` of the saved plan: a local read, no AWS call; this is `scripts/README.md` "Phase 15 pre-session checks", item 5a, with its path filled in):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; PLAN_FILE="$(ls ${SCD}/plans/p15-s1-a-up/course-*.tfplan)"; terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course show -json "${PLAN_FILE}" | python3 -I -c 'import json,sys; p=json.load(sys.stdin); rc=[r for r in p["resource_changes"] if r["type"]=="aws_sfn_state_machine"]; assert len(rc)==1, "expected exactly one aws_sfn_state_machine change, found %d" % len(rc); c=rc[0]["change"]; assert not (c.get("after_unknown") or {}).get("definition"), "the definition is unknown until apply: this check cannot run before the apply"; open(sys.argv[1], "w").write(c["after"]["definition"]); print("definition written", len(c["after"]["definition"]), "characters")' ${SCD}/logs/phase15-s1-a-sfn-definition-plan.json 2>&1 | tee ${SCD}/logs/phase15-s1-a-sfn-extract.log | tail -n 60; echo "exit: $?"
```

If it prints "the definition is unknown until apply", expect that for A: the definition refers to the loader task definition ARN, which is replaced by the image tag change (the Phase 14 README, discrepancy 1, hypothesis; UNVERIFIED). Then skip 5b and the summary below and rely on 2.5 (the deployed definition, read back after the apply). Say so in the evidence file. That is an honest "cannot be checked before apply", not a pass.

5b: validate it (`aws stepfunctions validate-state-machine-definition` is a non-mutating API; `doc-verification.md` "R4 item 9"; the parameter names are from the README and unchecked against the page; the IAM action name is from memory):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; AWS_PROFILE=scholarchain aws stepfunctions validate-state-machine-definition --definition file:///Users/winnguyen/scholarchain-data/logs/phase15-s1-a-sfn-definition-plan.json --type STANDARD --region us-east-2 --output json 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-a-sfn-validate.log | tail -n 60; echo "exit: $?"
```

Expected: `"result": "OK"` and no diagnostics (field names UNVERIFIED). Stop: `FAIL` or a diagnostic (still before the apply: report and wait).

Summary of the rendered values (compare with the table "Rendered values to expect", column A; the eight `timeouts` are compared as a set):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; python3 -I -c 'import re,sys; s=open(sys.argv[1]).read(); print("definition_chars", len(s)); [print(n, re.findall(p, s)) for n, p in (("compute_limits", r"\x22ComputeLimits\x22\s*:\s*\{[^}]*\}"), ("idle_timeout", r"\x22IdleTimeout\x22\s*:\s*\d+"), ("wait_states", r"\x22Type\x22\s*:\s*\x22Wait\x22"), ("wait_seconds", r"\x22Seconds\x22\s*:\s*\d+"), ("timeouts", r"\x22TimeoutSeconds\x22\s*:\s*\d+"), ("run_loader_state", r"\x22RunLoader\x22\s*:"), ("after_terminate_state", r"\x22AfterTerminate\x22\s*:"), ("profile_a_baseline", r"a-baseline"), ("profile_b_burst", r"b-burst"))]' /Users/winnguyen/scholarchain-data/logs/phase15-s1-a-sfn-definition-plan.json 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-a-sfn-summary-plan.log | tail -n 60; echo "exit: $?"
```

Expected for A: `compute_limits []`, `idle_timeout` 900, `wait_states []`, `after_terminate_state []`, `run_loader_state` one match, `profile_a_baseline` at least one match, `profile_b_burst []`, and `timeouts` with eight entries: 7200, 1440, 300, 4800, 4800, 900, 600, 900 (any order). The regexes read the definition text; they rely on `jsonencode` output (key names `ComputeLimits`, `IdleTimeout`, `Seconds`, `TimeoutSeconds`) and on the `Args.$` string carrying the profile (`workflow/main.tf` line 223). UNVERIFIED until run. Stop: a mismatch (before the apply: report and wait).

### 2.4 Deploy A (session-up apply; the session clock S3 starts at 2.4R): NOT TONIGHT (A-only session, P15-22)

`deploy.sh` applies the saved plan (never `-auto-approve`), logs in to ECR, builds and pushes both images for the HEAD tag (the tag is new for this HEAD, so they are built, not reused), and checks the revision (`deploy.sh` header; `DEPLOY_COMPLETE`, `IMAGE_*`, `DEPLOYED_REVISION=` markers per `scripts/README.md`). Docker must be running. Known process gap (Phase 14 README, open items): the apply runs before the image push, so for a short time the task definitions name a tag not yet in ECR. Services are at desired count 0, so nothing tries to pull it.

2.4P: print the deploy digests (needs the plan, its `.meta` and both contexts; `deploy.sh` lines 55 to 98):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PLAN_FILE="$(ls ${SCD}/plans/p15-s1-a-up/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --print-digest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --plan-file "${PLAN_FILE}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-s1-a-up 2>&1 | tee ${SCD}/logs/phase15-s1-a-deploy-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `ACTION=deploy`, `SCRIPT_DIGEST=`, `ARGS_CANONICAL=action=deploy;...;cost=20;extra=image_tag=<HEAD>;platform=linux/amd64;services_context=<digest>;loader_context=<digest>`, `ARGS_DIGEST=`, `exit: 0`. The `plan.sh` plan digest is in `phase15-s1-a-plan.log` (`PLAN_DIGEST=`). Note: the human reads the plan (2.2) before 2.4W; the record is the approval.

2.4W: write the record (the human approves the plan digest, script revision, account, Region, cost ceiling 20, and rollback below):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; REC=${SCD}/approvals/p15-s1-a-deploy.env; dg() { awk -F= -v k="${2}" '$1 == k { print $2; exit }' "${1}"; }; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; EXP="$(date -u -v+8H +%Y-%m-%dT%H:%M:%SZ)"; ( (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=deploy" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=20" "PLAN_DIGEST=$(dg ${SCD}/logs/phase15-s1-a-plan.log PLAN_DIGEST)" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-s1-a-deploy-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-s1-a-deploy-digest.log ARGS_DIGEST)" "GIT_SHA=$(git rev-parse HEAD)" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > "${REC}") && sed "s/^ACCOUNT_ID=.*/ACCOUNT_ID=<masked>/" "${REC}" ) 2>&1 | tee ${SCD}/logs/phase15-s1-a-deploy-record.log | tail -n 60; echo "exit: $?"
```

2.4D: dry run (checks the record, plan binding and clean tree; consumes nothing):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PLAN_FILE="$(ls ${SCD}/plans/p15-s1-a-up/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --dry-run --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-s1-a-deploy.env --plan-file "${PLAN_FILE}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-s1-a-up 2>&1 | tee ${SCD}/logs/phase15-s1-a-deploy-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `CLEAN_TREE`, `APPROVAL_VERIFIED action=deploy ...`, `APPROVAL_NOT_CONSUMED dry-run`, `DRY_RUN_COMPLETE`, `exit: 0`, no `APPROVAL_SKIPPED_FIELDS` listing `PLAN_DIGEST` or `ARGS_DIGEST` (the plan and contexts exist, so nothing is skipped). Stop: any refusal (still before the apply).

2.4R: the apply (the first billable step: the interface endpoints start billing when they are created; note the UTC time, it is the start of the 6 h session clock):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PLAN_FILE="$(ls ${SCD}/plans/p15-s1-a-up/course-*.tfplan)"; date -u +"S3_CLOCK_START_UTC=%Y-%m-%dT%H:%M:%SZ"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-s1-a-deploy.env --plan-file "${PLAN_FILE}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-s1-a-up 2>&1 | tee ${SCD}/logs/phase15-s1-a-deploy.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `S3_CLOCK_START_UTC=...` on the first line of the screen (it scrolls off the `tail` only if the deploy prints over 60 lines; it is not in the log, so write the time down), steps 1 to 12 `ok` as in the Phase 14 deploys, `IMAGE_*` lines for both repositories, `DEPLOY_COMPLETE`, `DEPLOYED_REVISION=<HEAD>`, `exit: 0`; apply counts equal to the plan within the known state-machine plan-versus-apply gap (Phase 14 README, discrepancy 1). Stop (S2: report, then go to step 4 unless the human decides otherwise within 15 minutes): any failure. Do not re-run the same record (a failed run consumed it). Rollback gate for A-up: plan with `session-down.tfvars` (step 4); the pushed image tags are immutable and stay. A half-applied plan: run `plan.sh` again with the same overlays to see the drift and report; do not run another apply without a new plan, a new record and a new human decision. Because the tree must stay clean, step 4 still works (S1).

### 2.5 Deployed definition of A (read-only; the check that always works): NOT TONIGHT (A-only session, P15-22)

Reads the live definition back and runs the same summary as 2.3 on it. This is the check that does not depend on whether the plan could render the definition.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; SM="$(aws stepfunctions list-state-machines --region us-east-2 --query "stateMachines[?name=='scholarchain-course-pipeline'].stateMachineArn | [0]" --output text)"; (set -C; aws stepfunctions describe-state-machine --state-machine-arn "${SM}" --region us-east-2 --query definition --output text > ${SCD}/logs/phase15-s1-a-sfn-definition-live.json) && python3 -I -c 'import re,sys; s=open(sys.argv[1]).read(); print("definition_chars", len(s)); [print(n, re.findall(p, s)) for n, p in (("compute_limits", r"\x22ComputeLimits\x22\s*:\s*\{[^}]*\}"), ("idle_timeout", r"\x22IdleTimeout\x22\s*:\s*\d+"), ("wait_states", r"\x22Type\x22\s*:\s*\x22Wait\x22"), ("wait_seconds", r"\x22Seconds\x22\s*:\s*\d+"), ("timeouts", r"\x22TimeoutSeconds\x22\s*:\s*\d+"), ("run_loader_state", r"\x22RunLoader\x22\s*:"), ("after_terminate_state", r"\x22AfterTerminate\x22\s*:"), ("profile_a_baseline", r"a-baseline"), ("profile_b_burst", r"b-burst"))]' ${SCD}/logs/phase15-s1-a-sfn-definition-live.json ) 2>&1 | tee ${SCD}/logs/phase15-s1-a-sfn-summary-live.log | tail -n 60; echo "exit: $?"
```

Expected: the same as the A column (including the eight `timeouts`). Stop (S2: no run starts; report, then step 4 unless the human decides otherwise within 15 minutes): any mismatch. The definition file holds the account id in ARNs: keep it out of the repository and redact before any results commit.

### 2.6 A, records for the run: NOT TONIGHT (A-only session, P15-22)

Immediately before the run, re-run command 1.3 (state check) and read it. Then:

2.6W: write the pipeline record and the experiment record (the digests are in the 1.6A logs; the human approves the A manifest copy and the A pipeline limits by running this):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; dg() { awk -F= -v k="${2}" '$1 == k { print $2; exit }' "${1}"; }; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; EXP="$(date -u -v+8H +%Y-%m-%dT%H:%M:%SZ)"; ( (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=pipeline" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=20" "PLAN_DIGEST=n/a" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-s1-a-pipeline-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-s1-a-pipeline-digest.log ARGS_DIGEST)" "GIT_SHA=n/a" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > ${SCD}/approvals/p15-s1-a-pipeline.env) && (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=experiment" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=0.75" "PLAN_DIGEST=n/a" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-s1-a-experiment-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-s1-a-experiment-digest.log ARGS_DIGEST)" "GIT_SHA=$(git rev-parse HEAD)" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > ${SCD}/approvals/p15-s1-a-experiment.env) && sed "s/^ACCOUNT_ID=.*/ACCOUNT_ID=<masked>/" ${SCD}/approvals/p15-s1-a-pipeline.env ${SCD}/approvals/p15-s1-a-experiment.env ) 2>&1 | tee ${SCD}/logs/phase15-s1-a-run-records.log | tail -n 60; echo "exit: $?"
```

2.6D: dry runs of both scripts with the records (the pipeline dry run matters most: the pipeline record is only read after the experiment record is consumed, so a wrong pipeline record would waste the experiment record). The verdict is the two `part` exit lines.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; { AWS_PROFILE=scholarchain bash scripts/run-bounded-pipeline.sh --dry-run --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-s1-a-pipeline.env --size medium --max-minutes 70 --spend-ceiling-usd 0.75 --hourly-burn-usd 0.60 --stop-on first-failure,dlq-nonempty --input-file ${SCD}/pipeline-inputs/p15-s1-a.json 2>&1 | tee ${SCD}/logs/phase15-s1-a-pipeline-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g"; echo "part1 exit: $?"; AWS_PROFILE=scholarchain bash scripts/run-experiment.sh --dry-run --manifest ${SCD}/experiments/manifests/p15-s1-a-baseline.manifest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 0.75 --approval-record ${SCD}/approvals/p15-s1-a-experiment.env 2>&1 | tee ${SCD}/logs/phase15-s1-a-experiment-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g"; echo "part2 exit: $?"; } | tail -n 60; echo "exit: $?"
```

Expected: both `APPROVAL_VERIFIED`, `DRY_RUN_COMPLETE`, `part1 exit: 0`, `part2 exit: 0`. The experiment dry run needs a clean tree and the entry point tracked in HEAD (`run-experiment.sh` lines 206 to 212). Stop (S2): any refusal or non-zero part exit.

### 2.7 Run A (the billable run): NOT TONIGHT (A-only session, P15-22)

`run-experiment.sh` consumes the experiment record at `start-run`, starts `experiments/a-baseline/run.sh` once under a `MAX_MINUTES` 75 limit; that script starts one pipeline execution through `run-bounded-pipeline.sh` (70 min stop, `--stop-on first-failure,dlq-nonempty`), which consumes the pipeline record at `start-execution`. The two environment variables are the ones `exp-lib.sh` reads (lines 74 to 76); they are given as prefix assignments, not exported. Nothing is tuned and nothing is retried. The run takes about an hour; the screen shows nothing until it ends (`tail`), so use the watch command below in a second terminal if you want to watch. If the `!` prompt cannot hold a command this long, run the same line in an ordinary terminal (UNVERIFIED which you need; Phase 14 ran 87-minute pipelines through this path).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; SCHOLARCHAIN_PIPELINE_INPUT_FILE=${SCD}/pipeline-inputs/p15-s1-a.json SCHOLARCHAIN_PIPELINE_APPROVAL_RECORD=${SCD}/approvals/p15-s1-a-pipeline.env AWS_PROFILE=scholarchain bash scripts/run-experiment.sh --manifest ${SCD}/experiments/manifests/p15-s1-a-baseline.manifest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 0.75 --approval-record ${SCD}/approvals/p15-s1-a-experiment.env 2>&1 | tee ${SCD}/logs/phase15-s1-a-run.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Watch (optional, second terminal, read-only: the pipeline script's POLL line every 30 s):

```
tail -n 5 "$(ls -t /Users/winnguyen/scholarchain-data/experiments/runs/exp-A-*/raw/pipeline-run.log | head -n 1)"
```

Expected at the end of 2.7: `RUN_ID=exp-A-<UTC>-<sha8>`, `ENTRY_EXIT=0 FINAL_STATUS=COMPLETED elapsed_s=<about 3,500 to 4,200>`, metric listings, `exit: 0`. In the run: pipeline `FINAL_STATUS=SUCCEEDED`, `NO_ACTIVE_CLUSTER`, `RECORD_COMPLETE=yes`. Stop conditions (all fall under S2: report, then step 4 unless the human decides otherwise within 15 minutes):
- Any other final status (`FAILED`, `STOPPED_WALL_CLOCK`, `STOPPED_BY_SCRIPT`, `INTERRUPTED`): the failed run is kept and reported (course rule), never deleted or edited. Do not run B unless the human decides otherwise (A and B share the on-node scripts, so a script defect likely fails B too, and a fix needs a new commit, a new upload (2.0), a new image tag and new records, none of which this session can do with a clean tree rule S1).
- The script prints `EMR cluster still active after the run`: run the S4 emergency action at once (E1 lookup, then E1 terminate) and report; the cluster bills hourly.
- Spend watch: `ESTIMATED_SPEND_USD` above 0.75.

### 2.8 A, what the run left (read-only): NOT TONIGHT (A-only session, P15-22)

2.8a: the run result from the run directory (no account id is printed; only named keys):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; A_DIR="$(ls -d ${SCD}/experiments/runs/exp-A-* | tail -n 1)"; (echo "RUN_DIR_NAME=${A_DIR##*/}"; echo "--run-record.env"; grep -E '^(RUN_ID|EXPERIMENT_ID|GIT_SHA|DATASET_SNAPSHOT|MANIFEST_SHA256|START_UTC|END_UTC|ELAPSED_SECONDS|EXIT_STATUS|FINAL_STATUS|ESTIMATED_SPEND_USD|SPEND_ESTIMATE_EXCEEDED|METRIC_EXPORT_FAILURES|ENTRY_PROCESSES_STRAY|ENTRY_PROCESSES_REMAINING|RECORD_COMPLETE)=' ${A_DIR}/run-record.env; echo "--pipeline record"; grep -E '^(RUN_ID|SIZE|MAX_MINUTES|START_UTC|END_UTC|FINAL_STATUS|ELAPSED_MINUTES|ESTIMATED_SPEND_USD|RECORD_COMPLETE)=' ${A_DIR}/raw/pipeline/pipeline-*.record.env; echo "--events (last 20)"; tail -n 20 ${A_DIR}/raw/events.jsonl) 2>&1 | tee ${SCD}/logs/phase15-s1-a-result.log | tail -n 60; echo "exit: $?"
```

If more than one `exp-A-*` directory exists (an earlier failed start), set `A_DIR` by hand to the one you mean.

2.8b: download the on-node reports of run A (read-only S3 get; `--only-show-errors` keeps bucket names out of the log). The key layout is `reports/runs/<run_id>/` with `run-record.env`, `run-record.json`, logs, `mapreduce-counters/`, `spark-events/` (`scripts/README.md` "S3 key layout"). `experiments/a-baseline/README.md` does not give this command; this is mine (UNVERIFIED).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; A_RUN="$(grep -o 'run-v1-[0-9a-f-]*' ${SCD}/pipeline-inputs/p15-s1-a.json | head -n 1)"; DATA_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw data_bucket)"; mkdir -p ${SCD}/runs/p15-s1-a && aws s3 cp "s3://${DATA_BUCKET}/reports/runs/${A_RUN}/" ${SCD}/runs/p15-s1-a/reports/ --recursive --only-show-errors --region us-east-2; echo "files: $(find ${SCD}/runs/p15-s1-a/reports -type f | wc -l | tr -d ' ')"; echo "--top level"; ls ${SCD}/runs/p15-s1-a/reports; echo "--mapreduce-counters"; ls ${SCD}/runs/p15-s1-a/reports/mapreduce-counters; echo "--spark-events"; ls -l ${SCD}/runs/p15-s1-a/reports/spark-events; echo "--selected run-record.env keys (names UNVERIFIED)"; grep -E '^(MAPREDUCE|MAPREDUCE_JOB_COUNT|SPARK_ON_YARN|SPARK_MASTER_USED|PREFLIGHT_RESULT|RUN_SCOPE|SPARK_EVENT_LOG_PUBLISHED|SPARK_RUN_MANIFEST_COPY|EXPORT_MANIFEST_COPY|INPUT_LINES_[A-Za-z_]+|STAGE_SECONDS_[A-Z_]+)=' ${SCD}/runs/p15-s1-a/reports/run-record.env ) 2>&1 | tee ${SCD}/logs/phase15-s1-a-reports.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `reports/` holds `run-record.env`, `run-record.json`, `spark-run-manifest.json`, `export-manifest.json`, `mapreduce-counters/` (one file per MapReduce job; run 6 had nine jobs), `spark-events/` (unless the event log was over the 256 MiB scan cap or unscanned, in which case it is not published and the record says so: R4 item 8). Expected keys: `MAPREDUCE=yarn`, `SPARK_ON_YARN=client:yes`, `PREFLIGHT_RESULT=ok`. Stop: none (a missing file is a finding for the results, not a runbook stop); report which of the four re-run asks (MR counters, Spark event log, `wc -l` inputs, manifest copies) did not arrive.

### 2.9 A, derive the course field table (no AWS): NOT TONIGHT (A-only session, P15-22)

From `experiments/a-baseline/README.md` ("What the A re-run records..."), with the A run's paths. `derive-table.py` reads raw files only, refuses an existing `--out-dir`, and prints the counts of RECORDED, DERIVED and NOT_RECORDED fields.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; A_DIR="$(ls -d ${SCD}/experiments/runs/exp-A-* | tail -n 1)"; PIPE_REC="$(ls ${A_DIR}/raw/pipeline/pipeline-*.record.env)"; python3 -I /Users/winnguyen/ScholarChain/experiments/a-baseline/derive-table.py --reports-dir ${SCD}/runs/p15-s1-a/reports --pipeline-record "${PIPE_REC}" --counters-dir ${SCD}/runs/p15-s1-a/reports/mapreduce-counters --spark-eventlog-dir ${SCD}/runs/p15-s1-a/reports/spark-events --out-dir ${SCD}/derived/p15-s1-a-fields 2>&1 | tee ${SCD}/logs/phase15-s1-a-derive.log | tail -n 60; echo "exit: $?"
```

Expected: `FIELDS_DIR=...`, `RECORDED=<n>`, `DERIVED=<n>`, `NOT_RECORDED=<n>`, `exit: 0`. Stop: none; list the NOT_RECORDED fields for the orchestrator (they go in the limitations). Also keep the commit difference from run 6 in the limitations (P15-2).

### 2.9b A, optional capacity collection (same collector as B, read-only): NOT TONIGHT (A-only session, P15-22)

`experiments/b-burst/collect.sh` reads Step Functions and EMR for the run's `raw/window.json`; nothing in it is B-specific (header: "describe-, list- or get- verbs"), but the A README does not mention it, so this is optional and UNVERIFIED for A. It gives A's requested, provisioned and released instance timeline (one primary node) for the results. The collector prints execution ARNs, so the screen output is redacted.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; A_DIR="$(ls -d ${SCD}/experiments/runs/exp-A-* | tail -n 1)"; AWS_PROFILE=scholarchain bash /Users/winnguyen/ScholarChain/experiments/b-burst/collect.sh --run-dir "${A_DIR}" 2>&1 | tee ${SCD}/logs/phase15-s1-a-collect.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `COLLECT_COMPLETE cluster=<id> window=...`, `exit: 0`. A failed required call keeps `collect-failed.<stamp>/` and the command can be repeated (collector header).

### 2.10 Decision gate between A and B: NOT TONIGHT (A-only session, P15-22)

Before step 3 the human reads the A run result (2.8a) and the A derive counts (2.9). Proceed to B only if A finished `COMPLETED` with pipeline `SUCCEEDED` and the clock rule S3 allows it (B must start within 3 h 30 min of the 2.4R start). Otherwise go to step 4 (S2). The next billable item is the B plan and apply (endpoints stay up meanwhile: about 0.12 USD/h).

---

## Step 3. Experiment B (managed scaling)

Tonight 3.1 follows 2.0.5 directly (B-only path, P15-22). Do 3.1 to 3.5 without delay once 2.0 is done; each idle hour with endpoints costs about 0.12 USD (HIGH). Every stop from 3.4R on is under S2: report, then step 4 unless the human decides otherwise within 15 minutes. Stops in 3.1 to 3.3 and 3.4P to 3.4D happen before the apply: nothing is billing, so report and wait.

The B profile values were confirmed by decision P15-21 (the 0.1b precondition). For the record: `scripts/emr/spark-yarn.conf` still calls `b-burst.PARTITION_COUNT=16`, `SPLIT_MAX_BYTES=131072`, `MAP_MEMORY_MB=3072`, `REDUCE_MEMORY_MB=3072` "PROPOSED values, to be confirmed by the experiment owner before the run". It is in the open items. (The file is uploaded from the committed tree in 2.0; a change now would need a new commit, which S1 forbids, so the confirmation must already be in hand before 0.2a.)

### 3.1 Plan B (tonight: session-up from the down state straight to elasticity-s)

Nothing here assumes an earlier `baseline-s` apply. The current state is the session-down state (`deploy-down-5.md`). The layering is `course.tfvars`, then `experiment-size-s.tfvars`, then `elasticity-s.tfvars` last (its header lists the order; `plan.sh` lines 99 to 100, `--extra-var-file` repeats and keeps order). `experiment-size-s.tfvars` turns Neptune and Bedrock on and sets loader 2048/8192 and the 5400 s loader cap; `elasticity-s.tfvars` turns them off again (`enable_neptune = false`, `enable_bedrock = false`, `service_desired_count = 0`, loader back to 512/1024), adds core 1 with scaling cap 4, the 900 s Wait, `emr_idle_timeout_seconds = 1800`, the 4200 s step cap, `pipeline_skip_loader = true` and `pipeline_profile = "b-burst"`. The output directory keeps the name `p15-s1-b-switch` so every later command stays as written.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ENVDIR=/Users/winnguyen/ScholarChain/infra/terraform/environments/course; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; AWS_PROFILE=scholarchain bash scripts/plan.sh --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --extra-var-file ${ENVDIR}/experiment-size-s.tfvars --extra-var-file ${ENVDIR}/elasticity-s.tfvars --out-dir ${SCD}/plans/p15-s1-b-switch 2>&1 | tee ${SCD}/logs/phase15-s1-b-plan.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected plan (UNVERIFIED; read from the overlays, `modules/workflow/main.tf` and `modules/emr/main.tf`, and the shape of `deploy-down-5.md` reversed without Neptune and Bedrock). Roughly 11 to add, a few changes in place, 3 destroyed; `PLAN_EXIT=2`, `STATEFUL_CHANGE=no`, `IAM_CHANGES: none`, `PLAN_DIGEST=<hex>`, `exit: 0`. In detail:
- Created: the 8 interface endpoints (sqs, s3vectors, secretsmanager, ecr.api, ecr.dkr, logs, elasticmapreduce, sts; `egress_mode` back from `none` to the course default `endpoints`). No `bedrock-runtime` endpoint (Bedrock off). They appear under `HIGH_COST_CREATIONS`.
- Replaced (3 added, 3 destroyed): the loader and the two services' task definitions, because the image tag is a new HEAD. The two ECS services are updated in place to the new revisions, still desired count 0.
- Updated in place: the pipeline state machine. Its new definition holds the `WaitBeforeTerminate` state (900 s), a `ManagedScalingPolicy` with `ComputeLimits` 1 / 3 / 3 / 3 (min 1, max 3, max core 3, max on-demand 3, unit `Instances`), `IdleTimeout` 1800, `TimeoutSeconds` 4200 for both step states, the `b-burst` profile as the sixth step argument, and no `AfterTerminate` state (the loader is skipped). The core instance group (1 node) is in the `CreateCluster` parameters; no EMR cluster exists in Terraform.
- Not in the plan: any Neptune resource, a `bedrock-runtime` endpoint, a NAT gateway, an Elastic IP, a load balancer, ECS autoscaling, a worker service, Container Insights, or any change to a bucket, DynamoDB table, S3 Vectors bucket or index, ECR repository or secret. No IAM change (both Neptune and Bedrock statements are already absent in the down state and stay off).
- Stateful change: `STATEFUL_CHANGE=no` is expected; the record keeps `STATEFUL_CHANGE_ACKNOWLEDGED=no`.

### 3.2 Read plan B

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; (echo "--resource actions"; grep -nE '^[[:space:]]*# .*(will be created|will be destroyed|must be replaced|will be replaced|will be updated in-place)' ${SCD}/logs/phase15-s1-b-plan.log; echo "--summary"; sed -n '/^PLAN_SUMMARY_BEGIN/,/^PLAN_SUMMARY_END/p' ${SCD}/logs/phase15-s1-b-plan.log; echo "--meta"; grep -E '^(GIT_SHA|DIRTY|STATEFUL_CHANGE|PLAN_EXIT|CPU_ARCHITECTURE|IMAGE_TAG)=' ${SCD}/plans/p15-s1-b-switch/course-*.tfplan.meta) 2>&1 | tee ${SCD}/logs/phase15-s1-b-plan-actions.log | tail -n 60; echo "exit: $?"
```

Stop conditions (no apply has happened yet, so report and wait; do not write a record): anything not in the expected list of 3.1, in particular any Neptune resource, any `bedrock-runtime` endpoint, any `aws_iam_*` line under `IAM_CHANGES`, any destroy or replacement of a bucket, table, vector bucket, index, repository or secret (`STATEFUL_CHANGE=yes`), an internal load balancer, ECS scaling target or policy, worker service or Container Insights (Wave 2 Terraform in HEAD, which the 0.2c gate should have made impossible), `DIRTY=yes`, `GIT_SHA` not equal to the HEAD of 0.2c, `CPU_ARCHITECTURE` other than `X86_64`, or a different set of added endpoints. Count differences with an explanation (for example an extra endpoint the module adds) are for the human to judge against `deploy-down-5.md`.

### 3.3 Rendered state machine and `ComputeLimits` from plan B (read-only; closes review-2 pending gate 4 for B)

Tonight the image tag is new (no earlier deploy at this HEAD), so the loader task definition ARN is unknown at plan time and the extract may print "the definition is unknown until apply"; then skip validate and summary and rely on 3.5 (UNVERIFIED hypothesis, as for A in 2.3). Three commands, as in 2.3 with the B file names.

Extract:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; PLAN_FILE="$(ls ${SCD}/plans/p15-s1-b-switch/course-*.tfplan)"; terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course show -json "${PLAN_FILE}" | python3 -I -c 'import json,sys; p=json.load(sys.stdin); rc=[r for r in p["resource_changes"] if r["type"]=="aws_sfn_state_machine"]; assert len(rc)==1, "expected exactly one aws_sfn_state_machine change, found %d" % len(rc); c=rc[0]["change"]; assert not (c.get("after_unknown") or {}).get("definition"), "the definition is unknown until apply: this check cannot run before the apply"; open(sys.argv[1], "w").write(c["after"]["definition"]); print("definition written", len(c["after"]["definition"]), "characters")' ${SCD}/logs/phase15-s1-b-sfn-definition-plan.json 2>&1 | tee ${SCD}/logs/phase15-s1-b-sfn-extract.log | tail -n 60; echo "exit: $?"
```

Validate:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; AWS_PROFILE=scholarchain aws stepfunctions validate-state-machine-definition --definition file:///Users/winnguyen/scholarchain-data/logs/phase15-s1-b-sfn-definition-plan.json --type STANDARD --region us-east-2 --output json 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-b-sfn-validate.log | tail -n 60; echo "exit: $?"
```

Summary:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; python3 -I -c 'import re,sys; s=open(sys.argv[1]).read(); print("definition_chars", len(s)); [print(n, re.findall(p, s)) for n, p in (("compute_limits", r"\x22ComputeLimits\x22\s*:\s*\{[^}]*\}"), ("idle_timeout", r"\x22IdleTimeout\x22\s*:\s*\d+"), ("wait_states", r"\x22Type\x22\s*:\s*\x22Wait\x22"), ("wait_seconds", r"\x22Seconds\x22\s*:\s*\d+"), ("timeouts", r"\x22TimeoutSeconds\x22\s*:\s*\d+"), ("run_loader_state", r"\x22RunLoader\x22\s*:"), ("after_terminate_state", r"\x22AfterTerminate\x22\s*:"), ("profile_a_baseline", r"a-baseline"), ("profile_b_burst", r"b-burst"))]' /Users/winnguyen/scholarchain-data/logs/phase15-s1-b-sfn-definition-plan.json 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-b-sfn-summary-plan.log | tail -n 60; echo "exit: $?"
```

Expected for B (column B of the table): one `compute_limits` object with Min 1, Max 3, MaxCore 3, MaxOnDemand 3 and `UnitType` `Instances`; `idle_timeout` 1800; one Wait state with `Seconds` 900; `timeouts` with eight entries 7200, 1440, 300, 4200, 4200, 900, 600, 900 (any order; no 4800); `after_terminate_state []`; `profile_b_burst` present, `profile_a_baseline []`. Stop: any difference (S2). If the definition is unknown until apply, rely on 3.5 and say so.

### 3.4 Deploy B (tonight: the session-up apply from the down state; the session clock S3 starts at 3.4R)

3.4R is the first billable step: the endpoints start billing when created, and the images for the new HEAD tag are built and pushed from `target/docker-local` (Docker must be running; the contexts come from 2.0.1 and are digested in 3.4P). Do not rebuild between 3.4P and 3.4R. Note the UTC time printed as `S3_CLOCK_START_UTC` by 3.4R: the 4 h limit and the 1 h 30 min B-start limit (S3) count from it. Known process gap (Phase 14 README, open items): the apply runs before the image push, so for a short time the task definitions name a tag not yet in ECR; services are at desired count 0, so nothing pulls it.

3.4P print the deploy digests:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PLAN_FILE="$(ls ${SCD}/plans/p15-s1-b-switch/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --print-digest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --plan-file "${PLAN_FILE}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-s1-b-switch 2>&1 | tee ${SCD}/logs/phase15-s1-b-deploy-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

3.4W write the record:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; REC=${SCD}/approvals/p15-s1-b-deploy.env; dg() { awk -F= -v k="${2}" '$1 == k { print $2; exit }' "${1}"; }; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; EXP="$(date -u -v+8H +%Y-%m-%dT%H:%M:%SZ)"; ( (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=deploy" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=20" "PLAN_DIGEST=$(dg ${SCD}/logs/phase15-s1-b-plan.log PLAN_DIGEST)" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-s1-b-deploy-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-s1-b-deploy-digest.log ARGS_DIGEST)" "GIT_SHA=$(git rev-parse HEAD)" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > "${REC}") && sed "s/^ACCOUNT_ID=.*/ACCOUNT_ID=<masked>/" "${REC}" ) 2>&1 | tee ${SCD}/logs/phase15-s1-b-deploy-record.log | tail -n 60; echo "exit: $?"
```

3.4D dry run:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PLAN_FILE="$(ls ${SCD}/plans/p15-s1-b-switch/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --dry-run --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-s1-b-deploy.env --plan-file "${PLAN_FILE}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-s1-b-switch 2>&1 | tee ${SCD}/logs/phase15-s1-b-deploy-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

3.4R apply:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PLAN_FILE="$(ls ${SCD}/plans/p15-s1-b-switch/course-*.tfplan)"; date -u +"S3_CLOCK_START_UTC=%Y-%m-%dT%H:%M:%SZ"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-s1-b-deploy.env --plan-file "${PLAN_FILE}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-s1-b-switch 2>&1 | tee ${SCD}/logs/phase15-s1-b-deploy.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `S3_CLOCK_START_UTC=...` (written down; it is not in the log), steps 1 to 12 `ok`, `IMAGE_*` lines for both repositories (built and pushed for the new HEAD tag), `DEPLOY_COMPLETE`, `DEPLOYED_REVISION=<HEAD>`, `exit: 0`; apply counts equal to the plan within the known state-machine plan-versus-apply gap (Phase 14 README, discrepancy 1). Rollback gate for the session-up: a plan with `session-down.tfvars` (step 4); pushed image tags are immutable and stay. A half-applied plan: run `plan.sh` again with the same overlays to see the drift and report; no second apply without a new plan, a new record and a new human decision. Stop (S2): any failure; do not re-run the same record (a failed run consumed it).

### 3.5 Deployed definition of B (read-only)

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; SM="$(aws stepfunctions list-state-machines --region us-east-2 --query "stateMachines[?name=='scholarchain-course-pipeline'].stateMachineArn | [0]" --output text)"; (set -C; aws stepfunctions describe-state-machine --state-machine-arn "${SM}" --region us-east-2 --query definition --output text > ${SCD}/logs/phase15-s1-b-sfn-definition-live.json) && python3 -I -c 'import re,sys; s=open(sys.argv[1]).read(); print("definition_chars", len(s)); [print(n, re.findall(p, s)) for n, p in (("compute_limits", r"\x22ComputeLimits\x22\s*:\s*\{[^}]*\}"), ("idle_timeout", r"\x22IdleTimeout\x22\s*:\s*\d+"), ("wait_states", r"\x22Type\x22\s*:\s*\x22Wait\x22"), ("wait_seconds", r"\x22Seconds\x22\s*:\s*\d+"), ("timeouts", r"\x22TimeoutSeconds\x22\s*:\s*\d+"), ("run_loader_state", r"\x22RunLoader\x22\s*:"), ("after_terminate_state", r"\x22AfterTerminate\x22\s*:"), ("profile_a_baseline", r"a-baseline"), ("profile_b_burst", r"b-burst"))]' ${SCD}/logs/phase15-s1-b-sfn-definition-live.json ) 2>&1 | tee ${SCD}/logs/phase15-s1-b-sfn-summary-live.log | tail -n 60; echo "exit: $?"
```

Expected: the B column. Stop (S2: no run starts): any difference. `get-managed-scaling-policy` on a running cluster is only possible during the run; the collector calls it after (3.9).

### 3.6 B, records for the run

Check the clock first: B must start no later than 1 h 30 min after the `S3_CLOCK_START_UTC` of 3.4R (S3, B-only). Re-run command 1.3 (state check) immediately before. Then the write, dry run and run, with B's digests from the 1.6B logs. The B manifest binds `MAX_MINUTES=90` and the pipeline stops at 88 min (P15-16). Limitation P15-20 applies: if the 88-minute stop fires, the cluster is still terminated and cost stays bounded (up to 0.13 USD of extra cluster time is in the HIGH), but the teardown check can take up to 300 s (`run-bounded-pipeline.sh` lines 482 to 491), more than the about 2 minutes left before the 90-minute entry limit, so the end of the run record (`window.json`, the last events) may be cut.

3.6W:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; dg() { awk -F= -v k="${2}" '$1 == k { print $2; exit }' "${1}"; }; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; EXP="$(date -u -v+8H +%Y-%m-%dT%H:%M:%SZ)"; ( (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=pipeline" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=20" "PLAN_DIGEST=n/a" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-s1-b-pipeline-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-s1-b-pipeline-digest.log ARGS_DIGEST)" "GIT_SHA=n/a" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > ${SCD}/approvals/p15-s1-b-pipeline.env) && (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=experiment" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=2.40" "PLAN_DIGEST=n/a" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-s1-b-experiment-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-s1-b-experiment-digest.log ARGS_DIGEST)" "GIT_SHA=$(git rev-parse HEAD)" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > ${SCD}/approvals/p15-s1-b-experiment.env) && sed "s/^ACCOUNT_ID=.*/ACCOUNT_ID=<masked>/" ${SCD}/approvals/p15-s1-b-pipeline.env ${SCD}/approvals/p15-s1-b-experiment.env ) 2>&1 | tee ${SCD}/logs/phase15-s1-b-run-records.log | tail -n 60; echo "exit: $?"
```

3.6D (the verdict is the two `part` exit lines):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; { AWS_PROFILE=scholarchain bash scripts/run-bounded-pipeline.sh --dry-run --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-s1-b-pipeline.env --size medium --max-minutes 88 --spend-ceiling-usd 2.30 --hourly-burn-usd 1.55 --stop-on first-failure,dlq-nonempty --input-file ${SCD}/pipeline-inputs/p15-s1-b.json 2>&1 | tee ${SCD}/logs/phase15-s1-b-pipeline-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g"; echo "part1 exit: $?"; AWS_PROFILE=scholarchain bash scripts/run-experiment.sh --dry-run --manifest ${SCD}/experiments/manifests/p15-s1-b-burst.manifest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 2.40 --approval-record ${SCD}/approvals/p15-s1-b-experiment.env 2>&1 | tee ${SCD}/logs/phase15-s1-b-experiment-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g"; echo "part2 exit: $?"; } | tail -n 60; echo "exit: $?"
```

Expected: both `APPROVAL_VERIFIED`, `DRY_RUN_COMPLETE`, `part1 exit: 0`, `part2 exit: 0`. Stop (S2): any refusal or non-zero part exit.

### 3.7 Run B (the billable run)

Same structure as 2.7. `experiments/b-burst/run.sh` starts one pipeline execution (88 min stop) with the `b-burst` profile: MapReduce on HDFS across the core nodes, Spark and export as `local[4]` on the primary (P15-12), then the 900 s Wait with the cluster idle so a scale-in can happen, then terminate. Realistic run about 4,300 s (WO-1b). Limitations on the record, as written, not to be edited into results later: P15-12 (B shows MapReduce scaling only, not Spark scaling), P15-20 (above), P15-11 (if no clear scale-out and scale-in, D's ECS scaling is the second source).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; SCHOLARCHAIN_PIPELINE_INPUT_FILE=${SCD}/pipeline-inputs/p15-s1-b.json SCHOLARCHAIN_PIPELINE_APPROVAL_RECORD=${SCD}/approvals/p15-s1-b-pipeline.env AWS_PROFILE=scholarchain bash scripts/run-experiment.sh --manifest ${SCD}/experiments/manifests/p15-s1-b-burst.manifest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 2.40 --approval-record ${SCD}/approvals/p15-s1-b-experiment.env 2>&1 | tee ${SCD}/logs/phase15-s1-b-run.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Optional, read-only, second terminal while it runs (the b-burst README step 3: watch the node counts change; replace `REPLACE_WITH_CLUSTER_ID` with the id the first command prints). Run the first command after the pipeline's `CreateCluster` has finished; run the second a few times during the MapReduce stage and the Wait:

```
( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; aws emr list-clusters --active --region us-east-2 --query 'Clusters[].[Id,Name,Status.State]' --output text )
```

```
( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; CLUSTER_ID="REPLACE_WITH_CLUSTER_ID"; date -u +%H:%M:%SZ; aws emr list-instance-groups --cluster-id "${CLUSTER_ID}" --region us-east-2 --query 'InstanceGroups[].[InstanceGroupType,RequestedInstanceCount,RunningInstanceCount,Status.State]' --output text )
```

and the pipeline's own poll line: `tail -n 5 "$(ls -t /Users/winnguyen/scholarchain-data/experiments/runs/exp-B-*/raw/pipeline-run.log | head -n 1)"`.

Expected at the end: `ENTRY_EXIT=0 FINAL_STATUS=COMPLETED`, pipeline `SUCCEEDED`, `NO_ACTIVE_CLUSTER`, `RECORD_COMPLETE=yes`, `exit: 0`. Stop conditions (S2: report, then step 4 unless the human decides otherwise within 15 minutes): as 2.7 (a failed or stopped B is kept and reported; no retry in this session; a repeat is a new, approved session). `EMR cluster still active after the run` means the S4 emergency action at once. In particular the Hadoop stage can fail if no core NodeManager registers within `NM_WAIT_SECONDS=300` (the script then dies with a clear message and never falls back to `file:///`, `spark-yarn.conf` header of the `b-burst` block): that is a finding, not something to fix mid-run.

### 3.8 B, what the run left (read-only)

3.8a run result (same as 2.8a with B):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; B_DIR="$(ls -d ${SCD}/experiments/runs/exp-B-* | tail -n 1)"; (echo "RUN_DIR_NAME=${B_DIR##*/}"; echo "--run-record.env"; grep -E '^(RUN_ID|EXPERIMENT_ID|GIT_SHA|DATASET_SNAPSHOT|MANIFEST_SHA256|START_UTC|END_UTC|ELAPSED_SECONDS|EXIT_STATUS|FINAL_STATUS|ESTIMATED_SPEND_USD|SPEND_ESTIMATE_EXCEEDED|METRIC_EXPORT_FAILURES|ENTRY_PROCESSES_STRAY|ENTRY_PROCESSES_REMAINING|RECORD_COMPLETE)=' ${B_DIR}/run-record.env; echo "--pipeline record"; grep -E '^(RUN_ID|SIZE|MAX_MINUTES|START_UTC|END_UTC|FINAL_STATUS|ELAPSED_MINUTES|ESTIMATED_SPEND_USD|RECORD_COMPLETE)=' ${B_DIR}/raw/pipeline/pipeline-*.record.env; echo "--events (last 20)"; tail -n 20 ${B_DIR}/raw/events.jsonl) 2>&1 | tee ${SCD}/logs/phase15-s1-b-result.log | tail -n 60; echo "exit: $?"
```

3.8b on-node reports of run B (the MapReduce counters are B's task and shuffle evidence; `MR_COUNTERS=true` in the `b-burst` profile):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; B_RUN="$(grep -o 'run-v1-[0-9a-f-]*' ${SCD}/pipeline-inputs/p15-s1-b.json | head -n 1)"; DATA_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw data_bucket)"; mkdir -p ${SCD}/runs/p15-s1-b && aws s3 cp "s3://${DATA_BUCKET}/reports/runs/${B_RUN}/" ${SCD}/runs/p15-s1-b/reports/ --recursive --only-show-errors --region us-east-2; echo "files: $(find ${SCD}/runs/p15-s1-b/reports -type f | wc -l | tr -d ' ')"; echo "--top level"; ls ${SCD}/runs/p15-s1-b/reports; echo "--mapreduce-counters"; ls ${SCD}/runs/p15-s1-b/reports/mapreduce-counters; echo "--selected run-record.env keys (names UNVERIFIED)"; grep -E '^(MAPREDUCE|MAPREDUCE_JOB_COUNT|SPARK_ON_YARN|SPARK_MASTER_USED|PREFLIGHT_RESULT|RUN_SCOPE|DATA_PATH[A-Z_]*|NM_[A-Z_]*|INPUT_LINES_[A-Za-z_]+|STAGE_SECONDS_[A-Z_]+)=' ${SCD}/runs/p15-s1-b/reports/run-record.env ) 2>&1 | tee ${SCD}/logs/phase15-s1-b-reports.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected keys (UNVERIFIED names, from `scripts/README.md` "Phase 15 sessions A and B" and the R4 report): `RUN_SCOPE` text for HDFS plus local Spark, `SPARK_ON_YARN=local-master:yes`, `SPARK_MASTER_USED=local[4]`, `MAPREDUCE=yarn`.

### 3.9 B, collect the capacity data (read-only; the first command of `experiments/b-burst/README.md` step 4)

Writes `<run dir>/collected/` once through a staging directory (`collect.sh` header "Partial failure"). The cluster may already be gone; an EMR cluster stays describable for about two months and CloudWatch keeps 5-minute data 63 days (README, UNVERIFIED retention figures). Calls: `stepfunctions describe-execution` and `get-execution-history`, `emr describe-cluster`, `list-instance-groups`, `list-instances`, optional `get-managed-scaling-policy`, `list-steps` and `cloudwatch list-metrics`, then `cloudwatch get-metric-data` (`collect.sh` lines 158 to 177). The collector prints execution ARNs, so the screen output is redacted.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; B_DIR="$(ls -d ${SCD}/experiments/runs/exp-B-* | tail -n 1)"; AWS_PROFILE=scholarchain bash /Users/winnguyen/ScholarChain/experiments/b-burst/collect.sh --run-dir "${B_DIR}" 2>&1 | tee ${SCD}/logs/phase15-s1-b-collect.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `CALL ...` lines, `CLUSTER_ID=...`, `COLLECTED_DIR=`, `COLLECT_COMPLETE cluster=<id> window=...`, `exit: 0`. An optional call that fails is named in `CALL_STATUS.txt` (for example `get-managed-scaling-policy` on a terminated cluster, UNVERIFIED). A required call that fails keeps `collect-failed.<stamp>/`; fix the cause and repeat. Stop: a required failure that repeats (report). These collection steps need no session resources: if the clock (S3) is running short, do step 4 first and come back (all of 3.8 to 3.11 work after the teardown).

### 3.10 B, derive the tables and the plot (no AWS)

From `b-burst/README.md` step 5. The `derive` subcommand and its flags are in `derive-capacity.py` lines 787 to 792.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; B_DIR="$(ls -d ${SCD}/experiments/runs/exp-B-* | tail -n 1)"; python3 -I /Users/winnguyen/ScholarChain/experiments/b-burst/derive-capacity.py derive --collected-dir "${B_DIR}/collected" --out-dir "${B_DIR}/derived" --title "Experiment B: EMR capacity over time" 2>&1 | tee ${SCD}/logs/phase15-s1-b-derive.log | tail -n 60; echo "exit: $?"
```

Expected: `DERIVED_DIR=...` and the line `scale_out_observed=<yes|unconfirmed|no> scale_in_observed=<yes|no|unknown>` (`derive-capacity.py` line 779), `exit: 0`. The gate evidence wants `scale_out_observed=yes` and `scale_in_observed=yes`. `unconfirmed`, `no` or `unknown` is not a stop for the session: it is the result, reported as is, and decision P15-11 (D's ECS scaling as the second source) applies. Do not re-run B to chase a better result (course rule: no tuning during a run; a repeat is a new, approved session).

### 3.11 Redaction copies before any results commit (review 2 N4)

The raw run directories stay exactly as written. The copy below replaces the 12-digit account id with `000000000000` in a COPY of the whole run directory. `experiments/b-burst/README.md` ("Before a results commit") lists `window.json`, `pipeline-run.log`, `pipeline/*.record.env` and `collected/`; it does not list `run-record.env` (it holds `ACCOUNT_ID=`), `entrypoint.log` or `metrics/`, so this copies the whole directory instead (my choice, a superset of the README's list). The copy's `SHA256SUMS` no longer matches after redaction: the raw directory is the source of truth. The final grep is the control; the account id also appears inside bucket names and ARNs, so a bucket name is redacted too. The copies live under `scholarchain-data/`; they are committed to the repository only after 4.5 (S1).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; B_DIR="$(ls -d ${SCD}/experiments/runs/exp-B-* | tail -n 1)"; COPY=${SCD}/results-copy/p15-s1-b; if [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ ]]; then mkdir -p ${SCD}/results-copy && cp -R "${B_DIR}" "${COPY}" && chmod -R u+w "${COPY}" && find "${COPY}" -type f -exec sed -i.bak "s/${ACCOUNT_ID}/000000000000/g" {} + && find "${COPY}" -name '*.bak' -delete; if grep -rq "${ACCOUNT_ID}" "${COPY}"; then echo "REDACTION_FAILED: the account id is still in ${COPY}"; false; else echo "REDACTION_OK: the account id is not in ${COPY}"; fi; else echo "no 12-digit account id from STS: STOP"; false; fi 2>&1 | tee ${SCD}/logs/phase15-s1-b-redact.log | tail -n 60; echo "exit: $?"
```

The same for A: NOT TONIGHT (A-only session, P15-22); skip this block tonight (kept for `session-1b-a-only-draft.md`):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; A_DIR="$(ls -d ${SCD}/experiments/runs/exp-A-* | tail -n 1)"; COPY=${SCD}/results-copy/p15-s1-a; if [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ ]]; then mkdir -p ${SCD}/results-copy && cp -R "${A_DIR}" "${COPY}" && chmod -R u+w "${COPY}" && find "${COPY}" -type f -exec sed -i.bak "s/${ACCOUNT_ID}/000000000000/g" {} + && find "${COPY}" -name '*.bak' -delete; if grep -rq "${ACCOUNT_ID}" "${COPY}"; then echo "REDACTION_FAILED: the account id is still in ${COPY}"; false; else echo "REDACTION_OK: the account id is not in ${COPY}"; fi; else echo "no 12-digit account id from STS: STOP"; false; fi 2>&1 | tee ${SCD}/logs/phase15-s1-a-redact.log | tail -n 60; echo "exit: $?"
```

Expected: `REDACTION_OK`, `exit: 0`. Stop: `REDACTION_FAILED` (nothing from that copy is committed). The derived outputs of 2.9 and 3.10 carry no account id (stated in the scripts' headers); the on-node reports under `runs/p15-s1-*/reports` are not part of the copy and are not committed as they are.

---

## Step 4. Session-down (same day)

Do this as soon as B's run has ended and its cluster is gone (or as soon as S2 or S3 sends you here). 3.8 to 3.11 are read-only and need no session resources, so they can wait until after the teardown if you prefer to stop the endpoint hours first (about 0.03 USD per 15 minutes at HIGH). The session-down apply (4.3R) must start no later than 4 hours after the 3.4R start (S3, B-only). Never combine `session-down.tfvars` with a size overlay (its header). The tree must still be clean here (S1); if `deploy.sh` refuses a dirty tree, something wrote to the repository: find what with `git status --short`, report, and do not discard anything.

### 4.1 Plan the session-down

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ENVDIR=/Users/winnguyen/ScholarChain/infra/terraform/environments/course; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; AWS_PROFILE=scholarchain bash scripts/plan.sh --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --extra-var-file ${ENVDIR}/session-down.tfvars --out-dir ${SCD}/plans/p15-s1-down 2>&1 | tee ${SCD}/logs/phase15-s1-down-plan.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected (UNVERIFIED; the shape of `deploy-down-5` without Neptune and Bedrock): the 8 interface endpoints destroyed, the pipeline state machine updated in place (Wait, profile and skip-loader returned to defaults, `IdleTimeout` back), possibly nothing else; `STATEFUL_CHANGE=no` (the endpoints are not in `STATEFUL_TYPES`, `lib.sh` line 848; no Neptune exists to destroy), `IAM_CHANGES: none`, `exit: 0`. The plan must not destroy a bucket, table, vector bucket, index, repository or secret (`scripts/README.md`, "Session up and session down", step 1). `HEAD` unchanged.

### 4.2 Read plan down

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; (echo "--resource actions"; grep -nE '^[[:space:]]*# .*(will be created|will be destroyed|must be replaced|will be replaced|will be updated in-place)' ${SCD}/logs/phase15-s1-down-plan.log; echo "--summary"; sed -n '/^PLAN_SUMMARY_BEGIN/,/^PLAN_SUMMARY_END/p' ${SCD}/logs/phase15-s1-down-plan.log; echo "--meta"; grep -E '^(GIT_SHA|DIRTY|STATEFUL_CHANGE|PLAN_EXIT|IMAGE_TAG)=' ${SCD}/plans/p15-s1-down/course-*.tfplan.meta) 2>&1 | tee ${SCD}/logs/phase15-s1-down-plan-actions.log | tail -n 60; echo "exit: $?"
```

Stop: any destroy line other than `aws_vpc_endpoint` interface endpoints; any `STATEFUL_CHANGE=yes` (the record would need `STATEFUL_CHANGE_ACKNOWLEDGED=yes` and the human must first check what is being destroyed); `DIRTY=yes`. Here a stop does not mean "go to step 4" (you are in it): the endpoints are still billing, so decide within 15 minutes and say so in the chat.

### 4.3 Deploy down

4.3P:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PLAN_FILE="$(ls ${SCD}/plans/p15-s1-down/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --print-digest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --plan-file "${PLAN_FILE}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-s1-down 2>&1 | tee ${SCD}/logs/phase15-s1-down-deploy-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

4.3W:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; REC=${SCD}/approvals/p15-s1-down-deploy.env; dg() { awk -F= -v k="${2}" '$1 == k { print $2; exit }' "${1}"; }; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; EXP="$(date -u -v+8H +%Y-%m-%dT%H:%M:%SZ)"; ( (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=deploy" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=20" "PLAN_DIGEST=$(dg ${SCD}/logs/phase15-s1-down-plan.log PLAN_DIGEST)" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-s1-down-deploy-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-s1-down-deploy-digest.log ARGS_DIGEST)" "GIT_SHA=$(git rev-parse HEAD)" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > "${REC}") && sed "s/^ACCOUNT_ID=.*/ACCOUNT_ID=<masked>/" "${REC}" ) 2>&1 | tee ${SCD}/logs/phase15-s1-down-deploy-record.log | tail -n 60; echo "exit: $?"
```

4.3D:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PLAN_FILE="$(ls ${SCD}/plans/p15-s1-down/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --dry-run --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-s1-down-deploy.env --plan-file "${PLAN_FILE}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-s1-down 2>&1 | tee ${SCD}/logs/phase15-s1-down-deploy-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

4.3R:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PLAN_FILE="$(ls ${SCD}/plans/p15-s1-down/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-s1-down-deploy.env --plan-file "${PLAN_FILE}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-s1-down 2>&1 | tee ${SCD}/logs/phase15-s1-down-deploy.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `IMAGE_EXISTS` for both, `DEPLOY_COMPLETE`, `DEPLOYED_REVISION=<HEAD>`, `exit: 0`. Rollback gate: there is no reversal of the endpoint destroy; the way back is a new session-up plan (a new record). Storage is untouched. Stop: a failure; the endpoints may still be up, so report at once (they bill about 0.12 USD/h at HIGH, 2.88 USD a day), run a new plan to see the drift, and decide within 15 minutes.

### 4.4 Post-teardown read-only check (review M7)

Labeled sections as in the Phase 14 confirm logs, now with UTC stamps and the items review 1 found missing: Elastic IPs, load balancers, unattached EBS volumes, Neptune instances and ECS service desired counts. All calls are describe-, list- or get- reads (the flag forms for the `ec2`, `elbv2`, `ecs` and `neptune` calls are UNVERIFIED against the CLI reference; none of them appears in the repository's scripts). Run it once right after 4.3R and again later in the day if you wish.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; mkdir -p /Users/winnguyen/scholarchain-data/logs; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; echo "--utc ${STAMP}"; echo "--emr-active-clusters (expect empty)"; aws emr list-clusters --active --region us-east-2 --query 'Clusters[].[Id,Name,Status.State]' --output text; echo "--emr-clusters-last-24h (expect tonight's B cluster, TERMINATED; tomorrow's A-only session also shows tonight's)"; aws emr list-clusters --created-after "$(date -u -v-24H +%Y-%m-%dT%H:%M:%SZ)" --region us-east-2 --query 'Clusters[].[Id,Name,Status.State,Status.StateChangeReason.Code]' --output text; echo "--ecs-running-tasks (expect none under every cluster)"; for C in $(aws ecs list-clusters --region us-east-2 --query 'clusterArns[]' --output text); do echo "cluster=${C##*/}"; aws ecs list-tasks --cluster "${C}" --desired-status RUNNING --region us-east-2 --query 'taskArns[]' --output text; done; echo "--ecs-service-desired-counts (expect desired 0 and running 0 for every service)"; for C in $(aws ecs list-clusters --region us-east-2 --query 'clusterArns[]' --output text); do echo "cluster=${C##*/}"; aws ecs describe-services --cluster "${C}" --services $(aws ecs list-services --cluster "${C}" --region us-east-2 --query 'serviceArns[]' --output text) --region us-east-2 --query 'services[].[serviceName,desiredCount,runningCount]' --output text; done; echo "--neptune-clusters (expect empty: Neptune was never created in session 1 and session-down keeps it off)"; aws neptune describe-db-clusters --region us-east-2 --query 'DBClusters[].[DBClusterIdentifier,Status]' --output text; echo "--neptune-instances (expect empty)"; aws neptune describe-db-instances --region us-east-2 --query 'DBInstances[].[DBInstanceIdentifier,DBInstanceClass,DBInstanceStatus]' --output text; echo "--vpc-endpoints (expect only Gateway rows for s3 and dynamodb, available)"; aws ec2 describe-vpc-endpoints --region us-east-2 --query 'VpcEndpoints[].[VpcEndpointType,ServiceName,State]' --output text; echo "--nat-gateways (expect empty)"; aws ec2 describe-nat-gateways --region us-east-2 --filter "Name=state,Values=pending,available" --query 'NatGateways[].[NatGatewayId,State]' --output text; echo "--elastic-ips (expect empty)"; aws ec2 describe-addresses --region us-east-2 --query 'Addresses[].[AllocationId,AssociationId]' --output text; echo "--load-balancers (expect empty)"; aws elbv2 describe-load-balancers --region us-east-2 --query 'LoadBalancers[].[LoadBalancerName,Type,State.Code]' --output text; echo "--ebs-volumes-unattached (expect empty)"; aws ec2 describe-volumes --region us-east-2 --filters "Name=status,Values=available" --query 'Volumes[].[VolumeId,Size]' --output text; echo "--ec2-instances-pending-or-running (expect empty)"; aws ec2 describe-instances --region us-east-2 --filters "Name=instance-state-name,Values=pending,running" --query 'Reservations[].Instances[].[InstanceId,InstanceType]' --output text; echo "--sfn-running-executions (expect empty)"; aws stepfunctions list-executions --state-machine-arn "$(aws stepfunctions list-state-machines --region us-east-2 --query "stateMachines[?name=='scholarchain-course-pipeline'].stateMachineArn | [0]" --output text)" --status-filter RUNNING --region us-east-2 --query 'executions[].[name,status]' --output text; echo "--sfn-last-executions (expect tonight's B execution, SUCCEEDED)"; aws stepfunctions list-executions --state-machine-arn "$(aws stepfunctions list-state-machines --region us-east-2 --query "stateMachines[?name=='scholarchain-course-pipeline'].stateMachineArn | [0]" --output text)" --max-results 5 --region us-east-2 --query 'executions[].[name,status,startDate,stopDate]' --output text; echo "--dlq-run-requests (expect 0 0)"; aws sqs get-queue-attributes --queue-url "$(aws sqs get-queue-url --queue-name scholarchain-course-run-requests-dlq --region us-east-2 --query QueueUrl --output text)" --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible --region us-east-2 --output text; echo "--dlq-embedding-work (expect 0 0)"; aws sqs get-queue-attributes --queue-url "$(aws sqs get-queue-url --queue-name scholarchain-course-embedding-work-dlq --region us-east-2 --query QueueUrl --output text)" --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible --region us-east-2 --output text ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-down-confirm-${STAMP}.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 120; echo "exit: $?"
```

Expected results (as in `deploy-down-5.md`, "Smoke evidence", plus the new items): no active EMR cluster, the last-24h list shows exactly the A and B clusters in a TERMINATED state (any other cluster is a finding), no running ECS task, every ECS service at desired 0 and running 0 (the services exist at desired count 0 by design), no Neptune cluster and no Neptune instance, only `Gateway` endpoints for the S3 and DynamoDB services in state `available` (no `Interface` row), no NAT gateway, no Elastic IP, no load balancer, no unattached EBS volume, no running EC2 instance, no running execution, the last executions `SUCCEEDED` for A and B (or the real status if a run failed), DLQs `0 0`. A service listing that is empty makes `describe-services` print a usage error for that cluster: read it as "no services" (UNVERIFIED). Stop (report at once, nothing else is changed by the agent): any active cluster, any interface endpoint, any running instance, any Elastic IP, load balancer or unattached volume; for a leftover EMR cluster use S4. Billing guidance: the idle items after the teardown are storage only (S3, DynamoDB, ECR, S3 Vectors, secrets, log groups), as in Phase 14. Still not checked here: CloudWatch alarms and log groups, which stay by design.

### 4.5 Restore the Wave 2 work (review B1, step (d))

Run this only after 4.4 passed. Until it runs, S1 holds. If the session was abandoned before 2.4R (nothing was deployed), run it right away.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; ( git stash list | head -n 5; git stash pop; echo "STASH_POP_EXIT=$?"; echo "--git status --short"; git status --short | head -n 60 ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1-stash-pop.log | tail -n 80; echo "exit: $?"
```

Expected: the stash list shows `p15-wave2-wip`, `STASH_POP_EXIT=0`, and `git status --short` lists the Wave 2 files again (modified `build.sbt`, the `modules/rag`, `modules/services` and `modules/storage` files, and the untracked `experiments/c-embedding-backlog/`, `experiments/d-query-load/`, `experiments/e-retrieval-modes/`, `experiments/f-failure-recovery/`, `load-tests/` files and the new Scala files, as in the git status snapshot of the start of the session). Compare it with that snapshot. Stop: a stash-pop conflict or any `STASH_POP_EXIT` other than 0. Never resolve it by discarding anything: no `git checkout`, `git reset`, `git stash drop` or `git clean`. Leave the stash in place (a failed pop keeps it), report the conflict lines, and wait for the orchestrator and the human.

After 4.5 the orchestrator may write evidence into the repository (S1 ends).

---

## Step 5. Evidence to paste back, and where the orchestrator records it

During the session the evidence goes to `/Users/winnguyen/scholarchain-data/logs/` (every command already writes there) or into the chat. The orchestrator records it in the repository only after 4.5 (S1).

### 5.1 Redacted copy of the logs for pasting

Logs may contain the account id (ARNs, bucket names in upload and deploy output). This writes one redacted file with the last 30 lines of every session-1 log; paste from it, not from the raw logs. It writes only under `scholarchain-data/`.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; if [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ ]]; then (for F in ${SCD}/logs/phase15-s1-*.log; do echo "=== ${F##*/}"; tail -n 30 "${F}"; done) | sed "s/${ACCOUNT_ID}/000000000000/g" > ${SCD}/logs/phase15-s1-paste-back.txt; wc -l ${SCD}/logs/phase15-s1-paste-back.txt; else echo "no 12-digit account id from STS: STOP"; false; fi 2>&1 | tail -n 60; echo "exit: $?"
```

Note: the output file is named `phase15-s1-paste-back.txt`, not `.log`, so a second run does not include the first run's copy.

### 5.2 What to paste, per step

| Step | Paste (from `phase15-s1-paste-back.txt`, or say "done" and the orchestrator reads the log) | Why |
|---|---|---|
| 0.1, 0.2 | `billing-gate`, `docs-stage`, `docs-commit`, `stash-push`, `tree-head` | P15-10 gate, the docs-only commit, and the pin to 085b5ec's code |
| 1.1 to 1.6 | `identity`, `state-check-<stamp>`, `raw-listing`, `prepare-inputs`, the four `-digest` logs | pre-checks, the A and B run ids, the manifest and pipeline digests |
| 2.0 | `package`, `upload-digest`, `raw-compare`, `jars-compare`, `upload-record` (masked), `upload-dry`, `upload`, `bootstrap-check` | raw inputs equal the snapshot, no stale jars, the node scripts in S3 are the committed ones |
| 2.1 to 2.5 | `a-plan`, `a-plan-actions`, `a-sfn-*`, `a-deploy-digest`, `a-deploy-record`, `a-deploy-dry`, `a-deploy`, `a-sfn-summary-live` | plan digest, approvals, apply, rendered and deployed definition |
| 2.6 to 2.9 | `a-run-records`, `a-pipeline-dry`, `a-experiment-dry`, `a-run`, `a-result`, `a-reports`, `a-derive`, `a-collect`, `a-redact` | the A run, its records and the course field table |
| 3.1 to 3.5 | `b-plan`, `b-plan-actions`, `b-sfn-*`, `b-deploy-*`, `b-sfn-summary-live` | the same for B |
| 3.6 to 3.11 | `b-run-records`, `b-pipeline-dry`, `b-experiment-dry`, `b-run`, `b-result`, `b-reports`, `b-collect`, `b-derive`, `b-redact` | the B run, `scale_out_observed` and `scale_in_observed` |
| 4 | `down-plan`, `down-plan-actions`, `down-deploy-*`, `down-confirm-<stamp>`, `stash-pop` | the teardown, its confirmation, and Wave 2 restored |
| any | `emergency-lookup-*`, `emergency-terminate-*` if S4 was used | the human emergency action, recorded as such |
| 5 | the billed total after the session (next day) | actual session spend against the B-only 2.83 HIGH (ignore every A row tonight) |

Do not paste: approval files (even masked, paste only the logs of the W commands), the raw `sfn-*` or definition files, the on-node reports, or any `s3://` bucket name. Keep those under `scholarchain-data/`.

### 5.3 Where the orchestrator records it (only after 4.5)

The orchestrator creates `docs/agent-runs/phase-15/session-1/` content and records, preserving each item as received with its author. Before 4.5 nothing is written there except what 0.2a already committed.
- `human-commands.md`: this runbook, copied as is (already committed in 0.2a; do not rewrite it).
- `evidence.md`: the pasted tails above, one section per step, with UTC times, plus a ledger with one row per approval record: action, plan digest, script digest, args digest (from the logs), cost bound (this session's share of the B-only 2.83 HIGH), rollback gate (the text under 2.4R, 3.4R and 4.3R), approved-by and approved-at.
- `a-run-result.md` and `b-run-result.md` (experiment-engineer's results work order, not mine): run ids, the derive outputs, the limitations P15-2, P15-12, P15-16, P15-20, and P15-11 if B does not show both events. The raw run directories stay in `scholarchain-data/`; only the redacted copies and the derived outputs are committed, by the human.

Deploy-engineer writes the release records afterwards, as in `docs/releases/phase-14/` (records only, written from the logs and records; no command run): `docs/releases/phase-15/deploy-s1-a-up.md`, `deploy-s1-b-switch.md`, `deploy-s1-down.md` and a README with the outcome table. Fields: HEAD, plan digest, script and args digests, approval file name and window, apply counts, image digests, smoke evidence (the read-only checks above), rollback gate, open items.

---

## Open items

1. B1 is a human decision point. The default is 0.2a to 0.2c and 4.5. The human may override it (for example a different way to park Wave 2); the 0.2c gate (clean tree, no code difference from 085b5ec) must pass whichever way is chosen. UNVERIFIED: that `git stash push -u` leaves every ignored state file (`terraform.tfstate*`, `.terraform/`) in place (it should: they are git-ignored, per review B1), and that a stale ignored Wave 2 build output in `target/` cannot leak into `package.sh` (2.0.2c checks the jar names).
2. The `b-burst` profile values are PROPOSED, to be confirmed by the experiment owner before the run (`scripts/emr/spark-yarn.conf` comment). It is a stated precondition in 0.1b and must happen before 0.2a, because the file is uploaded from the committed tree and S1 forbids a later commit. The NodeManager memory of an m5.xlarge is not read. Whether 16 reducers on 3 GiB containers create pending YARN containers is UNVERIFIED (b-burst README, "Open prerequisites" 1); if nothing is pending, nothing scales out and P15-11 applies.
3. Experiment A cost ceiling for the experiment record: 0.75 (the manifest `SPEND_CEILING_USD`); the human confirms, as the B README (N5) asked for 2.40.
4. `--spark-dir`, `--max-objects 120` and `--max-bytes 1500000000` for the upload are unverified against what Phase 14 used (2.0.2). The snapshot id `phase5-dblp-subset` is resolved (`snapshot-s.json` lines 2 and 51). The A `IdleTimeout` 900 is resolved (`course.tfvars` line 31).
5. UNVERIFIED, by nature of not having run: `aws emr terminate-clusters` (S4; the repository uses `terminate-job-flows`); the flag forms of the `ec2`, `elbv2`, `ecs` and `neptune` read calls in 4.4; the `aws s3 ls --recursive` column layout (2.0.2c); macOS `date -v`; the `run-record.env` key names the greps select; whether managed scaling scales in within the 900 s Wait (`elasticity-s.tfvars` comment); EMR's CloudWatch metric names other than `ContainerPendingRatio` (b-burst README); whether the plan can render A's definition before the apply (2.3); whether credentials outlast a 75 to 90 minute run.
6. Billing lag and the Phase 14 arithmetic gap (0.1 above): the human decides.
7. Pending gates (separate from validation evidence below): the P15-10 gate (0.1); the 0.2c gate; plan review and the human approvals (every W command); the rendered-definition checks (2.3, 3.3, and the live checks 2.5, 3.5); the confirmation of the B profile values (2); a fresh `sbt quick` after Wave 2 stops writing (review 2, pending gate 5; the stash pop of 4.5 restores the Wave 2 files, so it belongs after 4.5).
8. Script and documentation gaps found while writing (none blocks the runbook). There is no BLOCKED-ON infra-engineer script gap: every step maps to a flag read in the scripts. For owners, through the orchestrator: (a) `experiments/a-baseline/README.md` has no command to fetch the A run's on-node reports from S3 (2.8b is mine, UNVERIFIED), and does not name `b-burst/collect.sh` for A (2.9b); (b) the committed manifests need a copy with a real account id and output root before `run-experiment.sh` can run them, and no script makes it (1.5 uses `sed`); (c) the B README's redaction list omits `run-record.env`, `entrypoint.log` and `metrics/` (3.11 copies the whole run directory); (d) the deploy script applies before it pushes images (Phase 14 README, open items), unchanged here; (e) the upload script never deletes stale jars (2.0.2c only detects them; removal needs a human-approved path).

## Validation evidence, separate from the gates above

Nothing in this runbook was executed by me. Evidence that exists, read from the files (not re-verified): Wave 1 harness `RESULT pass=1659 fail=0` (run 5, after R12 and R13); A rehearsal `pass=49`, B rehearsal `pass=59`, collector rehearsal `pass=25`, manifest JSON check `OK manifests=6` (`wave-1/checks.md`); EC2 quota 32 vCPU and Fargate quota 30 vCPU (`wave-1/doc-verification.md`); `git check-ignore` rc 0 for the chunk file (review 2 gate 2); code-reviewer review 2 APPROVE for Wave 1 (`wave-1/review-2-verdict.md`); the runbook review 1 verdict "Verified correct" list (flags, `ARGS_CANONICAL` strings, approval keys, limits, `ComputeLimits`). No script in this runbook has run against AWS since the Phase 15 edits; every real AWS call, every `--print-digest` and every `--dry-run` listed here is unrun.

## Next gate

Independent read-only re-review of this revision by the code-reviewer through the orchestrator (no execution authority). Then the human runs it, in order, and pastes the evidence of 5.2. The deploy-engineer writes the release records only after the evidence exists and 4.5 is done.
