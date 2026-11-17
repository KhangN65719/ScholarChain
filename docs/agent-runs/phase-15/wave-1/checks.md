# Wave 1 checks run by the human (`!` path), recorded by the orchestrator, 2026-11-17

Logs are in `/Users/winnguyen/scholarchain-data/logs/`.

| Check | Result | Log |
|---|---|---|
| EC2 quota `L-1216C47A` | applied 32.0 vCPU | `phase15-precheck-ec2-quota.log` |
| Fargate quota `L-3032A538` | applied 30.0 vCPU | `phase15-precheck-fargate-quota.log` |
| Bedrock Haiku 4.5 availability | agreement `NOT_AVAILABLE` (form needed), authorized, entitled, Region available | `phase15-precheck-bedrock-availability.log` |
| Bedrock inference profiles | `us.anthropic.claude-haiku-4-5-20251001-v1:0 ACTIVE` | `phase15-precheck-bedrock-profiles.log` |
| Dataset WO-4, six steps | all exit 0; 99 tests OK (see the WO-4 report) | `phase15-wo4-1..6.log` |
| `check.sh --skip-sbt`, first run | FAIL at `terraform fmt -check` (`modules/workflow/main.tf`); validate not reached; all other gates PASS | `phase15-wave1-check.log` |
| `terraform fmt -check -diff` on that file | whitespace only, exit 3; repaired in WO-1c | `phase15-wave1-fmt-diff.log` |
| `bash -n` on `run-s-pipeline.sh`, `lib-emr.sh`, `capability-check.sh` | exit 0 each (the first combined command checked only the first file; rerun one per file) | `phase15-wave1-bash-n*.log` |
| `shellcheck -x` on the three EMR scripts, first run | SC2034 at `lib-emr.sh:617`, exit 1; repaired in WO-1c | `phase15-wave1-shellcheck.log` |
| A rehearsal | `REHEARSAL pass=24 fail=0` | `phase15-wave1-rehearse-a.log` |
| B rehearsal | `REHEARSAL pass=24 fail=0` | `phase15-wave1-rehearse-b.log` |
| `check.sh --skip-sbt`, after WO-1c | constitution-json, hook-syntax, shell-syntax, shellcheck, terraform PASS (fmt clean; `terraform validate`: "Success! The configuration is valid."); sbt SKIP; `CHECK_RESULT=PASS_WITH_SKIPS`, exit 0 | `phase15-wave1-check-2.log` |

| `shellcheck -x` on the three EMR scripts, after WO-1c | no findings, exit 0 | `phase15-wave1-shellcheck-2.log` |

| Script harness, run 1 | `RESULT pass=1599 fail=14`, `SKIPPED_CASES 0`; five root causes routed in `repair-batch-1.md` | `phase15-wave1-harness.log` |
| R3 move and seven commands | all as expected (see the R3 report); 105 tests OK | `phase15-r3-1..7.log` |
| Script harness, run 2 (after R1, R2, R3 and the test-engineer fix) | `RESULT pass=1610 fail=3`, `SKIPPED_CASES 0`. Failing: `15.emr.profile-arg.a-valid-profile-is-recorded-before-the-first-stage-ends` and `15.emr.profile-arg.no-profile-is-recorded-as-none` (script runs past the profile check into `aws s3 cp`, stub called; `lib-emr.sh: line 287: timeout: command not found` on macOS), and `0.tripwire-never-reached-in-any-case` (consequence: "a stub was called: aws s3 cp s3://harness-data-bucket/raw/harness-snap/ ..."). All 11 other run-1 failures pass, including the wrong-ID cases | `phase15-wave1-harness-2.log` |
| `bash -n` on `run-s-pipeline.sh`, `lib-emr.sh`, `tests/scripts/run.sh` (after batch 2) | exit 0 each | `phase15-rb2-bash-n-1..3.log` |
| `shellcheck -x` on the EMR scripts, `b-burst/run.sh`, `b-burst/collect.sh`, `check-manifest-json.sh` (after batch 2) | SC2155 (warning) at `experiments/b-burst/collect.sh:69` (`local failed="$RUN_DIR/collect-failed.$(date ...)"`), exit 1; routed to experiment-engineer | `phase15-rb2-shellcheck.log` |
| `check.sh --skip-sbt` (after batch 2) | all gates that ran PASS, `terraform validate` success, `CHECK_RESULT=PASS_WITH_SKIPS`, exit 0 (its shellcheck gate does not cover `experiments/`) | `phase15-rb2-check.log` |
| B rehearsal (after batch 2) | `REHEARSAL pass=59 fail=0`, exit 0 (expected 59) | `phase15-rb2-rehearse-b.log` |
| A rehearsal (after batch 2) | `REHEARSAL pass=44 fail=0`, exit 0 (expected 44) | `phase15-rb2-rehearse-a.log` |
| B collector rehearsal | `REHEARSAL pass=25 fail=0`, exit 0 (expected 25) | `phase15-rb2-rehearse-collect.log` |
| Manifest JSON rehearsal | `REHEARSAL pass=10 fail=0`, exit 0 (expected 10) | `phase15-rb2-rehearse-json.log` |
| Manifest JSON check | `MANIFEST_JSON_CHECK OK manifests=6`, exit 0 | `phase15-rb2-check-json.log` |
| Script harness, run 3 (after batch 2) | `RESULT pass=1649 fail=7`, exit 1; routed in `repair-batch-3.md` | `phase15-rb2-harness.log` |
| `bash -n` x3 (after batch 3) | exit 0 each | `phase15-rb3-bash-n-1..3.log` |
| `shellcheck -x` on six scripts (after batch 3) | no findings, exit 0 (SC2155 cleared) | `phase15-rb3-shellcheck.log` |
| Script harness, run 4 (after batch 3) | `RESULT pass=1658 fail=1` (1659 cases as predicted), `SKIPPED_CASES 0`. Only failure: `0.real-repository-untouched` ("git status of the real repository changed during the run"), because Wave 2 agents were writing to the repository during the run (human-directed parallel start). All seven run-3 failures and the three new cases pass. Human direction (2026-11-17): recorded as caused by Wave 2 edits, not by Wave 1; the clean-tree harness rerun happens at the phase gate. The Wave 1 Scala test files (`PartitionCountSettingSuite.scala`, `CollaborationPipelineSuite.scala`) predate the last recorded sbt run; `build.sbt` is Wave 2 | `phase15-rb3-harness.log` |
| `git check-ignore -v datasets/tier-s/chunks-c/chunks.jsonl` (review 2 gate 2) | `.gitignore:18`, rc 0 (human, 2026-11-17) | human message |
| A rehearsal (after R12) | `REHEARSAL pass=49 fail=0`, exit 0 (expected 49) | `phase15-r12-rehearse-a.log` |
| B rehearsal (after R12) | `REHEARSAL pass=59 fail=0`, exit 0 (expected 59) | `phase15-r12-rehearse-b.log` |
| `check.sh --skip-sbt` (after R13) | terraform PASS, `CHECK_RESULT=PASS_WITH_SKIPS`, exit 0 | `phase15-r13-check.log` |
| Script harness, run 5 (after R12 and R13) | `RESULT pass=1659 fail=0`, `SKIPPED_CASES 0`, exit 0; `0.real-repository-untouched` PASS (no agent writing during the run) | `phase15-r12-harness.log` |
| B rehearsal, collector rehearsal (after batch 3) | `pass=59 fail=0`, `pass=25 fail=0`, exit 0 each | `phase15-rb3-rehearse-b.log`, `phase15-rb3-rehearse-collect.log` |
