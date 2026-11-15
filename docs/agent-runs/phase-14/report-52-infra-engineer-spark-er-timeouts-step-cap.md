# Report for work order 52 (infra-engineer): on-node Spark and ER timeouts, S overlay step cap 4,800 s

Archived by the orchestrator as received from infra-engineer (2026-11-15 UTC). Nothing was executed by the agent.

**STATUS:** COMPLETE (authoring only). Nothing was executed. No static check, harness run, plan or AWS call was made.

**OWNER:** infra-engineer. **WORK ORDER:** 52 (D32, D31).

**FILES MODIFIED** (scripts/** and infra/terraform/** only)
- `scripts/emr/lib-emr.sh`
  - `SC_SPARK_TIMEOUT` default 1500 to 3000. The override and the exit-124 behaviour are kept.
  - The comment states the run 4 evidence. The limit also bounds the export stage and each capability-check SparkPi.
- `scripts/emr/run-s-pipeline.sh`
  - ER `timeout 1500` is now `timeout "${SC_ER_TIMEOUT:-3000}"`, with a comment that it is a ceiling (ER took about 103 s).
  - Markers, verdict rule, checkpoints, failure-path publish, run-id handling and codec and permission options are untouched.
- `infra/terraform/environments/course/experiment-size-s.tfvars`
  - `pipeline_emr_step_timeout_seconds` is now 4800 and the comment block is rewritten.
  - It states Rule A as 1440 + 4800 + 600 = 6840 <= 7200 (360 s slack), the worst case as 1440 + 300 + 4800 + 900 + 1800 = 9240 > 7200 (O1), and `--size medium` as 5,400 s.
- `infra/terraform/modules/workflow/variables.tf`: the description text "S overlay: 8040" is now 9240. The harness reads this figure.
- `infra/terraform/environments/course/variables.tf`: the S step value in a description is now 4800.
- `scripts/README.md`: new "On-node time limits" table, plus the S step cap and Rule A figures in "Pipeline sizes".
- `infra/terraform/README.md`: S 3600 became 4800 in the EMR design B note, the step-timeout table, "Pipeline bounds" (including Rule A and Rule B), the HIGH-bound statement, the overlay table and the overlay paragraph.
- `infra/terraform/COST_ESTIMATE.md`: the Region-audit note, section 0 item 6 and threat 11. No dollar figure changed.

**COMMANDS RUN:** Read and Edit, plus one `ls` of directories. No grep, find or shell checks.

**VALIDATION RESULTS**
- By reading only: the workflow module allows an EMR step of 300 to 7200, so 4800 is valid.
- The execution timeout allows 1800 to 10800, so 7200 is valid.
- The environment variable validations match the module's.
- Rule A (`create + step + 600 <= max`) holds on all tiers:

| Tier | Arithmetic | Result |
|---|---|---|
| S | 1440 + 4800 + 600 = 6840 <= 7200 | holds |
| M | 1440 + 2700 + 600 = 4740 <= 7200 | holds |
| L | 1440 + 3600 + 600 = 5640 <= 10800 | holds |

- M and L values are unchanged.
- **Unrun, for the human:**
  - `terraform fmt -check -recursive infra/terraform`
  - `terraform init -backend=false` then `terraform validate` in `environments/course`
  - `bash -n` and `shellcheck -x scripts/emr/*.sh`
  - `bash -n` and `shellcheck -x scripts/*.sh`
  - `bash tests/scripts/run.sh`
- Expect `run.sh` to fail one case until test-engineer repairs it (see BLOCKED-ON).

**TIMEOUT AUDIT** (line numbers after my edits)

| Bound | Value | File:line | Observed in S run 4 |
|---|---|---|---|
| Hadoop | `timeout 1500` | `run-s-pipeline.sh:174` | 740 s |
| ER | `timeout "${SC_ER_TIMEOUT:-3000}"` | `run-s-pipeline.sh:298` | 103 s |
| Spark and export (and SparkPi in the capability check) | `timeout "${SC_SPARK_TIMEOUT:-3000}"` | `lib-emr.sh:509` | Spark cut at 1,500 s |
| YARN MapReduce application list | `timeout 120` | `lib-emr.sh:634` | n/a |
| `mapred job -list all` and `mapred job -list` | `timeout 60` each | `lib-emr.sh:653`, `:656` | n/a |
| Progress checkpoint budget | `SC_PROGRESS_BUDGET_SECONDS=60`, each call `timeout "$remaining"` | `lib-emr.sh:65`, `:237` | n/a |
| Failed-log publish | `timeout 300` per file, no total budget | `lib-emr.sh:166` | n/a |
| Capability check `pi` jobs | `timeout 900` x2 | `capability-check.sh:89`, `:121` | n/a |

- **No timeout in the pipeline script:**
  - `aws s3 cp` in fetch-inputs and stage_spark (`run-s-pipeline.sh:86`, `:95`; `lib-emr.sh:422`-`:424`)
  - `hdfs dfs` (`lib-emr.sh:435`, `:437`)
  - the publish copy and listing (`lib-emr.sh:832`, `:837`)
  - `sha256sum`, `tar`
  - `install-java-bootstrap.sh`, whose `dnf`/`yum install` is bounded only by CreateCluster's 1440 s
- **Other bounds:**
  - No `--cli-read-timeout` or `--max-time` appears in `scripts/emr/*.sh`; the node AWS CLI uses its own defaults.
  - `scripts/lib.sh` bounds apply on the operator machine only: aws 10 s connect and 60 s read, docker login 120 s, build and push 1800 s, other docker 300 s.
  - The state machine fixes fetch at 300 s and terminate and cleanup at 900 s each.
- **Sums against the 4,800 s step cap:**
  - Sum of stage ceilings: 1500 + 3000 + 3000 + 3000 = 10,500 s.
  - With the Hadoop helper calls (120 + 60 + 60 = 240 s) and 9 checkpoints at 60 s (540 s), it is 11,280 s.
  - Every stage hitting its ceiling is therefore cut by the step cap, not the on-node limits, so the cap is the real outer bound.
  - Failed-log publish adds up to 300 s per file (about 8 files) on the failure path.
- **Normal case:**
  - The run 4 stages before Spark took 874 s. Spark's 3000 s ceiling therefore ends at about 3,900 s, inside the 4,800 s cap, so Spark's own timeout fires first and the failure-path publish can still run.
  - That holds as long as the stages before Spark take under about 1,800 s.
  - Expected total is 3,300 to 3,900 s (D32).
- **Reachability:** no bound makes the 4,800 s step cap unreachable, so nothing beyond items 1 and 2 was changed.
- **Capability check:** it runs three SparkPi submits at up to 3000 s each (up to 9,000 s), more than any step cap. Each SparkPi takes seconds, so this is harmless in practice. I noted it and did not change it.

**RISKS**
1. **Upload needed first.** The on-node scripts are fetched from S3 `bootstrap/`. The 3000 s limits only take effect after `upload-inputs.sh` re-uploads `lib-emr.sh` and `run-s-pipeline.sh`. That changes its `ARGS_DIGEST`, so it needs a new `upload` approval record.
2. **State machine update.** The state machine definition changes in place (step timeout 3600 to 4800). IAM changes are none, no resource is replaced, and the dollar figures are unchanged since caps are ceilings. This is expected, not planned or measured.
3. **Rule B.** The accepted O1 limitation widens to 2,040 s over 7,200. The script's 90-minute cap (5,400 s) stops the run first.
4. **Spark pace.** If Spark needs more than 3000 s it fails with exit 124. The next lever is spark-engineer's (D32 open item).

**BLOCKED-ON OWNER:** test-engineer (work order 54), evidence `tests/scripts/run.sh`. I did not edit tests/**.
- Fails: `14.terraform.s-overlay.timeouts-are-7200-1440-3600-1800` (about line 4389). It expects the S step to be 3600 (line 4393) and the case name carries 3600. It needs 4800 in the check and the name.
- Should now pass:
  - `14.terraform.s-overlay.comment-arithmetic-and-the-module-worst-case-figure-match-the-values` (line 4445). It reads the figures from the file and my text contains the needed strings: "1440 + 4800 + 600 = 6840 <= 7200", "1440 + 300 + 4800 + 900 + 1800 = 9240 > 7200", and in `variables.tf` "S overlay: 9240 against 7200".
  - `14.terraform.s-overlay.passes-rule-a-from-its-own-values`
  - `14.terraform.overlays.stay-inside-the-variable-ranges`
  - `14.terraform.m-and-l-overlays.comment-arithmetic-matches-the-values`
  - `14.terraform.rule-a.the-evaluator-in-this-harness-is-exact-at-the-boundary` (literal inputs, unaffected)
- No harness case asserts the old on-node 1500 s limit, `SC_SPARK_TIMEOUT` or the ER timeout.
- Optional new cases: the new defaults (3000) and `SC_ER_TIMEOUT` in `run-s-pipeline.sh`.

**NEXT GATE:** Human runs the static checks and harness listed above. Test-engineer then fixes the one stale case. Then the human decides on the `upload-inputs.sh` re-upload and a new S plan before the S run 5 approval chain.

## Orchestrator note (not part of the report)
The human ran the static checks (`phase14-static-checks-17.log`: terraform fmt and validate, bash -n and shellcheck on the four scripts and tests/scripts/run.sh, all exit 0) and the harness after work order 54 (`phase14-harness-7.log`, `RESULT pass=1031 fail=0`).
