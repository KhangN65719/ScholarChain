# Readiness repair: infra-engineer report (received 2026-11-17; summary as received)

Comment, description and doc edits only; no resource or value change; `service_memory = 4096` unchanged; nothing run.

- M2: `SESSION2.md:136` (section 7) now the P15-30 rule (retained <= 400 and peak <= 800 MiB on A's 17-table export -> 2048; otherwise, OOM at -Xmx1g, or void -> 4096); `:21` 4096 provisional, section 7 governs; `:183` void -> 4096; `:208` value 2048 or 4096 by P15-30; `services-session.tfvars:31-36` comment; `variables.tf:421` description. `:19` not touched.
- M3: `SESSION2.md:77` new "Expected IAM changes per plan": A1 two in-place updates (`aws_iam_role_policy.runtime` + `NeptuneReadOpenCypher`, `NeptuneEngineStatus`, `BedrockInvokeExactModels`, `modules/identity/main.tf:318-352`; `aws_iam_role_policy.loader` + `NeptuneBulkLoad`, `BedrockEmbeddingsOnly`, `:454-478`); A2 worker role and `aws_iam_role_policy.worker[0]` (`:565-578`, Titan inside), runtime and loader unchanged; A4 three in-place narrowings (runtime, loader, worker loses `BedrockEmbeddingsOnly` `:551-559`), worker role stays. `SESSION2.md:170` A2 "no runtime or loader policy change"; A1 and A4 counts 2 and 3.
- m1: `SESSION2.md:21`, `:210`, `experiment-size-s.tfvars:30-31`: 2.10 PASSED, 100.9 MiB, 34 objects; 324 MB marked as the old 11-table export.

Open: `module "identity"` block in `environments/course/main.tf` not read (address `module.identity` inferred). A1/A4 IAM expectations assume `enable_bedrock` on at A1 and off at A4 (S overlay `experiment-size-s.tfvars:17`). Possible inconsistency `SESSION2.md:85, :89` (A-up plan IAM none although the S overlay enables Bedrock and Neptune unless `baseline-s.tfvars` overrides).

Orchestrator note: the A + B session's A-up plan (course + S + baseline-s) showed `IAM_CHANGES: none` (session-record 2.1), so `baseline-s.tfvars` does override; the `:85, :89` text matches observation.
