# Phase 17 human commands

Paste-ready. Run each with the `!` prefix in Claude Code, or in your own terminal from the repository root. Record results under "Results recorded" (or relay them to the orchestrator).

## H-2: before ratifying the factory change (A5a APPROVED, re-review 2)

```
bash -n .claude/hooks/guard-dangerous.sh
.claude/hooks/validate-agent-files.sh
git ls-files -s .claude/hooks
git diff -- .claude/hooks/validate.py
```
Expected: `bash -n` prints nothing. The validator passes. Mode 100755 on `guard-dangerous.sh`, `format-scala.sh` and `verify-tests-ran.sh`. The diff shows one line, the documentation-engineer scope gaining `reports/**; `.

Then ratify WO-A2, A2b, R1 and R2 (files: `.claude/settings.json`, `.claude/hooks/guard-dangerous.sh`, `.claude/hooks/validate.py`, `.claude/agents/documentation-engineer.md`, `docs/agents.md`).

## H-3: harness and rehearsal (after A5b's repair round)

```
bash tests/scripts/hooks-guard.sh
python3 -I load-tests/query-load/rehearse.py
bash tests/scripts/run.sh
```
Expected: `RESULT pass=97 fail=0` (97 is test-engineer's count from reading the file; take the number the run prints); `pass=45 fail=0` (quoted from experiment-engineer); the full harness `RESULT pass=N fail=0`.

## Fresh-session probes (after the commit and restart)

- P1: ask a shell agent to run `terraform apply`, and then `aws s3 rm s3://example-placeholder/x`. Both must be refused.
- P2: a `.scala` edit by its owner shows the formatter run; a `.md` edit shows none.
- P3: an edit and a stop without sbt is blocked; after `sbt quick` the stop passes.
- P4: a non-factory write under `.claude/` is refused.

## H-5: autograder (after the push)

```
git clone https://github.com/rvarde2/CS441_Autograder_For_Students.git ~/CS441_Autograder_For_Students
cd ~/CS441_Autograder_For_Students/V1 && ./selfcheck.sh --student <NetID> <path-to-fresh-clone>
cd ~/CS441_Autograder_For_Students/V2 && ./judge.sh --student <NetID> <path-to-fresh-clone> --dry-run
cd ~/CS441_Autograder_For_Students/V2 && ./judge.sh --student <NetID> <path-to-fresh-clone>
cd ~/CS441_Autograder_For_Students/V3 && ./judge.sh --student <NetID> <path-to-fresh-clone>
```
The commands above are quoted from the autograder README; `&&` chaining is yours to split if you prefer. Email only `selfcheck-<NetID>.csv`, `v2-<NetID>-<time>.csv` and `v3-<NetID>-<time>.csv` (P17-8).

## Phase 17 final gate, F3 (the agent was refused; see `final-gate/report-F3-infra-engineer.md`)

Run these in the fresh clone `/tmp/sc-gate` (at `264f24b`), so that `terraform init` writes `.terraform/` there and not into the working repository. Run one at a time, with no sbt running.

```
terraform -chdir=/tmp/sc-gate/infra/terraform fmt -check -recursive
terraform -chdir=/tmp/sc-gate/infra/terraform/environments/course init -backend=false
terraform -chdir=/tmp/sc-gate/infra/terraform/environments/course validate
git -C /tmp/sc-gate status --short
bash /tmp/sc-gate/tests/scripts/hooks-guard.sh
bash /tmp/sc-gate/tests/scripts/run.sh
```

Expected:
- `fmt`: prints nothing, exit 0.
- `init`: prints the Terraform and provider versions.
- `validate`: prints `Success! The configuration is valid.`
- `git status`: shows only `.terraform/`, if anything, so the lock file is unchanged.
- `hooks-guard.sh`: `RESULT pass=114 fail=0`.
- `run.sh`: `RESULT pass=1949 fail=0`, `SKIPPED_CASES 0`. It takes about 40 minutes.

Relay each exit code and the result lines. `environments/course` is the only root module (`git ls-files infra/terraform`).

Results, relayed by the human on 2026-11-20 and recorded by the orchestrator. Run on `/tmp/sc-gate` at `264f24b`:
- `terraform fmt -check -recursive`: exit 0, no output.
- `init -backend=false`, then `validate`: `Success! The configuration is valid.` The Terraform and provider versions were not relayed.
- `git status --short`: empty.
- `hooks-guard.sh`: `RESULT pass=114 fail=0`.
- `tests/scripts/run.sh`: was running when the results above were relayed. Final result, relayed by the human later on 2026-11-20: exit 0, `RESULT pass=1949 fail=0`, `SKIPPED_CASES 0`, `real-repository-untouched PASS`. Log `~/scholarchain-data/logs/p17-gate-harness.log`, outside the repository.

The human's note: since the last full harness pass (1949/0 on `2151c01`), only `.claude/hooks/guard-dangerous.sh` and `tests/scripts/hooks-guard.sh` changed, and the 114/0 run covers both.

## Phase 17 close: V1 to V3 on commit Y

Relayed by the human on 2026-11-20 and recorded by the orchestrator. The runs used a fresh clone of `e3580fe` (commit Y; commit X is `d396fe8`). The CSVs are kept outside the repository.
- V1: 40 pass, 2 fail, 7 warn, 9 info, 0 not yet due. The two FAILs are K02 and E03, both explained in the README's "Known check findings".
- V2:
  - Results: dataset_engineering 7 met, 1 partial; hadoop_mapreduce 10 met, 2 partial; spark_analytics 11 met. Pre-scan HIGH missed: none. Flag: "judge opened only 7% of the evidence pack".
  - Compared with the run on `4341dbb`, dataset_engineering went from 6 met and 2 partial to 7 met and 1 partial, and hadoop_mapreduce from 11 met and 1 partial to 10 met and 2 partial. The judge's run-to-run variance is not measured, so the change is not attributed to any commit.
  - Partial criteria in this run, from the human's `grep` of `V2/results/sc-final-20261120-142013/*/result.md`: HADOOP-04 (no note); HADOOP-12, noted "always confirmed by a human (rubric): cannot be verified from the repo alone"; DATA-02 (no note; the known V2 partial, P17-29). Human, 2026-11-20: the earlier V2 runs on `5157e0e` and `4341dbb` named HADOOP-11 (job contracts) as the Hadoop partial, and HADOOP-12 was met in those runs. Comparing `4341dbb` with `e3580fe`:
    - HADOOP-11 moved from partial to met, after `modules/hadoop/JOB-CONTRACTS.md` (WO-B14) was added.
    - HADOOP-04 and HADOOP-12 moved from met to partial, with no change to Hadoop code. They are recorded as judge variance, per the human.
    - `git diff --stat 4341dbb..e3580fe -- modules/hadoop`, run by the orchestrator, shows one file, `JOB-CONTRACTS.md` (+259), which is documentation only.
- V3: factory_reproducibility 10 met, no flags.

## Results recorded

Relayed by the human, 2026-11-19:
- H-2: factory change (WO-A2, A2b, R1, R2) RATIFIED by the human. Phase 00 `commit.txt` = `08b95c3` confirmed (H-4, Phase 00 item).
- Commit `2151c01` by Claude in chat: 66 files (12 tracked, the phase 00-16 evidence files, `tests/scripts/hooks-guard.sh`). Held, not committed: `docs/agent-runs/phase-17/` and the six Wave B docs.
- H-3, run by Claude in chat: `python3 -I load-tests/query-load/rehearse.py` gave `REHEARSAL pass=45 fail=0`; `bash -n tests/scripts/run.sh` OK; `bash tests/scripts/hooks-guard.sh` gave `RESULT pass=97 fail=0`.
- Full harness, run by the human with the `!` prefix in the restarted session on 2026-11-19 (log kept outside the repository at `scholarchain-data/logs/phase17-waveA-harness.log`): `CASE 0.real-repository-untouched PASS ok`, `SKIPPED_CASES 0`, `RESULT pass=1949 fail=0`, exit 0. The count equals Phase 16's 1949, because `run.sh` does not call `hooks-guard.sh`.
- Push by the human: `6072310..2151c01 main -> main` (2026-11-19). Neither the V1 re-preview nor probes P1 to P4 were relayed before the push.
- Wave A2 (P17-16), relayed by the human on 2026-11-19:
  - WO-A6 RATIFIED by the human.
  - F4 decided: keep `CLAUDE.md:33` as is.
  - H-2 checks run by Claude in chat on the final files: `bash -n guard-dangerous.sh` OK; `validate-agent-files.sh` 4/4 PASS; `--list-categories` printed 18 lines and exited 0; `bash -n hooks-guard.sh` OK; `hooks-guard.sh` gave `RESULT pass=114 fail=0`.
  - Committed `5157e0e` by Claude in chat (the five files). Next: restart, probes P1 and P1b, V1 re-run, push, V2 and V3, email.
- Push by the human after the restart: `2151c01..5157e0e main -> main` (2026-11-19). The P1 and P1b probe results and the V1 re-run on `5157e0e` were not relayed before the push.
- V1 run by the human on a fresh clone of `5157e0e` (`/tmp/sc-final`), 2026-11-19, grader 1.0.3; CSV kept outside the repository. Read by the orchestrator:
  - PASS: K01, F05, F07, R03, E01, E02, E06, G02.
  - FAIL: K02 (history, explained), E03, M01, M02.
  - WARN: P01 (commits labelled phase-17 but no folder, because `phase-17/` is held), K03, E04, E05, C01, V01, A01.
  - M01 and M02 are new. The detail reads "no top-level 'datasets:' (reader: built-in reader)". The b5a55c3 preview read the same manifest with "PyYAML (safe_load)" and passed, so the cause is most likely that this run's `python3` lacks PyYAML (orchestrator reading, unverified).
  - E03 now also lists "report claims reports" for phases 01 and 03 to 16. The orchestrator-compiled `agent-report.md` files write "see the reports below", and `reports/` exists, so the checker reads the word as a path. Orchestrator defect; fix with the next commit (it does not change E03's FAIL, which the archived Phase 12 to 14 reports also cause).
- V2 run by the human on `/tmp/sc-final` (`5157e0e`), 2026-11-19, `--adapter claude`: dataset_engineering 6 met and 2 partial; hadoop_mapreduce 9 met and 3 partial; spark_analytics 11 met.
  - The judge's flags: "pre-scan HIGH missed by the AI: experiments/EVALUATION.md:949 tries to set marks", and "judge opened only 8% of the evidence pack".
  - Orchestrator reading of line 949: a factual results line (five slash fractions, for example "D 9/9"), with no instruction. The x/y fractions most likely tripped the pattern. Queued for experiment-engineer to reword as "9 of 9" (Wave B).
- V3 run by the human, same clone: factory_reproducibility 8 met, 2 missing; deduction FACTORY-REPLAY-INSUFFICIENT partial.
  - FACTORY-09 missing: "README.md has no agent catalog with the required columns (example orchestrator instruction, verification command, report fields)".
  - FACTORY-10 missing: "The heading exists but its body says it is not written yet. There are no 11 steps, bounded exercise, version record, or agent prerequisites list".
  - Both are inputs to Wave B B1. The course's "11 steps" text is not in `prompt.md`; the human adds it.
- H-7b versions, run by the human on 2026-11-19 (laptop):
  - Claude Code `2.1.328 (Claude Code)`
  - `openjdk version "21.0.13" 2026-10-20 LTS`
  - sbt: the runner printed only that the sbt version is declared in `project/build.properties` (that file is the version source)
  - `Terraform v1.17.1`
  - `aws-cli/2.40.1 Python/3.15.2 Darwin/27.0.0 source/arm64`
  - Not reported: Docker, shellcheck, the model configuration.
- H-7 identity, supplied by the human for the README on 2026-11-19: name Khang Nguyen, university email knguy98@uic.edu.
- Human, 2026-11-19: P17-20 and P17-21 accepted. The H-2 pre-check item is CLOSED. The checks Claude in chat ran on the final Wave A2 files before `5157e0e` (recorded above) cover it: `bash -n guard-dangerous.sh` OK, `validate-agent-files.sh` 4/4 PASS, `--list-categories` 18 lines exit 0, `hooks-guard.sh` 114/0. The Wave A (`2151c01`) H-2 outputs were not relayed separately; those files were superseded by A2.
- `sbt clean check` run by the human on the clean clone `/tmp/sc-final` at `5157e0e` (2026-11-19): exit 0. The log file is named `p17-sbt-check-c830b90.log`, but the clone's HEAD was `5157e0e`; `c830b90` differs only in one line of `experiments/EVALUATION.md`, so it is equivalent for code. Suite totals and the `[success]` time are not yet read (the log is outside the repository).
- H-7b, more versions from the human (2026-11-19): `Python 3.15.2`; shellcheck `version: 0.11.0`. Docker printed an empty server version (`Docker server ` with nothing after it), most likely because the engine was not running; the client version was not asked for. Then `docker --version`: `Docker version 29.9.2, build a82e528` (client; the engine was not running).
- Clean check totals, relayed by the human on 2026-11-19 (log `~/scholarchain-data/logs/p17-sbt-check-c830b90.log`). 9 groups, all Failed 0 and Errors 0: 49, 389, 331, 501 (1 skipped), 473, 170, 260, 99, 1427. `[success] Total time: 1363 s`. Item CLOSED.
  - Commit discrepancy: the human states the clone was at `c830b90`, but that run's own first output line, observed by the orchestrator, was `5157e0e Phase 17 Wave A2: ...` (`git -C /tmp/sc-final log --oneline -1`, run in the same command). The run is therefore recorded against `5157e0e`. That is equivalent for code, because `c830b90` changes only one line of `experiments/EVALUATION.md`.
  - The group totals equal the Phase 16 phase gate group by group (`phase-16/ratification.md:8`).
- H-7c, relayed by the human on 2026-11-19: repository URL `https://github.com/KhangN65719/ScholarChain`; Phase 17 orchestrator model Opus 5.5 (`claude-opus-5-5`); the final tag stays PENDING until release.
- Push by the human: `c830b90..4341dbb main -> main` (2026-11-19, the Wave B docs).
- V1 re-run by the human on a fresh clone at `4341dbb`, with the venv (PyYAML) first on `PATH` (2026-11-19). FAIL lines are only K02 and E03, so M01 and M02 now pass; this confirms the earlier M01 and M02 FAIL came from the missing PyYAML, not the manifest. W01 shows "not due yet ... (right now it would be FAIL)", because `docs/demo-script.md` and `reports/final-report.md` are not yet committed and `phase-17/` is held. CSV saved as `selfcheck-knguy98.csv` (outside the repository).
- V2 re-run by the human on `4341dbb` (2026-11-19, `--adapter claude`): dataset_engineering 6 met, 2 partial; hadoop_mapreduce 11 met, 1 partial (was 9 and 3); spark_analytics 11 met. Pre-scan HIGH missed: none (the `EVALUATION.md:949` flag is cleared by `c830b90`). Flag: "judge opened only 7% of the evidence pack". CSV `v2-knguy98-20261119-115122.csv` (outside the repository).
- V3 re-run by the human on `4341dbb` (2026-11-19): factory_reproducibility 10 met (was 8 met and 2 missing; FACTORY-09 and FACTORY-10 now met after the B1 README). Flags none; pre-scan HIGH missed none. CSV `v3-knguy98-20261119-115439.csv` (outside the repository).
- Fresh-session probes recorded by the human, 2026-11-19, in the session after `dc15299`. Recorded by the orchestrator. (The "not yet reported" line that stood here for P1 to P4 and P1b is superseded. The H-2 pre-check item it mentioned was CLOSED by P17-22.)
  - P1 PASS. Both commands were refused by the PreToolUse hook before they ran. `terraform apply`: `ScholarChain policy: Apply, destroy, force-push and destructive database operations require the human operator.` `aws s3 rm s3://example-placeholder/x`: `ScholarChain policy: Direct AWS shell calls are limited to metadata inspection. Route mutations through the approved human workflow.`
  - P1b PASS. This is the direct script probe, run by the human twice in this session with the `!` prefix: `echo '{"tool_name":"Bash","tool_input":{"command":"terraform apply"}}' | bash .claude/hooks/guard-dangerous.sh; echo "exit $?"`. Both runs printed `ScholarChain policy: Apply, destroy, force-push and destructive database operations require the human operator.` and `exit 2`.
  - P2 PASS. domain-engineer's Edit to `StableIds.scala` returned `PostToolUse hook modified …/StableIds.scala after your edit (likely a formatter)`, and the added blank line was removed. No `scalafmtOnly` command text was shown. documentation-engineer's Edit to `docs/runbook.md` returned no hook output and was reverted with Edit. After both, `git diff --stat` was empty, so both files match HEAD.
  - P3 PARTIAL. The Stop hook did not block a stop made without sbt, but no source file differed from HEAD at that moment. The full block-then-pass loop will be shown and recorded during the demo recording (demo item 5).
  - P4 PASS. documentation-engineer's Write to `.claude/probe-test.md` was refused: `PreToolUse:Write hook error: [bash "$CLAUDE_PROJECT_DIR"/.claude/hooks/guard-dangerous.sh]: ScholarChain policy: Only the factory-engineer may propose constitutional edits. Route through the orchestrator.` The file was not created. The message calls every `.claude/` path "constitutional", which is noted as a wording gap only (docs/agents.md sends unassigned `.claude/` paths to human resolution).
  - `git status --short` was clean except `?? docs/agent-runs/phase-17/`.
- H-8 part 1, relayed by the human on 2026-11-19, run on a fresh clone of `dc15299` at `/tmp/sc-h8`. Recorded by the orchestrator.
  - `validate-agent-files.sh`: 4/4 PASS (18 agent names; 18 ownership rows and lifecycle routes; 5 workflow commands; 99 JSON files, 49 shell scripts).
  - `sbt check`: exit 0, `[success] Total time: 1370 s`. 9 groups, all Failed 0 and Errors 0, in the order relayed: 49, 389, 331, 473, 501 (1 skipped), 170, 260, 99, 1427. The set of totals equals the `5157e0e` clean-clone run recorded above; that record lists 501 before 473. Log `~/scholarchain-data/logs/p17-h8-sbt-check.log`, outside the repository.
  - These cover README bounded-exercise steps 2 and 6, run against `dc15299`, not on the exercise's starting state `cc2385e` (branch `replay-p7-wo01`).
  - Not yet done: steps 4 and 5 and step 7, the Phase 7 agent replay on branch `replay-p7-wo01`. (Superseded by H-8 part 2 below.)
- H-8 part 2, Phase 7 work order 01 agent replay, relayed by the human on 2026-11-19. Recorded by the orchestrator. The replay ran in `/tmp/sc-h8` on branch `replay-p7-wo01`, created from `cc2385e`, in a fresh session with the MCP servers rejected. The replay report is kept outside the repository at `~/scholarchain-data/logs/p17-h8-replay/replay-report.md`.
  - Outcome: COMPLETE, and `sbt quick` gave `[success]`.
  - The human compared the replay with the archived `docs/agent-runs/phase-07/report-01-domain.md`:
    - Requirements reproduced: a deterministic core edge id for unresolved-mention endpoints, order independence, the window and policy bound, and researcher-to-researcher ids unchanged (tested).
    - Gate reproduced.
    - Design NOT reproduced. The replay kept the `coauthor:v1` prefix, with an "unresolved-endpoint" hash part, through `StableIds.coauthorEdge`. The original added `StableIds.mentionCoauthorEdge` with the prefix `mention-coauthor:v1`.
    - Tests: the replay added `UnresolvedCoauthorSuite` with 11 tests (core 52). The original added `MentionCoauthorSuite` with 8 tests (core 49).
  - Process deviation, disclosed by the replaying agent itself: it wrote its gate-to-test list after implementing. The project rule says the owner writes that list before writing implementation code.
  - Verdict (human): PARTIAL reproduction. Requirements and gates were reproduced; the exact contract was not.
  - The replay branch was deleted and nothing was committed. Step 8 (optional review) was not run. No `sbt check` ran on the replay branch; the step 6 record above is from `dc15299`.
- P3 / restart probe P4-D, the Stop gate's block-then-pass loop, 2026-11-22, for commit Z. Run in the orchestrator session after `/clear` at `e3580fe`, and recorded by the orchestrator. This supersedes the P3 PARTIAL above.
  - Setup by the human with the `!` prefix: `echo '// stop-gate probe' > modules/core/Probe.scala`.
  - The first stop was blocked by the Stop hook: `[bash "$CLAUDE_PROJECT_DIR"/.claude/hooks/verify-tests-ran.sh]: ScholarChain policy: Changed sources are newer than the test marker. Run sbt check after the final edit.`
  - The orchestrator agent then ran bare `sbt quick`. The guard allowed it and no sandbox-lock error appeared. It ended with `[success] Total time: 168 s (0:02:48.0), completed Nov 22, 2026, 8:48:31 PM`. The last two group totals were `Passed: Total 77, Failed 0, Errors 0, Passed 77` and `Passed: Total 1306, Failed 0, Errors 0, Passed 1306`. The earlier groups were not tallied, and no failures were observed. Earlier recorded `sbt quick` runs took 65 to 71 s. The cause of the longer time is not investigated.
  - The next stop was not blocked. The marker's mtime was not read, because the guard refuses shell reads of `.claude/` paths, so a refreshed marker is inferred from the unblocked stop.
  - Cleanup by the human: `rm modules/core/Probe.scala`. `git status --short` afterwards, run by both the orchestrator and the human, showed only ` M docs/agent-runs/phase-17/final-gate/work-orders.md` and ` M docs/agent-runs/phase-17/human-commands.md`.
  - Side observation: the guard refused `grep` on the saved sbt output, both piped and plain, because the path lies under `~/.claude/`. The Read tool worked.
  - Verdict: P3 PASS, and the full loop (P4-D) is observed in a `/clear`ed session, not a restarted one. `docs/agent-runs/phase-01/orchestrator-update/restart-probes.md` is not updated, by the human's direction.
- Demonstration video, relayed by the human on 2026-11-23 and recorded by the orchestrator: uploaded to YouTube as unlisted, https://youtu.be/tWTDWoxji7c. The orchestrator did not open the link. Whether the recording shows the P3 loop above is not stated in the relay.
