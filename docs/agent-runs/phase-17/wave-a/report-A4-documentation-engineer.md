# WO-A4 report (documentation-engineer): what reached the orchestrator

Recorded by the orchestrator, 2026-11-19. Twice, only the agent's closing Stop-gate note reached the orchestrator, so its full course-field report is NOT RECORDED here. The facts below come from the orchestrator's Read of `README.md`.

- Edit observed: `README.md:5-17` is a new section, "Autograder notes", placed after the first paragraph. It has bullets for F07, K02, K03, E01 to E03, V01 (5 lines) and A01. No other part of README.md was checked for changes. code-reviewer compares it against `git diff` in A5.
- Follow-up needed: the K02 bullet (line 10) says the P14LoaderRig rewrite is "planned, not yet done". It must be updated once WO-A3c lands. This goes in the A5 repair round, owned by documentation-engineer.
- The agent marks A01's per-call-site table coverage as NOT VERIFIED (line 17).
- sbt: none (the agent has no shell tool). The Stop gate is open and non-blocking for this docs-only edit.
