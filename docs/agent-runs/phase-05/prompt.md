# Phase 5 course specification (verbatim)

Source: <https://github.com/0x1DOCD00D/ScholarChain/blob/main/README.md#phase-5-implement-entity-resolution>

Raw source: <https://raw.githubusercontent.com/0x1DOCD00D/ScholarChain/main/README.md>. Copied from the local copy `target/course-README.md`, section at lines 1200-1247, ending immediately before `## Phase 6: build the temporal coauthorship graph and run Spark analytics`. The local copy's Phase 4 section sits at lines 1107-1199, the same lines Phase 4's prompt.md recorded for the raw source, but this file was not re-fetched or byte-compared for Phase 5.

The text below is reproduced verbatim from that copy. Do not edit it. Orchestrator additions to the work order belong in a separate file.

---

## Phase 5: implement entity resolution

Purpose:

Resolve records across datasets while preserving uncertainty and auditability.

Why:

A title or author-name match is not identity. Incorrectly merging two researchers or splitting one researcher into several identities corrupts coauthor counts, shortest paths, components, communities, bridge scores, team-size attribution, and every LLM answer downstream. Entity resolution must be treated as an evaluated model, not as a convenient string comparison.

Use this work order:

```text
Use the entity-resolution-engineer agent to implement the ScholarChain entity-resolution subsystem against the Phase 3 contracts and Phase 4 MatchCandidate records.

Required behavior:
- Exact identifier rules for DOI, arXiv ID, ORCID, OpenAlex ID, Semantic Scholar ID, and repository URL.
- Normalization for Unicode, punctuation, whitespace, title markup, initials, name order, and DOI prefixes.
- Feature functions for researcher-name similarity, initials, author order, coauthor overlap, affiliation compatibility, ORCID or external IDs, title similarity, year distance, venue compatibility, citation-neighborhood evidence when available, and source reliability.
- A versioned scoring policy.
- Separate thresholds for accepted, rejected, and unresolved results.
- Conflict detection when authoritative identifiers disagree.
- LLM adjudication only for a bounded unresolved band.
- Strict JSON schema for LLM adjudication with accept, reject, and abstain outcomes.
- No LLM call may alter source records or create an identifier not present in the candidates.
- Cache LLM adjudications by input hash, prompt version, model version, and policy version.
- Produce accepted, rejected, unresolved, and conflict outputs.
- Generate a review queue for human-labeled evaluation examples.

Evaluation requirements:
- At least 200 manually reviewed candidate pairs.
- Precision, recall, F1, confusion matrix, and coverage, reported separately for researcher-identity resolution and work resolution when both are implemented.
- Metrics by match path: exact ID, deterministic bibliographic, probabilistic, and LLM-adjudicated.
- Error analysis with at least ten false positives and ten false negatives when available.
- Threshold sensitivity analysis.

Run sbt check and the gold-set evaluation.
Report the policy version, feature definitions, thresholds, model and prompt versions, confusion matrix, unresolved rate, and representative errors.
```

Gate:

- Exact ID conflicts do not silently merge.
- The LLM can abstain.
- Evaluation examples are traceable to source records.
- Gold labels are human-reviewed.
- Precision and recall are reported separately, not hidden behind one aggregate score.
- The reviewer verifies there is no data leakage from evaluation labels into matching logic.
