# Runbook repair 6, after runbook review 5 (deploy-engineer, received 2026-11-17; summary as received)

- :55 "A results (2.8 to 2.10; ...)" plus 2.8e skipped when slack to T0+3:15 is under 15 minutes.
- :1074 "4.1 to 4.3" -> "4.1 to 4.4".
- :1076 removed "unless the human decides otherwise in the chat".
- :1105 infra repair batch 4 item CLOSED, citing 2026-11-17 `check.sh` GATE terraform=PASS, harness `RESULT pass=1883 fail=0` (`wave-2/checks.md`), Wave 2 commit `6cd7341`.
- :1117 validation-evidence paragraph: same item marked DONE with the same citation (follow-up message).
- :1121 Next gate: human runs the runbook from step 0.0; this file and the phase evidence are committed by step 0.4; copy to `human-commands.md` removed. Trailing sentence "The human runs it in order and pastes the evidence of 6.3" left (repeats "runs").
Text only; nothing run.
