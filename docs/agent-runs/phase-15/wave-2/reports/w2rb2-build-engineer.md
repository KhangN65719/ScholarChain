# Wave 2 repair batch 2, build-engineer m1 and m6 (received 2026-11-17; author: build-engineer subagent; the stop hook replaced the detailed report, so the file content below was read by the orchestrator)

Status: COMPLETE, no sbt (slot held by storage-engineer; no Scala or build.sbt change), no Docker run.
- m1: `.gitignore` gets `__pycache__/` and `*.pyc` entries.
- m6: `packaging/load-client/Dockerfile` declares `ARG PYTHON_BASE` with no default (lines 1-3) and fails the build at line 4-5 unless the value ends `@sha256:<64 hex>` ("PYTHON_BASE must be pinned by digest ..."). The check runs after `FROM`, so a tag-only base is pulled before the build fails; the §4 command (`python:3.12-slim-bookworm@sha256:<DIGEST>`) stays valid.
Open: the §4 Docker build (human) exercises it; marker stale until the next sbt run.
