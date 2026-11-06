# ORCID registry public file

ORCID's October 2026 annual file is a gzip-compressed tar collection of XML
record summaries and 11 activity partitions. The ORCID iD and put-code identify
the researcher and a registry activity, respectively. Activity identity also
depends on its researcher and section. Consume tar members without extracting
paths to disk and parse by XML namespace.

- Validate the ORCID check digit, preserve the original iD, and join against
  DBLP's verified person links or OpenAlex author ORCIDs.
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

Publisher references:
[versioned release](https://doi.org/10.23640/07243.34697766.v1),
[bulk format](https://info.orcid.org/documentation/integration-guide/working-with-bulk-data/),
[public file use policy](https://info.orcid.org/public-data-file-use-policy/),
[public API terms](https://info.orcid.org/public-client-terms-of-service/).
