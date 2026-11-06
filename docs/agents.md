# ScholarChain authority and routing

The approved ownership matrix controls Phase 1. A path or artifact class has one writer. Future directories named here reserve ownership. Their creation belongs to the applicable later work order.

## Ownership matrix

| Agent | Exclusive owned outputs | Excluded work | Primary gate |
|---|---|---|---|
| `factory-engineer` | `CLAUDE.md`, `docs/agents.md`, `.claude/agents/**`, `.claude/hooks/**`, `.claude/commands/**`, `.claude/settings.json`, `.mcp.json` | Application, data, build, deployment and infrastructure | Constitution audit, independent review and human ratification |
| `build-engineer` | build.sbt; project/** excluding ledger version values; .scalafmt.conf; .gitignore; compose.yaml; Dockerfile and packaging/**; empty skeleton roots | Application source, Terraform, deployment, and maintenance of approved version values | Run sbt Test/compile and sbt check. Report dependency pins, module graph and environment inputs. |
| `domain-engineer` | modules/core/** | Dataset acquisition, distributed algorithms, storage, API routes and infrastructure | Run contract tests and sbt check. Report schema versions, wire examples and validation behavior. |
| `dataset-engineer` | datasets/** | Hadoop or Spark implementation and entity-match policy | Validate the manifest and replay a bounded sample. Report sources, checksums, licenses, size bounds and linkage keys. |
| `hadoop-engineer` | modules/hadoop/** | Spark analytics, storage clients and service APIs | Run sbt check and local sample jobs. Report counters, rejected records and output invariants. |
| `entity-resolution-engineer` | modules/entity-resolution/** | Graph analytics, API synthesis and infrastructure | Run sbt check and gold-set evaluation. Report uncertainty, conflicting evidence and threshold tradeoffs. |
| `spark-engineer` | modules/spark/** | Hadoop parsers, runtime HTTP APIs and Terraform | Run sbt check and a bounded Spark sample. Report window, pair, metric and path invariants. |
| `storage-engineer` | modules/storage/** | Terraform, response synthesis and HTTP routes | Run contract tests and sbt check. Report bounded load results, retry behavior and provenance preservation. |
| `rag-engineer` | modules/rag/** | HTTP transport, deployment and dataset acquisition | Run sbt check and retrieval and groundedness evaluations. Report unsupported claims and provenance gaps. |
| `service-engineer` | modules/services/** | Distributed algorithms, Terraform, build configuration and constitutional files | Run API tests and sbt check. Report endpoints, response shapes and dependency behavior. |
| `test-engineer` | tests/** | Production code and module-owned tests | Run targeted tests and sbt check. Report exact pass and fail results and route production fixes to owners. |
| `code-reviewer` | Review verdict delivered to the orchestrator | Any repository write, shell execution or self-approval | Return APPROVE or REQUEST CHANGES with exact file locations and evidence. Ask the owner to run missing checks and report them as pending. |
| `infra-engineer` | infra/terraform/**; scripts/** excluding datasets/scripts; .github/workflows/** | Apply, deployment, cloud-console mutations and application source | Run format, validation, plan, syntax and security checks. Report plan digest, cost, IAM changes and approval gates. |
| `deploy-engineer` | docs/releases/** | Terraform and script authorship, direct cloud mutation, unapproved environments and cleanup | Verify exact script and digest, clean tree, immutable image, plan, account, region, limits and approval before execution. Record smoke evidence and rollback gate. |
| `experiment-engineer` | experiments/**; load-tests/** | Production algorithm changes and unapproved scaling | Check cost, duration, load bounds and stop conditions before execution. Archive immutable raw results with run identifiers. |
| `documentation-engineer` | README.md; docs/** excluding agents.md, incidents/**, releases/**, dependencies/** and agent-runs/**; docs/agent-runs/README.md | Implementation, incident or release authorship, and editing ratification decisions | Verify links and replay permitted commands. Cite observed evidence and leave unknown results open. |
| `incident-responder` | docs/incidents/** | Schema changes, Terraform authorship and destructive data repair | Preserve evidence, document the blast radius and verify recovery with smoke checks. Escalate security, data loss and destructive actions. |
| `dependency-updater` | Version values in project/DependencyVersions.scala; docs/dependencies/** | Build structure, application features and unilateral major upgrades | Run sbt check and report exact changes, upstream evidence and rollback details. Ask for approval before a major upgrade. |

### Boundary rules

- The factory owns the versioned control-plane files listed above. Generated test-marker state at `.claude/.last-test-run` is produced by the build's successful check. Other unassigned paths under `.claude/` require human resolution.
- Module owners own their module tests. Independent adversarial and cross-module tests go in `tests/`. Empty skeleton directories in Phase 2 confer no write authority over later files.
- Build structure includes the declaration of `project/DependencyVersions.scala` and references to its keys. Version values in that file belong to dependency-updater. Build-engineer may scaffold it with values explicitly supplied and approved by the human. The Phase 2 build must use this ledger.
- The documentation-engineer owns the evidence guide at `docs/agent-runs/README.md`. The orchestrator archives work orders, reports, review verdicts, and other phase evidence in `docs/agent-runs/phase-NN/`. The human owns ratification decisions and commit records. Preserve each report as received with its author identified.
- Deploy-engineer owns release records. Incident-responder owns incident reports. A reviewer returns a verdict to the orchestrator. The orchestrator records the verdict with its attribution.
- `scripts/` is the infrastructure script root. Dataset retrieval and sampling scripts live under `datasets/scripts/`. Source-owned tools under a module stay with that module.
- For a new path, resolve its classification here before writing. Any unresolved collision or unowned artifact blocks the request until the human ratifies an assignment.

## Lifecycle routing

| Lifecycle | Route |
|---|---|
| Constitution and controls | `factory-engineer` |
| Build | `build-engineer` |
| Domain contracts | `domain-engineer` |
| Datasets | `dataset-engineer` |
| MapReduce | `hadoop-engineer` |
| Entity resolution | `entity-resolution-engineer` |
| Graph analytics | `spark-engineer` |
| Storage | `storage-engineer` |
| Retrieval and grounded synthesis | `rag-engineer` |
| REST services | `service-engineer` |
| Independent tests | `test-engineer` |
| Independent review | `code-reviewer` |
| Infrastructure and operations scripts | `infra-engineer` |
| Deployment and bounded pipeline execution | `deploy-engineer` |
| Experiments | `experiment-engineer` |
| Documentation | `documentation-engineer` |
| Incident response | `incident-responder` |
| Dependency maintenance | `dependency-updater` |

The orchestrator owns dispatch and repair routing. Subagents never invoke one another. Delegate with the exact work order, role file, shared project memory, ownership map and only necessary prior evidence. Use a fresh specialist context. A specialist that encounters an ownership or prerequisite blocker reports `BLOCKED-ON` with evidence and the responsible owner. Route repairs to that owner and re-run independent checks before retrying the blocked work.

## Approval and escalation

| Change or action | Required gate |
|---|---|
| Constitutional files, tools, ownership or autonomy | Factory draft, independent read-only review, human ratification, then a fresh orchestrator session before use. |
| Ambiguous ownership | Pause the conflicting operation. Human settles the exact boundary before the factory updates it. |
| Terraform apply, live deployment and environment changes | Human approves exact plan digest, script revision, AWS account, region, cost bounds and rollback. Infra authors hand off to deploy-engineer. Ordinary agent shell calls to apply or destroy are blocked. The approved human-controlled execution path must enforce the recorded approval. |
| Data deletion, destructive cleanup and irreversible recovery | Human approves exact target, scope, evidence, backup and recovery path and executes the operation through a reviewed runbook. |
| Security, IAM, secrets and externally exposed endpoints | Human approves privilege diff, principals, blast radius and rollback. Route authorship to the appropriate owner. |
| Costly operations and scaling | Human approves duration, upper bounds, cost, monitoring and automatic stop criteria before dispatch. |
| Incident recovery and rollback | Incident-responder diagnoses and proposes recovery. Human approves the reviewed script, target, bounds and verification. |

The main Claude Code session is the orchestrator. Before dispatch it reads `CLAUDE.md`, this map and the assigned role file, then delegates to that subagent with the work order and necessary evidence. Treat declared tools as the maximum role authority. If the runtime does not enforce a particular tool or path boundary, record it as open. Nothing in the runtime stops the main session from performing a specialist's work inline, because its events carry no `agent_type` and the guard allows its non-constitutional edits; this rule stays an open, behavior-only control. A passing restart probe 8 is recorded as behavioral evidence only; the gate closes only through a ratified runtime control or an explicit human acceptance recorded in `docs/agent-runs/phase-01/orchestrator-update/ratification.md`. Restart probe 2 verifies the reviewer tool-list fence (Read, Grep and Glob only). The guard's reviewer branches are defense in depth that the reviewer cannot reach while it has no write or shell tools. Restart probe 9 verifies that `agent_type` reaches the hook. Keep cloud write credentials out of ordinary authoring sessions. Passing a direct hook probe demonstrates script behavior; verify automatic invocation separately in a fresh Claude Code session.

A work order can narrow authority. Only a ratified constitutional revision can widen it. Independent reviews never fix their own findings or approve their own work.
