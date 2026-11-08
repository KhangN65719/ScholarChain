# Work order: dataset-engineer, larger bounded real-data slice

Orchestrator to `dataset-engineer`. Phase 5, step 2. Narrowed to datasets/** only.

## Goal
Prepare a larger bounded slice of real data (not the tiny committed samples) sufficient to yield about 230 candidate pairs once the Phase 4 pipeline runs on it. Candidate pairs here means Phase 4 `MatchCandidate` records across DBLP and the enrichment datasets, spanning: exact-identifier matches (DOI etc.), clear non-matches, and hard common-name cases.

## Requirements
- Use the existing bounded retrieval/sampling scripts in `datasets/scripts/` where possible; extend them idempotently. No full-corpus download by default; state explicit size bounds.
- Prefer a slice where DBLP and OpenAlex/Crossref/ORCID records overlap (shared DOIs) so exact-ID pairs exist, plus several common names (for example Wei Wang, Li Zhang, Jun Wang, Yang Liu, Jing Li) for hard cases. Size should be justified against the ~230 pair target; say how you estimated it.
- Record in the manifest: source URLs, retrieval date, SHA-256, licenses, size, subset rule, linkage keys. Do not invent licenses or sources.
- Keep large generated data out of git-tracked sample paths unless small; say where it lives and whether it is gitignored (do not edit .gitignore; that belongs to build-engineer, report it if needed).
- Do not edit modules/**, tests/** or anything outside datasets/**.
- If a download needs network the sandbox blocks, STOP and report the exact command for the human to run with the `!` prefix, the expected output path, and the validation command to run after. Do not attempt to bypass the sandbox. No credentials.
- DBLP uses named HTML entities; a parallel hadoop-engineer repair handles parsing. Do not strip or rewrite entities in the data.

## Gate
Validate the manifest and replay a bounded sample (existing validation commands). Before implementation, list which specific test or check proves each gate item.

## Report
Required report contract, plus: sources, checksums, licenses, size bounds, linkage keys, expected candidate-pair estimate, and any exact `!` command needed.
