# Orchestrator repair versions

- Orchestrator: Claude Code 2.1.318 (`claude --version`, reported by the human architect).
- Model, review and repair sessions: Opus 5.5 (`claude-opus-5-5`, 1M-token context, as shown by `/context`).
- Model, restart-probe sessions: Opus 5, as shown in the Claude Code status bar.
- JVM: Eclipse Adoptium (Temurin) OpenJDK 21.0.13, 2026-10-20 LTS, selected with `JAVA_HOME=$(/usr/libexec/java_home -v 21)`.
- sbt: 1.11.7 (project, from `project/build.properties`), launched by sbt runner 2.0.12 (Homebrew).
- Scala: 3.3.7 (from the sbt build output).
- Git: 2.56.0.
- Platform: macOS on Apple silicon.
- Terraform and AWS CLI: not used in this repair.
