# Phase 3 independent code review

## First verdict

**REQUEST CHANGES** from the independent code-reviewer.

- `datasets/scripts/datasets.py:154-178` lets a local replay overwrite a checked-in fixture when the output path aliases the samples directory. Protect source paths before writing and add a regression test.
- `modules/core/src/main/scala/scholarchain/core/StableIds.scala:146-164` silently replaces unpaired UTF-16 surrogates while encoding UTF-8. Distinct malformed and valid inputs can therefore produce the same stable ID. Reject malformed input for every ID input encoding path and test the regression.

The reviewer independently checked DBLP release and DTD checksums and DTD licensing, OpenAlex CC0, the ORCID release identifier and license, and the Crossref release DOI and torrent info hash. Machine-readable ORCID and OpenAlex evidence was requested from the owner. The reviewer observed 17 passing Scala tests, successful manifest validation and identical four-source replay evidence. The reviewer used read-only file inspection and source browsing. The Codex per-role tool fence remains unverified.

## Final verdict

**APPROVE** the repaired Phase 3 candidate.

The stable ID code now rejects malformed Unicode before encoding. The dataset CLI resolves output paths and rejects destinations inside the checkout before writing. Post-repair evidence shows 18 Scala tests and 7 Python tests passing, all four manifest entries validated, and all eight sample and provenance files reproduced byte-for-byte.

The source evidence supports the registered licenses, release identifiers, and checksum claims. The manifest distinguishes publisher digests from local sample hashes and the Crossref torrent info hash.

Human ratification and enforcement of the Codex reviewer tool restrictions remain open. This review used read-only inspection.

Reviewed locations: `StableIds.scala:67–87,145–192` and `datasets.py:73–77,267–280`. See [post-repair verification](validation.md).
