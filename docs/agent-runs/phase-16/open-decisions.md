# Phase 16 open decisions

Recorded by the orchestrator. Each item waits for the human.

| ID | Raised | Question | Options | Orchestrator recommendation |
|---|---|---|---|---|
| OD-2 | 2026-11-18, re-review 5 NF-1 | `experiments/benchmark/README.md:64` says refused options print `BENCHMARK ERROR`; a flag with no value prints only `Usage` (exit 2, nothing written). | (a) accept as open; (b) fold a one-line fix into Wave 2 slice 2 (experiment-engineer already edits BenchmarkGen's neighbours). | (b): no extra round; reviewed with slice 2. |
| OD-1 | 2026-11-17, WO-6 report | `scripts/smoke-test.sh` now fails on an abstained answer, and its default `--question` ("Which authors collaborated most between 2015 and 2020?") abstained with `researcher-count` in SMOKE1. | (a) infra-engineer changes the default to a question the planner answers (taken from a passing benchmark question); (b) the default is removed and `--question` becomes required. | (b): no smoke runs in Phase 16, and a required argument cannot silently drift from the planner. RESOLVED 2026-11-18: (b), decision P16-7. |
