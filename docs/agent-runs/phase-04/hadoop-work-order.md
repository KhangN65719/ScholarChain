# Phase 4 work order: Hadoop ingestion and MapReduce

Owner: `hadoop-engineer`. Dispatched by the orchestrator, 2026-11-06.

Baseline: `74543bb` (Phase 3: Revalidate contracts and datasets with test and provenance repairs).

## Authority

Exclusive owned outputs: `modules/hadoop/**`. The module is currently an empty skeleton (`modules/hadoop/src/main/scala` and `modules/hadoop/src/test/scala` both empty).

Excluded: Spark analytics, storage clients, service APIs, `modules/core/**`, `datasets/**`, build structure, `tests/**`, `scripts/**` and all constitutional files. The bounded EMR smoke-run script the specification names belongs to the infrastructure workflow, not to this agent — do not author it. Report `BLOCKED-ON` with evidence and the responsible owner for anything outside the module.

## Scope

Implement the Phase 4 specification archived verbatim at [prompt.md](prompt.md): the seven required jobs, the required operational behavior, and the required data flow, consuming the Phase 3 core contracts.

## Inputs

- [Project memory](../../../CLAUDE.md)
- [Ownership map](../../agents.md)
- [Role file](../../../.claude/agents/hadoop-engineer.md)
- [Phase 4 specification, verbatim](prompt.md)
- Phase 3 core contracts: `modules/core/src/main/scala/scholarchain/core/{Contracts,ContractWire,StableIds,WireEnums,ScholarChainConfig}.scala`, with `modules/core/src/main/resources/scholarchain-local.v1.yaml` and the contract tests under `modules/core/src/test/scala/scholarchain/core/`
- Phase 3 dataset evidence: `datasets/dataset-manifest.yaml`, `datasets/schemas/{dblp,openalex,orcid,crossref}.md`, bounded samples at `datasets/samples/{dblp.xml,openalex.jsonl,orcid.jsonl,crossref.jsonl}`
- Phase 3 evidence: [domain report](../phase-03/domain-report.md), [dataset report](../phase-03/dataset-report.md), [revalidation results](../phase-03/revalidation-results.md)

## Orchestrator constraints

These narrow the specification's wording. They do not widen any authority.

- **Do not run `sbt check`.** The specification's "Run sbt check" line is satisfied by the human, who runs it with the `!` prefix after this report lands. Treat `.claude/.last-test-run` as stale. Report exactly which checks you ran and which remain pending; do not claim a verification you did not perform.
- **Local bounded inputs only.** Use `datasets/samples/**` and fixtures you create inside the module. No dataset downloads, no large snapshots.
- **No AWS and no live operations.** No EMR, no S3, no cloud calls, no deployment. Design output paths and partitioning so a later bounded EMR run is possible, but execute nothing remotely.
- Every data transformation records source and transformation provenance.
- Do not commit. The human owns commit records.

## Required reporting

Report as the specification's work order directs, limited to what you actually observed on bounded local inputs:

- Job graph, and mapper and reducer key-value contracts per job
- Partition counts and the explicit partition strategy
- Counter inventory and observed counter values
- Rejected records and the versioned reject output paths
- Output paths, including run ID and dataset snapshot composition
- Maximum observed author team size, largest pair expansion, largest observed blocking group
- Determinism and idempotency evidence
- Which tests exist, which you executed, and the exact results
- Any gate item you could not demonstrate, stated as pending with the reason

## Gate

The specification's gate applies. Note that two of its items are not yours to close: the test-engineer adds the adversarial cases under `tests/`, and the code-reviewer returns APPROVE after the human's `sbt check`. Report both as pending.
