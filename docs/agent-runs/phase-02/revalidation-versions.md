# Phase 02 revalidation versions

Recorded by the orchestrator for step 4 of `docs/agent-runs/phase-02/revalidation.md`. Date: November 5, 2026.

| Input | Version | How observed |
| --- | --- | --- |
| Claude Code | 2.1.319 | `claude --version` reported `2.1.319 (Claude Code)`, run by the human architect. The policy guard refuses that command to the orchestrator as outside the role's permitted commands. |
| Model, orchestrator session | Opus 5 (`claude-opus-5`) | Session environment. |
| Model, `code-reviewer` subagent | Inherited from the orchestrator session unless the role definition overrides it; not separately observed. |
| JVM | Eclipse Adoptium Java 21.0.13 | Observed in the `sbt check` welcome line recorded in `revalidation-results.md`. Matches the Phase 02 verification JVM in `docs/agent-runs/phase-02/versions.md`. |
| sbt | 1.11.7 | Observed in the `sbt check` welcome line recorded in `revalidation-results.md`. Matches the pin in `project/build.properties`. |
| Scala | 3.3.7 | Observed in the `sbt clean check` compile output paths `modules/core/target/scala-3.3.7/classes` and `.../test-classes`, recorded in `revalidation-results.md`. Matches `DependencyVersions.scala3` in `project/DependencyVersions.scala`. |
| Git | 2.56.0 | Carried forward from the Phase 01 orchestrator-update record; not re-observed. |
| Platform | macOS on Apple silicon (Darwin 27.0.0) | Session environment. |

## Notes

- The orchestrator did not run `sbt check`. The human architect ran it with the `!` prefix, which `CLAUDE.md` records as the accepted workflow for the test gate; an agent cannot run it inside the shell sandbox, and that is an accepted open gate. The command and its full output are recorded in `revalidation-results.md`.
- A second, clean-state run (`sbt clean check`) followed and is recorded in the same file. The sbt, JVM and Scala versions above are all observed from build output rather than stated.
- The Claude Code version is 2.1.319, not the 2.1.318 recorded in `docs/agent-runs/phase-01/orchestrator-update/versions.md`. The orchestrator initially carried that value forward and marked it unconfirmed; the human-run `claude --version` corrected it. The Phase 01 record is left unchanged as historical evidence of the version at that time, and this entry supersedes it for Phase 02 revalidation only. Whether the runtime was upgraded between the two records, or the Phase 01 value was itself inaccurate, is not determined here.
- Terraform, AWS CLI and cloud credentials were not used. No MCP server was contacted. Three configured MCP servers (`aws-inspection`, `github`, `terraform-registry`) failed to connect at session start and were not needed for this work.
