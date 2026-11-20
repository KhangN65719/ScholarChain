# Review B12a verdict (code-reviewer, Opus), as received 2026-11-19 (condensed by the orchestrator; findings kept)

Verdict: REQUEST CHANGES. 6 major and 6 minor findings, all for documentation-engineer, in one repair round.

## Major
1. `architecture.md:165` cites `phase-07/service-limits.md` and `modules/storage/README.md` for "OpenSearch optional", but neither mentions OpenSearch. Cite `infra/terraform/README.md` and `experiments/RESULTS.md` instead.
2. `service-limits.md:135` says "the deadline was not raised in this repository", and `threat-model.md:87` says "not changed". Both are false: `infra/terraform/environments/course/services-smoke.tfvars:26` sets `slow_deadline_millis = 60000`, and `variables.tf:492` says "The Bedrock smoke raises it". `variables.tf:497` bounds the value to 10000-120000, while the code allows up to 300000. `api.md:134` should cite the overlay. Correct statement: the smoke overlay sets 60000; the default (0) and `services-session.tfvars` do not.
3. `api.md:205` and `:3` say `docs/releases/phase-15/smoke-tn.md` holds `curl` examples and a recorded query with evidence. It has 0 `curl`; its smokes were `ecs run-task` load-client runs (`smoke-tn.md:5`).
4. `runbook.md:31` says the harness count is NOT RECORDED. It is recorded: 1949/0 (`phase-17/human-commands.md`; `phase-16/ratification.md:10`) and `hooks-guard.sh` 114/0.
5. `threat-model.md:97` and `:144` say R03 "is being revised in Wave A". It is ratified and committed at `5157e0e`. Re-cite `guard-dangerous.sh:1-81` and `--list-categories`, and add the 114/0 run to the Observed column (`:101-111`).
6. `threat-model.md:74` overstates the guard as an owner-boundary fence. `policy.py:179-197` and `guard-dangerous.sh:75-77` say "not a general per-owner path fence".

## Minor
7. `architecture.md:122`: there is no default model id. The answer model defaults to `stub` (`AwsSettings.scala:250-253`); `SCHOLARCHAIN_BEDROCK_MODEL_ID` is required for `bedrock` (`:254-259`).
8. `service-limits.md:9`: rows `:24` and `:28` are human command-line checks (`checks-3.md`), not page reads by the orchestrator.
9. `service-limits.md:91`: "checker ... not stated" is wrong; `phase-14/decisions.md:11` says "checked by the human" (only the date is missing).
10. `api.md:117`: metric citations. `http_rejected_total` is at `Http.scala:445`, `downstream_calls_total` at `Resilience.scala:93`, and `jobs_total` at `QueryService.scala:663-720`.
11. `data-lineage.md:91`: cite only `modules/spark/DESIGN.md` for the window list (`GraphService.scala:13-23` only validates).
12. `runbook.md:72`: "four in Phase 14" should be "four deploys (12 to 15) and one session-down" (`docs/releases/phase-14/README.md:24-25`).

## Verified
- All `api.md` services citations, apart from finding 10.
- The source citations across the storage, rag, ER, core and Terraform files.
- The run ids and figures, against `RESULTS.md`, `evaluation.md` and the release records.
- Every relative link resolves.
- Only the masked `000000000000` identifier appears.
- No grader-directed text.
- The course content per document is complete.
- `threat-model.md:61` matches repair 06.

## Pending
- Mermaid diagrams not rendered (`architecture.md:13`, `:73`; `data-lineage.md:9`).
- H-8 command replay.

## Orchestrator note for D1
Finding 2 changes WO-B8: P16-34's value already exists in `services-smoke.tfvars:26` (60000). WO-B8 becomes "use or extend that overlay for the demo session" (infra-engineer), not a code change.
