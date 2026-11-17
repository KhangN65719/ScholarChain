# WO-12a report, test-engineer (received 2026-11-17; preserved as received; author: test-engineer subagent)

Status: BLOCKED-ON for execution. Cases written, not run; no pass or fail counts. The guard refuses `bash tests/scripts/run.sh` for agents ("Run reviewed repository scripts directly. Shell and interpreter wrappers are blocked."); the bare path, `bash -n`, `grep`, `git status` and `wc` are refused too (decision D11, recorded in the harness README). The human runs `bash tests/scripts/run.sh`, plus `bash -n` and `shellcheck -x` on it. New code not checked for syntax or shellcheck warnings.

## Cases added (48, new "Case 15" block before "Case 12" in `tests/scripts/run.sh`)
| Spec item | Cases |
|---|---|
| S overlay class `db.t4g.medium` | `15.terraform.s-overlay.neptune-class-is-db-t4g-medium-and-is-assigned-exactly-once`, `…no-code-line-names-the-xlarge-class` |
| S overlay line 1 and 660 s comments | `…line-1-states-the-real-size-3472-works`, `…every-660-s-creation-figure-is-sourced-or-marked-unsourced` |
| graph-load overlay | `15.terraform.graph-load.*` (5): one code line, layering result, session 2 apply order, modify behaviour UNVERIFIED or cited, no other overlay sets the class |
| Elasticity overlay | `15.terraform.elasticity-s.*` (7): core 1, max units 4, spot 0, no services; min 2 < max 4, 8 to 16 vCPU; Neptune and Bedrock false after S; wait about 900 s; header |
| Elasticity example note | `15.terraform.elasticity-example.is-unchanged-in-values-and-its-header-says-min-equals-max-so-it-cannot-scale` |
| Wait absent by default, bounded when set | `15.terraform.wait.*` (8): variable discovered by name, default 0 in module and course, validation bounds, course wiring, one `Type = "Wait"` guarded by `> 0` leading to `TerminateCluster`, only elasticity-s sets it |
| Rule A with the wait | condition and message include the wait; evaluator exact at the boundary; S plus 900 s fails under 7200; module defaults and S/M/L overlays pass at wait 0; elasticity effective values pass |
| S caps 90 / 64 / 20,000, M and L unchanged | `15.experiment.*` (16): S, M, L accepted exactly at caps by dry run, each refused one above, S zero-minutes range text, static `tier_caps` lines, README line holding `D12` and `P15-1` |
| Pre-session checks read-only | `15.presession.*` (8): section found; every `aws` call `get-`/`list-`/`describe-`; no mutating text; EC2 and Fargate via `get-service-quota`; read-only Bedrock call naming Haiku; us-east-2 only; paste-ready form; docs cited; no 12-digit number |

## Existing cases edited (not weakened)
- `8.refuse.minutes-over-tier-cap`, `concurrency-over-tier-cap`, `tasks-over-tier-cap`, `minutes-zero` use the new S caps (91, 65, 20001, "from 1 to 90").
- `14.terraform.rule-a.the-workflow-module-counts-the-one-step-once-plus-600` now requires create, step and wait terms plus `+ 600` and rejects the old no-wait form.
- Phase 15 paragraph added to `tests/scripts/README.md`.

## Expected failures until WO-1 lands (state observed while WO-1 was in progress)
- `neptune_instance_class` assigned twice in `experiment-size-s.tfvars` (line 46 t4g, line 66 xlarge); S-overlay class and layering cases fail.
- 660 comment figures at lines 93 to 97 unmarked.
- `graph-load.tfvars`, `elasticity-s.tfvars`, the wait variable and `Wait` state, the Rule A wait form, the new caps, the README D12/P15-1 line and pre-session section not yet present.
- `15.experiment.tier-M-…` and `tier-L-…` should pass.

## Open items
- Some existing `14.terraform.s-overlay.*` cases pin the `660 + 300 + …` comment text and `S overlay: 12840 against 7200` in `modules/workflow/variables.tf`; may need reconciling after WO-1.
- Pre-session section searched in `scripts/README.md`, then `infra/terraform/README.md`.
- Could not search the 9,370-line harness for other cases pinning `neptune_instance_class` or the old S caps.
- Not covered: `terraform validate`, Wait runtime behaviour, whether quota codes and the Bedrock command are real.

Files changed: `tests/scripts/run.sh`, `tests/scripts/README.md`.

## Follow-up after WO-1 and WO-1b (received 2026-11-17; summary of the report as received)
Written, not executed (guard refuses `bash tests/scripts/run.sh`, `bash -n`, `grep`); no counts. Harness read only in parts (setup, helpers, case 8, about lines 3000 to 7630 and 8200 onward); other stale pins may exist.
- Stale pins: `14.emr.spark-yarn-conf.holds-only-key-value-settings` now allows a `<profile>.` prefix; `14.terraform.rule-a.the-workflow-module-counts-the-one-step-once-plus-600` restored to its original two pins (the first-pass loosening was wrong; infra kept the old validation and added a second); `15.terraform.s-overlay.every-660-s-…` looks for the source marker in the ten lines before each figure. Not stale: Hadoop command text, `run_spark_submit` pins, `timeout -k 30` counts, dynamic Spark helper cases, `Next = "RunLoader"` count, 660/12840/6840 figures. Not found: `Next = "TerminateCluster"`, `$.run_id)"`, `8340`.
- About 109 new cases (Case 15b): `15.emr.*` (data path file vs hdfs with stub `hdfs`/`yarn`, `-D` options only when positive, b-burst 16/131072/3072/3072, Spark master local[4] only with a remote node, 32 shuffle partitions, profile arg validation, conf precedence, event log, `--partitions "$shuffle"`); `15.terraform.skip-loader.*`, `profile.*`, `baseline-s.*`, `elasticity-s.*`.
- Stub cases: `15.experiment.b-collect.*` (17: 9 read-only calls with us-east-2, read-only files and SHA256SUMS, write-once, refusals, `aws_read` refuses 6 mutating verbs); `15.experiment.entry-A.*`/`entry-B.*` (19: one call, 4 UTC events, no retry on exit 3, refusals, limits under manifest ceilings).
- At risk: course wiring cases for skip-loader and profile (course `variables.tf`/`main.tf` not read); `15.emr.static.hdfs-commands-sit-only-inside-…` (counts exactly four `hdfs dfs ` lines); `15.experiment.entry.static…`; collect cases assume `derive-capacity.py` accepts the synthetic fixture; three `15.emr.profile-arg.*` cases could hit the `aws` stub tripwire on a host with JDK 17+ under `/usr/lib/jvm` (not expected on macOS).
- Not covered: real `yarn node -list` format, HDFS permissions, EMR scaling behaviour, a completed core-1 pipeline.

## Repair batch 1 (received 2026-11-17; summary of the report as received)
- Wrong-ID claim from R2 confirmed by tracing `w15_entry` and `exp_init` (line 55 refuses before `events.jsonl`; no experiment defect). Fix in `tests/scripts/run.sh`: `w15_entry` (about line 10909) honours an optional `W15_ENTRY_DIR` naming the entry point under test; the wrong-ID call (about line 10975) sets it to the entry point under test while exporting the other ID, then clears it. Assertions kept: exit 1, `EXPERIMENT_REFUSED: this entry point is for experiment A` (or B), no pipeline call, no `events.jsonl`.
- No case found asserting the old `MAPREDUCE_COUNTERS_FILES` label (search limited to parts read). `15.emr.static.counters-...` should now pass.
- R1 points checked by reading: `validate_snapshots` error text unchanged; README line satisfies `readme-marks-d12-as-decided-by-p15-1`; `course.tfvars` matches `graph-load.sets-nothing-...`.
- Not run (guard). Last known result `pass=1599 fail=14`.

## Repair after harness run 2 (received 2026-11-17; summary as received)
- Cause: with the regex fixed, the accepted-profile cases ran the whole `run-s-pipeline.sh` into the input fetch and the `aws` stub.
- Fix (`tests/scripts/run.sh`, `15.emr.profile-arg.*`, about lines 10470 to 10520): the three accepted-profile cases cut the first stage (from `stage validate-arguments` to just before `stage java`) out of the real script and run it in a child shell with the real `lib-emr.sh` and a stub `sc_publish_progress`; it stops at the first `stage_ok`. Refusal cases unchanged (they die at the argument check).
- Assertions: valid profile rc 0, `RUN_PROFILE=b-burst`, `SC_PROFILE=b-burst`, `STAGE[1] validate-arguments: ok`, no refusal, tripwire empty; none: `RUN_PROFILE=none`, `SC_PROFILE` empty; 32 characters accepted; new case 33 characters refused by the same block (rc 1, `profile must be 0 to 32 characters of a-z0-9-`, no `RUN_PROFILE=`). `0.tripwire-never-reached-in-any-case` unchanged.
- `f_min_lines` fails explicitly if the cut block is not found. README note added; earlier JDK-host remark withdrawn. Not run (guard); expected `fail=0`.
