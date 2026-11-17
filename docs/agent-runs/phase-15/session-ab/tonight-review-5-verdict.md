# Tonight runbook review 5, narrow re-review of revision 4 repairs (code-reviewer, received 2026-11-17; summary as received)

**APPROVE.** All six review-4 items fixed, no new defect.
- M1 :390 `EXP` 19/6/3 (matches :408, :412). M2 :14 D3 line as the record; :54, :56, :64 citation; `D3_TYPED=no` default refuses (:58, :69); H.6 ledger (:622). m3 :518. m4 :112 one command, mask after `tee`, `POST_TEARDOWN_CLEAN` verdict, DLQ `^0[[:space:]]+0$`, EMR/SFN/EC2 empty with errors captured, Gateway s3/dynamodb only; mid-session read by section (:115). m5 :352. m6 :583, :587, :592; `B_DIR` in register (:649).
- zsh safety and masking pass; failed STS falls back to `NOACCOUNT`; `pipefail` carries the subshell `false`.
Non-blocking: the gate cannot check `LAG_USD` equals the typed value (human, :58); :14 "in cents" could read as 337 (fails safe: STOP); suggest "in USD, two decimals".
Pending: register items (:649) until run; D1-D8.
