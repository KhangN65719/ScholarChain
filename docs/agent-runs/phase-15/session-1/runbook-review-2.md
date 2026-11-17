# Session 1 runbook re-review (review 2), code-reviewer (Opus, fresh, read-only; received 2026-11-17)

Recorded by the orchestrator with attribution. **Verdict: APPROVE.** B1, M1-M7, m1-m5 RESOLVED (evidence per item in the reviewer's message: HEAD stays `main` at 085b5ec plus a docs-only commit; `stash -u` leaves ignored tfstate and `.terraform/` in place; no step between 0.2c and 4.5 writes into the repository; timeouts match `workflow/main.tf` and the overlays; `terminate-clusters` is a real verb with a `j-` guard; HIGH 0.75 + 2.40 + 0.40 + 0.13 = 3.68; P15-10 limit 10.32).

Check 7: `scripts/emr/spark-yarn.conf` lines 92-100 unchanged from 085b5ec; `SHUFFLE_PARTITIONS=8` and `SPARK_EVENT_LOG=false` match P15-16; the PROPOSED marker (lines 80-84) still covers `PARTITION_COUNT 16`, `SPLIT_MAX_BYTES 131072`, `MAP/REDUCE 3072`.

## Minor notes (optional)
1. 2.0.2c (line 291): a failing `aws s3 ls` inside `<(...)` gives an empty remote list and a false `NO_STALE_JARS=yes`.
2. Same line: `comm` without `LC_ALL=C` while both `sort` calls use it.
3. E1 terminate (line 45): `| [0]` terminates the first cluster even if two exist; add a length check.
4. 2.0.2c, 2.0.5, 2.8b, 3.8b, 4.4 not passed through the redaction `sed`.
5. Gate 0.2c (line 155): path list omits `Dockerfile`, `.gitignore`, `.scalafmt.conf`, `compose.yaml`; `BRANCH` printed but not checked.
6. Step 3 (line 510) says profile confirmation "before 3.4W" but also requires it before 0.2a.

## Risks
- `.claude/settings.local.json` exists and is not in `.gitignore`; if tracked, a persistent permission answer during the session dirties the tree and blocks session-down. Check `git check-ignore -v` and `git ls-files` before 0.2a; accept no persistent permission prompts during the session.
- The stash rewrites sources, so the stop-hook test gate will fire; S1 forbids sbt, so the repeat stop is let through.
- `course.tfvars` line 31 was read from the working tree (Wave 2 modified it); `git diff 085b5ec -- infra/terraform/environments/course/course.tfvars` confirms beforehand.
- Optional: copy `terraform.tfstate*` to `scholarchain-data/` before 0.2b.

Orchestrator action: notes 1-6 sent to deploy-engineer as a small fix; the orchestrator checks the changed lines before handing the runbook to the human.
