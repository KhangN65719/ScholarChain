# Storage module

Ports, local fakes and loaders for S3, Neptune, DynamoDB and S3 Vectors. The
AWS-backed adapters are DEFERRED stubs (`AwsAdapters.scala`); nothing here calls
AWS or reads credentials.

## AWS integration suite (opt-in)

- Gate: environment variable `SCHOLARCHAIN_AWS_IT=1` (exactly `1`). Any other
  value or an unset variable skips the suite (`AwsIntegrationGate.enabled`).
- Never part of `sbt check` or `sbt quick` by default.
- With the variable set, the cases skip with "AWS adapters deferred" until real
  adapters exist.
- Planned bounded coverage once adapters exist (placeholders, not implemented):
  - S3: one test prefix, at most 10 objects of at most 1 MiB; cleanup deletes
    only that prefix.
  - DynamoDB: one throwaway table, at most 50 items; table deleted afterwards.
  - S3 Vectors: one index, at most one batch of 500 vectors; index deleted
    afterwards.
  - Neptune: one parameterized read on a graph of at most 1,000 elements.
- Live runs need the human-approved account, region, cost ceiling and cleanup
  procedure; those are recorded outside this module.

## Vector queries

`VectorBucketPort.query(VectorQuery(index, embedding, topK, filter))` with
`VectorFilter(researcher, yearFrom, yearTo, source, topic, institution, community,
documentType)`; `VectorFilter.parse` reads the JSON form and refuses unknown keys.
`SemanticVectorLoader.load` takes an optional `SummaryFacets` (from
`SummaryFacets.fromTables`) that fills `communityId`, `source` and `year`. See
`DESIGN.md`.

## Neptune scope

`new SparkGraphProjector(tables, bounds)` defaults to `GraphScope.Extended`, which
also loads RESOLVES_TO and DERIVED_FROM (`DatasetSnapshot` context node). Pass
`GraphScope.Base` for AUTHORED, COAUTHORED_WITH and PUBLISHED_IN only. AFFILIATED_WITH, HAS_TOPIC and SUPPORTED_BY are not loaded; `DESIGN.md`
says why.

## Edge IDs

AUTHORED and PUBLISHED_IN IDs are core `StableIds.graphEdge` IDs (`edge:v1:`),
with a core evidence ID minted from the row keys; `idDerivation` records
`core:StableIds.graphEdge`.
