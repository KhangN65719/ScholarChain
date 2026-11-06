# ScholarChain core contracts

Use `ContractWire.encode(record)` and `ContractWire.decode[Record](json)` at wire boundaries. Each top-level record and its provenance carry a required integer `schemaVersion`. Version 1 is supported. The record constructors validate schema versions and invariants for both Scala producers and decoded records. An optional value is written as its JSON value or `null`. Contract tests freeze representative wire values and member names.

Stable identifiers use explicit type and algorithm version prefixes. External work and researcher IDs preserve the canonical source namespace and unpadded base64url of the UTF-8 source ID. The producer validates source-specific identifiers before passing them to the ID factory. Internal IDs use SHA-256 over type, algorithm version and the ordered key fields, each preceded by its four-byte big-endian UTF-8 byte length. Order undirected endpoints lexicographically by full ID and preserve the associated author positions when sorting mentions. Directed graph endpoints preserve direction. The graph-edge formula treats source evidence IDs as a sorted set. Run IDs use random UUIDv4 and the immutable `RunManifest` carries the run ID with its input snapshot and configuration version references.

## Local configuration

`src/main/resources/scholarchain-local.v1.yaml` is a versioned, nonsecret local example. Configuration uses the JSON-compatible subset of YAML 1.2. Write a JSON object using quoted keys, without block-style YAML, aliases, or tags. Pass its path to `ConfigLoader.load`. The loader applies explicitly supported environment overrides before validating the merged configuration. An override can supply a missing setting. A malformed override fails loading. The file always supplies `schemaVersion`.

| File field | Environment override |
| --- | --- |
| `rawBucket` | `SCHOLARCHAIN_RAW_BUCKET` |
| `curatedBucket` | `SCHOLARCHAIN_CURATED_BUCKET` |
| `graphEndpoint` | `SCHOLARCHAIN_GRAPH_ENDPOINT` |
| `vectorEndpoint` | `SCHOLARCHAIN_VECTOR_ENDPOINT` |
| `awsAccountId` | `SCHOLARCHAIN_AWS_ACCOUNT_ID` |
| `answerModelId` | `SCHOLARCHAIN_ANSWER_MODEL_ID` |
| `embeddingModelId` | `SCHOLARCHAIN_EMBEDDING_MODEL_ID` |
| `matchAcceptThreshold` | `SCHOLARCHAIN_MATCH_ACCEPT_THRESHOLD` |
| `matchRejectThreshold` | `SCHOLARCHAIN_MATCH_REJECT_THRESHOLD` |
| `graphStartYear` | `SCHOLARCHAIN_GRAPH_START_YEAR` |
| `graphEndYear` | `SCHOLARCHAIN_GRAPH_END_YEAR` |
| `bridgeAlgorithm` | `SCHOLARCHAIN_BRIDGE_ALGORITHM` |
| `bridgeSampleSize` | `SCHOLARCHAIN_BRIDGE_SAMPLE_SIZE` |
| `bridgeRandomSeed` | `SCHOLARCHAIN_BRIDGE_RANDOM_SEED` |

Quote the 12-digit account ID and the decimal random seed in the file. Quoting the seed preserves all signed 64-bit values across JSON readers.
