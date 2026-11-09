# Report 03: test-engineer, independent fixtures, adversarial tests and benchmark (as received; condensed)

**Status: COMPLETE.** Work order 03. Only `tests/**` created; nothing modified or deleted; no production edits; no new dependency. `sbt check` not run (as ordered).

## Results
First `sbt quick` had 1 failure, a bug in the agent's own test (the answer lists a mention hash ID containing the digits "50"); fixed, negative-control test added. Final bare `sbt quick` exited 0, scalafmt check passed. Counts read from `target/test-reports/*.xml` because console output was too long.
- `Phase9BenchmarkSuite` 65 tests, 0 failed. `Phase9GateSuite` 20, 0 failed. `Phase9ModelSuite` 27, 0 failed. 112 new tests, each under 1 s; no slow suite for build-engineer (`rag % test->compile` already in `build.sbt`).

## Fixture (independent of rag's `RuntimeTestSupport` and `DemoWorld`)
13 canonical researchers plus one unresolved mention (Mara Ortega), windows 2000-2009 and 2010-2019; two "Wei Zhang" with different ORCIDs; two communities with one bridge researcher (Jonas Jensen, stored bridge score 0.82); Alice Archer and Greta Garcia tie at 4 distinct coauthors; component split (Kofi Kamara, Lena Lund, second Wei Zhang); one 5-author paper (max team 4 coauthors); path table with found paths, different-component, beyond-hop-limit, legacy `unreachable-within-bound` with no reason, and a never-precomputed pair; summaries ingested into the real vector fake with the hash embedder; Jonas Jensen's profile carries a trap ("99 distinct coauthors and 500 publications") contradicting the graph (stored 3). Served via `InMemoryNeptune`, `InMemoryPathEvidence`, `InMemoryComponents`, `InMemoryCommunities`, `InMemoryResearcherDirectory`, `InMemoryVectorBucket` behind recording wrappers. An `Oracle` computes expected metrics, edges, components and shortest paths from the paper list; only bridge scores and community assignments are hand-set.

## Benchmark (`target/phase9-benchmark.txt`, rewritten each run)
45 questions, each scored on outcome, expected graph facts, answer text, no evidence violations, verdict Verified, no reasoning field.
- Table 1, deterministic graph retrieval (stub model): breadth 5/5, team-size 3/3, direct 4/4, path 10/10, component 3/3, community 3/3, bridge 4/4, temporal 5/5, topic 2/2, institution 2/2, evidence 3/3, lookup 1/1. Total 45/45.
- Table 2, semantic retrieval, separate: index top-1, set and filter checks 13/13; workflow semantic-chunk checks 4/4.
- Topic and institution questions must say the filter cannot be applied (honest gap); one temporal case has a window with no metrics.

## Adversarial gates (all pass)
(a) six hostile strings (Cypher, SQL, AWS CLI, JSON escapes): only the two catalog templates ran, plan and answer equal the clean run, hostile text only under `untrusted`; hostile-only, oversize, blank questions touch no port. (b) shared name abstains with both candidates; only a listed-candidate choice resolves; non-candidate, mention and unknown IDs abstain. (c) independent digit-versus-cited-fact check with negative controls. (d) F1: snippet-only claim Unsupported and removed; snippet number beside a real citation weakened to Uncertain and shown without the number; snippet number as the claimed value Conflicting and removed; path/component claims resting on a snippet removed. (e) injection in snippet, display name and question: plan, queries, instructions unchanged; obedient-model bogus 500-coauthor claim removed; off-list planner operation refused after 3 calls. (f) malformed/oversize/reasoning/throwing model output retried at most maxRetries+1, then abstains (0,1,2,5 retries). (g) Conflicting, PartiallySupported, Unsupported cases exercised. (h) caps refuse, model never called; topK 21 refused; 21-item context and over-long snippet refused. (i) audit record contents match stored turn and bundle; abstentions audited; latency 42 with scripted clock. (j) fresh-world rerun identical response, audit payload and hash. (k) no reasoning key anywhere. Non-path honesty: only `different-component` is reported as "no path"; beyond-hop-limit, legacy unexplained, not-precomputed, timed-out, over-depth, over-results never are (regex-checked); logged path queries carry depth 6, results 6, timeout 2000 ms.

## Defects routed
None.

## Notes
Assumes `maximumCoauthorsOnOnePaper` is team size minus 1. Some assertions pin rag wording: "withheld", "narrow", "cannot be applied", "No direct coauthor edge between", the `Descriptive only` caveat, a reason containing "differs". Observed, not defects: audit stores raw question text; affiliation unavailable in candidates (tests check ORCID, coauthor context, confidence); an `evidence-cap` abstention still returns the partial bundle and no claims. Not covered: planner name-extraction limits (lowercase, "Smith, John") and the LowConfidence shared-ORCID path.

## Files
`tests/adversarial/Phase9World.scala`, `Phase9Steer.scala`, `Phase9GateSuite.scala`, `Phase9ModelSuite.scala`; `tests/end-to-end/Phase9BenchmarkSuite.scala`.

## Gate status
`sbt quick` passed (turn evidence). Pending: one agent `sbt check`, independent review, human `sbt clean check`.
