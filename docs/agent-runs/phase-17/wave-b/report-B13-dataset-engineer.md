# WO-B13 report (dataset-engineer), as received 2026-11-19 (condensed by the orchestrator)

STATUS: COMPLETE for the edits; not run.

## Files modified
Each file gets a version line and a field table; the existing prose is unchanged. Every row cites `file:line`.

| File | Table rows | Sources |
|---|---|---|
| `datasets/schemas/dblp.md` | 13 | `samples/dblp.xml`, `DblpParser.scala` |
| `datasets/schemas/openalex.md` | 18 | `samples/openalex.jsonl`, `SourceNormalizers.scala` |
| `datasets/schemas/crossref.md` | 15 | `samples/crossref.jsonl`, `SourceNormalizers.scala` |
| `datasets/schemas/orcid.md` | 6 | `samples/orcid.jsonl`, `SourceNormalizers.scala` |

## Versions
- Each note's own document version is 1, dated 2026-11-19. It is labelled as a documentation version, not an upstream one.
- The notes also cite the manifest snapshot versions, `manifest_version` 1 and `ContractSchema.currentVersionNumber` 1 (`Contracts.scala:9`).

## Readers of these files
- `datasets.py:180` checks only that each file exists.
- `test_datasets.py` asserts no schema string.
- Other tests could not be searched (the guard refused grep), so they are UNVERIFIED.
- Range text avoids the "digit / digit" pattern.

## Risks
Several fields are marked UNVERIFIED in the tables:
- the DBLP DTD was not read;
- `mdate`, `pages` and some OpenAlex and ORCID fields are not read by the parser;
- `XmlFragment` and `ExactIdentifiers` were not read;
- the ORCID bulk XML names are not listed.

## Manual action (human)
```
python3 -B datasets/scripts/datasets.py validate
python3 -B -m unittest discover -s datasets/scripts -p 'test_*.py'
grep -rln "datasets/schemas\|schemas/dblp\|schemas/orcid\|schemas/openalex\|schemas/crossref" tests modules datasets .github
```
Run `sbt check` only if the grep lists a Scala or shell reader.
