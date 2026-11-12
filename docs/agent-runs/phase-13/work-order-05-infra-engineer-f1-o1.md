# Work order 05: infra-engineer, finish F1 under the human's decision O1 (decisions.md D10)

Owner: infra-engineer. Local only. No AWS, no credentials, no plan or apply, no sbt. Do not edit CLAUDE.md, docs/agents.md or `.claude/**`. Do not commit. Read first: report-01-infra-engineer-f1-f4.md in this directory and decisions.md D10, D11.

Human decision: keep the caps. `--max-minutes` 60/90/150 and `pipeline_max_run_seconds` 5400/7200/10800 do not change. No cost figure rises.

Do:
1. Keep the separate create/EMR-step/loader timeout structure you built. Choose each tier's three values so every one is bounded, documented and fits inside the unchanged `pipeline_max_run_seconds`; Rule A must hold on every tier. Rule B (the sum with terminate allowance and loader) will not hold at the estimate's HIGH bound: that is the accepted outcome. Change Rule B from a generic warning that fires on every tier into an explicit, named, documented known limitation (keep it as a plan-time warning only if its text says it is the accepted O1 outcome and where it is recorded; otherwise remove it). Do not weaken Rule A.
2. State plainly in infra/terraform/README.md "Pipeline bounds", scripts/README.md "Pipeline sizes" and COST_ESTIMATE.md (wording only, no figure changes): at the estimate's HIGH bound a run may time out; the estimate's HIGH hours are not a promise the caps cover; a timed-out run still wastes its spend and needs a re-run with a new approval record; a cap is a ceiling, not a cost.
3. Document the recorded human step: after the S run in Phase 14, the human sets the M and L caps from the measured S runtime, scaled by size (5,000, 25,000 and 100,000 works) with a 1.5x margin, still bounded by the $25 ceiling and the existing validation ranges, and records that as a human decision before M runs. Same step for the three EMR/loader timeouts. Do not pick the numbers now; give the formula and where the decision is recorded (a Phase 14 decisions file). Add the same sentence to the "Open items" of scripts/README.md.
4. Restate nothing in the estimate's hour assumptions that you cannot support; if a sentence in COST_ESTIMATE.md says the estimate's runs fit the caps, correct it.
5. Sweep for stale references to `pipeline_step_timeout_seconds` or `step_timeout_seconds` in infra/**, scripts/**, and report any in other trees (docs/**, tests/**, modules/**) with file and line, without editing files you do not own.
6. Checks: the guard refuses terraform and shellcheck for you (D11). Do not try workarounds. Re-list for the human the exact commands (terraform fmt -check -recursive -diff infra/terraform; init -backend=false; validate; and, if you touched any script, bash -n and shellcheck -x on it).

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE. Report only commands you observed succeed.
