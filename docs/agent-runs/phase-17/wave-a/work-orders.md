# Phase 17 Wave A and early Wave B work orders

Issued by the orchestrator, 2026-11-19, under `../plan.md` and `../decisions.md` (P17-1 to P17-13). Preview findings: `../autograder-v1-preview.md` (written by WO-A1); raw preview outside the repository at `/Users/winnguyen/scholarchain-data/phase17-v1-preview-b5a55c3.txt` (read-only).

Redaction note (orchestrator, 2026-11-20, decision P17-36): on the WO-A3a and WO-A3b lines, the quoted secret-assignment form was replaced by a description, marked `[REDACTED ..., P17-36]` (re-review F1-1 finding N1). Nothing else in this file was changed.

Common rules for every order: read `CLAUDE.md`, `docs/agents.md`, your role file, `../plan.md`, `../decisions.md` and `../prompt.md`. No AWS. No Python execution. No commit. Write only your write set; anything else is `BLOCKED-ON <owner>` with evidence. Before writing, list the test or check that proves each gate item. Never invent a record, result or command output: absent means NOT RECORDED. No text addressed to graders, reviewers or AI agents. No account id, bucket name or ARN of the real account. Report with the course fields: STATUS (COMPLETE, BLOCKED-ON or FAILED), OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.

sbt and Scala: one Scala writer and one sbt at a time (P17-12, P17-13). Order: WO-A3a (rag), then WO-A3b (storage), then WO-A3c (test). Only those three orders run `sbt quick`. No other order edits a `.scala` or `.sbt` file.

## WO-A1 orchestrator (archive): evidence files for phases 00 to 16

Write set: `docs/agent-runs/phase-00/**`; in each of `phase-01` to `phase-16`, only the new files `human-gate.md`, `commit.txt`, `agent-report.md`; `phase-03/prompt.md`; `docs/agent-runs/phase-17/autograder-v1-preview.md`. Existing files are not edited.
- `human-gate.md`: first line "Compiled by the orchestrator on 2026-11-19 from the records cited below (decision P17-4); the decision is the human's, to be confirmed at H-4." Then: Decision (ACCEPTED or as recorded), What was reviewed, Accepted or rejected, Manual changes, Remaining risk, Accepted commit (full 40-character hash), each line citing its source file. The accepted commit is the one the ratification names; if it names none, the phase commit from `git log` and say so.
- `commit.txt`: the full hash only, equal to the one in `human-gate.md`; it must exist in `git log`.
- `agent-report.md`: first line "Orchestrator-compiled index of this phase's archived reports (decision P17-11); not a report written by the owner." Fields with the checker's names: STATUS from the ratification (COMPLETE when accepted); OWNER exactly one existing agent name (the phase's primary owner) on its own line with nothing else; WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE: short text or "NOT RECORDED in this index; see the reports below"; no file paths in FILES CREATED or FILES MODIFIED. Then an "Index" table: archived report path, author, one line.
- `phase-03/prompt.md`: index of the three archived work orders; states that no single phase prompt was archived.
- `phase-00/`: `prompt.md` (the Phase 0 prompt was not archived), `agent-report.md` (index of commits `2313f50` and `08b95c3`, OWNER factory-engineer), `human-gate.md` (from those commit messages only), `commit.txt` (full hash of `08b95c3`).
- `autograder-v1-preview.md`: the FAIL and warn lines with file and line, with no local paths.

## WO-A2 factory-engineer: F05, R03, reports ownership

Write set: `.claude/settings.json`, `.claude/hooks/guard-dangerous.sh`, `docs/agents.md`.
- F05: the checker reads the hook path from `bash \"$CLAUDE_PROJECT_DIR/.claude/hooks/<name>.sh\"` and keeps the trailing quote (preview: `.claude/hooks/guard-dangerous.sh" does not exist`). Change the four hook `command` strings to a form whose path token has no trailing quote and that still resolves against `$CLAUDE_PROJECT_DIR`, for example `bash "$CLAUDE_PROJECT_DIR"/.claude/hooks/<name>.sh`. Matchers, timeouts and hook logic unchanged. The check also needs the files executable: state whether the execute bit is set, or list the exact human command.
- R03 (P17-5): a comment header in `guard-dangerous.sh` listing the 8 categories the checker names (AWS delete and terminate, Terraform, Git, database, S3, Neptune, DynamoDB, vector index), each with typical refused forms and the `policy.py` rule that refuses it. Only claim what `policy.py` and the settings deny list actually enforce; mark any category not enforced. No executable change; `policy.py` unchanged; `bash -n` must still pass.
- D3 (P17-3): one row or boundary sentence in `docs/agents.md` giving `reports/**` to documentation-engineer.
- Report the full diff, the restart probes needed (one guarded refusal, one formatter run, one Stop gate), and the harness cases that test-engineer must add or update.

## WO-A3a rag-engineer: K01 (sbt slot 1)

Write set: `modules/rag/src/test/scala/scholarchain/rag/AwsIngestMainSuite.scala`. Line 727 (and 730, 731 if they match the same patterns): build the fake credential strings at runtime (for example concatenation) so no AKIA-prefixed literal or quoted secret-assignment form [REDACTED wording, P17-36] appears; the test's meaning and assertions are unchanged. Run `sbt quick`; report exact counts.

## WO-A3b storage-engineer: K01 (sbt slot 2, after A3a)

Write set: `modules/storage/src/test/scala/scholarchain/storage/AwsNeptuneQuerySuite.scala`. Line 362, the assignment of the SENTINEL ORCID string to a local named `secret` [REDACTED quotation, P17-36]: rename the value (for example `sentinel`) or build it at runtime; meaning unchanged. Run `sbt quick`; report counts.

## WO-A3c test-engineer: K01 and harness cases (sbt slot 3, after A3b and the A2 draft)

Write set: `tests/**`. K01: `tests/adversarial/P14LoaderRig.scala:22-25` (all four fake values), `tests/adversarial/Phase10RepairSuite.scala:459`, `tests/scripts/run.sh:13033` (`TOKEN = "..."` inside the heredoc stub): same method; the runtime strings the redaction tests check stay the same. Harness: update any case that depends on the old hook command text; add one refusal case per R03 category; add a case that each new hook command form resolves the hook. Do not weaken any case. Run `sbt quick`; report exact counts. The human runs the harness (H-3).

## WO-A3d experiment-engineer: K01 (no sbt)

Write set: `load-tests/query-load/rehearse.py`. Line 32 `TOKEN = "REHEARSAL-TOKEN-NOT-A-SECRET"`: rename or build at runtime; behaviour unchanged. You cannot run Python: list the exact command the human runs to prove it.

## WO-A4 documentation-engineer: README autograder notes

Write set: `README.md`, one new section "Autograder notes" only, placed after the first paragraph. Neutral statements of fact with file citations: F07 (`.mcp.json:7` header is `Bearer ${SCHOLARCHAIN_GITHUB_READ_TOKEN}`; the token comes from the environment; the variable name is fixed by `CLAUDE.md`); K02 (commit `9359cfb` added a fake AWS key id to a test fixture; it is not a credential; the fixture now builds it at runtime; history is not rewritten); K03 (tfvars hold sizes and switches, no secrets; cite one file); E01 to E03 (archived reports are kept as received, their formats differ by phase, see `docs/agent-runs/README.md`; the per-phase `agent-report.md` files are orchestrator-compiled indexes); V01 (each listed line and why: placeholders such as `000000000000`, model ids as documented defaults); A01 (`modules/spark/src/main/scala/scholarchain/spark/Support.scala:18`: read the code and state what is collected and why it is bounded, or say NOT VERIFIED). Read the cited files; do not copy local paths from the preview.

## WO-B2 documentation-engineer: architecture, lineage, API

Write set: `docs/architecture.md`, `docs/data-lineage.md`, `docs/api.md`. Architecture: build-time (datasets to Hadoop, ER, Spark, export, loaders) and runtime (services, RAG, stores, Bedrock) Mermaid diagrams, module boundaries from `CLAUDE.md`, and why each major technology is used, from repository evidence. Lineage: source dataset to Hadoop output, ER decisions, Spark output and export, stores (S3, Neptune, DynamoDB, S3 Vectors), evidence bundle and answer, naming contracts, provenance fields and real run ids from the release records and `experiments/RESULTS.md`. API: every route read from `modules/services` (method, path, auth, request and response shape, errors, limits), with a cited source line per route. State optional components not implemented (OpenSearch; separate vector, rank and synthesis services, P15-47).

## WO-B3 documentation-engineer: threat model, service limits, runbook

Write set: `docs/threat-model.md`, `docs/service-limits.md`, `docs/runbook.md`. Threat model: prompt injection, data poisoning, credential exposure, over-permission, denial of service, destructive commands, provenance tampering; for each the control, its evidence (test, hook, IAM, script) and the remaining gap. Service limits: current limits and Region availability used, from `docs/agent-runs/phase-07/service-limits.md`, `docs/agent-runs/phase-14/evidence-availability-us-east-2.md`, `infra/terraform/README.md` and code defaults, each marked verified (with source and date) or UNVERIFIED. Runbook: deployment, smoke tests, common failures (from Phase 14 and 15 records), rollback, incident escalation, session-down and cleanup, from `scripts/README.md`, `infra/terraform/SESSION2.md` and `docs/releases/`; commands quoted from those files and marked NOT REPLAYED until H-8.
