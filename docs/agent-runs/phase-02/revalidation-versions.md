# Phase 02 revalidation versions

Recorded by the orchestrator for step 4 of `docs/agent-runs/phase-02/revalidation.md`. Date: November 5, 2026.

| Input | Version | How observed |
| --- | --- | --- |
| Claude Code | 2.1.318 | Carried forward from `docs/agent-runs/phase-01/orchestrator-update/versions.md`, where the human architect reported `claude --version`. NOT re-observed in this session: the policy guard refuses `claude --version` as outside the role's permitted commands. Treat as unconfirmed until the human reports it. |
| Model, orchestrator session | Opus 5 (`claude-opus-5`) | Session environment. |
| Model, `code-reviewer` subagent | Inherited from the orchestrator session unless the role definition overrides it; not separately observed. |
| JVM | Eclipse Adoptium Java 21.0.13 | Observed in the `sbt check` welcome line recorded in `revalidation-results.md`. Matches the Phase 02 verification JVM in `docs/agent-runs/phase-02/versions.md`. |
| sbt | 1.11.7 | Observed in the `sbt check` welcome line recorded in `revalidation-results.md`. Matches the pin in `project/build.properties`. |
| Scala | 3.3.7 | Supplied by the human architect and matching `DependencyVersions.scala3` in `project/DependencyVersions.scala`. The `sbt check` run was incremental and printed no compiler line, so the compiler version was not re-observed in its output. |
| Git | 2.56.0 | Carried forward from the Phase 01 orchestrator-update record; not re-observed. |
| Platform | macOS on Apple silicon (Darwin 27.0.0) | Session environment. |

## Notes

- The orchestrator did not run `sbt check`. The human architect ran it with the `!` prefix, which `CLAUDE.md` records as the accepted workflow for the test gate; an agent cannot run it inside the shell sandbox, and that is an accepted open gate. The command and its full output are recorded in `revalidation-results.md`.
- The sbt and JVM versions are now observed from that run's output rather than stated. The Scala version remains a pin match, not an observation, because the run was incremental.
- Terraform, AWS CLI and cloud credentials were not used. No MCP server was contacted. Three configured MCP servers (`aws-inspection`, `github`, `terraform-registry`) failed to connect at session start and were not needed for this work.
