# Repair 02: WO-A2-R2 (factory-engineer), as received 2026-11-19 (condensed by the orchestrator)

STATUS: COMPLETE, awaiting re-review. Only comment lines in `.claude/hooks/guard-dangerous.sh` changed; the executable lines are untouched. No sbt, Python, `bash -n` or validator was run.
- **L3** (lines 9-11): the "does not cover" list now ends `... aws ec2 terminate-* or aws s3 sync <src> <dst> --delete; the guard alone refuses those.` The block is one line longer.
- **L4** (lines 76-77): now `refuses all code-reviewer writes, and confines factory-engineer, test-engineer, deploy-engineer and service-engineer to their paths.`
PENDING: same list as repair 01.
