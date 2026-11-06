# Phase 03 human acceptance

Status: ACCEPTED by the human architect after revalidation under the Claude Code orchestrator.

- Date: November 6, 2026
- Baseline: accepted Phase 03 commit `9ee8b22`, revalidated at HEAD `c7502b8` plus the repairs listed below.
- Evidence: [revalidation.md](revalidation.md), [revalidation-results.md](revalidation-results.md), [revalidation-review.md](revalidation-review.md), [revalidation-repair-work-orders.md](revalidation-repair-work-orders.md), [revalidation-repair-reports.md](revalidation-repair-reports.md), [revalidation-versions.md](revalidation-versions.md).

## Gate results

| Gate | Result |
|---|---|
| `sbt clean check` (human-run, post-repair) | `[success]`, `Passed: Total 31, Failed 0, Errors 0` (pre-repair: 18) |
| `datasets.py validate` (human-run, post-repair) | Validated all four sources: dblp, openalex, orcid, crossref |
| Dataset-tool unittest (human-run, post-repair) | `Ran 10 tests`, `OK` (pre-repair: 7); bounded-replay digests unchanged from the original Phase 03 run |
| Provenance; no invented licenses or identifiers | `code-reviewer`: REQUEST CHANGES (MEDIUM-1 to MEDIUM-3), then APPROVE scoped to those findings after repair |

## Repairs made during revalidation

- MEDIUM-1, `dataset-engineer`: negative test coverage for `datasets.py validate()`.
- MEDIUM-2, `domain-engineer`: tests for previously untested contract record types, with three invariant tightenings and the permissive empty-`items` shape kept as a recorded decision.
- MEDIUM-3, `dataset-engineer`: the Crossref size figure and an ORCID figure are marked UNCONFIRMED instead of carrying an unsupported attribution.

## Accepted as open (not blocking)

1. MEDIUM-3 residue: the Crossref size needs a citable URL or an authorized bounded re-fetch.
2. LOW-6 residue: `ConfigSuite.scala:86, 89, 92` still use bare `intercept[Exception]`.
3. LOW-7: `ContractWire.scala:7,12` null checks kept; the owner demonstrated reachability instead of removing them.
4. Four raw publisher API responses are not archived, leaving the `openalex.jsonl` `updated_date` question open.
5. Dataset script executable bits left unchanged by human decision.
6. The open runtime gates in `CLAUDE.md` and `docs/agents.md`, including the human-run `sbt check` gate, are unchanged by this acceptance.

The human architect stated:

> I accept Phase 3.
