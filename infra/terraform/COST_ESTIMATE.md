# ScholarChain cost estimate and three-size plan (Phase 12, work order 06, restated in repair round 1, corrected in repair round 2)

Author: infra-engineer. Status: estimate only. Nothing was planned, applied or priced against AWS. Binding inputs: decisions D2, D2a, D2b, D2c, D3, D4, D6, D7; `pricing-evidence.md` (the only price source); report-04; `infra/terraform/README.md`.

Repair round 2 (finding N1, ruling D7): the earlier version counted Neptune hours only for runs 14.0, 15.1, 15.2 and part of 15.5, but every size overlay sets `enable_neptune = true`, so a session brought up with an overlay bills Neptune for the whole session. It also left out the service tasks that the M overlay starts in 14.2 and 15.3. Both are added below, and every total that depended on them is recomputed. The review named 7.6 LOW and 15.7 HIGH missing Neptune hours (runs 14.1, 14.2, 15.3, 15.4). This version adds 10.6 and 21.2, because run 15.5 also has Neptune up for the whole 6 to 10 hour session under the L overlay and not only for the 3 to 4.5 hour query window. **The result: S + M at the HIGH bound is now $25.42, which is over the $25 ceiling.** The overlays and the Terraform defaults were not changed.

Ceiling (restated by Phase 14 decisions D1 and D6): the code ceiling is **$20** cumulative (`SC_PROJECT_CEILING_USD`, the `budget_total_usd` validation). It matches the AWS-enforced project spend limit of $20 (pause idle resources on) and the $20 of credits. The AWS Budget of $25 with alerts at $10, $15 and $20 is the outer alert and only notifies. The human's operating stop is near $15 of actual spend. The figures and the text below were written against the earlier $25 ceiling: every "$25" in sections 0 to 8 that is not marked is that earlier ceiling, and "Cost ceiling lowered to 20 USD" below says what each total means against $20. `create_budgets` stays false.

This version is restated for what Terraform now defaults to: **interface-endpoint egress with 8 endpoints (9 when Bedrock is on), provisioned Neptune `db.t4g.medium`, Cloud Map off, absolute alert thresholds**. The earlier version priced NAT, which the human rejected in D2c, so its totals are replaced. NAT is shown as an alternative column for comparison. This document does not choose it.

## Region migration to us-east-2 (Phase 14, work order 01)

The account's Region is LOCKED to us-east-2 (Ohio) (Phase 14 decision D1). This section adds a Region audit of every region-sensitive line. **No cost figure, total, cap or projection in this document was changed by this audit.** Only rates that could be tied to a us-east-2 page are marked verified, and only one is (V9, the NAT example, which the VPC pricing page states for US East (Ohio)). Every total below was computed from rates gathered for us-east-1 or for pages that state no Region, so every total is carried over unchanged and flagged: it is an estimate on unverified rates for us-east-2.

The new account's limits are tighter than the figures below assume (D1: USD 20 credits, an AWS-enforced USD 20 spend limit with idle-resource pause, an AWS Budget of USD 25, stop near USD 15). Work order 01 left the $25 ceiling unchanged and reported it BLOCKED-ON. The human decided (D6) to lower the code ceiling to $20; work order 02 changed `SC_PROJECT_CEILING_USD`, the `budget_total_usd` validation and the docs, and added "Cost ceiling lowered to 20 USD" below. No rate, total, cap or tier size was changed by either work order.

Marks: `us-east-2 verified` = a us-east-2 or Ohio figure was read on a cited page. `us-east-2 unverified` = the stated rate is for us-east-1 or for no stated Region and nothing for us-east-2 was read. `unchanged (Region-neutral)` = a fact that does not depend on the Region.

| ID or line | What it is | Mark | Cited page or reason |
|---|---|---|---|
| V1 | Neptune bills per second in NCUs, 1 NCU starting capacity | unchanged (Region-neutral) | billing model, aws.amazon.com/neptune/pricing. The per-NCU rate is U2 |
| V2 | Neptune db.r5.large $0.348 an hour | us-east-2 unverified | aws.amazon.com/neptune/pricing example, Region not stated |
| V3 | Neptune storage $0.10, I/O $0.20, CPU credits $0.15 | us-east-2 unverified | same page, Region not stated |
| V3 | Neptune Free Tier terms (750 hours, 10 M I/O, 1 GB, 30 days) | unchanged (Region-neutral) for the terms; whether a Paid-plan account gets it is still unverified (P3) | `instance-types.html`, evidence-availability-us-east-2.md |
| V4 | EMR charge c4.2xlarge $0.105 an hour "(US-East-1)" | us-east-2 unverified | aws.amazon.com/emr/pricing, source label kept as recorded |
| V5 | EMR Serverless per vCPU-hour and GB-hour | us-east-2 unverified | same page, Region not stated. Not used (Hadoop stage is MapReduce) |
| V6 | Spot "up to 90 percent off" | unchanged (Region-neutral) | aws.amazon.com/ec2/spot/pricing, no price |
| V7 | S3 Vectors storage, PUT, query rates "(US East N. Virginia)" | us-east-2 unverified | aws.amazon.com/s3/pricing, source label kept as recorded. The service itself is available in us-east-2 (endpoint `s3vectors.us-east-2.api.aws`, s3-vectors-regions-quotas.html); only the rate is unread |
| V8 | Fargate per vCPU-second and GB-second | us-east-2 unverified | aws.amazon.com/fargate/pricing, Region not stated |
| V9 | VPC gateway endpoints free; NAT $0.045 an hour and $0.045 per GB | us-east-2 verified | aws.amazon.com/vpc/pricing, the example is US East (Ohio). This is the deployed Region now |
| V10 | CloudWatch alarm $0.10, 3 dashboards free | us-east-2 unverified | aws.amazon.com/cloudwatch/pricing, Region not stated |
| V11 | Step Functions $0.000025 per transition, 4,000 free | us-east-2 unverified (rate); the free allowance is unchanged (Region-neutral) | aws.amazon.com/step-functions/pricing |
| V12 | SQS first 1 M requests free | unchanged (Region-neutral) | aws.amazon.com/sqs/pricing |
| V13 | Secrets Manager $0.40 per secret, $0.05 per 10,000 calls | us-east-2 unverified | aws.amazon.com/secrets-manager/pricing, Region not stated |
| V14 | KMS $1 per key (no key is created) | unchanged (Region-neutral) | not used |
| V15 | Bedrock batch inference 50 percent for select models | us-east-2 unverified | aws.amazon.com/bedrock/pricing. Not used |
| U1 | EC2 + EMR m5.xlarge $0.22 to $0.35 per node-hour | us-east-2 unverified | memory. EC2 offers m5.xlarge in us-east-2 (human read-only CLI check, 2026-11-13, `docs/agent-runs/phase-14/checks-3.md`); the EMR page was not read and the first CreateCluster confirms it |
| U2, U3 | Neptune Serverless NCU-hour, db.t4g.medium hour | us-east-2 unverified | memory. `db.t4g.medium` is confirmed orderable in us-east-2 by a human read-only CLI check on 2026-11-13 (`docs/agent-runs/phase-14/checks-3.md`); the rate itself stays unverified |
| U4 | NAT gateway hourly | us-east-2 verified for the $0.045 LOW value (V9, Ohio). The $0.052 HIGH value (Ohio plus 15 percent) is an assumption and stays flagged | aws.amazon.com/vpc/pricing |
| U5 | Public IPv4 for the NAT | us-east-2 unverified | memory |
| U6 | Interface endpoint per endpoint per AZ-hour, and per GB | us-east-2 unverified | memory. Per-hour and per-AZ billing is Region-independent in structure (R3) |
| U7 | S3 Standard storage and requests | us-east-2 unverified | memory |
| U8 | DynamoDB on-demand write, read, storage | us-east-2 unverified | `pricing-evidence.md`, summary only |
| U9 | Claude Haiku 4.5 per million tokens | us-east-2 unverified | memory. In us-east-2 the model is reached only through a cross-Region profile (evidence-availability-us-east-2.md); the profile price premium is unknown |
| U10 | Titan Text Embeddings V2 per million tokens | us-east-2 unverified | memory. In-Region support in us-east-2 is confirmed (availability only) |
| U11 | CloudWatch Logs ingestion per GB | us-east-2 unverified | memory |
| U12 | Cloud Map hosted zone | us-east-2 unverified | memory. Off by default |
| U13 | ECR storage per GB-month | us-east-2 unverified | memory |
| Section 2.3 | Endpoints versus NAT figures | us-east-2 unverified (endpoint side); NAT LOW side verified through V9 | derived from U4, U5, U6 |
| Section 3, 3a | Timeline, run costs, S + M and L totals, gate G-L figures ($10.18, $20.68) | us-east-2 unverified | derived from the rates above. Carried over unchanged |
| Section 4 | Totals by service ($10.32 LOW, $38.70 HIGH) | us-east-2 unverified | carried over unchanged. Not recomputed: a total recomputed from unverified rates would only look verified |
| Section 5, 8 | Threat table and open items | us-east-2 unverified | the item 4 and 6 rows now name us-east-2 |
| Data transfer | Interface-endpoint and NAT per-GB processing, cross-AZ charges with one endpoint AZ | us-east-2 unverified | volumes are assumptions in any Region |
| Bedrock cross-Region data transfer | Not priced anywhere in this document | unchanged (not modelled) | the US profile may route a request to us-east-1 or us-west-2 (evidence-availability-us-east-2.md); whether that adds a charge is unknown |

Counts: 2 lines us-east-2 verified (V9, and the LOW half of U4), 6 unchanged (Region-neutral) (V1, V6, V12, V14, the V3 and V11 terms), every other line unverified. Caps: this Region audit changed none of `pipeline_max_run_seconds`, the three step timeouts or the script `--max-minutes` caps (60, 90, 150). Work order 49 later raised the S overlay's execution timeout to 7200 s and its on-node step timeout to 3600 s, and work order 52 (D32) raised the S step timeout to 4800 s and the on-node Spark and entity-resolution limits to 3000 s (M and L overlays unchanged); no figure here changed with any of them. Closing the unverified lines needs the price-list queries in section 8, now pointed at us-east-2.

## Cost ceiling lowered to 20 USD (Phase 14 decisions D1 and D6, work order 02)

The code ceiling is now $20, the human's operating stop is near $15, and the AWS Budget of $25 is the outer alert. This section applies those three lines to the existing figures. **No figure was recomputed and no tier, overlay or cap was changed.** Every number below is copied from section 3 (timeline running totals), section 3a and section 4, and carries their status: rates mostly UNVERIFIED for us-east-2. The HIGH bound stacks every upper bound at once and is the stress case, not a forecast. The decision is the human's; this table only shows where the existing HIGH bound lands.

| Tier or cumulative point | LOW | HIGH | HIGH against 20 | HIGH against the 15 stop |
|---|---|---|---|---|
| Foundation 14.0 and tier S pipeline 14.1 (running total) | 1.44 | 5.30 | under by 14.70 | under by 9.70 |
| plus tier M pipeline 14.2 (running total) | 2.19 | 8.38 | under by 11.62 | under by 6.62 |
| plus 15.1 and 15.2 (queries on S and M, running total) | 3.54 | 14.20 | under by 5.80 | under by 0.80 |
| plus 15.3 elasticity on M (running total) | 4.76 | 18.64 | under by 1.36 | **over by 3.64** |
| **S + M complete, through 15.4 load test (running total)** | 7.46 | **25.42** | **over by 5.42** | **over by 10.42** |
| Tier L increment alone (15.5) | 2.82 | 13.32 | under by 6.68 taken alone (it runs after S + M, so the running total decides) | under by 1.68 taken alone |
| **Whole project S + M + L (section 4 service table)** | 10.32 | **38.70** | **over by 18.70** | **over by 23.70** |

Reading of the table. At LOW, everything is under 20 and under 15 (the whole project is $10.32). At HIGH, S + M does not fit under 20: the running total passes $15 during 15.3 and passes $20 during 15.4, and tier L is far past it. These are `BLOCKED-ON` the human, who decides what to do (nothing in this work order shortens a run, drops a tier or changes an overlay).

Alternatives already priced in section 3a, restated against 20 (all HIGH, S + M only): P1 (pipeline-only overlay with Neptune off for 15.3 and 15.4) 24.22, over by 4.22; P1 with P2 24.07, over by 4.07; P3 (Neptune Free Tier, conditional) 23.00, over by 3.00; P4 (NAT instead of endpoints) 23.41, over by 3.41. **None of them brings S + M under $20 at the HIGH bound.** Neither does the earlier unitemized-NAT-share reading (about $24.74). Only a smaller plan would: for example S plus a shorter M, or no 15.3 and 15.4 sessions, which is a scope decision and is not drawn here.

Gate G-L, restated. The gate was "run L only if A plus the L estimate is at most $23.5" ($25 minus a $1.5 reserve for teardown and billing lag). Against the new ceiling it is "at most $18.5" ($20 minus the same $1.5), and the human's operating stop near $15 is tighter than that. Derived arithmetic, not a new estimate: at the HIGH rates L costs $13.32, so L passes only if A is at most 18.5 - 13.32 = 5.18; at the LOW rates L costs $2.82, so it passes while A is at most 18.5 - 2.82 = 15.68. A after S + M is 25.42 at HIGH (no) and 7.46 at LOW (yes). The earlier figures $10.18 and $20.68 (against $23.5) are superseded. Against the $15 stop instead of $18.5 the thresholds fall by $3.50 more, to 1.68 (HIGH) and 12.18 (LOW).

What the figures leave out. The credits ($20) and the AWS spend limit ($20) apply on a basis the budget alerts do not (the Terraform budget excludes credits, so it counts gross usage). Whether the spend limit counts gross or net usage, and what "pause idle resources" does to a running EMR cluster or Neptune cluster when the limit is reached, are UNVERIFIED and not read from AWS documentation in this work order. An idle endpoint day is $1.92 to $3.24, so a forgotten session consumes about 10 to 16 percent of the $20 ceiling per day (LOW to HIGH rates).

## Loader and Bedrock endpoint (Phase 14, work order 31)

Added without recomputing any total in sections 0 to 8. The S overlay now sets `enable_bedrock = true`, which adds the `bedrock-runtime` interface endpoint (a ninth) so the loader can call Titan Text Embeddings V2 in-Region. Rate: U6, 0.010 to 0.015 USD per endpoint per AZ-hour, UNVERIFIED for us-east-2, one AZ. Delta: **+0.010 to +0.015 USD per session hour** on the S overlay. Endpoint hours in this document's totals were counted for 8 endpoints (section 2.3); a session on the S overlay now bills 9, so each S session hour is 0.010 to 0.015 USD above the figure used, and the idle endpoint day without a session-down is 2.16 to 3.24 USD (9 endpoints) instead of 1.92 to 2.88 (8). The M and L overlays do not set `enable_bedrock`, so their figures are unchanged until they do; a loader run under them cannot embed without it.

Hourly burn of an S session on the S overlay, same unverified rates (README, "Loader (work order 31)", has the table and arithmetic): with Neptune and Bedrock 0.37 to 0.585 USD an hour while the single EMR node is up and 0.18 to 0.27 while only the loader runs; with Neptune off 0.31 to 0.485 and 0.12 to 0.17. Neptune is 0.06 to 0.10 of that (U3). The loader's Fargate task is about 0.03 an hour (memory of the rate, unverified), and EMR is terminated before the loader starts. These do not change the HIGH bound of section 3 by more than the endpoint delta times the S session hours (1 to 2 hours LOW to HIGH for 14.1 in section 1.2): at most +0.03 USD, inside the noise of the unverified rates. Titan token cost is volume-based (section 4) and unchanged.

Neptune-off S session: saves 0.06 to 0.10 USD an hour of Neptune plus its creation wait, but gives up the Neptune bulk load that decision D17 requires. Treat it as a rehearsal or fallback.

## 0. Read this first

1. Most rates here are UNVERIFIED. The pricing pages gave worked examples only. The biggest lines (EC2 and EMR hours, Neptune, DynamoDB writes), Bedrock tokens and the interface-endpoint rate rest on agent memory. "LOW" below is the remembered-rate case, not a verified floor. "HIGH" stacks every upper bound at once, which is unlikely but is the check the work order asks for.
2. Run hours, pairs per work and I/O counts are assumptions, not measurements. They matter as much as the rates. The first live run on tier S replaces them with counters (section 8).
3. Headline, whole remaining project (Phases 14 and 15), tear-down of compute and network after each session (section 3a):

| Case | LOW | HIGH (all upper bounds at once) | Basis |
|---|---|---|---|
| **D, defaults as built (endpoints), tiers S + M + L** | **$10.32** | **$38.70** | section 4 service table |
| D, tiers S + M only | $7.46 | **$25.42** | section 3 timeline |
| D, tier L increment alone | $2.82 | $13.32 | section 3 timeline |
| NAT alternative (not chosen), S + M + L | $9.62 | $36.64 | section 4, NAT column |
| NAT alternative, S + M only | $6.86 | $23.41 | section 3 timeline less the endpoint-to-NAT difference |
| Endpoints minus NAT, whole project | +$0.70 | +$2.06 | section 2.3 |
| Neptune Serverless instead of provisioned (selectable) | +$1.52 | +$12.88 | Neptune lines only, section 4 note |

4. What the human's D2c choices and the overlays imply, stated at the HIGH bound:
   - **S + M at HIGH does not fit under $25. It is $25.42, which is $0.42 over the ceiling.** The earlier version said $23.71 and "fits"; that was wrong because it left out Neptune hours that the overlays switch on (7.6 hours LOW, 15.7 hours HIGH in runs 14.1, 14.2, 15.3 and 15.4) and the service-task hours of the M overlay. At LOW, S + M is $7.46 and fits with $17.54 to spare. If the earlier timeline rows already held a NAT data-processing share (not itemized, unknown), S + M HIGH is up to $0.68 lower, about $24.74; that would be under $25 by $0.26, which is thinner than any single UNVERIFIED rate, so it is not a fit proof either. The HIGH bound stacks every upper bound, so $25.42 is the stress case and not a forecast, but this document no longer claims a fit at HIGH.
   - **The whole project with tier L is $38.70 at HIGH**, $13.70 over $25 (it was $36.44). It does not fit at HIGH. At LOW it is $10.32 and leaves $14.68. At LOW the first $10 alert now fires during the L run (the LOW path ends above $10).
   - **The tier-L gate figure** (the $23.5 line, section 3): L costs $13.32 at HIGH (Neptune is up for the whole L session, 10 hours HIGH), so the gate passes only if the cumulative spend A before L is at most $10.18 (it was $10.73). At HIGH rates A after S + M is $25.42, so L would not run, and S + M alone is already over the ceiling. At LOW rates L costs $2.82 and the gate passes while A is at most $20.68 (it was $20.86); A after S + M is $7.46.
   - **Interface endpoints are more expensive than NAT** by $0.70 LOW and $2.06 HIGH over the project, with 8 or 9 endpoints. That is the cost of "no internet path at all" and of working S3 Vectors and SQS calls from the private subnets. With NAT, S + M HIGH is $23.41, which fits with $1.59 of margin. If the human prefers the thinner HIGH margin of NAT, that is the human's call; this document only shows the figures.
   - **Cheaper options, proposals only, none built and no overlay or default changed in this round** (arithmetic in section 3a): a pipeline-only overlay with Neptune off for the elasticity and load-test sessions (15.3, 15.4), shorter Neptune windows for the load and query sessions, Neptune Free Tier use (eligibility unverified), and NAT instead of endpoints. The first one alone brings S + M HIGH to $24.22, if the stub load test in 15.4 does not need Neptune (UNVERIFIED). No proposal is a proof of fit.
5. The real budget risk is a resource left running, not any single rate. Idle endpoints alone are $1.92 to $2.88 a day (8) or $2.16 to $3.24 a day (9). See section 5.
6. The estimate's HIGH run hours are not a promise that the pipeline caps cover them. The script `--max-minutes` caps (60, 90, 150) and the execution timeouts (S 7200 since work order 49, was 5400; M 7200; L 10800 s; the S step timeout is 4800 s since work order 52) are shorter than one sequential HIGH run on every tier (S HIGH 7200 s plus the terminate allowance), so at the HIGH bound a run may time out. The S run is launched with the 90-minute script tier (`--size medium`), whose stop at 5400 s fires before the 7200 s execution timeout. A timed-out run still wastes the spend it accrued and needs a re-run with a new approval record. A cap is a ceiling, not a cost, and no figure here rises because of it (human decision O1; section 5, threat 11).

## 1. Assumptions

Region us-east-2 (Phase 14 D1; it was us-east-1 under D4, and the rates below were gathered for us-east-1 or Region-unstated pages, see "Region migration to us-east-2"). One account. After each session the compute and network are removed with the session-down overlay (`session-down.tfvars`: egress mode `none`, Neptune off, service tasks 0, applied through `plan.sh` and `deploy.sh`; infra README, "Session up and session down"). The overlay **keeps** storage (S3, DynamoDB tables, S3 Vectors, ECR, secrets), which cost cents idle and would cost more to reload (DynamoDB writes) than to keep. Neptune keeps no snapshot in the course environment, so each session reloads its graph from S3; that reload is inside the Neptune session hours below. `cleanup.sh` is the end-of-project full teardown only; it deletes storage and is not the between-session step. Each session needs one approval record for the down apply and one for the next up apply.

### 1.1 Three sizes (proposal for the human to confirm; dataset selection belongs to dataset-engineer)

| | S | M | L |
|---|---|---|---|
| Works | 5,000 | 25,000 | 100,000 |
| Raw author pairs (8 per work, before consolidation) | 40,000 | 200,000 | 800,000 |
| Distinct coauthor edges (5 per work) | 25,000 | 125,000 | 500,000 |
| Semantic chunks (1 per 5 works) | 1,000 | 5,000 | 20,000 |
| Vectors, all five indexes (2 per chunk) | 2,000 | 10,000 | 40,000 |
| Raw input from four sources (4 KB per work) | 20 MB | 100 MB | 400 MB |
| DynamoDB items (2 per edge, plus researchers and metrics at 0.3 per work each) | 65,000 | 325,000 | 1,300,000 |

How derived:
- 4 KB per work: the Phase 5 slice (`datasets/slice/`, an untracked path at the time of writing) holds 3 OpenAlex works in 6,357 bytes (about 2.1 KB each), and the four source files plus ORCID total about 12 KB. Source: file sizes read from the repo.
- Pairs: `TeamExpansion` emits exactly n(n-1)/2 pairs per work and suppresses fan-out above `maxPairsPerWork`. The slice caps authors at 4 (6 pairs). The corpus has a heavier tail, so 8 pairs per work is an assumption. Consolidation to 5 distinct edges per work (62 percent) is also an assumption. Both are replaced by the Hadoop counters from run 14.1.
- Vectors at 2 per chunk, items per edge and chunks at 1 per 5 works are assumptions. I did not read `Vectors.scala`, the storage item model or the chunker. Edge count is the largest risk: a random sample of a global graph has fewer repeated pairs than a dense one, so edges per work could be 40 percent higher.
- L equals the old 100,000-works target. At the assumed density it gives 500,000 edges, half the old 1,000,000 target. A measured density of 10 edges per work on S would reach it.

Selection criteria (proposal): nested deterministic prefixes (S inside M inside L) of one sample ordered by a stable hash of the DOI, so that tiers differ only in size. Eligibility follows the Phase 5 spec: a DBLP record whose DOI resolves in OpenAlex and Crossref, years 2008 to 2024, all author counts kept, the pair cap applied and counted. Nesting means embeddings made for L serve S and M as prefixes (the cost lines assume this).

Limitations to document with the results:
- DBLP-anchored, so computer-science biased.
- Random sampling thins the graph. Density and repeated-collaboration share fall as size falls, so size changes structure as well as volume. Scaling curves mix the two effects.
- ORCID coverage is partial. Entity-resolution accuracy is only measurable on the common-name gold set, not on each tier.
- L at 100,000 works is about 1.5 percent of DBLP, so it is a representative subset, not the corpus.
- One run per configuration, so variance is unmeasured.

### 1.2 Runs, hours and node-hours (assumed; LOW / HIGH)

EMR m5.xlarge, one node unless stated, launch overhead 0.25 h LOW and 0.4 h HIGH:

| Run | Node-hours LOW | Node-hours HIGH |
|---|---|---|
| Pipeline S | 0.5 | 1.0 |
| Pipeline M | 0.6 | 1.4 |
| Pipeline L | 0.9 | 2.4 |
| Failed-attempt allowance (HIGH only) | 0 | 1.0 |
| Elasticity on M, master plus 1, 2 and 4 core nodes (scaling efficiency 0.85 and 0.7 at HIGH) | 4.2 | 9.6 |
| Total | 6.2 | 15.4 |

Neptune session hours (provisioned `db.t4g.medium`), restated in repair round 2. The earlier version counted only the query windows (smoke 1 / 2, S 2 / 3, M 2.5 / 3.5, L 3 / 4.5; total 8.5 LOW and 13 HIGH). Every `experiment-size-*.tfvars` overlay sets `enable_neptune = true`, so Neptune bills for the whole session of every run brought up with an overlay. Hours per run, LOW / HIGH, equal to the session hours in section 3: 14.0 1 / 2 (the Neptune smoke window, applied by itself and not through an overlay, assumed), 14.1 1 / 2, 14.2 1.2 / 2.5, 15.1 2 / 3, 15.2 2.5 / 3.5, 15.3 1.4 / 3.2, 15.4 4 / 8, 15.5 6 / 10 (it was 3 / 4.5). Total **19.1 LOW and 34.2 HIGH**, an increase of 10.6 and 21.2 hours (7.6 and 15.7 in runs 14.1, 14.2, 15.3 and 15.4, which the repair-round-2 review named, plus 3 and 5.5 in run 15.5, where the overlay keeps Neptune up for the pipeline and load part of the session as well). The bulk load in 14.1 and 14.2 needs Neptune up in any case. Under Serverless, HIGH averages 2 NCU and LOW 1 NCU over the same hours. CPU credits and I/O are volume-based, not hour-based, so they are unchanged (section 4).

Service tasks started by the overlays (repair round 2): `service_desired_count` is 0 in the S overlay, 1 in M and 2 in L, and there are two services (fast and slow), so S starts none, M starts 2 tasks and L starts 4. The earlier Fargate line counted runtime tasks only in runs 14.0, 15.1, 15.2, 15.4 and 15.5. It missed the 2 tasks of the M overlay in 14.2 and 15.3. Added in section 2.2 (Fargate rebuild).

Bedrock: 40 real Haiku queries in plan D (10 smoke, 5 on S, 5 on M, 20 on L). Load and latency tests use the offline stub, which costs nothing. Embeddings: one Titan pass over 40,000 texts (L's vectors), plus one repeat at HIGH.

Elasticity runs on M, not L, because 3 clusters on L would add about $4 at HIGH. Spot task nodes are not used: only the "up to 90% off" claim is verified, there is no price, and Spot needs a core node anyway.

Session hours with Bedrock on (nine endpoints): runs 14.0, 15.1, 15.2 and 15.5. Session hours without (eight endpoints): 14.1, 14.2, 15.3 and 15.4. Section 3 has the durations.

## 2. Rates and per-service costs

### 2.1 Rate register

VERIFIED (V) rates cite the page and label as recorded in `pricing-evidence.md`.

| ID | Rate | Status | Source |
|---|---|---|---|
| V1 | Neptune bills per second in NCUs; "Starting capacity" 1 NCU | VERIFIED | aws.amazon.com/neptune/pricing |
| V2 | Neptune db.r5.large on demand $0.348 an hour (Example 1) | VERIFIED | same |
| V3 | Neptune storage $0.10 per GB-month; I/O $0.20 per million request increments; backup free up to 100 percent of storage; T3/T4g CPU credits $0.15 per vCPU-hour (unlimited mode); Free Tier 750 hours of db.t3.medium or db.t4g.medium, 10 million I/O, 1 GB storage, 1 GB backup, for 30 days | VERIFIED | same |
| V4 | EMR charge for c4.2xlarge $0.105 an hour (US-East-1) | VERIFIED | aws.amazon.com/emr/pricing |
| V5 | EMR Serverless $0.052624 per vCPU-hour, $0.0057785 per GB-hour; per-second billing, 1-minute minimum (region and architecture not stated) | VERIFIED | same, Example 3 |
| V6 | EC2 Spot "up to 90% off compared to On-Demand" (no price) | VERIFIED | aws.amazon.com/ec2/spot/pricing |
| V7 | S3 Vectors (US East N. Virginia): storage $0.06 per GB-month; PUT $0.20 per GB with 128 KB minimum per PUT; queries $2.50 per million; data processed $0.004 per TB (first 100K vectors); data returned $0.01 per GB | VERIFIED | aws.amazon.com/s3/pricing, examples |
| V8 | Fargate Linux/x86 $0.000011244 per vCPU-second, $0.000001235 per GB-second; per second, 1-minute minimum. Per-hour figures are arithmetic from these: $0.0404784 per vCPU-hour, $0.004446 per GB-hour | VERIFIED (per-second), arithmetic (per-hour) | aws.amazon.com/fargate/pricing |
| V9 | VPC gateway endpoints: no hourly or data-processing charge. NAT example, US East (Ohio), not us-east-1: $0.045 an hour and $0.045 per GB processed | VERIFIED (Ohio only) | aws.amazon.com/vpc/pricing |
| V10 | CloudWatch standard alarm $0.10 per month; 3 dashboards free | VERIFIED | aws.amazon.com/cloudwatch/pricing |
| V11 | Step Functions Standard $0.000025 per transition; 4,000 free per month | VERIFIED | aws.amazon.com/step-functions/pricing |
| V12 | SQS first 1 million requests a month free | VERIFIED | aws.amazon.com/sqs/pricing |
| V13 | Secrets Manager $0.40 per secret per month; $0.05 per 10,000 API calls | VERIFIED | aws.amazon.com/secrets-manager/pricing |
| V14 | KMS $1 per customer-managed key per month; 20,000 free requests (no key is created) | VERIFIED | aws.amazon.com/kms/pricing |
| V15 | Bedrock batch inference 50 percent of on-demand for select models; Haiku 4.5 and Titan not confirmed eligible (not used) | VERIFIED (eligibility unknown) | aws.amazon.com/bedrock/pricing |

UNVERIFIED (U) rates. Source is agent memory unless stated. Low and high are the bounds used.

| ID | Rate | Low | High | Source and note |
|---|---|---|---|---|
| U1 | EC2 + EMR, m5.xlarge, per node-hour | $0.22 | $0.35 | Memory: EC2 about $0.192 plus EMR about $0.048. UNVERIFIED. The earlier claim that the EMR share is "consistent with V4" is removed: V4 prices a c4.2xlarge EMR charge ($0.105), a different instance, and `pricing-evidence.md` holds no c4.2xlarge or m5.xlarge EC2 price, so no check against V4 is possible. HIGH adds EBS. |
| U2 | Neptune Serverless per NCU-hour | $0.14 | $0.25 | Memory and README: about $0.16 |
| U3 | Neptune db.t4g.medium per hour | $0.06 | $0.10 | Memory: about $0.07 |
| U4 | NAT gateway hourly in us-east-1 | $0.045 | $0.052 | V9 is Ohio. HIGH is Ohio plus 15 percent (assumption). Data at V9's $0.045 per GB. |
| U5 | Public IPv4 address for the NAT, per hour | $0.005 | $0.010 | Memory |
| U6 | Interface endpoint, per endpoint per AZ-hour | $0.010 | $0.015 | Memory (README). Data $0.010 to $0.012 per GB. One AZ is configured (`interface_endpoint_az_count = 1`). |
| U7 | S3 Standard: storage per GB-month; PUT per 1,000; GET per 1,000 | $0.023; $0.005; $0.0004 | $0.030; $0.0065; $0.0004 | Memory |
| U8 | DynamoDB on demand: $0.625 per million writes, $0.125 per million reads; $0.25 per GB-month beyond 25 GB free | same | same | `pricing-evidence.md` "summary-only, unverified", agreeing with memory. No direct page reading. The swing is carried by volume (item size and index writes at 1x to 3x), not by rate. If the rate is stale and double, HIGH DynamoDB rises by about $4.4. |
| U9 | Claude Haiku 4.5 on demand, per million tokens, input / output | $1 / $5 | $2 / $10 | Memory. NOT FOUND in evidence. Cross-region profile premium unknown, so HIGH is 2x. |
| U10 | Titan Text Embeddings V2 per million input tokens | $0.02 | $0.06 | Memory. NOT FOUND in evidence. |
| U11 | CloudWatch Logs ingestion per GB | $0.50 | $0.60 | Memory |
| U12 | Cloud Map hosted zone per month | $0.50 | $1.00 | README, memory. Not used now: Cloud Map is off by default. |
| U13 | ECR storage per GB-month | $0.10 | $0.20 | Memory |

No rate is invented to close a gap. Where V and U both exist for a service the cell says which one it uses.

### 2.2 Per-service table (tier L bounded run unless stated; LOW to HIGH)

| Service | Hourly or unit rate | Idle rate | Cost of one bounded run |
|---|---|---|---|
| EMR on EC2, m5.xlarge, transient | $0.22 to $0.35 per node-hour (U1, UNVERIFIED; no V4 anchor) | $0 between runs (design: state machine creates and terminates; 900 s idle stop) | S $0.11 to $0.35; M $0.13 to $0.49; L $0.20 to $0.84 (U1 x node-hours) |
| EMR Serverless | $0.052624 per vCPU-hour and $0.0057785 per GB-hour (V5) | $0 | Not applicable: the Hadoop stage is MapReduce. A Spark-only 4 vCPU, 16 GB, 0.5 h job would be about $0.15 (V5 arithmetic, region unstated), about the same as EC2, so no saving |
| EC2 Spot task nodes | "up to 90% off" (V6), no price | $0 | Not priced. Not used |
| Neptune provisioned db.t4g.medium (default) | $0.06 to $0.10 (U3). Anchor: db.r5.large $0.348 (V2) | Same as hourly while the cluster exists, $1.4 to $2.4 per day (U3 x 24) | L session, whole 6 to 10 hour session with the overlay on (it was 3 to 4.5 hours): $0.36 to $1.00 compute |
| Neptune Serverless, 1 NCU (selectable) | $0.14 to $0.25 per NCU-hour (U2); 1 NCU minimum (V1) | No scale to zero: $3.4 to $6.0 per day at 1 NCU (U2 x 24) | L session 6 to 10 h at 1 to 2 NCU: $0.84 to $5.00 compute, plus I/O |
| Neptune Free Tier | 750 h of t3 or t4g medium, 10 M I/O, 1 GB storage, 30 days (V3) | $0 within the trial | $0 if eligible. Whether a Paid-plan upgrade keeps eligibility, and when the 30 days start, is UNVERIFIED |
| Neptune storage, I/O, backup, credits | $0.10 per GB-month, $0.20 per million I/O, backup free to 100 percent, $0.15 per vCPU-hour surplus credits (V3) | Storage only, about $0.30 per month for 3 GB | I/O volume is an assumption: L load 0.5 M to 5 M, queries 0.5 M to 3 M. $0.20 to $1.60 |
| Neptune: not run | $0 | $0 | Alternative, section 5 |
| S3 Standard | storage $0.023 to $0.030 per GB-month; PUT $0.005 to $0.0065 per 1,000; GET $0.0004 per 1,000 (U7) | $0.07 to $0.30 per month for 3 to 10 GB | whole project $0.34 to $1.80 |
| S3 Vectors, 1024 dim | $0.06 per GB-month, PUT $0.20 per GB with 128 KB minimum, queries $2.50 per million (V7). Vector size 4 KB data plus about 1 KB metadata is an assumption | 52,000 vectors x 5 KB = 0.26 GB, $0.016 per month | PUT batched $0.05; unbatched at 128 KB per PUT $1.33. Whole project $0.09 to $1.60 |
| S3 Vectors, 256 dim | same rates (V7); 1 KB data plus 1 KB metadata | 0.10 GB, $0.006 per month | batched $0.02; unbatched $1.33 (the 128 KB minimum dominates, so dimension does not change the HIGH cost). Whole project $0.05 to $1.60 |
| DynamoDB on demand | $0.625 per million writes, $0.125 per million reads (U8) | $0 below 25 GB (U8) | L load 1.3 M items: $0.81 to $2.92. Whole project $1.19 to $4.40 |
| Bedrock Haiku 4.5 | $1 to $2 per M input, $5 to $10 per M output (U9) | $0 | Per query: 2 calls, 3k in, 300 out each at LOW = $0.009; 4 calls, 6k in, 600 out at 2x price = $0.072. Per 10 queries $0.09 to $0.72 |
| Bedrock Titan V2 | $0.02 to $0.06 per M tokens (U10) | $0 | 40,000 texts x 200 to 400 tokens: 8 M to 16 M tokens (twice at HIGH) = $0.16 to $1.92. Tokens per minute quota 300,000 (D2a), so 16 M tokens takes at least 53 minutes |
| Bedrock offline stub and hash embedder | $0 | $0 | $0. The hash embedder is 384 dimensions. **A fallback run cannot reuse the 256-dimension indexes**: changing `vector_dimension` on the deployed environment replaces all five S3 Vectors indexes in place and destroys their vectors (the dimension is not in the index names). Terraform now refuses the change unless `acknowledge_vector_loss` is true. The fallback needs its own environment, whose cost is a second copy of the storage lines (cents) plus a second reload of DynamoDB and vectors (up to the DynamoDB and S3 Vectors lines again, $1.28 to $6.00 at the whole-project values), which this estimate does not include |
| Fargate x86, runtime task (0.25 vCPU, 0.5 GB) | $0.0123426 per task-hour (V8 arithmetic: 0.25 x 0.0404784 + 0.5 x 0.004446) | $0 at desired count 0 | two tasks $0.0247 per hour |
| Fargate x86, loader task | **0.5 vCPU, 1 GB (the Terraform default, 512 CPU and 1024 MiB): $0.0246852 per hour** (0.5 x 0.0404784 + 1 x 0.004446). The earlier draft priced 1 vCPU, 4 GB at $0.0583 per hour, which no Terraform variable sets | $0 | see the Fargate rebuild below |
| Fargate x86, load-test client (1 vCPU, 2 GB) | $0.0493704 per task-hour (1 x 0.0404784 + 2 x 0.004446). Not defined in Terraform: the experiment owner's task | $0 | see the Fargate rebuild below |
| VPC gateway endpoints (S3, DynamoDB) | $0 (V9) | $0 | $0 |
| VPC interface endpoints, **8 in one AZ** (`ecr.api`, `ecr.dkr`, `logs`, `secretsmanager`, `sts`, `elasticmapreduce`, `s3vectors`, `sqs`); 9 with `bedrock-runtime` | $0.010 to $0.015 per endpoint-hour (U6) = $0.08 to $0.12 per hour (8), $0.09 to $0.135 (9) | $1.92 to $2.88 per day (8), $2.16 to $3.24 per day (9) | see 2.3 |
| VPC NAT gateway (alternative) | $0.045 to $0.052 per hour plus public IPv4 $0.005 to $0.010 (V9 Ohio, U4, U5) = $0.05 to $0.062; $0.045 per GB (V9) | $1.20 to $1.49 per day | see 2.3 |
| CloudWatch | alarm $0.10 per month (V10); 3 dashboards free (V10); logs $0.50 to $0.60 per GB (U11) | 3 alarms $0.30 per month by default (the fourth exists only with Neptune Serverless); the line below was sized for 4 and is kept as the upper side | whole project $0.35 to $2.00 (0.5 to 2 GB logs, 3-day retention) |
| Step Functions | $0.000025 per transition, 4,000 free per month (V11) | $0 | about 30 transitions per run; the project stays within the free tier at LOW; HIGH 5,000 transitions $0.10 |
| SQS | first 1 M requests free (V12). Rate beyond not found, not needed | $0 | $0 |
| Secrets Manager, 3 secrets | $0.40 per secret per month, $0.05 per 10,000 calls (V13) | $1.20 per month | $1.20 (1 month) to $2.40 (2 months); no proration assumed |
| ECR | storage $0.10 to $0.20 per GB-month (U13), images 0.5 to 1 GB with scan on push | $0.10 to $0.50 | same |
| Cloud Map hosted zone | off by default (`enable_service_discovery = false`); $0.50 to $1.00 per month if turned on (U12) | $0 | $0 |
| KMS | $1 per key per month (V14); none created | $0 | $0 |

Fixed (time-based) items sum to about $1.30 LOW and $2.90 HIGH for the project (secrets 1.20 / 2.40 and ECR 0.10 / 0.50; Cloud Map is off, so its $0.50 to $1.00 is gone). They are the cost of the project's duration, not of any run.

Fargate rebuild (finding L6). The earlier line, $1.33 to $2.87, rested on hours that the earlier text did not record and on the loader at 1 vCPU, 4 GB. It is rebuilt from stated hours and replaces the old line. Hours follow the section 3 session durations (LOW / HIGH):

| Task | Task-hours LOW / HIGH | Rate per task-hour | LOW | HIGH |
|---|---|---|---|---|
| Runtime tasks (2 per session at 14.0, 15.1, 15.2: 2 x (4, 2, 2.5) = 17 LOW and 2 x (6, 3, 3.5) = 25 HIGH; 4 per session at 15.4 and 15.5: 4 x (4, 6) = 40 LOW and 4 x (8, 10) = 72 HIGH) | 57 / 97 | $0.0123426 | 0.7035 | 1.1972 |
| Added in repair round 2: M overlay service tasks (2 tasks, one per service) in 14.2 and 15.3: 2 x (1.2 + 1.4) = 5.2 LOW and 2 x (2.5 + 3.2) = 11.4 HIGH | 5.2 / 11.4 | $0.0123426 | 0.0642 | 0.1407 |
| Runtime tasks after repair round 2 | 62.2 / 108.4 | $0.0123426 | 0.7677 | 1.3379 |
| Load-test clients (4 tasks for 15.4: 4 x 4 LOW, 4 x 8 HIGH) | 16 / 32 | $0.0493704 | 0.7899 | 1.5799 |
| Loader (S 0.5 / 1.0, M 1.0 / 2.0, L 2.0 / 4.0 hours: assumed) | 3.5 / 7.0 | $0.0246852 | 0.0864 | 0.1728 |
| Total | | | **1.64** | **3.09** |

Arithmetic: LOW 0.7677 + 0.7899 + 0.0864 = 1.6440; HIGH 1.3379 + 1.5799 + 0.1728 = 3.0906. The line before repair round 2 was 1.58 / 2.95; it rises by 0.06 and 0.14. The earlier line (1.33 / 2.87) came from before the rebuild. The tier S overlay sets `service_desired_count = 0`, yet runs 14.0 and 15.1 need running services (assumed one task per service, two tasks); the estimate keeps those 2 tasks, so it prices more than the S overlay would start, and that is a tfvars matter for Phase 14. Likewise run 15.4 prices 4 runtime tasks and 4 load-test tasks, while the M overlay starts only 2 service tasks (the L overlay starts 4, and the load test needs a count of 2 per service); the estimate keeps its 4, so no further hours are added for 15.4. Run 14.1 uses the S overlay (no service tasks) and adds none.

### 2.3 Interface endpoints versus NAT

Per hour, one AZ:

| Mode | LOW | HIGH | Basis |
|---|---|---|---|
| 8 interface endpoints (Terraform default) | $0.080 | $0.120 | 8 x U6 |
| 9 endpoints (adds `bedrock-runtime` when Bedrock is on) | $0.090 | $0.135 | 9 x U6 |
| 1 NAT gateway plus public IPv4 | $0.050 | $0.062 | U4 + U5 |

Endpoints cost more per hour than one NAT at both bounds; break-even at LOW is 5 endpoints.

Session hours, from section 3. Bedrock sessions (nine endpoints): 14.0 (4 / 6 h), 15.1 (2 / 3), 15.2 (2.5 / 3.5), 15.5 (6 / 10) = 14.5 LOW and 22.5 HIGH. Other sessions (eight endpoints): 14.1 (1 / 2), 14.2 (1.2 / 2.5), 15.3 (1.4 / 3.2), 15.4 (4 / 8) = 7.6 LOW and 15.7 HIGH. Total 22.1 LOW and 38.2 HIGH (the earlier text used rounded 22 and 40). Data processing for the whole project: 3 GB LOW at $0.010 and 15 GB HIGH at $0.012 per GB for interface endpoints; the same 3 and 15 GB at $0.045 per GB for NAT. These volumes are assumptions.

| Mode | LOW arithmetic | LOW | HIGH arithmetic | HIGH |
|---|---|---|---|---|
| Endpoints, whole project | 14.5 x 9 x 0.010 + 7.6 x 8 x 0.010 + 3 x 0.010 = 1.305 + 0.608 + 0.030 | **1.94** | 22.5 x 9 x 0.015 + 15.7 x 8 x 0.015 + 15 x 0.012 = 3.0375 + 1.884 + 0.180 | **5.10** |
| NAT, whole project | 22.1 x 0.050 + 3 x 0.045 = 1.105 + 0.135 | **1.24** | 38.2 x 0.062 + 15 x 0.045 = 2.368 + 0.675 | **3.04** |
| Difference (endpoints minus NAT) | | +0.70 | | +2.06 |
| Endpoints, S + M only (all sessions but 15.5) | 8.5 x 9 x 0.010 + 7.6 x 8 x 0.010 + 0.030 = 0.765 + 0.608 + 0.030 | 1.40 | 12.5 x 9 x 0.015 + 15.7 x 8 x 0.015 + 0.180 = 1.6875 + 1.884 + 0.180 | 3.75 |
| NAT, S + M only | 16.1 x 0.050 + 0.135 = 0.805 + 0.135 | 0.94 | 28.2 x 0.062 + 0.675 = 1.748 + 0.675 | 2.42 |

The earlier NAT figures were $1.24 LOW and $3.16 HIGH; HIGH moves to $3.04 only because the hours are now summed exactly instead of rounded to 40.

The trade-off is not only price. NAT gives private subnets an internet path (outbound only, no ingress) that the current design deliberately lacks. Endpoints keep it closed, at the cost of eight or nine endpoints and their private-DNS setup, which are a likely first-run failure that burns billed hours (the S3 Vectors and SQS endpoints in particular are UNVERIFIED in this AZ, infra README). Either mode costs money if forgotten (section 5).

## 3. Timeline, Phases 14 and 15 (plan D: endpoints, Neptune provisioned t4g.medium)

Order change proposed: tier L runs last, after the elasticity and load experiments, so that the L gate reads the largest set of actuals. This moves the L pipeline out of Phase 14. If the Phase 14 gate needs an L bounded run, the human decides.

"Up" lists what bills during a session. After each session the session-down overlay removes the endpoints, Neptune and service tasks (section 1). Costs are LOW / HIGH in dollars.

How this table was restated. The earlier table's run costs held NAT egress and Cloud Map. For each run I removed the NAT hourly part (hours x $0.05 LOW, x $0.062 HIGH) and added the endpoint hourly part (hours x endpoints x U6, nine endpoints in Bedrock sessions and eight otherwise). Cloud Map ($0.50 / $1.00) is removed from the fixed items of run 14.0. Endpoint data processing (0.03 / 0.18) is added once, in run 15.4. The rest of each run's cost is carried unchanged from the earlier table, **including its Fargate share, which was priced with the old loader size and is not rebuilt per run** (the service table in section 4 uses the rebuilt Fargate line). Note: the earlier rows may have held a NAT data-processing share, which was not itemized and was not removed; if it was, S + M HIGH is up to $0.68 lower.

Repair round 2 (finding N1) added to the rows below, in bold in the "Up" column: the Neptune hours of every session that an overlay brings up (hours x $0.06 LOW, x $0.10 HIGH, U3) and, for the M overlay, 2 service tasks for the session hours of 14.2 and 15.3 (x $0.0123426 per task-hour). Run 15.5's extra Neptune hours (3 / 5.5) are the part of the 6 / 10 hour L session outside the earlier 3 / 4.5 hour query window.

| Run | Hours LOW / HIGH | Up during the run | Base cost excl. egress | Endpoints (hourly part) | Run cost | Running total | Alert crossed |
|---|---|---|---|---|---|---|---|
| 14.0 Foundation apply, image push, Fargate smoke on the 3-work slice, 10 Haiku queries, 1 Titan call, Neptune smoke | 4 / 6 | 9 endpoints, Fargate runtime and loader, Neptune t4g 1 / 2 h; storage persists | 0.46 + fixed 0.20 = 0.66 / 1.96 + fixed 0.60 = 2.56 | 0.36 / 0.81 | 1.02 / 3.37 | 1.02 / 3.37 | none |
| 14.1 Pipeline S, load | 1 / 2 | 8 endpoints, one EMR node, loader, **Neptune (S overlay)** | 0.34 / 1.69 (carried 0.33 / 1.61 less NAT 0.05 / 0.12, plus Neptune 1 h x 0.06 = 0.06 and 2 h x 0.10 = 0.20) | 0.08 / 0.24 | 0.42 / 1.93 | 1.44 / 5.30 | none |
| 14.2 Pipeline M, load | 1.2 / 2.5 | same, **Neptune and 2 service tasks (M overlay)** | 0.65 / 2.78 (0.61 / 2.62 less 0.06 / 0.16, plus Neptune 0.072 / 0.25 and 2 tasks 2.4 / 5.0 h x 0.0123426 = 0.030 / 0.062) | 0.10 / 0.30 | 0.75 / 3.08 | 2.19 / 8.38 | none |
| 15.1 Neptune S session, queries, 5 Haiku | 2 / 3 | 9 endpoints, Neptune, runtime tasks (already counted for the whole session) | 0.44 / 2.38 (0.54 / 2.57 less 0.10 / 0.19) | 0.18 / 0.41 | 0.62 / 2.79 | 2.81 / 11.16 | HIGH $10 |
| 15.2 Neptune M session, queries, 5 Haiku | 2.5 / 3.5 | same | 0.51 / 2.56 (0.63 / 2.78 less 0.13 / 0.22) | 0.23 / 0.47 | 0.73 / 3.04 | 3.54 / 14.20 | none |
| 15.3 Elasticity on M, master plus 1, 2, 4 core | 1.4 / 3.2 | 8 endpoints, EMR clusters in turn, **Neptune and 2 service tasks (M overlay)** | 1.11 / 4.06 (1.06 / 3.86 less 0.07 / 0.20, plus Neptune 0.084 / 0.32 and 2 tasks 2.8 / 6.4 h x 0.0123426 = 0.035 / 0.079) | 0.11 / 0.38 | 1.22 / 4.45 | 4.76 / 18.64 | HIGH $15 |
| 15.4 Load and latency test on stub, 4 tasks | 4 / 8 | 8 endpoints, 4 runtime tasks, 4 load-test tasks, DynamoDB and S3 Vectors reads, **Neptune (overlay)** | 2.35 / 5.63 (carried 1.21 / 3.03 plus rest of fixed 1.10 / 2.30, less NAT 0.20 / 0.50, plus Neptune 4 h x 0.06 = 0.24 and 8 h x 0.10 = 0.80) | 0.32 / 0.96, data 0.03 / 0.18 | 2.70 / 6.77 | 7.46 / 25.42 | HIGH $20 and **HIGH $25** |
| Gate G-L (below) | | | | | | | |
| 15.5 Tier L, pipeline, load, Neptune session, 20 Haiku | 6 / 10 | 9 endpoints, EMR, loader, runtime, **Neptune for the whole session (L overlay)** | 2.28 / 11.97 (carried 2.40 / 12.04 less 0.30 / 0.62, plus the extra Neptune 3 h x 0.06 = 0.18 and 5.5 h x 0.10 = 0.55) | 0.54 / 1.35 | 2.82 / 13.32 | 10.28 / 38.74 | LOW $10; HIGH already past $25 |

Notes:
- Running totals are the earlier running totals plus the cumulative additions of repair round 2, not sums of rounded cells, so a total can differ by $0.01 from the sum of the rounded cells (15.4 HIGH: 18.64 + 6.77 shows 25.41, the total is 25.42). Cumulative additions LOW / HIGH: after 14.1 0.06 / 0.20; after 14.2 0.162 / 0.512; after 15.3 0.281 / 0.911; after 15.4 0.521 / 1.711; after 15.5 0.701 / 2.261.
- The timeline is a hand allocation of the same lines as the service table. It sums to $10.28 LOW and $38.74 HIGH. The service table (section 4) is the more careful total, $10.32 and $38.70, and agrees within $0.04 (the same gap as before repair round 2). Use the service table for the whole-project headline.
- S + M stop at $7.46 LOW and **$25.42 HIGH**, before L. At HIGH the $10, $15, $20 and $25 alerts (the $25 one is not a configured alert; it marks the ceiling) are all passed before L would start: $10 in 15.1, $15 in 15.3, $20 and the ceiling in 15.4.
- At LOW, the $10 alert is first passed during the L run (the path ends at $10.28, just above it), so the alert would fire at the end of the project at LOW.
- NAT alternative on the same basis: S + M $6.86 LOW and $23.41 HIGH (the earlier $6.34 and $21.70 plus the repair round 2 additions 0.52 and 1.71). Endpoints cost up to $0.60 LOW and $2.01 HIGH more over S + M on this basis; the cleaner section 2.3 difference without the possible double count is $0.46 and $1.33.
- Neptune is priced at the provisioned t4g rate (U3, UNVERIFIED). In 14.0 the Neptune window (1 / 2 hours) is assumed to be switched on by itself and not through an overlay, as before.

Gate G-L, before 15.5: the human reads Billing (section 7) and records actual cumulative spend A. Estimate L from measured costs on M scaled by 4 (works ratio) and multiplied by 1.5. Run L only if A plus that estimate is at most **$18.5** (Phase 14 D6: the $20 code ceiling minus $1.5 for teardown and billing lag; it was $23.5 against $25; the human's operating stop near $15 is tighter). At the HIGH rates L costs $13.32, so the test passes only if A is at most $5.18 (it was $10.18); at LOW rates L costs $2.82 and the test passes while A is at most $15.68 (it was $20.68). The figures $10.18 and $20.68 quoted elsewhere in this document are the superseded $25-ceiling values. The measured-scaling rule does not scale the endpoint hours with works (they follow session time), so it may over-estimate L; that errs toward not running L. Fallback tier L-half (50,000 works) costs about half. L-half and L both run in a session that needs the same endpoints, so the fixed hourly part does not halve.

Gate G-S, before 14.1: every later tier waits for the human to confirm the 14.0 session-down left nothing running (section 5). Gate G-M, before 14.2: replace the pairs-per-work, edges-per-work, run-hour and I/O assumptions with S measurements and recompute this document's HIGH total.

### 3a. Whether the project fits under $25, stated once

| Question | LOW | HIGH | Answer |
|---|---|---|---|
| S + M under $25 | 7.46 | 25.42 | At LOW yes, $17.54 spare. **At HIGH no: $25.42 is $0.42 over the ceiling.** This replaces the earlier "Yes at both bounds" (which rested on $23.71 and left out Neptune and service-task hours the overlays switch on). If the earlier rows held an unitemized NAT data share, up to $0.68 less, about $24.74, which is under by $0.26 and is not a proof |
| S + M + L under $25 | 10.32 | 38.70 | At LOW yes, with $14.68 spare. At HIGH no, $13.70 over |
| Gate passes at HIGH after S + M | L needs A <= 5.18 (18.5 against the $20 ceiling; it was 10.18 against $25) | A is 25.42 | No: at HIGH rates L does not run, and S + M alone is over both ceilings |
| Gate at LOW | L needs A <= 15.68 (it was 20.68) | A is 7.46 | Yes |
| S + M under $20 (D6) | 7.46 | 25.42 | At LOW yes, $12.54 spare. At HIGH no, $5.42 over. See "Cost ceiling lowered to 20 USD" |

Endpoint range, for reference (corrected from the earlier text, which gave $2.16 to $3.24 at HIGH): at HIGH, one forgotten day of interface endpoints costs $2.88 (8 endpoints, 0.015 x 8 x 24) to $3.24 (9 endpoints, 0.015 x 9 x 24). The figure $2.16 is the 9-endpoint LOW day (0.010 x 9 x 24); the 8-endpoint LOW day is $1.92. The ceiling is therefore exceeded at HIGH, and one forgotten endpoint day would add $2.88 to $3.24 on top.

Cheaper options, **proposals only** (nothing below was built; the overlays and the Terraform defaults are unchanged in this round; each needs the human's decision and a Terraform or overlay change in a later work order). Arithmetic at the same rates, LOW / HIGH:

| # | Proposal | What it removes | S + M after the change | Fits $25 at HIGH? |
|---|---|---|---|---|
| P1 | A pipeline-only overlay with Neptune off (and no service tasks) for the elasticity run 15.3, and Neptune off for the stub load test 15.4 if the stub path does not call Neptune (UNVERIFIED) | Neptune 1.4 / 3.2 h and 4 / 8 h, and the 2 M-overlay tasks in 15.3: 0.119 + 0.24 = 0.359 LOW; 0.399 + 0.80 = 1.199 HIGH | 7.10 / 24.22 | Yes, margin $0.78. Less than one forgotten endpoint day, so thin |
| P2 | Shorter Neptune windows in the load sessions 14.1 and 14.2: Neptune up only for the bulk load (the loader hours of the Fargate table, assumed 0.5 / 1.0 h on S and 1.0 / 2.0 h on M), not for the whole pipeline session. Needs Neptune to be switched on in the middle of a session (a second apply, one more approval record each time) | Neptune hours 1 - 0.5 = 0.5 and 1.2 - 1.0 = 0.2 LOW, 2 - 1 = 1 and 2.5 - 2 = 0.5 HIGH: 0.7 h x 0.06 = 0.04 LOW and 1.5 h x 0.10 = 0.15 HIGH. Small, because those sessions are short | 7.06 / 24.07 together with P1 | Yes together with P1, margin $0.93. Alone it does not fit (25.27) |
| P3 | Neptune Free Tier: 750 hours of `db.t4g.medium`, 10 million I/O, 1 GB storage for 30 days (V3 VERIFIED text). S + M Neptune hours are 14.0 to 15.4: 13.1 LOW and 24.2 HIGH, far below 750 | If eligible and every session falls inside the 30 days: S + M Neptune compute 13.1 x 0.06 = 0.79 LOW and 24.2 x 0.10 = 2.42 HIGH, plus up to about $2.5 of I/O and storage at HIGH (section 4 note), which is not counted here | 6.67 / 23.00 (7.46 - 0.79, 25.42 - 2.42) | Conditional. Eligibility under the Paid plan and the start date of the 30 days are UNVERIFIED |
| P4 | NAT instead of interface endpoints (the human's D2c choice, so this reverses it) | Endpoints minus NAT: 0.60 LOW and 2.01 HIGH over S + M | 6.86 / 23.41 | Yes, margin $1.59. Opens an outbound internet path |

P1 alone is the smallest change that brings S + M HIGH under $25 without changing any rate assumption. None of the four proposals is a proof of fit: each leaves a margin smaller than one UNVERIFIED rate swing. The honest reading is that, at the HIGH bound, S + M does not provably fit with the overlays as they stand, and that is carried as an open risk for the human (section 8).

## 4. Totals by service and plan

LOW / HIGH in dollars. Plan D is the Terraform defaults as built: interface endpoints (8; 9 where Bedrock is on), Neptune provisioned t4g.medium, Cloud Map off, 40 real Haiku queries. The NAT columns keep everything else the same and swap the egress line (a comparison only). The earlier "plan A" (Neptune Serverless, 60 queries) and "plan R" (NAT) are retired: provisioned Neptune is now the default and NAT is not the default.

| Line | D LOW | D HIGH | NAT alt. LOW | NAT alt. HIGH | Basis |
|---|---|---|---|---|---|
| EMR + EC2 | 1.36 | 5.39 | 1.36 | 5.39 | 6.2 / 15.4 node-hours x U1 |
| Neptune compute | 1.15 | 3.42 | 1.15 | 3.42 | 19.1 h x $0.06 = 1.146; 34.2 h x $0.10 = 3.42 (U3); whole sessions with the overlays on (was 8.5 h and 13 h) |
| Neptune CPU credits | 0 | 0.80 | 0 | 0.80 | V3 $0.15 per vCPU-hour, about 3 heavy-load hours x 2 vCPU x 80 percent over baseline (assumption) |
| Neptune I/O and storage | 0.44 | 3.30 | 0.44 | 3.30 | V3. I/O 2.15 M LOW and 15.5 M HIGH (assumed), storage 0.01 / 0.20 |
| Egress | 1.94 | 5.10 | 1.24 | 3.04 | section 2.3 |
| Fargate | 1.64 | 3.09 | 1.64 | 3.09 | section 2.2 rebuild (loader 0.5 vCPU, 1 GB), plus the M overlay's service tasks in 14.2 and 15.3 |
| S3 | 0.34 | 1.80 | 0.34 | 1.80 | U7 |
| DynamoDB | 1.19 | 4.40 | 1.19 | 4.40 | U8, 1.69 M items, 1x to 3x write amplification, 20 percent retries at HIGH, reads 1 M to 5 M |
| S3 Vectors (1024 dim) | 0.09 | 1.60 | 0.09 | 1.60 | V7. HIGH is unbatched PUTs at the 128 KB minimum |
| Bedrock Haiku | 0.36 | 2.88 | 0.36 | 2.88 | U9; 40 queries |
| Bedrock Titan | 0.16 | 1.92 | 0.16 | 1.92 | U10 |
| CloudWatch | 0.35 | 2.00 | 0.35 | 2.00 | V10, U11 |
| Step Functions | 0 | 0.10 | 0 | 0.10 | V11 |
| Secrets Manager | 1.20 | 2.40 | 1.20 | 2.40 | V13 |
| ECR | 0.10 | 0.50 | 0.10 | 0.50 | U13 |
| Cloud Map | 0 | 0 | 0 | 0 | off by default |
| Total | **10.32** | **38.70** | 9.62 | 36.64 | |
| Reserve against $25 | 14.68 | -13.70 | 15.38 | -11.64 | |

Sums, so they can be checked. D LOW: 1.36 + 1.15 + 0 + 0.44 + 1.94 + 1.64 + 0.34 + 1.19 + 0.09 + 0.36 + 0.16 + 0.35 + 0 + 1.20 + 0.10 + 0 = 10.32. D HIGH: 5.39 + 3.42 + 0.80 + 3.30 + 5.10 + 3.09 + 1.80 + 4.40 + 1.60 + 2.88 + 1.92 + 2.00 + 0.10 + 2.40 + 0.50 + 0 = 38.70. NAT alternative: D minus egress plus the NAT egress, 10.32 - 1.94 + 1.24 = 9.62 and 38.70 - 5.10 + 3.04 = 36.64. Changes from the repair round 1 totals of 9.62 / 36.44 (NAT 8.92 / 34.38): Neptune compute +0.64 / +2.12 and Fargate +0.06 / +0.14 (repair round 2, finding N1). The earlier plan R (NAT) total was 9.17 / 35.42.

Neptune note: the Neptune total for provisioned is now 1.59 LOW (1.15 compute, 0 credits, 0.44 I/O and storage) and 7.52 HIGH (3.42 compute, 0.80 credits, 3.30 I/O and storage); it was 0.95 and 5.40. CPU credits assume about 3 heavy-load hours and the I/O volumes are unchanged, because extra idle session hours add neither. That assumption is not measured. Serverless instead (compute 19.1 h x 1 NCU x 0.14 = 2.67 LOW, 34.2 h x 2 NCU x 0.25 = 17.10 HIGH, no credits, same I/O and storage) is 3.11 and 20.40, so +1.52 LOW and +12.88 HIGH (it was +0.68 and +4.40). In provisioned mode, Neptune I/O of 10 million and 1 GB of storage are free if the Free Tier applies (V3), which could remove up to about $2.5 of the HIGH line. That is not counted.

Three largest lines at HIGH: Neptune $7.52, EMR and EC2 $5.39, egress $5.10 (then DynamoDB $4.40). At LOW: egress $1.94, Fargate $1.64, Neptune $1.59 (then EMR and EC2 $1.36).

Plan C, no Neptune (drops a course-required service, needs human approval): D minus the Neptune lines, LOW $8.73 (10.32 - 1.59) and HIGH $31.18 (38.70 - 7.52). With tier L gated it still does not remove the HIGH overrun alone. The Spark graph tables plus DynamoDB path would replace graph retrieval.

## 5. Budget threats and cheaper alternatives

| # | Threat | Cost if it happens | Cheaper alternative and trade-off |
|---|---|---|---|
| 1 | Anything left running between sessions | Per day, LOW to HIGH: Neptune Serverless 1 NCU $3.4 to $6.0 (a week: $24 to $42, the whole budget); Neptune t4g $1.4 to $2.4; one EMR node $5.3 to $8.4; NAT $1.20 to $1.49; **8 interface endpoints $1.92 to $2.88, 9 endpoints $2.16 to $3.24**; 2 runtime Fargate tasks $0.59 per day (V8). A month: NAT $36 to $45, **8 endpoints $58 to $86, 9 endpoints $65 to $97**, Fargate tasks $18 | Session-down overlay after each session (section 1). Human-run billable-resource check at the end of each session. Budget alerts only warn, so they need the check |
| 2 | Neptune Serverless has no scale to zero (1 NCU minimum, V1) | $3.11 LOW, $20.40 HIGH (whole-session hours, section 4 note) | Provisioned db.t4g.medium (the default; $1.59 to $7.52) and the Free Tier trial (V3, eligibility unverified). Trade-off: 4 GB of memory, so latency on L is not representative. Option C (no Neptune) needs human approval |
| 3 | DynamoDB on-demand writes for tier L | $0.81 to $2.92 for L, 66 percent of the line | Gate L. Fewer item forms per edge (storage-engineer decision). Tier L-half |
| 4 | EMR hours are an assumption | $1.36 to $5.39 | Measure S and M first. EMR Serverless does not help (MapReduce) |
| 5 | Haiku token volume | $0.009 to $0.072 per query. 410 real queries would reach $3.7 LOW and $29 HIGH | Cap real queries at 40. Offline stub for load, latency and ablation runs. Per-run token ceiling in the runtime config (service-engineer) |
| 6 | Interface endpoints (8 or 9) | $1.94 to $5.10 for the project, $0.70 to $2.06 more than NAT | NAT (not chosen, D2c): removes eight endpoints and their failure modes, opens an outbound internet path. The human decides |
| 7 | S3 Vectors 128 KB PUT minimum, unbatched | up to $1.33 | Batch at least 26 vectors per PUT at 5 KB each (storage-engineer). 256 dimensions do not help here |
| 8 | Credits and the Paid plan | unknown | Count gross usage against the $20 code ceiling (D6; the AWS spend limit is also $20). Whether credits cover Anthropic models on Bedrock is UNVERIFIED |
| 9 | Billing lag | Gate decisions use stale numbers | Allow up to 24 hours (stated from memory) between a run and its reading; one run per day where possible; reserve of $1.5 |
| 10 | Changing `vector_dimension` on a deployed environment (for example 256 to 384 for the hash embedder) | Replaces all five indexes and destroys their vectors; reloading costs up to the DynamoDB and S3 Vectors lines again ($1.28 to $6.00, whole-project values) plus the run time | Terraform refuses the change unless `acknowledge_vector_loss` is true. Use a separate environment for a 384-dimension fallback. Pick 256, 512 or 1024 once and keep it |
| 11 | The execution timeout skips cleanup | An EMR cluster left running by a dead client: up to one m5.xlarge node-hour rate ($0.22 to $0.35) per hour per node until the 900 s idle stop, which does not start while a step runs | `pipeline_max_run_seconds` bounds the state machine at S 7200 (work order 49, was 5400), M 7200, L 10800 s, and the per-tier `pipeline_emr_step_timeout_seconds` (S 4800 since work order 52, was 3600 and 1800 before; M 2700, L 3600) with `pipeline_create_cluster_timeout_seconds` (1440) makes a hung EMR step end in its step timeout, which runs cleanup, before the execution timeout (Rule A, enforced as create + step + 600, 6840 against 7200 for S; infra README, "Pipeline bounds"). If every S segment hit its own cap the sequence would be 1440 + 300 + 4800 + 900 + 1800 = 9240 s against 7200 s; the 90-minute script stop at 5400 s fires first. A hung CreateCluster goes to `Failed` at 1440 s with no cleanup state; the script's stop path terminates clusters; the human's cleanup check after every run. **Accepted limitation (human decision O1, Phase 13 decisions D10):** the loader windows (`pipeline_loader_timeout_seconds` S 1800, M 2700, L 3600) are shorter than this estimate's loader hours (S 1.0, M 2.0, L 4.0 h at HIGH), and one sequential HIGH run (S 120 min, M 204 min, L 384 min) exceeds the script caps of 60, 90 and 150 minutes (the S run uses the 90-minute tier). The caps were kept. At the HIGH bound a run may time out, a timed-out run still wastes its spend and needs a re-run with a new approval record, and a cap is a ceiling, not a cost. No figure in this document was changed. After the S run the human re-derives the M and L caps from the measured S runtime (infra README, "Pipeline bounds") |
| 12 | The size overlays switch Neptune on for the whole session, and M and L start service tasks | Already counted in this version: Neptune 19.1 / 34.2 session hours ($1.15 / $3.42 compute) and the 2 M-overlay tasks in 14.2 and 15.3. The cost of the overlays being wider than the earlier estimate assumed is $0.70 LOW and $2.26 HIGH over the project, of which $0.52 LOW and $1.71 HIGH fall before L; that is why S + M HIGH moved from $23.71 to $25.42 | Proposals P1 to P4 in section 3a (a pipeline-only overlay with Neptune off for 15.3 and 15.4, shorter load windows, Free Tier, NAT). None is built. Decision for the human |

## 6. Terraform defaults review: status after D2c and repair round 1

1. Neptune mode: DONE. `neptune_mode = "provisioned"` and `neptune_instance_class = "db.t4g.medium"` are the defaults; `enable_neptune = false`; Serverless maximum lowered from 2.5 to 2 NCU.
2. Egress: the human kept `endpoints` (D2c). `bedrock-runtime` is added only when `enable_bedrock` is on. `s3vectors` and `sqs` were added to the default list (repair round 1) because the in-VPC services and loader call them. NAT stays selectable.
3. Cloud Map: DONE. Default off.
4. Alert thresholds: DONE as variables, absolute amounts. `budget_total_usd = 20` (was 25 until decision D6) and `budget_alert_thresholds_usd = [10, 15, 20]`, validated, used only when `create_budgets` is true (default false until Phase 14). The module no longer uses percentage thresholds of a $25 or $50 limit. The human decides whether to add a forecast alert.
5. EMR: DONE. One node, 0 core, 0 Spot, 900 s idle. The scaling cap has its own variable (default 3), validated against 1 + core + task nodes (at most 7); the elasticity overlay uses core 4 and cap 5. Per-experiment tfvars exist. The EMR EBS volume size is not set in the module; reducing it is UNVERIFIED.
6. Kept: `use_fargate_spot = false` (no Fargate Spot price in the evidence), `service_desired_count = 0`, `log_retention_days = 3`, DynamoDB on demand with PITR off, no customer KMS key.
7. Vector dimension: kept at 256. The cost gap to 1024 is cents. Choose by retrieval quality (D2a). 384 is for the hash-embedder fallback only and needs its own environment (threat 10).
8. Teardown: the session-down overlay keeps storage and removes endpoints, Neptune and service tasks. `cleanup.sh` remains the full end-of-project teardown.
9. Loader batch size: at least 26 vectors per S3 Vectors put (storage-engineer, not Terraform).

## 7. Recording actual expenses in Phases 14 and 15

Agents have no credentials. The human reads the Billing and Cost Management console and the final report uses those figures.

Per run, the human records in `docs/agent-runs/phase-NN/costs/` (the orchestrator archives it):
1. Before the run: Cost Explorer cumulative gross cost since the account's start (Cost Explorer, monthly or daily, charge type: exclude credits), the timestamp, and the credit balance (Billing, Credits).
2. After the run, at least one day later because of billing lag (stated from memory, not verified): the same view grouped by Service and by Usage Type, daily granularity, for the run's date range. Download the CSV and keep it unedited. Record gross cost, credits applied and net cost separately. The $20 code ceiling (D6) is tested on gross cost.
3. One row per run: run id, start and end in UTC, estimate LOW and HIGH from section 3, actual by service, cumulative actual, delta against the estimate, and notes (failed attempts, resources found left running).
4. Bedrock: Anthropic models may appear as a separate service name in Cost Explorer. Record the exact names seen. Both the Billing page names and the Bedrock invocation counts are read by the human, and the runtime also logs its own token counts for cross-checking.
5. Cost allocation tags (`cost-center`, `environment`, `owner` and the others set by `default_tags`) must be activated in Billing before they appear, and they take time to show (UNVERIFIED). Do not rely on them for attribution. Run one tier per day where possible, so service totals identify the run.
6. End of project: a Cost Explorer export for the whole project period, grouped by service, a table of estimate against actual per line and per run, and an explanation of every line where actual exceeded HIGH.

## 8. Open items for the human

Ranked by how much each changes the total at HIGH (swing between LOW and HIGH of the line in plan D):

| Rank | Missing rate or fact | Swing | How to close it |
|---|---|---|---|
| 1 | Neptune db.t4g.medium and Serverless NCU-hour rates, and I/O volumes. Free Tier eligibility and start date | $5.9 for t4g (7.52 - 1.59), $17.3 for Serverless (20.40 - 3.11) | Price list file below; Billing console for Free Tier usage |
| 2 | EC2 on-demand and EMR surcharge for m5.xlarge | $4.0 | Price list file below |
| 3 | DynamoDB on-demand write and read rates (direct reading) | $3.2 volume swing, $4.4 more if the rate is stale | Price list file below |
| 4 | Interface endpoint hourly and per-GB rates in us-east-2 (8 or 9 endpoints, 38 session hours) | $3.2 (1.94 to 5.10) | Price list file |
| 5 | Claude Haiku 4.5 input and output prices, including the inference-profile price | $2.5 | Bedrock console pricing, or price list file |
| 6 | NAT hourly in us-east-2 (verified, V9) and public IPv4 (only if the human reconsiders NAT) | $1.8 | Price list file |
| 7 | Titan V2 price per million tokens | $1.8 | Price list file |
| 8 | CloudWatch Logs ingestion per GB; S3 Standard storage and requests | $1.7 and $1.5 | Price list file |
| 9 | Run hours, pairs per work, edges per work, I/O counts (assumptions, not rates) | not bounded | Measure on tier S (gate G-M) |
| 10 | Service quotas: On-Demand Standard vCPU (1 master plus 4 core of m5.xlarge is 20 vCPUs; new accounts can start low), Fargate vCPU | blocks runs | Service Quotas console |
| 11 | Whether credits cover Anthropic models on Bedrock | up to $4.3 | Billing, Credits, "services excluded" text |
| 12 | Whether the S3 Vectors and SQS interface endpoints are offered in the endpoint subnet's AZ | blocks runs in endpoints mode | Phase 14 plan, then the first apply |
| 13 | Whether S + M can be brought under $25 at the HIGH bound (it is $25.42 now). Proposals P1 to P4, section 3a | $0.42 over at HIGH | Human decision on P1 to P4; a later work order changes an overlay if chosen |

Paste-ready ways to close the rates. Run by the human with their own session. None needs project credentials. The first form reads the public price list over HTTPS with no credentials. The URL pattern is from memory, so check that the first command returns JSON.

```
curl -sS -o pricelist-index.json https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/index.json
```

Then pick the offer codes from that index (expected: `AmazonEC2`, `ElasticMapReduce`, `AmazonNeptune`, `AmazonDynamoDB`, `AmazonBedrock`, `AmazonVPC`, `AmazonCloudWatch`, `AmazonS3`; these names are UNVERIFIED until the index confirms them) and fetch one file per service, for example:

```
curl -sS -o neptune-us-east-2.json https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonNeptune/current/us-east-2/index.json
```

If the human prefers the CLI with their own profile (needs `pricing:GetProducts`). The `us-east-1` in the `pricing.us-east-1.amazonaws.com` host and in `--region` below is the Pricing API's own endpoint (a read-only public price service, it deploys nothing); the prices asked for are the deployed Region's, us-east-2 (Ohio):

```
aws pricing describe-services --region us-east-1 --query 'Services[].ServiceCode'
aws pricing get-products --region us-east-1 --service-code AmazonEC2 --filters Type=TERM_MATCH,Field=instanceType,Value=m5.xlarge Type=TERM_MATCH,Field=location,Value="US East (Ohio)" Type=TERM_MATCH,Field=operatingSystem,Value=Linux Type=TERM_MATCH,Field=tenancy,Value=Shared Type=TERM_MATCH,Field=preInstalledSw,Value=NA Type=TERM_MATCH,Field=capacitystatus,Value=Used --max-results 5
```

Reading the Bedrock pricing page in a browser and pasting the Haiku 4.5 and Titan V2 rows is the simplest route, because those pages render by script and the fetch agents could not read them. The orchestrator can then update section 2.1 and move each rate from UNVERIFIED to VERIFIED with the page and label.

Decisions for the human (D2c answered the Neptune, egress and tier-L-last questions on the earlier NAT-based figures; the egress answer should be re-read against section 0):
1. Confirm the three sizes (5,000 / 25,000 / 100,000 works), the nesting rule and the gates, or give different sizes. Still PROPOSED.
2. Egress: D2c kept interface endpoints. With 8 or 9 endpoints the project costs $0.70 LOW and $2.06 HIGH more than NAT and S + M HIGH is $25.42 (over the ceiling) instead of $23.41 on the same basis. Keep endpoints, or switch to NAT for run sessions (an outbound internet path). Other cheaper options are P1 to P3 in section 3a.
3. Whether tier L stays at the end of Phase 15 behind gate G-L (decided in D2c; restated gate figure $5.18 at HIGH against the $20 ceiling, it was $10.18 against $25, and at HIGH S + M alone already exceeds it).
4. Vector dimension (256, 512, 1024; 384 only for the hash-embedder fallback, which needs its own environment).
5. Cap on real Bedrock queries (40 proposed).
6. Confirm the Paid-plan upgrade is acceptable only after this estimate is read (D4). Never use "activate advanced features".
7. How the smoke test reaches the services (infra README, "Smoke-test network path"): the options add a small cost and, for two of them, an IAM change. Not in this estimate.
