# DOMAIN-ENGINEER REPORT FOR ARCHIVAL

STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: docs/agent-runs/phase-03/domain-work-order.md

FILES CREATED: modules/core/README.md; modules/core/src/main/resources/scholarchain-local.v1.yaml; main Scala under scholarchain/core: Contracts.scala, ContractWire.scala, ScholarChainConfig.scala, StableIds.scala, WireEnums.scala; test Scala under scholarchain/core: ConfigSuite.scala, ContractSuite.scala.

FILES MODIFIED: None of the baseline tracked files. The successful build generated its ignored marker.

COMMANDS RUN: Pinned sbt 1.11.7 with Eclipse Adoptium JDK 21.0.13 and SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false. `core / compile` passed. Final `core / scalafmtAll`, `core / test`, and `check` all exited 0 at 19:00:26 America/Chicago. `core / test` and `check` each reported 17 passed, zero failures and errors.

TEST RESULTS: Frozen WorkRecord and MatchDecision wire objects, coauthor and graph-edge ordering, confidence limits, missing/null/blank provenance, unsupported/missing/fractional/quoted schema versions, invalid ID shapes, reversed windows, enum values, claim evidence, UUID/run manifest, configuration validation and precedence. Initial frozen fixture was corrected to the actual pinned uPickle option representation (value or null). An adversarial version test exposed number/string coercion from the standard Int codec, so wire versions now use a strict numeric codec.

VALIDATION RESULTS: Implemented all 20 required versioned Scala contracts and RunManifest, version 1, with nested provenance. SourceType includes DBLP, OpenAlex, ORCID, and Crossref. Frozen sample WorkRecord fields include `schemaVersion: 1`, `workId: work:v1:dblp:Zml4dHVyZS93b3Jr`, `source: dblp`, `year: 2024`, `doi: null`, and a versioned provenance object. MatchDecision freezes `decision: ACCEPTED` and bounded numeric confidence.

ID FORMULAS: External IDs are kind:v1:namespace:unpadded-base64url(canonical source ID). Internal work uses namespace, snapshot, fingerprint and internal researcher uses versioned identity key. Hashed IDs use SHA-256 over kind, version and key parts, with each UTF-8 value length-prefixed by four big-endian bytes. Coauthor key uses strictly ascending distinct full researcher IDs, inclusive window endpoints and policy version. Candidate pairs sort mention IDs while keeping associated author positions; mention IDs derive from work and one-based author position. Chunk/profile key uses entity, source version, document type, optional ordinal and SHA-256 of text. Graph key uses type, endpoints (sorted only for undirected coauthorship), policy and sorted distinct source evidence IDs. Evidence uses source, snapshot, record ID, kind and locator. Run IDs use random UUIDv4 retained in immutable RunManifest.

CONFIGURATION: Environment allowlist overrides versioned file values before validation. File uses documented JSON-compatible YAML 1.2 subset parsed by pinned uPickle/ujson. All settings are sourced from file/environment, and sample values are local nonsecret placeholders. No new dependency pin. Use `ContractWire` at wire boundaries.

ASSUMPTIONS: Source producers validate and canonicalize external identifiers before issuing work or researcher IDs. Dataset artifact acquisition and snapshot evidence are tracked by dataset-engineer.

RISKS: YAML is intentionally limited to the documented JSON-compatible subset. Specific runtime endpoints, models and policy values remain environment inputs. No unresolved dataset question blocks the core contract.

MANUAL ACTION REQUIRED: None for the domain work order.

BLOCKED-ON OWNER/EVIDENCE: None.

NEXT GATE: Orchestrator integrated check and independent code review.
