# Re-review 2 of the B12d and B12e repairs (code-reviewer, Opus), as received 2026-11-19 (condensed by the orchestrator)

Verdict: APPROVE for all three files.
- **`modules/hadoop/JOB-CONTRACTS.md:87`.** The reviewer's search for `hadoop jar` outside `docs/agent-runs` found these hits, and every one is covered by the line:
  - `scripts/emr/capability-check.sh:89, :121`
  - `lib-emr.sh:1040`
  - `run-s-pipeline.sh:9, :309`
  - two scaladoc lines
  - `README.md:93`, `docs/demo-script.md:175`, `tests/scripts/README.md:116`
  - `tests/scripts/run.sh:4392, 6329, 11047, 11048`, which are the only four, so "for example" is accurate

  None is a local recipe. Optional wording: "refers to EMR use or to the entry point, or states there is no local recipe". The scaladoc lines and the capability-check `pi` example do not fit the current phrase exactly.
- **`docs/demo-script.md:318`:** "4 of 4 PASS".
- **`datasets/schemas/dblp.md:11`:** "release mismatch", matching manifest `:532`.
- No digit/digit pattern in the three files.

Status: `docs/demo-script.md`, `datasets/schemas/*.md` (B13) and `modules/hadoop/JOB-CONTRACTS.md` (B14) are approved for commit. B13 still awaits the human's dataset validate and unit-test run before commit.
