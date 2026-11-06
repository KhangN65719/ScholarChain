# OpenAlex works and embedded authorships

The 2026-10-14 public snapshot contains gzip-compressed JSON Lines in entity
and `updated_date` partitions. A partition date is the modification date,
not publication date. Capture the manifest and object integrity at acquisition
because the public S3 prefix changes in place on refresh.

- The work's `id` is an OpenAlex W ID. `doi` is optional and normally a DOI
  URL. Retain both and normalize DOI for exact joins.
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

Publisher references:
[public snapshot](https://help.openalex.org/access/snapshot/),
[JSONL manifest](https://openalex.s3.amazonaws.com/data/jsonl/manifest.json),
[work entity](https://help.openalex.org/entities/works/).
