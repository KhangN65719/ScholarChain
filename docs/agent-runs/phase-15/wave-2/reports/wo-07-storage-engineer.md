# WO-7 report, storage-engineer (received 2026-11-17; summary of the report as received; author: storage-engineer subagent)

Status: READY-FOR-SBT. No sbt, AWS or terraform; nothing compiled. Formatter "outside this edit" warnings treated as the parallel-writer false positive (`git status --short modules/storage` shows only its own files).

## Files (`modules/storage/src/`)
`main/.../EmbeddingWorkMessage.scala` (schema, parser, encoder); `WorkQueue.scala` (`WorkQueue` port, `InMemoryWorkQueue` fake with visibility, receive count, redrive after 3; `WorkQueues.sqs` loads the adapter by class name); `EmbeddingWorker.scala` (settings, outcomes, per-chunk ledger, loop); `EmbeddingWorkerMain.scala`; `AwsSqsWorkQueue.scala.pending` (SQS adapter, not compiled until `sqs` is on the classpath); `test/.../EmbeddingWorkerSuite.scala`.

## Tests per gate item (EmbeddingWorkerSuite)
Consume/embed/write/ledger/delete and schema round trip; idempotency (done entry deleted with no second embed; duplicate during processing; crash replays between vector write and ledger and between ledger and delete; failed delete); retry and dead letter (transient vector write and embed failures leave the message; retry delay; 15 poison classes; DLQ after three receives; refused text; changed content not overwritten); log lines and bounded shutdown (SIGTERM, max messages, bounded backoff); us-east-2 only and no URL echo; `InMemoryWorkQueue` fake.

## Message schema v1
Flat JSON, UTF-8, at most 262,144 bytes, all keys required, no extra keys: `schemaVersion` (1), `runId`, `subjectId`, `summaryId` (vector key, unique per chunk), `index` (one of 5 index names), `documentType` (e.g. `paper-abstract-v1` takes `work`), `templateVersion`, `embeddingModel` (= `amazon.titan-embed-text-v2:0-d256-norm`), `textHash` (lower-hex SHA-256 of `text`), `sourceRef`, `text` (at most 50,000 bytes, not blank). Other strings non-blank, at most 300 chars, no NUL. A reused `summaryId` with different text is poison (`ChangedVectorContent`).

## Behavior
Ledger in the run-ledger table, `pk EMBEDWORK#<summaryId>`, `sk WORK#<index>#<textHash>`; `CLAIMED` (lease), `DONE`, `RELEASED`; conditional claim writes. Existing identical vector skips embed and write. Transient failures release the claim and leave the message; a live lease defers; poison never deleted; SIGTERM releases unprocessed batch messages with visibility 0. Log line `WORKER_MSG id= outcome= receive_count= latency_ms= [queue_ms=] [reason=] at=<UTC>`, outcomes `done`, `already_done`, `deferred`, `transient`, `poison`, `released`; plus `WORKER_START`, `WORKER_STOP`, `WORKER_RECEIVE_FAILED`, `WORKER_RESULT`. Exit 0 stopped, 2 config error, 1 other; ten consecutive receive failures exit 1.

## Settings
Required: `AWS_REGION` (us-east-2), `SCHOLARCHAIN_EMBEDDING_QUEUE_URL`, `SCHOLARCHAIN_DATA_BUCKET`, `SCHOLARCHAIN_VECTOR_BUCKET`, `SCHOLARCHAIN_TABLE_PREFIX`. Optional: `SCHOLARCHAIN_WORKER_ID`, `_BATCH` 5 (1-10), `_WAIT_SECONDS` 10 (0-20), `_VISIBILITY_SECONDS` 120 (30-900), `_LEASE_SECONDS` 100, `_RETRY_DELAY_SECONDS` 0, `_MAX_MESSAGES` 0, `_SHUTDOWN_GRACE_SECONDS` 25. Dry forms: `--help`, `--check-config` (no AWS call).

## Dependencies
Reported `BLOCKED-ON build-engineer` for `sqs`. Orchestrator note: WO-9 already added `"sqs"` to storage in `build.sbt`; the `.pending` adapter is renamed to `.scala` in storage's sbt slot, and the human removes the `.pending` file.

## Open items
For WO-10: worker role needs SQS receive/delete/change-visibility on `embedding-work`; DynamoDB Get/Put on the run ledger; s3vectors Get/Put/GetIndex; `bedrock:InvokeModel` for Titan v2; S3 put/head/get on `semantic-context/documents/`. Risk: deferred or released receives count toward `maxReceiveCount` 3. Narrow duplicate-write race resolves to one vector (idempotent upsert). Text inline only in v1.

## Unverified
Nothing compiled or run; `AwsSqsWorkQueue` SDK calls from memory; no live SQS redrive or Titan latency.
