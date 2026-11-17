# WO-12b report, test-engineer (received 2026-11-17; summary of the report as received; author: test-engineer subagent)

Status: COMPLETE for authoring; nothing executed (guard). READY-FOR-SBT for one Scala suite.

## Files (`tests/**` only)
`tests/scripts/run.sh` (one section inserted before "Case 12"; no existing case edited); `tests/scripts/w2/{tfcheck.py,expcheck.py,emit_messages.py,mutate.py,mkjson.py}`; fixture tree `tests/scripts/w2/fixtures/tf-good/`; `tests/adversarial/P15EmbeddingMessageCrossModuleSuite.scala` (29 tests when python3 is present: runs the real producer and feeds 28 bodies to `EmbeddingWorkMessage.parse` plus the worker checks; prints a note and asserts nothing without python3; not in `slowSuites`).

## Cases: 85 new (`15.wave2.*`)
- Block 1, real repository (29): ALB internal/no public IP/`/healthz`/`ip`/port 8080 or 8081/grace >= 120 s; ALB SG only from the load-client SG; query scaling bounds and scale-in cooldown <= 900 s; worker scaling on `ApproximateNumberOfMessagesVisible`; max tasks x vCPU <= 30; p95 `TargetResponseTime` alarm; Container Insights enabled; dashboard names 19 metric groups; retention >= 7 days; task `command` starts with an existing `scholarchain.*` main; every env/secret name set is read by Scala main code; required names set and `SCHOLARCHAIN_GRAPH_RUN_ID` from a variable; secrets only via the ECS secrets block; `maxReceiveCount` 3; Bedrock model ID a variable, stub default, 10 calls; C-F manifests within P15-1 caps and agreeing with entry points (C `MAX_TASKS` exactly 2000; D `MAX_MINUTES` >= 65, >= 48 clients); C-F JSON copies equal; C-F verbs read-only except documented sends; narrow allow-lists; `load-tests/README.md` superseded; schema constants agree with the storage source; producer validation agrees on 28 cases; emit script output.
- Block 2, self-tests (56): good Terraform fixture plus 30 single-rule mutations each refused; 9 AWS-verb fixture cases; 11 manifest/JSON cases; 5 guard-weakening cases.
- Expected before WO-10 and M-1: about 66-67 pass, 18-19 fail among the new cases; after: 85/85 unless WO-10's design differs from the encoded assumptions (target type `ip`, port 8080/8081, grace >= 120 s, worker target named "worker" or "embed", load-client task needs no main class, unresolved CPU counted as 1 vCPU).

## Open items
Unexecuted (about 1,000 lines); `tfcheck.py` is a brace-matching scan resolving literals and `var.NAME` only; IAM content not covered; metric names unverified; `tests/scripts/README.md` and `tests/ADVERSARIAL_COVERAGE.md` not updated; possible verb-scan false positive on unread files.

## Orchestrator cross-check against WO-10 (landed in parallel)
WO-10 reports it aligned its names to `tfcheck.py`; remaining mismatch named by WO-10: `env-read` flags `SCHOLARCHAIN_QUERY_TOKEN` on the load-client container (read by Python, not Scala). Existing cases WO-10 expects to break: fast/slow service names or counts, Container Insights disabled, `desired_count` 0-2, retention 3 days, runtime header "no load balancer", `session-down` contents, IAM role count, `container_environment` shape, SG lists.
