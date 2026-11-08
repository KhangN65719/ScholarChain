# AWS service limits for Phase 7 adapters

Captured 2026-11-08 by the orchestrator through the AWS Documentation MCP server (public pages, no AWS identity). Values are as the pages stated on that date; re-verify before any live run. Adapters should take these as named constants/config and validate before sending.

## S3 Vectors
Source: https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-limitations.html (also s3-vectors-vectors.html for the key rule: up to 1,024 characters, case-sensitive, UTF-8)
- Vector buckets per Region per account: 10,000. Vector indexes per bucket: 10,000. Vectors per index: up to 2 billion.
- Dimension per vector: 1 to 4,096.
- Total metadata per vector: up to 40 KB (filterable + non-filterable). Total metadata keys per vector: up to 50.
- Filterable metadata per vector: up to 2 KB. Non-filterable metadata keys per index: up to 10.
- PutVectors and DeleteVectors: up to 1,000 combined requests/s per index; up to 2,500 vectors inserted+deleted/s per index.
- Request payload: up to 20 MiB. Vectors per PutVectors call: up to 500. Per DeleteVectors: up to 500. Per GetVectors: up to 100.
- QueryVectors top-K: up to 10,000; results per page up to 100; ENHANCED index filter constraints up to 100. ListVectors page up to 1,000; segment count up to 16.
- Vectors must be float32 (s3-vectors-best-practices.html).
- Note: the Bedrock Knowledge Bases page (s3-vectors-bedrock-kb.html) states tighter metadata limits (1 KB custom, 35 keys) only when used through Bedrock KB; Phase 7 writes directly.
- Design consequence: batch size <= 500; filterable metadata <= 2 KB and references only (no long text); stable key <= 1,024 chars.

## Neptune
Source: https://docs.aws.amazon.com/neptune/latest/userguide/limits.html
- Bulk loader: at most 64 queued load jobs; only the most recent 1,024 jobs tracked; last 10,000 error details per job stored. The loader API is non-ACID (so loads must be idempotent by ID).
- Individual property or label: 55 MB max (long text stays in S3 regardless).
- No limit on vertices, edges, or properties per element.
- Gremlin/SPARQL HTTP request payload under 150 MB (HTTP 400 otherwise); not applied to Gremlin WebSockets.
- Null characters in strings are unsupported (validate before export).
- VPC-only service; TLS 1.2 HTTPS only. Concurrent WebSocket connections: 32,768 on larger and serverless instances, smaller classes lower (table on the page).
- Cluster volume max 128 TiB.
- Offline loader all-or-nothing semantics: https://docs.aws.amazon.com/neptune/latest/userguide/bulk-load-offline.html (not read in detail; the live loader is non-ACID per the page above).

## DynamoDB
Source: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Constraints.html
- Item size: 400 KB including attribute names and values (UTF-8 bytes).
- Partition key value: 1 to 2,048 bytes. Sort key value: 1 to 1,024 bytes.
- Table and index names: 3 to 255 characters from A-Z a-z 0-9 _ - .
- BatchWriteItem: up to 25 put/delete requests, total up to 16 MB; BatchGetItem: up to 100 items, up to 16 MB.
- Query and Scan: 1 MB result per call (paginate with LastEvaluatedKey).
- UpdateTimeToLive: one enable/disable per table per hour.
- Capacity units: 1 WCU = one write/s up to 1 KB; transactional writes cost double.
- Design consequence: the conditional-write idempotency path uses PutItem/UpdateItem with a ConditionExpression (BatchWriteItem does not support conditions); batch writers must handle UnprocessedItems.

## Not captured
S3 general (ordinary S3) object and key limits, and Neptune per-account RDS-shared limits, were not read. The Neptune offline-loader page and Neptune Analytics quotas were located but not read. Mark as open.
