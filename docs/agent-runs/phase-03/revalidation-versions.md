# Phase 03 revalidation versions

Recorded for `docs/agent-runs/phase-03/revalidation.md` step 4. Author: orchestrator (main Claude Code session).

## Toolchain

| Component | Version | Source of this value |
| --- | --- | --- |
| Claude Code | 2.1.319 | `claude --version` observed by the human architect earlier in this session. Not captured in the session transcript: see note below. |
| Orchestrator model | `claude-opus-5` (Opus 5) | Reported by the session environment to the orchestrator. |
| JVM | Eclipse Adoptium Java 21.0.13 | Observed in the `sbt clean check` banner during the gate run. |
| sbt | 1.11.7 | Observed in the `sbt clean check` banner during the gate run. |
| Scala | 3.3.7 | Observed in the compile output path `modules/core/target/scala-3.3.7/classes` during the gate run. |

Library versions are unchanged from the accepted Phase 2 build and are not restated here; `project/DependencyVersions.scala` is the authority, and its values belong to `dependency-updater`.

## Verification note

The orchestrator attempted `claude --version` and `java -version`. Both were refused by `.claude/hooks/guard-dangerous.sh` with "This shell command is outside the role's permitted commands," so the orchestrator observed no version string directly through the shell.

Three of the four values were nevertheless confirmed from observed output rather than attestation. The human-run `sbt clean check` recorded in `revalidation-results.md` printed `welcome to sbt 1.11.7 (Eclipse Adoptium Java 21.0.13)` and compiled to `modules/core/target/scala-3.3.7/classes`, which establishes the sbt, JVM and Scala versions as observed facts of the gate run. Note that the banner reads "Eclipse Adoptium Java 21.0.13"; the architect supplied "Temurin 21.0.13." These denote the same distribution, Adoptium being the project and Temurin the build, and the versions agree, so this is a naming difference and not a discrepancy.

The Claude Code version, 2.1.319, rests on the human architect's attestation that they ran `claude --version` and observed that output earlier in this session. This is a direct observation of the command rather than a recalled or assumed value, which is materially stronger than the other attested values started out as. It is recorded here on that basis.

It is still weaker evidence than the other three rows. The sbt, JVM and Scala versions appear in gate output preserved in the session transcript and can be reread by any later auditor; the Claude Code version cannot, because the observation happened outside the transcript and the orchestrator's own attempt to reproduce it was refused by the guard. An auditor must therefore take this one value on the architect's word.

Closing the gap fully requires either a ratified allowance for `claude --version` as a read-only command, or a human-run capture with the `!` prefix so the string enters the transcript alongside the gate output. Neither is a prerequisite for Phase 03 acceptance; the version is contextual metadata, not a gate.

The orchestrator model identifier is reported by the session environment and was not obtained by command.

## Delta from the original Phase 03 run

`docs/agent-runs/phase-03/versions.md` records the original Phase 03 orchestration as "Codex desktop orchestrator and separate Codex specialist sessions." This revalidation ran under the Claude Code orchestrator with the subagent routing defined in `docs/agents.md`. The JVM, sbt and Scala versions are unchanged across both runs, now confirmed from the gate banner rather than assumed; the orchestrator and specialist runtime is the material difference, and it is the reason `revalidation.md` exists.

Because the toolchain held constant while the orchestration runtime changed, the determinism evidence in `revalidation-results.md` — four sample digests matching the original run exactly — isolates the runtime change as the only varying factor.

No live AWS operations were authorized for this revalidation.
