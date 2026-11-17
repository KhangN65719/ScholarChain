# Tonight runbook review 4, revision 4 (code-reviewer, received 2026-11-17; summary as received)

**REQUEST CHANGES.** 2 MAJOR, 4 MINOR.
- M1 :392 reader R default `EXP="11 to add, 6 to change, 3 to destroy"` (B-state); A1 from DOWN is 19/6/3 (:410, :414); would fail closed on a correct plan.
- M2 :54-58, :14 gate cited as "P15-40, amended by P15-42"; P15-42 records only that B ran; P15-40 still says both HIGHs. Record the new formula as a human decision (D3 typed line or a new decision) and fix the citation.
- m3 :520 manifest key list `b d e f c` -> `d e f c`.
- m4 :110, :114 (P.1) no account mask, no verdict line, exit reflects only the last call; add `ACCOUNT_ID` + `sed` and a verdict or "any error text is a stop".
- m5 :354 (DC) hardcoded `phase15-tn-s1-deploy-digest.log`; use `${NAME}`.
- m6 :585, :589, :375 half-applied A1 drift has no named teardown after (b) removal; route to variant (a) var files with the human reading drift counts; P.2 `B_DIR` to the UNVERIFIED register.
Verified: arithmetic, cents logic, clock, H.1 table, D5/S.1 IAM, P.2/P.3 flags (`collect.sh:85-100`, `derive-capacity.py:793-797`), zsh paste safety; `SESSION2.md:101-129` consistent with `modules/network/main.tf:11,16`, `session-down.tfvars:18`, `environments/course/main.tf:33`, `experiment-size-s.tfvars:17`, `modules/neptune/main.tf:58-130`, `graph-load.tfvars:33`.
