# Deploy ScholarChain

The orchestrator delegates execution and release recording to `deploy-engineer`. Route script or Terraform defects to `infra-engineer`.

Require a clean accepted commit, an immutable image, the reviewed Terraform plan digest, approved script and arguments, AWS account and region, cost limit, smoke checks and rollback criteria. Obtain explicit human approval for the recorded target and exact operation. Use only the approved execution path. Route commands blocked in ordinary agent sessions to the human operator. Capture the release outcome and smoke evidence. Stop on smoke failure and route rollback through the rollback gate.
