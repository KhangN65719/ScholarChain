# Phase 14 agent report (index)

Orchestrator-compiled index of this phase's archived reports (decision P17-11); not a report written by the owner.

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: see the phase prompt and the numbered work orders in this folder
FILES CREATED: NOT RECORDED in this index; see the reports below
FILES MODIFIED: NOT RECORDED in this index; see the reports below
COMMANDS RUN: NOT RECORDED in this index; see the check records and release records
TEST RESULTS: human sbt clean check Total 93 and 1,133, 0 failed, 0 errors, [success]; script harness 1,453 cases, 0 failed (ratification)
VALIDATION RESULTS: tier S pipeline, 41,885 vectors and Neptune load of 32,225 vertices and 135,788 edges with 0 errors, per the loader's own lines (ratification)
ASSUMPTIONS: NOT RECORDED in this index; see the owner reports
RISKS: accepted-open items in the ratification
MANUAL ACTION REQUIRED: live AWS runs by the human through approval records
BLOCKED-ON OWNER: NONE at acceptance
BLOCKED-ON EVIDENCE: NONE at acceptance
NEXT GATE: Phase 15

## Index

Phase 14 had many owners and over 100 work orders; work orders 85 to 114 are summarized in decisions D36 to D38 (ratification).

| Archived report or verdict | Author | Summary |
|---|---|---|
| `docs/agent-runs/phase-14/report-01-infra-engineer-us-east-2.md`, `report-02-infra-engineer-followup.md`, `report-08-infra-engineer-engine-and-docker.md`, `report-19-infra-engineer-compare-designs.md`, `report-20-infra-engineer-emr-design-b.md`, `report-31-infra-engineer-loader-iam.md`, `report-52-infra-engineer-spark-er-timeouts-step-cap.md` | infra-engineer | Region move, EMR design, IAM, timeouts |
| `docs/agent-runs/phase-14/report-03-test-engineer-region.md`, `report-06-test-engineer-case-fix.md`, `report-07-test-engineer-run-bounded-real.md`, `report-54-test-engineer-timeouts-step-cap-cases.md` | test-engineer | Harness cases |
| `docs/agent-runs/phase-14/report-04-documentation-engineer-region.md` | documentation-engineer | Region documentation |
| `docs/agent-runs/phase-14/report-05-experiment-engineer-manifests.md` | experiment-engineer | Manifests |
| `docs/agent-runs/phase-14/report-16-storage-engineer-aws-loader-inventory.md`, `report-53-storage-engineer-loader-path-check.md` | storage-engineer | AWS loader |
| `docs/agent-runs/phase-14/report-19-spark-engineer-compare-designs.md`, `report-51-spark-engineer-partitions-run-id.md` | spark-engineer | Spark on EMR |
| `docs/agent-runs/phase-14/reports-11-12-13.md`, `reports-14-15.md`, `reports-55-to-59-repair-rounds.md`, `reports-60-61-export-committer.md` | several owners | Grouped reports as received |
| `docs/agent-runs/phase-14/checks-1.md` to `checks-5.md`, `results-s-tier.md` | orchestrator | Checks and the tier S results |
| `docs/agent-runs/phase-14/decisions.md` and the `decisions-d*.md` files | orchestrator | Decisions D1 to D38 including review rounds |
| `docs/releases/phase-14/` | deploy-engineer | Release records |
