# Review B12c, re-review 3 (code-reviewer, Opus), as received 2026-11-19 (condensed by the orchestrator)

Verdict: REQUEST CHANGES (two wording fixes, documentation-engineer).

## Must fix
1. `final-report.md:83` (M5): "a slash between two range bounds" changes the meaning. The slash separated two ranges (`decisions.md:29`). Say "a slash separating two ranges became a semicolon".
2. `:69`: M11 comes from P17-29, not P17-27.

## Advisory
- `:82` still contains "D 9/9", the only digit/digit pattern left in the file, which may trip the V2 pre-scan.
- `:29` "the query was not run as written" should be "no record shows it was run as written".

## Checked correct
- The section 2 quotes are verbatim (`prompt.md:309, 314, 318, 322`).
- All 13 table rows match their sources (DESIGN, Coauthorship, CollaborationPipeline, EVALUATION 1.1-1.3, 6.1.3, 6.2.4, 6.3), and the gaps are honest.
- M4 keeps its meaning.
- M11 matches P17-29.
- The class-4 list matches through P17-29.

## Orchestrator ruling
Fix both must-fix items and apply both advisories in the same round. For `:82`, write the fractions in words as `c830b90` did, keeping the meaning. This avoids a further round.
