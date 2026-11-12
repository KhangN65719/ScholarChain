# Report 05: infra-engineer, F1 under O1 (as received, condensed)

STATUS: COMPLETE for the edits. Every check pending the human (guard refuses terraform, shellcheck, grep: D11). Stale-reference sweep only partly done.

Files modified (no figures changed, no script touched, SCRIPT_DIGEST unchanged):
- modules/workflow/main.tf: Rule B `check` block and unused local `worst_case_sequence_seconds` removed; comment records the known limitation (O1, D10). Rule A untouched.
- modules/workflow/variables.tf, environments/course/variables.tf: descriptions say known limitation / accepted under O1.
- environments/course/experiment-size-{s,m,l}.tfvars: comments only; M and L described as placeholders until set from the S runtime.
- infra/terraform/README.md: "Pipeline bounds" (Rule B limitation, four plain statements, Phase 14 formula), corrected header and "Checks to run" note.
- scripts/README.md: "Pipeline sizes" and a new first "Open items" bullet.
- COST_ESTIMATE.md: wording only (section 0 item 6; threat row 11 "Accepted limitation (O1)").

Tier values kept (Rule A holds on every tier): S create 1440 / step 1800 / loader 1800, sum 5340, max_run 5400. M 1440 / 2700 / 2700, sum 7140, max_run 7200. L 1440 / 3600 / 3600, sum 8940, max_run 10800.

Phase 14 formula (infra README): value(tier) = measured S value x (works / 5,000) x 1.5 (factors 7.5 for M, 30 for L), for the script cap, the EMR step timeout and the loader timeout; inside validation ranges, Rule A holds, spend under $25; recorded in docs/agent-runs/phase-14/decisions.md before M runs. Create-cluster rule (measured S start x 1.5, not scaled) is the agent's proposal.

Sweep: one deliberate historical mention at infra/terraform/README.md:130. Not searched (no grep): scripts/*.sh, rest of environments/course, other modules, docs/**, tests/**, modules/**.

Commands: none succeeded as a check. Human checks pending: terraform fmt -check -recursive -diff infra/terraform; init -backend=false; validate; `grep -rn "step_timeout_seconds" infra scripts docs tests modules`.

Risks: edited Terraform not formatted or validated; M formula can exceed ranges if S whole-run exceeds 20 min (needs a human decision, no silent clamping); linear scaling by works is an assumption; half-created-cluster termination UNVERIFIED.
