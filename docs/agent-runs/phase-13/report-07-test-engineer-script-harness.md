# Report 07: test-engineer, tests/scripts harness (as received, condensed)

STATUS: COMPLETE for authoring. NOT executed (guard refuses agent bash, D11). Pass 0, fail 0, skipped 0, none observed. Roughly 550 `CASE` lines expected.

Files created: tests/scripts/run.sh, tests/scripts/README.md. No other file touched. tests/ADVERSARIAL_COVERAGE.md left alone (Phase 11 Scala matrix); coverage is listed in tests/scripts/README.md.

Design: works in a fresh mktemp temp git repo (scripts copied, one commit, so the clean-tree gate passes); PATH holds logging stubs for aws, terraform, docker, curl, sbt, plus a `java` stub, all exit 1 and log to a tripwire that must stay empty across dry runs; own `SCHOLARCHAIN_OPS_STATE_DIR` outside the repo; plan files and `.meta` are hand-written fakes (the harness hashes them itself; SCRIPT_DIGEST and ARGS_DIGEST always come from `--print-digest`); gather uses a second PATH without terraform and java stubs.

Cases: 0 safety controls (stubs first on PATH, shellcheck hidden, tripwire empty over the whole run, real marker directory never written, real repo `git status` unchanged); 1 `--help` x10; 2 `--print-digest`; 3 dry runs; 4 approval verified/not consumed/reusable and `CHECK_SKIPPED` markers; 5 refusals for deploy, rollback image and state, smoke-test, pipeline, cleanup flip and destroy, experiment, archive upload (including exactly-24-hour boundary accepted, smoke-test out-dir symlink, `//` and case checks); 6 consumed record (marker written with `consume_approval` from lib.sh, control passes with a fresh marker dir, nothing SKIPPED); 7 cleanup guards; 8 run-experiment refusals and the six committed manifests; 9 archive-evidence; 10 check.sh; 11 package.sh; 12 secrets scan.

Observations (not failing cases):
- cleanup.sh requires `--acknowledge-data-loss` only for `apply-destroy`; `plan-destroy` does not, though the order said "the destroy modes". Only `apply-destroy` is tested. Human to confirm whether `plan-destroy` should require it.
- The gather's unguarded `TF_V="$(terraform version)"` aborts the whole gather under `set -e` if terraform is installed but exits nonzero.
- Cases `5.smoke.out-dir-*` are the first test of the smoke-test physical-path check.

Assumptions and risks: case 9 "destination exists" pre-creates evidence directories for 11 UTC seconds and fails if the script starts more than 8 seconds late; failure reasons assert message fragments from the current script text, so rewording shows as a FAIL; an expected-message slip could give a false FAIL; temp repo uses stand-ins for Dockerfile, project/build.properties, a synthetic `.claude/` and `.mcp.json`.

Human command pending: `bash tests/scripts/run.sh` (optionally `bash -n tests/scripts/run.sh` first). FAILs against scripts go to infra-engineer, never edited by the test-engineer.
