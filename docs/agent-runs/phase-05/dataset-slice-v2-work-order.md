# Work order v2: dataset-engineer, DBLP subset from the local dump (supersedes the overlap-slice approach)

Human direction (verbatim intent): skip register/validate/estimate for the 3-paper slice. Extract a bounded DBLP subset from the local dump: all works of a list of common-name authors plus their coauthors, a few thousand works, with a documented cap. Run Phase 4 on it locally, then sample about 230 stratified pairs from the actual candidate output. Use OpenAlex, ORCID and Crossref only to enrich pairs, mainly the exact-ID stratum, not as a filter. Give any commands that need to run outside the sandbox. Do not commit.

## Task (datasets/** only)
1. Subset extraction from /Users/winnguyen/scholarchain-data/dblp/dblp.xml.gz (human-verified SHA-256 356634eef5696aef7ba4d07d864257ddcacae55c95f64a755570801df0a82fa1, 1116868681 bytes; you cannot read that directory, the human runs the commands). Reuse the existing streaming scanner and hash verification.
   - Seed authors: a documented list of common names (include Wei Wang, Li Zhang, Jun Wang, Yang Liu, Jing Li, plus a few more such as Xin Li, Yan Zhang, Hui Li, Lei Zhang, Ying Zhang), matched on DBLP's name forms including the numeric homonym suffix (for example "Wei Wang 0001").
   - Keep ALL works of the seed authors across years (no year/author-count/DOI filter), then add works of their coauthors (one hop), under explicit caps: a hard cap on total works (target a few thousand, documented), a cap per seed name, and a cap on works with very large teams (state the rule). Document what is dropped when caps bind, deterministically (stable ordering, no randomness without a fixed seed).
   - Preserve DBLP records verbatim, named HTML entities included. The output is a well-formed XML document that the Phase 4 DBLP reader accepts (check how modules/hadoop reads DBLP input and keep the same record layout and DTD handling).
   - Output location: small enough to track in git only if under about 10 MiB; otherwise write outside the repo (for example /Users/winnguyen/scholarchain-data/slice/) and record path, size and SHA-256 in the manifest. Do not edit .gitignore (report to build-engineer if needed).
2. Enrichment, not a filter: for works that have a DOI, fetch OpenAlex, Crossref, and ORCID (when an ORCID appears) records with the existing bounded fetchers, cache, request bound and challenge-page detection. Missing enrichment must never remove a DBLP work. Prioritize DOIs that would form the exact-ID stratum. State the request bound and how it is spent.
3. Manifest: replace/retire the overlap-slice registration cleanly (do not leave the 3-paper slice registered); record subset rule, caps, counts, seed list, checksums, retrieval date, the DBLP release mismatch note, licenses copied from the existing dataset entries (never invented).
4. Before implementation, list which specific test proves each gate item. Tests are fixture-based with a tiny synthetic gz (named entities, homonym suffixes, caps binding deterministically).

## Constraints
- Do not run Python (guard blocks interpreters); give the exact `!` commands, including expected output and a validation command.
- No credentials, no challenge solving or evasion.
- Do not touch modules/**, tests/**, or run the Phase 4 pipeline (the orchestrator routes that step).

## Report
Required contract, plus: seed list, caps and estimated work counts, expected size, exact `!` commands, unverified items.
