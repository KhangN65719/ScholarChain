# Phase 03 revalidation independent review

Recorded for `docs/agent-runs/phase-03/revalidation.md` step 2. Recorded by the orchestrator (main Claude Code session). The verdict and all findings below are the work of `code-reviewer` and are preserved as received.

## Attribution

| Field | Value |
| --- | --- |
| Author | `code-reviewer` subagent, dispatched by the orchestrator |
| Work order | `revalidation.md` step 2 |
| Tools held | Read, Grep, Glob only. No Bash, no write tools. |
| Repository writes | None. The reviewer made no write and performed no repair. |
| Commands run by reviewer | None. |
| Reviewed commit | `9ee8b222466b0d920dc895dfb02372b681d1fe5f` |
| Scope | Contracts, config loader, dataset manifest and scripts |

## Verdict

**REQUEST CHANGES** — narrowly scoped.

The reviewer found **no functional defect that would make a gate command fail, no authority violation, and no license, DOI, ORCID iD or checksum it could show to be fabricated**. Two findings bear on whether a passing run is *meaningful*, and one on gate 4's "no invented facts" standard. The remainder are informational.

## Diff evidence supplied to the reviewer

The orchestrator produced the diff with read-only git, as the work order required. `git diff --stat 9ee8b22 HEAD -- modules/core datasets` produced no output, so the reviewed tree is byte-identical to the accepted commit for every reviewed path. Phase 03 was a pure addition: 31 files, 2635 insertions, 0 deletions, confirmed by `git diff --stat efcd46b 9ee8b22`. The diff content therefore equals current file content, and the reviewer inspected the files directly with its read-only tools.

The reviewer recorded byte-identity as an assumption it could not itself verify, since it has no Bash. The orchestrator did verify it by the command above. That assumption is discharged.

## Findings and routing

| ID | Severity | Finding | Owner |
| --- | --- | --- | --- |
| MEDIUM-1 | Medium | `datasets.py validate()` has zero negative test coverage | `dataset-engineer` |
| MEDIUM-2 | Medium | 10 of 23 contract record types have no test at all | `domain-engineer` |
| MEDIUM-3 | Medium | The Crossref size claim is unattributable | `dataset-engineer` |
| LOW-4 | Low | The "no invented licenses" gate is not machine-checkable | `dataset-engineer` |
| LOW-5 | Low | Provenance vocabularies do not meet across modules | `dataset-engineer`, with a note to `domain-engineer` |
| LOW-6 | Low | `ConfigSuite` rejection test cannot tell which validation fired | `domain-engineer` |
| LOW-7 | Low | Seven informational items | split, see below |

### MEDIUM-1 — `datasets.py validate()` has zero negative test coverage

Owner: `dataset-engineer`.

`test_datasets.py` calls `datasets.validate()` at lines 17, 36, 86, 109 and 119, and every call expects success against the real manifest. No test feeds a malformed manifest. The checks at `datasets/scripts/datasets.py:116-157` — manifest version, required-field presence, dataset-ID shape and uniqueness, HTTPS evidence URLs, the `s3_prefixes` derivation (`:128-132`), publisher-digest length by algorithm (`:136-139`), `not-published` entries forbidden from carrying a `value` (`:140-141`), sample SHA-256 (`:148-149`) and row count (`:150-151`) — could all regress to no-ops and `python3 -B datasets/scripts/datasets.py validate` would still exit 0.

Gate 2 is the assertion that these checks work; nothing tests that they fire. Requested: rejection cases for at least a sha256 mismatch, a non-HTTPS evidence URL, a mismatched `s3_prefixes`, and a `not-published` checksum carrying a `value`.

The reviewer noted the existing negative tests are real and well built, but target other functions: `bounded_read` and `records` (`test_datasets.py:52-66`), `download_provenance` (`:118-126`), output-path aliasing (`:68-83`).

### MEDIUM-2 — 10 of 23 contract record types have no test at all

Owner: `domain-engineer`.

Grep over `modules/core/src/test` for every record type returns one hit (`EvidenceBundle`, `ContractSuite.scala:375`). These types are never constructed, encoded or decoded in any test: `AuthorMention` (`Contracts.scala:117`), `CanonicalResearcher` (`:137`), `AuthorshipRecord` (`:149`), `PublicationTeam` (`:166`), `ResearcherMetrics` (`:237`), `Venue` (`:275`), `Institution` (`:287`), `Topic` (`:299`), `GraphNode` (`:337`), `QueryPlan` (`:423`).

Sharpest consequence: the mention-position coupling is the most intricate invariant in the file and is entirely unexercised. `WorkRecord` requires `author.workId == workId && author.authorPosition == index + 1` (`Contracts.scala:110-115`), yet the only `WorkRecord` fixture has `authors = Vector.empty` (`ContractSuite.scala:30`). The identical invariant in `PublicationTeam` (`Contracts.scala:175-180`) and the `mentionId == StableIds.mention(workId, authorPosition)` checks in `AuthorMention` (`:130-133`) and `AuthorshipRecord` (`:160-163`) have no coverage. `GraphNode`'s node-type-to-ID-kind dispatch (`:346-351`) is untested, including its permissive `case _ =>` fallthrough.

`modules/core/README.md:3` states "Contract tests freeze representative wire values and member names." That holds for `WorkRecord` (`ContractSuite.scala:72`) and `MatchDecision` (`:74`) only; the claim overreaches for the other 21 types and should be narrowed or the coverage extended.

The reviewer recorded that `ContractSuite` is otherwise strong: 13 of its 15 tests are rejection tests covering malformed provenance, absent, fractional, text and nested schema versions, noncanonical base64url, out-of-range and NaN confidence, reversed windows, unknown enum wire values, and the isolated-surrogate regression. It confirmed the prior Phase 03 repair landed: `StableIds.scala:169-180` throws `IllegalArgumentException` on malformed UTF-16 and is applied on every ID input path (`:78`, `:167`, `:190`), with `isCanonicalPayload` (`:145-154`) decoding strictly and re-encoding to enforce canonical unpadded base64url. `datasets.py:73-77` and `test_datasets.py:68-83` likewise match what `repairs.md:12-14` claimed.

### MEDIUM-3 — the Crossref size claim is the one manifest figure the repository cannot substantiate

Owner: `dataset-engineer`.

`datasets/dataset-manifest.yaml:298` reads `"208 GB compressed, nearly 180 million records (Crossref release announcement)."` No announcement URL appears in that entry, in `datasets/schemas/crossref.md:22-25`, or in the publisher observations at `validation.md:33-36`, which record only the torrent info dictionary SHA-1 for Crossref (`:35`).

Every other size figure is traceable: OpenAlex's `755,513,952,946` (`manifest:89`) matches the `content_length` in the fetched inventory (`validation.md:33`); DBLP's matches the publisher sidecar (`validation.md:36`); ORCID's filenames and MD5s match the figshare JSON (`validation.md:34`). Under a gate reading "no invented licenses or identifiers," an unattributable quantitative claim is the exact failure mode.

Requested: cite the announcement URL, or restate the figure as derived from the 3,697,724-byte torrent metadata that was actually fetched.

Related, weaker: ORCID's `"270.98 GB across compressed archives (ORCID Figshare listing)"` (`manifest:156`) is attributed to a JSON that was fetched, but `validation.md:34` records only that the 12 filenames and digests matched, not the size total. Confirm the total from the archived response.

### LOW-4 — the "no invented licenses" gate is not machine-checkable

Owner: `dataset-engineer`.

`license` carries only free-text `scope` and `url` (`manifest:10-13, 80-83, 147-150, 289-292`), and `validate()` checks only `url(entry["license"]["url"])` (`datasets.py:125-127`). There is no SPDX identifier field and no recorded digest of the license page as retrieved, in contrast to the `checksums` block, which does enforce `status`/`algorithm`/`evidence` discipline (`datasets.py:133-141`). A future edit could change `CC0-1.0` to anything and gate 2 would still pass.

This is an enforceability finding, not a claim that a license is wrong. The reviewer checked all four and recorded them as accurate and carefully hedged: DBLP metadata is CC0-1.0 with the DTD scope explicitly held separate (`:11`); OpenAlex is CC0 with linked full text excluded (`:81`); the ORCID annual public file is CC0 (`:148`); and the Crossref entry correctly declines a blanket CC0 claim, distinguishing reusable bibliographic facts, CC0 Crossref-generated data, and abstracts that retain publisher rights (`:290`).

### LOW-5 — provenance vocabularies do not meet across modules

Owner: `dataset-engineer` primary, with a note to `domain-engineer` for a later phase.

The core `Provenance` contract requires a `runId` and a `transformVersion` (`Contracts.scala:65-80`). The replay receipt records `transform: "first-rows-v1"` but no run identifier (`datasets.py:176-181`). The download receipt records `source`, `sha256`, `bytes` and `publisher_checksum` with no transform field and no observation timestamp (`datasets.py:249-252`), so a download receipt cannot establish when the artifact was seen, even though the manifest's sample provenance strings do carry dates (`manifest:130, 272, 344`).

Both receipts satisfy the `CLAUDE.md` "source and transformation provenance" minimum. This is an alignment gap, not a gate failure.

### LOW-6 — `ConfigSuite` rejection test cannot tell which validation fired

Owner: `domain-engineer`.

`ConfigSuite.scala:40-42` loops seven settings through a bare `intercept[Exception]` with no message assertion, so any single over-broad failure satisfies all seven. Specifically `"SCHOLARCHAIN_MATCH_ACCEPT_THRESHOLD" -> "NaN"` (`:35`) is meant to exercise the `.filter(_.isFinite)` guard at `ScholarChainConfig.scala:77`, but would pass equally if that guard vanished and `ContractValidation.confidence` (`Contracts.scala:34-38`) caught it instead.

Also missing: a case where a required non-version field is simply absent from the file. The loader relies on upickle throwing; `parse` checks only for unknown keys (`ScholarChainConfig.scala:116-119`).

### LOW-7 — informational items

To `domain-engineer`:

- `EnumWire.codec` (`WireEnums.scala:6-15`) builds the `wire -> value` map with no uniqueness assertion; a duplicate wire string would silently make one case undecodable instead of failing.
- `EvidenceBundle` does not require `items.nonEmpty` (`Contracts.scala:409-421`) and `AnswerClaim` permits zero evidence for non-`Verified` statuses (`:454-457`), so an `AnswerResponse` whose claims cite nothing, with an empty bundle, is structurally valid. `ContractSuite.scala:397-399` tests only the `Verified` path. This is permissive relative to the `CLAUDE.md` rule that every material runtime claim must cite an evidence bundle.
- ID discipline is inconsistent: `WorkRecord.venueId` is unvalidated (`Contracts.scala:90`), and `Venue.venueId`, `Institution.institutionId` and `Topic.topicId` get only `present` (`:284, 296, 307`), while work, mention, researcher, chunk and evidence IDs get `requireKind`.
- Dead null checks at `ScholarChainConfig.scala:141`, `ContractWire.scala:7,12`, `Contracts.scala:257`. Unreachable for Scala case classes. Noise only.

To `dataset-engineer`:

- `datasets.py:121` does not reject unknown extra keys in a dataset entry, unlike the config loader.
- `orcid.md:9` instructs consumers to "Validate the ORCID check digit," but no repository code does. The reviewer validated all four by hand (ISO 7064 MOD 11-2) and all four are well-formed: `0000-0001-7580-4351`, `0000-0001-5379-5191`, `0000-0003-0523-8547`, and `0000-0001-7441-129X` with check digit `X`.
- DOI letter-case drift between evidence URLs (`manifest:128, 342` use `JCDL`) and stored sample keys (`openalex.jsonl:2`, `crossref.jsonl:2` use `jcdl`). Harmless, since DOIs are case-insensitive and `test_datasets.py:23-24` normalizes, but the evidence URL is not a literal round-trip of the stored key.

Naming nit, no owner action required: both "YAML" files contain strict JSON (`dataset-manifest.yaml:1`, `scholarchain-local.v1.yaml:1`). Intentional and documented at `datasets/README.md:3` and `modules/core/README.md:9`.

## What the reviewer checked and found clean

- **Authority.** Every reviewed file is inside its owner's territory: `modules/core/**` to `domain-engineer`, including `modules/core/README.md`, which does not collide with `documentation-engineer`'s root `README.md`; `datasets/**` to `dataset-engineer`. `datasets/scripts/datasets.py` is correctly under `datasets/scripts/`, which `docs/agents.md` excludes from `infra-engineer`'s `scripts/**`. No violation.
- **Identifier authenticity.** All DOI prefixes are real and correctly attributed: 10.14778 (VLDB Endowment), 10.1109 (IEEE, with IEEE's `ACRONYM.year.articlenumber` suffix convention), 10.13003 (Crossref), 10.4230 (Schloss Dagstuhl), 10.23640 (figshare/ORCID). ISSN 2150-8097 is correct for *Proceedings of the VLDB Endowment*. OpenAlex `A5…` author IDs use the correct post-2024 format. DBLP keys `journals/pvldb/Ley09` and `conf/jcdl/MichelsFLSS17` match the five-author initial convention and the papers in the samples.
- **Cross-source consistency.** The three samples pivot on two shared DOIs and overlapping ORCID iDs, enforced by a genuine linkage test (`test_datasets.py:16-33`) rather than a tautology. Title divergence between DBLP (`dblp.xml:5`) and Crossref/OpenAlex is realistic and is called out at `openalex.md:18-21`. `orcid.md:4`'s "11 activity partitions" matches the 11 `activities_*` entries at `manifest:163-238`. Declared `rows: 2` matches every sample file and `validate()` enforces it (`datasets.py:150-151`).
- **Replay determinism.** `json.dumps(..., sort_keys=True)` (`datasets.py:167`) and the content-equality early return in `atomic_write` (`:42-43`) make replay byte-stable and mtime-stable, which `test_datasets.py:44-50` asserts. The XML path reindents via `ET.indent` (`:172`), so output is a declared projection rather than a byte slice, honestly labelled by `transform: "first-rows-v1"` and by `dblp.md:6`.
- **Fail-closed download.** `--proto =https`, no `--location`, 1 MiB and 15 s caps, staging-then-rename, and `download_provenance` raising when neither a publisher digest nor an `expected_json` rule exists (`datasets.py:248`).
- **Transcript corroboration.** The counts in `revalidation-results.md` match the source: ConfigSuite has exactly 3 tests, ContractSuite exactly 15, totalling 18, and `test_datasets.py` exactly 7 test methods.

## Gate commands: all PENDING for the reviewer

The reviewer has no Bash tool and ran nothing. It reported every gate command as PENDING and did not infer a pass from reading code.

| Gate | Command | Responsible runner | Reviewer state |
| --- | --- | --- | --- |
| 1 | `sbt check`, ConfigSuite and ContractSuite | Human architect, `!` prefix, per `CLAUDE.md` | PENDING for reviewer. Human-run result transcribed in `revalidation-results.md`. Not observed by reviewer. |
| 2 | `python3 -B datasets/scripts/datasets.py validate` | `dataset-engineer` territory; run by the human architect | PENDING for reviewer. Transcribed in `revalidation-results.md`. |
| 3 | `python3 -B -m unittest discover -s datasets/scripts -p 'test_*.py'` | `dataset-engineer` territory; run by the human architect | PENDING for reviewer. Transcribed in `revalidation-results.md`. |
| 4 | Provenance present, no invented licenses or identifiers | `code-reviewer`, this review | Addressed. Partially open, see below. |

Gates 1 through 3 were subsequently run by the human architect and are recorded in `revalidation-results.md`. The reviewer's PENDING marks describe its own position, not the current state of the gate.

Gates 1 through 3 must be re-run after any repair to MEDIUM-1 or MEDIUM-2, per `revalidation.md` step 3.

## Explicitly left open, not assumed

1. **Every external URL, publisher digest, release DOI and size figure.** No network and no shell. The reviewer cross-checked each against the recorded publisher observations at `validation.md:33-36` where those exist; MEDIUM-3 names the ones with no such backing.
2. **Exit codes.** The reviewer accepted `revalidation-results.md`'s own statement that no command echoed `$?` and that results rest on `[success]`, a success line with empty stderr, and `OK`. It reports no literal observed exit codes.
3. **Whether the 2026-11-01 DBLP release still links the 2023-06-28 DTD** (`manifest:16` vs `:70`, checksum `:33-38`). The date gap is disclosed at `dblp.md:3` and `manifest:48` makes recording the linked DTD part of the update method, so this is a staleness question for the owner, not a defect.
4. **Two OpenAlex sample rows share an identical `updated_date` of `2026-11-04T10:54:05.112534`** (`openalex.jsonl:1,2`) across two unrelated works. A bulk re-index can produce this, so the reviewer makes no fabrication claim, but the archived raw API responses were not preserved — `validation.md:33-36` records hashes for the OpenAlex inventory and the ORCID figshare JSON, not for the four sample-source API responses — so it cannot close the question. Row 1's float32-artifact topic scores (`0.6416000127792358`) read as authentically machine-generated; row 2's exact `1.0`/`0.0`/`0.0` is unusual but representable. The reviewer asks `dataset-engineer` to archive the four raw responses with digests.
5. **ROR check digits** for `02778hg05` and `052gg0110` (`openalex.jsonl:1,2`): not verified. The reviewer's reconstruction of MOD 97-10 failed on an ID it believes genuine, so it reported no result rather than risk a false negative.
6. **Native hook execution and subagent tool-list enforcement**, per `CLAUDE.md`. The reviewer operated within Read, Grep and Glob, but cannot demonstrate the fence was enforced rather than merely observed.

## Orchestrator note on scope (first review)

No finding is a functional defect in shipped behavior, and none would make a gate command fail. MEDIUM-1 and MEDIUM-2 are coverage findings: they assert that gates 2 and 3 can pass while the checks they are meant to assert are untested, and that the most intricate contract invariant in `modules/core` is unexercised. MEDIUM-3 is an attribution finding against the Phase 03 gate's explicit "no invented licenses or identifiers" clause.

The reviewer made no repair and approved nothing of its own. Repair routing is recorded above and belongs to the named owners. The orchestrator performed no owner's work inline.

The verdict above is the **first** review and is preserved unchanged as historical evidence. The re-review that supersedes it follows.

---

# Re-review of the repaired tree

Recorded by the orchestrator. Author: `code-reviewer`, dispatched after both repair owners reported and after the human architect's post-repair gate run.

## Attribution

| Field | Value |
| --- | --- |
| Author | `code-reviewer` subagent, dispatched by the orchestrator |
| Scope | Narrow. The 13 repaired files plus the new `Rejection.scala`, against MEDIUM-1, MEDIUM-2 and MEDIUM-3 only |
| Tools held | Read, Grep, Glob only. No Bash, no write tools. |
| Repository writes | None. No repair. |
| Commands run by reviewer | None. |
| Diff evidence | `git diff --stat` by the orchestrator: 13 files, 919 insertions, 26 deletions |
| Exec bit | Deliberately excluded by human decision. Not raised by the reviewer. |

The reviewer produced the original findings but performed none of the repairs, so it reviewed `dataset-engineer`'s and `domain-engineer`'s work, not its own. `docs/agents.md` forbids a review approving its own work; that constraint is satisfied.

## Verdict

**APPROVE**, scoped to the three MEDIUM findings.

| Finding | State | Owner |
| --- | --- | --- |
| MEDIUM-1 | **CLOSED** | `dataset-engineer` |
| MEDIUM-2 | **CLOSED** | `domain-engineer` |
| MEDIUM-3 | **PARTIALLY CLOSED**, interim disposition honest and sufficient to proceed | BLOCKED-ON human architect |

## MEDIUM-1 — CLOSED

The three-method count gap that the orchestrator declined to infer from is fully explained, and in the opposite direction from a shortfall: the negative cases are **18 `subTest` cases inside one method**, not three cases.

`test_datasets.py:145-154` iterates `self.cases()` wrapping each in `assertRaisesRegex(ValueError, pattern)`. Line 154 then asserts `len(set(messages.values())) == len(messages)` — every case produced a **distinct** message. The reviewer judged this stronger than the work order required: it defeats the bare-intercept weakness both by pinning each message and by proving no two cases collapse onto a single failure.

All four required cases exist, each pinned to its own error:

| Required case | Mutation | Asserted message | Production check proven |
| --- | --- | --- | --- |
| Sample SHA-256 mismatch | `test_datasets.py:46-47` | `:107` `dblp: sample SHA-256 mismatch` | `datasets.py:187-188` |
| Non-HTTPS evidence URL | `:52-54`, `:56-58`, `:60-63`, `:102-104` | `:111`, `:113`, `:115`, `:142`, four distinct | `datasets.py:162-163, 172-173, 182-184, 192-193` |
| Mismatched `s3_prefixes` | `:65-66` | `:117` `orcid: incorrect snapshot S3 prefixes` | `datasets.py:166-170` |
| `not-published` carrying a `value` | `:68-69` | `:119` `crossref: malformed checksum status` | `datasets.py:178-179` |

The fourth case is genuine rather than accidental: `crossref` `checksums[1]` is `"status": "not-published"` at `dataset-manifest.yaml:315-320`, so adding `value` lands on the `"value" in checksum` branch at `datasets.py:178` and not the publisher-digest branch, and the asserted message confirms which branch fired.

Nine further cases cover checks the first review named as untested: short publisher digest, unknown dataset field, missing required field, dataset-ID shape and uniqueness, manifest version, the four-dataset floor, SPDX shape, and the ORCID check digit.

**Temporary-copy discipline confirmed.** `test_datasets.py:31-39` mutates an in-memory `json.loads` copy, writes it to a `TemporaryDirectory` and patches `datasets.MANIFEST` for the duration; the checked-in manifest is never opened for writing. `:156-163` is a control test asserting `MANIFEST` is restored, comparing the file's SHA-256 before and after, and re-running the real `validate()` to a pass.

## MEDIUM-2 — CLOSED

All 10 previously untested types are genuinely constructed, round-tripped through `ContractWire` and given rejection cases, not merely named in a test title. `ContractSuite.scala` grew from 15 to 26 tests, matching the gate's observed 26.

Per-type evidence: `AuthorMention` (frozen literal `:117-118`, asserted `:529-533`, rejections `:536-547`), `CanonicalResearcher` (`:561-564`, `:565-571`), `AuthorshipRecord` (`:581-584`, `:589-600`), `PublicationTeam` (`:610-613`, `:614-632`), `ResearcherMetrics` (`:641-644`, `:645-683`), `Venue`/`Institution`/`Topic` (`:694`, `:705-708`, `:720` with rejections `:695-696`, `:709-712`, `:721-722`), `GraphNode` (`:742-745`, `:754-784`), `QueryPlan` (`:801-804`, `:805-824`).

**The mention-position coupling now actually executes.** The old `authors = Vector.empty` base fixture remains at `:30`, but `:487-525` builds a non-empty team via the `authorMention` helper (`:91-102`) and `work.copy(authors = Vector(first, second))` at `:492`. The invariant at `Contracts.scala:113-118` is exercised in both directions: round trip at `:493-496`, then four rejections — reordered (`:498-500`), position-2-only (`:501`), duplicated (`:502-504`), and a mention belonging to `otherWorkId` (`:505-518`, pinning `"Authors must belong to this work"`). A wire-level mutation at `:519-521` confirms the invariant fires on decode and not only on construction, and `:522-525` honestly records that the empty case remains valid and was previously vacuous.

`PublicationTeam`'s copy of the invariant (`Contracts.scala:178-183`) gets the same treatment at `:602-638` including a wire mutation at `:628-632`. The `mentionId == StableIds.mention(...)` check is pinned for `AuthorMention` at `:536-541` and `AuthorshipRecord` at `:589-597`.

**`GraphNode` dispatch is fully covered including the permissive fallthrough.** `:746-753` accepts all four kind-checked types; `:754-765` rejects a wrong-kind ID for each with four distinct messages; `:768-781` exercises the `case _ =>` at `Contracts.scala:354` for `Venue`, `Institution`, `Topic` and `DatasetSnapshot`, asserting both acceptance of an external ID and rejection of a blank one.

### Production changes: three tightenings, no loosening

The work order forbade changing shipped contract behavior to make a test pass. The reviewer read the complete current files against the invariants quoted verbatim in its first verdict and found **nothing weakened**:

1. `Contracts.scala:106-108` — `venueId.foreach(present(_, "Work venue ID"))`. Tightening; previously unvalidated per LOW-7. Tested at `ContractSuite.scala:731`, recorded at `modules/core/README.md:13`.
2. `Contracts.scala:421-427` — `EvidenceBundle` gains a duplicate-`evidenceId` check. Tightening. The permissive empty-`items` shape was **kept deliberately**, with reasoning written in-code at `:421-423` and at `modules/core/README.md:17`, and the decision asserted explicitly at `ContractSuite.scala:858-863`. This is the recorded decision the work order required, not an accident.
3. `Contracts.scala:464-469` — `AnswerClaim` now requires `evidenceIds.nonEmpty || uncertainty` nonblank. Tightening, and the correct resolution of the LOW-7 permissiveness against the `CLAUDE.md` evidence rule. The prior `Verified` implies evidence requirement at `:460-463` is intact. Documented at `modules/core/README.md:19`, tested at `ContractSuite.scala:882-891`.

`WireEnums.scala:8` adds `require(lookup.size == values.length, "Enum wire values must be unique")`, tested two ways at `ContractSuite.scala:922-938`. `ScholarChainConfig.scala:140-142` replaced the dead null check with a comment explaining unreachability; the `.filter(_.isFinite)` guards at `:77` and `:82` are intact.

**`modules/core/README.md:3` is now true.** It narrows the frozen-literal claim to `WorkRecord`, `AuthorMention` and `MatchDecision`, all three of which exist at `ContractSuite.scala:113-118`, and claims round-trip-plus-rejection coverage for the rest. The reviewer checked that second claim against all 23 types and it holds.

## MEDIUM-3 — PARTIALLY CLOSED

Not closable by review. `validate()` cannot check whether a cited figure is real.

- **No fabricated reference was introduced.** The reviewer grepped every URL in `datasets/schemas/**` and the manifest. All are URLs its first review had already validated; no release-announcement URL, DOI or citation was added anywhere.
- **The restatement is honest and traceable.** `dataset-manifest.yaml:302` now marks the figure `UNCONFIRMED`, states that no release-announcement URL is recorded and that no recorded publisher observation establishes either figure, and points to the only recorded Crossref observation: 3,697,724 bytes of torrent metadata with bencoded info-dictionary SHA-1 `b5ee0e102689b3e67023dd024694c0f5f124646f`. Both values match `validation.md:35` exactly. The false `(Crossref release announcement)` attribution is gone.
- **ORCID is correctly marked unconfirmed.** `dataset-manifest.yaml:159` states the recorded observation confirms the 12 filenames and MD5 digests but not the aggregate size, and that the raw figshare response was not archived — precisely what `validation.md:34` supports.

Remaining human action: supply a citable announcement URL, or authorize a bounded re-fetch.

## Regression check — clean

The reviewer confirmed from code that nothing in the `datasets.py` changes can perturb sample output: the payload is still built at `:218-225` with `json.dumps(row, ensure_ascii=False, sort_keys=True)` and digested at `:232`/`:239`; `atomic_write`'s content-equality early return is intact at `:50-52`; the fail-closed download posture is intact at `:268-274` with `MAX_BYTES` 1 MiB (`:19`), `MAX_SECONDS` 15 (`:21`), the curl-8.4 floor at `:260-262` and staging-then-rename at `:264-279`.

The two new receipt fields were added without breaking determinism, and the reviewer judged the design sound rather than merely passing: the replay `run_id` (`:200-210`) is content-derived precisely so replay stays byte-stable, with the reasoning and the distinction from the core UUIDv4 `runId` written in the docstring, and the download `observed_at` is nondeterministic by nature and excluded from cache comparison by `receipt_identity` (`:291-297`).

This agrees with the human's observed result: all four digests unchanged.

## Explicitly left open by the re-review

1. **Exact diff hunks.** With no Bash or git, the reviewer's attribution of which specific lines are new in `Contracts.scala`, `WireEnums.scala` and `ScholarChainConfig.scala` is inferred from the `--stat` counts plus its own prior line citations. It noted that the substantive conclusion — no shipped invariant was loosened — does not depend on that inference, because it read the complete current files against invariants quoted verbatim from its first review.
2. **Gate commands.** The reviewer ran nothing. All results are as transcribed from the human architect's `!`-prefixed runs.
3. **MEDIUM-3 closure.** Requires human evidence.
4. **Items 1, 3, 4, 5 and 6 of the first review's open list** are unchanged. In particular the four raw sample-source API responses were not archived, so the `openalex.jsonl:1,2` `updated_date` question stays open.

## Non-blocking observations, not findings against these repairs

- `ConfigSuite.scala:86`, `:89`, `:92` still use bare `intercept[Exception]` in the pre-existing unknown-key and version test. The work order named only `:40-42`, which is properly repaired at `:39-81` with each case message-pinned and the NaN-versus-out-of-range distinction made explicit at `:74-81`. LOW-6 residue, owner `domain-engineer`, outside the re-review's narrow scope.
- The LOW-7 dead null checks in `ContractWire.scala:7,12` were not removed. The owner instead demonstrated reachability at `ContractSuite.scala:940-953`. The reviewer called this a defensible alternative disposition that nonetheless differs from what the work order asked. Owner `domain-engineer`.

## Orchestrator note on the re-review

The count gap the orchestrator flagged as unverified in `revalidation-results.md` is resolved, and resolved in the direction opposite to a shortfall: 18 message-pinned subtest cases against four required, with an added assertion that no two collapse onto one message. The orchestrator was right not to infer closure from the test-count delta, and right not to claim a shortfall from it either.

Both LOW-6 and LOW-7 residue items are recorded above for the human's disposition. Neither was in the re-review's scope and neither is a defect introduced by these repairs. The exec-bit question remains a separate human control-surface decision and was excluded from this review by instruction.

## Consolidated state

| Item | State |
| --- | --- |
| Gate 1, `sbt check` | Passed post-repair. 31 tests, ConfigSuite 5, ContractSuite 26. |
| Gate 2, `datasets.py validate` | Passed post-repair. Four sources. |
| Gate 3, `unittest discover` | Passed post-repair. 10 tests. |
| Gate 4, provenance and no invented facts | MEDIUM-1 and MEDIUM-2 closed. MEDIUM-3 partially closed, honest interim disposition. |
| Sample digests | Observed unchanged across both orchestration runtimes and across the repairs. |
| Ownership boundaries | Clean throughout. Disjoint changed sets, no violation. |
| Independent review verdict | **APPROVE**, scoped to the three MEDIUM findings. |
| `revalidation.md` steps 1 through 4 | Complete. |
| `revalidation.md` step 5 | Open. Human architect records acceptance and the commit SHA. |

Carried forward for the human's decision, none of them blocking a gate:

1. **MEDIUM-3** stays open as an attribution item against `dataset-engineer`, pending a citable Crossref announcement URL or an authorized bounded re-fetch. The manifest currently marks the figure `UNCONFIRMED` and traceable to the recorded torrent metadata.
2. **LOW-6 residue** at `ConfigSuite.scala:86, 89, 92`, bare `intercept[Exception]` in the pre-existing test.
3. **LOW-7 disposition difference** at `ContractWire.scala:7,12`, where reachability was demonstrated instead of the checks being removed.
4. **The four unarchived raw sample-source API responses**, which keep the `openalex.jsonl` `updated_date` question open.
5. **The exec bit on `datasets/scripts/*.py`**, left unchanged by human decision. A control-surface question, not a gate item.

The orchestrator ran no gate command, performed no owner's work inline, and approved nothing. The human architect owns step 5.
