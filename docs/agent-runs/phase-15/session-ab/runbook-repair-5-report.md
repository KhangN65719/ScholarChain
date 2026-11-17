# Runbook repair 5, after runbook review 4 (deploy-engineer, received 2026-11-17; summary as received)

1. P15-33 absolute: :1073 failed A-up apply -> step 5 and stop, no override window; :1043 (B1.6) no in-session placement; :1067 no stash-pop delay; :1023 "stop and report"; :1076/:1077 a 2.9 or 2.10 failure is an A failure, no B, step 5.
2. :632 (2.11): gate requires 2.8b, 2.9 and 2.10 PASS before B; "next billable item is the B switch apply" on the success path; A-failure sentence follows (no B, no override, no b1).
3. Section 4 when A fails, stated in the Step 4 intro, :1075, :44 run order and the 5.5 precondition: 4.1-4.3 skipped, 4096 MiB stands (P15-29, P15-30), 4.4 skipped, 5.4 then 5.5 still run.
4. `human-commands.md` copy removed from :3, 0.3 preconditions and open item :1106; precondition (c) renumbered (b); no command text changed.
Beyond the list: 2.11 had said "2.9, 2.10 may still be running ... while B runs" (contradicts ruling 1), now requires both PASS before B; 2.10 expected line (:628) "B is not affected" replaced with P15-33 wording. Line numbers +0 to +3. Nothing run.
