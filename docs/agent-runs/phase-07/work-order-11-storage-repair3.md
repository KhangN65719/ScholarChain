# Work order 11: storage-engineer (finish repair cycle: PathEvidence endpoint agreement)

Evidence: test-engineer round 3 report. Failing test `Phase7PathEvidenceSuite` "a hop whose endpoints are not the endpoints of its cited edge is refused" (root default group 449 total, 1 failed; everything else green, storage module 84).

Defect: `PathEvidence.read` (modules/storage/.../PathEvidence.scala) checks hop edge ID membership in `edges-coauthored` but not that the hop's `fromId`/`toId` are the cited edge's endpoints (either order). Repro: edges table has edge(r1,r3); a hop with fromId=r1, toId=r2, edgeId=edge(r1,r3) plus matching works/hop index/provenance is accepted.

Fix: when `edges-coauthored` is supplied, refuse a hop whose {fromId,toId} differs from the edge's {researcherA,researcherB} (order-insensitive) with InvalidEdgeId or a dedicated code; keep legacy-ID, shape and provenance checks. Add a module test in your own suite. Do not edit tests/**; do not weaken tests. If you believe endpoint agreement is wrong for a reason, BLOCKED-ON with evidence instead of relaxing.

Run `sbt quick` only; report exact counts (storage module, root group) and list any failing test. Read-only git. No commit. No AWS.
