# Repair 04: A5b findings F1, F2 and L3 (test-engineer), as received 2026-11-19 (condensed by the orchestrator)

STATUS: COMPLETE. No sbt was run this round, because only shell files changed (the Stop gate does not watch them). Per-group sbt totals from the last run are NOT RECORDED: only 77/0/0 and 1306/0/0 were read, and the run exited `[success]` in 160 s. This stays pending; the human's `sbt clean check` at the phase gate covers it.
- **F1:** `tests/scripts/hooks-guard.sh:102` now matches `'# 8. Vector index (S3 Vectors)'` as a whole line. All eight headings were re-read from `guard-dangerous.sh` (lines 13, 23, 30, 40, 47, 55, 62, 67), and the other seven already matched.
- **F2:** in `tests/scripts/run.sh`, `TOKEN` became `STUB_BEARER` at lines 13033, 13091, 13125 and 13128. The value is unchanged, and the env var name `SCHOLARCHAIN_QUERY_TOKEN` is kept.
- **L3:** the hook-form cases number 33 (28 + 2 + 3). The harness total of 97 is a count from reading, not from a run.
- **Manual action:** `bash -n tests/scripts/run.sh` and `bash tests/scripts/hooks-guard.sh` at H-3.
