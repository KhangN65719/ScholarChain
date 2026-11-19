# DBLP XML

Schema version: note version 1, dated 2026-11-19. This is a documentation
version, not an upstream one. Upstream versions recorded in the repository:
registered snapshot `2026-11-01` (`datasets/dataset-manifest.yaml:16`), DTD
`dblp-2023-06-28.dtd` (`datasets/dataset-manifest.yaml:34`), and manifest
version 1 (`datasets/dataset-manifest.yaml:2`). The Phase 5 subset was
extracted from a different dump, release `2026-11-05`
(`datasets/dataset-manifest.yaml:388`). That release is not the registered
snapshot (`:386-387`), its DTD MD5 does not match the registered value
(`:381`), and the manifest records the release mismatch as a limitation (`:532`).
Records normalize to core
contract schema version 1
(`modules/core/src/main/scala/scholarchain/core/Contracts.scala:9`).

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

## Field table

Sources: the fixture `datasets/samples/dblp.xml` (lines 3 to 21) and the
parser `modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/DblpParser.scala`
(`DblpParser.scala:N`). DBLP XML carries text, so the type column gives the
type the parser produces. A type or meaning that the sources do not show is
marked UNVERIFIED. The DTD was not read for this note.

| Field | Type | Meaning | Used for |
|---|---|---|---|
| `key` (attribute of the record element) | string | DBLP record key, for example `journals/pvldb/Ley09` (sample line 3) | join key and source record id (`DblpParser.scala:63`, `:71`, `:90`) |
| record element name (`article`, `inproceedings`) | string | Record type, kept as `recordType` (`DblpParser.scala:57`, `:193`) | record-type filter; other tags reject as unsupported (`:58-61`) |
| `mdate` (attribute) | date string | Source modification date (sample line 10; note text above) | not read by `DblpParser.scala`; other use UNVERIFIED |
| `author` (element text) | string | Author name in occurrence order | author mention, position is occurrence index plus 1 (`DblpParser.scala:121-131`) |
| `author@orcid` | string | Explicit ORCID iD claimed on the author (sample line 4) | join key, kept only when `ExactIdentifiers.orcid` accepts it (`DblpParser.scala:102-106`, `:132-136`) |
| `editor` (element text, optional `orcid`) | string | Editor name | editor names and ORCID-bearing researchers (`DblpParser.scala:154-172`); not in the fixture |
| `title` | string | Title, inline markup collapsed to text by `XmlFragment.textOf` (UNVERIFIED for markup handling) | work title; required, record rejects without it (`DblpParser.scala:64-69`) |
| `year` | integer | Publication year, parsed with `toIntOption` | `year` (`DblpParser.scala:112`) |
| `journal`, `booktitle`, `series`, `school`, `publisher` | string | Venue name; first present element wins, in that order | venue name and venue id (`DblpParser.scala:37-38`, `:113-118`) |
| `ee` (one or more) | URL string | Electronic edition, for example a `https://doi.org/` URL (sample lines 8 and 20) | DOI and arXiv join keys via `ExactIdentifiers.fromElectronicEdition` (`DblpParser.scala:92`, `:100-101`) |
| `url` | string | Relative or absolute DBLP URL | source URIs, relative values prefixed with `https://dblp.org/` (`DblpParser.scala:93-96`); not in the fixture |
| `crossref` | string | Parent DBLP record key, not a DOI (note text above) | deliberately not read (`DblpParser.scala:98-99`) |
| `pages` | string | Page range (sample line 17) | not read by `DblpParser.scala`; other use UNVERIFIED |
| `www` or `person` record: `key`, `author` (first is the name, others are aliases), `author@orcid` | strings | Identity evidence, not a publication | canonical researcher, researcher id from the person key (`DblpParser.scala:222-261`); not in the fixture |

Publisher sources:
[XML structure](https://dblp.org/faq/16154937),
[ORCID integration](https://dblp.org/faq/How%2Bare%2BORCIDs%2Bintegrated%2Bin%2Bdblp.html),
[release and DTD](https://drops.dagstuhl.de/entities/artifact/10.4230/dblp.xml.2026-11-01).
