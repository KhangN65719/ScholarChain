# Phase 5 Ratification

- Decision: ACCEPTED by the human owner (Khang), 2026-11-07.
- Basis: ratification-summary.md; review-verdict-round1.md through review-verdict-round3.md and the final evidence-only round (no code defects left).
- Gate: the final bare `sbt check` ended [success] at 6:01:27 PM, and `.claude/.last-test-run` mtime is 18:01. The eleventh full run totals are core 41, tests 240, entity-resolution 366 and hadoop 207, all with 0 failures. The Python dataset tests passed and validate is clean. The v6 pipeline output is byte-identical to v5.
- Gold set: gold-v3.jsonl, 230 blind human labels, archived under gold/ with GOLD-MANIFEST.json.
- Results (policy 1.4.0): precision 1.000, recall 0.802, F1 0.890, the same across policy versions 1.1.0 to 1.4.0.

## Accepted known limits
- Only the exact-ID path accepts, and its precision holds by construction. The LLM path decided nothing (offline stub abstains).
- Only the 77 pass-2 labels were deliberate-paced. Pass 3 was fast, and 12 clear-non-match "same" labels are likely labeling errors (recall bound 0.917 if they were "different"). The labels are not corrected.
- The export excluded 5,766 same-name agreeing-context pairs, so precision does not measure the probabilistic path.
- The same-DOI/arXiv distinct-mention rule and the same-record CONFLICT rule rest on synthetic fixtures only.
- The thresholds are hand-set and unvalidated. None were tuned on the gold set.
- The hadoop-engineer report body is still partial.

## Deferred rulings (current behaviour stays)
- Non-DOI work-ID conflicts.
- Aggregator ID on two slots of one record with no work ID.

## Excluded from the commit
- datasets/slice/ (the retired 3-paper slice data; nothing references it), .DS_Store, .codex/, .agents/, AGENTS.md, codex-prompt-writer.md, .p5-logs/.
