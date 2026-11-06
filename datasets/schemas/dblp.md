# DBLP XML

The manifest pins the November 2026 XML dump and the June 2023 DTD separately.
The development XML is a small CC0 projection from the publisher's public
record and schema examples. It omits the DTD so the offline replay can reject
all entity declarations. It is not a byte slice of the registered corpus.

The full `dblp` root contains a sequence of bibliographic elements, including
`article`, `inproceedings`, `proceedings`, `book`, `incollection`, `phdthesis`,
`mastersthesis`, `www`, `person` and `data`. Verify supported tags against the
pinned DTD. Treat person and homepage records as identity evidence separately
from publications.

- The `key` attribute identifies a DBLP record. `mdate` is source modification
  date, not publication year.
- Preserve author occurrence order. Distinguish editors from authors and treat
  name and ORCID links as source claims. Publisher-supplied publication ORCID
  signatures may have a different validation path from manually verified person records.
- Preserve title content, including inline markup where present. Parse year,
  journal or booktitle, all `ee` electronic editions and the relative `url`.
- Extract a DOI or arXiv ID only from known identifier forms. DBLP XML
  `crossref` is a parent DBLP record key, separate from the Crossref DOI registry.
- A production parser needs the pinned local DTD for named entities and
  must disable arbitrary external resource resolution. The Phase 3 script
  processes only bounded standalone fixtures and never parses the full dump.

Publisher sources:
[XML structure](https://dblp.org/faq/16154937),
[ORCID integration](https://dblp.org/faq/How%2Bare%2BORCIDs%2Bintegrated%2Bin%2Bdblp.html),
[release and DTD](https://drops.dagstuhl.de/entities/artifact/10.4230/dblp.xml.2026-11-01).
