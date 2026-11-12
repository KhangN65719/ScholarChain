# Report 10: test-engineer, repair 1 (as received, condensed)

STATUS: COMPLETE for the shellcheck repairs (edited, not executed). Nothing run (guard, D11).

File modified: tests/scripts/run.sh.
1. :517 SC2054: pipeline branch of `build()` now sets `stop_on="first-failure,dlq-nonempty"` (local) and passes `--stop-on "$stop_on"`; argument value unchanged.
2. :545 `D_RC`: nothing read it; assignment and initializer removed.
3. :558, :561, :562 `SC_SCRIPT`, `SC_APPROVAL_FILE`, `SC_ENV`: read by the sourced lib.sh (`script_digest`, `consumed_dir`, `approval_value`, `consume_approval`); each assignment has a `# shellcheck disable=SC2034` line above with the reason.
4. :944 and :1232: `tr '[:lower:]' '[:upper:]'`.
5. Whole file re-read for other flaggable constructs: none found (a read, not a lint run).

Case `5.cleanup-destroy.destroy-without-stateful-ack`: no edit. Agrees with report 09: the case asserts `APPROVAL_VERIFIED` is NOT printed (`a_refused`); the script printed it (lib.sh:511) before refusing at cleanup.sh:195 and :345-346. Script order was wrong; fixed by infra-engineer in repair 1.

Cases `8.committed-manifest.A` to `F`: the harness `--print-digest` call uses the same argument shape as case 2 (no record in digest mode). The digest call exited 1 in `validate_manifest` (run-experiment.sh:205-211) because the manifests name entry points absent from the temp repo, which tracks only `experiments/harness/run.sh`; `MD_S` came back empty and the dry run then failed at lib.sh:447 with "empty SCRIPT_DIGEST". Agent offered two readings: script should tolerate an untracked entry point in `--print-digest` (infra-engineer made this change in repair 1), or the fixture should commit stub entry scripts. Same cause as report 09. Cases unedited.

Open: a real-repo `--print-digest` for a committed manifest also needs the tolerance, since the entry points do not exist until Phase 15.

Human check pending: `bash -n`, `shellcheck -x`, `bash tests/scripts/run.sh`. Expected if both reports are right: shellcheck clean on the harness and 546 pass, 0 fail. Not run: `sbt quick`, `sbt check`.
