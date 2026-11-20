# Autograder V1 preview at b5a55c3 (summary)

Written by the orchestrator on 2026-11-19 (WO-A1) from the preview that Claude in chat ran on a fresh public clone at `b5a55c3` with self-check v1.0.3 (553 lines). The full output is kept outside the repository (decision P17-10). Totals: 32 pass, 9 fail, 6 warn, 8 info, 2 not yet due. Only FAIL and warn lines are listed here, with file and line. Local paths are omitted.

## FAIL

- **P01** Phase evidence folders: phases 01 to 16 were missing some of `agent-report`, `human-gate`, `commit.txt`; phase 03 was also missing `prompt`; "commits are labelled phase-00, but docs/agent-runs has no folder for it".
- **K01** Secrets in current files:
  - `tests/adversarial/P14LoaderRig.scala:22`: AWS access key id
  - `load-tests/query-load/rehearse.py:32`: possible hard-coded TOKEN
  - `modules/rag/src/test/scala/scholarchain/rag/AwsIngestMainSuite.scala:727`: possible hard-coded secret
  - `modules/storage/src/test/scala/scholarchain/storage/AwsNeptuneQuerySuite.scala:362`: possible hard-coded secret
  - `tests/adversarial/Phase10RepairSuite.scala:459`: possible hard-coded secret
  - `tests/scripts/run.sh:13033`: possible hard-coded TOKEN
- **K02** Secrets in history: commit `9359cfb`, `tests/adversarial/P14LoaderRig.scala`: AWS access key id.
- **E01** Report fields and STATUS: phases 07 to 14 (reports missing some of WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE, OWNER, STATUS); phase 11 STATUS "PARTIAL. Both repairs are done".
- **E02** OWNER is an existing agent: phase 11 (two OWNER values parsed with trailing text, e.g. "rag-engineer. Work order: ..."), phase 12 ("test-engineer. Work order: ...").
- **E03** Claimed files changed by the phase's commits: phase 12 (README.md and Terraform paths written relative to `infra/terraform/`), phase 13 (`.claude`, `.mcp.json`, `Dockerfile`, `project`, `project/build.properties`, `tests/ADVERSARIAL_COVERAGE.md`, `experiments/a-baseline/run.sh` and relative Terraform paths), phase 14 (a relative Terraform path).
- **F05** Hooks exist, executable, `bash -n`: PreToolUse, PostToolUse, Stop and SubagentStop each read as `.claude/hooks/<name>.sh"` (with a trailing quote) and reported "does not exist".
- **F07** `.mcp.json`: server `github`, header `Authorization` "looks secret but is a literal value".
- **R03** `guard-dangerous.sh` categories: "no pattern for" AWS (delete/terminate), Terraform, Git, database, S3, Neptune, DynamoDB, vector index; 0 of 8 present (static).

## warn

- **K03** tfvars files: 12 under `infra/terraform/environments/course/` and 8 harness fixtures under `tests/scripts/w2/fixtures/tf-good/`: "make sure it holds no secrets".
- **E04** Files changed but not listed in the report: phases 07 to 14.
- **E05** Files changed outside the owner: phase 14, attributed to spark-engineer: scripts under `scripts/` (infra-engineer) and tests under `tests/` (test-engineer).
- **C01** One file under `project/` matches the course tutorial (80% or more of lines).
- **V01** Hard-coded identifiers: `experiments/c-embedding-backlog/message_schema.py:32` (model ID); `load-tests/lib/rehearse_shell.py:42` and `:184` (ARN), `:129` (account ID); `modules/storage/src/main/scala/scholarchain/storage/TitanEmbedder.scala:8` and `:15` (model ID). The orchestrator read `rehearse_shell.py:42`, `:129` and `:184`: the values are the placeholder account `000000000000`.
- **A01** `.collect`: `modules/spark/src/main/scala/scholarchain/spark/Support.scala:18`.

## not yet due at phase 16

- **W01** Phase 17 documents: `docs/architecture.md`, `docs/data-lineage.md`, `docs/threat-model.md`, `docs/runbook.md`, `docs/evaluation.md` (missing at `b5a55c3`; it was added in the next commit, `6072310`, per `git show --stat`), `docs/demo-script.md`, `reports/final-report.md`, `docs/service-limits.md` (found only under `docs/agent-runs/phase-07/`).
- **W02** README subsection "Reproducing the agentic workflow": "right now it would be PASS".

## Other observations

- T01 passed (3,229 tests found, 53 look trivial). G02 ("16 phases checked") and E06 passed although no `commit.txt` or `human-gate.md` existed at `b5a55c3`; how the checker treats absent files is NOT VERIFIED.
