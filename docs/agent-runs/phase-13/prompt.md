# Phase 13 prompt

## Scope note (human instruction and orchestrator reading; not part of the course text)

Phase 12 is accepted at 102e1b8. Phase 13 is local only: no AWS calls, no credentials, no `terraform plan` or `apply`, no deploys, no live operations. The course text below says "Run every non-destructive script in the bounded non-production environment" and "Terraform planning is reproducible and the plan checksum is archived". Those clauses need a live account. They are deferred to Phase 14 and recorded as pending, not satisfied. What Phase 13 can prove locally is syntax (`bash -n`, `shellcheck`), `--help`, `--print-digest`, `--dry-run` and refusal paths.

Constraints from the human for this phase:
- Build on the existing scripts/ (plan, deploy, rollback, smoke-test, run-bounded-pipeline, cleanup, lib). Do not rewrite them.
- Every script has a dry-run mode testable locally, records target account and Region, refuses to act without a valid approval record, lists resources before any deletion, and never runs a destructive AWS or Terraform operation without explicit human invocation.
- `shellcheck` is installed and agents may run it and `bash -n`. No other tool or dependency without asking the human.
- Step 1 first: Phase 12 carry-over F1 (per-step timeouts), F2 (N2 grace period), F4 (README check block). Work orders 01 and 02.
- Route work to owners per docs/agents.md. Do not edit CLAUDE.md, docs/agents.md or `.claude/**`. Agents run `sbt quick` during work and one `sbt check` before review. Never two sbt commands at once. Read-only git. No commit.
- End with a fresh read-only code-reviewer (Opus) review, at most 2 batched repair rounds with re-review of changed items only, then the human's `sbt clean check`, then stop for acceptance.

Owners in this phase: infra-engineer (scripts/**), experiment-engineer (experiments/**, load-tests/**), test-engineer (tests/**), documentation-engineer (README.md and docs/** except agents.md, agent-runs, incidents, releases, dependencies), code-reviewer (verdict only).

Decisions D1 to D9 are in decisions.md.

## Course text, verbatim (Phase 13 section of /Users/winnguyen/scholarchain-data/course-README.md)

## Phase 13: create reproducible build, deployment, and experiment automation

Purpose:

Provide source-controlled commands that reproduce validation, packaging, Terraform planning, deployment, bounded pipeline execution, smoke testing, experiments, evidence archiving, and cleanup without depending on a separately administered CI or CD server.

Why:

A project is not reproducible when its successful deployment depends on undocumented commands remembered by one student. The authoritative procedures must live in the repository as inspectable scripts that a grader can run directly.

Use this work order:

```text
Use the infra-engineer agent to create or finalize the source-controlled automation under scripts/ and any optional GitHub Actions workflows that call those scripts.

Required scripts or equivalent commands:

1. scripts/check.sh
- Validate agent constitution files and JSON configuration.
- Run hook syntax checks.
- Run scalafmt check, sbt Test/compile, sbt test, contract validation, schema validation, and shell syntax checks.
- Run Terraform format and validate.
- Exit nonzero on any failed gate.

2. scripts/package.sh
- Build Hadoop and Spark artifacts.
- Build required service images.
- Record checksums and image tags derived from the Git commit SHA.

3. scripts/plan.sh
- Run Terraform plan for the selected environment.
- Record plan file, plan checksum, commit SHA, target account and Region, and a summary of add, change, destroy, IAM, and stateful-resource actions.
- Never apply automatically.

4. scripts/deploy.sh
- Refuse a dirty tree.
- Require an explicitly approved plan checksum or the documented human-approval mechanism.
- Apply or invoke the approved deployment path exactly as documented.
- Record deployed image digests and revisions.

5. scripts/smoke-test.sh
- Check health and readiness.
- Run a bounded researcher query.
- Fetch evidence for at least one returned claim.
- Verify idempotency and one expected error path.

6. scripts/run-bounded-pipeline.sh
- Run or submit the approved bounded Hadoop and Spark pipeline.
- Record dataset snapshot, run IDs, configuration, and output locations.

7. scripts/run-experiment.sh or an equivalent manifest-driven command
- Execute only approved experiment manifests.
- Record commit SHA, resource limits, start and end times, Region, and metric locations.

8. scripts/archive-evidence.sh
- Gather test reports, plan checksum, image digests, run manifests, selected CloudWatch metric exports, experiment outputs, and release metadata into a versioned evidence directory or approved S3 prefix.

9. scripts/cleanup.sh
- List resources targeted for removal.
- Require the documented human confirmation for destructive actions.
- Preserve required state, logs, or final reports according to the cleanup policy.

Automation requirements:
- Bash strict mode or an equivalently strict scripting language configuration.
- No secrets in source control or command output.
- Timeouts on cloud operations.
- Retry only safe idempotent actions.
- Greppable step markers and explicit exit codes.
- Commands must work from a clean checkout after documented prerequisites are installed.
- Optional GitHub Actions may call these scripts, but the scripts remain the authoritative reproducibility interface.

Run every non-destructive script in the bounded non-production environment.
Report command lines, required environment variables by name, human approval points, generated evidence, and rollback behavior.
```

Gate:

- A clean checkout can run all non-cloud validation through the documented script.
- Terraform planning is reproducible and the plan checksum is archived.
- Deployment cannot proceed without the documented human approval.
- Smoke tests exercise a real bounded collaboration query.
- The same scripts are used in the README reproduction instructions and final demonstration.
