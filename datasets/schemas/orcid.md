# ORCID registry public file

Schema version: note version 1, dated 2026-11-19. This is a documentation
version, not an upstream one. Upstream versions recorded in the repository:
snapshot `2026-10-13-v1` (`datasets/dataset-manifest.yaml:155`), the public
API version `v3.0` of the fixture responses (`datasets/dataset-manifest.yaml:272`)
and manifest version 1 (`datasets/dataset-manifest.yaml:2`). The Phase 5
subset rows are not read from the annual file: they are allowlisted
projections of live person API pulls, with the subset retrieval date
2026-11-07 (`datasets/dataset-manifest.yaml:376`, `:532`). Records normalize
to core contract schema version 1
(`modules/core/src/main/scala/scholarchain/core/Contracts.scala:9`).

ORCID's October 2026 annual file is a gzip-compressed tar collection of XML
record summaries and 11 activity partitions. The ORCID iD and put-code identify
the researcher and a registry activity, respectively. Activity identity also
depends on its researcher and section. Consume tar members without extracting
paths to disk and parse by XML namespace.

- Validate the ORCID check digit, preserve the original iD, and join against
  DBLP's verified person links or OpenAlex author ORCIDs. `datasets.py`
  performs this check itself: `validate()` applies ISO 7064 MOD 11-2 to every
  ORCID-shaped string in the manifest and in all four sample fixtures, so a
  mistyped iD fails the dataset gate rather than reaching a consumer.
- Separate self-asserted names and activities from institution or publisher
  assertions. Keep the assertion source and external identifier type and
  relationship. For works, prefer DOI; for affiliations retain organization
  identifiers such as ROR when present.
- Honor the registry's visibility settings. Snapshots can retain previously
  public data, so account for removed or hidden records before publishing
  derived data. Exclude email, biography, keywords and other personal fields
  from development fixtures.
- The sample is projected JSONL from two public v3.0 `/person` responses,
  rather than an archive XML member. It retains the response `path` and
  public name components. Extract the canonical iD from `name.path`;
  the outer `path` identifies the API resource.

## Field table

Sources: the fixture `datasets/samples/orcid.jsonl` (two projected `/person`
responses) and the normalizer
`modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/SourceNormalizers.scala`
(`SourceNormalizers.scala:N`, function `orcid` from line 298). The fixture is
JSON; the bulk archive is XML, whose element names this note does not list
(UNVERIFIED).

| Field | Type | Meaning | Used for |
|---|---|---|---|
| `name.path` | string | Canonical ORCID iD, for example `0000-0001-7580-4351` | join key and researcher id; required, check digit verified (`SourceNormalizers.scala:303-306`, `:307-310`; `datasets/scripts/datasets.py:69-76`) |
| `name.given-names.value` | string | Given names | display name and alias (`SourceNormalizers.scala:318`, `:320-322`) |
| `name.family-name.value` | string | Family name | display name and alias (`SourceNormalizers.scala:317`) |
| `name.credit-name` | object with `value`, or null | Credit name, null in both fixture rows | display name when present, takes precedence (`SourceNormalizers.scala:319-322`) |
| `name.visibility` | string | Registry visibility setting, `public` in the fixture | not read by `SourceNormalizers.scala`; other use UNVERIFIED |
| `path` | string | API resource path, for example `/0000-0001-7580-4351/person` | not read by the normalizer; identifies the API resource (note text above) |

Publisher references:
[versioned release](https://doi.org/10.23640/07243.34697766.v1),
[bulk format](https://info.orcid.org/documentation/integration-guide/working-with-bulk-data/),
[public file use policy](https://info.orcid.org/public-data-file-use-policy/),
[public API terms](https://info.orcid.org/public-client-terms-of-service/).
