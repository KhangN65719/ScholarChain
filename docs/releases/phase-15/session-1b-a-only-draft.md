# Phase 15 session 1b: A-only session (tomorrow morning, P15-22). DRAFT, nothing executed

Author: deploy-engineer. Status: DRAFT for review. It is committed in tonight's docs-only commit (0.2a of `session-1-runbook.md`) and is not edited again until after tonight's 4.5. I ran nothing: no AWS call, no Terraform, no script, no git write, no sbt. Nothing here is an approval.

Decision P15-22 (`docs/agent-runs/phase-15/decisions.md`): tonight is B only; A re-runs in a short separate session the next morning. Decision P15-21 (same file) confirmed the `b-burst` values; the 0.1b precondition needs no new confirmation (the `a-baseline` profile lines in `scripts/emr/spark-yarn.conf` are in the same committed file tonight uploads).

## How to use this file

The command blocks are the ones in `docs/releases/phase-15/session-1-runbook.md` (the "runbook"), under the step ids named below, with two kinds of change: (a) the limits and gate numbers of this file replace the ones in the runbook (its S3 clock of 4 h and 1 h 30 min is B's; its A steps, marked "NOT TONIGHT", are tomorrow's); (b) the name substitutions below, so that tomorrow's logs, plans and records do not collide with tonight's. Records and `tee` logs created with noclobber or by name would clash with tonight's files otherwise.

Name substitutions, applied mechanically to every command copied from the runbook:

| In the runbook | In tomorrow's command |
|---|---|
| every `phase15-s1-` (log names) | `phase15-s1b-` |
| `plans/p15-s1-down` | `plans/p15-s1b-down` |
| `approvals/p15-s1-down-deploy.env` | `approvals/p15-s1b-down-deploy.env` |
| `state-backup-p15-s1` (0.2a2) | `state-backup-p15-s1b` |
| `p15-s1-a-up`, `p15-s1-a-*` (plans, approvals, run names) | unchanged: tonight creates none of them except the A manifest copy and the A input from 1.5, which tomorrow uses as they are |
| `${SCD}/runs/p15-s1-a`, `${SCD}/derived/p15-s1-a-fields`, `${SCD}/results-copy/p15-s1-a` | unchanged |

Session rules S1 (no repository writes and no agent sbt from the end of 0.2c until 4.5), S2 (every stop from the session-up apply is "go to the session-down unless the human decides otherwise within 15 minutes"), S4 (E1 emergency lookup and terminate, exactly one cluster) and S5 (no persistent permission prompts) of the runbook apply unchanged. S3 is replaced by the limits below. The state and gate commands that print `S3_CLOCK_START_UTC` do so at 2.4R in this session.

## A-only HIGH and wall-clock limit

| Item | USD (HIGH) | Arithmetic |
|---|---|---|
| A entry-point window | 0.75 | manifest `SPEND_CEILING_USD=0.75`; 0.60 USD/h x 75 min / 60 = 0.75 (`experiments/manifests/experiment-a-baseline.manifest`) |
| Cluster billing after A's 70-minute stop | 0.05 | one primary node at 0.60 USD/h for up to 300 s of teardown check (`run-bounded-pipeline.sh` lines 482 to 491): 0.60 x 300 / 3600 = 0.05 |
| Idle endpoints outside the A window | 0.27 | 8 interface endpoints x 0.015 USD/h = 0.12 USD/h (UNVERIFIED rate), for up to 2.25 h = the 3.5 h limit minus the 1.25 h A window (the burn constant already holds its endpoints) = 0.27 |
| **A-only session HIGH** | **1.07** | 0.75 + 0.05 + 0.27 |

Wall-clock limit: 3.5 hours from the start of 2.4R (the session-up apply). Arithmetic: apply and image push about 20 min, live check, records and dry runs about 30 min, A run at most 75 min, session-down plan, records and apply about 45 min = 170 min = 2.8 h, so 3.5 h leaves about 40 min of slack. A (2.7) must start no later than 1 hour after the 2.4R start (1 h + 1.25 h + 0.75 h = 3.0 h, inside 3.5 h). The session-down apply must start no later than 3.5 hours after the 2.4R start. Each hour beyond costs about 0.12 USD more.

P15-10 for A only: billed spend plus 1.07 must be at most 14 USD, so billed must be at most 12.93. The billed figure after tonight is not known to me: the human reads Billing again in the morning (billing can lag by up to about a day, UNVERIFIED), and if tonight's B session billed more than expected, the arithmetic is redone.

0.1 gate command (A-only constants 1.07 and the plan's 0.75):

```
set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs; BILLED_USD=REPLACE_WITH_BILLED_TOTAL; if [[ "${BILLED_USD}" =~ ^[0-9]+(\.[0-9]+)?$ ]]; then awk -v b="${BILLED_USD}" 'BEGIN { h1 = 1.07; h0 = 0.75; printf "P15-10 billed=%.2f limit=14.00\n  session1b_high=%.2f total=%.2f -> %s\n  plan_high=%.2f total=%.2f -> %s\n", b, h1, b + h1, (b + h1 <= 14 ? "GO" : "STOP"), h0, b + h0, (b + h0 <= 14 ? "GO" : "STOP"); exit (b + h1 <= 14 ? 0 : 3) }'; else echo "BILLED_USD is not a plain number: STOP"; false; fi 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-s1b-billing-gate.log | tail -n 60; echo "exit: $?"
```

Expected: `-> GO` and `exit: 0`. Stop: `STOP`, `exit: 3`, or a non-number.

## Order for tomorrow (A only)

| # | Runbook step id | What changes for tomorrow |
|---|---|---|
| 1 | 0.1 | the command above |
| 2 | 0.1b | run the risk-check command only (`.claude/settings.local.json` ignored and untracked; `course.tfvars` diff for information). The `b-burst` confirmation is P15-21 |
| 3 | 0.2a, 0.2a2, 0.2b, 0.2c | see "The commit pin" below |
| 4 | 1.1, 1.3, 1.4 | as written, with the substitutions. 1.2 is cited (quotas unchanged: EC2 32 vCPU, Fargate 30 vCPU) |
| 5 | 1.5 | not run: tonight's 1.5 made the A manifest copy and the A input. Confirm they exist with the check below. If one is missing, stop and tell the orchestrator (1.5 uses noclobber and would also try to recreate the B files) |
| 6 | 1.6A | the A digests, with the substitutions |
| 7 | 2.0.1 always; 2.0.2 to 2.0.5 only if the check below says the code changed | see "Is 2.0 needed" below |
| 8 | 2.1, 2.2, 2.3 | A plan from the down state to `baseline-s`; expected plan as written in 2.1 (about 11 to add: 8 endpoints and 3 task definitions; the state machine and two services updated in place; `STATEFUL_CHANGE=no`; no Neptune, no Bedrock endpoint) |
| 9 | 2.4P, 2.4W, 2.4D, 2.4R | deploy. 2.4R starts the session clock (3.5 h limit above). Tomorrow's HEAD may differ from tonight's (tonight's evidence commit), so the images are built and pushed again from the contexts of 2.0.1; Docker must be running |
| 10 | 2.5 | live definition check (column A of "Rendered values to expect") |
| 11 | 2.6 (1.3 again, W, D) | A pipeline and experiment records, dry runs; cost ceiling 0.75 (open item for the human to confirm) |
| 12 | 2.7 | the A run: `MAX_MINUTES` 75, pipeline stop 70 min, spend ceiling 0.75, burn 0.60 |
| 13 | 2.8a, 2.8b, 2.9, 2.9b (optional) | results and derive (read-only; may follow the session-down) |
| 14 | 3.11, A block only | redaction copy of A |
| 15 | 4.1, 4.2, 4.3P, 4.3W, 4.3D, 4.3R, 4.4 | session-down, with the substitutions (`p15-s1b-down`) |
| 16 | 4.5 | stash pop (if the stash of step 3 was used) |
| 17 | 5.1, 5.2, 5.3 | evidence; the rows for A |

Skipped tomorrow: 1.5, 1.6B, 2.10 (there is no B decision; go to the session-down), 3.1 to 3.10 and the B block of 3.11.

### The commit pin (step 3)

The pin is the same as tonight's: the tree clean, branch `main`, no difference outside `docs/` from 085b5ec. Two cases.
- Wave 2 is still uncommitted (expected): run 0.2a only if there are docs changes not yet committed (the human's after-4.5 evidence commit of tonight is a docs commit; `git status --short` shows the rest), then 0.2a2, 0.2b (stash) and 0.2c exactly as in the runbook, with the substitutions. The stash name `p15-wave2-wip` is reused; tonight's stash was popped at 4.5, so there is no clash (`git stash list` must show nothing named `p15-wave2-wip` before 0.2b: if it does, tonight's 4.5 did not finish, stop).
- Wave 2 has been committed by then: 0.2c fails (a difference outside `docs/`), correctly. Do not run A on a Wave 2 commit: the on-node scripts and jars in S3 (tonight's upload) and the Terraform would no longer match what was approved. Stop; the human decides (for example waiting for the Wave 2 review, or a new pin with a new upload and a new runbook).

### Is 2.0 needed (step 7)

2.0.1 (`package.sh`) always runs tomorrow, after the stash, because `target/docker-local` is ignored build output that Wave 2 work after tonight (any `sbt dockerLocal`) may have replaced; `deploy.sh` must digest and build the images from 085b5ec's code. 2.0.2 to 2.0.5 (upload and its checks) are needed only if the code changed since tonight's upload. This check compares the commit tonight's 0.2c recorded with today's HEAD outside `docs/`, shows that the A inputs exist, and confirms that S3 holds the committed on-node scripts (2.0.5 text, run read-only with the substitution). Run the first command now (read-only):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; T="$(awk -F= '$1 == "HEAD" { print $2; exit }' ${SCD}/logs/phase15-s1-tree-head.log)"; ( echo "TONIGHT_HEAD=${T}"; echo "TODAY_HEAD=$(git rev-parse HEAD)"; git diff --quiet "${T}" HEAD -- . ':(exclude)docs'; D=$?; echo "CODE_DIFF_SINCE_TONIGHT_EXIT=${D} (0 = no difference outside docs/)"; ls -l ${SCD}/experiments/manifests/p15-s1-a-baseline.manifest ${SCD}/pipeline-inputs/p15-s1-a.json; [[ -n "${T}" && "${D}" == "0" && -f ${SCD}/experiments/manifests/p15-s1-a-baseline.manifest && -f ${SCD}/pipeline-inputs/p15-s1-a.json ]] ) 2>&1 | tee ${SCD}/logs/phase15-s1b-code-since-tonight.log | tail -n 60; echo "exit: $?"
```

Expected: `CODE_DIFF_SINCE_TONIGHT_EXIT=0`, both A files listed, `exit: 0`. That reads tonight's `phase15-s1-tree-head.log`, which tonight's 0.2c wrote; tomorrow's 0.2c writes `phase15-s1b-tree-head.log` (substitution), so the tonight log is not overwritten. If the exit is non-zero for the code difference, run 2.0.2 to 2.0.5 in full (with the substitutions and a new upload record), including 2.0.2b and 2.0.2c. If an A file is missing, stop. Then run the runbook's 2.0.5 (bootstrap check, with the substitution): four `MATCH` lines are required even when 2.0.2 to 2.0.4 are skipped; any `DIFFERENT` means the upload must be redone.

## Limits and stops (A only)

- A (2.7) starts no later than 1 h after the 2.4R start; the session-down apply starts no later than 3.5 h after it. Past either limit, go to the session-down.
- Every stop from 2.4R on: report, then session-down unless the human decides otherwise within 15 minutes (S2). A failed A run is kept and reported, never deleted or edited.
- A leftover cluster: S4 (E1 lookup, then E1 terminate only when exactly one cluster is active).
- Rollback gate: the session-down plan (4.1 to 4.3). Nothing stateful is touched.

## Expected cost per step (A only; my arithmetic, UNVERIFIED rates, not a bill)

| Step | Expected | HIGH |
|---|---|---|
| 0, 1, 2.0 | 0 | 0 |
| 2.1 to 2.5 plan and deploy (about 20 min) | 0.02 | 0.04 |
| 2.6 records and dry runs (about 30 min) | 0.03 | 0.06 |
| 2.7 A run (about 58 to 70 min; 0.60 USD/h) | 0.55 to 0.70 | 0.75 |
| teardown check after a stop | 0 | 0.05 |
| 4.x session-down (about 45 min) | 0.05 | 0.09 |
| wall-clock reserve to 3.5 h | 0 | about 0.08 |
| **Total** | **about 0.65 to 0.80** | **1.07** |

## Open items

1. Billing figure after tonight (the human reads it in the morning).
2. The A experiment-record cost ceiling 0.75 is the manifest `SPEND_CEILING_USD`; the human confirms.
3. If Wave 2 is committed before tomorrow, step 3 stops (see "The commit pin").
4. UNVERIFIED, as in the runbook: the AWS CLI flag forms of the 4.4 read calls, `aws emr terminate-clusters` (S4), macOS `date -v`, the key names the result greps select, whether `plan.sh` can render A's definition before the apply (2.3 falls back to 2.5), and whether credentials outlast the 75-minute run.
5. The name substitutions are mechanical and unrun; a mistake in a record or plan name would produce a noclobber refusal, but a missed `phase15-s1-` log name would overwrite tonight's log of that name (`tee` truncates), so copy tonight's logs somewhere safe after 4.5 before tomorrow. The orchestrator may materialize the substituted commands in a copy outside `docs/releases/` if the human prefers paste-ready text.
