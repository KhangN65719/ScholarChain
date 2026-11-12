# Work order 01: infra-engineer, Phase 12 carry-over F1 and F4

Owner: infra-engineer. Local only: no AWS calls, no credentials, no `terraform plan` or `apply`, no deploy. Do not run sbt. Do not edit CLAUDE.md, docs/agents.md or `.claude/**`. Do not commit.

Read first: CLAUDE.md, docs/agents.md, docs/agent-runs/phase-12/ratification.md (Open carried forward), docs/agent-runs/phase-12/review-round-3.md (F1, F4), docs/agent-runs/phase-13/decisions.md, infra/terraform/modules/workflow/{main,variables}.tf, infra/terraform/environments/course/{main,variables}.tf and the three `experiment-size-*.tfvars`, infra/terraform/COST_ESTIMATE.md (pipeline and loader time assumptions), infra/terraform/README.md ("Pipeline bounds", "Checks to run"), scripts/run-bounded-pipeline.sh and scripts/README.md ("Pipeline sizes").

## F1: give each step its own documented, bounded timeout

Problem: `var.step_timeout_seconds` is the `TimeoutSeconds` of CreateCluster, HadoopStep, SparkStep and RunLoader (modules/workflow/main.tf lines 35, 58, 77, 118). The M and L loader windows (2700 s, 3600 s) are shorter than the loader times COST_ESTIMATE.md itself assumes (S 0.5 to 1.0 h, M 1.0 to 2.0 h, L 2.0 to 4.0 h).

Do:
1. Replace the single variable with separate variables (suggested: `create_cluster_timeout_seconds`, `emr_step_timeout_seconds` for Hadoop and Spark, `loader_timeout_seconds`), each with a validation range and a description that says what it bounds and what happens on timeout. TerminateCluster and CleanupOnFailure keep 900 s; document that.
2. Derive each tier's value from the estimate's own figures (use the HIGH bound so the estimate cannot time out its own run). Show the arithmetic in the overlays' comments and in infra/terraform/README.md "Pipeline bounds": cluster start, Hadoop, Spark, terminate, loader.
3. Keep the overall rule explicit and checkable: `max_run_seconds` against the worst-case sum of sequential step timeouts. Add or update the module precondition so it still catches an inconsistent set. State plainly which failures go to `CleanupOnFailure` (Hadoop, Spark) and which go to `Failed` with no cleanup (CreateCluster, TerminateCluster, RunLoader), and what that means for a hung CreateCluster. Correct the README sentence that says a CreateCluster hang "ends in the execution timeout".
4. Keep `scripts/run-bounded-pipeline.sh` `--max-minutes` caps, scripts/README.md "Pipeline sizes" and COST_ESTIMATE.md consistent with whatever you set. Edits to scripts/** are yours too.
5. STOP CONDITION. If making the loader windows consistent with the estimate forces any of these to rise above today's value: `pipeline_max_run_seconds`, the `--max-minutes` caps (60, 90, 150), or the projected worst-case spend in COST_ESTIMATE.md, do not apply the raise. Report `BLOCKED-ON` human decision with a table of the options (keep caps and shorten the stated loader assumption, or raise caps), each with the dollar change at the HIGH hourly burn already in scripts/README.md. Apply only the parts that need no raise. Cost and caps are the human's decision.
6. Every figure you change must be recomputed and each place that cites it updated (overlays, workflow variables, environments/course/{main,variables}.tf, infra README, scripts README, COST_ESTIMATE.md). List each place in the report.

## F4: README check block

infra/terraform/README.md "Checks to run" code block still lists `init -upgrade` while the text says it is not needed. Make the block match the text (init `-backend=false`, no `-upgrade`; the lock file is committed).

## Checks (run what the guard allows, report what it refuses)

- `terraform fmt -check -recursive -diff infra/terraform`
- `terraform -chdir=infra/terraform/environments/course init -backend=false` then `validate`
- `bash -n` on every file you edited under scripts/ and `shellcheck -x` on the same (`shellcheck` is installed). If the guard refuses any of these for an agent, do not work around it: report `BLOCKED-ON` with the refusal text and list the files that still need the human's check.
- Do not read or print `.tfstate`, credentials or `.env` files.

Before editing, list in the report which check proves each item of your primary gate.

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE. Report only commands you observed succeed.
