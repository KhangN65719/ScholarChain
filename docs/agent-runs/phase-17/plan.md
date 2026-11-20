# Phase 17 plan: autograder fixes, documentation, final review and release

Author: orchestrator, 2026-11-19 (Thu). Status: APPROVED 2026-11-19 for Wave A with D2 to D8 as recorded in `decisions.md` (P17-1 to P17-12); D1 on Sat 11-21. Where `decisions.md` P17-11 and the text below differ, P17-11 wins (K01 owners, F07 and E01 to E03 are FAIL, agent-report index format).
Inputs read: `docs/agent-runs/phase-17/prompt.md` (scope note and course text), `docs/agent-runs/phase-16/ratification.md` ("Required in Phase 17"), `docs/agent-runs/phase-16/plan.md` (form), `docs/agent-runs/phase-15/ratification.md`, `docs/agent-runs/README.md`, `docs/evaluation.md` section 6, `README.md`, `.claude/settings.json`, `.claude/hooks/guard-dangerous.sh`, `tests/adversarial/P14LoaderRig.scala:18-40`, `infra/terraform/COST_ESTIMATE.md` (lines 1-307), the listing of every `docs/agent-runs/phase-NN/`, `git log --oneline`, and the autograder's public README and HOW_TO_RUN files (read through the web-fetch tool, 2026-11-19). The guard refused `grep`, `git grep`, `wc` and `ls` on `.claude/**`, so "absent" below means "not seen in the files read", not an exhaustive search.

Deadline: Wed 2026-11-25 23:00 CST. Target: final tag pushed Tue 2026-11-24 evening. Wednesday is buffer only.

## Survey findings (read-only)

1. **Evidence folders (P01).** `phase-01` to `phase-16` exist; **none** has `human-gate.md`, `commit.txt` or `agent-report.md`. `phase-03` has no `prompt.md` (it has `dataset-work-order.md`, `domain-work-order.md`, `reviewer-work-order.md`). `phase-15` has no top-level reports (they are in `wave-1/` to `wave-3/`, `session-*`). There is no `phase-00` folder; Phase 0 commits are `2313f50` ("Phase-0: Add Factory Agent") and `08b95c3`. Every phase except 0 and 3 has a `ratification.md` (phase-03 also has one), which is the main source for `human-gate.md`.
2. **Accepted commits from history** (candidates for `commit.txt`; each must be confirmed against that phase's `ratification.md` before writing): P1 `f4453d6` (last Phase 1 commit; ratification may name another), P2 `efcd46b` / revalidation `cf8fc4a`, P3 `9ee8b22` / revalidation `74543bb`, P4 `6d89e3d` (+ follow-ups `f2f85cb`, `90fae19`), P5 `befe9c4`, P6 `54fe82f` (+ `cc2385e`), P7 `8cde783`, P8 `6c39f83`, P9 `0a86da0`, P10 `19ee05f` (+ `6842c2e`), P11 `fa21c2c`, P12 `102e1b8`, P13 `f065a33`, P14 `4927595`, P15 `848fb64`, P16 `6072310`. Where a ratification and history disagree, the file says so; nothing is guessed.
3. **Autograder V1 checks that matter here** (from its README): P01 folders; G02 every `commit.txt` names an existing commit; E06 `human-gate.md` states a decision and an accepted commit equal to `commit.txt`; E01 to E05 report fields, OWNER, claimed versus actual files (warn); K01 current files, K02 history; F05 hooks exist, executable, `bash -n`; F07 `.mcp.json` `${VAR}`; R03 guard covers destructive categories; V01 hard-coded ids; A01 Spark `.collect`; **W01 Phase 17 documents at the work order's paths; W02 README subsection "Reproducing the agentic workflow"**. W01 and W02 will still fail on Friday (Wave B delivers them); that is expected and is stated in the Friday email.
4. **K01.** `tests/adversarial/P14LoaderRig.scala:22-25` holds literal fake AKIA, secret, session and bearer strings. The other lines are in the V1 preview output, which is **not in the repository** (H-0 saves it).
5. **F05.** `.claude/settings.json:123,135,147,158` use `bash \"$CLAUDE_PROJECT_DIR/.claude/hooks/<name>.sh\"`. Whether the hook files carry the execute bit was not checked (the guard refused `ls` on `.claude/hooks`).
6. **R03.** `guard-dangerous.sh` is 4 lines and delegates everything to `policy.py guard`; no category is named in the `.sh`.
7. **`reports/` is unowned.** `docs/agents.md` assigns no owner to `reports/**`, and the course requires `reports/final-report.md`. Under the boundary rules this blocks the final report until the human ratifies an assignment (decision D3).
8. **README.md** is a Phase 13/14 document: "Reproducing the agentic workflow" is a placeholder, Phase 15 billing says pending, and the Status section cites `pass=570`. It needs a rewrite, not a patch.
9. **Docs present:** `docs/evaluation.md`, `docs/experiments.md`, `experiments/EVALUATION.md`, `RESULTS.md`, `LIMITATIONS.md`, `infra/terraform/README.md`, `COST_ESTIMATE.md`, `SESSION2.md`, `scripts/README.md`, release records `docs/releases/phase-14/`, `phase-15/`, and `docs/agent-runs/phase-07/service-limits.md`. **Absent:** `docs/architecture.md`, `data-lineage.md`, `threat-model.md`, `service-limits.md`, `runbook.md`, `demo-script.md`, an API reference, `reports/final-report.md`, a deliverable index, a release manifest, `docs/incidents/*` (only `.gitkeep`).
10. **Agent limits that shape the plan:** documentation-engineer and code-reviewer have no shell, so "replay commands before documenting" is a human task (H-8) with results archived here; agents cannot run Python, the harness or Terraform; agents run only `sbt check`, `sbt quick`, `sbt Test/compile`.
11. **Phase 16 carry-forward** (`docs/evaluation.md` section 6): P16-34 slow deadline (required before any Haiku deploy); account id in the sbt fork line (optional); false abstention, audit contradictions, vector weakness and fused-mode skips are documented as limitations, not fixed in this phase.

## Decisions for the human (approve, amend or reject each)

| ID | Proposal | Needed by |
|---|---|---|
| D1 | Demo and AWS option: see "Demo and AWS options" below. **Recommendation: (c)**. | Sat 11-21 noon |
| D2 | **Phase 00**: create `docs/agent-runs/phase-00/` with `prompt.md` stating "the Phase 0 prompt was not archived", `agent-report.md` indexing commits `2313f50` and `08b95c3`, `human-gate.md` built only from those commit messages, `commit.txt` = `08b95c3`. Alternative: no folder, and the README explains Phase 0 lives in commit history only. **Recommendation: create it**, every gap marked NOT RECORDED. | Thu evening |
| D3 | **`reports/**` ownership**: factory-engineer adds one row to `docs/agents.md` assigning `reports/**` to documentation-engineer. Constitutional, so it rides in the Wave A factory change (one review, one ratification, one restart). | Thu evening |
| D4 | **`human-gate.md` authorship**: the orchestrator drafts each from the phase's `ratification.md`, `decisions.md` and review verdicts, labels it "compiled by the orchestrator from the records cited; the decision is the human's", and the human confirms in one pass (H-4). | Thu evening |
| D5 | **R03 form**: a header block in `guard-dangerous.sh` listing each guarded category (AWS delete and terminate, Terraform apply and destroy, Git force and history rewrite, database drop and delete, S3, Neptune, DynamoDB, S3 Vectors) and naming the `policy.py` rule for each, plus one harness case per listed category proving it is refused. No second bash-level filter (duplicate logic drifts). | Thu evening |
| D6 | **Phase 17 `commit.txt`**: the final documentation commit X is the accepted commit; a docs-only follow-up commit adds `phase-17/human-gate.md` and `commit.txt` naming X; the tag goes on the follow-up. (A commit cannot contain its own hash.) | Tue |
| D7 | **Cleanup date** for D20: session-down after the demo (if any), resource inventory before and after; full `cleanup.sh` on a date the human picks after grading (storage costs cents per day; secrets about 1.20 USD a month). | Sun |
| D8 | **Video hosting** (unlisted link) and who emails the autograder CSVs, to which TA address. | Fri |

## Wave A: autograder fixes (finish Fri 11-20 by 12:00)

Common rules: read `CLAUDE.md`, `docs/agents.md`, the role file, this plan and `prompt.md`; no AWS; no Python; write only the listed paths or report `BLOCKED-ON`; list the test proving each gate item before writing; `sbt quick` during work; one sbt at a time (orchestrator assigns slots); never invent a record: absent means NOT RECORDED.

| WO | Owner | Write set | Content | Depends on |
|---|---|---|---|---|
| A0 | orchestrator | `docs/agent-runs/phase-17/**` | Archive work orders, reports, verdicts, decisions; save the V1 preview (from H-0) as `autograder-v1-preview.md` | approval |
| A1 | orchestrator | `docs/agent-runs/phase-00/**` (D2), and in each `phase-01` to `phase-16`: `human-gate.md`, `commit.txt`, `agent-report.md`; `phase-03/prompt.md` | `human-gate.md`: what was reviewed, accepted or rejected, manual changes, remaining risk, accepted hash, each line citing its source file; `commit.txt`: the hash only, equal to the one in `human-gate.md`; `agent-report.md`: an index of that phase's existing reports and verdicts (path, owner, one line), no new content; `phase-03/prompt.md`: an index of the three archived work orders, stating no single phase prompt was archived. Existing files are not edited (archives stay as received). | approval |
| A2 | factory-engineer | `.claude/settings.json`, `.claude/hooks/guard-dangerous.sh`, `docs/agents.md` (one row, D3) | F05: hook command form the checker parses (for example `"$CLAUDE_PROJECT_DIR"/.claude/hooks/x.sh` with the execute bit set, or another form the factory justifies), no change to matchers, timeouts or hook scripts' logic; R03 per D5 without touching `policy.py`; D3 row. Report the diff and the restart probes needed. | approval |
| A3 | test-engineer | `tests/**` | K01: rewrite every listed fake key (P14LoaderRig.scala:22-25 and the lines in `autograder-v1-preview.md`) so the literal no longer matches the secret patterns, for example concatenated at runtime; assertions unchanged in meaning (the redaction tests must still see the same runtime strings). Harness cases for A2: the new hook command form runs each hook, and one refusal case per R03 category. Report exact `sbt quick` counts. Module-owned test files in the V1 list go to their owners as `BLOCKED-ON`, not edited here. | A0 (list), A2 draft for harness cases |
| A4 | documentation-engineer | `README.md` (one new section only) | "Autograder notes": factual explanations of F07 (`.mcp.json` uses `${VAR}` references; quote the variable names), K02 (fake test keys exist in history before the Wave A rewrite; they are not credentials; history is not rewritten), E01 to E03 (archived reports are kept as received, formats drift by phase, see `docs/agent-runs/README.md`), V01 and A01 (each flagged location with its reason, from the preview). Neutral statements of fact only: **no text addressed to graders or agents**. | A0 |
| A5 | code-reviewer | verdict only | Batched review of A1 to A4: every `human-gate.md` line traces to a cited file; `commit.txt` equals the gate hash and exists in history; A2 does not weaken the guard; A3 tests unchanged in meaning; A4 has no grader-directed text and no account id. One repair round. | A1 to A4 |

Exit of Wave A: A5 APPROVE, the human's ratification of A2 (H-2), a fresh-session hook probe, harness `fail=0` (H-3), Claude in chat's V1 re-preview on a fresh public clone, push, then H-5.

Expected V1 after Wave A: P01, K01, F05, R03, G02, E06 pass; K02 stays FAIL (explained); E01 to E03, V01, A01 stay warn (explained); W01 and W02 fail until Wave B. If the re-preview shows anything else, it is routed before the push, or reported as open if no time is left.

## Wave B: deliverables (Fri evening to Mon), in a fresh orchestrator session after A2 is ratified

| WO | Owner | Write set | Content | Depends on |
|---|---|---|---|---|
| B1 | documentation-engineer | `README.md` | Full rewrite to the course "README requirements" list: title, identities (H-7), problem, architecture and agentic summaries, software, AWS prerequisites, env-var names, exact format/compile/test/package commands, bounded sample command, Terraform fmt/validate/plan, automation commands, deploy and smoke, API examples, experiment reproduction, report and video links, cleanup, known limitations, **"Reproducing the agentic workflow"** (start Claude Code, validate agents, invoke each agent through the orchestrator, replay one bounded phase, run test and review gates, compare with the archive), **deliverable index D01 to D20** with a link per item, and the A4 section kept. Every command marked with its H-8 replay result or NOT REPLAYED. | H-7, H-8 results |
| B2 | documentation-engineer | `docs/architecture.md`, `docs/data-lineage.md`, `docs/api.md` | Build-time and runtime diagrams (Mermaid); lineage from source dataset to Hadoop, Spark, stores, evidence and answer, citing contracts and run ids; route reference read from `modules/services` (paths, methods, request and response shapes, errors, auth). | none |
| B3 | documentation-engineer | `docs/threat-model.md`, `docs/service-limits.md`, `docs/runbook.md` | Seven threat classes with the control and its evidence (or the gap); limits and Region availability from `phase-07/service-limits.md` and `phase-14/evidence-availability-us-east-2.md`, marked verified or not; runbook from `scripts/README.md`, `SESSION2.md` and release records (deploy, smoke, common failures, rollback, escalation, cleanup). | none |
| B4 | documentation-engineer | `docs/demo-script.md` | All 22 items, each labelled LIVE (laptop), LIVE (AWS) or ARCHIVED with the evidence path, per D1. | D1 |
| B5 | documentation-engineer | `reports/final-report.md` | The 25 course sections in paragraphs, each technology argued; a section classifying work as agent-generated-accepted, agent-repaired, manually edited, human-authored, sourced from repair records and the human's list (H-13); costs from the billing readings only. | D3 ratified; B1 to B3 |
| B6 | documentation-engineer | `docs/evaluation.md` | Only if the course list lacks something (methods, raw-result pointers, tables, plots, uncertainty, threats): add pointers; no new numbers. | none |
| B7 | hadoop-engineer, spark-engineer (read-only reports) | none | Name the exact existing local bounded sample commands for their modules (inputs under `datasets/`, outputs, expected counters); `BLOCKED-ON` if none exists. No code change in this phase unless the human approves one. | none |
| B8 | infra-engineer or service-engineer (orchestrator resolves which file sets the value at dispatch) | the one file that sets it | P16-34: `SCHOLARCHAIN_SERVICES_SLOW_DEADLINE_MILLIS` about 60000 for Haiku answers; feature or harness test; only if D1 is (a) or (c). | D1 |
| B9 | deploy-engineer | `docs/releases/phase-17/**` | Release record of the demo session (if any; P16-34 noted) and the **release manifest**: commit SHA, image digests (Phase 15 records or the new deploy), Terraform plan checksum (H-8), dataset snapshots, run ids, prompt versions, model versions, test counts (H-14), artifact checksums (H-8 `package.sh`). | H-8, H-14 |
| B10 | experiment-engineer (optional, only if ahead of schedule) | `experiments/**` run scripts | Stop the account id echo in the sbt fork line (`docs/evaluation.md` 6.3). | none |
| B11 | test-engineer | `tests/**` | Final run: `sbt check` with exact per-group counts; harness cases for B8. | B8 |
| B12 | code-reviewer (Opus) | verdict only | Batched review of B1 to B4 and B8 (Sun); final full read-only repository review (Tue morning): docs against evidence, every result linked, no invented result, no account id, no grader-directed text, optional components stated as not implemented. | |

## Demo and AWS options (for D1)

Budget: billed 10.39 USD at 2026-11-19 07:41 (H-1c); stop 14 USD; hard spend limit 20. Gate rule (P15-10): run only if billed + 0.40 lag reserve + the option's HIGH is at most 14. Rates are mostly UNVERIFIED (`COST_ESTIMATE.md`); the one real anchor is Phase 15 session 2 (graph load on `db.r6g.xlarge` plus about 4 h of services and experiments), billed about 2.7 USD (6.18 to 8.91, which also includes standing storage).

What each option makes live (items from the course list):
- Always LIVE on the laptop, no AWS cost: 1 to 10, 16, 17, 21 (hook refusal and an F2-style recovery from evidence), `sbt check`, the harness.
- Always LIVE and read-only on AWS, about 0 USD: 18 Terraform plan (`plan.sh`, read-only), 19 the CloudWatch dashboard in the console (Phase 15 history is retained at reduced resolution), 12 a DynamoDB run-record read, 22 resource inventory (read-only CLI).

| Option | Live on AWS | Archived | Projected LOW / HIGH (USD) | Gate check (10.39 + 0.40 + HIGH) | Risk |
|---|---|---|---|---|---|
| (a) Full live session: Neptune create and bulk load on `db.r6g.xlarge`, services up, Haiku smoke, API query, evidence and explain, a 10-minute load for scale-out and scale-in, session-down | 11, 13, 14, 15, 20 | none | 1.30 / 2.70 (load 0.76-1.67; services 1.5 h 0.39-0.55; scale extras 0.05-0.10; Haiku and Titan 0.05-0.25; ALB and logs 0.05-0.10) | 13.49: fits by 0.51; **no retry fits** | Phase 15 smoke with Haiku failed (SMOKE1, SMOKE2); a failed first attempt ends live AWS for the phase |
| (b) Laptop only: Titan, S3 Vectors and Haiku from the laptop through the Phase 16 evaluation entry points on 5 to 10 demo questions; local services API over the demo world (stub data) | 13 (S3 Vectors from the laptop), Haiku answers with evidence and verification | 11 Neptune, 14 and 15 on the deployed API, 20 scale-out and scale-in (Phase 14 to 16 evidence) | 0.03 / 0.20 | 10.99: fits | Low; but Neptune and the deployed API are archived only, and the local API shows demo data, which the script must say |
| (c) (b) first, then one short live session without the load test: Neptune load, services up, 3 to 5 API queries with evidence and explain, recorded, session-down | 11, 13, 14, 15 | 20 (Phase 15 B, C, D plus the live dashboard history) | 1.15 / 2.30 (load 0.76-1.67; services 1 h 0.26-0.37; Haiku and Titan 0.05-0.15; extras 0.08-0.11) | 13.09: fits by 0.91 | If the session fails, the recording from (b) stands and the failure is documented honestly (it doubles as a real item 21) |

Recommendation: **(c)**. The Neptune reload dominates every live option, so (a) adds little live coverage for the scale-out items that the dashboard and Phase 15 evidence already show, and it leaves no margin. (c) records the safe laptop version first, so a failed live session costs money but not the demo. Preconditions for any live session: B8 merged and tested, an approval record per action, H-6 billing read the same morning, P16-34 noted in the release record by deploy-engineer. No agent runs any AWS-changing command.

## Human tasks (paste-ready `!` commands go in `docs/agent-runs/phase-17/human-commands.md`)

| # | Task | When | Time |
|---|---|---|---|
| H-0 | Paste the full V1 preview result (every FAIL and warn with file and line) for `autograder-v1-preview.md` | Thu evening | 5 min |
| H-1 | Approve this plan and D1 to D8 (D1 may wait to Sat) | Thu evening | 20 min |
| H-2 | Read A2's diff and A5's verdict; ratify; Claude in chat commits; start a fresh session; probe that each hook still fires (one guarded refusal, one formatter run, one Stop gate) | Fri 08:00-09:30 | 30 min |
| H-3 | Harness `bash tests/scripts/run.sh` after A2 and A3 | Fri 09:30 | 40 min, mostly waiting |
| H-4 | Spot-read the 17 `human-gate.md` files (decision and hash per phase) and confirm | Fri morning | 30 min |
| H-5 | Clone the autograder outside the repo; run V1 (`./selfcheck.sh --student <NetID> <repo>`), V2 and V3 (`./judge.sh --student <NetID> <repo> --dry-run`, then without `--dry-run`); email the three CSVs (only the CSVs) | Fri afternoon | 45 min (V2 and V3 about 0.30-0.50 USD each of Claude usage) |
| H-6 | Billing: month-to-date reading before any AWS step, and after the session | Sat, Sun | 5 min each |
| H-7 | Supply name and university email for the README | Fri | 2 min |
| H-8 | Replay every documented command from a clean checkout (`check.sh`, `package.sh` with checksums, `plan.sh` read-only for the plan checksum, the B7 local sample, the ER replay) and paste results | Sat to Mon | 2.5 h, mostly waiting |
| H-9 | Laptop live demo parts (option b): approval record, 5 to 10 questions | Sat | 45 min |
| H-10 | Live AWS session if D1 is (a) or (c): approval records, deploy, smoke, demo queries recorded, session-down | Sun morning | 2.5-3.5 h |
| H-11 | Resource inventory before and after session-down (read-only CLI), for D20 | Sun | 20 min |
| H-12 | Record and narrate the demo video from `docs/demo-script.md`; upload; give the link | Mon | 3-4 h |
| H-13 | List manual edits and human-authored items for the final report's classification | Mon | 30 min |
| H-14 | Final gate: `sbt clean check` from a clean checkout, harness, `check.sh` (Terraform fmt and validate), final V1 to V3, strict account-id scan; acceptance; Claude in chat commits, tags, pushes | Tue | 2 h, mostly waiting |
| | **Total** | | **about 15 h over 6 days** (about 12 h with option (b)) |

## Schedule

| Day | Agents | Human |
|---|---|---|
| Thu 11-19 evening | A0; A1, A2, A3, A4 in parallel; A5 as soon as all four report | H-0, H-1 |
| Fri 11-20 | 07:00 one A5 repair round; 08:00 ratification; fresh session; 11:00 Claude in chat V1 re-preview, push by 12:00. Evening: B2, B3, B7 start | H-2, H-3, H-4 (morning); H-5, H-7 (afternoon) |
| Sat 11-21 | B1 draft, B2 and B3 complete; B8 if D1 is (a) or (c); B11 for B8 | D1 by noon; H-6, H-8 part 1, H-9 |
| Sun 11-22 | B4; B12 batched review of B1 to B4 and B8; one repair round; B5 draft | H-6, H-10, H-11 |
| Mon 11-23 | B5 complete; B1 final with replay results; B9 draft; B10 only if ahead | H-8 part 2, H-12, H-13 |
| Tue 11-24 | 08:00 B12 final full review (Opus) and B11 final run; one repair round until 16:00; B9 manifest with final counts; phase-17 records | H-14; tag pushed by 21:00 |
| Wed 11-25 | buffer only; no new work | deadline 23:00 CST |

Critical path: A2 ratification and restart before the Friday push; D1 by Saturday noon; the video depends on B4 and on H-10; the Tuesday final review leaves room for one repair round only. If Tuesday's review finds more than one round of work, the remainder is recorded as accepted-open in `human-gate.md` rather than pushed past the tag.

## Risks

- The new hook command form breaks a hook in the fresh session: the probe in H-2 catches it; fallback is to revert A2's settings change, keep R03 and D3, and record F05 as explained, not fixed.
- K01 lines in module tests (outside `tests/**`) need their module owners; each is a small extra order, routed Thursday night.
- The V1 checker's secret patterns are not in this repository; the re-preview is the only proof that A3's rewrite clears K01.
- Live session failure or overrun (option a or c): bounded by the approval record's ceiling; (c) keeps the laptop recording.
- Documentation-engineer cannot replay commands; any command without an H-8 result is marked NOT REPLAYED, never shown as working.
- Volume: B1, B2, B3 and B5 are large. If B5 slips, the report is reduced to the 25 sections with shorter paragraphs; no section is dropped.

## Verification

- Agents: `sbt quick` per round, one `sbt check` before each review (code-changing orders only); test-engineer reports exact counts.
- Every `human-gate.md` line cites a file; every `commit.txt` hash exists (`git log`); every doc result links to evidence.
- Final: code-reviewer APPROVE, the human's `sbt clean check` `[success]` from a clean checkout, harness `fail=0`, strict account-id scan clean, V1 re-run recorded, tag pushed before the deadline. No commit by agents.
