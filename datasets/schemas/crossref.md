# Crossref work metadata

The March 2026 annual public data file contains Crossref work JSON Lines.
Fixture records project fields from the public REST API's unwrapped
`message` object for two DBLP-linked DOIs.

- Preserve `DOI` as the record key. Normalize case and unwrap known DOI
  URLs when joining DBLP electronic editions or OpenAlex work DOIs.
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

Publisher references:
[annual public data file](https://www.crossref.org/services/metadata-retrieval/public-data-file/),
[license scope](https://www.crossref.org/documentation/retrieve-metadata/),
[REST format](https://www.crossref.org/documentation/retrieve-metadata/rest-api/).
