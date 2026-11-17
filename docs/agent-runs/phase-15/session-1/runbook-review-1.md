# Session 1 runbook review 1, code-reviewer (Opus, fresh, read-only; received 2026-11-17)

Recorded by the orchestrator with attribution. **Verdict: REQUEST CHANGES.** Reviewed `docs/releases/phase-15/session-1-runbook.md` against the scripts, tfvars, manifests and decisions.

## Blocker
- **B1** Session not pinned to 085b5ec's Terraform: step 0.2 (line 105) says "commit or resolve first"; open item 1 (line 709) suggests committing Wave 2 or a second checkout. No backend block exists in `infra/terraform` and `.gitignore:7-9` ignores `*.tfstate`, so state is local to the main checkout (a fresh checkout would plan to recreate everything); `package.sh:146-156` refuses a detached HEAD; step 2.0.1 (line 210) expects Wave 2 in HEAD. Fix: hard check in 0.2 (clean tree and `git diff --quiet 085b5ec HEAD -- infra/terraform scripts experiments modules build.sbt project datasets`, only docs may differ) plus a named human procedure.

## Major
- **M1** Repository writes during the session (orchestrator evidence, the untracked runbook) dirty the tree; `deploy.sh:105-106` then refuses, including session-down at 4.3R. Rule: no repository writes and no agent sbt until 4.4.
- **M2** Stops from 2.4R on (2.4R, 2.5, 2.7, 3.2, 3.5, 3.7) say only "report"; endpoints bill about 0.12 USD/h. Each must say: go to step 4 unless the human decides otherwise within a stated time.
- **M3** `upload-inputs.sh:113-115` overwrites `raw/phase5-dblp-subset/` (versioning off). Add a read-only comparison of the four `WOULD_UPLOAD ... sha256=` lines (2.0.2 log) against `datasets/tier-s/snapshot-s.json` (dblp `9076e857...`, openalex `fdeddfa3...`, crossref `c403ccbf...`, orcid `719a1cdb...`) before 2.0.3W; any difference stops.
- **M4** Old jars stay on the classpath: the upload never deletes; the node copies all of `jars/<m>/` (`run-s-pipeline.sh:132`); `lib_jars_csv` takes every `lib/*.jar` (`lib-emr.sh:877-888`); a renamed main jar trips `main_jar` (`lib-emr.sh:903-911`). Add a read-only `aws s3 ls --recursive` of `jars/{hadoop,entity-resolution,spark}/` compared with local `modules/*/target/job`; extra keys stop; removal needs a human-approved path.
- **M5** Expected `TimeoutSeconds` list wrong (lines 77, 305, 478): the definition has 8 values (`workflow/main.tf:116,147,173,209,228,239,281,296`): A 7200, 1440, 300, 4800, 4800, 900, 600, 900; B the same with 4200 for both step states.
- **M6** Line 385 suggests console or `aws emr terminate-job-flows` for a left-over cluster: console mutation is prohibited; replace with one exact CLI line (cluster id from `emr list-clusters --active` filtered to `scholarchain-course-pipeline`), recorded as a human emergency action.
- **M7** Post-teardown check 4.4 misses Elastic IPs, load balancers, unattached EBS volumes, Neptune instances and ECS service desired counts (gap admitted at line 666).

## Minor
- **m1** Account ID reaches the transcript via `tail` (`ARGS_CANONICAL ... account=`, `APPROVAL_VERIFIED ... account=`, `IDENTITY_OK account=`; `lib.sh:368,548,773`) and bucket names in upload logs; redact with `sed` before `tail` or have the human accept it.
- **m2** `{ ...; } | tail; echo "exit: $?"` in 1.6A, 1.6B, 2.6D, 3.6D always prints `exit: 0`; say so or restructure.
- **m3** zsh noclobber prints "file exists", not the bash message at line 230.
- **m4** Resolvable UNVERIFIED items: snapshot id `phase5-dblp-subset` (`snapshot-s.json:2`, `docs/releases/phase-14/runs-s-6-to-11.md:27`); A `IdleTimeout` 900 (`course.tfvars:31`).
- **m5** HIGH 3.45 arithmetic and P15-10 (billed <= 10.55; 3.94 gives 7.39) are right, but omit up to about 300 s of cluster billing after B's 88-minute stop (about 0.12 USD, P15-20) and idle endpoint time beyond 2.3 h; add a session wall-clock limit.

## Verified correct
All flags exist; `ARGS_CANONICAL` strings match `run-experiment.sh:249`, `run-bounded-pipeline.sh:252`; approval keys match `lib.sh:393`; each record's ceiling matches its flag; A limits 75/0.75 with pipeline 70/0.75/0.60; B 90/2.40 with pipeline 88/2.30/1.55; order plan, read, digest, record, dry run, run; session-down last, no size overlay; Region forced us-east-2 (`lib.sh:209`); ComputeLimits match `emr/main.tf:82-88`; the 1.5 `sed` edits touch only `KEY=` lines.

## Human fill-ins
`BILLED_USD`; B1 decision; `--spark-dir`, `--max-objects`, `--max-bytes`; `APPROVED_BY` (optional); `REPLACE_WITH_CLUSTER_ID`; `A_DIR`/`B_DIR` if several; confirm A ceiling 0.75; experiment owner confirms `b-burst` profile values.
