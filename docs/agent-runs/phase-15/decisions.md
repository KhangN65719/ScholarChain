# Phase 15 decisions

Recorded by the orchestrator on 2026-11-16 from the human's message answering the ten decisions in `plan.md`. The human is the decision owner. IDs are local to Phase 15 (P15-1 and on).

| ID | Decision (human, 2026-11-16) |
|---|---|
| P15-1 | D12 caps ratified as proposed: tier S 90 min, 64 clients, 20,000 requests (tasks). Tiers M and L unchanged. Experiment D levels 1, 8, 24, 48 clients at 8 min each, then 15 min at zero clients. |
| P15-2 | Experiment A: reuse S run 6 (`run-v1-192de0fd-1442-4508-9065-09c44775643b`) if its records hold task counts, shuffle and failures; record the commit difference in the limitations. Otherwise one single-node re-run in session 1. |
| P15-3 | Internal load balancer approved: internal only, no public IPs, its security group open only to the load-client tasks. |
| P15-4 | Haiku for the smoke test only, at most 10 queries. D, E and F use the offline stub. The Bedrock model ID is a configuration setting, not hard-coded. Add a read-only, paste-ready check that Bedrock model access for that Haiku model is enabled in us-east-2 (no AWS-changing command). If access is missing, the human enables it in the console. |
| P15-5 | Neptune: bulk load on `db.r6g.xlarge`, then apply back to `db.t4g.medium` inside session 2. Each apply has its own approval record. |
| P15-6 | Container Insights on. |
| P15-7 | If no partition or reducer setting exists for Experiment B, add it as a setting with the default unchanged. |
| P15-8 | The human gets the read-only EC2 On-Demand vCPU and Fargate vCPU quota checks before session 1. |
| P15-9 | A code-reviewer pass after each wave. The human commits after each wave. |
| P15-10 | Stop rule: a session starts only if billed spend plus that session's HIGH projection is at most 14 USD. Session 2 order: smoke, D, E, F, then C. |
| P15-12 | (2026-11-17) The WO-1b limit is accepted: in Experiment B, Hadoop MapReduce runs on HDFS across core nodes and the Spark and export stages stay on the primary node (`local[4]`), so B's scale-out evidence comes from the MapReduce stage. Recorded as a B limitation. |
| P15-13 | (2026-11-17) Keep Claude Haiku 4.5 through `us.anthropic.claude-haiku-4-5-20251001-v1:0` (ACTIVE). The human submits the Anthropic first-time use case form and re-runs the read-only availability check before session 2 (expect `AVAILABLE`). |
| P15-14 | (2026-11-17) The 2,000-chunk file for C is not tracked in git. It lives under `/Users/winnguyen/scholarchain-data`; the repository keeps only its manifest (sha256, count, bytes, selection rule) and the rebuild script. |
| P15-15 | (2026-11-17) Wave 1 harness failures (1,599 pass, 14 fail) are repaired by their owners in one batch without weakening any assertion; the human reruns the harness, then the Wave 1 code review starts. |
| P15-16 | (2026-11-17, review 1 K1 and M2) Experiment B uses the full tier S cap: pipeline stop about 88 min, manifest `MAX_MINUTES=90`, `SPEND_CEILING_USD` about 2.40. `b-burst` Spark shuffle partitions 8 (not 32) and no Spark event log for B. Session 1 HIGH about 3.15 USD. |
| P15-17 | (2026-11-17, review 1 M5) Each experiment keeps its flat KEY=VALUE manifest for `run-experiment.sh` and commits a generated JSON copy, with a check that the two match. |
| P15-18 | (2026-11-17) WO-7b full scope: S3-export read models plus the Neptune openCypher query adapter, dispatched to storage-engineer now; it blocks the AWS query smoke (minimum acceptance criterion). No sbt by anyone else while storage-engineer writes WO-7b; the human holds the `dockerLocal` section of `wave-2/human-commands.md` until WO-7b is ready. |
| P15-19 | (2026-11-17, review 2) N1, N3, N4, N5, N6 go to their owners in one batch now. N1 also fixes the fixture `spark-run-manifest.json` to the real `outputs[].table` / `outputs[].rows` shape so the rehearsal would catch the defect; the rehearsal is not weakened. |
| P15-20 | (2026-11-17, review 2 N2) B limitation accepted: if B's 88-minute pipeline stop fires, the cluster is still terminated and cost stays bounded, but the teardown check after it can take up to 300 s, so the 90-minute entry limit may cut the end of the run record. Recorded as a B limitation; the `experiments/b-burst/run.sh` comment is corrected to say so. |
| P15-11 | Fallback for the scaling gate: if B does not show a clear scale-out and scale-in, D's ECS scaling is the second source of that evidence. Both are recorded either way. |
