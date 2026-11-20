# Repair 01 for review B12a (documentation-engineer), as received 2026-11-19 (condensed by the orchestrator)

STATUS: COMPLETE for findings 1 to 12. Write set: the six Wave B docs only; README untouched. No commands run.
1. `architecture.md:165`: OpenSearch now cites `infra/terraform/README.md` "OpenSearch" ("Not deployed"), with `RESULTS.md`.
2. `service-limits.md:135`, `threat-model.md:87`, `api.md:134`: state `services-smoke.tfvars:26` = 60000; the default (0) and `services-session.tfvars` keep 10000 ms; cite `variables.tf:492` and the `:497-498` bounds (10000-120000) against the code's 20-300000.
3. `api.md:3` and `:205`: `smoke-tn.md` has no `curl`; its smokes were `ecs run-task` load-client runs (`smoke-tn.md:5`); `scripts/smoke-test.sh` is the `curl` script.
4. `runbook.md:31`: harness 1949/0 (`SKIPPED_CASES 0`) and `hooks-guard.sh` 114/0, labelled recorded, not replayed.
5. `threat-model.md:97`, `:101`, `:144`: R03 ratified at `5157e0e`; cites `guard-dangerous.sh:1-81` and `--list-categories`; 114/0 added to Observed.
6. `threat-model.md:74`: the real fence, "not a general per-owner path fence" (`policy.py:179-197`, `guard-dangerous.sh:75-77`).
7. `architecture.md:122`: default `stub`; no default model id; the variable is required for `bedrock`.
8. `service-limits.md:9`: rows 24 and 28 were human CLI checks.
9. `service-limits.md:91`: "checked by the human"; only the date is missing.
10. `api.md:117`: one line per metric, each with its own citation.
11. `data-lineage.md:91`: `modules/spark/DESIGN.md` only.
12. `runbook.md:72`: "four deploys (12 to 15) and one session-down".
Pending: Mermaid rendering, H-8.
