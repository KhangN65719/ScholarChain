# Work order 07: test-engineer, tests/scripts harness for the ten scripts

Owner: test-engineer. Local only. No AWS, no credentials, no terraform plan/apply, no real docker or sbt. Do not edit scripts/** (infra-engineer owns it), production code, CLAUDE.md, docs/agents.md or `.claude/**`. Do not weaken or delete existing tests. Do not commit. Do not run sbt in this order.

Read first: CLAUDE.md, docs/agents.md, docs/agent-runs/phase-13/{prompt,decisions}.md (D5, D6, D8, D13), reports 03, 04 and 06 in the same directory, scripts/README.md (dry run, exit codes, approval record, manifest contract), and every script under scripts/ including lib.sh (read only).

## Deliver under tests/scripts/ (you own it)

A plain bash harness (no new tools; bash, git, python3 already required). Entry: `tests/scripts/run.sh`, run as `bash tests/scripts/run.sh`. It prints one line per case `CASE <id> PASS|FAIL <reason>` and a final `RESULT pass=N fail=M`, exits 0 only if fail=0. It must be safe to run on the real repository and on a laptop with no AWS credentials and no network:
- It works in a fresh `mktemp -d` directory: it copies `scripts/` and the files the scripts need into a temporary git repository with one commit (so the clean-tree gate is satisfied) and runs the copy. It never modifies the real repository, never writes under `$HOME`, and removes only the temp directory it made (check the path is under the temp root before removing).
- It puts logging stubs for `aws`, `terraform`, `docker`, `curl` and `sbt` first on `PATH`. Each stub appends its name and arguments to a tripwire log and exits 1. For every dry-run case it asserts the tripwire log is empty. (Scripts also define shell functions of the same names; the log proves no real binary was reached either way, and any stub call in a dry run is a FAIL.)
- It sets `SCHOLARCHAIN_OPS_STATE_DIR` to its own temp directory outside the temp repo and outside `--out-dir`, and removes `SCHOLARCHAIN_AGENT_RUN` unless a case sets it.
- Approval records and manifests it needs are written into the temp directory with dates computed at run time. Use the account ID `123456789012`. Digests come from the scripts' own `--print-digest`, never recomputed by hand.

## Cases (each is an assertion on exit code and a greppable marker, not only "ran")

1. `--help` exits 0 for all ten scripts.
2. `--print-digest` prints SCRIPT_DIGEST and ARGS_DIGEST for the scripts that support it, exits 0, and calls no stub.
3. Dry run, valid arguments, each script that takes one (plan; deploy; rollback; smoke-test; run-bounded-pipeline; cleanup; check; package; run-experiment; archive-evidence): exit 0, `DRY_RUN=yes`, `TARGET_ACCOUNT=123456789012`, `TARGET_REGION=us-east-1`, `TARGET_ENVIRONMENT=course` where the script has them, at least one `STEP[` line ending `dry-run`, empty tripwire log.
4. Approval-gated scripts in dry run: valid record -> `APPROVAL_VERIFIED` and `APPROVAL_NOT_CONSUMED`, and the record is still usable (the second dry run also succeeds, and no consumed marker exists). Where a dry run legitimately skips a check (plan file, contexts), assert the `CHECK_SKIPPED` or `APPROVAL_CHECK_SKIPPED` marker is printed and no pass is claimed for it.
5. Refusals (nonzero exit and an `EVIDENCE:` line on stderr), for at least deploy, rollback, run-bounded-pipeline, cleanup and run-experiment: missing `--approval-record`; nonexistent record; expired record; record whose account differs from `--account-id`; record whose action differs; record with `EXPIRES_AT` more than 24 hours after `APPROVED_AT`; extra or missing keys; account ID not 12 digits; region not us-east-1; cost ceiling 0, negative, non-numeric and above 25; record cost ceiling different from the argument.
6. Consumed record: a real-run path cannot be exercised, so use the library function or the marker directory the README documents to pre-create the consumed marker for a record, then assert a dry run refuses it as already consumed. If this cannot be done without calling the script's internals, say so in the report and mark the case SKIPPED with the reason, never PASS.
7. cleanup.sh: refused without `--i-am-the-human`; refused with `SCHOLARCHAIN_AGENT_RUN` set; refused for any environment but `course`; refused without `--acknowledge-data-loss` in the destroy modes; a dry run prints `SOURCE=terraform-source (not live)` and performs no deletion (tripwire empty); the refusal comes before any resource listing (assert the listing marker is absent on a refused run).
8. run-experiment.sh: manifest with MAX_MINUTES, MAX_CONCURRENCY and MAX_TASKS each one above the tier cap, region other than us-east-1, unknown experiment id, output root inside the repository, relative output root, a stop condition `dlq-nonempty`, a missing key, a duplicate key, spaces in a value, an entry point outside experiments/ and load-tests/, a dirty tree, each refused; the six committed manifests under experiments/manifests/ pass `MANIFEST_VALID` and `LIMITS` in a dry run (copy them into the temp repo).
9. archive-evidence.sh: dry run lists what would be gathered; `--include` of a file named `.env`, `x.pem`, `x.key`, `terraform.tfstate`, `my-credentials.txt` is refused naming the file; a destination directory that already exists is refused; an upload without `--approval-record` is refused and nothing is uploaded (tripwire empty); gather output contains MANIFEST.sha256 of what it copied (use the non-dry-run local gather only, which touches no cloud; a gather into the temp out-dir is allowed).
10. check.sh: `--dry-run` prints the six gate names and runs nothing; `--skip-sbt` appears in the summary as skipped and the summary never says PASS for sbt; with a stub-less PATH that hides shellcheck, the shellcheck gate fails unless `--allow-missing-shellcheck`.
11. package.sh: dirty tree refused without `--allow-dirty`; dry run shows the platform and the tag derived from HEAD; no docker or sbt stub call.
12. Secrets: no case output (stdout or stderr, all cases combined) may contain a string that looks like key material or the contents of a test approval record's `APPROVED_BY` beyond the named fields; assert with a pattern scan like the scripts' own.

If a case reveals a defect in a script, do NOT change the script and do not loosen the case: leave the case failing, report it as a finding for infra-engineer with the exact output (`BLOCKED-ON infra-engineer`).

## Execution

The Phase 1 guard blocks agent `bash` and `shellcheck` (D11). Write the harness carefully, check it by reading, and run `bash -n tests/scripts/run.sh` only if the guard allows it. If you cannot run it, say so; the human runs `bash tests/scripts/run.sh` and the result is pending until then. Never report a case as passing without having seen its output. Also add `tests/scripts/README.md`: what it covers, what it cannot cover (no live AWS, no real `docker login`, `run_bounded` not exercised with a real daemon), the single command, and the expected output format. Update `tests/ADVERSARIAL_COVERAGE.md` only if it is the right place to list this coverage (it is under tests/**).

Before writing, list which case proves each item of your primary gate.

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS (exact pass and fail counts or "not run"), VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.
