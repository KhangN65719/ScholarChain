# R13 report, infra-engineer (received 2026-11-17; summary of the report as received; author: infra-engineer subagent)

Status: COMPLETE, wording only; nothing run except file reads and `rg`. Files: `scripts/README.md`, `infra/terraform/README.md`.

- Harness pins: `15.presession.*` (`tests/scripts/run.sh` 9840-10003) check only structure (heading, fenced `aws` blocks, Haiku, us-east-2, two docs URLs, no other Region or 12-digit number); fenced blocks untouched. No case pins the changed sentences. Line 6589 pins an `infra/terraform/README.md` sentence that was not touched. No BLOCKED-ON.
- N3, `scripts/README.md:584`: now says every AWS command is get-/list- except `aws stepfunctions validate-state-machine-definition` (item 5, non-mutating), and that item 5 also runs a local `terraform show -json` on a saved plan; none creates, changes or deletes anything.
- N3, `scripts/README.md:648`: "UNVERIFIED, written from memory" replaced by a citation of `doc-verification.md` (R4 item 9); keeps the two caveats that record: parameter names not checked against the pages, IAM action `states:ValidateStateMachineDefinition` from memory.
- N6, `infra/terraform/README.md:59` EMR row: the cap counts all instances including the primary; ComputeLimits apply to core and task only, so the module renders Min = core, Max = cap - 1, MaxCore = max(core, cap - 1 - Spot task, 1), MaxOnDemand = cap - 1; elasticity-s renders 1/3/3/3 (`doc-verification.md` K2). Checked against `modules/emr/main.tf:80-88`.
- Left unchanged (not requested, not pinned): stale "UNVERIFIED / written from memory / not read in this turn" wording at `scripts/README.md` lines 586, 610, 630, although `doc-verification.md` verified the quota codes and the Bedrock command and the human has run them.

Human check: `bash scripts/check.sh --skip-sbt` (log `phase15-r13-check.log`).
