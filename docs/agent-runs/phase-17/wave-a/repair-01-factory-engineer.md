# Repair 01: WO-A2-R1 (factory-engineer), as received 2026-11-19 (condensed by the orchestrator)

STATUS: COMPLETE, awaiting re-review. Nothing was verified by execution (no sbt, Python, `bash -n` or validator).
FILES MODIFIED: `.claude/hooks/validate.py:40`, `.claude/agents/documentation-engineer.md:13`, and `.claude/hooks/guard-dangerous.sh` (comments only; the executable lines 67-69 are untouched). `docs/agents.md` is unchanged; line 24 already held the string.
- **B1:** one string in all three places: `README.md; reports/**; docs/** excluding agents.md, incidents/**, releases/**, dependencies/** and agent-runs/**; docs/agent-runs/README.md`.
- **M2:** the header now says the deny list "repeats some of these forms" and names what it covers and what it misses (`-chdir` destroy, `+<ref>`, `--delete`, `:<ref>`, `branch -D`, `reset --hard`, `rebase`, `aws ec2 terminate-*`), checked against `settings.json:40-63`.
- **M1:** aws metadata forms are allowed only for infra-engineer, as exactly three tokens (`policy.py:152`). `aws s3 ls <uri>` is refused for every role.
- **L1:** adds `fmt -check -recursive`.
- **L2:** the write fence (`policy.py:179-197`) is described as covering Git metadata, the marker, the constitution and five named roles; "not a general per-owner path fence".
PENDING: `validate-agent-files.sh`, `bash -n`, execute bits, probes P1 to P4, V1 re-preview. The secretsmanager and ssm claim was not re-checked (grep refused).
