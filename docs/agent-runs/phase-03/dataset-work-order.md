# Phase 3 dataset-engineer work order

Baseline main efcd46bf55b95b4d62ffef59b8556cd886097216. Read CLAUDE.md, docs/agents.md and .claude/agents/dataset-engineer.md as supplied authority. Own only datasets. Start with DBLP and its DTD. The orchestrator will relay the human selection of at least three enrichment datasets. Proceed with independent DBLP and common tooling work while selection is pending.

- Create datasets/dataset-manifest.yaml with per-dataset source, license, snapshot, format, size estimate, S3 prefixes, checksums, subset rule, schema, linkage keys, update method, limitations, privacy notes and validation commands.
- Verify dataset IDs, snapshots, license terms and checksums from primary evidence. Clearly distinguish publisher checksums, locally verified fixture hashes and unavailable full snapshot digests.
- Create representative legal checked-in samples under datasets/samples and normalization/schema notes under datasets/schemas.
- Create bounded, idempotent download and sample tools under datasets/scripts. Default to local bounded samples. Explicitly cap bytes, time and rows as applicable and fail safely. Exercise repeatability and bounds. Avoid cloud operations and full corpus acquisition.
- Validate the manifest and replay a bounded sample. Report commands, outputs, files, primary evidence, checksums, licenses, bounds, linkage keys, assumptions and next gate.

Course reference: https://github.com/0x1DOCD00D/ScholarChain/blob/main/README.md#phase-3-define-contracts-configuration-and-dataset-manifests

## Human selection and dispatch amendment

Use OpenAlex, ORCID and Crossref alongside DBLP. Prioritize a DOI shared across the actual fixture records and an ORCID link where verifiable. Keep publication metadata for upstream releases distinct from local sample acquisition metadata.
