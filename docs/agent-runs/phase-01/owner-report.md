# Factory-engineer report

- Status: **COMPLETE for pre-ratification review**.
- Owner: factory-engineer. The documentation-engineer separately wrote the evidence guide. The orchestrator assembled this phase record.
- Work order: [Phase 1 work order](prompt.md), with the Phase 0 seed at `2313f5083b2794705d4e6c5a6d3fed59e028ff4b`.
- Files modified from the seed: none. All Phase 1 paths are additions. The complete diff is the set of repository additions relative to that seed, including the executable modes of the Bash entry points.
- Commands and observed results: `.claude/hooks/validate-agent-files.sh` and `.claude/hooks/probe-policy.sh` passed, with 18 roles, 18 ownership and route records, 5 commands, 2 parsed JSON files, 5 executable Bash entry points, and 52 simulated probes. See [orchestrator rerun](validation.md).
- Assumptions: use Codex with explicit Claude-file loading and an approved dependency ledger at `project/DependencyVersions.scala`. Build structure, approved check task, and runtime bindings are provided in their applicable phases.
- Risks and manual action: complete [fresh-session probes](restart-probes.md), verify runtime secret and tool fences, and verify effective credentials before enabling MCP. The scripts' local passes do not establish live Codex enforcement.
- Blocked-on owner and evidence: human architect for ratification and hook trust. Build engineer for actual `sbt fmt`, `sbt check`, and the test marker. Infrastructure and deployment owners for reviewed execution path. See [validation](validation.md).
- Next gate: human review of the complete diff and an explicit ratification decision. A fresh session and live probes follow before Phase 2.

## Authority by file

| File | Reason for authority |
|---|---|
| `CLAUDE.md` | Shared memory, module boundaries, build gate, safety and connector policy. |
| `docs/agents.md` | One writer per artifact, lifecycle routes and human gates. |
| `.claude/agents/build-engineer.md` | Build structure and packaging. |
| `.claude/agents/domain-engineer.md` | Shared contracts under core. |
| `.claude/agents/dataset-engineer.md` | Dataset manifests, samples and acquisition. |
| `.claude/agents/hadoop-engineer.md` | MapReduce jobs and their module tests. |
| `.claude/agents/entity-resolution-engineer.md` | Match policy and decisions. |
| `.claude/agents/spark-engineer.md` | Distributed graph analytics. |
| `.claude/agents/storage-engineer.md` | Storage adapters and loaders. |
| `.claude/agents/rag-engineer.md` | Retrieval, prompts and claim evidence. |
| `.claude/agents/service-engineer.md` | REST APIs and runtime composition. |
| `.claude/agents/test-engineer.md` | Independent tests in the reserved test tree. |
| `.claude/agents/code-reviewer.md` | Read-only independent review. |
| `.claude/agents/infra-engineer.md` | Terraform and script authorship, scoped inspection and planning. |
| `.claude/agents/deploy-engineer.md` | Approved operational execution and release records. |
| `.claude/agents/experiment-engineer.md` | Bounded experiments and evidence. |
| `.claude/agents/documentation-engineer.md` | User-facing documentation and evidence guide. |
| `.claude/agents/incident-responder.md` | Incident diagnosis and approved recovery. |
| `.claude/agents/dependency-updater.md` | Ledger version values and update reports. |
| `.claude/commands/deploy.md` | Deployment route, approval and smoke gates. |
| `.claude/commands/rollback.md` | Incident-led rollback route and verification gates. |
| `.claude/commands/incident.md` | Evidence preservation, diagnosis and escalation. |
| `.claude/commands/run-pipeline.md` | Bounded pipeline through an approved operator and script. |
| `.claude/commands/experiment.md` | Experiment route with cost and duration limits. |
| `.claude/settings.json` | Claude tool permissions, shell restrictions and event registrations. |
| `.claude/hooks/guard-dangerous.sh` | Pre-tool guard entry point. |
| `.claude/hooks/format-scala.sh` | Scala formatting entry point. |
| `.claude/hooks/verify-tests-ran.sh` | Test freshness entry point for completion. |
| `.claude/hooks/policy.py` | Implementation shared by the three lifecycle entry points. |
| `.claude/hooks/validate-agent-files.sh` | Static audit entry point. |
| `.claude/hooks/validate.py` | Ownership, routing, metadata and configuration validation. |
| `.claude/hooks/probe-policy.sh` | Safe policy replay entry point. |
| `.claude/hooks/probe_policy.py` | Simulated inputs and disposable source/marker fixtures. |
| `.mcp.json` | Proposed scoped GitHub, AWS and Terraform inspection or documentation connections. |
| `docs/agent-runs/README.md` | Evidence format, authored under the approved documentation ownership. |
| `docs/agent-runs/phase-01/prompt.md` | Preserved work order and human-approved inputs. |
| `docs/agent-runs/phase-01/owner-report.md` | Factory handoff, evidence pointers and authority rationale. |
| `docs/agent-runs/phase-01/validation.md` | Observed local checks and pending runtime checks. |
| `docs/agent-runs/phase-01/review.md` | Attributed independent verdict. |
| `docs/agent-runs/phase-01/repairs.md` | Findings routed to the owner and resulting changes. |
| `docs/agent-runs/phase-01/restart-probes.md` | Instructions and expected results after ratification. |
| `docs/agent-runs/phase-01/versions.md` | Tool versions observed during this phase. |
