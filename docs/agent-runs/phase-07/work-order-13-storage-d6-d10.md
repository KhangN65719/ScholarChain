# Work order 13: storage-engineer (human rulings D6 and D10)

Edit modules/storage/** only. Do not edit tests/**; do not weaken any test. Read docs/agent-runs/phase-07/decisions.md (RULINGS), prompt.md (now verbatim; note the gate bullet "AWS integration tests are bounded and separately enabled"), and report-02/work-order-12 (Spark transformVersion is now `spark-analytics/1.1.0`).

## D6: gate the AWS integration suite behind an explicit opt-in
- `AwsIntegrationSuite` (modules/storage) must run its cases only when the environment variable `SCHOLARCHAIN_AWS_IT=1` is set; otherwise every case is skipped (munit `assume`/`munitIgnore` conditional on the env var, not unconditional). It must stay inert when the variable is set but there are no AWS adapters yet: the AWS-backed adapters remain deferred stubs, so with the variable set the cases must fail or skip with a clear message "AWS adapters deferred" rather than call AWS. No AWS call, credential or SDK in this work.
- Add a module test that proves the suite is skipped by default: read the gate predicate (factor it into a small testable function, e.g. `AwsIntegrationGate.enabled(env: Map[String,String])`) and assert it is false for an empty environment, false for "0", "true", "", "2", and true only for "1". 
- Document the profile in the suite header and in a short `modules/storage/README.md` (new file, in your module): variable name, what the bounded suite will cover when the adapters exist (bounded sizes, cleanup procedure placeholders), and that it is never part of `sbt check`/`quick` by default.

## D10: use core StableIds for AUTHORED and PUBLISHED_IN edge IDs
- Replace storage-local `authored:v1:` / `published-in:v1:` derivation (Neptune.scala; the `idDerivation` property) with core `StableIds.graphEdge(GraphEdgeType.Authored/PublishedIn, ...)` (length-framed, core-owned). Read modules/core/.../StableIds.scala (~lines 140-160) and Contracts.scala for the exact signature and what fields it takes (e.g. endpoints, type, window/policy if required); choose endpoint arguments faithfully (mention vs researcher node as the AUTHORED source). If core `graphEdge` cannot express an AUTHORED edge from a mention node, or needs a field storage lacks, report BLOCKED-ON domain-engineer with evidence and leave the local derivation in place; do not widen core yourself.
- Keep `idDerivation` pointing to the core function (e.g. `core:StableIds.graphEdge`) for provenance. Remove now-dead local code. IDs must be deterministic and unchanged by provenance, as before.
- Update module tests and Fixtures that pinned the local forms (they are yours). Do not edit tests/**: many tests/adversarial Phase7 tests (Phase7NeptuneSuite "authored and published-in IDs ...") pin the local form and will fail: list every failing tests/** test by name with cause for the orchestrator to route to test-engineer.

## Also
- List every file in modules/storage/** that pins `spark-analytics/1.0.0` and update the literal where it is a fixture that mirrors Spark provenance to `spark-analytics/1.1.0` (module-owned files only); list the tests/** files you know of that pin it.
- Run `sbt quick` only (never two sbt at once). Report exact counts per suite, the proving test per ruling, and failing tests/** tests. Read-only git. No commit. No AWS.
