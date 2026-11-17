# Wave 1 documentation verification (orchestrator, 2026-11-17, aws-documentation MCP, read-only)

Purpose: close the UNVERIFIED pre-session command items from WO-1 and WO-1b. Evidence only; no file owned by a specialist was edited.

| Item | Finding | Source |
|---|---|---|
| EC2 quota code `L-1216C47A` | VERIFIED: "Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances", unit vCPUs, **default 5**, adjustable | https://docs.aws.amazon.com/ec2/latest/instancetypes/ec2-instance-quotas.html ("On-Demand Instance quotas") |
| Fargate quota code `L-3032A538` | VERIFIED: "Fargate On-Demand vCPU resource count", **default 6 in each supported Region**, adjustable; new accounts may start lower and rise with usage | https://docs.aws.amazon.com/general/latest/gr/ecs-service.html ("AWS Fargate quotas") |
| `aws bedrock get-foundation-model-availability --model-id <ModelId>` | VERIFIED that the CLI command exists (read-level IAM action `bedrock:GetFoundationModelAvailability`). Response fields not read | https://docs.aws.amazon.com/cli/latest/reference/bedrock/get-foundation-model-availability.html (search result) |
| EMR managed scaling signal | Search result only: CloudWatch metric `ContainerPendingRatio` (pending containers to allocated) is a managed-scaling metric; page sections not read | https://docs.aws.amazon.com/emr/latest/ManagementGuide/managed-scaling-metrics.html |
| Haiku model ID and inference-profile ID | NOT verified | - |

## Consequence for the plan (BLOCKER for session 1 if the applied quota equals the default)
- B's maximum shape (4 x m5.xlarge = 16 vCPU) needs an applied `L-1216C47A` value of at least 16. At the default 5, only one m5.xlarge (4 vCPU) can run, so B cannot scale out at all, and A (single node, 4 vCPU) is the most that fits. Phase 14 ran single-node only, which is consistent with a low quota.
- Session 2 Fargate peak (fast and slow up to 4 tasks each at 0.25 vCPU = 2, worker up to 4 at 0.5 = 2, load client 1, loader 2 during the graph load) is about 7 vCPU if they overlap; the default 6 is enough only if the loader has finished before the experiments start.
- A quota increase request is an account change: the human decides and makes it; no agent does.

## K2 (review 1): what EMR managed-scaling limits count (orchestrator, 2026-11-17)
VERIFIED. https://docs.aws.amazon.com/emr/latest/APIReference/API_ComputeLimits.html ("Contents"): for `MinimumCapacityUnits` and `MaximumCapacityUnits`, "The limit only applies to the core and task nodes. The master node cannot be scaled after initial configuration." `MaximumCoreCapacityUnits` "is used to split capacity allocation between core and task nodes". https://docs.aws.amazon.com/emr/latest/ManagementGuide/managed-scaling-allocation-strategy.html ("Node allocation scenarios", Scenario 1, instance groups): to scale core nodes only, set the maximum core node and the On-Demand limit equal to the maximum boundary (example: Core 1 On-Demand, Min 1, Max 20, MaxOnDemand 20, MaxCore 20 scales 1 to 20 core instances).
Consequence: `infra/terraform/modules/emr/main.tf:74-77` (min = core + 1, max core = cap - 1 - tasks) counts the primary and is wrong. For B (primary 1, core 1, target 2 to 4 instances total, 8 to 16 vCPU) the policy should be Min 1, Max 3, MaxCore 3, MaxOnDemand 3 (core-only scaling, Scenario 1). With the current formula EMR would raise core to 2 at start (false scale-out) and could add a 4th unit only as a task node.

Cross-reference (R4): infra-engineer applied this rule in `infra/terraform/modules/emr/main.tf` (Min = core, Max = cap - 1, MaxCore = max(core, cap - 1 - tasks, 1), MaxOnDemand = cap - 1) and cites both pages there and in `elasticity-s.tfvars`. Rendered for `elasticity-s`: 1 / 3 / 3 / 3.

## R4 item 9: Step Functions definition validation (orchestrator, 2026-11-17)
VERIFIED that the API and CLI exist: `ValidateStateMachineDefinition` "Validates the syntax of a state machine definition ... without creating a state machine resource" (https://docs.aws.amazon.com/cli/latest/reference/stepfunctions/validate-state-machine-definition.html, https://docs.aws.amazon.com/step-functions/latest/apireference/API_ValidateStateMachineDefinition.html; search-result summaries). Parameter names in the README command not checked against the page.

## Applied quota (human, read-only `get-service-quota`, 2026-11-17, log `phase15-precheck-ec2-quota.log`)
`L-1216C47A  Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances  32.0  None  True`, exit 0. The applied value is 32 vCPU, so B's maximum of 16 vCPU (4 x m5.xlarge) fits. The session 1 quota blocker is closed.

`L-3032A538  Fargate On-Demand vCPU resource count  30.0  None  True`, exit 0 (log `phase15-precheck-fargate-quota.log`). The session 2 peak of about 7 vCPU fits with room to spare; the loader overlap concern is closed.

## Bedrock Haiku access (human, read-only, 2026-11-17, log `phase15-precheck-bedrock-availability.log`)
`get-foundation-model-availability --model-id anthropic.claude-haiku-4-5-20251001-v1:0` returned modelId `anthropic.claude-haiku-4-5-20251001-v1`, `agreementAvailability.status = NOT_AVAILABLE`, `authorizationStatus = AUTHORIZED`, `entitlementAvailability = AVAILABLE`, `regionAvailability = AVAILABLE`, exit 0. The model ID resolves.

Reading (https://docs.aws.amazon.com/bedrock/latest/userguide/model-access.html, "Manage model access using SDK and CLI", Step 4 and Step 2, and "Request access to models using the console"): `NOT_AVAILABLE` means "access doesn't exist". Claude Haiku 4.5 still needs the Anthropic first-time use case form ("required for earlier models, including ... Claude Haiku 4.5"), submitted once per account or organization, in the console via Model catalog or the Anthropic providers page. After submission the status can be `PENDING`, and invoking returns `AccessDeniedException` until it is `AVAILABLE`. Per P15-4 the human enables access in the console; no agent does. Re-run the same read-only command afterwards and expect `AVAILABLE` before session 2.

`list-inference-profiles --type-equals SYSTEM_DEFINED` (human, read-only, log `phase15-precheck-bedrock-profiles.log`, exit 0): `us.anthropic.claude-haiku-4-5-20251001-v1:0 ACTIVE` and `global.anthropic.claude-haiku-4-5-20251001-v1:0 ACTIVE` are listed, so the WO-1 inference-profile ID is verified. Also listed: `us.anthropic.claude-haiku-5-5 ACTIVE`. Per the same docs page, Anthropic models from Claude Opus 4.7 onward do not need the first-time use case form; whether Haiku 5.5 falls under that rule, and its price, were not read. Model choice is a configuration value (P15-4) and a human decision.
