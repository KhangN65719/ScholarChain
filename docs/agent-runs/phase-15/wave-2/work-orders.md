# Phase 15 Wave 2 work orders (first dispatch)

Issued by the orchestrator on 2026-11-17 under `../plan.md` and `../decisions.md`. Human instruction (2026-11-17): start Wave 2 in parallel with the Wave 1 harness and re-review, only for work orders whose write sets do not overlap Wave 1: WO-5, WO-6 (only if needed), WO-7, WO-8, WO-9 and WO-11 (new paths only). WO-10 (infra) and WO-12b (test) are held until the human confirms the Wave 1 commit. Wave 1 repairs keep priority when an owner is needed for both. Never two sbt at once. Every human-only command for Wave 2 goes in `human-commands.md` in this directory with expected results.

## Common rules
- Read `CLAUDE.md`, `docs/agents.md`, your role file, `../plan.md`, `../decisions.md` and `../prompt.md` first.
- No AWS command of any kind, no terraform, no deploy, no docker push, no commit, read-only git. Region us-east-2 only.
- Write only inside your owned paths. A need outside them is `BLOCKED-ON <owner>` with evidence. Do not touch any Wave 1 path: `scripts/**`, `infra/terraform/**`, `tests/**`, `experiments/manifests/**`, `experiments/lib/**`, `experiments/a-baseline/**`, `experiments/b-burst/**`, `experiments/check-manifest-json.sh`, `experiments/rehearse-manifest-json.sh`, `experiments/pipeline-inputs/**`, `datasets/**`, `modules/hadoop/**`, `modules/spark/**`.
- Before implementation, list which specific test proves each item of your primary gate; put it in your report. Every behavior change has a feature test. Do not weaken tests. Default behavior stays unchanged unless a setting asks otherwise.
- **sbt slot.** Do NOT run sbt in this turn. Several owners write Scala at once. Finish the code and tests, then end with `READY-FOR-SBT`. The orchestrator resumes owners one at a time to run `sbt quick` (bare two-token form). When the stop hook fires because sources are newer than the marker, repeat your FULL report; that is expected.
- Human commands: `AWS_PROFILE=scholarchain` where AWS is involved, `set -o pipefail`, log tee'd to `/Users/winnguyen/scholarchain-data/logs/phase15-w2-<name>.log`, ending in `| tail -n 60; echo "exit: $?"`; zsh-safe; with the expected result. No account ID, no secrets (Secrets Manager only).
- Report: files changed, tests written (names), open items, the exact settings (environment variable names, defaults, meaning) your code reads, and anything not verified.

## Shared names (fixed here so parallel owners code against the same API)
| Name | Owner | Meaning |
|---|---|---|
| `scholarchain.rag.RetrievalMode` enum: `Graph` (`"graph"`), `Vector` (`"vector"`), `Fused` (`"fused"`, default) | rag | which retrievers the workflow runs; `Fused` is today's behavior, byte-identical |
| `scholarchain.rag.BedrockAnswerModel` | rag | Bedrock Converse adapter implementing the existing synthesis model trait; model ID passed in, never hard-coded; hard per-process call cap |
| per-stage timings (planning, graph retrieval, vector retrieval, fusion, synthesis, verification) in milliseconds | rag | returned by the workflow alongside the answer, not in the core response contract |
| `scholarchain.services.runtime.AwsRuntime` (main) | services | AWS composition: Neptune, S3 Vectors, DynamoDB, answer model by setting (stub default, Bedrock optional) |
| `scholarchain.storage.EmbeddingWorkerMain` (main) | storage | SQS `embedding-work` consumer |
| Environment variables prefixed `SCHOLARCHAIN_` | each main's owner | listed in each report; WO-10 (held) wires them in Terraform |

WO-6 (domain-engineer) is NOT dispatched: the retrieval mode is a services request field and a rag parameter, and timings travel in rag results and service logs, so the core contract needs no change. An owner that finds a real core need reports `BLOCKED-ON domain-engineer` with evidence.

## WO-5 rag-engineer (`modules/rag/**`, `prompts/**`)
1. `RetrievalMode` as above, threaded through the workflow (`Workflow.scala`, `Retrieval.scala`, `SemanticRetrieval.scala` as needed). `Graph` runs no vector retrieval; `Vector` runs no graph retrieval; `Fused` unchanged. Each mode still returns evidence IDs; a mode that yields no evidence returns the existing insufficient-evidence result, never an unsupported claim. Tests: each mode on the demo/fixture world (which retrievers ran, evidence IDs present), and `Fused` output identical to today's.
2. `BedrockAnswerModel`: Bedrock Runtime Converse call for the synthesis step, model ID and Region (us-east-2) passed in by the caller (P15-4, P15-13: `us.anthropic.claude-haiku-4-5-20251001-v1:0` is the value the human will set, not a code constant). A hard cap on calls per process (constructor argument; the runtime default is 10) after which it refuses with a clear error and makes no call. Bounded timeouts, no retry storm, no prompt or answer text in logs beyond IDs and sizes. Claims are still verified by the existing verifier; an unsupported model claim is dropped or flagged as today. Tests with a fake client: request shape, cap enforcement (call 11 refused, no client call), timeout and error mapping, verifier still applied. No live call.
3. Per-stage timings as above. Tests: every stage present, non-negative, the skipped retriever recorded as skipped (not 0 ms) in `Graph` and `Vector` modes.
4. If the Bedrock Runtime SDK is not on `rag`'s classpath, report the exact artifact as `BLOCKED-ON build-engineer` (WO-9 is told to expect it) and keep the adapter behind an interface so the rest compiles.

## WO-7 storage-engineer (`modules/storage/**`)
1. `EmbeddingWorkerMain`: long-polls the `embedding-work` SQS queue (queue URL from a setting), parses one message as one `EmbeddingWorkItem`-based work message (define and document the JSON schema with a version field; reuse `EmbeddingWorkItem` and add the chunk text or a pointer to it), embeds with the existing Titan embedder, writes the vector to S3 Vectors, records completion in the DynamoDB ledger, then deletes the message.
2. Idempotency: a message whose ledger entry already says done is deleted without a second embed or write; a duplicate delivery during processing does not produce two vector writes (conditional ledger write). Tests: duplicate message, replay after a crash between write and delete.
3. Retry and dead letter: a transient failure leaves the message for SQS redelivery (no delete, bounded visibility extension at most); a poison message (unparseable, wrong version, oversized) is never deleted by the worker and is logged with its message ID and reason only, so the queue's `maxReceiveCount` 3 moves it to the DLQ (existing Terraform, `workflow/main.tf`). Tests for each class.
4. Structured log lines per message (message ID, outcome, receive count, latency ms, UTC time) for Experiment C throughput and F evidence; bounded shutdown on SIGTERM (finish or release the in-flight message).
5. In-VPC use: every AWS client takes Region us-east-2 and bounded timeouts; no public endpoint assumption.
6. If the SQS SDK artifact is missing, report it as `BLOCKED-ON build-engineer` with the exact artifact; code against a small queue interface with a fake for tests.
7. Write the message schema and a two-line example into your report; experiment-engineer (WO-11) builds the enqueue tool from it.

## WO-8 service-engineer (`modules/services/**`)
1. `AwsRuntime` main: composes Neptune, S3 Vectors, DynamoDB and the answer model from settings. Answer model setting: `stub` (default, the existing `DeterministicStubModel`) or `bedrock` (WO-5 `BedrockAnswerModel`, model ID setting required, call cap setting default 10). Reuse or explain the existing `SCHOLARCHAIN_BEDROCK` variable that Terraform passes and no code reads. Secrets only via Secrets Manager if any are needed.
2. Optional request field for the retrieval mode (`graph`, `vector`, `fused`; absent means `fused`, today's behavior; an unknown value is 400 with the existing error shape). Tests: each value reaches the workflow; absent is unchanged; unknown is refused.
3. Latency logs: one structured line per request with request ID, mode, status, total ms and the WO-5 stage timings, UTC time; no question text. Tests.
4. Health check route suitable for an internal ALB target group (cheap, no downstream call, 200 when the process is ready); give its path and port. Tests.
5. Settings table in your report: every environment variable `AwsRuntime` reads, default, required or optional, and which Terraform variable likely feeds it (read `infra/terraform/modules/runtime/main.tf`, do not edit).
6. rag-engineer is changing `modules/rag/**` at the same time to the shared names above. Code against those names; if the landed signatures differ, report the exact mismatch rather than editing rag.

## WO-9 build-engineer (build.sbt, `project/**` excluding version values, `Dockerfile`, `packaging/**`)
1. The service image's `ENTRYPOINT` is `java ... -cp /opt/scholarchain/lib/*` with `CMD ["-version"]`; the ECS task `command` (WO-10, held) will name the main class. Verify that the staged `lib/` holds every jar the two new mains need (`scholarchain.services.runtime.AwsRuntime`, `scholarchain.storage.EmbeddingWorkerMain`), and fix the packaging if not. Give the exact local commands for the human: stage, `docker build`, and `docker run --rm <image> <main> --help` (or the mains' documented dry form) for each main, with expected output.
2. Dependencies: rag may need the Bedrock Runtime SDK and storage the SQS SDK (AWS SDK v2). Check what is already declared. Add module references using existing ledger keys only (structure is yours). If a new version value is needed, report `BLOCKED-ON dependency-updater` with the artifact and the ledger key you propose; do not set version values.
3. Load-client image: a small `packaging/load-client/Dockerfile` (base image pinned by digest, non-root) that runs `python3 /opt/load-client/load_client.py` from `load-tests/query-load/` (WO-11 writes it; stdlib only). Give the human `docker build` and a local `--help` run.
4. `slowSuites`: add any new slow Spark or Hadoop suite reported by owners (none expected this wave); state that you checked.

## WO-11 experiment-engineer (new paths only: `experiments/c-embedding-backlog/**`, `experiments/d-query-load/**`, `experiments/e-retrieval-modes/**`, `experiments/f-failure-recovery/**`, `load-tests/**`)
Do NOT edit `experiments/manifests/**` (Wave 1, under review). List the manifest changes you need (including C `MAX_TASKS` 5000 to 2000 and anything D, E, F need) in your report; they are applied after the Wave 1 commit.
1. `load-tests/query-load/load_client.py` (Python 3, stdlib only): closed-loop clients against the internal ALB URL (argument), level schedule from arguments (D: 1, 8, 24, 48 clients, 8 min each, then 15 min at zero, P15-1), per-request think time, hard caps from arguments (max clients 64, max total requests 20,000, max minutes 90) that stop the run, per-request raw lines (UTC send time, latency ms, status, mode, request ID) written once to an output directory it refuses to overwrite. Size the think time so the D projection stays under 20,000 requests and state the arithmetic. A local rehearsal against a fake HTTP server (stdlib) with expected counts.
2. Entry points and collectors for C, D, E and F in the new directories, following the A and B pattern (start once, no tuning, UTC events, raw files immutable; collectors read-only: CloudWatch `get-metric-data`, ECS `describe-services`, SQS `get-queue-attributes`, Step Functions history where relevant). Verify every metric name you rely on from AWS documentation (aws-documentation MCP allowed) and cite it; mark any unverified name.
   - C: enqueue tool that sends the 2,000 chunks (P15-14, chunk file outside the repo, path argument) in the WO-7 message schema; the sending command is a human `!` command; record queue depth, running and desired worker tasks, throughput, retries, DLQ count and drain time.
   - D: as above, with ECS task count, CPU, memory, ALB p50/p95/p99, errors, Neptune, DynamoDB throttles, vector and LLM latency (stub in D, P15-4).
   - E: same query set in `graph`, `vector`, `fused` modes, interleaved or in fixed order (state which and why), stub model.
   - F: one safe failure: one poison message (and optionally one duplicate message for idempotency) on the embedding queue; show receive count to 3, DLQ arrival, the ledger keeping one write, and the worker still draining normal messages.
3. Derive scripts (tables and plots from raw files only) with fixture rehearsals and expected pass counts, like A and B.
4. Everything AWS-touching is a paste-ready human command; list them for `human-commands.md`.
