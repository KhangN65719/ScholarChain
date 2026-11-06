# Run a bounded pipeline

The orchestrator delegates approved pipeline execution and evidence collection to `deploy-engineer`. Hadoop, entity-resolution and Spark owners handle algorithm defects. Infra-engineer owns operations scripts.

Record the approved script revision and arguments, dataset snapshot and sample limit, output prefix and run ID, target environment, cost, duration and cleanup conditions. Start with the local bounded path. Obtain human approval for live AWS execution. Capture output counts, rejects, checksums and the final run status. Stop at an exceeded bound or failed validation and route the evidence to the owner.
