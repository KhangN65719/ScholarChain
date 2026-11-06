# Phase 3 domain-engineer work order

Baseline main efcd46bf55b95b4d62ffef59b8556cd886097216. Accepted build 32ebc6d2ac21f1f351577b38ba27f9cfb355d259. Read CLAUDE.md, docs/agents.md and .claude/agents/domain-engineer.md as supplied authority. Own only modules/core. Use approved uPickle 4.4.3 and MUnit 1.1.1. Escalate any missing required pin. Coordinate shared sbt and formatting through the orchestrator.

- Define immutable, versioned Scala 3 contracts with provenance for WorkRecord, AuthorMention, CanonicalResearcher, AuthorshipRecord, PublicationTeam, CoauthorPairCandidate, CoauthorEdge, ResearcherMetrics, Venue, Institution, Topic, Provenance, MatchDecision, GraphNode, GraphEdge, EvidenceItem, EvidenceBundle, QueryPlan, AnswerClaim and AnswerResponse. Consult the course core data model and wire requirements.
- Define source type, match outcome, evidence type, collaboration metric, query operation and verification status enums with stable wire values.
- Define stable work IDs from authoritative namespace and ID where available, researcher IDs from validated external IDs or versioned internal identity, coauthor edge IDs from ordered researchers, window and policy, chunk/profile IDs from entity, source version, document type, ordinal as needed and text hash, graph edge IDs from type, canonical endpoints, policy and evidence, stable evidence IDs, and timestamp-independent unique run IDs stored with an immutable run manifest contract.
- Load versioned HOCON or YAML configuration with environment overrides. Externalize buckets, endpoints, thresholds, models, AWS accounts, graph windows and bridge parameters. Keep secrets out.
- Validate provenance, confidence, schema versions, stable ID formats, strict undirected order and year-window ordering at contract boundaries. Exercise representative frozen JSON, round trips, nested invalid input and configuration precedence in MUnit.
- Run core tests and sbt check. Report files, results, wire examples, ID formulas, pair rule, config precedence, schema versions, unresolved data questions and next gate.

Course reference: https://github.com/0x1DOCD00D/ScholarChain/blob/main/README.md#phase-3-define-contracts-configuration-and-dataset-manifests

## Dispatch amendments

The selected sources are DBLP, OpenAlex, ORCID and Crossref. Include them in SourceType. The core compile dependency graph has no HOCON or YAML parser. The domain owner will use the YAML 1.2 JSON-compatible subset, parse it using the existing uPickle dependency and document the supported grammar. Use the pinned sbt launcher with SBT_SERVER_FORCESTART=true and SBT_SERVER_AUTOSTART=false.
