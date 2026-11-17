# A + B runbook revision 2, deploy-engineer (received 2026-11-17; author: deploy-engineer subagent; summary as received)

Status: COMPLETE (draft revision 2; nothing executed). Only `docs/releases/phase-15/session-ab-runbook-draft.md` written.
1. MAJOR-1: `PLAN_A_UP_CHECK`, `PLAN_B_SWITCH_CHECK`, `PLAN_DOWN_CHECK` test `grep -A1 -x 'IAM_CHANGES:' LOG | tail -n 1 | grep -qx '  none'`; display with `-A1`; wording :439, :645, :860 and verdict paragraphs :447, :653, :868.
2. Found by the agent: `plan_summary` re-prints `will be created` lines after `PLAN_SUMMARY_BEGIN`, so plan A's 8 endpoints would count as 16; all three checks now strip from `PLAN_SUMMARY_BEGIN` (`sed '/^PLAN_SUMMARY_BEGIN/,$d'`) before the action grep. `Plan:` line, `-no-color`, meta glob `course-*.tfplan.meta` (`plan.sh:97,122`) match.
3. MINOR-3: b1 branch run 6 slug `run-v1-192de0fd-...`, `sourceRunId` `run:v1:9f6e6fb0-2ec3-4148-85fc-f5ba7c56ad05`.
4. 4.2: HEAP-MEASURE layout from `ExportHeapMeasureSuite.scala:125-133`; 4.2b parses `rows=` and requires non-zero numeric counts for the six SP-2 tables, `HEAP_MEASURE_ROWS_CHECK=PASS|FAIL` (replaces `HEAP_MEASURE_LINE_PRESENT`; whole-file search for the old name not possible).
Risks: table names in 4.2b vs manifest `t.table` values UNVERIFIED (fails safe); all new shell unrun.
