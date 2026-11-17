# Runbook review 4 verdict, narrow re-review of repair 4 (code-reviewer; received 2026-11-17; summary as received)

**REQUEST CHANGES.** 0 BLOCKER, 0 MAJOR; MINOR wording conflicts with P15-33, no command changes.
1. 0.1 gate PASS (:179 `BILLED_USD=5.33`, `LAG_USD=1.15`; fails closed; total > 14 exits 3; conventions hold; :182 arithmetic correct).
2. P15-33 wording, MINOR:
   - :1073 "then step 5 unless the human decides otherwise within 15 minutes" conflicts with "stops there" (:1108 says no decision to take).
   - :1043 (B1.6) "after 5.4, before 5.5, 4.4 afterwards" puts a B1 step in the session; leftovers :1067 ("delays the stash pop of 5.5"), :1023 ("the session stays on the conservative path").
   - :985/:987 (2.9 or 2.10 failure is an A failure, no B) vs :1076/:1077 ("B is not affected"): contradiction.
   - :632 "The next billable item is the B switch apply" sits after the A-failure branch; move to the success path.
   - Section 4 when A fails is only implied (:816 precondition, :1075); :44 run order and "stops there" leave 4.4 and 5.5 open. State: 4.1-4.3 skipped, 4096 MiB stands, 5.4 then 5.5 still run.
3. Placeholders PASS (only `REPLACE_WITH_CLUSTER_ID`, :743, :749, :758, :1100).
4. `session-ab/human-commands.md`: no command depends on it; :3, :196 precondition (b), :1106 expect an orchestrator copy before 0.4; 0.4 `git add docs/releases/phase-15 docs/agent-runs/phase-15` (:211) commits it if present; a copy after 0.6 makes 0.7 fail (tree not clean).
