# OpenAlex works and embedded authorships

Schema version: note version 1, dated 2026-11-19. This is a documentation
version, not an upstream one. Upstream versions recorded in the repository:
snapshot `2026-10-14` (`datasets/dataset-manifest.yaml:87`) and manifest
version 1 (`datasets/dataset-manifest.yaml:2`). The Phase 5 subset rows are
not read from that snapshot: they are allowlisted projections of live API
pulls, with the subset retrieval date 2026-11-07
(`datasets/dataset-manifest.yaml:376`, `:532`). Records normalize to core
contract schema version 1
(`modules/core/src/main/scala/scholarchain/core/Contracts.scala:9`).

The 2026-10-14 public snapshot contains gzip-compressed JSON Lines in entity
and `updated_date` partitions. A partition date is the modification date,
not publication date. Capture the manifest and object integrity at acquisition
because the public S3 prefix changes in place on refresh.

- The work's `id` is an OpenAlex W ID. `doi` is optional and normally a DOI
  URL. Retain both and normalize DOI for exact joins. The stored `doi` is
  lowercased (`10.1109/jcdl.2017.7991609`) while the manifest's evidence URL
  keeps the registered suffix case (`JCDL`). DOIs are case-insensitive, so this
  drift is intentional and the evidence URL is an equivalent request rather
  than a literal round-trip of the stored value.
- Preserve `authorships` order and the source `author_position` label.
  The nested author A ID and optional ORCID supply different identity signals.
  Indexed authors may split or merge and can share incorrect ORCIDs.
- Institution IDs and optional ROR identify organizations, topics carry T IDs
  and scores, and source IDs can link venues. Do not infer missing affiliation.
- Abstracts, where available, can appear as an inverted index. Fixture rows
  omit abstract, contact data and text URLs. Do not assume that linked full
  text is reusable merely because the OpenAlex metadata is CC0.
- The two fixture rows retain an allowlist of fields from individual public
  Work API responses. Their DOI and source IDs were observed directly. The
  first work's display title differs from DBLP's title, so the DOI is the
  exact join key. Keep the source title for audit.

## Field table

Sources: the fixture `datasets/samples/openalex.jsonl` (two rows) and the
normalizer `modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/SourceNormalizers.scala`
(`SourceNormalizers.scala:N`, function `openAlex` from line 87). Types are the
JSON types visible in the fixture. The full upstream entity has more fields
than the allowlisted projection shows, and those are not described here.

| Field | Type | Meaning | Used for |
|---|---|---|---|
| `id` | string (URL) | OpenAlex Work ID, for example `https://openalex.org/W2147107544` | join key and source record id; required (`SourceNormalizers.scala:92-93`) |
| `doi` | string (URL) or null | DOI URL, stored lowercased in the fixture | join key; also source of the arXiv id (`SourceNormalizers.scala:109-111`) |
| `display_name` | string | Work title | title; required (`SourceNormalizers.scala:94`, `:100-103`) |
| `publication_year` | integer | Publication year | `year` (`SourceNormalizers.scala:254`) |
| `type` | string | Work type, for example `article`, `conference-paper` | `recordType`, default `openalex-work` (`SourceNormalizers.scala:275-276`) |
| `updated_date` | string (timestamp) | Source update time (fixture value `2026-11-04T10:54:05.112534`) | not read by `SourceNormalizers.scala`; other use UNVERIFIED |
| `authorships` | array of objects | Ordered authors; array order is the author sequence | author mentions, position is index plus 1 (`SourceNormalizers.scala:112-132`) |
| `authorships[].author.id` | string (URL) | OpenAlex Author ID (A ID) | join key (`SourceNormalizers.scala:120-122`) |
| `authorships[].author.display_name` | string | Author name | mention name; a missing name rejects the record (`SourceNormalizers.scala:119`, `:149-152`) |
| `authorships[].author.orcid` | string (URL) or null | Author ORCID iD, null when absent (fixture row 2) | join key, kept when `ExactIdentifiers.orcid` accepts it (`SourceNormalizers.scala:123-125`, `:195-219`) |
| `authorships[].author_position` | string | Coarse label `first`, `middle` or `last` | kept as a cross-check, not as the ordering key (`SourceNormalizers.scala:82-86`, `:139-140`) |
| `authorships[].institutions[].id` | string (URL) | OpenAlex Institution ID (I ID) | institution id and join key (`SourceNormalizers.scala:175-178`) |
| `authorships[].institutions[].display_name` | string | Institution name | affiliation text and institution name (`SourceNormalizers.scala:142-143`, `:178`) |
| `authorships[].institutions[].ror` | string (URL) | ROR identifier | join key (`SourceNormalizers.scala:186-188`) |
| `authorships[].institutions[].country_code` | string | Country code | read when present (`SourceNormalizers.scala:183`); not in the fixture |
| `topics[].id` | string (URL) | OpenAlex Topic ID (T ID) | topic id (`SourceNormalizers.scala:154-160`) |
| `topics[].display_name` | string | Topic name | topic name (`SourceNormalizers.scala:160`) |
| `topics[].score` | number | Topic score (for example 0.6416 in row 1) | not read by `SourceNormalizers.scala`; other use UNVERIFIED |
| `abstract_inverted_index` | object | Abstract as an inverted index | only presence is read, to build an abstract reference; the fixture omits it (`SourceNormalizers.scala:270-271`) |

Publisher references:
[public snapshot](https://help.openalex.org/access/snapshot/),
[JSONL manifest](https://openalex.s3.amazonaws.com/data/jsonl/manifest.json),
[work entity](https://help.openalex.org/entities/works/).
