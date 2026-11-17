# Phase 15 session 2 tonight, from the down state (revision 4, nothing executed)

Author: deploy-engineer. Status: FINAL (revision 4, review 5 APPROVE) (revision 3 was FINAL, review 3 APPROVE, n1-n3 applied). Revision 4 applies P15-42: B already ran this afternoon as its own session (`session-ab/session-b-now-record.md`). Part B is removed (its generic deploy commands survive as DP, DW, DD, DR, DC); Section P (the B-only leftovers) is new and comes first in the evening; session 2 starts from the DOWN state at the evening commit's HEAD; the billing gate has one HIGH and a human-set LAG; the clock is recomputed without B; variant (b) of the teardown is removed. A1's plan counts are recomputed from the down state by infra-engineer (`SESSION2.md` 5b, relayed by the coordinator): `19 to add, 6 to change, 3 to destroy`, IAM unchanged, `STATEFUL_CHANGE=no`; A2, A3, A4 and the early-stop variant (a) are unchanged. Revision 2 applied `docs/agent-runs/phase-15/session-ab/tonight-review-1-verdict.md` (4 BLOCKER, 5 MAJOR, 9 MINOR) in one round, using `tonight-infra-report.md` (`SESSION2.md` section 5b), `tonight-experiment-report.md`, `load-tests/query-load/README.md` "Smoke", `experiments/e-retrieval-modes/run.sh`, and the approved fallback `session-b-only-runbook.md` (its allowlist, cents gate, fail-closed git checks, re-plan out-dir rule and B-part commands are copied here with `tn` names, not "renamed while pasting").
Basis: decisions P15-1 to P15-42, `session-ab/session-record.md`, `session-ab/session-b-now-record.md`, `readiness-review-verdict.md`, `readiness-repair-infra-report.md`, `readiness-rag-engineer-report.md`, `infra/terraform/SESSION2.md`, `infra/terraform/COST_ESTIMATE.md`, `experiments/pipeline-inputs/README.md`, the six manifests and the D, E, F, C READMEs.

I ran nothing: no AWS, terraform, sbt, script or git write. Every command is a `!` paste for the human. Nothing here is an approval: the human approves by running the W command of each action after reading the plan or arguments it names. A statement read from a file is cited; one no run has confirmed is UNVERIFIED; a gap owned by someone else is PENDING with the owner. Profile `scholarchain`, us-east-2 only, account read from STS and masked. Never use "Activate advanced features", never change the spend limit.

## 0. Open decisions and acknowledgements (0.0, in writing in the chat, before 0.1)

| # | Item | Decision line the human types (placeholder = OPEN) |
|---|---|---|
| D1 | M1 vector provenance. A had no loader and graph-only skips ingest, so S3 Vectors hold an earlier run's vectors while Neptune and the read models are A's (`run:v1:3cfb57b1-abd1-4913-8e53-da477d8d6027`). The runtime filters vector hits by neither run nor label (`Vectors.scala:291-300`); fused degrades silently to graph-only on a vector read failure (`Retrieval.scala:258-263`). Options: (1) RECOMMENDED: E in graph mode (351 requests) plus a separate, labeled mixed-run smoke (36 requests, record the earlier `ingest-report.json` `sourceRunId` beside A's, check `textRef` run segments); (2) accept and label mixed runs: E runs all three modes (1,053 requests); (3) re-ingest (not recommended: `ChangedVectorContent`, old runId kept, `EmbeddingIngest.scala:262-296`). `experiments/e-retrieval-modes/run.sh:30-46` now reads `SCHOLARCHAIN_E_MODES` and `SCHOLARCHAIN_E_LIMIT_QUERIES` (1 to 351, no leading zero), so both are realisable; S.9 has the commands. In every option E records whether a gap note appeared: `load_client.py` raw lines carry `vector_gap_note` (true only on a real vector read failure) and `uncertainty_chars`; there is no service-log key. | `M1 DECISION: REPLACE_WITH_1_OR_2` (OPEN; 3 is outside tonight) |
| D2 | P15-36 amended reading. Tonight's tree is not "docs/ only". The commit carries `docs/` plus ten exact paths (the fallback's reviewed list): `infra/terraform/SESSION2.md`; `infra/terraform/environments/course/services-session.tfvars`, `experiment-size-s.tfvars`, `variables.tf` (comments and descriptions); `experiments/pipeline-inputs/s-graph-only-reload.template.json`, `experiments/pipeline-inputs/README.md`; `tests/scripts/run.sh`; `load-tests/query-load/load_client.py`, `load-tests/query-load/README.md`; `experiments/e-retrieval-modes/run.sh`. Nothing under `modules/`, `scripts/`, `build.sbt`, `project/`, `packaging/`; any other path or a git error fails closed. The B-now record `docs/agent-runs/phase-15/session-ab/session-b-now-record.md` is under `docs/`, so the `^docs/` pattern of the E.7, E.8a and G allowlists already accepts it; no pattern changes in revision 4. | `P15-36 AMENDED READING ACCEPTED: docs/ plus the ten paths` (OPEN) |
| D3 | LAG: today's usage not yet in Billing (section 2). The A + B session about 1.10, plus the B-only session about 2.27 (2.09 estimated run, `session-b-now-record.md`, plus about 1.5 h of the 8 interface endpoints at 0.12 USD/h for all eight, 0.18; the rate is UNVERIFIED), so 3.37. LAG replaces the OD-7 allowance 1.15 and the `AB_USD` choice of revision 3. This typed line is the decision record of tonight's amended gate: P15-42 records only that B ran, and P15-40 still reads "both HIGHs". Section 2 does not run until it is typed. | `P15-40 GATE FOR TONIGHT ACCEPTED: billed + LAG + HIGH(session 2) 4.50 <= 14, LAG = <value in USD, two decimals, 0.00 to 4.00>` (OPEN, default value 3.37; typed at 0.0, before section 2) |
| D4 | Hard stop, literal reading (default): the session-down starts by 01:15 local whatever the state, that is the A4 PLAN command is run by 01:15 and the A4 apply follows as soon as its read passes. The start rule C3 (section 1) keeps every experiment's cap plus hold before 00:55, so nothing runs at 01:15; if something does, the human decides in the chat (no apply during an experiment, `SESSION2.md` section 10 item 6). | `HARD STOP READING ACCEPTED` or a stated alternative (OPEN) |
| D5 | IAM privilege approvals, typed at each plan (`docs/agents.md` "Security, IAM, secrets"): A1 two in-place policy updates AND two creates (`aws_iam_role.bulk_load`, trust `rds.amazonaws.com`, and its policy, `modules/neptune/main.tf:58-67`; recomputed from the down state by infra-engineer, `SESSION2.md` 5b: IAM unchanged from revision 3); A2 two creates (the worker role and its policy); A3 none; A4 three narrowings AND two destroys (the bulk-load role and policy); the early-stop variant (a) (A1 applied, A2 never reached) two narrowings AND the same two destroys, with its own typed line `IAM DIFF APPROVED tn-s4a`. | typed at S.1, S.4, H.1 |
| D6 | Haiku smoke path: the committed command exists (`load-tests/query-load/README.md` "Smoke", S.6): one `ecs run-task`, graph mode, `--limit-queries 4`, caps 1 client, 4 requests, 5 minutes. "4 queries" is not "4 model calls": the planner and synthesis both call Haiku and retries add calls, and `bedrock_max_calls = 10` is per process (P15-27). UNVERIFIED until run: that Haiku is called in graph mode. | `SMOKE PATH ACCEPTED` (OPEN) |
| D7 | P15-30 and P15-38 acknowledgement. | `P15-30 acknowledged: OD-1, OD-7, OD-8; OD-3 as ratified; P15-38 acknowledged: path-evidence 42 rows is a stated limitation` |
| D8 | Agent `sbt check` before the evening commit. The commit changes code files (`tests/scripts/run.sh`, `load_client.py`, E `run.sh`, tfvars comments, `variables.tf` descriptions; no Scala). CLAUDE.md: a follow-up commit that changes code needs a passing agent `sbt check` (about 33 minutes); a docs-only one needs none. Either an agent `sbt check` before the commit, or an explicit human ruling that this commit needs none. | `D8 DECISION: REPLACE_WITH_SBT_CHECK_OR_HUMAN_RULING` (OPEN). If sbt check: Section E slot E.7b runs before E.8 |

## 1. Clock and hard stop

Local is UTC-6 today. Evidence: `session-record.md` T0 `2026-11-17T16:13:20Z` (2.4R); the human's playground call 10:55 local with the marketplace agreement at 16:52Z the same morning, consistent with 16:13:20Z = 10:13 local. E.1 prints the machine offset.

| Local | UTC |
|---|---|
| 01:15 on 2026-11-18, hard stop (D4: A4 plan starts) | 07:15Z on 2026-11-18 |
| 00:55, latest end of any experiment cap plus hold (C3) | 06:55Z |

If E.1 does not print `OFFSET=-0600`, recompute every UTC value here.

Durations (my arithmetic, UNVERIFIED, from the AB timeline, `COST_ESTIMATE.md:126-127`, `pipeline-inputs/README.md:24`; no package is needed because the images are built by `deploy.sh` from the AB `target/docker-local`; Section P is read-only and outside the window):

| Segment | Expected | Cap |
|---|---|---|
| A1: plan, read, records, apply (Neptune create about 12 min) | 40 min | |
| Graph-only reload (run 11: 4.7 min for 11 tables) | 10 min | 100 min |
| A2: plan, read, records, apply, downsize, export load (grace 300 s), load-client push | 45 min | |
| Smoke | 15 min | 5 min client cap |
| A3 (answer model to stub, task roll, export reload) | 20 min | |
| D | 75 min | 70 min entry cap + 25 min scale-in hold after the client ends = 95 min planning budget (the one hold value, also in S.9). Information only: the expected 75 min is 47 min schedule + 25 min hold + about 3 min setup (80 min with margin) |
| E graph (351) plus mixed smoke (36) | 15 min | 60 min each |
| F | 25 min | 30 min |
| C | 65 min | 60 min + 10 min scale-in |

Not everything fits between a normal start and 01:15; P15-39 keeps finished experiments and records the rest as not run. Rules:

- C1. No B tonight (P15-42): session 2 starts from the DOWN state at the evening commit's HEAD. Latest A1 plan start, counted back from 00:55 (the end of the last cap, C3): if D is wanted (A1 to smoke 110 + A3 20 + D 95 = 225 min) 21:10 local; smoke only (110 min) 23:05 local. Experiments end by 00:55; the A4 plan starts by 01:15 (D4). A later A1 start: the human decides in the chat which of smoke, D, E, F, C are worth running; the rest are recorded NOT RUN under C3.
- C2. The A1 plan starts only if `now + 110 min <= 00:55` for the smoke (23:05 local), and `now + 225 min <= 00:55` if D is wanted (21:10 local). S.0 tests the clock against C2.
- C3. Each of smoke, D, E, F, C starts only if `now + cap + hold <= 00:55`; otherwise it is recorded NOT RUN and the next one is tested independently (P15-10 order smoke, D, E, F, C is a priority order, not a chain).
- C4. Every collector runs before A4 (scaling activities may not be readable after the scalable targets are deregistered, UNVERIFIED). Derive scripts need no AWS and may wait.
- C5. Nothing is interrupted by hand: the script caps end runs.

## 2. Billing gate (P15-40 as amended by the human's D3 line tonight; B done, P15-42)

Rule: billed reading + LAG + HIGH(session 2) at most 14 USD; 14 is never raised. Compared in integer cents. B is done (P15-42), so there is one HIGH (revision 3 also counted HIGH(B part) 2.93). P15-40 itself still reads "both HIGHs" and P15-42 records only that B ran: the one-HIGH formula with LAG is the human's decision of tonight, recorded by the D3 typed line (`P15-40 GATE FOR TONIGHT ACCEPTED: billed + LAG + HIGH(session 2) 4.50 <= 14, LAG = <value>`).

This section refuses to run until D3 is typed: the command below stops unless `D3_TYPED=yes` is set by hand after the line is typed, and its `LAG_USD` must equal the typed value.

LAG is today's usage that Billing does not show yet, one number the human sets (D3). Default 3.37 = the A + B session about 1.10 + the B-only session about 2.27 (2.09 estimated run, `session-b-now-record.md`, plus about 1.5 h of the 8 interface endpoints at 0.12 USD/h, 0.18; the rate is UNVERIFIED). Accepted values 0 to 4.00 in cents; anything else is a STOP. It replaces the OD-7 allowance 1.15 and `AB_USD`; nothing else is added.
HIGH(session 2) = 4.50: `COST_ESTIMATE.md:133,135` (A1 1.67 `:126`, services time 1.58 `:127`, extras 0.17 `:120`, smoke 0.72 `:129`, Titan 0.06 `:130`, S3 Vectors 0.17 `:131`, logs 0.10 `:132`).
Sum: billed + LAG must be at most 9.50 (14 minus 4.50); with LAG 3.37 a reading of at most 6.13.

Which rule governs: `COST_ESTIMATE.md:135` says billed at most 9.5 at the start of session 2 (14 minus 4.5) for a stand-alone session 2. With B done and LAG counted, the amended gate is the same test (effective at most 9.50); P15-40 as amended by the human's D3 line tonight (B done, P15-42) governs and S.0 re-runs it.

Drop list (hours at `COST_ESTIMATE.md:127` times the 0.3677 steady HIGH `:118`, plus extras `:120`, `:130`, `:131`; my arithmetic): C 0.45 (0.294 + 0.030 + Titan 0.06 + PUTs 0.067); F 0.18; E 0.16; D 0.68 (0.552 + 0.076 + 0.049); smoke 1.09 (0.368 + Haiku 0.72 for 10 queries). Drop C first, then F, E, D; the smoke goes last and with D and E (they need it). The command below prints this drop list (C, F, E, D, cumulative) whenever the total is over 14.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs; D3_TYPED=no; BILLED_USD=REPLACE_WITH_BILLED_USD; LAG_USD=3.37; DROP=""; if [[ "${D3_TYPED}" == "yes" ]] && [[ "${BILLED_USD}" =~ ^[0-9]+(\.[0-9]{1,2})?$ ]] && [[ "${LAG_USD}" =~ ^[0-9]+(\.[0-9]{1,2})?$ ]]; then awk -v b="${BILLED_USD}" -v l="${LAG_USD}" -v d="${DROP}" 'BEGIN { bc = int(b * 100 + 0.5); lc = int(l * 100 + 0.5); if (lc > 400) { print "LAG_USD above 4.00: STOP"; exit 4 } split("C F E D", nm, " "); split("45 18 16 68", vl, " "); used = 450; for (i = 1; i <= 4; i++) if (index("," d ",", "," nm[i] ",") > 0) used -= vl[i]; e = bc + lc; t = e + used; printf "billed=%.2f lag=%.2f effective=%.2f limit=14.00\n  high_s2=%.2f (after drops %s) total=%.2f -> %s\n", bc / 100, lc / 100, e / 100, used / 100, d, t / 100, (t <= 1400 ? "GO" : "STOP"); if (t > 1400) { c = 0; for (i = 1; i <= 4; i++) { c += vl[i]; printf "  drop through %s: saves %.2f total %.2f -> %s\n", nm[i], c / 100, (e + 450 - c) / 100, (e + 450 - c <= 1400 ? "GO" : "STOP") } } exit (t <= 1400 ? 0 : 3) }'; else echo "D3 line not typed (D3_TYPED is not yes), or BILLED_USD or LAG_USD is not a plain number with at most two decimals: STOP"; false; fi 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-billing-gate-$(date -u +%H%M%S).log | tail -n 60; echo "exit: $?"
```

Worked examples (default `LAG_USD=3.37`, my arithmetic): reading 5.33 gives effective 8.70 and total 13.20, GO. Reading 6.13 gives 14.00, GO (at most 14). Reading 6.50 gives effective 9.87, total 14.37, STOP; dropping C gives 13.92, GO. Reading 7.00 gives effective 10.37, total 14.87, STOP; dropping C gives 14.42, C and F 14.24, C, F and E 14.08, all four 13.40 (GO only after all four). Reading 8.00 gives 15.87 and 14.40 after all four drops: STOP, and the human decides whether the smoke stays. `exit: 3` (or 4 for a LAG above 4.00) or STOP: nothing that bills runs; set `DROP="C"`, `"C,F"` and so on and rerun until GO. The log name carries the time, so reruns keep their readings.

## 3. Conventions

- Each command is self-contained. `set -o pipefail;` first, `tee` to `/Users/winnguyen/scholarchain-data/logs/phase15-tn-<step>.log`, ending `| tail -n 60; echo "exit: $?"`. Braced zsh variables. No `!` inside double quotes. Account never typed: `ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"`; output that can carry it passes `sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g"` after the `tee`. A missing verdict line is a stop, not a pass.
- `SCD=/Users/winnguyen/scholarchain-data`, `ENVDIR=/Users/winnguyen/ScholarChain/infra/terraform/environments/course`. Files `p15-tn-*`, logs `phase15-tn-*`.
- Parametrised commands start with assignments (`NAME=...`); set only those. `NAME` per apply: `tn-s1` (A1), `tn-s2` (A2), `tn-s3` (A3), `tn-s4` (A4). Plans in `${SCD}/plans/p15-${NAME}`, logs `phase15-${NAME}-*`, records `${SCD}/approvals/p15-${NAME}-*.env`.
- Re-plan rule (as the fallback 2.1 and 4.1): a repeated plan uses a NEW name with a `-2` suffix (`NAME=tn-s1-2`) so its out-dir holds one `course-*.tfplan`; the new `NAME` replaces the old in every later command that reads that plan; no record is written from the old plan; the old plan log is kept by the new name.
- Approval record: the 14-key `KEY=VALUE` file of `session-ab-runbook-draft.md` ("Approval record, exact fields"): `COST_CEILING_USD` equals `--cost-ceiling-usd` (deploy and pipeline 20; experiment: D 0.65, E 0.45, F 0.25, C 0.60); `PLAN_DIGEST` only for deploy; `GIT_SHA` HEAD for deploy and experiment, `n/a` otherwise; `STATEFUL_CHANGE_ACKNOWLEDGED=no` unless `plan.sh` printed `STATEFUL_CHANGE=yes` (expected only at A4); expiry 8 h. Single use; a script edit voids it; a dry run consumes nothing. The orchestrator records each as a ledger row afterwards (H.6).
- UNVERIFIED throughout: any AWS CLI flag form not run in the A + B session; `plan.sh --out-dir` creating its directory (the AB runs used it unchanged and worked).

## 4. Session rules (from E.9 until H.4)

- S1. No repository write and no agent sbt after E.9 (D8's agent `sbt check`, if chosen, runs before E.8). The only sbt in the session window is none: the heap measure runs in the evening. Evidence goes to `${SCD}/logs/` and the chat; the orchestrator writes the repository only after H.4 (P15-31).
- S2. Any stop after the A1 apply starts (DR with `NAME=tn-s1`) means "go to H" unless the human decides otherwise within 15 minutes in writing with an end time. A failed S.2 (reload) cancels smoke, D and E (they need the graph) but not F and C; the human decides in the chat. If A1 never applies, nothing is up: there is no session-down; run only the post-teardown read (H.4).
- S3. Emergency action (leftover EMR cluster): E1 lookup and E1 terminate below, recorded as a human emergency action (log, UTC time, cluster id, reason). Console mutation is prohibited.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; AWS_PROFILE=scholarchain aws emr list-clusters --active --region us-east-2 --query "Clusters[?Name=='scholarchain-course-pipeline'].[Id,Status.State]" --output text 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-emergency-lookup-${STAMP}.log | tail -n 60; echo "exit: $?"
```

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; IDS="$(AWS_PROFILE=scholarchain aws emr list-clusters --active --region us-east-2 --query "Clusters[?Name=='scholarchain-course-pipeline'].Id" --output text)"; N="$(printf '%s' "${IDS}" | wc -w | tr -d ' ')"; if [[ "${N}" == "1" && "${IDS}" =~ ^j-[A-Z0-9]+$ ]]; then AWS_PROFILE=scholarchain aws emr terminate-clusters --cluster-ids "${IDS}" --region us-east-2; echo "terminate requested for ${IDS}"; else echo "found ${N} active cluster(s): nothing terminated"; false; fi 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-emergency-terminate-${STAMP}.log | tail -n 60; echo "exit: $?"
```

UNVERIFIED: `terminate-clusters --cluster-ids` is the verb of the AB runbook (unrun); the repository scripts use `terminate-job-flows --job-flow-ids`; swap if rejected. Afterwards the lookup must print nothing.
- S4. No persistent permission prompt writes `.claude/settings.local.json` (ignored: `.git/info/exclude:8`).
- S5. No apply while an experiment or load run is in progress.

---

## Section P. B-only leftovers (first in the evening, before E; read-only; P15-42)

B ran this afternoon as its own session at HEAD `5c69ba9` (`session-ab/session-b-now-record.md`): run `exp-B-20261117T185928Z-5c69ba90`, `final=COMPLETED`, `elapsed_s=4858`, `estimated_spend_usd=2.09`, cluster `j-0091484IPP1V1PIP0UK`, down apply `DEPLOY_COMPLETE` (plan `dc000094...`). Scale-out to 3 core nodes and scale-in to 1 were seen in the human's polls. Open there: the post-teardown read, the collector and the derive. Section P applies nothing: no plan, record or approval, AWS describe and list calls and local files only; outputs go to `${SCD}/logs/` and the chat. Nothing of B reruns (P15-11: the derive result is the result; D's ECS scaling is the second source).

### P.1 Post-teardown read (short form: DLQs, EMR, Step Functions, EC2 and endpoints; one command with a verdict)

One command, also "the P.1 state command" reused before S.2, F and C. Each call's output (stderr included) is captured and tested: a DLQ pair must read exactly `0 0`, the EMR, Step Functions and EC2 sections must be empty, and every endpoint row must be a `Gateway` row for S3 or DynamoDB. Any error text in a section makes it non-matching, so it fails the verdict. The screen output passes through the account mask. The DLQ query form (`Attributes.[...]`) is new and UNVERIFIED.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; BAD=""; echo "--utc ${STAMP}"; for Q in run-requests embedding-work; do V="$(aws sqs get-queue-attributes --queue-url "$(aws sqs get-queue-url --queue-name scholarchain-course-${Q}-dlq --region us-east-2 --query QueueUrl --output text)" --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible --region us-east-2 --query 'Attributes.[ApproximateNumberOfMessages,ApproximateNumberOfMessagesNotVisible]' --output text 2>&1)"; echo "--dlq-${Q} (expect 0 0): ${V}"; if [[ ! "${V}" =~ ^0[[:space:]]+0$ ]]; then BAD="${BAD} dlq-${Q}"; fi; done; E="$(aws emr list-clusters --active --region us-east-2 --query 'Clusters[].[Id,Name,Status.State]' --output text 2>&1)"; echo "--emr-active (expect empty): ${E}"; if [[ -n "${E}" ]]; then BAD="${BAD} emr-active"; fi; S="$(aws stepfunctions list-executions --state-machine-arn "$(aws stepfunctions list-state-machines --region us-east-2 --query "stateMachines[?name=='scholarchain-course-pipeline'].stateMachineArn | [0]" --output text)" --status-filter RUNNING --region us-east-2 --query 'executions[].[name,status]' --output text 2>&1)"; echo "--sfn-running (expect empty): ${S}"; if [[ -n "${S}" ]]; then BAD="${BAD} sfn-running"; fi; I="$(aws ec2 describe-instances --region us-east-2 --filters "Name=instance-state-name,Values=pending,running" --query 'Reservations[].Instances[].[InstanceId,InstanceType]' --output text 2>&1)"; echo "--ec2 pending or running (expect empty): ${I}"; if [[ -n "${I}" ]]; then BAD="${BAD} ec2-running"; fi; P="$(aws ec2 describe-vpc-endpoints --region us-east-2 --query 'VpcEndpoints[].[VpcEndpointType,ServiceName,State]' --output text 2>&1)"; echo "--vpc-endpoints (expect only Gateway s3 and dynamodb rows):"; printf '%s\n' "${P}"; NG="$(printf '%s\n' "${P}" | grep -v -e '^$' | grep -c -v -E '^Gateway[[:space:]]+com\.amazonaws\.us-east-2\.(s3|dynamodb)[[:space:]]')"; if [[ "${NG}" != "0" ]]; then BAD="${BAD} vpc-endpoints"; fi; if [[ -z "${BAD}" ]]; then echo "POST_TEARDOWN_CLEAN=yes"; else echo "POST_TEARDOWN_CLEAN=NO:${BAD}"; false; fi ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-state-${STAMP}.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected `POST_TEARDOWN_CLEAN=yes`. Stop (report; nothing purged or terminated): `POST_TEARDOWN_CLEAN=NO` for any listed section (a non-zero DLQ, an active cluster, a running execution or instance, any interface endpoint, or error text); the afternoon teardown is then not confirmed and session 2 is not planned from the down state. Reused later in the session the verdict is read by section, not as a whole: the session's interface endpoints are expected up, and before C the embedding DLQ holds F's poison message; the EMR, Step Functions and EC2 sections must still be empty. A leftover EMR cluster is the S3 emergency (section 4). The afternoon read was not complete (`session-b-now-record.md`: Step Functions not read); this is the first full short read.

### P.2 Collector for the B run (masked, tee'd; writes `collected/` once)

The collector prints execution ARNs, so its screen output is redacted; the tee'd log is raw and stays local. A failed required call keeps `collect-failed.<stamp>/` and can be repeated. UNVERIFIED: that every collector call still works after the cluster terminated and the endpoints were destroyed (EMR keeps terminated clusters visible; the scaling-activity reads are the doubtful part, C4).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; B_DIR=/Users/winnguyen/scholarchain-data/experiments/runs/exp-B-20261117T185928Z-5c69ba90; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; if [[ -d "${B_DIR}" ]]; then AWS_PROFILE=scholarchain bash experiments/b-burst/collect.sh --run-dir "${B_DIR}" 2>&1 | tee ${SCD}/logs/phase15-tn-b-collect.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; else echo "B run directory is missing: STOP"; false; fi; echo "exit: $?"
```

### P.3 Derive for the B run (masked, tee'd)

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; B_DIR=/Users/winnguyen/scholarchain-data/experiments/runs/exp-B-20261117T185928Z-5c69ba90; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; if [[ -d "${B_DIR}/collected" ]]; then python3 -I experiments/b-burst/derive-capacity.py derive --collected-dir "${B_DIR}/collected" --out-dir "${B_DIR}/derived" --title "Experiment B: EMR capacity over time" 2>&1 | tee ${SCD}/logs/phase15-tn-b-derive.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; else echo "collected/ is missing: STOP (P.2 first)"; false; fi; echo "exit: $?"
```

Expected from P.2 `COLLECT_COMPLETE cluster=<id> window=...`; from P.3 `DERIVED_DIR=...` and `scale_out_observed=<yes|unconfirmed|no> scale_in_observed=<yes|no|unknown>`. The goal was `scale_in_observed=yes` (P15-23); any other value is the result. Paste both outputs into the chat; the orchestrator records them (H.6).

---

## Section E. Evening, before the session (P15-41 order: P, heap measure, 4.4 read, then the session)

4.1 (download of A's export) is done: `DOWNLOAD_MATCHES_S3_LISTING=yes`, `rehearsal/a-export`, 34 files, 373,888,058 bytes.

### E.1 Clock and local export

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ( date; date -u; echo "OFFSET=$(date +%z) (expect -0600)"; F="$(find ${SCD}/rehearsal/a-export -type f | wc -l | tr -d ' ')"; B="$(find ${SCD}/rehearsal/a-export -type f -exec stat -f %z {} + | awk '{ s += $1 } END { print s + 0 }')"; echo "local files=${F} bytes=${B} (expect 34 373888058)"; if [[ "${F}" == "34" && "${B}" == "373888058" ]]; then echo "A_EXPORT_LOCAL_OK=yes"; else echo "A_EXPORT_LOCAL_OK=NO: STOP"; false; fi ) 2>&1 | tee ${SCD}/logs/phase15-tn-clock-export.log | tail -n 60; echo "exit: $?"
```

### E.2 Bedrock availability for session 2 (read-only; P15-13, P15-37)

```
set -o pipefail; AWS_PROFILE=scholarchain aws bedrock get-foundation-model-availability --model-id anthropic.claude-haiku-4-5-20251001-v1:0 --region us-east-2 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-bedrock-availability.log | tail -n 60; echo "exit: $?"
```

```
set -o pipefail; AWS_PROFILE=scholarchain aws bedrock list-inference-profiles --type-equals SYSTEM_DEFINED --region us-east-2 --query 'inferenceProfileSummaries[].[inferenceProfileId,status]' --output text 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-bedrock-profiles.log | tail -n 60; echo "exit: $?"
```

Expected `agreementAvailability AVAILABLE` and `us.anthropic.claude-haiku-4-5-20251001-v1:0 ACTIVE`. If not: the smoke is NOT RUN; the human fixes it in the console (not a runbook step).

### E.3 HEAP-MEASURE (AB 4.2; the human's own sbt run, one sbt at a time)

Preconditions: 2.10 PASSED (session-record), 4.1 done, no session resource is up (P.1 shows it; the A + B and B-only session-downs are done). Measured on A's export, never `s-export` (P15-29). If D8 = sbt check, do not run this while the agent's `sbt check` runs: run E.3 first or wait.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCHOLARCHAIN_S_EXPORT_DIR=/Users/winnguyen/scholarchain-data/rehearsal/a-export sbt -J-Xmx1g "storage/testOnly scholarchain.storage.ExportHeapMeasureSuite" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-heap-measure.log | tail -n 60; echo "exit: $?"
```

E.3b (4.2b) the line is present and the six SP-2 tables are non-zero:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; L=/Users/winnguyen/scholarchain-data/logs/phase15-tn-heap-measure.log; N="$(grep -a -c 'HEAP-MEASURE' ${L})"; echo "HEAP_MEASURE_LINES=${N}"; grep -a 'HEAP-MEASURE' ${L}; R="$(grep -a 'HEAP-MEASURE' ${L} | sed -n 's/.*rows=//p' | tail -n 1)"; BAD=""; for T in path-evidence components component-membership communities community-membership community-cross-edges; do V="$(printf '%s\n' "${R}" | tr ',' '\n' | awk -F: -v t="${T}" '$1 == t { print $2; exit }')"; echo "rows ${T}=${V:-MISSING}"; if [[ ! "${V}" =~ ^[0-9]+$ ]] || [[ "${V}" -le 0 ]]; then BAD="${BAD} ${T}"; fi; done; if [[ "${N}" -ge 1 && -z "${BAD}" ]]; then echo "HEAP_MEASURE_ROWS_CHECK=PASS"; else echo "HEAP_MEASURE_ROWS_CHECK=FAIL: void, 4096 MiB stands (zero or missing:${BAD})"; false; fi 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-heap-measure-check.log | tail -n 60; echo "exit: $?"
```

`path-evidence` shows `rows=42` (P15-38): non-zero, so it passes. An `OutOfMemoryError` at `-Xmx1g` means 4096; no larger cap, no retry.

### E.4 Sizing rule (AB 4.3; P15-30 OD-3, ratified; `SESSION2.md` section 7)

Valid line (six SP-2 tables non-zero): retained at most 400 MiB AND peak at most 800 MiB gives 2048; otherwise, `OutOfMemoryError`, or a void measure gives 4096. No other value or formula.

### E.5 `service_cpu` and `service_memory` in `session2.tfvars` (outside the repository)

Verified by reading: `session2.tfvars` has two lines from AB 2.9. `plan.sh` appends each `--extra-var-file` after `course.tfvars` in command order (`plan.sh:29,59`), so `session2.tfvars` after `services-session.tfvars` overrides its `service_memory = 4096` (`:39`) in A2 and A3 (`SESSION2.md:70-71`); 2048 and 4096 pass the module validation at 512 CPU (`modules/runtime/variables.tf:119-131`). A4 omits `services-session.tfvars`, so `service_cpu` would fall to 256 (`variables.tf:414-424`): 4096 fails validation, 2048 plans CPU 512 to 256. infra-engineer confirmed (`tonight-infra-report.md` item 2): appending `service_cpu = 512` and `service_memory = <2048|4096>` gives the same pair in A2, A3 and A4. The A4 query task definition is replaced either way (`SCHOLARCHAIN_BEDROCK` on to off, `SCHOLARCHAIN_NEPTUNE_HOST` to empty).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; F=/Users/winnguyen/scholarchain-data/session2.tfvars; MEM=REPLACE_WITH_2048_OR_4096; ( N="$(wc -l < ${F} | tr -d ' ')"; if [[ "${MEM}" =~ ^(2048|4096)$ && "${N}" == "2" ]] && ! grep -q -e '^service_memory' -e '^service_cpu' ${F}; then printf '%s\n' "service_cpu = 512" "service_memory = ${MEM}" >> ${F}; echo "APPENDED"; R=0; else echo "NOT APPENDED: MEM invalid, the file does not have exactly 2 lines (${N}), or the keys exist"; R=1; fi; cat ${F}; exit ${R} ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-session2-memory.log | tail -n 60; echo "exit: $?"
```

Expected: `APPENDED`, four lines, `exit: 0` (the `exit` is the append's status, not `cat`'s). The file is never committed.

### E.6 Static checks (no AWS, no sbt)

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; bash scripts/check.sh --skip-sbt 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-check.log | tail -n 60; echo "exit: $?"
```

Expected: `GATE terraform=PASS` and every gate that ran PASS, `GATE sbt=SKIP` allowed, `CHECK_RESULT=PASS_WITH_SKIPS` (a skip is not a pass; accepted only because no Scala changed), exit 0. If `terraform fmt -check` fails: STOP, route to infra-engineer through the orchestrator (the human does not run `terraform fmt` here). Nothing is committed on a failure.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; bash experiments/check-manifest-json.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-check-json.log | tail -n 20; echo "exit: $?"
```

Expected `MANIFEST_JSON_CHECK OK manifests=6`.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; bash tests/scripts/run.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-harness.log | tail -n 20; echo "exit: $?"
```

Expected `RESULT pass=1917 fail=0` (1889 + 28 new cases, the orchestrator's count). Any other number or a FAIL: stop, route the FAIL list to test-engineer.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; python3 -I load-tests/query-load/rehearse.py 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-load-rehearse.log | tail -n 20; echo "exit: $?"
```

Expected `REHEARSAL pass=45 fail=0`. It binds a local port (a sandbox without local port binding cannot run it; the human runs it). There is no `experiments/e-retrieval-modes/rehearse.py`, so `run.sh` is covered only by the harness, if at all (UNVERIFIED).

### E.7 The 4.4 read: what changed, against the allowlist (D2; fallback 0.5 form, git errors fail closed)

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; ( echo "HEAD=$(git rev-parse HEAD)"; git status --short | head -n 30; ST="$(git status --porcelain)"; GX=$?; N="$(printf '%s\n' "${ST}" | cut -c4- | grep -c -v -e '^docs/' -e '^infra/terraform/SESSION2.md$' -e '^infra/terraform/environments/course/services-session.tfvars$' -e '^infra/terraform/environments/course/experiment-size-s.tfvars$' -e '^infra/terraform/environments/course/variables.tf$' -e '^tests/scripts/run.sh$' -e '^experiments/pipeline-inputs/s-graph-only-reload.template.json$' -e '^experiments/pipeline-inputs/README.md$' -e '^load-tests/query-load/load_client.py$' -e '^load-tests/query-load/README.md$' -e '^experiments/e-retrieval-modes/run.sh$')"; echo "GIT_STATUS_EXIT=${GX} (0 = git answered)"; echo "PATHS_NOT_ALLOWED=${N}"; if [[ "${GX}" == "0" && "${N}" == "0" ]]; then echo "NO_CHANGE_OUTSIDE_ALLOWLIST=yes"; else echo "NO_CHANGE_OUTSIDE_ALLOWLIST=NO: STOP, report (a git error also stops); discard nothing"; false; fi ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-tree-read.log | tail -n 60; echo "exit: $?"
```

Expected `NO_CHANGE_OUTSIDE_ALLOWLIST=yes`. A path of the list that `git status` does not show is simply absent. The B-now record `docs/agent-runs/phase-15/session-ab/session-b-now-record.md` is under `docs/` and passes the `^docs/` pattern (the patterns are unchanged in revision 4). Stop: any `modules/`, `scripts/`, `build.sbt`, `project/`, `packaging/` path or any other path; never `git checkout`, `reset`, `stash drop` or `clean`.

### E.7b Agent `sbt check` slot (only if D8 = sbt check; before E.8)

The orchestrator dispatches one agent, which runs exactly `sbt check` (the bare two-token form; `CLAUDE.md` Hook operation). About 33 minutes. No other sbt runs meanwhile (E.3 before or after). Expected `Passed: Total <N>, Failed 0, Errors 0` and `[success]`; the pasted line goes to the orchestrator and into the evening commit message ledger. A failure: no commit; route to the owner. If D8 = human ruling, this slot is skipped and the ruling is quoted in the ledger.

### E.8 The evening commit (human; explicit file list; the HEAD every later step is bound to)

E.8a stage the explicit list and check every staged path is allowed (nothing else is added; no `git add -A`; fallback 1.3d form):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; git add docs/releases/phase-15 docs/agent-runs/phase-15 infra/terraform/SESSION2.md infra/terraform/environments/course/services-session.tfvars infra/terraform/environments/course/experiment-size-s.tfvars infra/terraform/environments/course/variables.tf experiments/pipeline-inputs/s-graph-only-reload.template.json experiments/pipeline-inputs/README.md tests/scripts/run.sh load-tests/query-load/load_client.py load-tests/query-load/README.md experiments/e-retrieval-modes/run.sh; AX=$?; ( SL="$(git diff --cached --name-only)"; DX=$?; BAD="$(printf '%s\n' "${SL}" | grep -v -e '^$' -e '^docs/' -e '^infra/terraform/SESSION2.md$' -e '^infra/terraform/environments/course/services-session.tfvars$' -e '^infra/terraform/environments/course/experiment-size-s.tfvars$' -e '^infra/terraform/environments/course/variables.tf$' -e '^experiments/pipeline-inputs/s-graph-only-reload.template.json$' -e '^experiments/pipeline-inputs/README.md$' -e '^tests/scripts/run.sh$' -e '^load-tests/query-load/load_client.py$' -e '^load-tests/query-load/README.md$' -e '^experiments/e-retrieval-modes/run.sh$')"; echo "GIT_ADD_EXIT=${AX} GIT_DIFF_EXIT=${DX} (0 = git answered)"; echo "STAGED_FILES=$(printf '%s\n' "${SL}" | grep -c .)"; printf '%s\n' "${SL}" | head -n 60; echo "NOT_STAGED_OR_UNTRACKED (expect none):"; git status --short | grep -v '^[AMDR]  ' | head -n 20; if [[ "${AX}" == "0" && "${DX}" == "0" && -z "${BAD}" ]]; then echo "STAGED_ALLOWED_ONLY=yes"; else echo "STAGED_ALLOWED_ONLY=NO: STOP (a git error also stops):"; echo "${BAD}"; false; fi ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-stage.log | tail -n 60; echo "exit: $?"
```

Expected `STAGED_ALLOWED_ONLY=yes`, the ten paths plus `docs/` files, nothing under `NOT_STAGED_OR_UNTRACKED`. Stop: `NO` (`git restore --staged <path>` by hand and rerun; never commit it) or a leftover path.

E.8b commit (only what is staged, no `-a`):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; git commit -m "Phase 15 combined session tonight: runbook, session 2 readiness repairs, graph-only reload input, harness cases, load client and E retrieval run script" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-commit.log | tail -n 60; echo "exit: $?"
```

### E.9 Pin the HEAD and the code gate G (reused at S.0 and H.0)

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; (set -C; git rev-parse HEAD > ${SCD}/logs/phase15-tn-commit-sha.txt) 2>&1; echo "PINNED=$(cat ${SCD}/logs/phase15-tn-commit-sha.txt)"; git log --oneline -3 2>&1 | tee ${SCD}/logs/phase15-tn-pin.log | tail -n 60; echo "exit: $?"
```

G (fallback 1.4 form; the pin is read from the file; paste as is):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; W=6cd73419c546b2205a2e2fde09b25a747c3efb8e; C="$(cat /Users/winnguyen/scholarchain-data/logs/phase15-tn-commit-sha.txt)"; ( git merge-base --is-ancestor ${W} HEAD; ANC=$?; DL="$(git diff --name-only --no-renames ${W} HEAD)"; DX=$?; DB="$(printf '%s\n' "${DL}" | grep -v -e '^docs/' -e '^infra/terraform/SESSION2.md$' -e '^infra/terraform/environments/course/services-session.tfvars$' -e '^infra/terraform/environments/course/experiment-size-s.tfvars$' -e '^infra/terraform/environments/course/variables.tf$' -e '^experiments/pipeline-inputs/s-graph-only-reload.template.json$' -e '^experiments/pipeline-inputs/README.md$' -e '^tests/scripts/run.sh$' -e '^load-tests/query-load/load_client.py$' -e '^load-tests/query-load/README.md$' -e '^experiments/e-retrieval-modes/run.sh$' -e '^$')"; CL="$(git diff -U0 --no-renames ${W} HEAD -- 'infra/terraform/environments/course/*.tfvars' | grep -E '^[+-]' | grep -v -e '^+++' -e '^---' | grep -v -E '^[+-][[:space:]]*(#|//)?[[:space:]]*$' | grep -v -E '^[+-][[:space:]]*(#|//)')"; VL="$(git diff -U0 --no-renames ${W} HEAD -- infra/terraform/environments/course/variables.tf | grep -E '^[+-]' | grep -v -e '^+++' -e '^---' | grep -v -E '^[+-][[:space:]]*(#|//)?[[:space:]]*$' | grep -v -E '^[+-][[:space:]]*(#|//)' | grep -v -E '^[+-][[:space:]]*description[[:space:]]*=[[:space:]]*".*"[[:space:]]*$')"; S="$(git status --porcelain | wc -l | tr -d ' ')"; BR="$(git symbolic-ref --short -q HEAD)"; H="$(git rev-parse HEAD)"; echo "HEAD=${H}"; echo "PIN=${C}"; echo "BRANCH=${BR:-DETACHED}"; echo "ANCESTOR_EXIT=${ANC} (0 = yes)"; echo "DIFF_EXIT=${DX} (0 = git answered)"; echo "PATHS_NOT_ALLOWED_SINCE_6cd7341 (expect none):"; echo "${DB}"; echo "NON_COMMENT_LINES_IN_TFVARS (expect none):"; echo "${CL}"; echo "NON_DESCRIPTION_LINES_IN_VARIABLES_TF (expect none):"; echo "${VL}"; echo "STATUS_LINES=${S}"; git status --short | head -n 20; if [[ "${C}" =~ ^[0-9a-f]{40}$ && "${C}" == "${H}" && "${ANC}" == "0" && "${DX}" == "0" && -z "${DB}" && -z "${CL}" && -z "${VL}" && "${S}" == "0" && "${BR}" == "main" ]]; then echo "GATE=PASS"; else echo "GATE=FAIL: STOP"; false; fi ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-gate-$(date -u +%H%M%S).log | tail -n 60; echo "exit: $?"
```

Expected `GATE=PASS`, `STATUS_LINES=0`. The tfvars test covers every `*.tfvars` of the course environment; a multi-line description would fail closed and the human reads the printed lines. The regexes are UNVERIFIED by a run (they are the fallback's). From here S1 applies.

### E.10 No package tonight (decision, copied from the fallback 2.4)

No `modules/`, `scripts/`, `build.sbt`, `project/` or `packaging/` path changed since Wave 2 (E.9 proves it), so the AB `target/docker-local` (built by `package.sh` in the A + B session, 1.6) is reused: `deploy.sh` builds the services and loader images from it under the new HEAD tag. Do NOT run `package.sh` or sbt again: a new jar would not match the S3 jars checked in E.11. DC (A1 only) compares the build contexts with the A + B ones.

### E.11 The uploaded bootstrap and jars are the committed ones (fallback 1.6; reuse or stop)

A mismatch is a STOP: no upload without a reviewed upload path (the A + B 1.9W, D, R commands, added by the orchestrator through infra-engineer and the human). The local jars are those of the A + B build (ignored `modules/*/target/job/`).

E.11a node scripts and the P15-23 profile:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; ART_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw artifacts_bucket)"; X=0; for F in lib-emr.sh run-s-pipeline.sh capability-check.sh spark-yarn.conf; do L="$(shasum -a 256 < /Users/winnguyen/ScholarChain/scripts/emr/${F} | cut -d' ' -f1)"; R="$(aws s3 cp "s3://${ART_BUCKET}/bootstrap/${F}" - --region us-east-2 | shasum -a 256 | cut -d' ' -f1)"; if [[ "${L}" == "${R}" ]]; then echo "MATCH ${F}"; else echo "DIFFERENT ${F}"; X=1; fi; done; P="$(aws s3 cp "s3://${ART_BUCKET}/bootstrap/spark-yarn.conf" - --region us-east-2 | grep -E '^b-burst\.(PARTITION_COUNT|SPLIT_MAX_BYTES|MAP_MEMORY_MB|REDUCE_MEMORY_MB)=' | LC_ALL=C sort | tr '\n' ' ')"; echo "${P}"; if [[ "${P}" == "b-burst.MAP_MEMORY_MB=2048 b-burst.PARTITION_COUNT=8 b-burst.REDUCE_MEMORY_MB=3072 b-burst.SPLIT_MAX_BYTES=262144 " ]]; then echo "B_PROFILE_P15_23_IN_S3=yes"; else echo "B_PROFILE_P15_23_IN_S3=NO"; X=1; fi; if [[ "${X}" == "0" ]]; then echo "BOOTSTRAP_REUSE=MATCH"; else echo "BOOTSTRAP_REUSE=NO: STOP"; false; fi ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-bootstrap-check.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

E.11b top-level job jars of the three modules:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; ART_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw artifacts_bucket)"; X=0; for M in hadoop entity-resolution spark; do for J in /Users/winnguyen/ScholarChain/modules/${M}/target/job/*.jar; do L="$(shasum -a 256 < "${J}" | cut -d' ' -f1)"; R="$(aws s3 cp "s3://${ART_BUCKET}/jars/${M}/${J##*/}" - --region us-east-2 | shasum -a 256 | cut -d' ' -f1)"; if [[ "${L}" == "${R}" ]]; then echo "JAR_MATCH ${M}/${J##*/}"; else echo "JAR_DIFFERENT ${M}/${J##*/}"; X=1; fi; done; done; if [[ "${X}" == "0" ]]; then echo "JARS_REUSE=MATCH"; else echo "JARS_REUSE=NO: STOP"; false; fi ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-jars-check.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: four `MATCH`, `B_PROFILE_P15_23_IN_S3=yes`, `BOOTSTRAP_REUSE=MATCH`, a `JAR_MATCH` per top-level jar, `JARS_REUSE=MATCH`. Stop: any `DIFFERENT`, `JAR_DIFFERENT`, a missing verdict (zsh `no matches found` because `target/job` was cleaned: reuse cannot be shown). The `lib/*.jar` are skipped: acceptable because `build.sbt` and `project/` are outside the allowlist.

### E.12 Load-client image (local build under the HEAD tag; the push follows A2)

`load_client.py` changed tonight, so the image is built from the evening commit. The ECR repository `scholarchain-course-load-client` exists only after A2. Tag = the 40-hex HEAD SHA (OD-2, `SESSION2.md:135`; immutable, one build per SHA). Docker must be running.

```
docker buildx imagetools inspect python:3.12-slim-bookworm 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-python-base.log | tail -n 20
```

Copy the top-level `Digest: sha256:...`, then:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; DIG=REPLACE_WITH_SHA256_DIGEST; H="$(git rev-parse HEAD)"; if [[ "${DIG}" =~ ^sha256:[0-9a-f]{64}$ ]]; then docker build --platform linux/amd64 -f packaging/load-client/Dockerfile --build-arg PYTHON_BASE=python:3.12-slim-bookworm@${DIG} -t scholarchain-load-client:${H} load-tests/query-load; else echo "DIGEST NOT SET: STOP"; false; fi 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-load-client-build.log | tail -n 60; echo "exit: $?"
```

(`--platform linux/amd64` is added: Fargate is X86_64 and `human-commands.md` section 4 has no platform flag; UNVERIFIED that the Dockerfile accepts it.)

```
set -o pipefail; docker run --rm scholarchain-load-client:$(git -C /Users/winnguyen/ScholarChain rev-parse HEAD) --help 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-load-client-help.log | tail -n 20; echo "exit: $?"
```

### E.13 Third line of `session2.tfvars` (`SESSION2.md` 6a: after the last commit, none after this)

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; F=/Users/winnguyen/scholarchain-data/session2.tfvars; H="$(git rev-parse HEAD)"; PIN="$(cat /Users/winnguyen/scholarchain-data/logs/phase15-tn-commit-sha.txt)"; ( N="$(wc -l < ${F} | tr -d ' ')"; if [[ "${H}" == "${PIN}" && "${H}" =~ ^[0-9a-f]{40}$ && "${N}" == "4" ]] && ! grep -q '^load_client_image_tag' ${F}; then printf '%s\n' "load_client_image_tag = \"${H}\"" >> ${F}; echo "APPENDED"; R=0; else echo "NOT APPENDED: HEAD differs from the pin, not 40 hex, ${N} lines (expect 4), or the key exists"; R=1; fi; cat ${F}; exit ${R} ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-session2-tag.log | tail -n 20; echo "exit: $?"
```

Expected five lines: `export_run_id`, `graph_run_id`, `service_cpu = 512`, `service_memory = <MEM>`, `load_client_image_tag = "<HEAD>"`.

### E.14 Manifest copies and pipeline inputs (fallback 1.7 form; local files and fail-closed S3 reads)

B is done (P15-42), so there is no B manifest, B input or B run id check tonight. The four manifest copies (D, E, F, C) and the graph-only reload input. Noclobber; the reload input is the experiment-engineer's template as is (`run_id` already `run-v1-b76e2d37-2ac1-40d4-923b-9418d5ff72c7`, exactly the keys `mode`, `run_id`, `loader_command`, `--tolerate-load-errors` OUT):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; REPO=/Users/winnguyen/ScholarChain; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; mkdir -p ${SCD}/experiments/manifests ${SCD}/experiments/runs ${SCD}/pipeline-inputs ${SCD}/approvals ${SCD}/plans; if [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ ]]; then for X in d:d-query-load e:e-retrieval-modes f:f-failure-recovery c:c-embedding-backlog; do K="${X%%:*}"; N="${X#*:}"; (set -C; sed -e "s/^ACCOUNT_ID=.*/ACCOUNT_ID=${ACCOUNT_ID}/" -e "s#^OUTPUT_ROOT=.*#OUTPUT_ROOT=${SCD}/experiments/runs#" ${REPO}/experiments/manifests/experiment-${N}.manifest > ${SCD}/experiments/manifests/p15-tn-${K}.manifest) || echo "manifest ${K}: exists or failed"; done; (set -C; cp ${REPO}/experiments/pipeline-inputs/s-graph-only-reload.template.json ${SCD}/pipeline-inputs/p15-tn-reload.json) || echo "reload input: exists or failed"; grep -HE '^(EXPERIMENT_ID|MAX_MINUTES|MAX_TASKS|SPEND_CEILING_USD|HOURLY_BURN_USD)=' ${SCD}/experiments/manifests/p15-tn-*.manifest; grep -o 'run-v1-[0-9a-f-]*' ${SCD}/pipeline-inputs/p15-tn-reload.json; else echo "no 12-digit account id from STS: STOP"; false; fi 2>&1 | tee ${SCD}/logs/phase15-tn-prepare-inputs.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: four `p15-tn-*.manifest` (d, e, f, c), limits D 70/48/20000/0.65/0.55, E 60/2/1500/0.45/0.42, F 30/2/200/0.25/0.40, C 60/4/2000/0.60/0.45; one `run-v1-<uuid>` in the reload input. A second run reports `exists`: read why; delete only by hand and treat any digest from it as void.

### E.15 Digests (no AWS call; `--print-digest` exits before any cloud call)

The E manifest is unchanged (`MAX_TASKS` 1500), but `run.sh` changed tonight, so the E script digest differs from any earlier one; digests are printed after the commit, so they are the ones to approve. Experiments (cost ceilings D 0.65, E 0.45, F 0.25, C 0.60):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; { for X in d:0.65 e:0.45 f:0.25 c:0.60; do K="${X%%:*}"; C="${X#*:}"; bash scripts/run-experiment.sh --print-digest --manifest ${SCD}/experiments/manifests/p15-tn-${K}.manifest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd "${C}" 2>&1 | tee ${SCD}/logs/phase15-tn-${K}-experiment-digest.log | grep -E '^(ACTION|SCRIPT_DIGEST|ARGS_DIGEST|EVIDENCE)' | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g"; echo "${K} exit: $?"; done; } | tail -n 60; echo "exit: $?"
```

Pipeline (the graph-only reload, the experiment-engineer's proposal `pipeline-inputs/README.md:20-28`: `--size large --max-minutes 100 --spend-ceiling-usd 1.70 --hourly-burn-usd 0.97`):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; bash scripts/run-bounded-pipeline.sh --print-digest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --size large --max-minutes 100 --spend-ceiling-usd 1.70 --hourly-burn-usd 0.97 --stop-on first-failure,dlq-nonempty --input-file ${SCD}/pipeline-inputs/p15-tn-reload.json 2>&1 | tee ${SCD}/logs/phase15-tn-reload-pipeline-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: each prints `ACTION=`, `SCRIPT_DIGEST=`, `ARGS_DIGEST=` and no `EVIDENCE:` refusal (an `EVIDENCE` line is a stop). Projected spend: reload 0.97 x 100 / 60 = 1.617 at most 1.70.

### E.16 Evening exit

Section P done (P.1 clean, P.2 and P.3 output in the chat), billing gate GO (D3 LAG typed), D1 to D8 typed (D5 later), E.3b PASS, E.9 `GATE=PASS`, E.11 reuse, E.12 build, E.13 `APPENDED`, E.15 digests. Then the session.

---

## Deploy commands (generic DP, DC, DW, DD, DR for A1, A2, A3 and A4)

Revision 3's Part B (B.0 to B.7) is removed: B ran this afternoon (P15-42), the state reads are P.1, and B's collector and derive are P.2 and P.3. What survives here is the generic deploy sequence, with `NAME` and `STATE_ACK` set per apply (A1 `tn-s1` and `no`; the table in S.1, S.4, S.7 and H.1 gives each). Rules S1 to S5 apply.

### DP, DC, DW, DD, DR (`NAME` and `STATE_ACK` set per apply)

DP digests (Docker running; `deploy.sh` builds and pushes both images under the new HEAD tag from the AB `target/docker-local`; do not rebuild):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; NAME=tn-s1; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PLAN_FILE="$(ls ${SCD}/plans/p15-${NAME}/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --print-digest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --plan-file "${PLAN_FILE}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-${NAME} 2>&1 | tee ${SCD}/logs/phase15-${NAME}-deploy-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

DC (A1 only, between DP and DW) the build contexts are those of the A + B A-up (fallback 2.4c; read-only; UNVERIFIED that `ARGS_CANONICAL` carries them in that form, `deploy.sh:94`; the B-only session's contexts digest `a70d0d11...` is recorded as unchanged in `session-b-now-record.md`):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; NAME=tn-s1; SCD=/Users/winnguyen/scholarchain-data; ( X=0; for K in services_context loader_context; do A="$(grep -o "${K}=[^; ]*" ${SCD}/logs/phase15-ab-a-deploy-digest.log | head -n 1)"; N="$(grep -o "${K}=[^; ]*" ${SCD}/logs/phase15-${NAME}-deploy-digest.log | head -n 1)"; echo "ab ${A}"; echo "tn ${N}"; if [[ -z "${N}" || "${A}" != "${N}" ]]; then X=1; fi; done; if [[ "${X}" == "0" ]]; then echo "CONTEXTS_EQUAL_AB=yes"; else echo "CONTEXTS_EQUAL_AB=NO: STOP, report (target/docker-local differs from the A + B build)"; false; fi ) 2>&1 | tee ${SCD}/logs/phase15-${NAME}-contexts.log | tail -n 60; echo "exit: $?"
```

DW (the human approves the plan digest, script revision, account, Region, cost ceiling 20 and the rollback gate below, after reading the plan with reader R and typing the D5 line where the plan has IAM changes):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; NAME=tn-s1; STATE_ACK=no; SCD=/Users/winnguyen/scholarchain-data; REC=${SCD}/approvals/p15-${NAME}-deploy.env; dg() { awk -F= -v k="${2}" '$1 == k { print $2; exit }' "${1}"; }; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; EXP="$(date -u -v+8H +%Y-%m-%dT%H:%M:%SZ)"; ( (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=deploy" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=20" "PLAN_DIGEST=$(dg ${SCD}/logs/phase15-${NAME}-plan.log PLAN_DIGEST)" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-${NAME}-deploy-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-${NAME}-deploy-digest.log ARGS_DIGEST)" "GIT_SHA=$(git rev-parse HEAD)" "STATEFUL_CHANGE_ACKNOWLEDGED=${STATE_ACK}" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > "${REC}") && sed "s/^ACCOUNT_ID=.*/ACCOUNT_ID=<masked>/" "${REC}" ) 2>&1 | tee ${SCD}/logs/phase15-${NAME}-deploy-record.log | tail -n 60; echo "exit: $?"
```

DD dry run (consumes nothing; expected `CLEAN_TREE`, `APPROVAL_VERIFIED action=deploy`, `APPROVAL_NOT_CONSUMED dry-run`, `DRY_RUN_COMPLETE`, no `APPROVAL_SKIPPED_FIELDS` naming `PLAN_DIGEST` or `ARGS_DIGEST`):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; NAME=tn-s1; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PLAN_FILE="$(ls ${SCD}/plans/p15-${NAME}/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --dry-run --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-${NAME}-deploy.env --plan-file "${PLAN_FILE}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-${NAME} 2>&1 | tee ${SCD}/logs/phase15-${NAME}-deploy-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

DR the apply. For A1 it is the first billable step of session 2; the printed time is the session-2 T0 (record it in the chat).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; NAME=tn-s1; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PLAN_FILE="$(ls ${SCD}/plans/p15-${NAME}/course-*.tfplan)"; date -u +"S3_CLOCK_START_UTC=%Y-%m-%dT%H:%M:%SZ"; date +"LOCAL_START=%H:%M"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-${NAME}-deploy.env --plan-file "${PLAN_FILE}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-${NAME} 2>&1 | tee ${SCD}/logs/phase15-${NAME}-deploy.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: steps ok, `IMAGE_PUSHED` for both repositories, `DEPLOY_COMPLETE`, `DEPLOYED_REVISION=<HEAD>`, `REVISION_OK` for fast, slow, loader, exit 0; apply counts equal to the plan within the known state-machine plan-versus-apply gap. A `REVISION_MISMATCH` naming `load-client` or `worker` means a stack flag leaked: a failed apply. Known process gap (Phase 14 README): the apply runs before the image push, so task definitions briefly name a tag not yet in ECR (services at desired 0). Rollback gate (all applies): there is no reversal; the way back is a new plan with a new record (H); pushed tags are immutable. A half-applied plan: re-plan with the same overlays under a `-2` name to see the drift and report; no second apply without a new plan, record and decision. The record is consumed on failure.

---

## Part S2. Session-2 overlays (A1, reload, A2, smoke, A3, D, E, F, C), one session-down at the end

Order and var files: `SESSION2.md` section 5 (`:66-72`) and section 5b (infra-engineer, counts). No commit between E.9 and H.4. Each apply has its own plan, digest and record.

Generic plan command P (`NAME` and the overlay array set per apply; layering is in command order after `course.tfvars`, later wins):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; NAME=tn-s1; SCD=/Users/winnguyen/scholarchain-data; ENVDIR=/Users/winnguyen/ScholarChain/infra/terraform/environments/course; OV=(${ENVDIR}/experiment-size-s.tfvars ${ENVDIR}/graph-load.tfvars); ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; A=(); for F in "${OV[@]}"; do A+=(--extra-var-file "${F}"); done; AWS_PROFILE=scholarchain bash scripts/plan.sh --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 "${A[@]}" --out-dir ${SCD}/plans/p15-${NAME} 2>&1 | tee ${SCD}/logs/phase15-${NAME}-plan.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Generic reader R (set `NAME`, `EXP`, `EXP2` (a second accepted counts line, equal to `EXP` when there is none), `STATE`; it checks the counts line, `STATEFUL_CHANGE`, `DIRTY=no` and the plan's `GIT_SHA` against the pinned HEAD, and prints the IAM section for the human to compare):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; NAME=tn-s1; EXP="19 to add, 6 to change, 3 to destroy"; EXP2="${EXP}"; STATE=no; SCD=/Users/winnguyen/scholarchain-data; ( LOG=${SCD}/logs/phase15-${NAME}-plan.log; M="$(ls ${SCD}/plans/p15-${NAME}/course-*.tfplan.meta)"; ACT="$(sed '/^PLAN_SUMMARY_BEGIN/,$d' ${LOG} | grep -nE '^[[:space:]]*# .*(will be created|will be destroyed|must be replaced|will be replaced|will be updated in-place)')"; echo "--resource actions"; printf '%s\n' "${ACT}"; echo "--summary"; sed -n '/^PLAN_SUMMARY_BEGIN/,/^PLAN_SUMMARY_END/p' ${LOG}; echo "--meta"; grep -E '^(GIT_SHA|DIRTY|STATEFUL_CHANGE|PLAN_EXIT|CPU_ARCHITECTURE|IMAGE_TAG)=' ${M}; echo "--iam section"; grep -A12 -x 'IAM_CHANGES:' ${LOG}; echo "--counts"; grep -E '[0-9]+ to add, [0-9]+ to change, [0-9]+ to destroy' ${LOG}; OK=1; grep -qF "${EXP}" ${LOG} || grep -qF "${EXP2}" ${LOG} || OK=0; grep -q "^STATEFUL_CHANGE=${STATE}" ${M} || OK=0; grep -q '^DIRTY=no' ${M} || OK=0; grep -q "^GIT_SHA=$(cat ${SCD}/logs/phase15-tn-commit-sha.txt)" ${M} || OK=0; echo "EXPECTED=${EXP} OR ${EXP2} STATEFUL=${STATE}"; if [[ "${OK}" == "1" ]]; then echo "PLAN_READ_CHECK=PASS (the human compares the IAM lines with the expected list)"; else echo "PLAN_READ_CHECK=FAIL: STOP"; false; fi ) 2>&1 | tee ${SCD}/logs/phase15-${NAME}-plan-read.log | tail -n 80; echo "exit: $?"
```

Deploy P, W, D, R: the commands DP, DW, DD, DR above with the `NAME` (and `STATE_ACK`) set; DC is A1 only.

### S.0 Gate before session 2 (identity, clock, code, billing)

Run G (E.9). Check the clock against C2 (A1 plan by 21:10 local if D is wanted, 23:05 for the smoke only). Identity:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs; ( echo "profile_region=$(AWS_PROFILE=scholarchain aws configure get region)"; C="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; if [[ "${C}" =~ ^[0-9]{12}$ ]]; then echo "caller_account=12-digit id (not printed)"; else echo "caller_account=NOT A 12-DIGIT ID: STOP"; false; fi ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-identity.log | tail -n 60; echo "exit: $?"
```

Run the P.1 command once more right before A1 (it is the state check; `POST_TEARDOWN_CLEAN=yes` expected). Billing: the section 2 command is the only spend gate. Nothing of session 2 has billed before A1, so no mid-session term exists (revision 3's `B_SPEND` and `IDLE_H` gate is removed with B). Rerun the section 2 command here only if the human has a newer reading or a different `LAG_USD` or `DROP`; its log name carries the time. STOP: reduce session 2 in the stated order or go to H (H only if something was applied). Never raise 14.

### S.1 A1: plan, read, approve, apply (Neptune `db.r6g.xlarge`, ninth endpoint, loader)

`NAME=tn-s1`, `OV=(${ENVDIR}/experiment-size-s.tfvars ${ENVDIR}/graph-load.tfvars)` (P above, as pasted). From the DOWN state at the evening commit's HEAD (P.1: eight interface endpoints destroyed, no Neptune, fast and slow at 0). Counts and IAM recomputed from the down state by infra-engineer into `SESSION2.md` 5b (relayed by the coordinator; the plan is the test, UNVERIFIED until it runs):
- Counts `19 to add, 6 to change, 3 to destroy`. ADD: 9 interface endpoints (the 8 of the split layout plus `bedrock-runtime`), the 7 resources of the Neptune module (including `aws_iam_role.bulk_load` and its policy), 3 replaced task definitions; CHANGE: the runtime and loader inline policies, `fast` and `slow` services, the state machine, the overview dashboard; DESTROY: the 3 replaced task definitions. No services-stack resource (`enable_services_stack` false: no ALB, no `query` service, no worker, no load-client repository, no new security group, no scaling dashboard, no `containerInsights` change).
- IAM (`SESSION2.md` section 5b): 2 in-place policy updates (`aws_iam_role_policy.runtime` gaining `NeptuneReadOpenCypher`, `NeptuneEngineStatus`, `BedrockInvokeExactModels`, `modules/identity/main.tf:318-352`; `aws_iam_role_policy.loader` gaining `NeptuneBulkLoad`, `BedrockEmbeddingsOnly`, `:454-478`) AND 2 creates: `aws_iam_role.bulk_load` (trust `rds.amazonaws.com`) and its policy (`modules/neptune/main.tf:58-67`). The worker role does not exist yet. STOP on any other IAM line, any role destroyed, or a plan with no Bedrock statement (it means `enable_bedrock` is off). `module.identity` is inferred (UNVERIFIED).
- `STATEFUL_CHANGE=no`.

Reader R with `NAME=tn-s1`, `EXP="19 to add, 6 to change, 3 to destroy"`, `EXP2` the same, `STATE=no`. D5 line before the record: `IAM DIFF APPROVED tn-s1: runtime and loader inline policies updated (Neptune and Bedrock statements); new role bulk_load (trust rds.amazonaws.com) with its policy; no other principal`. Apply: DP, DC, DW, DD, DR with `NAME=tn-s1`, `STATE_ACK=no`. Expected `DEPLOY_COMPLETE`, `REVISION_OK`, Neptune available. Rollback gate: H; Neptune data is destroyed there with no snapshot (`session-down.tfvars:5-8`).

### S.2 Graph-only reload of A's export (loader-only; `--tolerate-load-errors` OFF)

State check (the P.1 command, read by section) first. Records: action `pipeline`, single use, fresh per execution. S.2W:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; dg() { awk -F= -v k="${2}" '$1 == k { print $2; exit }' "${1}"; }; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; EXP="$(date -u -v+8H +%Y-%m-%dT%H:%M:%SZ)"; ( (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=pipeline" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=20" "PLAN_DIGEST=n/a" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-tn-reload-pipeline-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-tn-reload-pipeline-digest.log ARGS_DIGEST)" "GIT_SHA=n/a" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > ${SCD}/approvals/p15-tn-reload-pipeline.env) && sed "s/^ACCOUNT_ID=.*/ACCOUNT_ID=<masked>/" ${SCD}/approvals/p15-tn-reload-pipeline.env ) 2>&1 | tee ${SCD}/logs/phase15-tn-reload-record.log | tail -n 60; echo "exit: $?"
```

S.2D dry run (the output location of a stand-alone `run-bounded-pipeline.sh` run is UNVERIFIED; the dry run shows it):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; AWS_PROFILE=scholarchain bash scripts/run-bounded-pipeline.sh --dry-run --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-tn-reload-pipeline.env --size large --max-minutes 100 --spend-ceiling-usd 1.70 --hourly-burn-usd 0.97 --stop-on first-failure,dlq-nonempty --input-file ${SCD}/pipeline-inputs/p15-tn-reload.json 2>&1 | tee ${SCD}/logs/phase15-tn-reload-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

S.2R:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; date +"RELOAD_START_LOCAL=%H:%M"; date +%s > ${SCD}/logs/phase15-tn-reload-start-epoch.txt; AWS_PROFILE=scholarchain bash scripts/run-bounded-pipeline.sh --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-tn-reload-pipeline.env --size large --max-minutes 100 --spend-ceiling-usd 1.70 --hourly-burn-usd 0.97 --stop-on first-failure,dlq-nonempty --input-file ${SCD}/pipeline-inputs/p15-tn-reload.json 2>&1 | tee ${SCD}/logs/phase15-tn-reload-run.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected `FINAL_STATUS=SUCCEEDED` (run 11 took 4.7 min whole for 11 tables; no EMR cluster; the loader cap is 5400 s). Burn arithmetic 0.97 x 100 / 60 = 1.617 at most 1.70 (`pipeline-inputs/README.md:25-26`).

S.2C check that the loader reported success (rag-engineer B2: graph-only downloads all 17 tables, verifies every manifest table, loads the 9 required and ignores the 6 others; A's export is inside the default bounds: largest table 101,478 rows under 1,000,000, 34 objects under 10,000, 373,888,058 bytes under 1 GiB). Read-only; the log window starts at the reload start; field names UNVERIFIED:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; ST="$(( $(cat ${SCD}/logs/phase15-tn-reload-start-epoch.txt) * 1000 ))"; A_RUN=run-v1-b76e2d37-2ac1-40d4-923b-9418d5ff72c7; DATA_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw data_bucket)"; mkdir -p ${SCD}/runs/p15-tn-reload; aws s3 cp "s3://${DATA_BUCKET}/reports/runs/${A_RUN}/loader/" ${SCD}/runs/p15-tn-reload/ --recursive --only-show-errors --region us-east-2; ls ${SCD}/runs/p15-tn-reload; echo "--NEPTUNE_LOAD lines (expect succeeded)"; aws logs filter-log-events --log-group-name /scholarchain-course/loader --start-time "${ST}" --filter-pattern NEPTUNE_LOAD --region us-east-2 --query 'events[].message' --output text | tail -n 10; B="$(aws logs filter-log-events --log-group-name /scholarchain-course/loader --start-time "${ST}" --filter-pattern BoundExceeded --region us-east-2 --query 'length(events)' --output text)"; F="$(aws logs filter-log-events --log-group-name /scholarchain-course/loader --start-time "${ST}" --filter-pattern FAILED --region us-east-2 --query 'length(events)' --output text)"; echo "BOUND_EXCEEDED_EVENTS=${B} FAILED_EVENTS=${F}"; if [[ "${B}" == "0" && "${F}" == "0" ]]; then echo "RELOAD_CHECK=PASS (the human still reads the NEPTUNE_LOAD line above)"; else echo "RELOAD_CHECK=FAIL: BoundExceeded or FAILED events: STOP, S2"; false; fi ) 2>&1 | tee ${SCD}/logs/phase15-tn-reload-check.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected `graph-report.json` and `graph-run.json` under the loader prefix, a `NEPTUNE_LOAD` success line, `RELOAD_CHECK=PASS`. Stop (S2): `NEPTUNE_LOAD=FAILED`, `BoundExceeded` or FAILED events (a benign "FAILED" word in a log line is possible, UNVERIFIED: the human reads the lines). No retry without the human's decision; a second attempt with `--tolerate-load-errors` is the human's call only if the failure is named, countable load errors and not memory (`pipeline-inputs/README.md:36`): a copied input, a new digest and a new record, the first run stays in the results. Without the graph, smoke, D and E are NOT RUN; F and C may run.

### S.3 Neptune back to `db.t4g.medium` (P15-5): inside A2

`SESSION2.md:70` puts the downsize in the A2 apply; no separate apply. The A2 plan must say `updated in-place` for the Neptune instance, never `must be replaced` (a replacement is a STOP).

### S.4 A2 (stack first creation, query 1 task, worker 1, ALB, scaling, Bedrock answer model, downsize)

`NAME=tn-s2`, `OV=(${ENVDIR}/experiment-size-s.tfvars ${ENVDIR}/services-session.tfvars /Users/winnguyen/scholarchain-data/session2.tfvars ${ENVDIR}/services-smoke.tfvars)` (P above). Predicted (`tonight-infra-report.md`; UNVERIFIED until the plan runs): `34 to add, 3 to change, 6 to destroy`. ADD: the worker role and policy, load-client ECR repository and lifecycle, 3 log groups (query, worker, load-client), 3 task definitions, 2 security groups and 7 rules, the ALB (3), the `query` and `worker` services, 6 scaling resources, 3 alarms, the scaling dashboard. CHANGE: the Neptune writer in place (`db.r6g.xlarge` to `db.t4g.medium`), cluster `containerInsights` to `enabled`, the overview dashboard. DESTROY: `fast` and `slow` task definitions, services and log groups. `STATEFUL_CHANGE=no`.

IAM (`SESSION2.md` section 5b, section 6): 2 creates, `aws_iam_role.worker[0]` (`scholarchain-course-worker`) and `aws_iam_role_policy.worker[0]` (`modules/identity/main.tf:565-578`) with `ConsumeEmbeddingWork` (SQS receive, delete, change-visibility, get-attributes on the embedding-work queue only), `ChunkLedger` (DynamoDB get and put on the run-ledger table), `WriteVectors` (S3 Vectors get-index, get-vectors, put-vectors on the five index ARNs), `ListDocumentPrefix` and `ChunkDocuments` (S3 under `semantic-context/documents/*`), `BedrockEmbeddingsOnly` (Titan V2). Runtime and loader policies unchanged (A1 applied them); `deployer` `iam:PassRole` gains the worker role ARN only if trusted principals exist. STOP on any other IAM line, a role or policy destroyed, or runtime or loader policy changes.

Reader R with `NAME=tn-s2`, `EXP="34 to add, 3 to change, 6 to destroy"`, `EXP2` the same, `STATE=no`. D5 line: `IAM DIFF APPROVED tn-s2: new role scholarchain-course-worker with worker-embed policy as in SESSION2.md section 6; blast radius SQS embedding-work queue, run-ledger table, five vector indexes, documents prefix, Titan V2 invoke`. Apply: DP, DW, DD, DR with `NAME=tn-s2`, `STATE_ACK=no`. `verify_revision` passes at A2 with the load-client image absent (it checks image existence only for the services and loader images and compares strings for the other families, `deploy.sh:206-216`, `lib.sh:934-976`): a passing verify is NOT evidence that the load-client image is pushed. Order: A2 deploy (creates the repository), S.5 push, S.5b `describe-images`, then the first `run-task`.

### S.5 Load-client image push, then prove it is in ECR (after A2, before the smoke, D and E)

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; H="$(git rev-parse HEAD)"; PIN="$(cat /Users/winnguyen/scholarchain-data/logs/phase15-tn-commit-sha.txt)"; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; REG="${ACCOUNT_ID}.dkr.ecr.us-east-2.amazonaws.com"; if [[ "${H}" == "${PIN}" ]]; then AWS_PROFILE=scholarchain aws ecr get-login-password --region us-east-2 | docker login --username AWS --password-stdin "${REG}"; docker tag scholarchain-load-client:${H} ${REG}/scholarchain-course-load-client:${H}; docker push ${REG}/scholarchain-course-load-client:${H}; else echo "HEAD differs from the pin: STOP"; false; fi 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-load-client-push.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

S.5b fail-closed image check (tags are immutable; the repository name is from `SESSION2.md:59`, UNVERIFIED against `ecr_repository_urls`):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; H="$(git rev-parse HEAD)"; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( D="$(AWS_PROFILE=scholarchain aws ecr describe-images --repository-name scholarchain-course-load-client --image-ids imageTag=${H} --region us-east-2 --query 'imageDetails[0].imageTags' --output text)"; echo "tags=${D}"; if [[ "${D}" == *"${H}"* ]]; then echo "LOAD_CLIENT_IMAGE_AT_HEAD=yes"; else echo "LOAD_CLIENT_IMAGE_AT_HEAD=NO: STOP, no smoke, D or E"; false; fi ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-load-client-image.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 20; echo "exit: $?"
```

Expected `LOAD_CLIENT_IMAGE_AT_HEAD=yes`. An error from `describe-images` leaves `D` empty and fails closed.

### S.6 A2 verification, then the Haiku smoke (exactly 1 task, at most 4 queries, P15-27)

Read-only A2 checks (`SESSION2.md:193-195`):

```
set -o pipefail; AWS_PROFILE=scholarchain aws ecs describe-services --cluster scholarchain-course-cluster --services scholarchain-course-query scholarchain-course-worker --region us-east-2 --query 'services[].[serviceName,desiredCount,runningCount,deployments[0].rolloutState]' --output text 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-s2-services.log | tail -n 60; echo "exit: $?"
```

```
set -o pipefail; AWS_PROFILE=scholarchain aws application-autoscaling describe-scalable-targets --service-namespace ecs --region us-east-2 --query 'ScalableTargets[].[ResourceId,MinCapacity,MaxCapacity]' --output text 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-s2-targets.log | tail -n 60; echo "exit: $?"
```

```
set -o pipefail; AWS_PROFILE=scholarchain aws cloudwatch describe-alarms --alarm-name-prefix scholarchain-course- --region us-east-2 --query 'MetricAlarms[].[AlarmName,StateValue]' --output text 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-s2-alarms.log | tail -n 60; echo "exit: $?"
```

Expected: query desired 1 and `COMPLETED` once the export has loaded (up to the 300 s grace), worker 1/1; targets `...query 1 1` (smoke overlay) and `...worker 1 4`; worker alarms `OK` or `INSUFFICIENT_DATA` (still `INSUFFICIENT_DATA` after 10 minutes: no C, report to infra-engineer, `SESSION2.md:197`).

First the environment file (S.8) must exist (write it now; it is also used by D, E, F, C). Smoke run-task (the command of `load-tests/query-load/README.md` "Smoke", with the task ARN kept; the base URL is the internal ALB; graph mode, the first four seeded `builtin:windows` questions; hard caps `--max-clients 1 --max-requests 4 --max-minutes 5`):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; source ${SCD}/p15-tn-env.sh; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; SMOKE_BASE_URL="${SCHOLARCHAIN_D_BASE_URL}"; date +"SMOKE_START_LOCAL=%H:%M"; AWS_PROFILE=scholarchain aws ecs run-task --cluster "${SCHOLARCHAIN_LC_CLUSTER}" --task-definition "${SCHOLARCHAIN_LC_TASK_DEFINITION}" --launch-type FARGATE --count 1 --started-by sc-smoke-haiku --network-configuration "awsvpcConfiguration={subnets=[${SCHOLARCHAIN_LC_SUBNETS}],securityGroups=[${SCHOLARCHAIN_LC_SECURITY_GROUPS}],assignPublicIp=DISABLED}" --overrides "{\"containerOverrides\":[{\"name\":\"${SCHOLARCHAIN_LC_CONTAINER}\",\"command\":[\"--base-url\",\"${SMOKE_BASE_URL}\",\"--out-dir\",\"/tmp/loadclient/SMOKE1\",\"--run-tag\",\"SMOKE1\",\"--sequence\",\"rotate-modes\",\"--modes\",\"graph\",\"--limit-queries\",\"4\",\"--clients\",\"1\",\"--mode-field\",\"retrievalMode\",\"--think-min\",\"1\",\"--think-max\",\"2\",\"--timeout\",\"60\",\"--max-clients\",\"1\",\"--max-requests\",\"4\",\"--max-minutes\",\"5\",\"--echo\"]}]}" --region us-east-2 --query 'tasks[0].taskArn' --output text 2>&1 | tee ${SCD}/logs/phase15-tn-smoke-task.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 20; echo "exit: $?"
```

Pass check (waits for the task to stop, then reads its exit code and its log lines; the last line of the task log is the task ARN):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; T="$(tail -n 1 ${SCD}/logs/phase15-tn-smoke-task.log)"; aws ecs wait tasks-stopped --cluster scholarchain-course-cluster --tasks "${T}"; EC="$(aws ecs describe-tasks --cluster scholarchain-course-cluster --tasks "${T}" --query 'tasks[0].containers[0].exitCode' --output text)"; echo "TASK_EXIT=${EC} (expect 0; 3 means a cap stopped it)"; ST="$(( ($(date +%s) - 3600) * 1000 ))"; aws logs filter-log-events --log-group-name /scholarchain-course/load-client --start-time "${ST}" --filter-pattern LOADCLIENT_RAW --region us-east-2 --query 'events[].[message]' --output text > ${SCD}/logs/phase15-tn-smoke-raw.txt; aws logs filter-log-events --log-group-name /scholarchain-course/load-client --start-time "${ST}" --filter-pattern LOAD_CLIENT_DONE --region us-east-2 --query 'events[].[message]' --output text > ${SCD}/logs/phase15-tn-smoke-done.txt; RAW="$(grep -c LOADCLIENT_RAW ${SCD}/logs/phase15-tn-smoke-raw.txt)"; OK200="$(grep -c -E 'status[^0-9]{1,5}200' ${SCD}/logs/phase15-tn-smoke-raw.txt)"; echo "LOADCLIENT_RAW_LINES=${RAW} (expect 4) STATUS_200_LINES=${OK200}"; cat ${SCD}/logs/phase15-tn-smoke-done.txt; if [[ "${EC}" == "0" && "${RAW}" == "4" ]] && grep -q 'LOAD_CLIENT_DONE requests=4 stop_reason=none' ${SCD}/logs/phase15-tn-smoke-done.txt; then echo "SMOKE_RESULT=PASS (the human reads status, evidence_items and abstained per line in phase15-tn-smoke-raw.txt)"; else echo "SMOKE_RESULT=FAIL: report, D and E need the human's decision"; false; fi ) 2>&1 | tee ${SCD}/logs/phase15-tn-smoke-check.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected four `LOADCLIENT_RAW` lines and `LOAD_CLIENT_DONE requests=4 stop_reason=none`, task exit 0, `status` 200 on each line, `evidence_items` above 0. P15-27 is UNVERIFIED until this runs: that Haiku is called in graph mode (planner and synthesis are model calls in every mode) and that retries add model calls ("4 queries" is not "4 model calls"; `bedrock_max_calls = 10` per process). An `AccessDenied` on `InvokeModel` or `neptune-db` openCypher is the unverified-IAM finding of `SESSION2.md:112-113`: report; no IAM change in the session without a privilege review. A cap ending the run (exit 3), non-200 lines, or no evidence: the smoke FAILED, and D and E need the human's decision. If A2's Bedrock availability (E.2) was not AVAILABLE, the smoke is NOT RUN.

### S.7 A3 (answer model back to stub; D, E, F, C run on it)

`NAME=tn-s3`, `OV=(${ENVDIR}/experiment-size-s.tfvars ${ENVDIR}/services-session.tfvars /Users/winnguyen/scholarchain-data/session2.tfvars)` (P above). Predicted (`tonight-infra-report.md`): `1 to add, 2 to change, 1 to destroy`: the query task definition replaced (stub, slow-deadline environment removed), the query service and its autoscaling target (max 1 to 4) in place. IAM none, `STATEFUL_CHANGE=no`. Reader R with `NAME=tn-s3`, `EXP="1 to add, 2 to change, 1 to destroy"`, `EXP2` the same, `STATE=no`; `IAM_CHANGES:` must read `  none` (the reader prints the IAM section: a non-`none` is a STOP). Apply DP, DW, DD, DR with `NAME=tn-s3`, `STATE_ACK=no`. Then wait for the query service `COMPLETED` and desired = running = 1 (first S.6 read command).

### S.8 Experiment environment file (identifiers, one local file; never committed or pasted)

Written once after A2. It holds queue URLs (which contain the account id) and resource names: it stays under `scholarchain-data`. The `load_client` output keys (`task_definition_family`, `security_group_id`) are from `SESSION2.md:49-56` (UNVERIFIED); the run-ledger table name follows the `scholarchain-course-` prefix (UNVERIFIED). The shell is zsh (`${=T}` word-splits).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; T="terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output"; E=${SCD}/p15-tn-env.sh; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; ALB="$(${=T} -raw alb_dns_name)"; LC="$(${=T} -json load_client)"; LCF="$(printf '%s' "${LC}" | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["task_definition_family"])')"; LCS="$(printf '%s' "${LC}" | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["security_group_id"])')"; SUB="$(${=T} -json app_subnet_ids | tr -d '[]" ')"; ALBARN="$(aws elbv2 describe-load-balancers --region us-east-2 --query 'LoadBalancers[0].LoadBalancerArn' --output text | sed 's#.*:loadbalancer/##')"; TGARN="$(aws elbv2 describe-target-groups --region us-east-2 --query 'TargetGroups[0].TargetGroupArn' --output text | sed 's#.*:##')"; NEP="$(aws neptune describe-db-clusters --region us-east-2 --query 'DBClusters[0].DBClusterIdentifier' --output text)"; DDB="$(${=T} -json dynamodb_table_names | python3 -I -c 'import json,sys; print(",".join(json.load(sys.stdin).values()))')"; QU="$(${=T} -raw embedding_queue_url)"; DQ="$(aws sqs get-queue-url --queue-name scholarchain-course-embedding-work-dlq --region us-east-2 --query QueueUrl --output text)"; CL="$(${=T} -raw ecs_cluster_name)"; (set -C; { echo "export SCHOLARCHAIN_D_BASE_URL=http://${ALB}"; echo "export SCHOLARCHAIN_E_BASE_URL=http://${ALB}"; echo "export SCHOLARCHAIN_LC_CLUSTER=${CL}"; echo "export SCHOLARCHAIN_LC_TASK_DEFINITION=${LCF}"; echo "export SCHOLARCHAIN_LC_CONTAINER=load-client"; echo "export SCHOLARCHAIN_LC_SUBNETS=${SUB}"; echo "export SCHOLARCHAIN_LC_SECURITY_GROUPS=${LCS}"; echo "export SCHOLARCHAIN_QL_ECS_CLUSTER=${CL}"; echo "export SCHOLARCHAIN_QL_ECS_SERVICE=$(${=T} -raw query_service_name)"; echo "export SCHOLARCHAIN_QL_ALB=${ALBARN}"; echo "export SCHOLARCHAIN_QL_TARGET_GROUP=${TGARN}"; echo "export SCHOLARCHAIN_QL_NEPTUNE_CLUSTER=${NEP}"; echo "export SCHOLARCHAIN_QL_DDB_TABLES=${DDB}"; echo "export SCHOLARCHAIN_QL_LOADCLIENT_LOG_GROUP=/scholarchain-course/load-client"; echo "export SCHOLARCHAIN_QL_LOADCLIENT_STREAM_PREFIX=ecs/load-client"; echo "export SCHOLARCHAIN_QL_SERVICE_LOG_GROUP=/scholarchain-course/query"; for P in C F; do echo "export SCHOLARCHAIN_${P}_QUEUE_URL=${QU}"; echo "export SCHOLARCHAIN_${P}_DLQ_URL=${DQ}"; echo "export SCHOLARCHAIN_${P}_CHUNK_FILE=${SCD}/phase15-chunkset-c/chunks.jsonl"; echo "export SCHOLARCHAIN_${P}_ECS_CLUSTER=${CL}"; echo "export SCHOLARCHAIN_${P}_ECS_SERVICE=$(${=T} -raw worker_service_name)"; echo "export SCHOLARCHAIN_${P}_WORKER_LOG_GROUP=/scholarchain-course/worker"; done; echo "export SCHOLARCHAIN_F_LEDGER_TABLE=scholarchain-course-run-ledger"; echo "export SCHOLARCHAIN_F_VECTOR_BUCKET=$(${=T} -raw vector_bucket)"; } > ${E}) && echo "ENV_FILE_WRITTEN" && grep -c '^export' ${E} && grep -c '=$' ${E} ) 2>&1 | tee ${SCD}/logs/phase15-tn-env-write.log | tail -n 20; echo "exit: $?"
```

Expected `ENV_FILE_WRITTEN`, then 30 (export lines) and 0 (lines with an empty value; `grep -c` exits 1 on a zero count, so the final `exit: 1` is the expected sign of 0 empty values). Stop: any empty value.

### S.9 Experiments D, E, F, C (P15-10 order; each only if C3 allows; collectors before A4)

Common per experiment: state check (the P.1 command, read by section), the clock rule C3, the record W, dry run, run, collector. Generic variables: `K` the record and run key, `M` the manifest key (`d e f c`), `CEIL` the ceiling, `XENV` optional environment (the environment is NOT in the approval digest; E subsets are labeled in the run ledger by the script).

W (record):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; K=d; M=d; CEIL=0.65; SCD=/Users/winnguyen/scholarchain-data; dg() { awk -F= -v k="${2}" '$1 == k { print $2; exit }' "${1}"; }; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; EXP="$(date -u -v+8H +%Y-%m-%dT%H:%M:%SZ)"; ( (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=experiment" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=${CEIL}" "PLAN_DIGEST=n/a" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-tn-${M}-experiment-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-tn-${M}-experiment-digest.log ARGS_DIGEST)" "GIT_SHA=$(git rev-parse HEAD)" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > ${SCD}/approvals/p15-tn-${K}-experiment.env) && sed "s/^ACCOUNT_ID=.*/ACCOUNT_ID=<masked>/" ${SCD}/approvals/p15-tn-${K}-experiment.env ) 2>&1 | tee ${SCD}/logs/phase15-tn-${K}-record.log | tail -n 60; echo "exit: $?"
```

Dry run, then run:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; K=d; M=d; CEIL=0.65; XENV=""; SCD=/Users/winnguyen/scholarchain-data; source ${SCD}/p15-tn-env.sh; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; env ${=XENV} AWS_PROFILE=scholarchain bash scripts/run-experiment.sh --dry-run --manifest ${SCD}/experiments/manifests/p15-tn-${M}.manifest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd "${CEIL}" --approval-record ${SCD}/approvals/p15-tn-${K}-experiment.env 2>&1 | tee ${SCD}/logs/phase15-tn-${K}-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; K=d; M=d; CEIL=0.65; XENV=""; SCD=/Users/winnguyen/scholarchain-data; source ${SCD}/p15-tn-env.sh; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; date +"${K}_START_LOCAL=%H:%M"; env ${=XENV} AWS_PROFILE=scholarchain bash scripts/run-experiment.sh --manifest ${SCD}/experiments/manifests/p15-tn-${M}.manifest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd "${CEIL}" --approval-record ${SCD}/approvals/p15-tn-${K}-experiment.env 2>&1 | tee ${SCD}/logs/phase15-tn-${K}-run.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Per run (set the variables of the first line of W, dry run and run):

| Run | `K`, `M`, `CEIL`, `XENV` | Prerequisites | Cap and hold |
|---|---|---|---|
| D | `d`, `d`, `0.65`, empty | passing smoke; query 1/1 on the stub; image pushed (S.5b yes) | 70 min; exit 0 when the schedule finished (about 47 min). Keep the services up 25 minutes after the client ends and confirm the query desired count is back at 1 (scale-in check) before E (`SESSION2.md:39`; client cooldown 900 s, scale-in 15 to 17 min). Collector now |
| E graph (D1 option 1) | `e`, `e`, `0.45`, `SCHOLARCHAIN_E_MODES=graph` | as D; D1 typed; E path queries go through Neptune graph-only (P15-38); `path-evidence` has 42 rows by design (default `--auto-pair-top 4`, C(4,2) = 6 pairs x 7 windows, `Config.scala:188`, `CollaborationPipeline.scala:885-915`), a stated limitation | 60 min cap; 351 requests (`run.sh:44`), about 10 min |
| E mixed smoke (option 1) | `e2`, `e`, `0.45`, `SCHOLARCHAIN_E_MODES=graph,vector,fused SCHOLARCHAIN_E_LIMIT_QUERIES=12` | its own record (distinct `K`), labeled `run_scope=subset-smoke`, 36 requests; record the earlier `ingest-report.json` `sourceRunId` beside A's and check `textRef` run segments | 60 min cap; about 2 min |
| E full (D1 option 2) | `e`, `e`, `0.45`, empty | all three modes, 1,053 requests, labeled mixed in the results | 60 min |
| F | `f`, `f`, `0.25`, empty | worker 1/1; embedding DLQ `0 0` (the DLQ lines of the P.1 command); chunk file sha256 `1ccaefcb...0c92` (P15-14) | 30 min |
| C | `c`, `c`, `0.60`, empty | F first (its 50 rows are rows of C's file, so C finds those vectors done); `python3 -B datasets/scripts/chunkset.py validate --chunks-dir /Users/winnguyen/scholarchain-data/phase15-chunkset-c` first; embedding DLQ `0 0`; the vector bucket must not hold C's 2,000 vectors (check `worker-outcomes.csv` before reading throughput) | 60 min; collection continues until `RunningTaskCount` is back at 1, about 10 minutes after the drain (`SESSION2.md:46`) |

The E script digest differs from any earlier one (`run.sh` changed tonight): the digest approved is the one printed at E.15. Whether the human's profile may `ecs:RunTask` and `iam:PassRole` for the load client: `SESSION2.md:205` (read-only simulate check, inconclusive for an assumed-role ARN; then the first `run-task`, the smoke, is the test).

Scale-in check (before E and before A4):

```
set -o pipefail; AWS_PROFILE=scholarchain aws ecs describe-services --cluster scholarchain-course-cluster --services scholarchain-course-query scholarchain-course-worker --region us-east-2 --query 'services[].[serviceName,desiredCount,runningCount]' --output text 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-scalein-check.log | tail -n 60; echo "exit: $?"
```

Expected `1 1` for both once scale-in is done. If not, the experiment records it and the limitations say so; the session-down is not delayed beyond the clock.

Collector (generic, read-only; `COLLECT` is `experiments/d-query-load/collect.sh` (D), `experiments/e-retrieval-modes/collect.sh` (E), `experiments/f-failure-recovery/collect.sh` (F), `experiments/c-embedding-backlog/collect.sh` (C); `RUN_DIR` is the `exp-<X>-...` directory the run printed):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; K=d; COLLECT=experiments/d-query-load/collect.sh; RUN_DIR=REPLACE_WITH_RUN_DIR; SCD=/Users/winnguyen/scholarchain-data; source ${SCD}/p15-tn-env.sh; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; if [[ -d "${RUN_DIR}" ]]; then AWS_PROFILE=scholarchain bash ${COLLECT} --run-dir "${RUN_DIR}" 2>&1 | tee ${SCD}/logs/phase15-tn-${K}-collect.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; else echo "RUN_DIR is not an existing directory: STOP"; false; fi; echo "exit: $?"
```

Expected `COLLECT_COMPLETE window=...`; an existing `collected/` is refused; a failed required call keeps `collect-failed.<stamp>/` and can be repeated.

E gap-note count after the E collector (the sources are `evidence.uncertainty` and the raw-line field `vector_gap_note`, which is true only on a real vector read failure; graph mode never carries it):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; RUN_DIR=REPLACE_WITH_E_RUN_DIR; F=${RUN_DIR}/collected/loadclient-logs.json; if [[ -f "${F}" ]]; then echo "RAW_LINES=$(grep -o LOADCLIENT_RAW ${F} | wc -l | tr -d ' ')"; echo "LINES_WITH_vector_gap_note_FIELD=$(grep -o 'vector_gap_note' ${F} | wc -l | tr -d ' ')"; echo "GAP_NOTE_TRUE_COUNT=$(grep -o -E 'vector_gap_note[^a-z]{1,6}true' ${F} | wc -l | tr -d ' ')"; else echo "loadclient-logs.json missing: gap notes NOT RECORDED"; false; fi 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-e-gap-count.log | tail -n 10; echo "exit: $?"
```

UNVERIFIED: the wire key of `evidence.uncertainty` is unseen (`tonight-experiment-report.md` risks) and the collected file may hold the raw lines JSON-escaped (the pattern tolerates up to six non-letter characters). Derive scripts (no AWS) are the commands of the D, E, F, C READMEs (`d-query-load/README.md` step 3, `e-retrieval-modes/README.md` step 3 with `--group-by mode`, `f-failure-recovery/README.md` step 4, `c-embedding-backlog/README.md` step 4); they may run after the teardown.

---

## Hard stop and session-down (H): starts by 01:15 local (07:15Z on 2026-11-18) whatever the state

H.0 At 01:15 local (D4): run G; then H.1. A finished experiment is kept; one not started is recorded `NOT RUN, hard stop` with the clock reading. C3 means nothing should be running; if an experiment still is, the human decides in the chat (no apply during an experiment). Any collector not yet run: run it now, before H.3 (C4).

H.1 Plan the teardown (`NAME=tn-s4`), var files `course` + `session-down.tfvars` + `session-down-services.tfvars` + `session2.tfvars`, in this order (`SESSION2.md:72,75`; `session-down-services.tfvars:1-5`). Never `services-session.tfvars`, never a size overlay. P above with `OV=(${ENVDIR}/session-down.tfvars ${ENVDIR}/session-down-services.tfvars /Users/winnguyen/scholarchain-data/session2.tfvars)`.

Predicted (`tonight-infra-report.md`; UNVERIFIED): `2 to add, 7 to change, 31 to destroy` (a variant `3 to add, 7 to change, 32 to destroy` if the provider replaces the query service on removing its `load_balancer` block: not a stop). DESTROY: 9 interface endpoints, the 7 Neptune-module resources (the Neptune cluster and instance are stateful), the worker service, the ALB (3 resources) and the p95 alarm, 2 scaling targets, 4 scaling policies, 2 worker alarms; the query and loader task definitions are replaced (`SCHOLARCHAIN_BEDROCK` on to off, `SCHOLARCHAIN_NEPTUNE_HOST` to empty: expected, not a stop). `STATEFUL_CHANGE=yes`: record `STATE_ACK=yes`.

IAM (`SESSION2.md` section 5b): 3 in-place narrowings (`aws_iam_role_policy.runtime` loses `NeptuneReadOpenCypher`, `NeptuneEngineStatus`, `BedrockInvokeExactModels`; `aws_iam_role_policy.loader` loses `NeptuneBulkLoad`, `BedrockEmbeddingsOnly`; `aws_iam_role_policy.worker[0]` loses `BedrockEmbeddingsOnly`) and 2 destroys, the bulk-load role and its policy (expected, they go with Neptune). The worker role and its policy stay. D5 line: `IAM DIFF APPROVED tn-s4: three narrowing policy updates; bulk_load role and policy destroyed with Neptune; worker role kept`. Variant (a) types instead: `IAM DIFF APPROVED tn-s4a: runtime and loader policies narrowed (two in-place updates); bulk_load role and policy destroyed with Neptune; no worker role exists`. There is no variant (b): if A1 never applies (no `DEPLOY_COMPLETE` for `tn-s1` and a drift re-plan shows nothing created), nothing is up, so no session-down is planned or applied; run only H.4 (the read). A half-applied A1 (the apply failed after creating some resources) is not "never applied": re-plan the drift under `tn-s1-2` (section 3), and if the drift shows resources created, the teardown uses the variant (a) var files (`session-down.tfvars` only, `NAME=tn-s4a`). The human reads the drift counts and the action lines BEFORE any record is written: the (a) row's `EXP` assumes a fully applied A1, so reader R may fail on a partial state; set `EXP` to the printed counts only after the human has read the actions against what was created, and keep every stop of variant (a). No record is written until that reading is done and typed in the chat.

The only stops for the A4 plan: a memory validation error; a change of the query task definition `cpu` (512 to 256, E.5 lines missing); the destroy or replacement of a log group, an ECR repository, `aws_iam_role.worker[0]` or its policy, a dashboard or a security group; any create other than the 2 task definitions (3 in the variant: the query service). The two task-definition replacements, the bulk-load destroys, `STATEFUL_CHANGE=yes` and the counts above are not stops.

The variant is chosen by the LAST SUCCESSFUL APPLY, read from the `DEPLOY_COMPLETE` lines of `phase15-tn-s2-deploy.log` and `phase15-tn-s1-deploy.log` (a failed or half-applied apply does not count: re-plan it as in section 3 and read the drift first). Sources: infra-engineer, `SESSION2.md` section 5b (early-stop variant; counts unchanged by the down-state recomputation). In variant (a) the var files are `course` + `session-down.tfvars` ONLY: never `session-down-services.tfvars` (it sets `enable_services_stack = true` and would CREATE the stack, `session-down-services.tfvars:17`) and never `session2.tfvars`.

| Last successful apply | Var files (P with this `OV`) | `NAME` | `EXP` | IAM | `STATE_ACK` |
|---|---|---|---|---|---|
| A2 or A3 (stack exists): the main case above | `session-down.tfvars`, `session-down-services.tfvars`, `session2.tfvars` | `tn-s4` | `2 to add, 7 to change, 31 to destroy` (`EXP2` the 3/7/32 variant) | 3 narrowings + 2 destroys (bulk-load role and policy) | `yes` |
| (a) A1 applied, A2 never reached | `session-down.tfvars` only | `tn-s4a` | `3 to add, 6 to change, 19 to destroy` | 2 in-place narrowings (runtime, loader) + 2 destroys (`module.neptune[0].aws_iam_role.bulk_load` and its policy); no worker role exists | `yes`: Neptune cluster and writer are destroyed, and the human's record line must say so: `STATEFUL CHANGE ACKNOWLEDGED tn-s4a: Neptune cluster and writer destroyed` |

For (a) use `OV=(${ENVDIR}/session-down.tfvars)` in the generic plan command, and the reader R with that row's `NAME`, `EXP`, `EXP2` equal to `EXP`, and `STATE`. Stop in variant (a) (nothing applied): any created role or ECR repository, or any destroyed log group (a services file leaked), plus any other IAM line than the row's. Other stops of the A4 plan above do not apply to (a), which has no query service.

H.2 Reader R with the `NAME`, `EXP`, `EXP2` and `STATE` of the variant row of H.1 (main case: `NAME=tn-s4`, `EXP="2 to add, 7 to change, 31 to destroy"`, `EXP2="3 to add, 7 to change, 32 to destroy"`, `STATE=yes`). Read the action lines against H.1.

H.3 Deploy down: DP, DW (`STATE_ACK` as in the variant row), DD, DR with the variant's `NAME`. Expected `DEPLOY_COMPLETE`, `DEPLOYED_REVISION=<HEAD>`, exit 0. A failure with the stack still up bills about 0.26 to 0.37 USD an hour (`COST_ESTIMATE.md:118`, services up) plus the xlarge-free Neptune: report at once, re-plan under the variant's name with a `-2` suffix (`tn-s4-2` or `tn-s4a-2`), decide within 15 minutes.

H.4 Post-teardown read-only check:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; echo "--utc ${STAMP}"; echo "--emr-active-clusters (expect empty)"; aws emr list-clusters --active --region us-east-2 --query 'Clusters[].[Id,Name,Status.State]' --output text; echo "--emr-clusters-last-24h (expect B TERMINATED)"; aws emr list-clusters --created-after "$(date -u -v-24H +%Y-%m-%dT%H:%M:%SZ)" --region us-east-2 --query 'Clusters[].[Id,Name,Status.State]' --output text; echo "--ecs-running-tasks (expect none)"; for C in $(aws ecs list-clusters --region us-east-2 --query 'clusterArns[]' --output text); do echo "cluster=${C##*/}"; aws ecs list-tasks --cluster "${C}" --desired-status RUNNING --region us-east-2 --query 'taskArns[]' --output text; done; echo "--ecs services (expect query desired 0 running 0, no worker)"; aws ecs describe-services --cluster scholarchain-course-cluster --services scholarchain-course-query scholarchain-course-worker --region us-east-2 --query 'services[].[serviceName,desiredCount,runningCount,status]' --output text; echo "--neptune-clusters (expect empty)"; aws neptune describe-db-clusters --region us-east-2 --query 'DBClusters[].[DBClusterIdentifier,Status]' --output text; echo "--neptune-instances (expect empty)"; aws neptune describe-db-instances --region us-east-2 --query 'DBInstances[].[DBInstanceIdentifier,DBInstanceClass,DBInstanceStatus]' --output text; echo "--vpc-endpoints (expect only Gateway s3 and dynamodb)"; aws ec2 describe-vpc-endpoints --region us-east-2 --query 'VpcEndpoints[].[VpcEndpointType,ServiceName,State]' --output text; echo "--nat-gateways (expect empty)"; aws ec2 describe-nat-gateways --region us-east-2 --filter "Name=state,Values=pending,available" --query 'NatGateways[].[NatGatewayId,State]' --output text; echo "--elastic-ips (expect empty)"; aws ec2 describe-addresses --region us-east-2 --query 'Addresses[].[AllocationId,AssociationId]' --output text; echo "--load-balancers (expect empty)"; aws elbv2 describe-load-balancers --region us-east-2 --query 'LoadBalancers[].[LoadBalancerName,Type,State.Code]' --output text; echo "--ebs-volumes-unattached (expect empty)"; aws ec2 describe-volumes --region us-east-2 --filters "Name=status,Values=available" --query 'Volumes[].[VolumeId,Size]' --output text; echo "--ec2-instances-pending-or-running (expect empty)"; aws ec2 describe-instances --region us-east-2 --filters "Name=instance-state-name,Values=pending,running" --query 'Reservations[].Instances[].[InstanceId,InstanceType]' --output text; echo "--sfn-running-executions (expect empty)"; aws stepfunctions list-executions --state-machine-arn "$(aws stepfunctions list-state-machines --region us-east-2 --query "stateMachines[?name=='scholarchain-course-pipeline'].stateMachineArn | [0]" --output text)" --status-filter RUNNING --region us-east-2 --query 'executions[].[name,status]' --output text; echo "--dlq depths (record; F's poison message is expected in the embedding DLQ)"; for Q in run-requests embedding-work; do aws sqs get-queue-attributes --queue-url "$(aws sqs get-queue-url --queue-name scholarchain-course-${Q}-dlq --region us-east-2 --query QueueUrl --output text)" --attribute-names ApproximateNumberOfMessages --region us-east-2 --output text; done ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-tn-down-confirm-${STAMP}.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 70; echo "exit: $?"
```

Expected: no active EMR cluster, no running task, no Neptune cluster or instance, only Gateway endpoints for S3 and DynamoDB, no NAT, Elastic IP, load balancer, unattached volume or running instance. A services section with a usage error: read it as "no services". Stop (report at once): any active cluster, interface endpoint, Neptune, ALB, Elastic IP, running instance or unattached volume; a leftover EMR cluster is the S3 emergency. After the teardown the idle items are storage, ECR, secrets, log groups and DLQ messages.

H.5 Redaction copies and paste-back (fallback-style; one generic redaction command per run directory, the control is the final `grep`; the raw directories stay as written; the copies are committed only after H.4; `p15-tn-env.sh` is never copied):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; SRC=REPLACE_WITH_RUN_DIR; L=REPLACE_WITH_LABEL; COPY=${SCD}/results-copy/p15-tn-${L}; if [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ && -d "${SRC}" && "${L}" != "REPLACE_WITH_LABEL" ]]; then mkdir -p ${SCD}/results-copy && cp -R "${SRC}" "${COPY}" && chmod -R u+w "${COPY}" && find "${COPY}" -type f -exec sed -i.bak "s/${ACCOUNT_ID}/000000000000/g" {} + && find "${COPY}" -name '*.bak' -delete; if grep -rq "${ACCOUNT_ID}" "${COPY}"; then echo "REDACTION_FAILED: the account id is still in ${COPY}"; false; else echo "REDACTION_OK: the account id is not in ${COPY}"; fi; else echo "no 12-digit account id, no run directory or no label: STOP"; false; fi 2>&1 | tee ${SCD}/logs/phase15-tn-redact-${L}.log | tail -n 60; echo "exit: $?"
```

Run it once per run directory (B from P.2, D, E, F, C and each E subset) with its label. The copy's `SHA256SUMS` no longer matches after redaction; the raw directory is the source of truth. Paste-back copy of the logs (redacted, one file):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; if [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ ]]; then (for F in ${SCD}/logs/phase15-tn-*.log; do echo "=== ${F##*/}"; tail -n 30 "${F}"; done) | sed "s/${ACCOUNT_ID}/000000000000/g" > ${SCD}/logs/phase15-tn-paste-back.txt; wc -l ${SCD}/logs/phase15-tn-paste-back.txt; else echo "no 12-digit account id from STS: STOP"; false; fi 2>&1 | tee ${SCD}/logs/phase15-tn-paste-back-run.txt | tail -n 60; echo "exit: $?"
```

Do not paste: approval files (the W logs only), `p15-tn-env.sh`, `session2.tfvars` beyond the lines shown, raw definition files, any `s3://` bucket name.

H.6 After H.4 the orchestrator may write the repository (S1 ends): `docs/agent-runs/phase-15/session-tonight/` with `evidence.md` (pasted tails per step with UTC times and a ledger with one row per approval record: action, plan digest, script digest, args digest, cost bound, rollback gate, approved-by, approved-at, the D3 typed gate line with its LAG value, the D5 IAM approval lines and the D8 ruling or `sbt check` line), the P.2 and P.3 outputs for the afternoon B run, the experiment result files (limitations P15-2, P15-11, P15-12, P15-20, P15-23, P15-38, D1). Deploy-engineer writes the release records afterwards from the logs and records, no command run: `docs/releases/phase-15/deploy-tn-s1-a1.md`, `deploy-tn-s2-a2.md`, `deploy-tn-s3-a3.md`, `deploy-tn-s4-down.md`, `smoke-tn.md` and an outcome README. The actual billed cost is read from Billing the next day and recorded against section 2 (LAG and the 4.50 HIGH against the real bills). A release record for the afternoon B session is outside this runbook (PENDING decision, orchestrator).

---

## Stop rules (summary)

| Situation | Action |
|---|---|
| Billing gate STOP | Nothing that bills starts; the drop list (C, F, E, D) is printed; never raise 14; LAG above 4.00 is a STOP (section 2) |
| Section P finds a non-zero DLQ, an active cluster or an interface endpoint | Report; no session-2 plan from the down state; a leftover EMR cluster is the S3 emergency |
| Any gate `FAIL` before the A1 apply (DR, `tn-s1`) | Report, wait; nothing billable has run |
| Any failure from the A1 apply on | Go to H, unless the human decides otherwise in writing within 15 minutes with an end time (S2) |
| A1 never applies | Nothing is up: no session-down; run H.4 only |
| A plan with an unexpected IAM line, a created or destroyed role other than the ones named per plan, a replaced Neptune instance, or a stack resource where none belongs | Do not approve; route to infra-engineer through the orchestrator |
| A4 plan | Stops only as listed in H.1 (memory validation error, `cpu` change, a destroyed log group, ECR repository, worker role or policy, dashboard or security group, an unexpected create); the task-definition replacements and the bulk-load destroys are expected |
| B derive shows no scale-in (P.3) | The result stands; no rerun; D's ECS scaling is the second source (P15-11) |
| Reload `NEPTUNE_LOAD=FAILED`, `BoundExceeded` or FAILED events | No retry without the human's decision; smoke, D, E NOT RUN; F and C may run |
| Load-client image not at HEAD in ECR (S.5b) | No smoke, D or E |
| Smoke failed or Bedrock unavailable | Smoke recorded FAILED or NOT RUN; D and E need the human's decision |
| Experiment would not finish by 00:55 local | NOT RUN, recorded with the clock reading |
| A run in progress at 01:15 | The human decides in the chat; no apply during a run |
| A leftover EMR cluster | S3 emergency (E1 lookup and terminate) |
| Spend estimate over the limit (section 2 or S.0) or `SPEND_ESTIMATE_EXCEEDED` | Drop in order or go to H |

## Register of PENDING and UNVERIFIED

PENDING (owner): human decisions D1, D2, D3 (LAG), D4, D6, D8 and the D5 lines; a fresh review of revision 4 (code-reviewer); `session-ab/evening-list.md` update (orchestrator); the B-session release record decision (orchestrator). `session-b-only-runbook.md` is superseded for B by P15-42 (B ran from adapted A + B commands).
UNVERIFIED: the local offset (E.1 prints it); the plan counts of every apply (predicted from code, first plans are the test); the `ARGS_CANONICAL` context form (DC); the A1 counts `19 to add, 6 to change, 3 to destroy` (relayed from infra-engineer, the plan is the test); that the B collector still works after the cluster terminated and the endpoints were destroyed (P.2); the B run directory path `/Users/winnguyen/scholarchain-data/experiments/runs/exp-B-20261117T185928Z-5c69ba90` (taken from the run output, not confirmed by a listing; P.2 and P.3 fail closed if it is absent); the new DLQ `--query` form in P.1; the 0.12 USD/h endpoint rate behind LAG; the allowlist and comment-only regexes (the fallback's); `plan.sh --out-dir` creation; `module.identity` address; the stand-alone `run-bounded-pipeline.sh` output location; log field names for `NEPTUNE_LOAD`, `FAILED` and the smoke `status`; `load_client` output key names; the run-ledger table name; the ECR repository URL; `--platform linux/amd64` with the Dockerfile; whether Haiku is called in graph mode and retries add calls (P15-27); the `evidence.uncertainty` wire key; every Region rate in `COST_ESTIMATE.md` (HIGH figures are estimates, not bills); a Neptune downsize restart (`graph-load.tfvars:23-31`); `db.r6g.xlarge` orderability in the writer's AZ; the openCypher `QueryLanguage` condition key (`SESSION2.md:113`); the `terminate-clusters` verb.
