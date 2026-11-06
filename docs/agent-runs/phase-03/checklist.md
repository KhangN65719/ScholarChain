# Phase 3 course acceptance checklist

[Course Phase 3 definition of done](https://github.com/0x1DOCD00D/ScholarChain/blob/main/README.md#phase-3-define-contracts-configuration-and-dataset-manifests)

- [x] Verify accepted Phase 2 baseline and dispatch separate domain and dataset work orders.
- [x] Record the human-selected portfolio of DBLP, OpenAlex, ORCID and Crossref.
- [x] Define all 20 immutable records with versioned contracts and provenance.
- [x] Define stable work, researcher, coauthor edge, chunk/profile, graph edge, evidence and run IDs and an immutable run manifest.
- [x] Define source, match, evidence, metric, operation and verification enums.
- [x] Load versioned configuration plus environment overrides for deployment, thresholds, models, graph windows and bridge settings.
- [x] Test serialization, frozen JSON, schema version, provenance, confidence, IDs, undirected pair order and time-window order.
- [x] Register all four sources in a manifest with source, license, snapshot, format, size, S3 prefixes, checksums, subset, schema, linkage, updates, limitations, privacy and validation.
- [x] Include legal representative samples and source schema notes.
- [x] Verify bounded idempotent download and sampling, with local bounded defaults and explicit network use.
- [x] Run integrated sbt check, manifest validation and repeated local sample replay.
- [x] Preserve both owner reports, observed verification, independent verdict and any repair cycles.
- [x] Obtain independent approval with evidence-backed dataset licenses, identifiers and source claims.
- [x] Present the candidate for human review before Phase 4.

## Open gates

- Human acceptance of the uncommitted Phase 3 candidate.
- Native Codex hook invocation and enforcement of specialist path and reviewer tool restrictions remain unverified. This run used role-scoped work orders and read-only reviewer inspection.
- Full corpus byte verification is deferred to acquisition. The Phase 3 manifest distinguishes publisher digests, local fixture hashes and the Crossref torrent info hash. OpenAlex publishes an inventory without an aggregate corpus digest.
