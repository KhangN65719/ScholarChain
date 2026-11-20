# Phase 17 Wave B work orders (first dispatch), issued 2026-11-19

Common rules: read `CLAUDE.md`, `docs/agents.md`, your role file, `../plan.md`, `../decisions.md` (P17-1 to P17-18) and `../prompt.md`, including "Appendix: course text for B1". No AWS, no Python execution, no commit. Write only your write set; anything else is `BLOCKED-ON`. Never invent a result, version or command output: absent means NOT RECORDED or PENDING (name who supplies it). No text addressed to graders, reviewers or AI agents. No account id, bucket name or ARN of the real account. Make your FINAL message the full course-field report (STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE).

## WO-B1 documentation-engineer: README.md (full rewrite)

Write set: `README.md`.

Follow the course "README requirements" list in `../prompt.md` and the appendix **exactly**. V3 marked FACTORY-09 and FACTORY-10 missing for the reasons quoted in `../human-commands.md`.

- **Agent catalog (FACTORY-09).** One row per file in `.claude/agents/` (18 agents). Columns: agent name, purpose, owned artifact paths (verbatim from `docs/agents.md`), tool fence (verbatim from the agent file's `tools:`), prerequisites (external tools, MCP servers, permissions, env-var names, model endpoints, local executables; "none beyond the base toolchain" where true), an exact example orchestrator instruction (`Use the <agent> agent to ...`, taken from a real archived work order where one exists, with that work order cited), the deterministic verification command (from the agent's primary gate in `docs/agents.md`, for example `sbt quick` or `sbt check`; for code-reviewer, "none: returns APPROVE or REQUEST CHANGES"), and the expected final-report fields (the 14 course fields). Then the rule for agents that cannot run commands: the human or orchestrator replays them.
- **"Reproducing the agentic workflow" (FACTORY-10).** Replace the placeholder. Give all 11 appendix steps in project-specific form:
  - the exact `claude` start command;
  - the safe validation command `.claude/hooks/validate-agent-files.sh`;
  - the dangerous-command probe `bash .claude/hooks/guard-dangerous.sh --list-categories` and `bash tests/scripts/hooks-guard.sh`, plus one live refusal in a session;
  - fresh session per phase;
  - BLOCKED-ON routing;
  - comparison with `docs/agent-runs/phase-NN/`.
- **Version record.** Add a "Version record" subsection with the Claude Code client version, model configuration, JVM, sbt, Scala, Terraform, AWS CLI, Python, Docker and shellcheck. Take each value only from a committed record (`docs/agent-runs/phase-NN/versions.md`, `build.sbt`, `project/build.properties`, `project/DependencyVersions.scala`, Terraform `required_version`), cited by file. Mark every value not recorded as PENDING (human H-7b, `claude --version` and similar).
- **Bounded reproduction exercise.** Pick one small, committed, test-hardening or code-generation work order from the archive (cite the file) that a grader can replay on a branch without AWS. Write the exact steps: checkout a branch; fresh session; paste the work order; owning agent; `sbt check`; compare with the archived report. Mark it NOT REPLAYED until H-8.
- **External prerequisites.** For each one an agent needs, name how a grader verifies it is available.
- **Everything else from "README requirements":**
  - title; identities (PENDING H-7: name and university email; do not invent)
  - problem statement; architecture summary linking `docs/architecture.md`
  - local software; AWS prerequisites; env-var names only
  - exact format, compile, test and package commands
  - the bounded local sample command (PENDING WO-B7)
  - Terraform fmt, validate and plan; automation commands; deploy and smoke
  - API examples linking `docs/api.md`; experiment reproduction
  - final report and video links (PENDING); cleanup (D7: full cleanup after grades, mid-December 2026)
  - known limitations (from `experiments/LIMITATIONS.md` and `docs/evaluation.md` section 5)
  - the deliverable index D01 to D20, each linked to a path or marked PENDING with its owner
- **Keep and preserve.** Keep the "Autograder notes" section as is. Preserve the true facts from the current README's Phase 13 to 15 sections, updated: harness `pass=1949`, Billing from the phase-16 ratification.
- Commands not replayed are marked NOT REPLAYED (H-8).

## WO-B5 documentation-engineer: reports/final-report.md (issued 2026-11-19, after `4341dbb`)

Write set: `reports/final-report.md` (owned by documentation-engineer since P17-3).

- **Structure:** the 25 sections of the course "Final report structure" (`../prompt.md`), in coherent paragraphs. Each argues why each major technology is used, from repository evidence. It is not a product catalogue.
- **Sources:**
  - experiments, evaluation and costs: `experiments/RESULTS.md`, `experiments/EVALUATION.md`, `experiments/LIMITATIONS.md`, `docs/evaluation.md`, `docs/experiments.md`
  - architecture: the committed Wave B docs
  - per-phase facts: `docs/agent-runs/phase-NN/` ratifications, human gates and reviews
  - releases and billing: `docs/releases/`, and the Billing readings in the phase 15 to 17 records (P16-43, P17-17)
- **Every number** cites its file; any not in a file is NOT DERIVED. Keep negative results (`docs/evaluation.md` section 5). State optional components not implemented (OpenSearch; separate vector, rank and synthesis services).
- **Agentic factory sections** (4, 5, 6, 19) must distinguish the four course classes:
  - agent-generated and accepted without code change
  - agent-generated and repaired by another agent (cite repair records)
  - agent-generated and manually edited, at least: Phase 4 repair 8 (Codex, `phase-04/ratification.md`); the Phase 0 seed edit (`phase-01/orchestrator-update/ratification.md`); P17-18 (`c830b90`); P17-24 (`docs/api.md:185`, `docs/service-limits.md:120`); and any other disclosed in ratifications
  - human-authored prompts, labels, decisions and explanations (phase prompts, gold labels, the 8 free-form benchmark questions, the human rating, ratifications)
  
  The human's own list is PENDING H-13; mark that and do not invent entries.
- **Demo, live-session and final-gate results** (D1 session, video, H-14, final tag) are PENDING. Leave a clearly marked placeholder sentence for each, to be filled after the event.
- No text addressed to graders or agents; no account id; identity only as in README.

## WO-B4 documentation-engineer: docs/demo-script.md (issued 2026-11-19, D1-bis P17-26)

Write set: `docs/demo-script.md`.

A narrated demonstration script for the human's video (H-12), covering all 22 items of the course "Demonstration requirements" (`../prompt.md`), in order.

**Per item**, give:
- the label, one of:
  - **LIVE (laptop)**: run on the human's laptop during recording.
  - **LIVE (AWS, read-only)**: a free read-only console or CLI view; name the exact page or command, and say that no AWS-changing command is run.
  - **ARCHIVED**: shown from committed evidence, with the exact file path.
- the exact command or file to show (from committed sources only: README, runbook, `scripts/README.md`, `experiments/**`, `docs/releases/**`, `docs/agent-runs/**`);
- what the viewer should see;
- a one- or two-sentence narration line;
- NOT REPLAYED until the human records.

**Basis: option (iii)** (P17-26, laptop plus archived evidence).
- Laptop live: items 1 to 10, 16, 17 and 21.
  - 4: `bash .claude/hooks/guard-dangerous.sh --list-categories` and one refused command in a session.
  - 5: the Stop gate.
  - 7: `sbt check` or the recorded clean check.
  - 9 and 10: per P17-20 and P17-21; the test-based samples and `sbt check`.
  - 16: claim verification with an unsupported-claim test, a named test suite.
  - 17: the README bounded exercise.
  - 21: Phase 15 F2 evidence plus a local refusal or recovery.
- Laptop runs against AWS data services, if the human chooses: S3 Vectors and Haiku through the Phase 16 evaluation entry points (`experiments/g-retrieval`, `experiments/h-answers` READMEs), each run needing its approval record; otherwise ARCHIVED from G and H results.
- AWS read-only: 18 (`scripts/plan.sh`, read-only), 19 (the CloudWatch dashboard in the console, Phase 15 history), 22 (resource inventory, read-only CLI), 12 (a DynamoDB read, if the human chooses; otherwise archived).
- Archived: 13 to 15 on the deployed API (Phase 14 to 16 evidence) and 20 (scale-out and scale-in, Phase 15 B, C and D). The local services API over the demo world may be shown live, stating that it is demo data.
- **Item 11 (Neptune graph query) gets both paths:**
  - (iii) ARCHIVED: the Phase 14 and 15 load and query evidence.
  - (i) OPTIONAL LIVE, decided Saturday after a Billing reading: a live Neptune load and query with no live Haiku. Reference the sequence in `report-B8-infra-engineer.md`, with every step human-run with approval records and session-down after.

**Also include:**
- a prerequisites and setup section (what to have open, env-var names only, and that no secret is shown on screen);
- a time budget per item;
- a final checklist mapping each item to the README deliverable index.

**Rules:**
- Do not claim any live result.
- No account id, bucket or ARN on screen; tell the human to mask the account id.
- No text addressed to graders or agents.

## WO-B13 dataset-engineer: DATA-05 schema versions and field tables (issued 2026-11-19, P17-29)

Write set: `datasets/schemas/*.md` only. Do not touch `datasets/dataset-manifest.yaml` or `datasets/scripts/**`: DATA-02 is skipped, because `datasets.py` rejects unknown manifest fields.

For each of the four schema notes (DBLP, OpenAlex, Crossref, ORCID):
- **Schema version line.** Add one. Use the version the code or manifest already records (for example a schema or contract version in `datasets/dataset-manifest.yaml`, `modules/core` contracts, or the dataset README) and cite it. If none exists, state the version as the note's own document version, `1`, dated 2026-11-19, and say it is a documentation version, not an upstream one.
- **Field table.** Add a table with the columns field, type, meaning, and used for (join key, provenance, and so on). Build it only from fields the note, the samples (`datasets/samples/`) or the parsers (`modules/hadoop/.../SourceNormalizer`, `DblpParser` and similar) actually show, and cite the source per row or per table. Mark a type or meaning that is not evident as UNVERIFIED.

Keep the existing prose. Check whether any test reads these files (for example `datasets/scripts/test_*.py`). If one does, preserve every string it asserts and report the test. You cannot run Python: list the command the human runs (the `datasets` validate and the unit tests).

## WO-B14 hadoop-engineer: HADOOP-11 job contracts (issued 2026-11-19, P17-29)

Write set: `modules/hadoop/JOB-CONTRACTS.md` (new). No code change, so no sbt is required.

Write one section per MapReduce job and stage (DBLP extraction, source normalisation, publication team, team expansion, blocking, candidate feature, graph export, and any others in `modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/`). Each section gives, with source `file:line` citations:
- input and output paths and the stage layout (`stage=.../v1`), and the S3 prefix pattern used on EMR (from `PipelineDriver` and `scripts/emr/run-s-pipeline.sh`, read-only);
- mapper and reducer classes with their key and value types;
- partitioner and number of reducers, and the bounds (pair cap, block cap);
- output format and codec;
- counters;
- reject handling;
- the test suites and fixtures that cover the job (from the B7a report).

Not evident from the code: UNVERIFIED. Also fix the stale scaladoc at `PipelineDriver.scala:28-29` only if the human approves a code change. Do not change it now; list it under RISKS.

## WO-B5b documentation-engineer: final report section 2 (issued 2026-11-19, P17-29)

Write set: `reports/final-report.md`, section 2 only.
- Rewrite section 2 ("The three collaboration research questions and required metrics") from `../prompt.md` "Appendix: course text for final report section 2", quoting the questions verbatim, and map each required metric to where the project computes it and where results are recorded (cite files). A metric not computed or not reported is stated as such.
- Also add the P17-29 manual change (Claude in chat, `final-report.md:59-60`) as a class-3 entry, or extend M5 if it is the same kind; cite P17-29.

Change nothing else.

## WO-B7a hadoop-engineer, WO-B7b spark-engineer: local bounded sample command (read-only report)

Write set: none. Report the exact existing command that runs your module's bounded local sample from a clean checkout without AWS (inputs under `datasets/`, outputs, expected counters or invariants, approximate run time), citing source files. If agents cannot run it (agents may run only `sbt check`, `sbt quick` and `sbt Test/compile`), say so; the human replays it at H-8. If no such command exists, say `BLOCKED-ON` and name what is missing. Do not change code.

## WO-B12a code-reviewer: review of the held Wave B docs

Read-only. Review `docs/architecture.md`, `docs/data-lineage.md`, `docs/api.md` (WO-B2) and `docs/threat-model.md`, `docs/service-limits.md`, `docs/runbook.md` (WO-B3) against their work orders (`../wave-a/work-orders.md`) and reports (`../wave-a/report-B2-*`, `report-B3-*`). Check:
- every claim and cited `file:line` against the repository;
- no invented result; NOT REPLAYED, UNVERIFIED and NOT RECORDED used honestly;
- relative links resolve;
- no account id, bucket name or ARN;
- no grader-directed text;
- the course content per document (`../prompt.md`): diagrams; lineage from source to answer; the seven threat classes; limits and Region; runbook sections; the API route table.

Batch all findings by owner. FINAL message: APPROVE or REQUEST CHANGES with `file:line` evidence.
