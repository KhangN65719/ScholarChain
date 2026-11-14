# Report 05: experiment-engineer, manifests to us-east-2 (condensed by the orchestrator; author: experiment-engineer)

STATUS: COMPLETE (local edits; nothing executed).

Modified: the six experiments/manifests/*.manifest (`REGION=us-east-2`, nothing else) and experiments/README.md (check commands use `--region us-east-2 --cost-ceiling-usd 20`; Phase 14 paragraph: M and L not approved per D8, only A (tier S) fits Phase 14, B to F stay drafts; ceiling text now 20 USD per D6, S+M 25.42 HIGH noted as over it).

No cost figure above 20 in manifests or load-tests/README.md. Per-manifest SPEND_CEILING_USD: 0.75, 3.50, 1.00, 1.50, 0.80, 0.50 (sum 8.05). No limit raised. No us-east-1 remains under experiments/** or load-tests/**. README line 5 keeps the historical `RESULT pass=546`; title still says "Phase 13 draft manifests".

Could not obtain sha256 (guard refuses shasum); all six digests changed, so any approval record bound to an old digest is invalid. Placeholders ACCOUNT_ID, DATASET_SNAPSHOT, OUTPUT_ROOT unchanged; entry-point scripts do not exist, so a real run refuses.
