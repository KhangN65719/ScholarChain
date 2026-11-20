# WO-B3 report (documentation-engineer), as received 2026-11-19 (condensed by the orchestrator; field values kept)

STATUS: COMPLETE. The Stop gate is open and non-blocking: the agent has no shell and changed only Markdown.
OWNER: documentation-engineer
WORK ORDER: WO-B3 (threat model, service limits, runbook)
FILES CREATED: `docs/threat-model.md`, `docs/service-limits.md`, `docs/runbook.md`
FILES MODIFIED: none
COMMANDS RUN: none (no shell)
TEST RESULTS: none. Every replayable step is marked NOT REPLAYED until H-8.
VALIDATION RESULTS: written from the sources read. Each limit is marked VERIFIED, OBSERVED or UNVERIFIED. No account id and no grader-directed text. The destructive-command controls cite `.claude/hooks/policy.py` and `.claude/settings.json`. Links to the B2 files were not checked.
ASSUMPTIONS: the Phase 7 AWS limits are labelled verified on the 2026-11-08 read and were not re-read. That Git reset, rebase and clean are refused only by default-deny (`policy.py:158`) is a reading of the code, not a probe result.
RISKS: re-cite the R03 header after ratification. The Phase 7 limits are 11 days old. Sources not read: Hadoop reject counters, the rag verifier source, and the dataset licence and checksum records. The full argument lines of `deploy.sh`, `rollback.sh` and `cleanup.sh` are NOT RECORDED; the runbook points to `--help`.
MANUAL ACTION REQUIRED: H-8 replay of runbook sections 1, 2, 3, 5 and 7. Human decisions the documents list: the unencrypted ALB listener, secret values retained between sessions, and P16-34 before any Haiku deploy.
Left UNVERIFIED or NOT RECORDED:
- Fargate and EC2 vCPU quota results (P15-8)
- Neptune openCypher condition-key behaviour on a live query
- Haiku injection behaviour as its own measurement
- a per-client rate limit (none found in `modules/services/src/main`)
- the exact Phase 15 inventory commands
- whether `smoke-test.sh` ever ran against a deployment
BLOCKED-ON OWNER: none. BLOCKED-ON EVIDENCE: none.
NEXT GATE: B12 review, then H-8.
