# Work order 57: infra-engineer, one comment in lib-emr.sh (found by test-engineer in work order 56)

Owner: infra-engineer (scripts/**). Edit `scripts/emr/lib-emr.sh` only, comment text only, no behavior change. No AWS, no commit. The guard blocks agent grep, find, bash -n and shellcheck.

Evidence: report of work order 56 (test-engineer): `scripts/emr/lib-emr.sh` lines about 529 to 530 say the exit status "stays 124 when the limit was reached", but with `timeout -k 30` a command that ignores TERM and needs the KILL exits 137, as `scripts/README.md` already states (a coreutils manual claim, not observed on the node). Make the comment precise: 124 when the command ended on the TERM at the limit, 137 when it needed the KILL after the 30 s grace; neither was observed on a node except 124 not at all (S run 4 exited cleanly on TERM). Do not change any code line, any marker, any needle that tests/scripts/run.sh matches (the harness has no case on this sentence; if you find one, report BLOCKED-ON test-engineer instead of editing).

Report contract: STATUS, OWNER, WORK ORDER, FILES MODIFIED, COMMANDS RUN, VALIDATION RESULTS, RISKS, NEXT GATE. Final message must be the complete report (short).
