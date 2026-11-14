# Work order 06: test-engineer, fix one harness case

Owner: test-engineer (tests/**). Same limits as work order 03: local only, no edits outside tests/**, no commit. No sbt (no Scala changes).

Evidence: docs/agent-runs/phase-14/checks-2.md. The human's harness run gave `pass=680 fail=1`. The failing case:

`8.refuse.region-us-east-1 FAIL stderr lacks: manifest REGION must be us-east-2, got: us-east-1exp_refuse [rc=1 err1=EVIDENCE: manifest REGION must be us-east-2, got: us-east-1]`

The script output is correct; the expected fragment has the text `exp_refuse` glued onto the end, so the match cannot succeed. Find the case in tests/scripts/run.sh (read the file), fix the fragment or the argument split so it matches exactly `manifest REGION must be us-east-2, got: us-east-1`, and check the neighboring cases (`8.refuse.region-not-us-east-2`, `8.refuse.region-argument-*`) for the same slip. Do not weaken the assertion, do not delete the case. If any other case has the same kind of glued-text defect, fix it and list it.

Report: what the defect was, the exact lines changed, why the assertion is not weaker, and the expected result (681 cases, pass=681 fail=0). Say plainly that you did not run it; the human runs it.

Report contract: STATUS, OWNER, WORK ORDER, FILES MODIFIED, COMMANDS RUN, VALIDATION RESULTS, ASSUMPTIONS, RISKS, NEXT GATE.
