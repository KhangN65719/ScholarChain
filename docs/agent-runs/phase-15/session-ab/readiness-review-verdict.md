# Session 2 readiness review (WO-R) verdict (code-reviewer, received 2026-11-17; summary as received)

**REQUEST CHANGES.** Read-only; nothing executed.

BLOCKER
- B1 `docs/releases/phase-15/`: no session-2 runbook (only session-1, session-1b, session-ab). `SESSION2.md:163-202` has plans and reads but no A1-A4 records, no smoke/D/E/F/C commands, no stop rules.
- B2 Graph-only reload of A's export: code path exists (`modules/rag/.../AwsIngestMain.scala:71-81` `--graph-only`; `scripts/run-bounded-pipeline.sh:172-227` mode `loader-only`; xlarge from `graph-load.tfvars:33`). Chain: A1 (course + S + graph-load), then `run-bounded-pipeline.sh` with `{"mode":"loader-only","run_id":"run-v1-b76e2d37-...","loader_command":[..., "--graph-only"]}` and `--size large` (`scripts/README.md:505`, loader cap 5400 s). Not written or reviewed; `experiments/pipeline-inputs/` has only A and B templates; no burn figure with xlarge Neptune; no `--tolerate-load-errors` decision; no evidence graph-only handles a 17-table export (S run 11 used 11).

MAJOR
- M1 Vectors and graph from different runs: A had no loader (`SESSION2.md:85`); graph-only skips ingest (`AwsIngestMain.scala:74-77`); S3 Vectors hold an earlier run's vectors while Neptune and read models are A's (`run:v1:3cfb57b1...`); E's vector and fused modes would cite mixed runs. Index naming gap open (`SESSION2.md:217`; `wo-08b-service-engineer.md:24`). Unverified whether the runtime filters vector hits by run.
- M2 Memory rule conflict: `SESSION2.md:136` (peak x 2 / 0.75 + 0.3 GiB) gives 3072 at 800 MiB; P15-30 says 2048. `services-session.tfvars:31-34` and `environments/course/variables.tf:421` defer to SESSION2.md's measurement; value `services-session.tfvars:37` (`service_memory = 4096`); 512 CPU allows 2048 (`modules/runtime/variables.tf:123`). Change and commit before A1 (`SESSION2.md:73`).
- M3 Expected IAM changes not stated for A1 and A4: A4 drops the S overlay so `enable_neptune`/`enable_bedrock` turn false, removing runtime Neptune and Bedrock (`modules/identity/main.tf:318-352`), loader (`:458`, `:474`), worker Titan (`:551-559`); `SESSION2.md:170` lists no IAM change; A1 adds them; `SESSION2.md:77` names only the runtime split.

MINOR
- m1 Stale text `SESSION2.md:21`, `:209`; `experiment-size-s.tfvars:30` ("324 MB").
- m2 Haiku foundation-model ARNs in three Regions (`environments/course/main.tf:27-29`) without a `bedrock:InferenceProfileArn` condition (optional hardening).
- m3 4-query smoke cap enforced by procedure only; `bedrock_max_calls = 10` allows about 5; a restart resets (`services-smoke.tfvars:13`); 8 would enforce 4 in code.
- m4 D scale-in: client cooldown 900 s (`load_client.py:11`), target-tracking scale-in 15-17 min (`SESSION2.md:39`): runbook needs the 25-minute hold and ECS event collection.
- m5 `OpenCypher` condition key unverified (`identity/main.tf:316-331`); fallback needs a pre-agreed privilege review and apply path.
- m6 Worker prefix question (`SESSION2.md:216`) looks resolved by `SemanticDocuments.scala:47-62`; owner to confirm `ObjectKeys.versioned`.

Fine: item 1 (`session2.tfvars` layering and validations: `variables.tf:438, :454, :443, :459, :617`, `modules/runtime/main.tf:561-569`, `plan.sh:44`); item 2 (smoke 5 keys, 1 task, model from `bedrock_haiku_profile_id`, 60 s deadline under 120 s ALB idle); item 5 (300 req/min and CPU 60 %, min 1 max 4, cooldowns 60/300, `services-session.tfvars:53-58`); item 7 (Neptune ARN scoped to cluster resource ID, IAM DB auth on, worker no Neptune/Haiku, runtime InvokeModel on exact ARNs).

Must change: B1 deploy-engineer; B2 experiment-engineer (input) + deploy-engineer (runbook) + rag-engineer (17-table evidence); M1 rag-engineer with storage-engineer, then human; M2 infra-engineer (value after 4.2); M3 infra-engineer; m1 infra-engineer.
