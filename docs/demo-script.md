# ScholarChain demonstration script

Narrated script for the demonstration video (deliverable D19, human step H-12). It covers the 22 items of the course "Demonstration requirements" in order, on the basis recorded as decision P17-26 (option iii): laptop runs plus archived evidence, with no live AWS session. Option (i), a live Neptune load and query, stays optional for item 11 only.

Status of this file: written from repository files; nothing in it was run by its author, who has no shell tool. Every command, file name and expected output below is NOT REPLAYED until the human records a rehearsal or the take. No result is claimed as live. Where an expected output comes from an archived record, the record is cited, and it is an earlier run, not a promise about this one.

Labels used for each item:

- **LIVE (laptop)**: run on the presenter's laptop during recording.
- **LIVE (AWS, read-only)**: a free read-only console page or CLI call. No AWS-changing command is run in an option (iii) item. The only exceptions are the optional live path (i) of item 11, which is human-run under approval records, and the item 4 refusal probe `aws s3 rm s3://example-placeholder/x`, which the guard must refuse before it runs (see item 4).
- **ARCHIVED**: shown from a committed file, path given.

Replay marks: NOT REPLAYED (nobody has run it against this tree for this script), UNVERIFIED (not seen in a file read), NOT RECORDED (no record holds it), PENDING (names who supplies it).

## 1. Prerequisites and setup

### 1.1 Have open before recording

- Terminal A: repository root, clean working tree on `main` (`git status --short` empty except the held `docs/agent-runs/phase-17/` and `reports/final-report.md` until they are committed). `package.sh` and `deploy.sh` refuse a dirty tree, and an untracked file such as `docs/.DS_Store` counts as dirty (`scripts/README.md`, "From a clean checkout").
- Terminal B: for `curl` only. Sbt is run in one terminal at a time (project rule). The local services run in items 14 and 15 occupy one sbt, so nothing else uses sbt then.
- An editor or file viewer open at the repository root, for the ownership map, a role file, `.claude/settings.json`, the SVG plots and the archived records.
- A browser with two tabs: the AWS console signed in to the read-only profile (items 12, 19, 22; region us-east-2) and the repository page.
- Claude Code installed (version in README "Version record"; 2.1.328 human-reported). Start it from the repository root with `claude`. Do not approve any MCP server on camera; the four servers in `.mcp.json` stay disabled until approved one by one (`CLAUDE.md`, "MCP trust boundaries").
- A rehearsal of every LIVE item first. The live refusal in item 4 and the Stop gate in item 5 were NOT RECORDED after the last restart (README "Reproducing the agentic workflow", step 5 and 6), so a first observation on camera is a risk.

### 1.2 Environment variable names (never values)

- `AWS_PROFILE`: the named profile for the read-only AWS items. `experiments/g-retrieval/README.md` uses the profile name `scholarchain`.
- `SCHOLARCHAIN_GITHUB_AUTH_HEADER`, `SCHOLARCHAIN_AWS_INSPECTION_PROFILE`: MCP variables from `.mcp.json`. Leave them unused in the take.
- `QUERY_TOKEN`, `READ_TOKEN`: shell variables for the local services in items 14 and 15, standing for the two bearer tokens (`SCHOLARCHAIN_SERVICES_AUTH_QUERY_TOKEN`, `SCHOLARCHAIN_SERVICES_AUTH_READ_TOKEN`, `docs/api.md` section 2). The local run uses fixed placeholder tokens that `LocalRuntime` defines in source; read them from `modules/services/src/main/scala/scholarchain/services/runtime/LocalRuntime.scala` lines 63-64 into the variables before recording. They are nonsecret placeholders, and the real tokens never appear.
- `SBT_SERVER_FORCESTART` and `SBT_SERVER_AUTOSTART`: set for the Phase 6 human sbt runs (`docs/agent-runs/phase-06/spark-owner-report.md`). Whether they are needed now is UNVERIFIED.

### 1.3 Nothing sensitive on screen

- Do not run `env`, `printenv` or `echo` on any variable above, and do not open `~/.aws`, `.mcp.json` with an expanded variable, or any approval record.
- Mask the account id. Hide it in the console top bar (the account menu), and pipe any command that may print it through `sed -E 's/[0-9]{12}/************/g'` (the same mask the project's logs use). Never type the account id in a command shown on screen. Use `<id>` placeholders and set the real value in the shell before recording.
- No bucket name, ARN or secret name on screen. Do not run `aws s3 ls`, do not show `terraform output`, and do not scroll a raw Terraform plan (it can print resource attributes). Use the count-only `--query` forms below.
- Shorten the shell prompt so it does not show the home directory path.
- Close any window holding `scholarchain-data` logs, approval records or the Claude in chat session.

### 1.4 Spend

No item in the option (iii) path starts a paid AWS resource. The claim "no AWS-changing command is run" holds for option (iii) with two named exceptions: the optional live path (i) of item 11, and the item 4 probe `aws s3 rm s3://example-placeholder/x`, which is a delete-class command that the guard must refuse and which would fail on a placeholder bucket if it ever ran. Items 12, 18, 19 and 22 are read calls; item 18 runs `plan.sh`, which applies nothing. Items 9, 10, 15 and 16 run locally with no network. Item 14's local segment is offline. The optional laptop runs against Bedrock, Titan and S3 Vectors are in item 13 (the G retrieval run) and item 14 (the H answer runs, `experiments/h-answers/README.md`); each needs its own approval record (the H-4 diagnostic is the one run that bypasses `scripts/run-experiment.sh`, with in-process bounds) and its own Billing reading. Billing reference points: 10.39 USD month-to-date at 10:59 on 2026-11-19 with nothing running, against the 14 USD operating stop (`docs/agent-runs/phase-17/decisions.md`, P17-17).

## 2. Time budget

Narrated time per item, after cutting waiting time (an `sbt` run is started and the recording cuts to its end). The video length limit, if any, is NOT RECORDED in the repository.

| Item | Label | Minutes |
|---|---|---|
| Intro | n/a | 1.5 |
| 1 Repository structure | LIVE (laptop) | 1.5 |
| 2 Ownership map | LIVE (laptop) | 1 |
| 3 A role file | LIVE (laptop) | 1 |
| 4 Dangerous-command hook | LIVE (laptop) | 2.5 |
| 5 Test-stop gate | LIVE (laptop) | 3 |
| 6 Phase report and repair record | ARCHIVED | 2 |
| 7 Clean `sbt check` | LIVE (laptop) with recorded result | 2 |
| 8 Dataset manifest and samples | LIVE (laptop) | 2 |
| 9 Hadoop evidence and counters | LIVE (laptop) | 2.5 |
| 10 Spark evidence and outputs | LIVE (laptop) | 3 |
| 11 Neptune graph query | ARCHIVED (primary); optional LIVE | 2 |
| 12 DynamoDB record | LIVE (AWS, read-only) or ARCHIVED | 1.5 |
| 13 S3 Vectors query | ARCHIVED (laptop live optional) | 2 |
| 14 Complete query through the API | ARCHIVED, plus a local demo-data segment | 3 |
| 15 Evidence and explain endpoints | ARCHIVED, plus a local demo-data segment | 2 |
| 16 Claim verification | LIVE (laptop) | 2.5 |
| 17 Reproduction scripts and replay | LIVE (laptop) | 4 |
| 18 Terraform plan | LIVE (AWS, read-only) | 2.5 |
| 19 CloudWatch dashboard | LIVE (AWS, read-only) | 2 |
| 20 Scale-out and scale-in | ARCHIVED | 2.5 |
| 21 Failure and recovery | LIVE (laptop) plus ARCHIVED | 3 |
| 22 Cleanup or inventory | LIVE (AWS, read-only) plus ARCHIVED | 2.5 |
| Closing | n/a | 1 |
| Total narrated | | 52.5 |

Items that can be shortened if a limit applies: 6, 8, 17 (steps 1 to 5 only), 20.

## 3. Intro (1.5 min)

Show `README.md` top and the "Deliverable index" table. Narration: "ScholarChain builds a temporal coauthorship graph from scholarly records with Hadoop and Spark, loads it into AWS stores, and answers collaboration questions with a Graph-RAG service that cites evidence and abstains when the evidence does not support an answer. The whole project was built by a factory of Claude Code subagents under human gates. This video shows the 22 required items, marks each as live on this laptop, a read-only look at AWS, or archived evidence, and does not claim an archived result as live."

State once that the demonstration uses option (iii): no AWS resource other than storage is running, and the AWS-only items are shown from the records of the Phase 14 to 16 sessions.

## 4. The 22 items

### Item 1. Private repository structure

- **Label:** LIVE (laptop).
- **Show:** in terminal A, `git ls-tree --name-only HEAD`, then `git log --oneline -8`, then open the repository page in the browser. The README "Instructor-approved deviations" section records that the repository is public by the instructor's later instruction, which overrides the course text ("private repository").
- **Viewer sees:** top-level directories `modules/`, `infra/`, `scripts/`, `datasets/`, `experiments/`, `docs/`, `tests/`, `.claude/`, plus `CLAUDE.md`, `README.md`, `build.sbt`. Final tag: PENDING (human, at release); do not show a tag that does not exist yet.
- **Narration:** "The repository holds the Scala modules, Terraform, operations scripts, datasets, experiments, tests and the agent constitution under `.claude/`. It is public for the grading period by the instructor's later instruction."
- **Marks:** NOT REPLAYED. Deliverable index: D01.

### Item 2. Agent ownership map

- **Label:** LIVE (laptop).
- **Show:** open `docs/agents.md` at "Ownership matrix" (18 agent rows: exclusive owned outputs, excluded work, primary gate), then "Boundary rules". Then the README "Agent catalog" table for the same agents with their tool fences.
- **Viewer sees:** each artifact path has exactly one writing agent; subagents never call one another; routing goes through the orchestrator (`docs/agents.md`).
- **Narration:** "Every path has exactly one writer. The orchestrator delegates each work order to the owning agent, and an agent that meets a boundary reports BLOCKED-ON with evidence instead of editing another owner's files."
- **Marks:** NOT REPLAYED. Deliverable index: D02.

### Item 3. At least one agent role file

- **Label:** LIVE (laptop).
- **Show:** open `.claude/agents/code-reviewer.md` (read it before recording and narrate only what it contains). The README catalog gives its tool fence as `Read, Grep, Glob` and its model as `opus`; the verification is "none: returns APPROVE or REQUEST CHANGES". Optional contrast: `.claude/agents/documentation-engineer.md`, whose tools are `Read, Grep, Glob, Write, Edit` (read for this script).
- **Viewer sees:** frontmatter with name, model, description, tools; an Authority section; a Procedure; a Report section.
- **Narration:** "The reviewer has no write tool and no shell, so it cannot fix its own findings or approve its own work. A tool list in the role file is the maximum authority of the role."
- **Marks:** NOT REPLAYED. Deliverable index: D02.

### Item 4. A dangerous-command hook safely blocking a probe

- **Label:** LIVE (laptop).
- **Show, part 1 (no session):**
  ```
  bash .claude/hooks/guard-dangerous.sh --list-categories
  bash tests/scripts/hooks-guard.sh
  ```
  Expected, from the human relay recorded in `docs/agent-runs/phase-17/human-commands.md` and the README: 18 lines over 8 categories (AWS delete and terminate, Terraform, Git, database, S3, Neptune, DynamoDB, vector index), exit 0; the harness ends `RESULT pass=114 fail=0`. The harness run time is NOT RECORDED; cut to its last line.
- **Show, part 2 (session):** start `claude` at the repository root and ask the orchestrator to run `terraform apply`, then `aws s3 rm s3://example-placeholder/x`. Expected per the README: both refused by the guard hook, nothing executed. The result after the last restart is NOT RECORDED, so rehearse it. Run this session in a shell with no AWS credentials in its environment (unset `AWS_PROFILE` and any `AWS_*` credential variables first), so that a guard failure cannot reach a real account. Both commands would then fail anyway (no Terraform working directory, a placeholder bucket and no credentials), but stop the take if either runs. `aws s3 rm` is a delete-class command: this is the one AWS-delete-class command in the script, and it is shown as refused, not run.
- **Viewer sees:** a refusal message from the hook, and the category list.
- **Narration:** "The guard refuses direct dangerous requests before they run. The policy lives in `policy.py`; the shell file lists the guarded categories with example commands. The harness feeds each example to the policy and checks it is refused."
- **Limits to state:** hooks inspect ordinary tool inputs and do not analyze what an approved script does (`CLAUDE.md`, "Hook operation"). Live apply, deploy and rollback stay human-controlled.
- **Marks:** NOT REPLAYED. Deliverable index: D02, D13 (harness).

### Item 5. A test-stop hook or equivalent deterministic gate

- **Label:** LIVE (laptop).
- **Show:** open `.claude/settings.json` lines 142-163 (the `Stop` and `SubagentStop` entries, which run `.claude/hooks/verify-tests-ran.sh`), then `.claude/hooks/policy.py` function `verify_tests` (lines 270 onward). The rule: if any changed or untracked source file is newer than `.claude/.last-test-run`, the first Stop is blocked; a repeat stop is not blocked, so a turn cannot loop, and it reports an unverified stop.
- **Show, the probe:** `changed_paths()` reads `git diff HEAD` plus untracked files, so touching a tracked file leaves no diff and does not trigger the gate. Create an untracked disposable file with the human's `!` prefix inside the session, for example `! echo '// stop-gate probe' > modules/core/Probe.scala`, end the turn and observe the block. Then `sbt quick` (recorded at 65 to 71 s, `CLAUDE.md`, "Hook operation"), end the turn again and observe no block. Finally `! rm modules/core/Probe.scala` and `! git status --short`.
- **Open points, state them on camera:** the full loop (a block, then an agent run that clears it) is still unobserved (`CLAUDE.md`, restart probe P4-D); the block message text is NOT RECORDED; whether `sbt quick` accepts a comment-only Scala file is UNVERIFIED. Rehearse. A passing `sbt quick` means the quick set passed on these sources, not every test.
- **Viewer sees:** the block on the first stop, then the gate satisfied by the marker.
- **Narration:** "A finish is blocked when source files are newer than the last successful test run. `sbt quick` or `sbt check` updates the marker only after format check, compile and tests pass. The full suite is the human's `sbt clean check` at each phase gate."
- **Marks:** NOT REPLAYED. Deliverable index: D02, D04.

### Item 6. A phase report and repair record

- **Label:** ARCHIVED.
- **Show (Phase 10, services):** `docs/agent-runs/phase-10/` in this order, opening each file briefly:
  1. `work-order-01-service-engineer.md` and the owner report `report-01b-service-engineer.md` (the phase report);
  2. `review-round-1.md` (verdict REQUEST CHANGES with two majors M1 and M2, file locations and owners, per its header);
  3. `work-order-06-service-engineer-repair-1.md` and `report-05-service-engineer-repair-1.md` (the repair record);
  4. `review-round-2.md`, `ratification.md`, `human-gate.md`, `commit.txt` and `agent-report.md`.
  Read `review-round-2.md` and `ratification.md` before recording; this script does not state their verdicts.
- **Viewer sees:** a work order, an owner report, a reviewer's findings with owners, a repair work order and report, and the human's gate with the accepted commit hash. `agent-report.md` is an index compiled by the orchestrator in Phase 17, not a report by the phase owner (README "Known check findings").
- **Narration:** "Each phase keeps its work order, owner report, independent review, repair record, human gate and accepted commit. The reviewer found two major defects; the repair went to the owning agent, and the reviewer re-checked only what changed."
- **Marks:** archived; nothing replayed. Deliverable index: D03.

### Item 7. A clean `sbt check` result

- **Label:** LIVE (laptop). The recorded phase-gate results are shown as files.
- **Show, live:** `sbt check` from the repository root, started on camera and cut to its end; expected final lines `[success]`, each suite group with `Failed 0, Errors 0`. Run time for a non-clean `sbt check` is NOT RECORDED (the recorded `sbt clean check` runs are 1314 s and 1363 s). Fast alternative: `sbt quick`, 65 to 71 s on the recorded runs, which skips the suites named in `slowSuites` (`build.sbt:105-130`); say so if it is used.
- **Show, recorded:**
  - `docs/agent-runs/phase-16/ratification.md`: the human's `sbt clean check` at `ccbc1ec`, `[success]` in 1314 s, every group 0 failed and 0 errors, matched by a clean-checkout run group by group.
  - `docs/agent-runs/phase-17/human-commands.md`: the clean-clone run at `5157e0e` (2026-11-19), `[success] Total time: 1363 s`, nine groups, group totals 49, 389, 331, 501 (1 skipped), 473, 170, 260, 99, 1427.
  The final-tree `sbt clean check` is the human's gate H-14 and is PENDING; replace these with it when it is recorded.
- **Viewer sees:** `[success]` and a per-group count line.
- **Narration:** "`sbt check` verifies formatting, compiles every module and runs every test. The phase gate is the human's `sbt clean check`; these are its recorded results."
- **Marks:** NOT REPLAYED here. Deliverable index: D04.

### Item 8. A dataset manifest and actual source samples

- **Label:** LIVE (laptop). The python commands are run by the human.
- **Show:**
  ```
  python3 -B datasets/scripts/datasets.py validate
  ```
  then open `datasets/dataset-manifest.yaml` (sources, licenses, snapshot ids, checksums) and show a few lines of each sample: `head -c 600 datasets/samples/dblp.xml`, and `head -n 2` of `datasets/samples/openalex.jsonl`, `crossref.jsonl`, `orcid.jsonl`. Open `datasets/README.md` for the retrieval and sampling scripts under `datasets/scripts/`. Keep the head output short; the samples are public scholarly metadata.
- **Viewer sees:** the manifest, a validation line (the Phase 5 record shows "Validated manifest, sample checksums and row counts: dblp, openalex, orcid, crossref", `docs/agent-runs/phase-05/subset-run-logs.md`), and real source records.
- **Narration:** "The dataset package registers DBLP, OpenAlex, Crossref and ORCID snapshots with licenses and checksums. The validator checks the manifest and the sample checksums. These are the actual source samples the Hadoop sample runs read."
- **Marks:** NOT REPLAYED. Deliverable index: D05.

### Item 9. Hadoop job execution evidence and counters

- **Label:** LIVE (laptop), on the test-based sample (decision P17-20: there is no standalone local `hadoop jar` command).
- **Show:** the per-suite form for the human, which runs real MapReduce jobs under Hadoop's `LocalJobRunner`:
  ```
  sbt "hadoop / testOnly scholarchain.hadoop.PipelineDriverOutputsSuite"
  ```
  NOT REPLAYED, and no record of this exact form exists. The suite is in `slowSuites`, so `sbt quick` skips it and `sbt check` runs it; `build.sbt` comments about 10 s for the seven-stage driver run. Expected assertions (hadoop-engineer's report, UNVERIFIED until the human replays): driver returns 0, non-empty `part-*` outputs, DBLP `InputRecords=2` and `AcceptedRecords=2`, `EmittedPairs=30`, 6 teams, a repeat run identical.
- **Show, archived counters:** `docs/agent-runs/phase-04/validation.md` lines 504-513 (counters read off a completed job in run 7: `InputRecords=4`, `AcceptedRecords=3`, `DuplicateRecords=2`, `RejectedRecords=1`, `EmittedRecords=6`, and a reject written to `rejects/v1/MISSING_REQUIRED_FIELD/part`). The test that reads them is `MapReduceEndToEndSuite`, "job 1's counters, read off the completed job, match the input" (`modules/hadoop/src/test/scala/scholarchain/hadoop/MapReduceEndToEndSuite.scala:245`). The real-data subset run commands and results are in `docs/agent-runs/phase-05/subset-run-logs.md` and `hadoop-snapshot-report.md`.
- **Viewer sees:** test lines for the seven-stage driver, then the archived counter block.
- **Narration:** "Hadoop prepares publication, author and link records and coauthor pair candidates. Counters and rejected records are asserted by the tests and recorded in the Phase 4 validation. There is no single command that runs Hadoop, entity resolution and Spark end to end on the samples; that is a stated limitation."
- **Marks:** NOT REPLAYED. Deliverable index: D06.

### Item 10. Spark job execution evidence and graph outputs

- **Label:** LIVE (laptop), on the test-based sample (decision P17-21), plus archived output counts.
- **Show, live:** the real Spark pipeline over a built-in world, through the suite named in the README:
  ```
  sbt "sparkAnalytics / testOnly scholarchain.spark.CollaborationPipelineSuite"
  ```
  This per-suite form follows the pattern of the Hadoop form in P17-20 and the project id at `build.sbt:361`; no record of it exists (NOT REPLAYED). The suite is in `slowSuites` (79 to 88 s per test per the `build.sbt` comment), and `sbt check` runs it. Cut the wait.
- **Show, archived outputs:** `docs/agent-runs/phase-06/validation.md`, V3 (real Spark run on the subset, `SPARK-PIPELINE RESULT: SUCCESS`, `[success] Total time: 380 s`, 25 tables written, 17,569 researcher nodes, 98,661 coauthored edges across windows, 41,885 summaries, 0 integrity violations, all 11 invariants pass at that time). The command with placeholder paths is in the README "Bounded local sample"; its inputs (an entity-resolution run and a Hadoop snapshot) are not in the repository, so the command is not shown as runnable from a clean checkout. The later code states 13 invariants (`CollaborationPipeline.scala:936-1111`, per the Spark engineer's read); the Phase 6 counts came from older code and an older subset.
- **Viewer sees:** the suite passing, then the archived table and invariant lines.
- **Narration:** "Spark builds the temporal weighted coauthorship graph, with per-window metrics, components, communities and bridge scores, and checks its own invariants. Here the real pipeline runs over a built-in world, and the larger real-subset run is shown from its Phase 6 record."
- **Marks:** NOT REPLAYED. Deliverable index: D08.

### Item 11. Neptune graph query

This item has two paths. The primary path is ARCHIVED. The second is OPTIONAL LIVE and is decided on Saturday 2026-11-21 after a fresh Billing reading (decisions P17-26 and P17-17).

**Path (iii), primary: ARCHIVED.**

- **Show:**
  - `docs/releases/phase-15/README.md` ("Session outcome"): the graph-only reload from experiment A's export, `SUCCEEDED` in 5.2 minutes, `NEPTUNE_LOAD=LOADED vertices=32225 edges=135788`; and the record `docs/releases/phase-15/deploy-tn-s1-a1.md`.
  - `docs/releases/phase-14/runs-s-6-to-11.md` and `docs/releases/phase-14/README.md`: the Phase 14 loads, including the Neptune out-of-memory failures on `db.t4g.medium` and the `db.r6g.xlarge` load that succeeded (runbook `docs/runbook.md` section 4).
  - Graph retrieval on the deployed service: `experiments/RESULTS.md` section 3.D, the graph-stage table (graph p50 3 to 4 ms, p95 12 to 27 ms across the four load levels, `experiments/RESULTS.md:253`), and `docs/releases/phase-15/smoke-tn.md` STUB1 (4 of 4 answered with `evidence_items` 4, 3, 3, 8).
  - Graph integrity checks on the laptop copy of the export: `experiments/EVALUATION.md` section 1 (these read the export, not Neptune).
- **State honestly:** no standalone Gremlin or openCypher query transcript against Neptune is archived. The Phase 14 release record says Neptune cannot be queried from outside the VPC and that no independent query was made (`docs/releases/phase-14/README.md` line 36). The archived "query" evidence is the load report and the deployed service's graph-retrieval stage timing. Neptune no longer exists: it was destroyed at session down without a snapshot (`docs/releases/phase-15/README.md`, "Rollback gate").
- **Narration:** "Neptune holds the coauthorship graph. It is billed hourly, so it exists only during a session. This is the record of the load that put 32,225 vertices and 135,788 edges into it, and of the deployed service reading the graph. There is no live Neptune query in this video."

**Path (i), optional LIVE: a live Neptune load and query with no live Haiku.**

Use only if the human chooses it on Saturday. Every step is human-run, with an approval record per script and a typed IAM approval line where IAM changes, and session-down after. Sequence from the infra-engineer's read-only reports (`docs/agent-runs/phase-17/wave-b/report-B8-infra-engineer.md` and the stub-answer revision `report-B8b-infra-engineer.md`, which governs where they differ):

1. Billing reading first. Gate from P17-17: 10.39 + 0.40 lag reserve + 2.30 session ceiling = 13.09, at most the 14 stop. Use `--cost-ceiling-usd 2.30` (decimal acceptance UNVERIFIED) and cap the reload at about 15 minutes. Cost without Haiku (`report-B8b-infra-engineer.md`, section 4): projected HIGH about 1.21 USD at UNVERIFIED rates (xlarge window 40 min x 0.97 = 0.65; services 60 min x 0.37 = 0.37; teardown 0.09; other 0.10), a margin of 1.09 to the 2.30 ceiling. Sensitivities: a 60-minute xlarge window gives 1.53; 90 minutes gives 2.02; 90 minutes of xlarge plus 120 minutes of services gives 2.39, over the ceiling. Keep the services window to 60 minutes or less. The earlier 1.9 to 2.0 USD figure in the B8 report included about 0.72 USD of Haiku and does not apply.
2. A1: `course.tfvars` plus the S overlay plus `graph-load` (`db.r6g.xlarge`) plus `session-down-services` plus `session2` (off-repository), then `bash scripts/deploy.sh` with an approval record and a typed IAM line. The last two overlays keep the services stack and combined layout, because the `SESSION2.md:228` A1 command omits `enable_services_stack` and `service_layout` and a literal plan might destroy the stack (report B8, line 42; report B8b section 3, step 1). Expected IAM: 2 creates (the Neptune bulk-load role, principal `rds.amazonaws.com`). Stop on any role, ECR, log-group or security-group destroy, or a Neptune replace.
3. Reload: a loader-only run through `bash scripts/run-bounded-pipeline.sh` (record action `pipeline`) with `--size small --max-minutes 15 --spend-ceiling-usd 0.25` (report B8b section 3, step 2).
4. A2-stub overlay set (report B8b section 1): `course` plus `experiment-size-s` plus `services-session` plus `session2` (off-repository), with no `services-smoke.tfvars`. This is the Phase 15 A3 set (`SESSION2.md:71, 230`). The answer model is the stub, set at `services-session.tfvars:49`, so no Haiku call is made and P17-26 holds. Keep `enable_bedrock` (dropping it saves about 0.03 USD but gives untested plan shapes and removes the loader's Titan path). Then `bash scripts/deploy.sh` with an approval record. Expect no IAM change; the writer goes from xlarge to t4g in place. The slow deadline of P16-34 (`slow_deadline_millis = 60000`, set in `services-smoke.tfvars`) is not needed without Haiku: the default 10 s applies and stub queries take milliseconds. Unverified: that the off-repository `session2.tfvars` sets no `answer_model` or `slow_deadline_millis`; the human checks it before planning.
5. Before the first query: the Phase 15 `session2.tfvars` is not in the repository (`infra/terraform/SESSION2.md:229` passes it from the human's data directory), so the human supplies that file. Rewrite its `load_client_image_tag` (line 5 per the B8 report) to the session HEAD SHA, push the load-client image at that SHA, and confirm the three auth secrets hold values (`infra/terraform/SESSION2.md` section 8).
6. Queries: 4 queries (`--limit-queries 4`) through a load-client `ecs run-task` (no approval record). The client records status, evidence count and abstained, never the answer text; evidence and explain cannot be shown live on AWS because the load balancer forwards only port 8081 (report B8, gaps 1 and 3).
7. Teardown (`SESSION2.md:231` form; report B8b section 3, step 6): `session-down` plus `session-down-services` plus `session2` with `STATEFUL_CHANGE_ACKNOWLEDGED=yes`. Expect 2 IAM destroys. Then the post-teardown read (item 22 commands).

Risks to state (report B8b): SMOKE2 abstained live in Phase 15 and the Phase 16 fix is unproven on AWS, so a stub query may abstain too; evidence and explain are not reachable live (port 8080); the load client records no answer text, only status, evidence count and abstained; `--cost-ceiling-usd 2.30` (decimal) acceptance is UNVERIFIED because `lib.sh` was not read; the cost figure uses UNVERIFIED rates. Any destroy of a role, ECR repository, log group or security group, or a Neptune replace, in the first read-only plan is a stop. Four queries (`--limit-queries 4`) are planned.

- **Marks:** NOT REPLAYED; path (i) not decided. Deliverable index: D09, D14, D15.

### Item 12. DynamoDB run or query record

- **Label:** LIVE (AWS, read-only) if the human chooses; otherwise ARCHIVED.
- **Show, live (read-only; no AWS-changing command is run):** the AWS console, DynamoDB, Tables, region us-east-2, with the table list and the table overview (item count, status). The console table page can display the table ARN, so prefer the CLI forms below and keep the console to the table list; if the overview must be shown, mask the ARN. The table names in the committed records are `scholarchain-course-evidence-bundle` and `scholarchain-course-query-session` (`experiments/RESULTS.md` section 3.D). These CLI forms are composed for this script from those names and are not committed commands; the human confirms they are read-only before use:
  ```
  aws dynamodb describe-table --table-name scholarchain-course-query-session --region us-east-2 --query 'Table.{Status:TableStatus,Items:ItemCount}'
  aws dynamodb scan --table-name scholarchain-course-query-session --region us-east-2 --select COUNT --max-items 5
  ```
  `--select COUNT` returns a count and no items. Do not scan with item output: items can hold question text. Whether any items remain after the Phase 15 session is NOT RECORDED.
- **Show, archived:** `experiments/RESULTS.md` section 3.D (consumed write capacity per minute at its peak for the two tables: 10,243 for the evidence table at 00:39Z and 2,713 for the query-session table at 00:53Z; no throttle datapoints), and `experiments/results/f/exp-F-20261118T013740Z-38421c6f/derived-verdict/verdict.csv` (the duplicate-message check, "one ledger entry exists, an Item, present, DONE", result PASS). Which table holds that ledger entry is not stated in the file read.
- **Viewer sees:** table status and an item count, or the archived capacity figures and the verdict row.
- **Narration:** "DynamoDB holds the query sessions, evidence bundles and the embedding ledger. It is on-demand storage that stays after a session. This view reads metadata only."
- **Marks:** NOT REPLAYED. Deliverable index: D09, D14.

### Item 13. S3 Vectors query returning source pointers

- **Label:** ARCHIVED. A laptop live run against S3 Vectors is optional and needs approval (below).
- **Show, archived:**
  - `experiments/EVALUATION.md` section 6.1, run `exp-G-20261119T053608Z-2e8943cf`: retrieval in graph, vector and fused modes, Titan v2 embeddings (256 dimensions) and S3 Vectors queries from the laptop, with pooled recall at K = 10 of 0.1498 for vector, 0.4935 for graph and 0.5008 for fused, and the limits (`docs/evaluation.md` section 2.3).
  - `experiments/g-retrieval/README.md`, "What it measures": each record holds the ranked ids retrieved per question and mode; the raw per-question records are `records.jsonl` outside the repository, hashed by `RAW-SHA256SUMS`.
  - `experiments/RESULTS.md` section 3.D and 3.E: the vector-stage latency in the deployed service (p50 485, 311, 416 and 3,250 ms at 1, 8, 24 and 48 clients).
- **State honestly:** the repository holds no committed excerpt of a vector query that prints source pointers. The raw `records.jsonl` is outside the repository and was not read for this script. If the human wants a source-pointer view, open the raw file first, confirm it holds the ids and no account id or bucket name, and show only masked lines. Evidence ids of deployed answers are not captured in the Phase 15 data (`experiments/RESULTS.md` requirement 2, "counts only").
- **Optional LIVE (laptop, against AWS data services):** the G run through `scripts/run-experiment.sh` with experiment id G, using the paste-ready block in `experiments/g-retrieval/README.md`; it needs a manifest copy, an approval record, a clean tree, the filled benchmark committed, about 10 to 40 minutes, a Titan call cap of 2000 and a USD stop of 0.25 (frozen run estimate 0.000117 USD, `experiments/EVALUATION.md` section 7.2). The block holds absolute paths with a user directory and bucket placeholders; do not show it on screen. Decide after a Billing reading.
- **Narration:** "S3 Vectors holds the semantic summaries. In the retrieval evaluation, vector search alone is weak on this benchmark and graph retrieval is strong; fusion adds little. Those are negative results and are kept in the evaluation."
- **Marks:** NOT REPLAYED. Deliverable index: D09, D10, D16.

### Item 14. A complete user query through the API

- **Label:** ARCHIVED (deployed API). Added segment: LIVE (laptop), local services over demo data.
- **Show, archived:** `docs/releases/phase-15/smoke-tn.md`: STUB1 PASS (4 of 4 answered with evidence, stub answer model), SMOKE1 FAIL and SMOKE2 FAIL (Haiku synthesis rejected). `experiments/RESULTS.md` section 3.D (7,428 requests across four load levels with the stub model). The Haiku answers over 56 questions are from the laptop evaluation H (`experiments/EVALUATION.md` section 6.2, run `exp-H-20261119T053926Z-2e8943cf`; laptop-to-Bedrock, graph port `InMemoryNeptune`, not the deployed HTTP API).
- **State honestly:** no archived complete query through the deployed HTTP API returned a Haiku-synthesized answer. The Phase 16 fix for synthesis truncation is proven on the laptop evaluation (`docs/evaluation.md` section 2.4) and unproven on AWS. The services have no public ingress (`README.md`).
- **Optional LIVE (laptop, against Bedrock, Titan and S3 Vectors): the H answer entry points** in `experiments/h-answers/README.md`, used only if the human chooses and after a Billing reading. Two forms: the H-4 diagnostic (`AnswerEval --diagnostic`, at most 4 Haiku calls and at most 0.15 USD, bounds enforced in the process, no approval record; about 1 to 3 minutes expected; for named questions `--question-ids Q-RK-01,Q-RK-02` with a new run id), and the full H run through `scripts/run-experiment.sh` with experiment id H (manifest copy, approval record, clean tree, USD stop 3.00, about 15 to 60 minutes; far too long for the video). Both are laptop-to-Bedrock with the graph on `InMemoryNeptune`, not the deployed API. The paste-ready blocks contain absolute paths with a user directory and bucket placeholders, so do not show them on screen; rehearse them off camera. Otherwise this item stays ARCHIVED from run `exp-H-20261119T053926Z-2e8943cf`.
- **Show, live (laptop), demo data:** say before starting: "This is the local services over a bundled demo world, not the deployed system and not the real corpus."
  - Terminal A: `sbt "services/runMain scholarchain.services.runtime.LocalRuntime"` (the main prints `fast service: <url>`, `slow service: <url>`, and the token placeholder note). This command is composed from the `main` method at `LocalRuntime.scala:260` and the project at `build.sbt:414`; no record of it exists (NOT REPLAYED, UNVERIFIED).
  - Terminal B, using the printed slow URL (shape from `README.md` "API query examples" and `docs/api.md`):
    ```
    curl -sS -X POST "<slow service url>/v1/query" \
      -H "Authorization: Bearer $QUERY_TOKEN" -H "Content-Type: application/json" \
      -d '{"question": "Which researcher has the most distinct coauthors from 2000-2009?", "retrievalMode": "fused", "mode": "sync"}'
    ```
    The question is the first demo question in `RuntimeDemoMain.scala:13`. The local runtime uses the offline stub model, so the answer text is the stub's. Save the `queryId`, `evidenceId` values and claim fields from the response for item 15.
  - Offline equivalent with no HTTP: `sbt "rag/runMain scholarchain.rag.RuntimeDemoMain"` prints the answer, claims with statuses, the verdict and the audit hash for six questions (`RuntimeDemoMain.scala:5-9`, "in a human session").
- **Viewer sees:** a JSON response with an answer, claims, evidence references, uncertainty and an abstained flag; field names are in `docs/api.md` section 4.2 (read it before recording).
- **Narration:** "A query goes to the slow service, which plans it, retrieves graph and vector evidence, synthesizes with the model, verifies every claim against the evidence and returns the answer with an evidence bundle. The deployed runs I show from the records; the local run uses demo data and a stub model."
- **Marks:** NOT REPLAYED. Deliverable index: D10, D11, D14.

### Item 15. Evidence and explain endpoints for the answer

- **Label:** ARCHIVED (deployed API). Added segment: LIVE (laptop), local services over demo data.
- **Show, archived:** `docs/api.md` section 4.1 (`GET /v1/evidence/{id}`, `GET /v1/explain/{id}`, `GET /v1/graph/nodes/{id}/neighbors`, read scope, port 8080) and section 4.2 for the query route. The Phase 15 service-engineer records on these routes are `docs/agent-runs/phase-15/wave-2/reports/wo-08-service-engineer.md` and `wo-08b-service-engineer.md` (they mention the routes; not read in detail for this script).
- **State honestly:** the deployed load balancer forwards only the slow port 8081, so no archived live call to the evidence or explain endpoint of the deployed system was found, and none can be made live in an option (i) session without a second target group (report B8, gap 1). Lookups work only while the answer is in the service's in-memory index (`README.md`, "Known limitations").
- **Show, live (laptop), demo data:** with the local services still running from item 14, in terminal B and using the fast service URL and the ids from the item 14 response:
  ```
  curl -sS "<fast service url>/v1/evidence/<evidenceId>" -H "Authorization: Bearer $READ_TOKEN"
  curl -sS "<fast service url>/v1/explain/<queryId>" -H "Authorization: Bearer $READ_TOKEN"
  ```
  Stop the local services afterwards (Ctrl-C in terminal A).
- **Viewer sees:** the evidence bundle (source identifiers, graph or retrieval evidence, uncertainty) and the explain record for that answer.
- **Narration:** "Every material claim cites an evidence bundle. The evidence endpoint returns that bundle and the explain endpoint returns how the answer was produced, so a reader can check the claim against its sources."
- **Marks:** NOT REPLAYED. Deliverable index: D11, D17 (`docs/api.md`).

### Item 16. Claim verification behavior, including one unsupported claim test

- **Label:** LIVE (laptop).
- **Show:**
  ```
  sbt "rag / testOnly scholarchain.rag.VerificationSuite"
  ```
  NOT REPLAYED; no record of this per-suite form exists. `VerificationSuite` is not in `slowSuites`, so it also runs in `sbt quick`. Then open `modules/rag/src/test/scala/scholarchain/rag/VerificationSuite.scala` at line 298, the test "a claim that cites nothing, or only missing evidence, is unsupported": a claim with no cited evidence, and a claim citing an evidence id that does not exist, both get the verdict `Unsupported`, and the report reason mentions that the evidence "do not exist". Companion tests in the same file: "a wrong value conflicts with the stored value and is removed" (line 101), "text retrieved from a vector cannot pose as a fact" (line 245).
- **Viewer sees:** the suite's test names and a pass line (count NOT RECORDED), then the test source.
- **Narration:** "The verifier checks each claim against the evidence it cites. A claim that cites nothing, or cites evidence that is not there, is unsupported and does not reach the answer as verified. A wrong number conflicts with the stored value and is removed."
- **State the limit:** the independent audit found 0 structural support failures in the frozen H run, and 2 of 199 claims contradicted; the verifier's own verdicts are self-reported (`docs/evaluation.md` section 2.4). The synthesis prompt version is 1.2.0.
- **Marks:** NOT REPLAYED. Deliverable index: D10.

### Item 17. Reproduction scripts, agent replay instructions, and a clean-checkout bounded run

- **Label:** LIVE (laptop).
- **Show, scripts from a clean checkout** (from `scripts/README.md`, "From a clean checkout"; clone into an empty directory outside the repository, with the working tree clean):
  ```
  git clone https://github.com/KhangN65719/ScholarChain ScholarChain && cd ScholarChain
  bash scripts/check.sh --dry-run
  bash scripts/package.sh --dry-run
  bash scripts/archive-evidence.sh --dry-run --include /abs/outside/repo/package-1
  bash scripts/plan.sh --dry-run --environment course --account-id <id> --cost-ceiling-usd 20
  bash scripts/run-experiment.sh --help
  ```
  Expected: each dry run prints `DRY_RUN=yes`, step lines `STEP[n] name: dry-run` and `WOULD_RUN:` lines and calls no cloud command. `bash scripts/check.sh --skip-sbt --skip-terraform` is the fast form; its last line is `CHECK_RESULT=PASS_WITH_SKIPS`, never `PASS`. The full `bash scripts/check.sh` and `bash scripts/package.sh --out-dir ...` are longer runs. Do not type the real account id on screen. The `--include` directory `/abs/outside/repo/package-1` does not exist in a fresh clone, because `package.sh --dry-run` writes nothing. Create an empty directory outside the repository first and use its real path (a typed path under the home directory shows the user name, so choose a short path outside it); whether `archive-evidence.sh --dry-run` accepts an empty directory is UNVERIFIED, so rehearse it, or run the real `bash scripts/package.sh --out-dir <that directory>` first so the directory is populated.
- **Show, agent replay (the README bounded exercise)**: Phase 7 work order 01, owner `domain-engineer`, archived at `docs/agent-runs/phase-07/work-order-01-domain.md`, report `docs/agent-runs/phase-07/report-01-domain.md`. Steps 1 to 5 of the README exercise:
  1. `git checkout -b replay-p7-wo01 cc2385e` (the starting state, decision P17-23; the final tag already contains the work).
  2. `bash .claude/hooks/validate-agent-files.sh` (human-relayed result 4 of 4 PASS).
  3. `claude`
  4. Paste `Use the domain-engineer agent to carry out this work order.` and the work order text (`git show <final-tag>:docs/agent-runs/phase-07/work-order-01-domain.md`; the final tag is PENDING). The order tells the agent to write logs under a directory on the original author's machine, so give it a directory of your own or none.
  5. Show the orchestrator delegating to `domain-engineer`. Cut to the end: `sbt check` (the Phase 7 record shows 1128 s for the full clean check; later phases take longer), then compare with the archived report: `StableIds.mentionCoauthorEdge`, id format `mention-coauthor:v1:<64 hex>`, a new `MentionCoauthorSuite` with 8 tests, the core suite 41 to 49 tests, `[success]` with Failed 0 and Errors 0 (archived `sbt quick` groups 181, 49, 389, 42, 267 at 66 s).
  Afterwards: `git checkout main` and delete the branch; commit nothing.
- **Show, the replay instructions:** README sections "Agent catalog" and "Reproducing the agentic workflow" (the 11 steps).
- **State honestly:** the agent replay uses Claude usage whose cost is NOT RECORDED; the outcome of an agent replay is NOT REPLAYED until the human archives it at H-8 (`docs/agent-runs/phase-17/`). A replay compares required artifacts, tests and results, not byte-identical wording.
- **Viewer sees:** dry-run markers, the branch checkout, a session delegating to the named agent, and the comparison with the archived report.
- **Narration:** "Every script has `--help` and `--dry-run`, and the clean-checkout path needs no AWS. To replay an agent phase, check out the recorded starting commit, open a fresh session, paste the archived work order, name the owning agent, run the gate and compare with the archived report."
- **Marks:** NOT REPLAYED. Deliverable index: D13, D02, D03, D17.

### Item 18. Terraform plan or deployed-resource evidence

- **Label:** LIVE (AWS, read-only).
- **Show, no AWS first:**
  ```
  terraform fmt -check -recursive -diff infra/terraform
  terraform -chdir=infra/terraform/environments/course init -backend=false
  terraform -chdir=infra/terraform/environments/course validate
  bash scripts/plan.sh --dry-run --environment course --account-id <id> --cost-ceiling-usd 20
  ```
  These are from `infra/terraform/README.md` ("Checks to run") and the README. `docs/agent-runs/phase-13/checks-1.md` records only the `terraform fmt`, `init -backend=false` and `validate` lines passing on 2026-11-11, before later Terraform edits (its overall exit was 1, from `shellcheck`, not Terraform). It does not cover `plan.sh --dry-run`. All are NOT REPLAYED on the final tree (human, H-8).
- **Show, read-only plan:**
  ```
  bash scripts/plan.sh --environment course --account-id <id> --cost-ceiling-usd 20 --extra-var-file "$TF/session-down.tfvars" --extra-var-file "$TF/session-down-services.tfvars" --extra-var-file "$S2" 2>&1 | sed -E 's/[0-9]{12}/************/g' | grep -E 'PLAN_DIGEST=|STATEFUL_DESTROY_OR_REPLACE'
  ```
  This pipeline is composed for this script. It is not a committed command and was never run. The overlay set follows the post-services-session teardown in `docs/runbook.md` section 7.1 (line 158) and `infra/terraform/SESSION2.md:231`; `session2.tfvars` is not in the repository (`SESSION2.md:229`), and if the services stack was never applied, section 7.1 says to use `session-down.tfvars` only. Choose the set that matches the state of the account, and rehearse the plan before recording. `TF` (the absolute path of `infra/terraform/environments/course`) and `S2` (the absolute path of the human's `session2.tfvars`) are shell variables set before recording, off camera, because a typed absolute path shows the home directory on screen. `plan.sh` needs absolute var-file paths. `plan.sh` applies nothing and calls AWS only for `sts` and the Terraform state (`scripts/README.md`). The `grep` limits what appears on screen; the markers it matches are the two named in `docs/runbook.md` section 2.2, and a full summary may use other marker names (UNVERIFIED). No apply, deploy, destroy or other AWS-changing command is run in this item. A Terraform plan may take a short-lived state lock; that is Terraform behavior and is not verified for this backend. The expected content of a session-down plan is in `docs/runbook.md` section 7.1. If a live plan is not run, show the archived plan summaries instead: `docs/releases/phase-15/deploy-tn-s4-down.md` and `docs/releases/phase-14/deploy-down-5.md`.
- **Also show:** the module layout under `infra/terraform/` and `infra/terraform/README.md`. The security scan (tflint, tfsec, checkov) was not run (`docs/agent-runs/phase-12/decisions.md:44`); say so. The plan checksum for the release manifest is PENDING (human, H-8).
- **Viewer sees:** a plan digest and, if shown, the stateful-change flag; the Terraform README.
- **Narration:** "All infrastructure is defined in Terraform. The plan script prints a digest that the human's approval record must name before any apply. Nothing here applies anything."
- **Marks:** NOT REPLAYED. Deliverable index: D12, D13, D14.

### Item 19. CloudWatch elasticity dashboard

- **Label:** LIVE (AWS, read-only).
- **Show:** the AWS console, CloudWatch, Dashboards, region us-east-2. The Terraform definition is the dashboard `<name_prefix>-scaling` (`infra/terraform/modules/observability/scaling_dashboard.tf:204-206`; the prefix appears as `scholarchain-course` in resource names such as `scholarchain-course-query`) with widgets for EMR nodes and pending containers, ECS tasks, CPU and memory, ALB p50, p95 and p99, request count and 5xx, SQS depth and dead letters, DynamoDB throttles and Neptune (lines 27-200), all on one time range. It exists only while the services stack is enabled (`environments/course/main.tf:303`), so it may have been destroyed at session down: whether it exists now is NOT RECORDED. Check the dashboard list before recording.
  - If it exists: open it and set an absolute time range covering the Phase 15 session (2026-11-17 22:30 UTC to 2026-11-18 02:15 UTC, from `docs/releases/phase-15/README.md`; the windows of runs B, C and D are in `experiments/RESULTS.md` section 1). Metric history is kept after a resource is deleted (a CloudWatch property, UNVERIFIED for these series; the dashboard file marks several metric names UNVERIFIED).
  - If it does not exist: show `scaling_dashboard.tf` and the archived plots (item 20) and say the dashboard is not currently deployed. Do not create, edit, save or import a dashboard in the console: direct console mutation is prohibited (`CLAUDE.md`).
  - No AWS-changing command is run. Do not click "Actions", "Save" or "Add widget".
- **Archived fallback:** `experiments/RESULTS.md` requirement 23: the dashboard was defined and reported created in A2 (`docs/releases/phase-15/deploy-tn-s2-a2.md:34`), but no screenshot or export of it is archived.
- **Viewer sees:** scaling widgets on one time axis, or the definition file.
- **Narration:** "The scaling dashboard is Terraform code. It puts the EMR, ECS, queue and load balancer metrics on one time axis so scale-out and scale-in can be read together. I am only reading it; I do not change anything in the console."
- **Marks:** NOT REPLAYED. Deliverable index: D12, D15.

### Item 20. Scale-out and scale-in evidence

- **Label:** ARCHIVED.
- **Show (open each SVG in the browser, then its table):**
  - Experiment B, EMR managed scaling: `experiments/results/b/exp-B-20261117T185928Z-5c69ba90/derived/capacity.svg` and `summary.md`. From `experiments/RESULTS.md` section 3.B: `scale_out_observed` yes and `scale_in_observed` yes; instances requested 2, 3 and 4 and released back to 1 core; the first scale-out request at 19:08:13Z, provisioned at 19:10:51Z (157 s). Two caveats to state: the third release is `INSTANCE_FAILURE`, not `TERMINATED`, and the cluster ended about 14 minutes before the state machine's terminate state.
  - Experiment C, embedding workers on SQS backlog: `experiments/results/c/exp-C-20261118T014553Z-38421c6f/derived-queue/queue.svg`. From section 3.C: 2,000 messages, peak backlog 1,804, drain time 406 s, workers 1 to 4 and back to 1 (the scale-in is in the autoscaling activities file at 02:00 to 02:04 UTC; the derived `scale_in_observed=false` reflects the poll window only).
  - Experiment D, query service: `experiments/results/d/exp-D-20261118T002157Z-38421c6f/derived-metrics/metrics.svg` and `derived-latency-v2/latency.svg`. From `docs/evaluation.md` section 2.5: service tasks went from 1 to 3; ok p50 3,768.982 ms at 48 clients against 337.545 ms at 8 clients; the error rate is at most 0.4212 percent. Each is one run with no interval for the elasticity events.
- **State the limits:** the Phase 15 answer model was the offline stub, so the latency has no language model in it; a separately scaled vector retriever and a separate rank and synthesis service were not implemented (`experiments/LIMITATIONS.md` L15, L23); Step Functions `Retry` evidence is not shown; billed Phase 15 cost beyond the human's Billing readings is NOT DERIVED.
- **Viewer sees:** capacity, queue and latency plots with the scale events marked, and the derived tables.
- **Narration:** "Experiment B shows EMR growing and shrinking under its managed scaling policy, experiment C shows workers scaling out on queue backlog and back in, and experiment D shows the query service scaling and where latency rises under load. All three are single runs and the limits are on the page."
- **Marks:** archived; nothing replayed (the B to F derived tables were regenerated by the human, 42 of 44 files identical, `docs/evaluation.md` section 2.7). Deliverable index: D15.

### Item 21. One failure and recovery example

- **Label:** LIVE (laptop) for the local refusal and recovery; ARCHIVED for the Phase 15 F2 evidence.
- **Show, archived (Phase 15 F2):** `experiments/results/f/exp-F-20261118T013740Z-38421c6f/derived-verdict/verdict.csv`: the poison message received 3 times and refused each time, never written, the dead-letter queue holding it (1), a duplicate message with exactly one embed and write, 50 of 50 normal messages completed, the main queue drained, PASS 9, FAIL 0, INFO 3. Then `experiments/results/f/exp-F-20261118T011431Z-38421c6f/README.md`: the first F run is INVALID as a recovery result (a `ChangedVectorContent` error on rerun), kept and not hidden; F2 is the result (`docs/releases/phase-15/README.md`, finding 4). The same record notes that the vectors were deleted outside the runbook.
- **Show, live (laptop), refusal then recovery with a documented, harmless script:**
  1. Create an untracked file in the repository root with `! touch demo-dirty-tree.txt`. Any untracked file counts as dirty (`scripts/README.md`).
  2. `bash scripts/package.sh --dry-run`. Expected per `scripts/README.md`: refused on a dirty tree even in a dry run, exit 1, with an `EVIDENCE:` line on stderr. The exact text is NOT RECORDED.
  3. `! rm demo-dirty-tree.txt`, then `bash scripts/package.sh --dry-run` again. Expected: the dry run proceeds to its `WOULD_RUN:` lines. Nothing is built and no cloud command is called.
  Precondition: the recovery dry run in step 3 passes only if the tree is otherwise clean, and `git status` counts untracked files as dirty (`scripts/README.md:322`: "A clean tree ... a run without `--allow-dirty` stops with `EVIDENCE: refusing: dirty=yes`"). Until `docs/agent-runs/phase-17/`, `docs/demo-script.md` and `reports/final-report.md` are committed, the main working tree is already dirty and step 3 would fail. So either commit those files first, or run item 21 in the fresh clone made for item 17 (a clone holds only committed files). Say which on camera. Rehearse first: the exact refusal text and exit code were not observed for this script.
- **Viewer sees:** the verdict table of F2, then a refusal with its evidence line, the file removed, and the dry run passing.
- **Narration:** "Experiment F injected a poison message and a duplicate. The poison message was retried, refused and dead-lettered, the duplicate was written once, and the queue drained. The first attempt was invalid and is kept in the record, labelled invalid. Locally, the same discipline: the script refuses to act on a dirty tree, tells you why, and proceeds once the tree is clean."
- **Marks:** NOT REPLAYED. Deliverable index: D15, D13.

### Item 22. Cleanup or resource inventory evidence

- **Label:** LIVE (AWS, read-only) for the current inventory; ARCHIVED for the earlier reads. The full cleanup is not run in the video.
- **Show, live (read-only; every verb is `describe` or `list`; no AWS-changing command is run):** the checks that `docs/runbook.md` section 7.3 names: no EMR cluster in a non-terminated state, no running ECS task, no Neptune cluster, no interface endpoint, no NAT gateway, no load balancer, no unattached volume, no running EC2 instance. The runbook says the exact inventory commands of the Phase 15 read are NOT RECORDED. The forms below are composed for this script from that check list, return counts only so that no identifier reaches the screen, and are not committed commands. The human confirms each is read-only before recording:
  ```
  aws emr list-clusters --active --region us-east-2 --query 'length(Clusters)'
  aws neptune describe-db-clusters --region us-east-2 --filters Name=engine,Values=neptune --query 'length(DBClusters)'
  aws ecs list-clusters --region us-east-2 --query 'length(clusterArns)'
  aws ecs list-tasks --cluster <cluster name> --region us-east-2 --query 'length(taskArns)'
  aws ecs describe-services --cluster <cluster name> --services <service names> --region us-east-2 --query 'services[].[desiredCount,runningCount]'
  aws sqs get-queue-attributes --queue-url <dead-letter queue url> --attribute-names ApproximateNumberOfMessages --region us-east-2 --query 'Attributes'
  aws ec2 describe-nat-gateways --region us-east-2 --filter Name=state,Values=pending,available --query 'length(NatGateways)'
  aws elbv2 describe-load-balancers --region us-east-2 --query 'length(LoadBalancers)'
  aws ec2 describe-volumes --region us-east-2 --filters Name=status,Values=available --query 'length(Volumes)'
  aws ec2 describe-instances --region us-east-2 --filters Name=instance-state-name,Values=running --query 'length(Reservations)'
  aws ec2 describe-vpc-endpoints --region us-east-2 --query 'VpcEndpoints[].VpcEndpointType'
  ```
  The four added lines cover the runbook 7.3 ECS-task, ECS-service-count and dead-letter-queue checks (`docs/runbook.md:175`). The Neptune engine filter stops `describe-db-clusters` from counting non-Neptune clusters. The cluster, service and queue names are placeholders to fill off camera from the Terraform resource names; do not show the queue URL (it contains the account id) and do not run `ecs list-clusters` with output other than the count. Whether the ECS cluster name appears in a committed record in a form that can be typed is NOT RECORDED here. The expected state after the last session-down is the one recorded in `docs/releases/phase-15/deploy-tn-s4-down.md`: no EMR cluster, no running task, no Neptune, only gateway endpoints, no NAT, Elastic IP, load balancer, unattached volume or running instance. The state today is NOT RECORDED until this step is run.
- **Show, retained state:** storage stays after a session: S3 buckets, DynamoDB tables, the S3 Vectors bucket and indexes, ECR repositories, log groups and three Secrets Manager secrets (`docs/releases/phase-14/README.md`, `docs/releases/phase-15/deploy-tn-s4-down.md`, `docs/runbook.md` section 7.1). Do not list them on screen.
- **Show, the cleanup path:** `bash scripts/cleanup.sh --help` (prints the argument line; four modes `plan-flip`, `apply-flip`, `plan-destroy`, `apply-destroy`), and `docs/runbook.md` section 7.2. Full cleanup is human-only, needs `--i-am-the-human`, `--acknowledge-data-loss`, an approval record and a saved plan, deletes only through `terraform destroy` of a saved plan, and is planned for mid-December 2026 after grades are released (decision P17-7; exact date PENDING, human). The AWS Budget created outside Terraform is removed by a separate human action.
- **State honestly:** the course asks for an inventory before and after cleanup. The "after cleanup" inventory does not exist yet: it is PENDING (human, H-11; release record by deploy-engineer). What this item shows is the inventory after session-down, with storage retained.
- **Viewer sees:** zeros and gateway-only endpoints, then the cleanup help text and the runbook section.
- **Narration:** "After a session the expensive resources are removed by a Terraform session-down plan; storage is kept. The read-only inventory checks that nothing billing hourly is left. The full cleanup is a separate, human-only, approval-gated script that I run after grading; this is the plan for it, not the result."
- **Marks:** NOT REPLAYED. Deliverable index: D20, D13, D14.

## 5. Closing (1 min)

Open the README "Known limitations" and the final-report link (PENDING, `reports/final-report.md`). Narration: "What is not here: OpenSearch and separately scaled retrieval services were not implemented; there is no single clean-checkout command through Hadoop, entity resolution and Spark; false abstention is about 28 percent; vector retrieval is weak. Every command in the README is marked as replayed or not, and the human's replay results are archived at the final gate."

## 6. Final checklist: item to README deliverable index

Tick each line only after the take, with the recording time stamp. Until then every box is open.

| Item | Label | README deliverable index | Primary file or command | Recorded |
|---|---|---|---|---|
| 1 Repository structure | LIVE (laptop) | D01 | `git ls-tree --name-only HEAD` | [ ] |
| 2 Ownership map | LIVE (laptop) | D02 | `docs/agents.md` | [ ] |
| 3 Role file | LIVE (laptop) | D02 | `.claude/agents/code-reviewer.md` | [ ] |
| 4 Dangerous-command hook | LIVE (laptop) | D02, D13 | `bash .claude/hooks/guard-dangerous.sh --list-categories`; live refusal | [ ] |
| 5 Test-stop gate | LIVE (laptop) | D02, D04 | `.claude/hooks/verify-tests-ran.sh`; probe file and `sbt quick` | [ ] |
| 6 Phase report and repair | ARCHIVED | D03 | `docs/agent-runs/phase-10/` | [ ] |
| 7 Clean `sbt check` | LIVE (laptop) | D04 | `sbt check`; `docs/agent-runs/phase-16/ratification.md` | [ ] |
| 8 Manifest and samples | LIVE (laptop) | D05 | `python3 -B datasets/scripts/datasets.py validate` | [ ] |
| 9 Hadoop and counters | LIVE (laptop) | D06 | `sbt "hadoop / testOnly scholarchain.hadoop.PipelineDriverOutputsSuite"` | [ ] |
| 10 Spark and graph outputs | LIVE (laptop) | D08 | `sbt "sparkAnalytics / testOnly scholarchain.spark.CollaborationPipelineSuite"` | [ ] |
| 11 Neptune query | ARCHIVED (optional LIVE) | D09, D14, D15 | `docs/releases/phase-15/README.md` | [ ] |
| 12 DynamoDB record | LIVE (AWS, read-only) or ARCHIVED | D09, D14 | `describe-table`; `experiments/RESULTS.md` 3.D | [ ] |
| 13 S3 Vectors query | ARCHIVED | D09, D10, D16 | `experiments/EVALUATION.md` 6.1 | [ ] |
| 14 Complete query | ARCHIVED (+ local demo) | D10, D11, D14 | `docs/releases/phase-15/smoke-tn.md`; local `POST /v1/query` | [ ] |
| 15 Evidence and explain | ARCHIVED (+ local demo) | D11, D17 | `docs/api.md` 4.1; local `GET /v1/evidence`, `/v1/explain` | [ ] |
| 16 Claim verification | LIVE (laptop) | D10 | `sbt "rag / testOnly scholarchain.rag.VerificationSuite"` | [ ] |
| 17 Reproduction | LIVE (laptop) | D13, D02, D03, D17 | `scripts/README.md` clean-checkout block; README exercise | [ ] |
| 18 Terraform plan | LIVE (AWS, read-only) | D12, D13, D14 | `bash scripts/plan.sh ...` | [ ] |
| 19 CloudWatch dashboard | LIVE (AWS, read-only) | D12, D15 | console, CloudWatch, Dashboards | [ ] |
| 20 Scale-out and scale-in | ARCHIVED | D15 | `experiments/results/b`, `c`, `d` plots | [ ] |
| 21 Failure and recovery | LIVE (laptop) + ARCHIVED | D15, D13 | `experiments/results/f/.../verdict.csv`; dirty-tree refusal | [ ] |
| 22 Cleanup or inventory | LIVE (AWS, read-only) + ARCHIVED | D20, D13, D14 | read-only inventory; `bash scripts/cleanup.sh --help` | [ ] |

After the take:

- [ ] Video link recorded in the README (D19; hosting decision P17-8: unlisted YouTube link; PENDING human, H-12).
- [ ] Account id masked in every shot; no bucket, ARN, secret name or token on screen (re-watch the whole video once).
- [ ] If option (i) ran: session-down confirmed by the item 22 reads, Billing reading after, and the records archived under `docs/agent-runs/phase-17/` and `docs/releases/phase-17/` (owners: orchestrator and deploy-engineer).
- [ ] `git status --short` clean: probe file from item 5 and the dirty-tree file from item 21 removed, branch from item 17 deleted.
- [ ] Items whose live result differed from the expectation here recorded as such, with the difference, in the Phase 17 evidence files; this script is not edited to match.
