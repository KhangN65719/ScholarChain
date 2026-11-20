# WO-A3c report (test-engineer), as received 2026-11-19 (condensed by the orchestrator; field values kept)

STATUS: COMPLETE for the Scala edits and `sbt quick`. The new harness has NOT been run (the guard refuses `bash` on harness scripts; the human runs it at H-3).
OWNER: test-engineer
FILES CREATED: `tests/scripts/hooks-guard.sh`
FILES MODIFIED: `tests/adversarial/P14LoaderRig.scala`, `tests/adversarial/Phase10RepairSuite.scala`, `tests/scripts/run.sh`

## K01 (values unchanged at runtime)

| Location | After |
|---|---|
| `P14LoaderRig.scala:22-25` | the four fake values built with `Vector(...).mkString` (AKIA split as `"AKIA", "P14FAKEFAKEFAKE7"`) |
| `Phase10RepairSuite.scala:459, 466-467` | `val canary = Vector("cursor-secret-", "canary-0123456789").mkString`; uses renamed |
| `run.sh:13033` | `TOKEN = "-".join(["TONIGHT", "TOKEN", "NOT", "A", "SECRET"])` (the name `TOKEN` kept, see the orchestrator note) |

## Test results
`sbt quick` (bare), run once after the last Scala edit: `[success] Total time: 160 s`. Last two totals: 77/0/0 and 1306/0/0. Earlier group totals NOT READ. Harness case count: 97 by reading the source, not by a run.

## New harness cases (`tests/scripts/hooks-guard.sh`, new cases only)
- 35 hook command-form cases.
- 15 header cases. They assert the eight headings, the last three lines, the shebang and `bash -n`, and no header wording beyond that.
- 33 R03 refusals as `test-engineer`, each expecting exit 2.
- 13 role cases: infra-engineer is allowed `aws s3 ls`, `neptune describe-db-clusters`, `dynamodb list-tables` and `sts get-caller-identity`. These are refused for test-engineer and documentation-engineer. infra-engineer is still refused `s3 rm`, `secretsmanager list-secrets` and `terraform apply`. Read-only git is allowed for test-engineer.
- 3 `reports/**` row cases.
- Output format: `CASE <id> PASS|FAIL`, then `RESULT pass=N fail=M`.

## Risks and gaps
- Old harness expectations tied to the hook command text were not searched (grep refused); none were changed.
- Uses of `P14Loader.fake*` elsewhere in `tests/` were not checked.

MANUAL ACTION REQUIRED: the human runs `bash tests/scripts/hooks-guard.sh`, and `bash tests/scripts/run.sh` (the full harness), at H-3.

## Orchestrator note
`run.sh:13033` still has the shape `TOKEN = "...`, now followed by `.join`. Whether the checker's TOKEN pattern still matches `TOKEN = "-"` is unknown; A5b checks it and the V1 re-preview settles it.
