# Storage module

Ports, local fakes and loaders for S3, Neptune, DynamoDB and S3 Vectors, and the
AWS SDK v2 adapters behind the ports. Every adapter is constructor-injected and
reads no environment. Tests use stubbed SDK clients; nothing in the test run
calls AWS.

## AWS adapters

| Class | Port | Notes |
|---|---|---|
| `AwsObjectStore` | `ObjectStore` | S3. Default encryption only. `head` is `None` only for a 404 naming a missing key; a 403 is `AwsAccessDenied`. Conditional put (`If-None-Match: *`). `list` costs one HEAD per object; `listKeys` is cheap. |
| `AwsVectorBucket` | `VectorBucketPort` | S3 Vectors. `ensureIndex` only calls GetIndex and compares dimension, data type, metric and non-filterable keys; it never creates an index. `count` pages ListVectors. `query` pages with `nextToken` and translates `VectorFilter` (`S3VectorsFilters`). |
| `TitanEmbedder` | `Embedder` | Bedrock `amazon.titan-embed-text-v2:0`, 256 dimensions, normalize true. Retries throttling itself, then `RetryExhausted`. |
| `AwsNeptuneBulkLoader` | `NeptuneBulkLoader` | StartLoaderJob and GetLoaderJobStatus, one file per job, vertex files first. Runs inside the VPC. |
| `AwsDynamoTable` | `DynamoTable` | Conditional PutItem, consistent GetItem, paged Query. |
| `AwsClients` | none | The only source of SDK clients: takes a `Region`, default credentials provider chain, `UrlConnectionHttpClient` on every builder. |

Errors: throttling, request timeouts and 5xx are `TransientStorageException`;
401 and 403 are `AwsAccessDenied`; other 4xx are `AwsRejected` and are not
retried (`AwsErrors`).

Neptune export: the CSV files live under `graph/neptune/v=v1/run=<run>/` and the
manifest under `graph/neptune-manifest/v=v1/run=<run>/`, so a prefix load never
reads the manifest. A list property is one `String` column joined by `;`
(`NeptuneExporter.ListColumns`); the openCypher format documents no array type.

Still deferred: the Neptune openCypher query port (`DeferredAwsNeptuneQuery`).

## AWS integration suite (opt-in)

- Gate: environment variable `SCHOLARCHAIN_AWS_IT=1` (exactly `1`). Any other
  value or an unset variable skips the suite (`AwsIntegrationGate.enabled`).
- Never part of `sbt check` or `sbt quick` by default.
- With the variable set, the cases still skip with "AWS adapters deferred":
  the adapters exist, but no live case is written or approved yet.
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
