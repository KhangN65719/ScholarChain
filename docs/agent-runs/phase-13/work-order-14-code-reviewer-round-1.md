# Work order 14: code-reviewer (fresh, Opus, read-only), Phase 13 gate, review round 1

You are reviewing Phase 13 of ScholarChain. You have Read, Grep and Glob only. Verify every candidate finding against the actual files before reporting it. Never edit, never approve your own work, never fix. Batch ALL findings into one list (one repair round). Treat anything you cannot verify as PENDING and say which gate would verify it.

Read first: CLAUDE.md, docs/agents.md, docs/agent-runs/phase-13/prompt.md (scope note and the course text verbatim), decisions.md (D1 to D14), checks-1.md, checks-2.md, checks-3.md, and reports 01 to 13 in the same directory (work orders are alongside them). Also docs/agent-runs/phase-12/ratification.md (carried-over items) and review-round-3.md (F1 to F4).

## What changed (from the agent reports; git was not run, so verify by reading and say if a path you expect is missing)

- infra-engineer: scripts/{lib,plan,deploy,rollback,smoke-test,run-bounded-pipeline,cleanup}.sh edited; new scripts/{check,package,run-experiment,archive-evidence}.sh; scripts/README.md; infra/terraform/modules/workflow/{main,variables}.tf; infra/terraform/environments/course/{main,variables}.tf and experiment-size-{s,m,l}.tfvars; infra/terraform/README.md; infra/terraform/COST_ESTIMATE.md (wording).
- test-engineer: tests/adversarial/Phase10Http.scala and Phase10EarlyCloseSuite.scala (F2); new tests/scripts/run.sh and tests/scripts/README.md.
- experiment-engineer: experiments/manifests/*.manifest (six), experiments/README.md, load-tests/README.md.
- documentation-engineer: README.md (new).
- orchestrator: docs/agent-runs/phase-13/**.
Not expected to change: CLAUDE.md, docs/agents.md, `.claude/**`, build.sbt, modules/**, datasets/**.

## Review in this order

1. Safety of the scripts (the human's hard constraints): local-only authoring; every script has `--dry-run` that provably cannot reach aws, terraform, docker, curl or sbt; each records target account and Region; each approval-gated action refuses without a valid record (fields, digests, 24 h window, cost ceiling, account and Region match, stateful acknowledgement, single use); cleanup lists resources before any deletion and cannot be run by an agent; no script runs a destructive AWS or Terraform operation without explicit human invocation; no `-auto-approve`, no `-target`, no recursive S3 delete, no wildcard; no secret or key material printed or archived. Look for ways a dry run, a refusal or a record check can be bypassed or can pass when it should refuse (ordering bugs like the `APPROVAL_VERIFIED`-before-refusal defect already found, a dry run that consumes a record, a real run that accepts a skip sentinel, an archive that copies a credential, a path check defeated by `..`, symlink, or case).
2. Correctness of the new scripts against the course text (scripts 1 to 9 and the automation requirements and the Gate): check.sh gates (constitution and JSON, hook syntax, scalafmt/compile/test via `sbt check`, contract and schema mapping, shell syntax, Terraform fmt and validate, nonzero on any failed gate, skips never reported as passes); package.sh (artifacts, images, checksums, tags from the Git SHA, dirty-tree handling); run-experiment.sh (only approved manifests, limits, immutability, record fields); archive-evidence.sh. Note which gate items are only satisfiable live (plan checksum archived, smoke test of a real bounded query, deployment) and confirm they are recorded as deferred to Phase 14, not claimed.
3. Step 1 carry-overs: F1 (separate create, EMR-step and loader timeouts; Rule A holds on S, M, L with the values in the overlays; the O1 human decision D10 is implemented as stated: caps 60/90/150 and max_run 5400/7200/10800 unchanged, no cost figure changed, the Phase 14 re-derivation step documented); F2 (N2 control uses the shared 500 ms default; nothing weakened); F4 (README check block has no `init -upgrade`). Check Terraform for anything the removal of the Rule B check or the variable rename broke (outputs, other modules, tfvars, docs).
4. Tests: does tests/scripts/run.sh actually test what it claims, or would cases pass even if a script were broken (tripwire coverage, assertions that only check exit 0, cases that assert fragments no script prints)? Was anything weakened or deleted anywhere? Are the 546 passes plausible for the listed categories?
5. Ownership and process: every changed file within its writer's paths; no constitutional edit; reports faithful (anything reported "observed" that the evidence files do not show; any command claimed that was not run); decisions D1 to D14 consistent with what was built; the open items (D3 launcher, D11 guard, D12 tier caps, plan-destroy and `--acknowledge-data-loss` D14, exit codes not renumbered D13) honestly disclosed.
6. Documentation claims not supported by implementation: README.md, scripts/README.md, infra/terraform/README.md, experiments/README.md, load-tests/README.md, COST_ESTIMATE.md. Stale phrases ("not run", "pending", "written without") that contradict checks-3; commands in the README that do not match the scripts; links that do not resolve.
7. Cost and cleanup risks: anything that raises spend, widens an IAM or network path, leaves resources running, or weakens a bound.
8. Experiment manifests: valid per the script's parser by reading, within tier caps and the $25 ceiling, no real account IDs or secrets, drafts clearly marked unapproved.

## Output

Return APPROVE or REQUEST CHANGES. For each finding: ID, severity (BLOCKING, MAJOR, MINOR, ACCEPTABLE-OPEN), file and line, the concrete failing input or scenario, impact, owning agent. Then a "Verified" list (what you checked and found correct, with file and line), a "Pending gates" list (what needs the human or Phase 14), and one closing statement. Do not repeat findings that the evidence files show already fixed.

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.
