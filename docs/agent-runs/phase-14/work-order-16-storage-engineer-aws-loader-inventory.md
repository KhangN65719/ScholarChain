# Work order 16: storage-engineer, what exists for an AWS loader (read-only inventory, replaces the blocked work order 12)

Owner: storage-engineer (modules/storage/**). Read-only: no edits, no sbt, no AWS, no commit.

Search is unavailable to agents (guard). The human wrote a file listing to docs/agent-runs/phase-14/listing-storage-rag.txt (all .scala files under modules/storage and modules/rag, plus test files). Read it first, then read the relevant sources by path with Read.

Evidence: decisions-d13-d14.md (D17), reports-14-15.md (spark-engineer: `scholarchain.rag.IngestMain` is LOCAL ONLY: LocalFsObjectStore, InMemoryVectorBucket, InMemoryDynamoTable, HashEmbedder, no AWS client; it reads a JsonlExport directory `<exportDir> <outDir> [maxSummaryRows]` and writes ingest-report.json and ingest-run.json). Instructor requirement (D2/D17): vector ingestion counts with working retrieval and a Neptune load, on AWS, S tier (3,472 works), Titan embeddings `amazon.titan-embed-text-v2:0` at 256 dimensions, S3 Vectors indexes, region us-east-2.

Report, marking each statement read from code or UNVERIFIED:
1. Inventory: every adapter or interface in modules/storage and modules/rag for S3 objects, S3 Vectors, DynamoDB, Neptune (bulk loader or openCypher/Gremlin writes), and embeddings (Titan, hash embedder). For each: interface name, local implementation, AWS implementation if any (file and line), how it authenticates and calls AWS (AWS SDK dependency? Java HttpClient with SigV4? check build.sbt and project/DependencyVersions.scala), and the tests that cover it (stubbed clients?).
2. The smallest AWS loader that works for the S run: which existing pieces compose (IngestMain structure, the vector bucket and index names and dimension guard, the embedder interface, the ingest-run ledger and idempotency), what is missing (an AWS S3 Vectors adapter? a Titan embedder? a DynamoDB adapter? a Neptune bulk-load writer that turns Parquet or the `load-manifests/*.json` plans into the Neptune bulk loader CSV or JSON format and calls the loader API with an IAM role and an S3 path, L1?), and a recommended split between storage-engineer (adapters) and rag-engineer (IngestMain wiring), with the order.
3. What must be true in Terraform for it to work (IAM actions on the loader role, the Neptune bulk-loader role and S3 path, VPC endpoints for bedrock-runtime, s3vectors, dynamodb; the S3 Vectors bucket and index names the Terraform creates: `scholarchain-course-<account>-vectors` and five indexes, read the names from modules/s3vectors), as a list for infra-engineer. Say which are already present and which are not.
4. New dependencies needed (an AWS SDK or none), because a new dependency needs dependency-updater and human approval via the ledger. If an SDK is required, say which module and the smallest option.
5. The retrieval check that proves ingestion works (a query against the S3 Vectors index returning hits with evidence ids), and what the loader reports (counts per index, skipped, failed).
6. A test list for the implementation, naming the specific stubbed-AWS tests that would prove each item (CLAUDE.md rule: the owner lists the tests before implementing).

Do not guess names. You cannot run bash (guard D11); state nothing was executed.

Report contract: STATUS, OWNER, WORK ORDER, FINDINGS (1 to 6), RECOMMENDATION, RISKS, BLOCKED-ON OWNER, NEXT GATE.
