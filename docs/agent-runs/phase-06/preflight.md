# Phase 6 preflight: input verification (orchestrator, 2026-11-07)

Observed with `ls` and file reads only. Record counts have NOT been taken yet.

## Build
- `sparkAnalytics` = `modules/spark` (`build.sbt`), depends on `core` only, `spark-sql_2.13` Provided at `DependencyVersions.spark`. `src/main/scala` and `src/test/scala` are empty. `-release:17` is set. Spark on Scala 3 and Java 21 is unverified; routes to build-engineer if it breaks.
- The root test classpath includes `core`, `hadoop` and `entityResolution` but not `sparkAnalytics`. Independent Spark tests in `tests/**` would need a build change (build-engineer).

## Graph-export lead (Phase 5 subset-v6)
Root: `/Users/winnguyen/scholarchain-data/phase4-out/subset-v6/run=run-v1-d4722bc7-0d02-42da-bdb6-088ee508a143/snapshot=8e1be6626420/`
- Run ID `run:v1:d4722bc7-0d02-42da-bdb6-088ee508a143`. Snapshot dir digest `8e1be6626420`. Dataset snapshots pinned `dblp=2026-11-05`, `openalex/crossref/orcid=2026-11-07`. Output is byte-identical to v5 (`subset-v6` recorded in `phase-05/subset-run-logs.md`).
- Stages present: blocking, candidate-features, dblp-extraction, graph-export, identifier-join, source-normalization, team-expansion.
- `stage=graph-export` (has `_SUCCESS`): `works`, `researchers`, `author-mentions`, `institutions`, `topics`, `venues`, each `v1/part-r-00000..3`.
- `stage=candidate-features` (has `_SUCCESS`): `match-candidates/v1/part-r-00000..3`.
- Sampled rows: `schemaVersion` 1. `author-mentions` rows carry `mentionId`, `workId`, `authorPosition`, `name`, `normalizedName`, `externalIds` (sometimes `orcid`), `affiliations`, `provenance`, with no resolved researcher ID. `researchers` rows are source-level (`researcher:v1:orcid:...`), with sources openalex (and orcid).

## Accepted links
- No artifact of accepted researcher or work links was found. `author-mentions` do not link to `researchers`. The ER module (`modules/entity-resolution/.../er/{matching,pairs,eval,sampling,review,llm}`) has a matcher and a gold evaluator over exported pairs, and no batch runner over all match-candidates that writes decisions.
- `/Users/winnguyen/scholarchain-data/gold/phase5/` holds gold labels, `pairs.jsonl` and evaluation outputs only. These are evaluation inputs and are NOT accepted links.
- Decision (per human instruction): route an ER batch run over all match-candidates to `entity-resolution-engineer` first (work order 01).

## Still to be verified by owners
- Exact count of match-candidates (Phase 5 export read 362,905 candidates, 311,249 distinct pairs) and of works, researchers and mentions in the graph-export.
- Whether Hadoop `team-expansion` holds author-paper and pair-candidate outputs usable by Spark. The stage directory exists; its contents have not been listed.
- Output layout, schema versions and ID formulas for canonical researchers (core contract owner: domain-engineer).
