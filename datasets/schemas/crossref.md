# Crossref work metadata

Schema version: note version 1, dated 2026-11-19. This is a documentation
version, not an upstream one. Upstream versions recorded in the repository:
snapshot `2026-03` (`datasets/dataset-manifest.yaml:298`) and manifest
version 1 (`datasets/dataset-manifest.yaml:2`). The Phase 5 subset rows are
not read from that data file: they are allowlisted projections of live REST
API pulls, with the subset retrieval date 2026-11-07
(`datasets/dataset-manifest.yaml:376`, `:532`). Records normalize to core
contract schema version 1
(`modules/core/src/main/scala/scholarchain/core/Contracts.scala:9`).

The March 2026 annual public data file contains Crossref work JSON Lines.
Fixture records project fields from the public REST API's unwrapped
`message` object for two DBLP-linked DOIs.

- Preserve `DOI` as the record key. Normalize case and unwrap known DOI
  URLs when joining DBLP electronic editions or OpenAlex work DOIs.
- DOI case drift between the evidence URLs and the stored keys is intentional.
  The manifest's sample evidence URLs keep the registered suffix case
  (`10.1109/JCDL.2017.7991609`), while the stored record key is lowercased
  (`10.1109/jcdl.2017.7991609`) so joins need no case folding. DOIs are
  case-insensitive, so the evidence URL is an equivalent request rather than a
  literal round-trip of the stored key.
- Preserve ordered `author` entries, contributor role and any explicitly
  supplied `ORCID`. Absent ORCID is common. Do not manufacture it from
  author name or infer individual identity from DOI alone.
- Fields such as `issued.date-parts`, `container-title`, `ISSN`,
  affiliations, `reference` and `relation` vary by deposit. Treat title
  and venue arrays as source observations. Distinguish deposited work date
  from ingestion, indexing, and update timestamps.
- Use factual bibliographic metadata and available relation identifiers.
  Abstracts retain publisher or author rights. The fixture excludes abstracts,
  full-text links, free-text affiliation, and contact data.
- The torrent info hash identifies the torrent metadata. It is not an
  archive digest. The torrent's piece checks apply at acquisition.

## Field table

Sources: the fixture `datasets/samples/crossref.jsonl` (two rows, unwrapped
`message` objects) and the normalizer
`modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/SourceNormalizers.scala`
(`SourceNormalizers.scala:N`, function `crossref` from line 362). Types are the
JSON types visible in the fixture. The normalizer also accepts a record wrapped
in `message` (`SourceNormalizers.scala:367-371`).

| Field | Type | Meaning | Used for |
|---|---|---|---|
| `DOI` | string | Work DOI, lowercased in the fixture | record key and join key; required (`SourceNormalizers.scala:372`, `:376-378`) |
| `title` | array of strings | Work title; first element used | title; required (`SourceNormalizers.scala:373`, `:379-382`) |
| `container-title` | array of strings | Venue name; first element used | venue name and venue id (`SourceNormalizers.scala:440-443`) |
| `ISSN` | array of strings | Venue ISSNs (row 1 only) | venue ISSNs and join key (`SourceNormalizers.scala:436-439`, `:488-489`) |
| `issued.date-parts` | array of arrays of integers, for example `[[2009, 8]]` | Year and month of the work date | year is the first element of the first array (`SourceNormalizers.scala:445-451`) |
| `type` | string | Work type, for example `journal-article`, `proceedings-article` | `recordType`, default `crossref-work` (`SourceNormalizers.scala:521`) |
| `author` | array of objects | Ordered authors; array order is the author sequence | author mentions, position is index plus 1 (`SourceNormalizers.scala:392-406`) |
| `author[].family` | string | Family name | name and normalized name (`SourceNormalizers.scala:394`, `:410-415`) |
| `author[].given` | string | Given names | name and normalized name (`SourceNormalizers.scala:395`) |
| `author[].sequence` | string | `first` or `additional` | kept as a cross-check (`SourceNormalizers.scala:359-361`, `:419-420`) |
| `author[].name` | string | Literal name for an entry without family and given | mention name when present (`SourceNormalizers.scala:396-400`); not in the fixture |
| `author[].ORCID` | string | Explicit author ORCID iD | join key (`SourceNormalizers.scala:416-418`, `:471-485`); not in the fixture |
| `author[].affiliation[].id` | string | Affiliation identifier, read as ROR | join key (`SourceNormalizers.scala:495-500`); not in the fixture, free-text affiliation is never read (`:424-426`) |
| `abstract` | string | Abstract | only presence is read, to build a reference; the fixture omits it (`SourceNormalizers.scala:517-518`) |
| `message` | object | REST wrapper around a work | unwrapped when present (`SourceNormalizers.scala:367-371`) |

Publisher references:
[annual public data file](https://www.crossref.org/services/metadata-retrieval/public-data-file/),
[license scope](https://www.crossref.org/documentation/retrieve-metadata/),
[REST format](https://www.crossref.org/documentation/retrieve-metadata/rest-api/).
