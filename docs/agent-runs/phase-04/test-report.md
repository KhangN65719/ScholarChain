# Phase 4 independent adversarial tests

Author: `test-engineer`. Received by the orchestrator 2026-11-06.

Dispatched to close the specification gate item: "Test engineer adds adversarial XML, Unicode, missing-field, duplicate-ID, common-name, and giant-team cases."

Usage across the dispatch: 248168 then 270386 subagent tokens; 65 then 67 tool uses. The final delivery was itself cut short by a rate limit but the report arrived complete.

## Delivery note

The findings took four attempts to reach the orchestrator. Only an agent's **closing summary** is delivered in this runtime; intermediate messages are never seen, however complete. The agent twice reported having sent the findings "in my previous message" when nothing had arrived. The same mechanism accounts for the preservation caveats in [owner-report.md](owner-report.md) and repairs 3 and 5 of [repairs.md](repairs.md). The report below is preserved as received, from the final message that did arrive.

## Files delivered

Eight files under `tests/adversarial/`, **65 cases across seven suites**, verified by orchestrator inspection.

| Suite | Cases | Specification category |
|---|---|---|
| `AdversarialXmlSuite.scala` | 11 | Adversarial XML |
| `AdversarialUnicodeSuite.scala` | 14 | Unicode |
| `AdversarialMissingFieldSuite.scala` | 11 | Missing fields |
| `AdversarialDuplicateIdSuite.scala` | 10 | Duplicate IDs |
| `AdversarialCommonNameSuite.scala` | 7 | Common names |
| `AdversarialGiantTeamSuite.scala` | 8 | Giant teams |
| `AdversarialJobConfigSuite.scala` | 4 | Beyond the six; provenance integrity |
| `AdversarialFixtures.scala` | 0 | Shared fixtures |

All six named categories are covered. 146 module cases + 65 = **211**, as predicted.

Authority held: every file is under `tests/adversarial/`. Nothing under `modules/**` or `build.sbt` was touched.

## Phase 2 findings F1 and F2: closed on executed evidence

Run 9 compiled and ran these suites as `scholarchain.tests.adversarial.*`. That retires both latent Phase 2 findings and the gate-strength reduction accepted with them. See [validation.md](validation.md), run 9.

The agent's one correction to the orchestrator's note: it used one-symbol-per-line imports, so `AdversarialXmlSuite` has three separate `import scholarchain.hadoop.pipeline.…` lines rather than a braced group. Substance unchanged — all eight files reference `scholarchain.hadoop`, so compilation alone proves the F2 fix.

---

# Findings, as received

> Reporting only. No files edited this turn. Everything below is derived from reading `modules/hadoop/**`; **no test has ever been executed**, so 211/211 remains a prediction, not a result.

## Part 1 — The mapping table

> **All thirteen findings are genuine defects (category 1). None is "surprising but correct."** Five bear directly on a Phase 4 gate item or operational requirement, which means a green 211/211 would *appear* to discharge requirements that remain false.

| Gate item / operational requirement (source) | Would look satisfied by | Actually false because | Finding |
|---|---|---|---|
| **"Emit exact-identifier keys and duplicate or conflict counters"** (spec, job 3) + operational **"Hadoop counters for … duplicate, conflict … records"** | A green duplicate-ID suite, 10 cases | The duplicate counter is **structurally incapable of firing** for a source's own native key. A fivefold replay of one DBLP record reports 0 duplicates; two contradictory titles on one key report 0 conflicts. | **F4** |
| **"Emit valid normalized WorkRecord and AuthorMention records"** (job 1); **"Write malformed and unsupported records to versioned reject outputs"** (job 2); operational **"counters for … rejected … and schema failure records"** | A green Unicode suite, 14 cases | A record with non-Latin-script content **throws out of the parser** instead of becoming a reject. In a map task that fails the task and the whole split. The parser's own guard for this is unreachable dead code. | **F1** |
| **"Emit bounded candidate blocks"** and **"Prevent pathological common-name or title blocks from overwhelming reducers"** (job 4); **"Count and report unusually large author teams"** (job 6) | A green giant-team suite (8 cases) and common-name suite (7 cases) | One of the **two** quadratic paths is entirely unbounded: `Blocking.emit` emits `n*(n-1)` records from one input record (~9,000,000 for the 3,000-author paper the module's own comment names). `maxPairsPerWork` guards only the pair path; `maxAuthorTeamSize` only counts. | **F3** |
| Gate: **"Repeated runs with the same input produce equivalent logical output"**; operational **"Deterministic outputs for deterministic inputs"** | A green duplicate-ID suite | Pair consolidation's tiebreak **ties**, so arrival order decides which record — and therefore which provenance — survives. The method's own comment claims order independence. | **F5** |
| Operational **"Idempotent output paths containing run ID and dataset snapshot"**; project rule **"Every data transformation must record source and transformation provenance"**; and the determinism gate above | A green job-config suite, 4 cases | A delimiter in a snapshot label silently changes the snapshot digest, hence **every output path and every `Provenance.snapshotId` in the run**, with no error. | **F12** |

> **The precise risk.** A characterization case cannot make a gate item pass by itself — gate items are judged by a reviewer against evidence. The hazard is the *inference*: "adversarial cases were added, they all pass, therefore the parser, counter, determinism and bounding gates hold." That inference is false for F1, F3, F4, F5 and F12. **Do not let a green 211/211 be read as a clean bill for Phase 4.** This is the same failure shape as the gate item that already passed an inspection review while false at runtime — F4 in particular is a counter that is wired, visible, and incapable of firing on its primary case.

## Part 2 — The five gate-bearing findings

### F4 — The duplicate counter can never fire for a source's own native key

- **Origin:** `pipeline/IdentifierJoin.scala:137-139` — the `distinctBy` on `(source.wire, sourceRecordId, entityId)` at the head of `resolve`; duplicate derivation `:148-155`, conflict derivation `:156-159`, returned counts `:176-181`. Counters incremented at `jobs/IdentifierJoinJob.scala:107,109`.
- **Current:** Deduplication runs *before* duplicates or conflicts are sought. For a native key the identifier value **is** the `sourceRecordId` (`:70-77`) and `entityId` derives from it, so two claims on one DBLP key always collapse to one — the second record's title is discarded uncompared. The DOI path, where record IDs differ, works correctly.
- **Should be:** Deduplicate on the full claim payload, or compare content before collapsing.
- **Severity: High.** Corrected assertions once fixed: `duplicates == 1L`, `conflicts == 1L`, `conflictingTitles.size == 2`; and `duplicates == 4L` for the fivefold replay.
- **Pinned by:** `one DBLP key claimed twice with two titles is neither finding`; `a fivefold replay of one record reports no duplicate at all`; working contrast in `a DOI claimed by two works in one source is a duplicate`.

### F1 — Non-Latin-script content throws out of the parser instead of being rejected

- **Origin:** `pipeline/DblpParser.scala:118-137` (mention construction in `build`), unreachable guard at `:145-148`, `WorkRecord` construction at `:170-191`. Throws originate in `modules/core/.../Contracts.scala:138` and `:104`. Empty key from `pipeline/TextNormalizer.scala:16,25-32` (`[^0-9a-z]+` ending `fold`).
- **Current:** `<author>李明</author>` → `fold` yields `""` → `AuthorMention`'s constructor assertion throws *before* the parser's own `mentions.exists(_.normalizedName.isEmpty)` check can run; **that guard is dead code**. A title like `日本語の論文` throws from `WorkRecord`, with no guard at all. An `IllegalArgumentException` escapes `parse`, whose entire contract is to return `Left`.
- **Should be:** `Left((RejectReason.MissingRequiredField, …))`, counted and written to the reject output. **The core contracts are correct and are not the fix site** — `domain-engineer`'s invariants are right; `hadoop-engineer` must validate before constructing.
- **Severity: High.** Only unexposed because the pinned fixture is Latin-script. Corrected assertion: replace `intercept[IllegalArgumentException]` with `assertEquals(reasonOf(xml), Some(RejectReason.MissingRequiredField))`.
- **Pinned by:** `an author name in a non-Latin script throws out of the parser`; `a title in a non-Latin script throws out of the parser`; `every non-Latin script folds to the empty key`; `a blank normalized title is unreachable, so its guard is dead code`.

### F3 — Blocking fans out quadratically on a giant team with no bound of its own

- **Origin:** `pipeline/Blocking.scala:327-346` (`emit`), driven by the coauthor-key loop at `:232-247` in `researcherKeys`.
- **Current:** One `NameCoauthor` key per mention per coauthor — exactly `n*(n-1)` records from **one** input record (4,032 at n=64). Each carries the full `n-1` coauthor list (`researcherMember`, `:306-324`), so emitted *bytes* grow ~cubically. The case sets pair bounds to their tightest (4 and 1), confirms pair expansion is fully suppressed, and shows blocking still emitting 4,032 records with zero rejects. `maxBlockSize` does not help — it bounds what one block *keeps*, while this is map-output volume over `n(n-1)/2` distinct keys.
- **Should be:** The same explicit fan-out bound the pair path has — suppress or sample above a configured per-work limit, increment a counter, write a reject.
- **Severity: High.** "The one finding I would expect to take down a full-snapshot EMR run." Corrected assertion: coauthor-key count bounded by the limit, counter > 0, reject present.
- **Pinned by:** `blocking fans out quadratically on a giant team with no bound`; `doubling the team size quadruples the blocking record count`.

### F5 — Pair consolidation depends on arrival order when the tiebreak ties

- **Origin:** `pipeline/TeamExpansion.scala:160-165` (`consolidate`), specifically `group.sortBy(_.provenance.sourceRecordId).head` at **:164** (verified).
- **Current:** For two records of *one* work that field is identical, so the sort ties and Scala's stable sort keeps whichever the shuffle delivered first. Reversing the input changes the retained record's provenance. The doc comment at `:150-155` claims order independence; it holds only for the field sorted on.
- **Should be:** A total order — add `runId`, then `transformVersion`, then the encoded record as final discriminator.
- **Severity: Medium.** Reachable on a re-ingest into shared input; falsifies a determinism claim the gate rests on, and the surviving record *is* the provenance record. Corrected assertion: `forward == reversed` (the current case asserts the inverse, so the repair turns it red immediately).
- **Pinned by:** `pair consolidation is decided by arrival order when the tiebreak ties`.

### F12 — The job-configuration snapshot map is lossy, silently moving output paths and provenance

- **Origin:** `jobs/JobConfig.scala:30-35` (`write`, joining `name=value` with commas) and `:46-57` (`readSnapshots`, splitting on both, no escaping, no validation).
- **Current:** `Map("dblp" -> "2026-11-01,beta")` round-trips to `Map("dblp" -> "2026-11-01")`; `Map("db=lp" -> "2026-11-01")` becomes `Map("db" -> "lp=2026-11-01")`. Both change `RunContext.snapshotId` (`pipeline/RunContext.scala:45-50`), therefore `runPath:84`, every artifact path, and every `Provenance.snapshotId:69`. Nothing reports it. A version containing `=` *does* survive (value split limited to two parts) — that asymmetry is pinned too.
- **Should be:** Refuse a delimiter-bearing dataset name or version at write time, or serialize in a form that survives its own values.
- **Severity: Medium.** Latent today (pinned labels are delimiter-free); blast radius if it fires is every path and provenance field in the run.
- **Pinned by:** `a snapshot version containing a comma is silently discarded`; `a dataset name containing an equals sign is silently renamed`; bound in `a version containing an equals sign does round-trip`.

**Orchestrator caveat, added after run 9: the second pinning case for F12 FAILED.** See "The two failures" below. F12's dataset-name claim is not substantiated as stated.

## Part 3 — The remaining eight, in brief

**F2 — Latin letters with no canonical decomposition are deleted mid-token, renaming the author.** `pipeline/TextNormalizer.scala:25-32` (`fold`), consumed by `nameKey:45`, `familyToken:56-57`, `initials:59-60`, `nameBlockKey:63-69`. `ø ł ı ß` survive NFD then become a **space**: `"Bjørn Ødegård" → "bj rn degard"` → block `"degard|br"`; `"Lech Wałęsa" → "lech wa esa"`; `"Işık" → "is k"`; `"Straße" → "stra e"` never meets `"strasse"`; a Cyrillic-А homoglyph in `"Ann Lee"` → `"nn lee"` → `"lee|n"`. Key is nonblank so nothing fires. *Should:* transliterate to ASCII, or retain; count mixed-script names. Bears on job 4 blocking keys. **Severity High**, invisible in every counter. Category 1 for the silence and mid-token deletion; the ASCII-only key space itself is category 3 — the agent leans keep the key space, add transliteration. Pinned by `a Latin letter with no decomposition becomes a word break`, `the resulting block key names the wrong family and initials`, `a case-folding pair that should agree does not`, `the Turkish dotted capital I folds but the dotless i does not`, `a Cyrillic homoglyph silently redirects a name to another block`.

**F6 — Truncating a common-name block can starve an entire source.** `pipeline/Blocking.scala:98-99` (`Ordering.by((source.wire, memberId))`), applied in `add:106-114`, `retained:135-136`. Source is the **first** sort component, so 60 DBLP + 60 OpenAlex at bound 50 retain 50 DBLP and **zero** OpenAlex — no cross-source candidate can be generated from the blocks that most need the bound. Deterministic as designed, and bounded in the wrong dimension. *Should:* select within source or round-robin across sources. Bears on jobs 4–5. **Severity High for recall.** Category 1 with a category-3 edge; the agent leans strongly to fixing. Pinned by `truncating a common-name block can starve a whole source`.

**F7 — A paper whose authors all share one name emits no coauthor key.** `pipeline/Blocking.scala:337` — `names.filterNot(_ == mention.normalizedName)` (verified). Excludes by *name*, not mention identity, so three authors all "Wei Wang" emit **zero** `NameCoauthor` keys — the strongest signal for a common name (per `:230-231`) is absent exactly where homonyms are certain, and those records fall back to the flooding `NameInitials` block. Compounds with F3. *Should:* exclude by `mentionId`. Bears on job 4 coauthor-context blocking and the common-name requirement. **Severity Medium-High**; one-line fix. Pinned by `a paper whose authors all share one name loses that escape`, `a giant team sharing one name emits no coauthor key`.

**F8 — Commented-out and CDATA-escaped records are ingested as live publications.** `pipeline/XmlRecordStream.scala:76-95` (`seekRecordStart`); the comment at `:87-90` claims the opposite of what the code does. The name scan consumes `!--`, matches no tag, then finds the `<article` **inside** the comment; the ghost record parses and is accepted with its own key and provenance. Whitespace-sensitive: `<!-- <article` is ingested, `<!--<article` is not. *Should:* skip comment/CDATA regions, or correct the comment and reject such records. Bears on the gate "DBLP parser handles record boundaries correctly" — currently established only for comment-free input. **Severity Medium** (DBLP dumps carry neither; the false comment is what makes it routable). Pinned by `a record tag inside an XML comment is read as a live record`, `whether a commented-out record is ingested turns on one space`, `a record tag inside a CDATA section is read as a live record`.

**F9 — A literal `>` in an attribute value kills the record reader.** `pipeline/XmlRecordStream.scala:107-142` (`readRecordBody`), tag-close handling `:124-133`, throw `:119-122`. XML permits literal `>`; the reader treats the first one as end-of-start-tag, so a self-closing record never returns to depth zero and throws `IllegalStateException` out of the reader — fatal for the split, not one reject. The paired-tag form recovers by luck (both pinned). *Should:* track quote state inside a start tag. Same gate as F8. **Severity Medium** — well-formed input, fatal outcome, low frequency. Pinned by `a literal '>' in an attribute value kills a self-closing record`, `the same attribute is survivable when the record has a closing tag`.

**F10 — One unterminated record destroys every record already read from the split.** `pipeline/XmlRecordStream.scala:119-122` via `iterator:155-156`, `records:193-203`. The iterator throws mid-drain, so a complete record preceding a truncated one is lost with it; a dump truncated at its tail yields **nothing** from that split. *Should:* emit the preceding records, then report the tear as a reject. Bears on "write malformed records to versioned reject outputs". **Severity Low-Medium.** Category 1 for the data loss, category 3 for fail-fast — the agent leans: keep loud failure if preferred, but not at the cost of records already parsed. Pinned by `one unterminated record destroys every record read before it`.

**F11 — Non-UTF-8 bytes are silently replaced rather than counted or rejected.** `pipeline/XmlRecordStream.scala:140-142` (bytes reinterpreted as UTF-8 at the end of `readRecordBody`), compounded by `pipeline/XmlFragment.scala:70` re-encoding. A Latin-1 record becomes U+FFFD with no counter, no reject, no diagnostic — and the corruption reaches `normalizedTitle`, a **join key** (`"Café." → "caf"`). *Should:* decode with a reporting decoder and reject with a counter, or honor the declared encoding. Bears on schema-failure/rejected counters and the provenance rule. **Severity Medium.** Pinned by `a non-UTF-8 byte is silently replaced rather than rejected`.

**F13 — Five minor reporting and attribution defects**, each independently routable, all category 1, all **Severity Low**:

- **F13a** `pipeline/IdentifierJoin.scala:180` returns `conflictingTitles.size` as the conflict count (`jobs/IdentifierJoinJob.scala:109` increments by it): three disagreeing titles is one conflict, reported as three. *Should:* `titles.size - 1`. Pinned by `the conflict count is the title count, not the conflict count`.
- **F13b** `pipeline/TeamExpansion.scala:125-137`, reason `OversizeAuthorTeam` at `:130`; `pipeline/TaskSink.scala:42-51` has no code for a withheld fan-out, so an operator cannot tell "team too large" from "pairs withheld" — different remedies. *Should:* a distinct `RejectReason`. Pinned by `a suppressed fan-out is reported as an oversize author team`.
- **F13c** `pipeline/TeamExpansion.scala:111` (`authors.size < 2`), counter at `pipeline/TaskSink.scala:34`: a zero-author work increments `SingleAuthorWorks`, conflating none with one. *Should:* count apart, or rename — owner's choice. Pinned by `a zero-author work is counted as a single-author work`, `a record with no author element at all is accepted`.
- **F13d** `pipeline/ExactIdentifiers.scala:108-116` returns `None` on a failed check digit and `DblpParser.scala:128-132` drops it: a transposed ORCID is indistinguishable from an absent one. The refusal is right (`:105-107`) — the silence is the defect. *Should:* a quality counter. Pinned by `an unusable ORCID is dropped with no counter and no reject`.
- **F13e** `pipeline/IdentifierJoin.scala:86` (`.distinct` over `(namespace, value)`) with attribution at `:97-105`: two authors sharing an ORCID collapse onto whichever mention comes first, second claim untraced. *Should:* count as a conflict on a person identifier. Pinned by `one ORCID on two mentions of one work collapses to the first`.

### Three non-defect items the agent condensed rather than expanded

- Unbounded DOM recursion in `XmlFragment.text:105-114` with `catch case error: Exception` at `:73-77` not catching `StackOverflowError` — category 3, hardening.
- `IdentifierJoin.resolve` buffering every claim unboundedly for a hot ORCID — category 3; the scope split itself is correct and well reasoned.
- Every DBLP record carrying `&eacute;` being a `MALFORMED_XML` reject under the no-DTD policy — **category 2, correct and deliberate** per `XmlFragment.scala:10-22`. But this reject class should dominate a full-snapshot run, so whoever sizes the reject output must expect it.

Also category 2, needing nothing: `stripHomonymOrdinal` discarding DBLP disambiguation (documented tradeoff, consequence now asserted), the 141/142-author cutoff from the default `maxPairsPerWork`, and the selective-strategy salt exemption.

## Part 4 — The agent's two recommendations

**1. The `.fail`-paired tripwire — recommended.** munit supports `test("…".fail) { … }`, where the body is *expected* to fail: the assertion states the **desired** behavior, the suite stays green while the defect is open, and it turns **red the instant the defect is fixed**, forcing removal of the `.fail` tag — so the fix cannot land silently and the corrected assertion is written in the test rather than a comment. The agent would pair each of the thirteen pinned-as-observed cases with a `.fail` case carrying the corrected assertion, keeping both: the pinned case is the precise diagnostic, the `.fail` case is the tripwire. Caveat it recorded: a `.fail` case passes if its body fails *for any reason*, so it is weaker as a diagnostic alone — hence keeping both rather than substituting.

The agent added, unprompted: "my original choice to pin rather than write red tests is a judgement call that deserves the reviewer's scrutiny, not my own sign-off, and `.fail` pairing is strictly better than what I shipped."

**2. `AdversarialJobConfigSuite` should stay as its own suite.** It is the **only** suite that constructs a Hadoop-typed object (`new Configuration(false)` via `scholarchain.hadoop.jobs.JobConfig`), making it the direct isolated proof of the Phase 2 F2 classpath widening — if it fails to compile or throws at construction, the finding is `build-engineer`'s, not `hadoop-engineer`'s, and separation keeps that routing signal clean. Its subject is a project *rule* (provenance integrity) rather than one of the six categories. If the architect prefers the six to stand alone, the natural redistribution is the two F12 cases into `AdversarialDuplicateIdSuite` and the hyperauthorship-bounds round-trip into `tests/integration` — but the isolated F2 proof would be lost, so the agent advises against it.

---

# The two failures in run 9

Established by a targeted reproduction the human ran at 13:22: `testOnly` on both suites, 11 cases, 9 passed, 2 failed, in 1 second.

### Failure 1 — `AdversarialJobConfigSuite."a dataset name containing an equals sign is silently renamed"`

```
tests/adversarial/AdversarialJobConfigSuite.scala:95 assertion failed
94:    )
95:    assert(restored.snapshotId != run.snapshotId)
```

The case expected the snapshot digest to **change** when a dataset name contains `=`. It did not change. So **F12's dataset-name claim is not substantiated as written.** Either the mangling the agent describes does not occur, or it occurs without altering the digest.

Note the sibling case `a version containing an equals sign does round-trip` **passed**, and the comma case is not in the failing list, so the "version containing a comma is silently discarded" half of F12 appears to hold. F12 is therefore **partly wrong and needs re-derivation before it is routed as a defect.**

This failure is in `tests/**` and is `test-engineer`'s own expectation being wrong, not a `hadoop-engineer` defect.

### Failure 2 — `AdversarialCommonNameSuite."the salt fan-out is a constant factor, not a bound"`

```
tests/adversarial/AdversarialCommonNameSuite.scala:112 researcher|name-initials|wang|ww|s7
111:      salted.head
112:    )
```

The assertion failed carrying the observed key `researcher|name-initials|wang|ww|s7` as its message — an expectation about the salted key or the fan-out factor that does not match actual behavior. Also `test-engineer`'s own territory.

### Orchestrator diagnosis of both failures, 2026-11-06

Performed read-only by the orchestrator while `test-engineer` was rate-limited, because the salt question decided ownership and routing is the orchestrator's call. **Neither failure is a production defect.**

#### The salt is deterministic. Nothing routes to `hadoop-engineer`

`Blocking.scala:172-176` derives the bucket as SHA-256 of the value, first two bytes, mod `saltBuckets`:

```scala
private def digestBucket(value: String, buckets: Int): Int =
  val digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8))
  val unsigned = ((digest(0) & 0xff) << 8) | (digest(1) & 0xff)
  unsigned % buckets
```

`salted` at `:182-188` applies it to `memberId`. The bucket is a pure function of content — no run ID, no hash seed, no iteration order, no clock. The doc at `:163-166` claims it and the code holds it. **The determinism gate is not threatened.**

The failing assertion was never about the salt. `AdversarialCommonNameSuite.scala:109-112` asserts the prefix `researcher|name-initials|wang|w|s`, while the observed key was `researcher|name-initials|wang|ww|s7`. The discrepancy is the **given-initials segment**, `w` against `ww`; the salt segment `s7` is present and correct. The genuine salt assertions at `:104-107` — bucket count within `saltBuckets`, largest bucket at least `count / saltBuckets` — **passed**. The salt fan-out claim was validated; only a hardcoded prefix string was wrong.

#### F12's mangling is real; its blast radius is not

The `assertEquals` at `AdversarialJobConfigSuite.scala:91-94`, asserting `restored.datasetSnapshots == Map("db" -> "lp=2026-11-01")`, **passed**. The silent rename is genuine and correctly pinned. Only `:95`, `assert(restored.snapshotId != run.snapshotId)`, failed — and `RunContext.scala:45-50` explains exactly why:

```scala
val snapshotId: String =
  val digest = MessageDigest.getInstance("SHA-256")
  datasetSnapshots.toVector.sorted.foreach { case (dataset, version) =>
    digest.update(s"$dataset=$version\u0000".getBytes(UTF_8))
  }
```

The digest joins each pair with `=`. So `Map("db=lp" -> "2026-11-01")` digests `"db=lp=2026-11-01\u0000"` and `Map("db" -> "lp=2026-11-01")` digests `"db=lp=2026-11-01\u0000"` — **byte-identical**. The digest uses `=` as its own separator, so shifting the `=` boundary between key and value is invisible to it. The comma case still passes because a comma changes the *value content*, so its digest input genuinely differs, and `\u0000` separates entries.

**Therefore F12's claim that this "changes every output path and every `Provenance.snapshotId` in the run" is false for the dataset-name half.** `snapshotId` is invariant, so `runPath` and every artifact path are invariant too.

The actual downstream effect appears to be at `RunContext.scala:54-58`:

```scala
def snapshotOf(dataset: String): String =
  datasetSnapshots.getOrElse(
    dataset,
    throw new IllegalArgumentException(s"Unpinned dataset snapshot: $dataset")
```

After the round-trip the map is keyed `"db"`, not `"db=lp"`. A stage asking for `snapshotOf("db=lp")` **throws**; one asking for `snapshotOf("db")` receives the corrupted version string `"lp=2026-11-01"` as its provenance value. So the consequence is a hard failure or a wrong provenance value, not a silent path move. This is the orchestrator's reading and `test-engineer` has been asked to verify rather than accept it.

**Net effect on F12:** the rename is real and pinned; the path and digest blast radius is withdrawn; the downstream effect relocates to `snapshotOf`. F12 survives at reduced severity, and its gate-bearing claim against "idempotent output paths containing run ID and dataset snapshot" **does not hold** — those paths are unaffected.

### What the two failures mean for the report's reliability

The agent predicted 211 of 211 and stated that "none of them should produce a red test, because each is pinned as the behavior currently observed." **Both claims are wrong**, and neither failure appears in its own triage list — which anticipated only a compile failure, a missing suite, or the three byte-ratio inequalities in `AdversarialGiantTeamSuite` (all of which passed).

Two of its pins did not match actual behavior. That does not invalidate the other eleven findings, several of which cite verified line numbers and are internally consistent, but it does mean **the thirteen must be independently verified before any is accepted as a defect.** F12 in particular is now known to be partly wrong, and it is one of the five gate-bearing findings.

---

# Corrections after run 9

Author: `test-engineer`, 2026-11-06 16:54. Usage: 302932 subagent tokens, 74 tool uses.

Orchestrator verification: only `AdversarialJobConfigSuite.scala` and `AdversarialCommonNameSuite.scala` changed, both at 16:54. Case counts move 4 → **6** and 7 → **8**. Total **68 cases**, so 146 + 68 = **214** predicted. Nothing outside `tests/**`.

**Both failures were the agent's own expectations, as diagnosed. Neither is a production defect.** The agent verified both independently rather than accepting the orchestrator's reading.

## The salt case

The salt is deterministic — "a function of exactly one thing: the member ID's bytes," with `MessageDigest` constructed per call so there is no shared mutable state. Confirmed at `Blocking.scala:172-176` and `:182-188`. The determinism gate is not threatened and nothing routes to `hadoop-engineer`.

**The real cause, which the agent found and named frankly:** two initials conventions coexist. `TextNormalizer.scala:59-60` — `initials` folds over **every** token including the family name, so `"Wei Wang 0001"` yields `"ww"`. `:63-69` — `nameBlockKey` uses `tokens.init`, yielding `"wang|w"`. `researcherKeys` builds the `NameInitials` key from `familyToken` + `initials`, so the real key is `researcher|name-initials|wang|ww|s7`. The agent had already identified this distinction under F2 and then contradicted it one file over: "Careless, not subtle."

The genuine salt assertions at `:104-107` passed in run 9, so **the fan-out claim is now execution-confirmed**: 800 members, 8 buckets, largest bucket ≥ 100 against a bound of 50. Only the literal prefix was wrong. The corrected case strengthens rather than relaxes — it now also asserts the salt suffix is numeric and below `saltBuckets`.

**One case added:** `the salt bucket is a pure function of the member ID` emits one work twice under two **different run IDs** and asserts byte-identical block keys. The agent's reasoning for adding it is sound: the salt is the only part of a blocking key derived through a digest rather than by folding text, and the module's determinism evidence comes from `LocalPipelineSuite`, whose fixtures may not cover a salted non-selective strategy. The determinism gate item now has a case aimed directly at it.

## F12, partly withdrawn

The agent confirmed the digest invariance independently and withdrew the overclaim without hunting for a substitute assertion to keep the finding alive.

| | map | digest input |
|---|---|---|
| original | `Map("db=lp" -> "2026-11-01")` | `"db=lp=2026-11-01\u0000"` |
| restored | `Map("db" -> "lp=2026-11-01")` | `"db=lp=2026-11-01\u0000"` |

- **Withdrawn:** the dataset-name half's claim that it "changes every output path and every `Provenance.snapshotId`." False. `snapshotId`, `runPath` and every artifact path are invariant.
- **Survives, execution-confirmed:** the silent rename itself.
- **Survives, execution-confirmed, and this is what keeps F12 gate-bearing:** the **comma half**. It drops half the map, the digest genuinely differs, and path and provenance divergence are real.
- **New, narrower, replacing the withdrawn claim:** `the renamed dataset breaks the snapshot lookup instead` pins the real consequence at `RunContext.snapshotOf:54-58` — `run.snapshotOf("db=lp") == "2026-11-01"`, `intercept[IllegalArgumentException]` on `restored.snapshotOf("db=lp")`, and `restored.snapshotOf("db") == "lp=2026-11-01"` as a corrupted provenance value. A hard failure or a wrong provenance field, not a silent path move. Reachable because every job calls `run.provenance(…, "dblp")` with a literal dataset name.
- **New Low-severity defect, owner `hadoop-engineer`:** `the renamed dataset does not move the snapshot digest` pins the invariance, plus a **digest collision reachable from `RunContext` alone with no round-trip** — two distinct snapshot sets addressing one prefix. Repair: a separator the values cannot contain, or length framing as `StableIds.digestId:183-193` already does.

### Corrected mapping-table row

The F1, F3, F4 and F5 rows are unchanged and all their pinning cases passed in run 9.

| Requirement | Finding | Status after run 9 |
|---|---|---|
| "Idempotent output paths containing run ID and dataset snapshot"; "Every data transformation must record source and transformation provenance" | **F12** | **Still gate-bearing via the comma half only.** Dataset-name path claim **withdrawn**; real effect is a throw at `snapshotOf` or a corrupted provenance version, plus a Low-severity digest collision |

## No test weakened

One false assertion removed; three cases and nine assertions added. The orchestrator accepts this: the removed assertion was false, the salt case is strictly stronger, and the withdrawn F12 claim was replaced by a narrower case pinning a real consequence rather than deleted.

## The agent's own audit of the remaining eleven

Asked whether two wrong pins made it doubt the others. Its answer is the most useful thing in this correction and is recorded in full.

**What it notes first:** run 9 executed all 65 cases and **every pinning case for the other eleven passed**. F1's parser throw, F4's zero duplicates on a fivefold replay, F7's zero coauthor keys, F11's `"caf"` and F3's superquadratic ratio are measured, not hypothesised.

**Its diagnosis of its own method, narrower and better than "be more cautious":** both failures were assertions on *derived* values — a digest inequality, a key literal — not on the behavior under test. Every case that asserted its subject directly passed. The defect was "smuggling a second, unverified claim into a case whose subject was something else." It notes a third case carried the same risk and happened to pass: the n=32/n=64 byte-ratio inequality.

**Where it withdraws confidence — interpretation, not observation:**

1. **F3** — the record count `n*(n-1)` is structural arithmetic and it stands behind it. The **cubic bytes** claim is not measured. Record F3 as "superquadratic, measured at n = 32 and n = 64," not cubic, and not "~9,000,000 bytes-worth."
2. **F6** — its case asserts the retained members are all one source (passed). The further step, that `MatchFeatures` therefore yields zero cross-source candidates, was **never asserted**. Carry F6 as "truncation retains a single source"; the candidate consequence is unverified. It offers to add the assertion on instruction.
3. **F4** — narrow "never" to "cannot fire for DBLP's native key, and by construction for any source whose native key is its record ID." One source was verified.
4. **F5** — the non-determinism is execution-confirmed; its *reachability* ("a re-ingest into shared input") is speculation about operations, not code.

**One observation it explicitly declines to call a fourteenth finding:** the two initials conventions coexist in one key space — `NameInitials` uses `family|initialsOfAllTokens` (`wang|ww`) while `nameBlockKey` uses `family|initialsOfGivenNames` (`wang|w`), both for researcher keys in the same shuffle. Deterministic and harmless, because the family initial is a constant function of the family segment preceding it and so partitions nothing further. Worth a reader's awareness, not `hadoop-engineer`'s time.

**It does not retract the other eleven**, and asks that the record carry the distinction *observation confirmed, interpretation mine* rather than a blanket discount. The orchestrator accepts that framing: it is more precise than the blanket caution recorded before this correction, and the self-audit is more rigorous than the reviewer would have had to be.

## Routing

| Item | Owner | Status |
|---|---|---|
| The two failing cases; re-derive F12 | `test-engineer` | To dispatch — its own territory and its own wrong expectations |
| F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F13a–e | `hadoop-engineer` | To dispatch after independent verification; none accepted as a defect on this report alone |
| `.fail` tripwire pairing; `AdversarialJobConfigSuite` placement | `test-engineer`, on the architect's decision | Awaiting the architect |
| Independent verification of the thirteen | `code-reviewer` | Recommended before any repair dispatch |

The agent does not review its own work and did not ask to. Nothing committed; the marker is stale.
