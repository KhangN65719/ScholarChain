# Phase 17 prompt

## Scope note (human instruction and orchestrator reading; not part of the course text)

Phase 16 is accepted at 6072310 (`docs/agent-runs/phase-16/ratification.md`, "Required in Phase 17"). Goal of this phase: a complete, reproducible, honest submission by the deadline, Wednesday 2026-11-25 11 PM CST, with a final tag and commit pushed well before it. Plan to finish Tuesday 2026-11-24 evening; Wednesday is buffer only.

Submission rules from the instructor's post (newer than the course text; they win where they differ):
- The repository must be PUBLIC and clonable without authentication for the whole grading period (the course text says "private repository"; the post overrides it). It is public now.
- Only the last commit before the deadline counts; post-deadline commits need prior approval and may add only one file.
- Grading is by automated agents; no artifact may contain instructions aimed at graders (the injection test strings are documented in tests/scripts/README.md as adversarial inputs).
- Use the released autograder: https://github.com/rvarde2/CS441_Autograder_For_Students (V1 static, V2 and V3 AI judge). Results emailed by end of Friday 2026-11-20 earn bonus points.

Wave A (FIRST, finish by Friday 2026-11-20 midday so the human can run V1-V3 and email the CSVs that afternoon):
- Autograder V1 preview by Claude in chat on a fresh public clone at b5a55c3: 32 pass, 9 fail, 6 warn. Fix:
  - P01: every docs/agent-runs/phase-NN/ gets the course's evidence files (course section "Agent-generated deliverable evidence"): human-gate.md (what was reviewed, accepted or rejected, manual changes, remaining risk, accepted commit hash), commit.txt (accepted commit hash), agent-report.md (an index of that phase's existing reports), plus prompt.md where missing (phase-03). Build them ONLY from existing records (ratification.md, decisions.md, reports, git history); never invent; where a record does not exist, say so. Phase 00 commits exist but there is no phase-00 folder: decide with the human.
  - K01: fake keys in tests (tests/adversarial/P14LoaderRig.scala:22 and the other listed lines) rewritten so the secret patterns do not match (e.g. built at runtime), tests unchanged in meaning. K02 (history) is explained, not rewritten.
  - F05: .claude/settings.json hook commands: the checker misparses the escaped quotes; change the command form so it reads the path (factory-engineer owns .claude/**; route there).
  - R03: guard-dangerous.sh delegates to policy.py; make the guarded categories visible in the .sh (AWS delete/terminate, Terraform, Git, database, S3, Neptune, DynamoDB) without weakening policy.py (factory-engineer).
  - Explain in the README: F07 (`${VAR}` reference), K02, E01-E03 (archived reports kept as received), V01 and A01 warnings.
  - Then re-run V1 (Claude in chat can preview it read-only on a fresh clone), push, and give the human the exact V1, V2, V3 commands for his Mac (`--student` NetID; V2 and V3 use his Claude usage, about 0.30-0.50 USD each).

Wave B (deliverables, Saturday to Monday): everything in the course text below: README (all "README requirements" items, including "Reproducing the agentic workflow"), docs/architecture.md, docs/data-lineage.md, docs/threat-model.md, docs/service-limits.md, docs/runbook.md, docs/evaluation.md (exists; extend only if the course list needs more), reports/final-report.md (course "Final report structure"; it must distinguish agent-generated-accepted, agent-repaired, manually edited, and human-authored items), docs/demo-script.md (all 22 demonstration items), API route reference, deliverable index, release manifest. Replay every documented command; link every result to evidence; state what was not implemented.

Demo and AWS (human decision needed early, by Saturday): about 3.61 USD headroom to the 14 USD stop (spend limit 20). The demonstration list needs a live Neptune query, S3 Vectors query, API query, evidence and explain endpoints, CloudWatch dashboard, Terraform plan. Propose options with projected cost: (a) one short live demo session (deploy, smoke, record, teardown) sized to the headroom; P16-34 first: raise SCHOLARCHAIN_SERVICES_SLOW_DEADLINE_MILLIS to about 60000 for Haiku answers; (b) live laptop parts (S3 Vectors and Haiku from the laptop, local services) plus archived Phase 14-16 evidence for the AWS-only items, stating which items are live and which archived; (c) a mix. No AWS-changing command without the human's approval records, as always.

Final gate (Tuesday): fresh code-reviewer (Opus) full read-only repository review, test-engineer final run, the human's sbt clean check from a clean checkout, harness, Terraform validate, final autograder V1-V3 run, release manifest, final tag pushed.

Unchanged constraints: no edits to CLAUDE.md, docs/agents.md or .claude/** except through factory-engineer; agents never run AWS-changing commands; agents cannot run Python (the human side runs it, through Claude in chat where possible); read-only git, Claude in chat commits; do not weaken tests; one sbt at a time; account id masked everywhere; never fabricate reports, outputs or results (course integrity rules).


## Course text, verbatim (lines 2141,2183)

## Phase 17: produce documentation, final review, and release

Purpose:

Turn the implementation and experiments into a reproducible course submission.

Why:

A distributed system that only its authors can run is a temporary performance, not an engineering deliverable.

Use this work order:

```text
Use the documentation-engineer agent to create the complete ScholarChain documentation from verified repository state and archived experiment evidence.

Required documentation:
- README.md with student name, university email, project summary, architecture, prerequisites, exact build commands, exact local test commands, exact Terraform plan commands, deployment path, bounded data-run path, query examples, cleanup path, and known limitations.
- docs/architecture.md with build-time and runtime diagrams.
- docs/agents.md already owned by factory-engineer and referenced, not rewritten.
- docs/data-lineage.md tracing source dataset to Hadoop output, Spark output, stores, evidence, and answer.
- docs/threat-model.md covering prompt injection, data poisoning, credential exposure, over-permission, denial of service, destructive commands, and provenance tampering.
- docs/service-limits.md recording the current verified limits and Region availability used by the implementation.
- docs/runbook.md covering deployment, smoke tests, common failures, rollback, incident escalation, and cleanup.
- docs/evaluation.md with methods, raw-result pointers, tables, plots, uncertainty, and threats to validity.
- reports/final-report.md with problem, motivation, architecture, implementation, agentic workflow, experiments, results, costs, failures, lessons, and limitations.
- docs/demo-script.md for the final demonstration.
- An API specification or route reference.
- A complete deliverable index.

Rules:
- Replay commands before documenting them.
- Do not invent successful results.
- Link every result table to raw or derived evidence.
- State when an optional component was not implemented.
- Do not edit docs/agents.md or constitutional artifacts; route corrections to factory-engineer.

After documentation, use code-reviewer for a final read-only repository review and test-engineer for a final test run.
Create a release manifest containing commit SHA, image digests, Terraform plan checksum, dataset snapshots, run IDs, prompt versions, model versions, test counts, and artifact checksums.
```

Final gate:

- `sbt clean check` succeeds from a clean checkout.

## Course text, verbatim (lines 2759,2785)

## Required deliverables

The submission must include every deliverable below. A link in the README should point to each item.

| ID | Deliverable | Minimum contents |
|---|---|---|
| D01 | Private Git repository | Complete source, history, tags, and instructor access |
| D02 | Agentic constitution | `CLAUDE.md`, `docs/agents.md`, every `.claude/agents/*.md` file used in the project, settings, hooks, commands, MCP configuration, tool-version record, and instructions for invoking every agent |
| D03 | Agent run evidence | Phase prompts, final reports, human gates, repair records, and commit hashes |
| D04 | Deterministic build | Multi-project sbt build, format rules, pinned versions, and `sbt check` |
| D05 | Dataset package | Manifest, schemas, samples, license notes, checksums, and bounded retrieval scripts |
| D06 | Hadoop package | MapReduce jobs, tests, job contracts, counters, reject handling, and run scripts |
| D07 | Entity-resolution package | Features, policy, thresholds, LLM adjudication schema, gold set, and evaluation |
| D08 | Spark package | Graph builder, analytics, tests, output schemas, and bounded run results |
| D09 | Storage package | S3, Neptune, DynamoDB, S3 Vectors, and optional OpenSearch adapters and loaders |
| D10 | Runtime collaboration-analysis package | Query planning, graph and vector retrieval, fusion, synthesis, verification, prompts, and tests |
| D11 | Microservices package | REST services, API contract, health checks, authentication, and tests |
| D12 | Terraform package | Complete IaC, environment configuration, validation, plan, and security scan results |
| D13 | Reproducibility automation package | Source-controlled check, package, plan, deploy, smoke-test, bounded-pipeline, experiment, evidence-archive, and cleanup scripts plus optional CI workflow evidence |
| D14 | Deployment package | Release manifest, image digests, deployed revisions, smoke tests, and rollback evidence |
| D15 | Experiment package | Manifests, load generators, raw metrics, derived plots, elasticity evidence, and failure run |
| D16 | Evaluation package | Entity, graph, retrieval, answer, performance, factory, and reproducibility evaluations |
| D17 | Documentation package | README, architecture, data lineage, threat model, limits, runbook, evaluation, and API reference |
| D18 | Final report | Complete technical and experimental report in Markdown or instructor-approved format |
| D19 | Demonstration video | Narrated system demonstration with required evidence |
| D20 | Cleanup evidence | Resource inventory before and after cleanup, with retained-state explanation |


## Course text, verbatim (lines 2786,2816)

## Agent-generated deliverable evidence

For each phase, `docs/agent-runs/phase-NN/` must contain:

```text
prompt.md
agent-report.md
review-report.md when applicable
test-report.md when applicable
human-gate.md
repair-01.md when applicable
commit.txt
```

The human-gate file must state:

- What was reviewed.
- What was accepted or rejected.
- Any manual changes.
- Any remaining risk.
- The accepted commit hash.

Manual editing is not automatically prohibited, but it must be disclosed. The final report must distinguish:

- Agent-generated and accepted without code change.
- Agent-generated and repaired by another agent.
- Agent-generated and manually edited.
- Human-authored prompts, labels, decisions, and explanations.

A repository with no agent evidence does not satisfy the agentic workflow requirement, even when its code happens to work.


## Course text, verbatim (lines 2860,2891)

## Final report structure

The final report should use coherent paragraphs and include at least these sections:

1. Problem and motivation.
2. The three collaboration research questions and required metrics.
3. Dataset selection and licenses.
4. Agentic software factory architecture.
5. Authority and ownership design.
6. Human gates and repair loops.
7. Hadoop pipeline.
8. Entity-resolution method and evaluation.
9. Spark coauthorship graph, collaboration metrics, paths, communities, bridge analysis, and temporal analytics.
10. Storage architecture.
11. S3 Vectors and optional OpenSearch design.
12. Runtime collaboration-analysis agents.
13. Microservice architecture and API.
14. Terraform architecture.
15. Build, deployment, experiment, and reproduction automation.
16. Security and threat model.
17. Elasticity experiments.
18. Retrieval and answer evaluation.
19. Agentic factory evaluation.
20. Cost analysis.
21. Failures and incident analysis.
22. Threats to validity.
23. Limitations.
24. Lessons learned.
25. Reproduction and cleanup instructions.

The report must explain why each major technology is used. A catalog of AWS product descriptions is not an architecture argument.


## Course text, verbatim (lines 2892,2920)

## Demonstration requirements

The final demonstration must show the following without relying only on prerecorded screenshots:

1. Private repository structure.
2. Agent ownership map.
3. At least one agent role file.
4. A dangerous-command hook safely blocking a probe.
5. A test-stop hook or equivalent deterministic gate.
6. A phase report and repair record.
7. A clean `sbt check` result.
8. A dataset manifest and actual source samples.
9. Hadoop job execution evidence and counters.
10. Spark job execution evidence and graph outputs.
11. Neptune graph query.
12. DynamoDB run or query record.
13. S3 Vectors query returning source pointers.
14. A complete user query through the API.
15. Evidence and explain endpoints for the answer.
16. Claim verification behavior, including one unsupported claim test.
17. Reproduction scripts, agent replay instructions, and a clean-checkout bounded run.
18. Terraform plan or deployed-resource evidence.
19. CloudWatch elasticity dashboard.
20. Scale-out and scale-in evidence.
21. One failure and recovery example.
22. Cleanup or resource inventory evidence.

Students must be able to explain any agent-generated file selected by the instructor or TA.


## Course text, verbatim (lines 2978,3002)

## README requirements

The root `README.md` must include:

- Project title.
- Student or team identities.
- One-paragraph problem statement.
- Architecture summary.
- Agentic workflow summary.
- Required local software.
- AWS prerequisites.
- Environment variable names without secret values.
- Exact commands for formatting, compiling, testing, and packaging.
- Exact command for the bounded local or cloud sample.
- Exact Terraform format, validate, and plan commands.
- Exact source-controlled automation commands and required environment-variable names.
- Deployment and smoke-test commands.
- API query examples.
- Experiment reproduction commands.
- Final report and video links.
- Cleanup commands.
- Known limitations.
- Deliverable index.

The commands must work from the repository root unless the README explicitly changes directory.

## Course text, verbatim (lines 3004,3024)

## Submission logistics

Submission deadline: Wednesday, November 25, 2026 at 11PM CST.

The submission should include:

- URL of the private repository.
- Final tag and commit SHA created before the deadline.
- Every agent definition used by the project under `.claude/agents/`.
- `CLAUDE.md`, `docs/agents.md`, `.claude/settings.json`, all hooks, all project commands, and `.mcp.json` with no secret values.
- Detailed `Reproducing the agentic workflow` instructions in the README, including how to start Claude Code, validate the agent system, invoke each required agent through the orchestrator, replay at least one bounded phase, run test and review gates, and compare reproduced evidence with the archived run.
- Phase prompts, agent reports, test reports, review reports, human gates, repair records, and accepted commit hashes under `docs/agent-runs/`.
- Link to the demonstration video.
- Link to the deployed application when the deployment is still available.
- Link to the final report.
- Link to archived experiment results.
- Resource inventory and planned cleanup date.
- Any instructor-approved deviations.

Only the latest commit or submitted tag created before Wednesday, November 25, 2026 at 11PM CST will be graded unless the instructor states otherwise. A late commit does not become timely because an agent started generating it earlier.



## Appendix: course text for B1 (verbatim, course README "Agent submission and reproducibility requirements", added by Claude in chat 2026-11-19)

## Agent submission and reproducibility requirements

Every submitted agent is part of the graded artifact. The repository must contain the complete agent system used to generate and maintain the project, not a cleaned-up subset prepared after the work is finished.

The submission must include:

- Every file under `.claude/agents/` used during the project.
- `CLAUDE.md` and `docs/agents.md`.
- `.claude/settings.json` and every hook under `.claude/hooks/`.
- Every project command under `.claude/commands/`.
- `.mcp.json` with secret values omitted and environment-variable names preserved.
- The exact phase work order for every phase under `docs/agent-runs/phase-NN/prompt.md`.
- The final agent report for every phase.
- Reviewer and test-agent reports when they were required.
- Human ratification records and repair records.
- The commit hash accepted at each phase.
- A version record describing the Claude Code client version, model configuration when known, JVM, sbt, Scala, Terraform, AWS CLI, and other tools needed to replay the workflow.

The README must include an agent catalog with one row per submitted subagent. Each row must identify the agent name, purpose, owned artifact paths, tool fence, prerequisites, an exact example orchestrator instruction such as `Use the spark-engineer agent to ...`, the deterministic verification command, and the expected final-report fields. This catalog is required even when several agents are normally invoked only through phase prompts.

The root README must contain a subsection named `Reproducing the agentic workflow`. At minimum it must provide these steps in project-specific form:

```text
1. Clone the private repository and check out the submitted tag.
2. Install the documented Claude Code version and all local toolchain prerequisites.
3. Configure only the required environment variables and authenticated MCP or AWS read access. Never place secrets in the repository.
4. Start Claude Code from the repository root using the documented `claude` command.
5. Confirm that CLAUDE.md, docs/agents.md, .claude/settings.json, hooks, MCP configuration, and all submitted subagents are loaded or discoverable.
6. Run the repository's safe agent-validation command and the documented dangerous-command probe.
7. For a phase to be replayed, open a fresh Claude Code session or clear the existing session, copy the exact work order from docs/agent-runs/phase-NN/prompt.md, and instruct the orchestrator to use the named owning subagent.
8. After the owning subagent returns, invoke the test-engineer or code-reviewer exactly when the phase record requires it.
9. Route BLOCKED-ON findings through the orchestrator to the artifact owner rather than editing another owner's files directly.
10. Compare the reproduced report, tests, and generated diff with the archived phase evidence. Exact natural-language wording is not expected to be byte-identical, but required artifacts, contracts, tests, safety gates, and observable results must be reproducible.
11. Use a fresh session before replaying the next phase so that the ratified repository state, not stale conversation context, becomes the next phase's starting point.
```

The README must also contain at least one bounded reproduction exercise that a grader can execute without rebuilding the entire cloud environment. A suitable exercise is to run the agent validation, replay one small code-generation or test-hardening phase on a branch, run `sbt check`, and compare the resulting report to the archived phase evidence.

If a submitted agent requires an external tool, MCP server, account permission, environment variable, model endpoint, or local executable, the instructions must name it and explain how the grader verifies that it is available. Statements such as `run the agents` are insufficient.


## Appendix: course text for final report section 2 (verbatim, course README "research questions", added by Claude in chat 2026-11-19)

A successful ScholarChain deployment must answer a family of related research questions about scientific collaboration. The central research problem is:

```text
Which researchers have collaborated with the largest and most diverse sets of coauthors, how strongly connected are researchers through direct and indirect coauthorship relationships, and which researchers act as bridges between otherwise separate collaboration communities?
```

The project decomposes this problem into three related questions.

1. Which researchers have the broadest collaboration networks and which researchers publish papers with the largest author teams?

This question distinguishes collaboration breadth from simple publication count. A researcher who publishes one hundred papers with the same three collaborators is structurally different from a researcher who publishes thirty papers with eighty different collaborators. The system must therefore report distinct coauthor counts, repeated collaboration counts, publication counts, average team size, maximum team size, and the papers responsible for unusually large collaboration teams.

2. How connected are researchers through their coauthors?

This question treats the coauthorship data as a graph. The system must identify direct collaborators, compute shortest coauthorship paths between researchers, identify connected components, report component sizes, and characterize how many collaboration hops separate researchers when a path exists. Where practical, the project should estimate component-level quantities such as average path length or effective diameter using scalable methods rather than collecting the full graph on one machine.

3. Which researchers connect otherwise separate collaboration communities?

A researcher can have fewer direct coauthors than another researcher and still be more important structurally because the researcher lies on paths between otherwise weakly connected groups. The system must therefore discover collaboration communities and compute one or more bridge measures such as betweenness centrality or an explicitly documented scalable approximation, cross-community edge count, number of communities connected, participation coefficient, or related measures. K-core or coreness should also be used to distinguish peripheral high-degree researchers from researchers embedded in dense collaboration structures.

The final application must support time windows and enrichment filters such as research topic, venue, institution, and publication year when the linked datasets provide the necessary evidence. A representative query is:

```text
Which ten researchers have the broadest coauthorship networks in distributed systems since 2015, which of them have published papers with the largest author teams, and which of them are most important for connecting otherwise separate collaboration communities? Show direct collaboration counts, shortest-path or bridge evidence, representative papers, topics, institutions, and uncertainty in researcher identity resolution.
```

The answer must include:

- Ranked researchers for the metric requested by the user.
- Distinct coauthor counts and collaboration-strength measures.
- Publication count, average coauthors per paper, and maximum coauthors on one paper.
- The publication or publications that produced each reported maximum team size.
- Direct and indirect coauthorship paths when connectivity is requested.
- Connected-component and community membership information.
- Bridge metrics with their definitions and limitations.
- Temporal boundaries used for every metric.
- Topic, institution, venue, and representative-publication context when available.
- Stable identifiers and links back to source records.
