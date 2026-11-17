# Work order: finalize the A + B session runbook (orchestrator to deploy-engineer, 2026-11-17)

Target: `docs/releases/phase-15/session-ab-runbook-draft.md` (revision 2, approved in review 5). Owner: deploy-engineer. Nothing is executed; no AWS command, no sbt, no git write.

## Inputs
- Wave 2 commit `6cd7341` on `main`, pushed; tree clean after it. A docs-only commit (this finalization plus evidence records) will sit on top of it before the session starts.
- Decisions `docs/agent-runs/phase-15/decisions.md`: P15-26 (fallback if A fails: b1, local export from run 6's Parquet), P15-29, P15-30 (OD-1, OD-7, OD-8, OD-10 accepted; OD-3 ratified: retained <= 400 MiB and peak <= 800 MiB on A's export -> 2048 MiB, otherwise or on `OutOfMemoryError` -> 4096 MiB), P15-31 (no repository write during the session).
- Run 6: slug `run-v1-192de0fd-1442-4508-9065-09c44775643b`, Spark `sourceRunId` `run:v1:9f6e6fb0-2ec3-4148-85fc-f5ba7c56ad05`; its export has only the 11 old tables.

## Items
1. Replace every `REPLACE_WITH_WAVE2_COMMIT_SHA` with the full 40-hex SHA of `6cd7341` (read it with read-only git). Make the HEAD gate accept the docs-only commit(s) on top: `6cd7341` must be an ancestor of HEAD and `git diff --name-only 6cd7341 HEAD` must list only paths under `docs/`. Fail closed otherwise.
2. Step 0.0 and section 4: replace "human to ratify" wording for OD-3 with the ratified rule (P15-30); mark OD-1, OD-7, OD-8 acknowledged per P15-30 (the human still types the acknowledgement line at 0.0 if the runbook asks for one).
3. Write the b1 fallback section (P15-26): when it triggers (A fails, or A not finished by its limit), what happens to B (B still runs if time and budget allow; state the rule), and the full procedure to produce the 17-table export from run 6's Parquet. Rules:
   - Use only tools committed at `6cd7341`. If any step needs a tool, option or upload path that does not exist, do not invent one: write the gap into the section as `BLOCKED-ON <owner>: <what is missing>` with file evidence, and say what the session does instead (conservative: stop after B, no Wave 2 session-2 sizing from b1).
   - S3 reads are read-only downloads into a new directory under `/Users/winnguyen/scholarchain-data/` created inside `if mkdir`. Any S3 write is a human-only paste-ready command with its own expected result, and only if an existing reviewed script performs it.
   - State which slug and `sourceRunId` go into `/Users/winnguyen/scholarchain-data/session2.tfvars` under b1, and how they are read (not hard-coded unless they are run 6's recorded values).
   - State b1's cost, wall time and stop conditions, and its effect on HIGH (3.90) and the T0 limits.
   - Heap measure (section 4) under b1 runs on the b1 export.
4. Change the status line from DRAFT to FINAL, pending only the billed total at 0.1. List every remaining placeholder at the top (expected: billed total only; the optional cluster id in the watch command may stay).
5. Conventions unchanged: `!` commands, `AWS_PROFILE=scholarchain`, `set -o pipefail`, tee to `/Users/winnguyen/scholarchain-data/logs/`, `| tail -n 60; echo "exit: $?"`, braced variables, us-east-2 only, no account ID, no spend-limit or advanced-features advice.

## Report
Changed line ranges per item; the b1 procedure summary; every `BLOCKED-ON`; remaining placeholders; what is unverified. Write the report to `docs/agent-runs/phase-15/session-ab/runbook-final-report.md` is NOT your path: return it to the orchestrator.
