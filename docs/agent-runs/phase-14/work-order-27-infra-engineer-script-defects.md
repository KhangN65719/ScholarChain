# Work order 27: infra-engineer, four script defects found by test-engineer (work order 24)

Owner: infra-engineer (scripts/**, infra/terraform/**). Edits in your paths only. No AWS, no commit, no terraform apply. You cannot run bash or shellcheck (guard D11); state that nothing was executed. Keep every existing check; do not loosen any harness case (tests/** is test-engineer's; their expectations are the spec).

Evidence: test-engineer's report for work order 24 (summarized here; the cases are in tests/scripts/run.sh by name).
1. scripts/run-bounded-pipeline.sh, `INPUT_SNAPSHOTS=`: `awk -F= '$1 == "SNAPSHOTS" { print $2; exit }'` cuts the spec at the first `=`, so `dblp=a,openalex=b` is printed and recorded as `dblp`. Case `14.pipeline.pipeline-mode.snapshots-spec-printed-whole`. Fix: print everything after the first `=` (for example `sub(/^[^=]*=/, "")`).
2. scripts/run-bounded-pipeline.sh id patterns: `^[A-Za-z0-9._-]{1,64}$` accepts `.` and `..` (giving `curated/runs/../`), and the Python `$` matches before a final newline, so `"run1\n"` passes validation and the node refuses it only after the cluster is up. Cases `14.pipeline.input.run-id-dot-dot`, `snapshot-dot-dot`, `run-id-trailing-newline`, `snapshot-trailing-newline`, `snapshots-trailing-newline`. Fix: `re.fullmatch` and refuse `.` and `..`; apply the same to `validate_id` in scripts/emr/lib-emr.sh (it accepts `.` and `..` too).
3. scripts/upload-inputs.sh `--snapshot`: the same pattern accepts `..`. Case `14.upload.snapshot-dot-dot`. Fix as above (also refuse a trailing newline).
4. scripts/emr/lib-emr.sh line 33: `SC_SECRET_RE` holds the literal text `aws_secret_access_key`; upload-inputs.sh runs `scan_for_secrets` on every scripts/emr/*.sh and would refuse this file, so the bootstrap upload cannot complete. Case `14.upload.real-emr-file.lib-emr.sh.passes-the-upload-secret-scan`. Fix: split the literal so the scanner does not match (as the harness does with `aws_secret_""access_key`), without weakening the scanner or the pattern's meaning at run time.
5. Also: the run-bounded-pipeline.sh header comment still says the input has no dataset snapshot field; update it to the new contract.
6. Check the `upload` action line exists in the scripts/README.md approval-record table (test-engineer could not check); add it if missing.

List the files for the human's `bash -n` and `shellcheck -x` (scripts/*.sh and scripts/emr/*.sh) and the harness re-run, and the cases you expect to flip from fail to pass.

Report contract: STATUS, OWNER, WORK ORDER, FILES MODIFIED, COMMANDS RUN, VALIDATION RESULTS (none executed), RISKS, NEXT GATE. Your final message must be the complete report text.
