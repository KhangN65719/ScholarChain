# Report 06: test-engineer, harness case fix (condensed by the orchestrator; author: test-engineer)

STATUS: COMPLETE (edited, not run).

Defect: tests/scripts/run.sh line 1419 held two `exp_refuse` calls on one physical line with no newline, so the closing quote of the first fragment joined the next word (`...got: us-east-1exp_refuse`) and the second call became ignored arguments. Effects: `8.refuse.region-us-east-1` could never match, and `8.refuse.unknown-experiment-id` was never created.

Edit: a newline between the two calls; fragment is exactly `manifest REGION must be us-east-2, got: us-east-1`. Still `a_refused` with fixed-string grep on stderr; nothing deleted or loosened. Neighbouring cases correct. All 2227 lines read; no other glued-text case.

Orchestrator correction to the report's expected count: the last run executed 681 cases (pass=680, fail=1). The restored unknown-experiment-id case brings it to 682, so the expected result is `pass=682 fail=0`, not 681/0 as the report says. The restored case has never run; if it fails, the script message differs from `unknown EXPERIMENT_ID` and that goes to the script owner rather than loosening the case.
