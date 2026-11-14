# Script harness (tests/scripts)

Owner: test-engineer. A plain bash harness for the ten operational scripts under `scripts/` (`plan`, `deploy`, `rollback`, `smoke-test`, `run-bounded-pipeline`, `cleanup`, `check`, `package`, `run-experiment`, `archive-evidence`). It needs only bash, git and python3, which the scripts already need. No new tool.

Status: the Phase 1 guard refuses an agent `bash -n`, `shellcheck` and `bash tests/scripts/run.sh` (decision D11), so an agent writes cases by reading and never sees them run; only the human's run counts.

- Observed (human runs 4 and 5, `docs/agent-runs/phase-13/checks-4.md` and `checks-5.md`, 2026-11-11, after repair round 2): `bash -n` and `shellcheck -x` clean, and `RESULT pass=570 fail=0`, tripwire empty over the whole run. The earlier 546 figure is the pre-repair-round-1 result in `checks-3.md`.
- Covered by that 570-case run (dry-run and refusal paths only), the work order 17 cases: stateful-plan cases for deploy and rollback image mode (`5.deploy.stateful-plan-unacknowledged`, `4.approval.deploy.stateful-plan-acknowledged`, and the two `rollback-image` equivalents); the `PASS_WITH_SKIPS` assertions in `10.skip-sbt.summary-never-passes-sbt`; `.smoke-auth.123` refusal by name and reason, and the `Authorization: Bearer`, `ghp_` and `github_pat_` content refusals plus an ordinary-text control (`9.*`); the untracked-entry-point real-run refusal and its `--print-digest` and `--dry-run` counterparts (`8.real-run.*`, `8.untracked-entry-point.*`); the new `WOULD_RUN` process-group text (`8.dry-run.would-run-names-the-process-group`); the smoke-test steps and `--read-secret-arn` cases (`3.dry-run.smoke.*`, `2.print-digest.smoke.*`, `4.approval.smoke.*`, `5.smoke.read-secret-arn-*`); and the pipeline input, limits and output markers (`3.dry-run.pipeline.*`). Not covered by any case: S1's repair (lib.sh `run_bounded` returning 124 whenever the timeout marker exists, even when the entry point exits 0). The dry-run harness cannot cover it (D8); real-process behavior is a Phase 14 check. No run exercises that repair until the human reruns the harness, and then only syntax, lint and the dry-run paths.

Phase 14 (us-east-2, work order 03): the approved Region in every fixture, plan metadata, manifest and approval record is us-east-2, and the cost ceiling is no longer a literal: the harness reads `SC_PROJECT_CEILING_USD` from the copied `lib.sh` (`CEIL`) and derives the values around it. Not observed: no run of these edits exists until the human runs the harness; the 570 above is the last observed count and predates them. The six committed manifests under `experiments/manifests/` carry `REGION=us-east-1`, so `8.committed-manifest.A` to `.F` fail until their owner changes them; the harness does not loosen those cases.

Real-process check of `run_bounded` (Phase 14 work order 07, Phase 13 required item S1): `tests/scripts/run-bounded-real.sh` is a separate file, so the stubbed harness above never depends on real sleeps. It sources the real `scripts/lib.sh` and calls the real `run_bounded` with local processes only (`sh`, `sleep`, `cat`, `true`, `false`), in a temp directory under `$TMPDIR`, with limits of 1 to 30 seconds and a total runtime of about 30 to 40 seconds. It covers a clean exit, a nonzero exit, a limit exceeded, the wall-clock cutoff (124, marker present, final state `STOPPED_WALL_CLOCK`, never `COMPLETED`), the S1 path (the command exits 0 after the marker exists, the result must still be 124), a child that ignores SIGTERM, a grandchild left behind, and a check that no recorded process remains. The human runs it with `!`, from any directory:

```
bash tests/scripts/run-bounded-real.sh
```

It prints `CASE <name> PASS|FAIL <reason>` lines and `RESULT pass=N fail=M`, and exits 0 only when `fail=0`. Not observed: an agent cannot run it (decision D11), so no result exists until the human runs it, together with `bash -n` and `shellcheck -x` on the file. Ctrl-C and TERM forwarding to the group (S2, S3) are not covered.

## The one command

```
bash tests/scripts/run.sh
```

Run it from any directory. It exits 0 only when `fail=0`. Optional: `SC_HARNESS_KEEP=1` keeps the temp directory (its path is printed on stderr) and `SC_HARNESS_RUN_TIMEOUT=N` changes the per-run limit (default 120 s). A run takes a few minutes: about 650 script invocations.

## Output format

One line per case, then two summary lines:

```
CASE 4.approval.deploy.verified PASS ok
CASE 5.deploy.expired FAIL stderr lacks: approval expired [rc=1 err1=EVIDENCE: ...]
CASE 9.upload.unlisted-file-refused SKIPPED <reason>
SKIPPED_CASES 0
RESULT pass=N fail=M
```

A FAIL reason lists every assertion that failed and the exit code and first stderr line of the last run in the case. Setup problems print `PREREQ ...` on stderr and exit 2 before any case.

## How it stays safe

- Everything runs in `mktemp -d` under `$TMPDIR`. `scripts/` and the files the scripts read (Terraform `*.tf`, `*.tfvars`, the lock file, the six experiment manifests) are copied into a temporary git repository with one commit, so the clean-tree gate holds. The real repository is read, never written; case `0.real-repository-untouched` compares `git status --porcelain` before and after. The temp directory is removed at exit only if its path is under the temp root and has the harness name.
- `PATH` is the temp `bin` (stubs) then a temp `tools` directory of one-line wrappers for the tools the scripts need. `aws`, `terraform`, `docker`, `curl`, `sbt` and `java` are stubs that append their name and arguments to a tripwire file and exit 1; `shellcheck` and every real cloud binary are absent. Every dry-run and refusal case asserts the tripwire is empty, and `0.tripwire-never-reached-in-any-case` asserts it stayed empty over the whole run. `0.tripwire-armed` proves the stub logs.
- `SCHOLARCHAIN_OPS_STATE_DIR` is a temp directory outside the temp repository and outside every `--out-dir`. `SCHOLARCHAIN_AGENT_RUN`, `CLAUDECODE`, `CLAUDE_CODE_ENTRYPOINT` and the AWS credential variables are unset, `TMPDIR` points into the temp directory, and git ignores the user's global config.
- Approval records and manifests are written at run time with dates computed from the clock, account `123456789012`. `SCRIPT_DIGEST` and `ARGS_DIGEST` come from each script's own `--print-digest`. The one digest the harness computes itself is the sha256 of its fake plan files (a real plan file needs terraform), which `plan.sh` would print as `PLAN_DIGEST`.

## What it covers (work order 07 case numbers)

| Case | What | Evidence in the output |
|---|---|---|
| 0 | harness controls: stubs first on PATH, shellcheck hidden, tripwire armed, temp repo clean, marker directory never written, real repo untouched | `0.*` |
| 1 | `--help` exits 0 for all ten scripts | `1.help.<script>` |
| 2 | `--print-digest` prints 64-hex `SCRIPT_DIGEST` and `ARGS_DIGEST`, exits 0, calls no stub; unsupported on `check` and `package`; deterministic; `ARGS_DIGEST` follows the arguments | `2.*` |
| 3 | dry run with valid arguments for all ten: exit 0, `DRY_RUN=yes` first, `TARGET_*`, `STEP[n] ...: dry-run`, `DRY_RUN_COMPLETE`, tripwire empty | `3.dry-run.*` |
| 4 | approval-gated dry runs (deploy, rollback image and state, smoke-test, pipeline, cleanup flip and destroy, experiment, archive upload): `APPROVAL_VERIFIED`, `APPROVAL_NOT_CONSUMED`, record reusable, no marker; skipped checks print `CHECK_SKIPPED` / `APPROVAL_CHECK_SKIPPED` and claim no pass | `4.*` |
| 5 | refusals (exit nonzero, `EVIDENCE:` on stderr, a message fragment, tripwire empty) for all nine approval-gated forms: missing, nonexistent, expired, other account, other action, window over 24 h (and exactly 24 h accepted), extra, missing, duplicate and empty keys, malformed line, wrong script, args, plan and git digests, wrong region, environment and cost in the record, agent-like approver, future approval, symlinked record, account not 12 digits, region not us-east-2 (eu-west-1, and per key us-east-1, us-west-2, us-east-2a and US-EAST-2 with the Phase 14 D1 note, also with `--print-digest`), a record REGION of us-east-1 or us-west-2 against `--region us-east-2`, cost 0, negative, non-numeric, exponent, above the project ceiling and just above it (the ceiling is read from `scripts/lib.sh`, see case 13), record cost different; dirty tree for deploy and rollback; state directory inside the repository, relative, with `..`, inside `--out-dir`; smoke `--out-dir` by symlink, `//`, letter case, relative, `..`; pipeline limits and input scan | `5.*` |
| 6 | consumed record: the marker is written with `consume_approval` from `lib.sh` (the library function, not the script's internals), the dry run then refuses it, and the same record passes with a fresh marker directory | `6.*` |
| 7 | cleanup: refused without `--i-am-the-human`, with `SCHOLARCHAIN_AGENT_RUN`, with an agent environment and no terminal, for another environment, `apply-destroy` without `--acknowledge-data-loss`, bad mode, missing plan file, bad tag; each refusal before any listing marker; dry run prints `SOURCE=terraform-source (not live)` and deletes nothing | `7.*` |
| 8 | run-experiment: 24 manifest and argument refusals, dirty tree, key material not echoed; the six committed manifests pass `MANIFEST_VALID`, `LIMITS` and `CHECK_SKIPPED entry-point` | `8.*` |
| 9 | archive-evidence: local gather with `MANIFEST.sha256` re-verified by the harness; nine refused names in dry and real mode; key material in an included file; existing destination; upload without a record, bad prefix, wrongly named or tampered directory | `9.*` |
| 10 | check: `--dry-run` lists six gates and runs nothing; `--skip-sbt` is `GATE sbt=SKIP` with `SBT_RAN=no` and ends `CHECK_RESULT=PASS_WITH_SKIPS`, never `CHECK_RESULT=PASS`; shellcheck absent fails the gate unless `--allow-missing-shellcheck` | `10.*` |
| 11 | package: dry run shows platform and the tag from HEAD; dirty tree refused in dry and real mode before any build; `--allow-dirty` tags `-dirty`; out directory rules | `11.*` |
| 13 | Phase 14 Region lock (decision D1), work order 03: `plan.sh` refuses us-east-1, us-west-2, us-east-2a and US-EAST-2 (dry run, `--print-digest`, and real runs of plan, deploy, smoke-test and run-experiment, none reaching a stub); `check_tfvars_region` through `plan.sh --extra-var-file` and called directly (a `region` other than us-east-2, last assignment wins, comments ignored, zones outside us-east-2 including a prefix trap, us-east-2a and b accepted, an empty file accepted, every committed var file accepted); the Terraform defaults read as plain text (`region`, the two zones, the `us.anthropic.claude-haiku-4-5-20251001-v1:0` Haiku profile) and the smoke-test defaults agreeing with them; `13.ceiling-pinned-to-D6` pins the project ceiling at 20 (Phase 14 D6) | `13.*` |
| 12 | no key-material pattern, planted secret or record comment in any output of any case; `APPROVED_BY` appears only in the `APPROVAL_VERIFIED` line | `12.*` |

## What it cannot cover

- No live AWS. Every cloud call is a stub, and a dry run never reaches one. The real run path of each script (identity check, `consume_approval` inside a script, terraform, ECR, Step Functions, S3 upload) is unexercised.
- No real `docker login`, `docker build` or `docker push`, and `run_bounded` is not run with a real daemon, a timeout or a signal.
- `sbt_run` is never reached (`check.sh --skip-sbt`, no real sbt). The sbt gate, the terraform gate and a present shellcheck are not run; `check.sh` runs `bash -n` over the copied scripts and a JSON parse over a synthetic `.claude/`.
- Plan files are fake (a text file and a hand-written `.meta`); no real plan was made, so `plan.sh` itself is only dry-run and digest tested.
- The gather runs with a PATH that has no `terraform` and no `java` stub, because the gather calls `terraform version` and `java -version` when they exist, and a stub exits 1. It still has stubs for `aws`, `docker`, `curl` and `sbt`, and the tripwire must stay empty.
- "Destination directory already exists" for the gather is tested by pre-creating the evidence directory for the eleven UTC seconds around the run, because the name holds a timestamp. A start more than eight seconds late would fail that one case.
- The temporary repository holds stand-ins for `Dockerfile` and `project/build.properties`. `experiments/manifests/*.manifest` are the real files. The tracked entry point is `experiments/harness/run.sh`, a stand-in.
- `case-changed` for the smoke `--out-dir` is meaningful on a case-insensitive volume (macOS); on a case-sensitive one it exercises the lower-casing in `path_within`, not the file system.
