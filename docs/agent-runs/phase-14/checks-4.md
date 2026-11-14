# Checks 4: human-observed results, 2026-11-13/14 (recorded by the orchestrator from pasted output)

Account ID deliberately omitted (D1: no account ID in committed evidence). Full logs are in /Users/winnguyen/scholarchain-data/logs/.

## Static checks (phase14-static-checks-2.log)
- terraform fmt -check -recursive: pass. terraform validate (course): "Success! The configuration is valid."
- bash -n: pass on scripts/*.sh, tests/scripts/run.sh, tests/scripts/run-bounded-real.sh.
- shellcheck -x: clean on scripts/*.sh and tests/scripts/run.sh. tests/scripts/run-bounded-real.sh: SC2034 warning (CAT unused, line 40), info SC1091, SC2013 x3, SC2016 x2. No behavior defect. Cleanup queued for test-engineer (repair batch).

## Repair batch (work orders 09 and 10), human re-checks
- WO 09 (test-engineer): shellcheck cleanup of run-bounded-real.sh (CAT removed, three `while read` loops, SC1090/SC1091/SC2016 directives). After one follow-up (SC1090 directive), `bash -n` and `shellcheck -x` print nothing, exit 0 (phase14-static-checks-3.log). Real run again `RESULT pass=15 fail=0`, exit 0 (phase14-run-bounded-real-2.log).
- WO 10 (infra-engineer): no edit needed in the files it read; see decision-09-grace-period.md. The human grep over scripts, infra/terraform and phase-13 docs was requested and is not yet observed.

## run-bounded with real processes (phase14-run-bounded-real-1.log)
- RESULT pass=15 fail=0, exit 0. The report predicted 16; the script has 15 cases (report count was wrong). Covers S1, process-group cleanup, 124/STOPPED_WALL_CLOCK mapping.
- Not covered: Ctrl-C/TERM forwarding (S2, S3), setsid escapes.

## Real docker build (phase14-package-dryrun.log, phase14-package-real-1.log, phase14-package-verify-1.log)
- Dry run exit 0. Real `scripts/package.sh --platform linux/amd64 --allow-dirty`: PACKAGE_COMPLETE, tag f065a33371cdc9f2ba781c02527f7a7fc6e2a8c2-dirty. Docker Desktop 29.9.2, 18 CPUs, disk 586Gi free.
- Images: scholarchain-services sha256:3dd967d24a823a971da09fb4f0cb4c3f0005f7c63ce430ef332820cc2905d930, scholarchain-loader sha256:9a70548f0b49205154d4dc1f7ef5d3e0626c3cbed4a1ee49f1d1dd8c2bb31bcf, both linux/amd64 (docker image inspect). Local only, not pushed. Build duration not captured on screen (in the log).
- Default out-dir under $TMPDIR was used (not --out-dir).
- A -dirty image is never usable by deploy.sh.

## Real docker login --password-stdin (phase14-docker-login-*.log)
- `aws ecr get-login-password --region us-east-2 --profile scholarchain | docker login --password-stdin <registry>`: "Login Succeeded", exit 0. Proves a token was issued; does not prove push rights.
- Step 6: `docker logout <registry>` printed "Removing login credentials for <registry>" (stored token removed). Credential-helper list not pasted (optional).
