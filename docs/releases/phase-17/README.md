# Phase 17 release manifest

Author: deploy-engineer (work order WO-F4, plan item B9), 2026-11-20. Records only. No build, package, deploy, plan, AWS call or sbt command was run to write this file; every value was read from a repository file. Account ids are masked as `000000000000` or not written. Citations are `file:line` relative to the repository root. A value with no citation is marked PENDING (with its owner) or NOT RECORDED.

Terms: PENDING means a step not yet done and owned by the named role. NOT RECORDED means no file read holds the value.

## 1. Release scope

- This is the Phase 17 documentation and final-gate release: documentation, evidence archive, test-harness and guard changes, and the final review. It deploys nothing.
- No live AWS session ran in Phase 17 (`docs/agent-runs/phase-17/decisions.md:38` P17-33; `:42` P17-37). There is no Phase 17 deploy, smoke or rollback record, and none is claimed.
- Terraform, scripts, images and datasets were not changed to ship this release. The deployed state last recorded is the Phase 15 teardown (section 3).
- P16-34 note. `infra/terraform/environments/course/services-smoke.tfvars:26` sets `slow_deadline_millis = 60000`. The comment at `:24-25` gives the reason (the Bedrock call deadline is 40 s, the code default slow deadline is 10 s, and 60 s sits under the 120 s load balancer idle timeout). The variable accepts 0 or 10000 to 120000 (`infra/terraform/environments/course/variables.tf:497`). The human chose to reuse this file for the optional live session with no Terraform change (`decisions.md:30`, P17-25). That session was dropped (`decisions.md:38`, P17-33). The value was not deployed in Phase 17, and no plan or apply used it. Whether any earlier session deployed line 26 is not established by a direct record. The Phase 15 `tn-s3` record says the A3 apply removed the slow-deadline variable from the `query` task definition (`docs/releases/phase-15/deploy-tn-s3-a3.md:32`), which implies the earlier A2 and `tn-s2b` applies carried it. That statement is a runbook statement and is UNVERIFIED in that record, because its author did not read the plan's task-definition diff. It stays UNVERIFIED here.

## 2. Commits

| Item | Value | Source |
|---|---|---|
| Code commit | `264f24b`. The human's gate `sbt clean check` ran on a fresh clone at this commit. | `docs/agent-runs/phase-17/decisions.md:44` (P17-39); `docs/agent-runs/phase-17/human-commands.md:67` |
| Changes after `264f24b` | Documentation only, per the human's statement. The uncommitted documentation changes and `docs/agent-runs/phase-17/`, including this record at `docs/releases/phase-17/`, are committed in the final documentation commit (PENDING, human, P17-6). No file list is given here. | `decisions.md:44` (P17-39); `decisions.md:12` (P17-6) |
| Final documentation commit | PENDING (human). Phase 17 `commit.txt` names it; a docs-only follow-up adds it. | `decisions.md:12` (P17-6) |
| Release tag | PENDING (human). The tag goes on the follow-up commit. | `decisions.md:12` (P17-6) |

## 3. Container image digests

These are the last deployed images. Phase 16 had no new deploy (`experiments/EVALUATION.md:931`, citing P16-34), and Phase 17 had none. They were built from the Phase 15 tag `38421c6f986ddd09cc4c1e5ac309a530f760f2a9`, not from `264f24b`, so they do not contain the Phase 16 and Phase 17 code changes.

| Phase | Service image | Tag | Digest | Source |
|---|---|---|---|---|
| 15 (last deploy, `tn-s2b`; reused unrebuilt in `tn-s3` and present in `tn-s4`) | services | `38421c6f986ddd09cc4c1e5ac309a530f760f2a9` | `sha256:32358714c35aab3520f8e3ac974a5e1ee9520da06e72868a34273d0ac7fd9ed3` | `docs/releases/phase-15/deploy-tn-s2b-a2b.md:31`; reuse `deploy-tn-s3-a3.md:27`; present `deploy-tn-s4-down.md:43` |
| 15 (same) | loader | same tag | `sha256:c38bb57aab322fc725363817fe9ca5a705927bc0dfedcd7708d012cc846c2e8b` | `deploy-tn-s2b-a2b.md:31` |
| 15 (same; built and pushed by hand, outside `deploy.sh`) | load-client | same tag | `sha256:ec96e9b6cc743f4125152c876a1c7aa313f8fc32083763d2f98ee1409e998e57`, base image digest `sha256:935c3c5b12357a7e10caa9c1e6ca5692344d97cf229832ef584be2cec9fc03df` | `deploy-tn-s2b-a2b.md:32` |
| 14 (last Phase 14 deploy, session 15; superseded by Phase 15) | services | `b456cd3fddeb337ebd9c1e81d0d599bbc487aa1e` | `sha256:6bded15f96ac5e860961530cf39a7f72584b42960d385ebe3752ba34ad9521e0` | `docs/releases/phase-14/deploy-s-session-15.md:25` |
| 14 (same) | loader | same tag | `sha256:c027725857ddc09c77287edbeca679f0c1fa3c5e268fb1ff9385f33ce659855d` | `deploy-s-session-15.md:25` |

- Phase 15 `tn-s2b` rebuilt the services and loader images at the new tag. The earlier builds at `32e401a0` have different manifest digests (prefixes `e1236153...` and `b6839af3...`; full values not recorded in the file read) although the build context digest is equal. The cause is UNVERIFIED in the record (`deploy-tn-s2b-a2b.md:31`).
- Tags are immutable, so images at earlier tags stay in the registry (`deploy-tn-s2b-a2b.md:50`).
- Registry state after the Phase 15 teardown: ECR repositories and images remain by design (`deploy-tn-s4-down.md:53`). No registry listing was made in Phase 17.
- Phase 14 deploy digests for sessions 12 to 14 are in `docs/releases/phase-14/deploy-s-session-12.md`, `-13.md` and `-14.md` (not re-read for this manifest).
- Deployed environment at the last apply: course, region us-east-2, account `000000000000`, cost bound 20 USD (`deploy-tn-s4-down.md:10-11,17`).

## 4. Terraform

Phase 17 results, relayed by the human on 2026-11-20 and recorded by the orchestrator (`docs/agent-runs/phase-17/human-commands.md:67-72`). Run in the fresh clone `/tmp/sc-gate` at `264f24b`:

| Check | Result | Source |
|---|---|---|
| `terraform fmt -check -recursive` over `infra/terraform` | exit 0, no output | `human-commands.md:68` |
| `terraform init -backend=false`, then `terraform validate` in `environments/course` (the only root module) | `Success! The configuration is valid.` | `human-commands.md:65,69` |
| `git status --short` in the clone after the checks | empty | `human-commands.md:70` |
| Terraform and provider versions printed by `init` | NOT RECORDED (not relayed) | `human-commands.md:69` |

Version pins and requirements read from the files:

| Item | Value | Source |
|---|---|---|
| Terraform required version | `>= 1.10.0, < 2.0.0` | `infra/terraform/environments/course/versions.tf:2` |
| Provider `hashicorp/aws` | `= 6.26.0` | `versions.tf:6-7` |
| Provider in the lock file | version `6.26.0`, constraint `6.26.0` (hash entries at `.terraform.lock.hcl:8-23`) | `infra/terraform/environments/course/.terraform.lock.hcl:4-6` |
| Terraform CLI on the human's laptop | `Terraform v1.17.1`, reported 2026-11-19, a different day and machine state than the 2026-11-20 gate clone, so it is not the version `init` printed | `human-commands.md:107` |

Plan digest:
- Phase 17 plan checksum: NOT RECORDED. No plan ran in Phase 17.
- Earlier plan digests, for reference only, none from Phase 17:
  - Phase 14, session 15 (`plan-15`): `a5d43b8fb6c8fb37ee5c6d8cef1e616a0e980bbec0d3b1fab0fa801d0f08da35` (`docs/releases/phase-14/deploy-s-session-15.md:13`).
  - Phase 15 `tn-s2b`: `98a134131f4e9a87400f1a41a192ed0e2a7569b107e61d5e2df7f4430ccd0f43` (`docs/releases/phase-15/deploy-tn-s2b-a2b.md:15`).
  - Phase 15 `tn-s3`: `a24588eb796ac877d7d8c8369e2dce33c170340107a32007dfb336d32389c092` (`deploy-tn-s3-a3.md:15`).
  - Phase 15 `tn-s4`, the last apply (teardown, `STATEFUL_CHANGE=yes`): `aefd76b868e96291a3bf43e21ad3c42179a09dc0a6283b87001b3a1c70c92f24` (`deploy-tn-s4-down.md:15`).
- Deploy script digest at the last apply: `961ce19b54e5bdfef3c5297e7998f0590b9abbe2a86c9c0bc4e453192820d504`; args digest `9586ec2175b8069f01173abd20806554ffe3d4a7cbbd288029e3a423cb2ddfe2` (`deploy-tn-s4-down.md:13,16`).
- The repository also holds Terraform state files in the course environment directory. They were not read for this manifest.

## 5. Datasets

Source: `datasets/dataset-manifest.yaml` (`manifest_version` 1, line 2). The file is JSON. Hashes are copied in full unless marked truncated. A truncated value ends in `...`; the full value is at the cited line.

| Dataset | Snapshot version | Licence (SPDX) | Checksums | Source lines |
|---|---|---|---|---|
| `dblp` | 2026-11-01 | CC0-1.0 | `dblp-2026-11-01.xml.gz` md5 `18ad5db59592406ec74289975c4b44cb` (publisher-reported); `dblp-2023-06-28.dtd` md5 `96e4283e7188...` (publisher-reported; full value at `:37`) | `:7-40` (snapshot `:16`, licence `:11`, md5 `:30`, `:37`) |
| `openalex` | 2026-10-14 | CC0-1.0 | None published for the complete snapshot (status `not-published`) | `:76-103` (snapshot `:87`, licence `:82`, checksum status `:99`) |
| `orcid` | 2026-10-13-v1 | CC0-1.0 | 12 publisher-reported md5 values, one per archive (`ORCID_2026_10_activities_0` to `_9`, `_X`, and `ORCID_2026_10_summaries`) at `:164-248`. Size estimate is marked UNCONFIRMED (`:159`). | `:144-249` (snapshot `:155`, licence `:150`) |
| `crossref` | 2026-03 | NOASSERTION (no single licence covers the distribution; the manifest states this is a determination, `:294`) | Torrent info dictionary sha1 `b5ee0e102689b3e67023dd024694c0f5f124646f` (publisher-reported); no whole-file digest published (`:315-320`). Size and record counts are marked UNCONFIRMED (`:302`). | `:287-321` (snapshot `:298`, licence `:293`, sha1 `:312`) |

ORCID md5 values (`datasets/dataset-manifest.yaml`, lines as listed):

| Archive | md5 | Line |
|---|---|---|
| `ORCID_2026_10_activities_0.tar.gz` | `b4408eaed823afbd42d6a800b01c821c` | 169 |
| `ORCID_2026_10_activities_1.tar.gz` | `17fd95ff1cd557902e219e6e5e1c3f9c` | 176 |
| `ORCID_2026_10_activities_2.tar.gz` | `21c7be85e944d8a0a9580e29c617cdaa` | 183 |
| `ORCID_2026_10_activities_3.tar.gz` | `c350088e3653d51ff7f39467bf338bb1` | 190 |
| `ORCID_2026_10_activities_4.tar.gz` | `483d5a5c63bc5ec8f8bf88d0d92e41bd` | 197 |
| `ORCID_2026_10_activities_5.tar.gz` | `99ebd6b33c5d6ed39cbd62fae7550c89` | 204 |
| `ORCID_2026_10_activities_6.tar.gz` | `87a4b05d567436110b01b916a8c119fa` | 211 |
| `ORCID_2026_10_activities_7.tar.gz` | `378338721f5d913cb231a0fee0bb114e` | 218 |
| `ORCID_2026_10_activities_8.tar.gz` | `3d14bdda9e5ebb8c94b0701c99c9bb7f` | 225 |
| `ORCID_2026_10_activities_9.tar.gz` | `54f6739ed5de832fc32b79df6a39daf6` | 232 |
| `ORCID_2026_10_activities_X.tar.gz` | `ba665d7d3471ac873d1d7b4ed93d3a1f` | 239 |
| `ORCID_2026_10_summaries.tar.gz` | `a55a16fe0b292bda03cc6016546c8984` | 246 |

Bounded samples (checked-in fixtures; sha256 of each file):

| Dataset | Sample path | Rows | sha256 | Line |
|---|---|---|---|---|
| `dblp` | `samples/dblp.xml` | 2 | `a33f55bcd067d9b8a3c2b82a862d87e92c907dfe79c1d75a3cb4e09b18625548` | 61 |
| `openalex` | `samples/openalex.jsonl` | 2 | `d8e765e845abbef946d4433e07468e7f572ca2ca3360fe66bca8c9d471394b07` | 127 |
| `orcid` | `samples/orcid.jsonl` | 2 | `f4ac89ec1bc315939119a648b3e28a1d85e72bf110277b8eb0c85244267be2d3` | 270 |
| `crossref` | `samples/crossref.jsonl` | 2 | `b601c0dfa3d4594f9ef2...` (full value at line 343) | 343 |

Phase 5 bounded subset `phase5-dblp-subset` (`datasets/dataset-manifest.yaml:362-539`), retrieval date 2026-11-07 (`:376`):

| Item | Value | Line |
|---|---|---|
| Spec sha256 | `3731c8f92db22adbd5b433531a81b44e598ccceb211768e6f293ff72a1778692` | 366 |
| Receipt sha256 | `1216b8aa5ed996eed3cfd13c3a1401ed368c3c4baf22feb765260c4a4d5a1e62` | 370 |
| Enrichment receipt sha256 | `6d0b8d7b1d88a2b08facf922a5f1d915fd891b5263601dd3a5ffc2fd35ea2c60` | 374 |
| `subset/dblp-subset.xml` (3472 rows, 2111266 bytes) | `9076e8574643fa6f64619aa912956ed0ddef60f8d0634ae4be218b0edce28c7d` | 419 |
| `subset/openalex.jsonl` (396 rows) | `fdeddfa389f0f9af861dff9d7c89b8e91e3d7b2cda96111504bf6ed8f8a062a9` | 431 |
| `subset/crossref.jsonl` (251 rows) | `c403ccbfe67ad475b3788a44cae0cc292c60ef05a4f6d02b8249b25a662cd368` | 443 |
| `subset/orcid.jsonl` (100 rows) | `719a1cdb41069fe520bd2ab37aef5d253483d38c22e517cc714d369d91de35c5` | 455 |
| Subset licences | dblp CC0-1.0, openalex CC0-1.0, crossref NOASSERTION, orcid CC0-1.0 | 463-466 |
| DBLP dump used for the subset | release 2026-11-05, sha256 `356634eef5696aef7ba4d07d864257ddcacae55c95f64a755570801df0a82fa1`, checked only against an operator-supplied value | 388, 401-402 |

Stated mismatch, from the manifest: the DBLP dump behind the subset is release 2026-11-05, not the registered snapshot 2026-11-01 (`registered_snapshot_match` is `false`, `:387`; limitation text at `:532`). The DBLP dataset entry was left unchanged.

Snapshot labels carried by the Phase 14 run 6 input record: dblp 2026-11-05, openalex 2026-11-07, crossref 2026-11-07, orcid 2026-11-07 (`docs/releases/phase-14/runs-s-6-to-11.md:27`). These differ from the registered snapshot versions above, and the records do not reconcile them. Reconciliation is NOT RECORDED.

## 6. Run ids

No pipeline, load or experiment run was started in Phase 17.

Pipeline (Phase 14), tier S:
- Pipeline run id used by runs 6 to 11: `run-v1-192de0fd-1442-4508-9065-09c44775643b` (`docs/releases/phase-14/runs-s-6-to-11.md:7`).
- Execution names: `run-20261115T192111Z-large` (run 6, FAILED, `:26`), `run-20261116T024211Z-large` (run 7, FAILED, `:36`), `run-20261116T083333Z-large` (run 8, FAILED, `:45`), `run-20261116T171501Z-small` (run 9, FAILED, `:54`), `run-20261116T201535Z-small` (run 10, FAILED, `:61`), `run-20261116T213222Z-small` (run 11, SUCCEEDED, `:69`).
- The graph loaded in run 11 is named for the Spark run `run:v1:9f6e6fb0-2ec3-4148-85fc-f5ba7c56ad05`, not for the pipeline run id (`runs-s-6-to-11.md:87`). `RUN_ID_MATCH=false` in runs 9 to 11 is expected there. No single run completed EMR, vector ingest and graph load together (`docs/releases/phase-14/README.md:37`).

Phase 15 (session 2, 2026-11-17 to 2026-11-18; `docs/releases/phase-15/README.md`):
- B (afternoon, B-only session): `exp-B-20261117T185928Z-5c69ba90` (`:20`). Release record for it was not written (`:20`).
- Reload (graph-only): SUCCEEDED, `NEPTUNE_LOAD=LOADED vertices=32225 edges=135788` (`:27`). Run id NOT RECORDED in the README; the approval ledger lists it by args digest only (`:45`).
- D, query load: `exp-D-20261118T002157Z-38421c6f` COMPLETED (`:29`).
- E: `exp-E-20261118T012347Z-38421c6f` COMPLETED, mixed provenance (`:30`).
- F (first): `exp-F-20261118T011431Z-38421c6f`, invalid as a recovery result, kept (`:31`).
- F2: `exp-F-20261118T013740Z-38421c6f` COMPLETED, the F result (`:32`).
- C: `exp-C-20261118T014553Z-38421c6f` COMPLETED (`:33`).
- Smoke checks SMOKE1 (FAIL), SMOKE2 (FAIL) and STUB1 (PASS): run ids NOT RECORDED in the README (`:28`); the detail is in `docs/releases/phase-15/smoke-tn.md` (not read).

Phase 16 (frozen runs, `experiments/EVALUATION.md`):
- `graphchecks-a-export-2` (`:19`); source export run `run:v1:3cfb57b1-abd1-4913-8e53-da477d8d6027` (`:19`).
- `factorycounts-1` (`:93`).
- Retrieval G: `exp-G-20261119T053608Z-2e8943cf`; answers H: `exp-H-20261119T053926Z-2e8943cf` (`:958`).
- `h-audit-2` (`:884`, `:958`); diagnostics `h4-diagnostic-1` to `h4-diagnostic-4` (`:958`).
- `er-replay-v3-r3-1` and `erextras-1` (`:883`, `:958`).
- No new deploy in Phase 16 (`:931`).

## 7. Prompt versions

Source: `modules/rag/src/main/scala/scholarchain/rag/Model.scala` (lines checked in this session).

| Prompt | Id | Version | Output schema | Lines |
|---|---|---|---|---|
| `Planner` | `planner-classify` | `1.0.0` | `scholarchain.rag.plan-output/1` | 65-67, schema id at 97 |
| `Synthesis` | `synthesis` | `1.2.0` | `scholarchain.rag.synthesis-output/3` | 76-78, schema id at 98 |

The verifier report schema id is `scholarchain.rag.verifier-report/1` (`Model.scala:99`). The prompt hash is the sha256 of the prompt text (`Model.scala:62`); the hash value was not computed in this session and is NOT RECORDED here (each Phase 16 run manifest records it, `experiments/h-answers/README.md:74`).

## 8. Model versions

| Role | Identifier | Source |
|---|---|---|
| Answer model, Claude Haiku 4.5, US inference profile | `us.anthropic.claude-haiku-4-5-20251001-v1:0` | `infra/terraform/environments/course/variables.tf:212` (default); the Phase 16 experiment commands pass the same id, `experiments/h-answers/README.md:31,43,57` |
| Foundation model behind the profile | `anthropic.claude-haiku-4-5-20251001-v1:0`, marked UNVERIFIED in the variable description | `variables.tf:216,218` |
| Embedding model, Titan Text Embeddings V2 | `amazon.titan-embed-text-v2:0`, 256 dimensions with normalization in the AWS profile | `variables.tf:230`; `modules/rag/src/main/scala/scholarchain/rag/EmbeddingModels.scala:48-55`; vector dimension default 256 at `variables.tf:238` |
| Local fallback embedder | `hash-embed-v1`, 384 dimensions | `EmbeddingModels.scala:32-33` |
| Offline answer model | stub (default `answer_model = "stub"`) | `variables.tf:467` |

No Bedrock, Titan or S3 Vectors call was made in Phase 17 (`decisions.md:42`, P17-37). The Phase 16 Haiku and Titan calls are archived in `experiments/EVALUATION.md` (section 6).

## 9. Tests

| Check | Result | Source |
|---|---|---|
| Human gate `sbt clean check`, fresh clone of `264f24b` at `/tmp/sc-gate` | Exit 0. 9 groups, all Failed 0 and Errors 0, in the order relayed: 49, 331, 389, 473, 501 (1 skipped), 170, 260, 99, 1427. `[success] Total time: 1360 s`. Log kept outside the repository. A first attempt failed 3 Hadoop tests with `OutOfMemoryError` while a second sbt ran at the same time; the same code passed when run alone. | `docs/agent-runs/phase-17/decisions.md:43-44` (P17-38, P17-39) |
| Agent `sbt quick` (test-engineer, WO-F2) | `[success] Total time: 158 s`. Only the last two group totals were read: 77 (Failed 0, Errors 0) and 1306 (Failed 0, Errors 0). The exit code was not observed. This is agent-side confirmation, not the gate. | `docs/agent-runs/phase-17/final-gate/report-F2-test-engineer.md:22-28` |
| `bash tests/scripts/hooks-guard.sh` (human, `/tmp/sc-gate`, `264f24b`) | `RESULT pass=114 fail=0` | `human-commands.md:71` |
| `bash tests/scripts/run.sh` (human, `/tmp/sc-gate`, `264f24b`, 2026-11-20) | Exit 0. `RESULT pass=1949 fail=0`, `SKIPPED_CASES 0`, `real-repository-untouched PASS`. Log kept outside the repository. The earlier full pass was the same count on `2151c01`, 2026-11-19. | `human-commands.md:72`; earlier pass `:82` |

## 10. Artifact checksums

- Build artifact checksums (jars, packages, archive bundles): NOT RECORDED in Phase 17. `scripts/package.sh` was not replayed in this phase.
- The release records of Phases 14 and 15 reference package records (`package-7` to `package-10`, `docs/releases/phase-14/README.md:11-14`) and a deploy script digest, but the package checksums themselves are kept in the operator data directory outside the repository and are NOT RECORDED in the files read.
- Checksums that are recorded: container image digests (section 3), plan and script digests (section 4), and dataset and fixture hashes (section 5).

## 11. Cleanup

- A full cleanup is planned for mid-December 2026, after grades are released (`docs/agent-runs/phase-17/decisions.md:13`, P17-7). Cleanup is a separate human-approved operation; deploy-engineer does not run it.
- Resources left in place after the Phase 15 teardown, by design: S3 buckets and objects, DynamoDB tables, the S3 Vectors bucket and indexes, ECR repositories and images, CloudWatch log groups, queue messages, and the Secrets Manager secrets with values written by hand (`docs/releases/phase-15/deploy-tn-s4-down.md:53`). The same class of items is listed for Phase 14 (`docs/releases/phase-14/README.md:66`).
- A resource inventory at submission is NOT RECORDED. It exists only if the read-only describe and list inventory of demo item 22 is run (`decisions.md:42`, P17-37); no result of it is recorded here.
- The post-teardown read after Phase 15 was reduced and found no billable session resource (`deploy-tn-s4-down.md:45`). No newer read exists.

## 12. Billing readings

Account-level, month-to-date readings taken by the human from Billing. They are subject to Billing lag. They are not per-phase bills.

| Reading | Value | Source |
|---|---|---|
| 2026-11-19, 10:59 | 10.39 USD month-to-date, unchanged since 07:41, nothing running | `docs/agent-runs/phase-17/decisions.md:22` (P17-17) |
| 2026-11-20, 12:58 | 10.44 USD month-to-date, spend limit 20.00, status "Approaching limit", nothing running. The forecast shown was 298.10, which the human reads as an extrapolation. | `decisions.md:38` (P17-33) |

- Per-phase split of spend (Phases 15 to 17): NOT DERIVED. Two account-level readings do not give it.
- Final reading at submission: PENDING (human, H-10).

## Open fields

| Field | Status | Owner |
|---|---|---|
| Final documentation commit hash | PENDING | human (P17-6) |
| Release tag | PENDING | human (P17-6) |
| `tests/scripts/run.sh` result for `264f24b` | RECORDED: exit 0, 1949 pass, 0 fail (section 9) | none |
| Terraform and provider versions printed by `init` in the gate clone | NOT RECORDED | none; not relayed |
| Phase 17 plan checksum | NOT RECORDED (no plan ran) | none |
| Prompt text hashes | NOT RECORDED here | none; in Phase 16 run manifests |
| Per-group totals for the agent `sbt quick` beyond 77 and 1306, and its exit code | NOT RECORDED | none; gate is P17-39 |
| Build artifact and package checksums | NOT RECORDED | none; `package.sh` not replayed |
| Run id of the Phase 15 reload; run ids of SMOKE1, SMOKE2, STUB1 | NOT RECORDED in the Phase 15 README | none |
| Reconciliation of Phase 14 run-6 snapshot labels with the registered snapshot versions | NOT RECORDED | dataset-engineer, if wanted |
| Resource inventory at submission | NOT RECORDED unless demo item 22 is run | human |
| Billing readings (account level, month-to-date) | RECORDED: 10.39 USD on 2026-11-19, 10.44 USD on 2026-11-20 (section 12) | none |
| Per-phase split of spend for Phases 15 to 17 | NOT DERIVED | human (Billing) |
| Final billing reading at submission | PENDING | human (H-10) |
