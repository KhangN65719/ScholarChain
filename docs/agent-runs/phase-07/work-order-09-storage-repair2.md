# Work order 09: storage-engineer repair round 2 (batched, from review.md round 1)

Edit modules/storage/** only. Do not edit tests/** (test-engineer owns them); do not weaken any test.

Blocking:
- B1: edge-support rows must match their edge: refuse a support row whose researcherA/researcherB (and window) differ from its edge's; cross-check each (endpoint, supporting work) pair against `edges-authored` (an endpoint that did not author a cited work is refused as MissingEvidence/InvalidEvidence). If you judge a cross-check inapplicable for mention endpoints or for `countedInMetrics` semantics, implement the rest and record exactly why in a code comment and your report. Fix the module fixture (Fixtures.scala) to add the missing AUTHORED rows (not by weakening checks). Add module tests for each check.
- B2: record storage source + transformation provenance (`transformVersion` storage/1.0.0 plus source reference/Spark run and Spark transformVersion kept distinct) on: ArtifactRegistry, ResearcherMetricCache, EntityLinkCache, RunLedger (do not mix kind=spark-analytics with the storage version; keep Spark's), and vector metadata (stay under 2 KB / 50 keys). Add tests.

Non-blocking to include (cheap): reviewer items 2 (rejected-vector log key attempt-scoped or merged so a reload cannot throw ImmutableKeyConflict), 4 (PathEvidence: validate the edge ID shape (prefix + 64 hex), fromId/toId node kinds, supportingWorkIds as work IDs, hop edge membership if the coauthor-edges table is supplied, keep provenance), 5 (`clean()` must not rewrite IDs: reject IDs containing tab/newline/NUL), 6 (edge `window` text must equal the years), 3 (lower the default byte bound to a sane value such as 1 GiB with a comment; streaming is out of scope), 12 (Rows.int refuses non-integral values, DynamoDB size estimate counts the missing byte per element, `$limit` upper bound, refuse `.` and `..` path segments in object keys). Do NOT change AUTHORED/PUBLISHED_IN ID derivation (D10) and do not touch item 8/9/10/7 (recorded as D12).

Warning: the cross-checks may make test-engineer's tests/adversarial Phase7 fixtures fail (their fixtures may lack AUTHORED rows). Do not edit tests/**; list every failing tests/** test with cause so the orchestrator can route fixture corrections to test-engineer.

Run `sbt quick` only (never two sbt at once). List which test proves each item before you finish, report exact counts, list failing tests by name. Read-only git. No commit. No AWS.
