# Readiness repair: test-engineer report (received 2026-11-17; summary as received)

`tests/scripts/run.sh` edited; not executed (shell and grep refused).
- `run.sh` does not glob `experiments/pipeline-inputs/`; new cases read the template read-only from `$REAL_ROOT`; no `setup_repo` change.
- Six cases (before the loader-only static block): `15.pipeline-inputs.template.has-exactly-the-loader-only-keys-and-the-graph-only-command`; `...print-digest-accepted-with-the-s-graph-only-flags-and-binds-the-input-file` (rc 0, digests, `input=<sha256>` in `ARGS_CANONICAL`); `...dry-run-prints-input-mode-loader-only-and-loader-graph-only-yes` (`INPUT_MODE=loader-only`, one `LOADER_GRAPH_ONLY=yes (` line, `PROJECTED_SPEND_USD=1.62`, loader `OUTPUT_PREFIX`, `DATASET_SNAPSHOT=n/a`); `...with-an-extra-snapshot-key-is-refused.{real,dry,digest}` (`unknown input key(s) for mode loader-only: snapshot`, no stub called).
- Finding: `--print-digest` exits in `maybe_print_digest` (`lib.sh` ~377) before `INPUT_MODE`/`LOADER_GRAPH_ONLY`; those are asserted under `--dry-run`. No production change.
- Expected: last human `RESULT pass` + 6. Orchestrator: last observed 1883 (morning batch) -> expected `RESULT pass=1889 fail=0`.
No existing case changed.
