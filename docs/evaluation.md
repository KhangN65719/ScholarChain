# Phase 16 evaluation: interpretation

Author: documentation-engineer, work order WO-10, repaired in WO-10-R1 and WO-10-R2 (2026-11-19). Status: written for independent review; links were checked by review 26. Nothing here is a reviewed or accepted result.

## 1. Purpose and how to read this document

- The measured facts are in [experiments/EVALUATION.md](../experiments/EVALUATION.md). That file holds the numbers, their source files and their limits. This file does not repeat the tables.
- This file interprets those facts: what each group of evidence supports, what it does not, and what stays open. Nothing here is a new measurement. I ran no command and read no run directory. Every number below is copied from EVALUATION.md, and the section where it lives is named in brackets, for example "[EVALUATION.md 6.3]".
- Where EVALUATION.md says NOT DERIVED, NOT RECORDED, NOT STATED, NOT IDENTIFIABLE or UNVERIFIED, this file says the same and does not fill the gap (section 4).
- EVALUATION.md is itself "written for independent review" and not an accepted result (its header). Interpretation built on it carries the same status.
- Related records: the plan ([plan.md](agent-runs/phase-16/plan.md), including "Metrics per group", "Risks" and "Benchmark questions and labels"), the decisions ([decisions.md](agent-runs/phase-16/decisions.md)), the human's commands and results ([human-commands.md](agent-runs/phase-16/human-commands.md)), and the Phase 15 records [RESULTS.md](../experiments/RESULTS.md) and [LIMITATIONS.md](../experiments/LIMITATIONS.md). Run-specific limits: [benchmark README](../experiments/benchmark/README.md), [G README](../experiments/g-retrieval/README.md), [H README](../experiments/h-answers/README.md), [ER README](../experiments/evaluation/er-README.md).

One framing point governs the rest. The benchmark labels and the retrieval system both come from one export. Retrieval and answer metrics therefore measure fidelity to the graph, not truth about the world [EVALUATION.md 3.5; plan, "Threat stated up front"].

## 2. Interpretation per group

### 2.1 Entity resolution

Facts: [EVALUATION.md 6.5.1 and 6.5.2].

What the evidence supports:

- The replay reproduces the earlier Phase 5 report: pooled precision 1.000, recall 0.802, F1 0.890, and 0 of 230 decisions changed [6.5.1]. This supports reproducibility of the matcher on the frozen gold set, and nothing more.
- Precision is 1.000 with a Wilson 95% interval of [0.952, 1.000] [6.5.1]. The stratified bootstrap interval is the degenerate [1.000, 1.000] [6.5.2], which I do not rely on. There were 0 false positives in the sample, so the interval says only that none were seen in 230 pairs. The report itself warns that fewer than 10 false positives were available [6.5.1].
- Recall comes almost entirely from the exact-id path (77 of the 78 gold-same pairs it saw). The deterministic-bibliographic and probabilistic paths accepted 0 of 9 gold-same pairs each [6.5.2]. The reading is that the shipped provisional thresholds accept on identifiers and almost never on weaker evidence in this gold set.
- The sensitivity variants show recall of 0.917 if the 12 suspected mislabelled pairs are removed or relabelled [6.5.2]. This is a sensitivity figure under a proxy. It is neither a bound nor an estimate of the true recall.

What it does not support:

- The gold set is 230 stratified pairs from the Phase 4 subset, not tier S, and the stratum mix drives the pooled numbers [6.5.2 limits]. The pooled figures are not population rates.
- The `llm-adjudicated` path decided no pair because the adjudicator is an offline stub, so its recall is undefined [6.5.2]. Nothing here says anything about an LLM adjudicator.
- The suspected mislabelled pairs are not identifiable from the manifest. The 12-pair set is a proxy [6.5.2].
- The tier S population view shows high unresolved-endpoint rates on coauthor edges (0.8025 overall, either endpoint) and 0.6719 of researchers `unlinked` [6.5.2]. These are descriptive counts of the export. They do not say how many of those researchers are in fact the same person, because no gold set covers tier S.
- Precision of the probabilistic path is not measured by the pooled number [6.5.1 limits].

### 2.2 Graph correctness

Facts: [EVALUATION.md 1.1 to 1.4].

What the evidence supports:

- The export is internally consistent on every check run: 0 duplicate ids in all 17 tables, 0 dangling endpoints, 0 edges without support, joint counts agree on every edge, 0 provenance mismatches, and 27 of 27 checked path hops found in the edge table [1.2].
- The composed edge and vertex counts equal the projected totals of the Phase 14 rehearsal (difference 0 and 0) [1.3]. This is consistent with the earlier load projection. It is not a check of Neptune, because the report's basis says the figures are projections [1.3].
- The earlier figure of -12,371 is a definitional difference (two edge kinds have no export table), not an integrity finding [1.3].
- The human traced 10 edges to DBLP and found 10 of 10 correct [1.2].

What it does not support:

- The checks read the export and not Neptune [1.4]. They test the export's consistency, not its correspondence to the sources beyond the 10 traced edges.
- The H-8 check is one auditor, 10 edges, all DBLP-backed and taken in trace order. The 5 traced edges without a DBLP work were not checked, and the check does not cover the joint publication count or the supporting-row count [1.2].
- Known-graph unit tests of the six algorithms are not part of EVALUATION.md section 1 [1.4].

### 2.3 Retrieval (G)

Facts: [EVALUATION.md 6.1].

What the evidence supports:

- Graph retrieval is much stronger than vector retrieval on this benchmark. Pooled over all questions at K = 10, graph recall is 0.4935 and vector recall is 0.1498; fused is 0.5008 [6.1.1]. The vector-available scope shows the same order (0.3520, 0.1971, 0.3520 at K = 10) [6.1.2]. The deleted vector keys therefore do not explain the gap.
- Fused tracks graph almost everywhere. In the per-stratum tables the fused and graph rows are equal in every stratum except community-component (fused 0.0556 recall at K = 5 and 10, graph 0) [6.1.3]. The reading is that the fusion adds little over graph here, and that the benchmark mostly asks graph-shaped questions.
- Ranking questions score high in graph mode (nDCG 1.0 at every K) [6.1.3]. These questions come from templates and the labels come from the same table the runtime reads, so this is a check of plumbing, not of difficulty (see 3.1 and 3.6 below).
- Graph recall is 0 for shortest-path and community-component questions [6.1.3]. This is a measured failure of the retrieval path for those strata, partly by design (community and component members are never retrieved in graph mode, per the G README, quoted in [6.1.7]).
- The two repeats give identical retrieval metrics [6.1.6]. G is deterministic enough that repeats add no information on these inputs.

What it does not support:

- The intervals are bootstrap intervals over questions with small strata (n 4 to 10 over all questions; n 3 for free-form in the vector-available scope; n 0 for unanswerable) [6.1, 6.1.4]. The confidence level is NOT STATED in the files read [6.1]. I do not read the per-stratum numbers as rates.
- Synthesis is the stub in G. G says nothing about answer quality [6.1.7].
- Researcher-lookup recall in graph mode is capped by what the runtime returns, so the subject item (grade 2) is the comparable one [3.5].

### 2.4 Answer quality (H), the independent audit and the human rating

Facts: [EVALUATION.md 6.2, 6.3, 6.4, 5.5].

What the evidence supports:

- Rule 6 and the claim cap held across the full H run: no `max_tokens` stop in 57 calls, and 2 completed replies were rejected by the cap [6.2.2]. The fix is supported on H (section 5 item 3 below tells the history).
- The independent audit found no structural support failures and no claim citing invalid evidence. All checked evidence kinds were valid where anything was checked: 28 coauthor edges, 6 publications, 93 metrics, 12 rankings, 9 groups, 22 identities [6.3]. Of 613 numbers, 585 matched the export, 25 matched evidence only and 3 matched nothing (0.954323) [6.3]. Two claims were contradicted (rate 0.010050) [6.3].
- Abstention behaves as designed on the unanswerable set: recall 0.9, and the reason code matched on every abstained unanswerable question (9 of 9) [6.3, 6.2.4].
- The human rater gave a mean usefulness of 4.00 and clarity of 4.15 over 20 answers, and marked all 20 supported [6.4.3, 6.4.2].

What it does not support:

- The audit's high structural scores are partly by construction. The audit and the labels share the export [6.3 limits], and the audit reads only numbers and ids it extracts, not free text [6.3 limits]. "No unsupported claim" means no claim whose extracted ids and numbers fail the export. It does not mean the prose is accurate.
- Path-hop, path-chain and supporting-work validity were checked on zero items, because all four shortest-path questions abstained [6.3]. The audit gives no evidence that the system can produce a valid path answer.
- Identifier accuracy has no rate: no identifier was checked [6.3].
- The verifier's own verdicts (140 partially supported, 68 unsupported, 59 verified) are self-reported and not independent [6.3]. They are not comparable with the audit's rate of 1.
- The human rating is one rater, 20 rows, and strata of 2 or 3 rows [6.4.6]. The `supported` column is "yes" on all 20 rows, including the 6 refusals, so the column does not separate rows [6.4.6]. I read the rating as a weak check that answers are readable and useful, and I do not rank strata or modes with it. The frozen abstention column was corrected after rating by instruction, so it is not the rater's own entry [6.4].
- The means for answered (4.36 usefulness) and refused (3.17) rows are descriptive, with no interval [6.4.3].
- Latency here is laptop-to-Bedrock (3.5 below).

### 2.5 Performance and elasticity

Facts: [EVALUATION.md 4.1 to 4.5], with Haiku latency in [5.1 to 5.5] and [6.2.1].

What the evidence supports:

- Query load: the error rate stays at or below 0.4212% up to 48 clients, but latency rises sharply above 8 clients. The ok p50 at 48 clients is 3,768.982 ms against 337.545 ms at 8 clients [4.3]. The bootstrap intervals are narrow relative to that gap [4.4], so the rise is not sampling noise under the iid assumption.
- Elasticity: scale-out and scale-in were observed in B (EMR) and C (workers), and the service tasks went from 1 to 3 in D [4.2, 4.3]. Each is one run with no interval [4.5].
- End-to-end ok p50 differs by mode in E: graph 26.847 ms, vector 332.538 ms, fused 342.416 ms [4.2]. The stage p50s are graph stage 4 ms, vector stage 315 ms (vector mode) and 317 ms (fused) [4.2]. Reading: the vector stage accounts for most of the vector and fused latency. Fused skipped the vector stage on 17 requests, and the cause is UNVERIFIED [4.2].

What it does not support:

- The answer model in Phase 15 D, E, F and C was a stub. Phase 15 latency has no language model in it [4.5; LIMITATIONS L15]. The only Haiku latency is from Phase 16 and is laptop-to-Bedrock: p50 8,038 ms, p95 17,671 ms [6.2.1]. The two are not additive in any recorded measurement. A full in-VPC latency with Haiku is not recorded.
- The bootstrap treats requests as independent. They are not: autoscaling changed capacity during L2 and L3, so the intervals describe sampling variation under the iid assumption only [4.5]. Reading (mine, not stated in EVALUATION.md): the real uncertainty is therefore at least as large as the intervals show.
- One load-client task ran the whole schedule, and client saturation was not measured [4.5].
- The abstention counts in D were not explained at the time [4.5].
- Billed Phase 15 cost is not reconciled to a single figure [4.1].

### 2.6 Agentic factory

Facts: [EVALUATION.md 2.1 to 2.4, 2.3a].

What the evidence supports:

- The parser counted what the repository's own records contain: BLOCKED-ON events, review findings by severity, repair files, human rulings [2.1]. As counts of recorded process, they describe the volume and shape of the process.
- The human's audit of Phase 14 found 16 of 20 counted items correct, 80% [2.3a]. The false positives were of identifiable kinds (a "none" status line, facts counted as rulings, a read-only note counted as a refusal).

What it does not support:

- The 80% is a share of a 20-item sample of one phase by one auditor. It is not the tool's precision across phases [2.3a limits].
- The first-pass table has denominators of 1 to 14. It is not an acceptance rate of any agent [2.2, 2.4].
- The Phase 14 reviews are in decision files that the tool does not pair, so the first-pass counts omit the 12 unreviewed pairs [2.3a]. Any statement about review coverage in Phase 14 from the tool is wrong, and the tool is not corrected in Phase 16.
- The 0 ownership violations is a count of lines the parser classed as violations. The same file counts 150 refusal events. EVALUATION.md draws no link, and I draw none [2.3].
- Human-annotated rewrites 0 is the human's statement, not a parser finding [2.3, 2.3a].
- Two categories were not audited at all: BLOCKED-ON mentions and the first-pass counts apart from the miss [2.3a].

### 2.7 Reproducibility

Facts: [EVALUATION.md 7.1 to 7.3, 3.3, 6.5.1].

What the evidence supports:

- The committed code builds and passes in two trees: the working repository and a fresh clone both ended with exit 0, equal totals group by group, 0 failed and 0 errors, one skipped opt-in test, at the recorded commit [7.1]. The commit of the clone rests on the human's relay [7.1].
- Benchmark regeneration at the recorded generator commit matched all three files before the free-form fill [3.3]. The entity-resolution replay equals the earlier report [6.5.1]. The reconciliation difference of the export is 0 and 0 [1.3].
- The frozen G repeats are identical [6.1.6].
- The B to F table regeneration is recorded, and the corrected count is 42 of 44 committed derived files identical (decision P16-47; [EVALUATION.md 7.3](../experiments/EVALUATION.md); section 4, H-12).

What it does not support:

- Only the offline pieces replay without AWS. G, H and the diagnostic call Bedrock, Titan and S3 Vectors and are not reproduced without a new approval record [7.2]. The frozen H repeats are not byte-identical (`sameAnswerHash` 0.65) [6.2.5], so a rerun of H will not equal the frozen answers.
- Replay of the bounded local sample pipeline by checksum is NOT DERIVED [7.3].
- Each clean-checkout run is one run on one machine, with warm-cache status NOT STATED [7.1 limits].

## 3. Threats to validity

Each threat is followed by its effect on the reading.

### 3.1 Template wording and the 8 human-written questions

Template wording matches the planner rules, so planner accuracy on the 48 generated questions is overstated [EVALUATION.md 3.5; plan, "Risks"]. The 8 free-form questions exist to test this. Effect: the ranking and temporal-comparison strata are entirely template questions (the 8 free-form questions are the only human-written ones), so their high scores say nothing about natural phrasing. The free-form stratum scores lower in graph mode (nDCG@10 0.4689, n 6) than the ranking stratum (1.0000, n 10) [6.1.3]. That comparison is selective. Free-form is close to coauthor-lookup (0.5000, n 8) and above shortest-path and community-component (both 0) [6.1.3]. nDCG is also only a stand-in for planner accuracy here: it mixes planning, identity resolution and retrieval, so a stratum gap is confounded with the retrieval path and the question shape. With 6 questions the difference is suggestive and not established. I treat planner accuracy as an upper bound for template questions and leave it unmeasured for natural phrasing.

### 3.2 Shortest-path questions

The shortest-path stratum has 4 questions, not the 6 requested [EVALUATION.md 3.1]. All four are said to be of length 1. Source of that statement: the human's note, recorded in decision P16-24 and cited in EVALUATION.md 3.1. EVALUATION.md verified only two of the four itself (spot-check rows 11 and 12 show path length 1); the other two labels "were not read" [3.1]. Effect: the stratum tests only a one-hop path, which is the same as a coauthor edge. All four questions abstained in H [6.2.4], and graph recall is 0 [6.1.3]. The evaluation therefore says almost nothing about multi-hop path answers. The path-validity audit has 0 items [6.3].

### 3.3 Single rater

Each human task had a single human: the 8 free-form questions and the 15 label checks [3.5], the 10 traced edges (H-8, [1.2]), the factory sample (H-9, [2.3a]) and the 20 answer ratings (H-11, [6.4]). Whether it was the same person across the tasks is not stated in the files read [6.4.6]. The rubric names the single-rater threat itself [plan, "Human rating rubric"; EVALUATION.md 6.4]. Effect: no inter-rater agreement exists for any human result. The rating cannot separate rows, because `supported` is "yes" on 20 of 20. A consistent rater bias would shift every human figure the same way, and nothing here can detect that.

### 3.4 InMemoryNeptune, not Neptune

G and H ran the graph port `InMemoryNeptune` over the local export [EVALUATION.md 6.1, 6.2]. Effect: retrieval and answer quality are measured against an in-memory projection. Neptune's query behavior and latency are not exercised. Phase 15 D saw Neptune only by CPU and pending requests [4.5]. Graph-mode latency from Phase 16 should not be quoted as Neptune latency.

### 3.5 Laptop-to-Bedrock latency

H's latency (p50 8,038 ms, p95 17,671 ms [6.2.1]) and the diagnostics' per-call times [5.3 to 5.5] were measured from a laptop to Bedrock, not from inside the VPC [H README, quoted in 6.2.6]. Effect: the figures include the network path from the laptop and are not a deployed-service latency. They show the order of magnitude of a Haiku synthesis call at the 4,096 limit (about 9 to 17 s) and nothing more precise.

### 3.6 One export for labels and retrieval

The labels come from the tables of the A export, and retrieval reads the same export [EVALUATION.md 3.5; plan]. The audit also checks answers against that export [6.3]. Effect: every retrieval, audit and answer-validity result measures fidelity to the graph. If the graph is wrong about the world, these metrics do not detect it. The graph-correctness and entity-resolution groups address that partly (2.1 and 2.2), within their own limits.

### 3.7 Mixed vector provenance and the 2,000 deleted vector keys

Vectors come from the earlier ingest and the graph from A, and 2,000 vector keys were deleted after experiment E [EVALUATION.md 3.2, 3.5]. The vector-available scope holds 30 of the 56 questions, and has no ranking or unanswerable question [3.2, 6.1.2]. Effect: all-question vector metrics are reduced for questions whose vectors were deleted. The vector-available scope removes that effect, and the vector deficit remains (2.3 above). The two scopes cover different question mixes, so their numbers are not directly comparable as a before-and-after.

### 3.8 FactoryCounts precision and the missed Phase 14 reviews

The audit found 16 of 20 items correct for Phase 14, and the tool missed the Phase 14 reviews because they sit in decision files [EVALUATION.md 2.3a]. Effect: the process counts are approximate. Phase 14 is the one phase audited, and other phases may differ in either direction. The count of 12 first-pass pairs without review is a total over phases 1 to 15 in the frozen JSON; the human attributes it to Phase 14 [2.3a]. Any use of the counts should carry the 80% sample figure and these limits.

### 3.9 Stub answer model in Phase 15 D and E

D, E, F and C ran with the offline stub model [EVALUATION.md 4, preamble and 4.5]. Effect: Phase 15 latency and throughput describe the retrieval and service path without a language model. Elasticity conclusions (scaling behavior) are less affected than latency conclusions.

### 3.10 Other threats recorded in EVALUATION.md

- Costs are hourly burn times elapsed time at unverified rates, not bills [4.1]. The billed Phase 16 Bedrock usage was 1.46 USD at H-1c, and the file estimates sum to 1.147985 USD, a gap of 0.312015 that "is not explained in the files read" [6.2.3].
- The G interval's confidence level is not stated (section 4). Repeat variability in H is a negative result (section 5, item 4).
- The benchmark generator ran from a dirty tree and reports its Scala library version, not the build's [3.4].
- Small strata: most strata have 2 to 10 questions or rows.

## 4. Missing data

Every NOT DERIVED, NOT RECORDED, NOT STATED, NOT IDENTIFIABLE and UNVERIFIED item in EVALUATION.md, with its section. Items are listed as EVALUATION.md states them. I filled none.

| Item | EVALUATION.md section |
|---|---|
| Known-graph unit tests of the six algorithms and the counts from the `sbt clean check` log (as inputs to the graph group) | 1.4 |
| GraphChecks verification of per-file sha256 in the export manifest | 1.4 (NOT STATED) |
| Which pairs and which agents the 12 Phase 14 "without review" first-pass pairs belong to | 2.3a |
| A single reconciled Phase 15 billed total (only readings are recorded) | 4.1 |
| Experiment A: input records, task counts, shuffle and failures | 4.2 |
| Experiment C: latency and retry count when separated from F2's window | 4.2 |
| Experiment F1: INVALID as a result, not derived | 4.2 |
| Experiment E: why fused mode skipped the vector stage on 17 requests (UNVERIFIED) | 4.2 |
| Phase 15 SMOKE2: whether the rejected reply was truncated at `maxOutputTokens` 2,000 (UNVERIFIED; the stop reason was not logged then) | EVALUATION.md line 329 |
| Cost rates (UNVERIFIED; at line 577 the Haiku and Titan rates are "values given on the command line") | EVALUATION.md lines 329, 410, 577; 4.1 |
| Experiment D: the abstention reasons behind the abstention counts 23, 226, 687 and 617 (not captured) | 4.5 |
| H: which two completed replies the claim cap rejected (`model-calls.jsonl` has no question id) | 6.2.2 |
| H: the Haiku temperature (set by the entry point; value not read) | 6.2.6 |
| H-8: any check of the 5 non-DBLP traced edges (no recorded check) | 1.2 |
| G: the confidence level of the bootstrap interval | 6.1 (NOT STATED) |
| G: what `cost.haikuCalls` 210 counts in a stub run | 6.1 (NOT STATED); 8 |
| H: retry decisions beyond the rejection codes | 6.2.2 |
| H: its own billed share of Bedrock spend (billing is a month-to-date difference); the 0.312015 gap between file estimates and billed is unexplained | 6.2.3 |
| Audit: an identifier-accuracy rate (no identifier was checked) | 6.3 |
| Entity resolution: which pairs are the roughly 12 suspected mislabelled | 6.5.2 (NOT IDENTIFIABLE) |
| Replay of the bounded local sample pipeline compared by checksum | 7 introduction and 7.3; 8 |
| Release records of Phase 14 and Phase 15 (deployment from an approved plan; not re-read) | 7.3 |
| Number of dirty files in the working repository at the phase-gate run | 7.1 (NOT RECORDED) |
| Whether dependency caches were warm in the clean-checkout run | 7.1 (NOT STATED) |

Two further gaps are stated in EVALUATION.md without the NOT labels, and I list them so they are not lost. The prompt version of diagnostics 1 to 3 is not recorded in their manifests and rests on the decision log and the H README [5.1, 5.6]. Whether the human rater is the person who wrote the free-form questions is not stated in the files read [6.4.6].

### H-12 (B to F regeneration): corrected result

Decision P16-47 ([decisions.md](agent-runs/phase-16/decisions.md)) corrects the result: 42 of 44 committed derived files are identical. By run: D 9 of 9, E 5 of 5 (with the original title), F2 12 of 12, C 10 of 10, B 6 of 8. B's two differences are only the recorded input sha256 and source path, which come from the redacted copy (per P16-47; the files were not opened, EVALUATION.md 7.3). The earlier relayed F2 15 and C 12 were miscounts, corrected by the human; the source is `phase16-h12-compare.log` [decision P16-47]. See [EVALUATION.md 7.3](../experiments/EVALUATION.md). EVALUATION.md 7.3 was updated to these counts by W2-RB18 (2026-11-19). The text that follows is my record of what it said and is superseded by the corrected result above.

Before W2-RB18, EVALUATION.md 7.3 recorded a conflict. The human's relayed result is 47 of 49 files identical, with F2 at 15 of 15 and C at 12 of 12. The compare log, as read by the EVALUATION.md author, shows 12 F2 lines and 10 C lines, all identical, and a total of 49 lines. The author could not find the relayed 15 and 12, and the committed `experiments/results` holds 12 and 10 derived files for F2 and C [7.3]. Its arithmetic from the log gives 46 identical and 3 different in 49 lines, or 42 of 44 leaving out the other-title E run. Neither equals the relay's 47 of 49, and EVALUATION.md says that "both 49 and the relay's figures need the human's or Claude in chat's correction before they are treated as a result" [superseded text, formerly in 7.3 and 8]. At the time, I stated the conflict and did not pick a side; P16-47 has since settled the counts. Other parts of the H-12 result (the two B differences being only input hashes and the source path, the cause being a redacted copy, the title value, and that the compare was by sha256) rest on the relay alone [7.3].

## 5. Negative results

These are kept because they are results.

1. **False abstention is about 28% on answerable questions.** `falseAbstentionRate` is 0.282609: 13 abstentions on the 46 answerable questions [EVALUATION.md 6.3; same value in all three G modes, 6.1.5]. Most are `identity-unresolved`: 11 of the 13 false abstentions (researcher-lookup 1, coauthor-lookup 4, shortest-path 4, community-component 2; the other two are 1 `evidence-cap` and 1 `researcher-count`) [6.2.4, counted by reading]. Within strata, 4 of 4 shortest-path questions and 4 of 8 coauthor-lookup questions abstained [6.2.4]. The abstention behavior is correct on unanswerable questions (recall 0.9), and the cost is a refusal on a large share of questions that have an answer. The rater marked abstention appropriate "no" on 4 of the 6 refusals in the sample [6.4]. Whether the cause is resolution of the names or the planner is not established by the evidence here.
2. **One unanswerable question was answered.** `unanswerableAnswered` is 1, and the answered question is the free-form item whose expected class is an unrankable metric [6.3, 6.2.4].
3. **Synthesis truncation, with its fix history** [EVALUATION.md 5.1 to 5.5]:
   - At a 2,000-token limit, 3 of 4 Haiku calls stopped on `max_tokens` and the ranking question abstained `synthesis-failed` [5.2]. The one accepted call used 1,990 of 2,000 tokens and ended `end_turn` [5.2]. The non-ranking question was accepted at that limit [5.3], so the 3 `max_tokens` stops were all the ranking question (derived from 5.2 and 5.3; the `h4-diagnostic-2` row of [human-commands.md](agent-runs/phase-16/human-commands.md) also records the ranking question at the 2,000 limit).
   - Raising the limit to 4,096 did not fix the ranking question, which hit `max_tokens` on all three attempts at 4,096 [5.3]. The non-ranking question had already been accepted at 2,000, and at 4,096 it used 2,559 tokens [5.3]. The raise therefore fixed no recorded failure. Reading: it gave headroom, since 2,559 is above the old limit. Raising the limit alone was not enough for the ranking question.
   - With the text-free measures added (prompt 1.1.0), one ranking question still truncated while another completed 57 tokens under the limit. The pre-registered rule for "the model lists beyond the evidence" matched. The earlier hypothesis that the reply grows with a tie list was not supported [5.4].
   - The fix was prompt 1.2.0 with a claim cap (entries plus 2) and the planner maximum limit lowered from 20 to 12 [5.5]. Two ranking questions then completed at 1,828 and 1,821 tokens [5.5], and across all of H there was no `max_tokens` stop [6.2.2].
   - Interpretation: the cause was the model producing too many claims for a ranking, and the structural cap removed it on the tested questions. The cost is that the cap rejected 2 completed replies in H [6.2.2]. Evidence for the fix covers 2 diagnostic questions and one full H run [5.6].
4. **Repeat variability.** `sameAnswerHash` is 0.65 over 20 repeated questions, `sameClaimCount` 0.8, `sameAbstention` 1 and `sameEvidenceIds` 1 [6.2.5]. Retrieval is stable and the answer text is not. A reader who needs identical answers on rerun will not get them from this system.
5. **Audit contradictions and unmatched numbers.** Two claims were contradicted by the export (rate 0.010050) and 3 numbers matched nothing; EVALUATION.md does not explain either [6.3, 8]. The verifier called both contradicted claims verified (`claimsVerifiedButAuditContradicted` 2) [6.3]. The verifier's verdicts and the audit are otherwise compared only in aggregate, and the 267 verifier verdicts against 199 audited claims are not explained in EVALUATION.md [6.3]. The verifier has 68 UNSUPPORTED claim verdicts on its own [6.3].
6. **Vector retrieval is weak.** Vector recall at K = 10 is 0.1498 against 0.4935 for graph [6.1.1], and vector scores 0 recall on coauthor-lookup [6.1.3]. The vector-available scope still shows a large gap [6.1.2].
7. **Graph retrieval fails on two strata.** Recall 0 on shortest-path (4) and community-component (6) [6.1.3]. The H audit's path-validity checks have 0 items because the four shortest-path questions all abstained [6.3, 6.2.4].
8. **Entity resolution recall is 0.802 and weak off the exact-id path.** Two non-identifier paths accepted 0 of 9 gold-same pairs each, and the hard-common-name stratum has recall 0.000 [6.5.1, 6.5.2]. The tier S export has 0.6719 of researchers `unlinked` and an overall unresolved-endpoint rate of 0.8025 on coauthor edges [6.5.2].
9. **Factory counts.** Precision of 16 of 20 on the audited phase and the 12 unreviewed first-pass pairs [2.3a] (section 3.8).
10. **Latency at load.** The p95 at 48 clients is 7,990.338 ms, p99 10,130.334 ms, and 14 errors in 3,324 requests [4.3]. Per-call Haiku time at the 4,096 limit is 13.9 to 17.1 s [5.6], and the slow-route deadline default is 10 s [5.6; decision P16-34].
11. **A failed experiment run.** F1 completed by the harness but is INVALID as a result [4.1, 4.2].
12. **Superseded audit run.** `h-audit-1` ran with a metric-name defect (ranking validity 1 of 12, claims citing invalid evidence 59) and was replaced by `h-audit-2` after the fix [6.3]. It is kept as a record that the first audit result was wrong because of a defect in the audit.
13. **Other recorded defects.** The H summary flag `tokensEstimated: true` was a defect and not a measurement; the recorded run keeps it [6.2.3]. The ER report's "gold labels changed between the files" line is misleading when both gold hashes match [6.5.1; 8].
14. **Fused mode skipped the vector stage on 17 requests.** In experiment E, fused mode did not run the vector stage on 17 requests. The cause is UNVERIFIED [4.2, citing RESULTS.md]. It is open and I offer no explanation.

## 6. What Phase 17 should take from this

These are recommendations. Each rests on an item already recorded in EVALUATION.md or the decisions it cites. None is a decision.

1. **Raise the slow-route deadline before any deploy with Haiku (P16-34).** Per-call synthesis takes 13.9 to 17.1 s at the 4,096 limit, and the default slow deadline is 10 s [EVALUATION.md 5.6]. No deploy happened in Phase 16.
2. **Measure Haiku latency in-VPC and under load.** Phase 15 D, E, F and C used a stub [4.5], and Phase 16 latency is from a laptop [section 3.5 above; EVALUATION.md 6.2.6]. This follows from the recorded limit and is not a separate recorded item.
3. **Stop the account id echoing in the sbt fork line** so raw run files do not hold bucket names (experiment-engineer, a later work order) [EVALUATION.md 8].
4. **Explain the 3 unmatched numbers and the 2 contradicted claims** in the audit, and the meaning of `cost.haikuCalls` 210 in a stub run [8].
5. **Address the rag advisory** that 5 entries were retrieved for a top-3 question [6.2.2], and the entity-resolution advisory on the misleading "between the files" wording [6.5.1; 8].
6. **Follow up false abstention.** Section 5 item 1 is the largest quality gap measured. The recorded evidence points at `identity-unresolved` [6.2.4]. Which stage causes it is open.
7. **Replay the bounded sample pipeline by checksum**, open in section 4 [7.3]. The H-12 count conflict is corrected (P16-47; section 4). EVALUATION.md 7.3 carries the corrected counts (W2-RB18).
8. **Reconcile the Phase 15 billed total and the 0.312015 estimate gap** [4.1, 6.2.3].

## Appendix: verification notes

- Round R1 and R2 repairs followed reviews 25 and 26. Review 26 checked that all relative links exist and that every cited section number exists. In R2 I read `decisions.md` (P16-47) and `review-26-verdict.md`, and cited the `human-commands.md` h4-diagnostic-2 row as the review reports it.
- Links in this file point to files confirmed to exist at the time of writing: `experiments/EVALUATION.md`, `experiments/RESULTS.md`, `experiments/LIMITATIONS.md`, `experiments/benchmark/README.md`, `experiments/g-retrieval/README.md`, `experiments/h-answers/README.md`, `experiments/evaluation/er-README.md`, `docs/agent-runs/phase-16/plan.md`, `decisions.md` and `human-commands.md`. Apart from the P16-47 entry of `decisions.md` read in R2, I did not read `decisions.md`, `human-commands.md`, RESULTS.md, LIMITATIONS.md or the READMEs for this file. Statements drawn from them are quoted as EVALUATION.md cites them.
- Section references use the numbering of EVALUATION.md as read on 2026-11-19, and no heading anchors are used, so a renumbering would need a recheck.
- This file copies no question text, answer text, bucket name, ARN, account id or 12-digit number.
