# Report 03: infra-engineer, dry-run for the existing scripts (as received, condensed)

STATUS: COMPLETE for the edits. Nothing executed: no script parsed, linted or run (guard, D11). Every check pending the human.

Files modified: scripts/lib.sh (all shared dry-run, wrapper and timeout work); plan.sh, deploy.sh, rollback.sh, smoke-test.sh, run-bounded-pipeline.sh, cleanup.sh (each calls `dry_announce` and has a dry-run branch); scripts/README.md (new "Dry run", "Exit codes", "Timeouts and retries"; `--dry-run` row; pending-checks paragraph). SCRIPT_DIGEST of every script changed; no approval record exists yet.

## Design
- `lib.sh` defines shell functions `aws`, `terraform`, `docker`, `curl`, `sbt` that call `dry_guard` first; in a dry run it dies with `EVIDENCE: DRY_RUN_GUARD`. `consume_approval`, `make_out_dir`, `write_meta` carry the same guard. Each script exits via `dry_finish` before its first real call. `require_cmd` uses `type -P`.
- Markers: `DRY_RUN=yes` first; then `TARGET_ACCOUNT`, `TARGET_REGION`, `TARGET_ENVIRONMENT`, `IDENTITY_CHECK=skipped`, `SCRIPT_DIGEST`, `ARGS_DIGEST`; `STEP[n] name: dry-run` with real-run step names.
- Approval gated scripts run the full record verification in dry run, never consume it (`APPROVAL_NOT_CONSUMED dry-run`); missing plan or contexts print `CHECK_SKIPPED` / `APPROVAL_CHECK_SKIPPED` and `APPROVAL_SKIPPED_FIELDS=`; a real run refuses the skip sentinel.
- plan.sh dry run prints terraform commands as text, exits 0.
- cleanup.sh: human-only refusals apply in dry run; listing from Terraform source labelled `SOURCE=terraform-source (not live)`.
- Timeouts: every `aws` call has connect 10 s and read 60 s timeouts; only describe/list/get/head get 3 attempts, the rest once; docker login/build/push bounded 120/1800/1800 s through `run_bounded`; curl already bounded; terraform only `-lock-timeout=60s`.

## Deviation
Exit codes: README table lists 0, 1, 124, 130, other. The order's 2/3/4 split is NOT implemented, because every refusal exits 1 through `die` and renumbering would change real paths.

## Risks (agent)
- `run_bounded` never executed: backgrounds docker, relies on `"$@" <&0 &` keeping stdin for `docker login --password-stdin`; Ctrl-C forwarding unverified. One human `docker login` test needed before any deploy.
- A failing `describe-execution` in the pipeline monitor loop under `set -e` leaves execution and cluster running until the Terraform timeout (unchanged real behavior).
- Dry run refuses on a dirty tree, as a real run does.
- cleanup listing cannot be tested by an agent (terminal/agent-env guard); awk blocks untested; `aws_secretsmanager_secret` in `identity` unread.
- shellcheck may report SC2329 on wrapper functions; `aws` retry rule matches by operation prefix.
- Observed, unchanged: cleanup.sh `tf show` output not scanned for key material; smoke-test prints `RESPONSE_HEAD` (300 bytes).

Human checks pending: `bash -n` on 7 files; `shellcheck -x scripts/*.sh`; tripwire run with logging stubs for aws/terraform/docker/curl/sbt (empty log, exit 0); refusal cases (expired record, mismatched account, consumed record, cleanup without `--i-am-the-human`); a real `docker login --password-stdin` through the wrapper.
