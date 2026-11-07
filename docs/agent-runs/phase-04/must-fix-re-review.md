# Phase 4 must-fix re-review

Reviewer: `code-reviewer`, fresh instance. Received by the orchestrator 2026-11-06.

Owners of the reviewed work: `hadoop-engineer` (repairs 7, 9), `test-engineer` (repair 9b), **OpenAI Codex** driven by the human architect (repair 8).

Scope: the five must-fix items from [final-review.md](final-review.md), plus the F8 comment correction, the F12 digest collision, the pin conversions and the nine `.fail` twins. Validation: [validation.md](validation.md), run 11, 229/229.

Usage: 180834 reviewer tokens, 45 tool uses, 504471 ms. Read-only throughout; no file created or modified, no command run.

## Two verdicts

**On the repairs (7, 8, 9, 9b): APPROVE.** All five must-fix items are closed in the shipped source, each with an executed assertion behind it.

**On Phase 4's readiness: RATIFIABLE**, conditional on two record corrections and one commit-hygiene action. No gate item is falsified by the current tree.

## The five must-fix items

### F1 + F9 + F10 + F11 — CLOSED

`DblpParser.scala:122-141` now **filters** a mention whose `nameKey` is empty inside `Option.when(...)` rather than constructing it, so `Contracts.scala:138` is never reached and the length mismatch at `:145-148` is **live code** returning `MissingRequiredField`. The title half is guarded at `:149-152`, before the `WorkRecord` construction at `:174`. Validation precedes construction at both sites and no core contract was touched.

A backstop at `DblpParser.parse:47-50` wraps `parseElement` in `catch case error: IllegalArgumentException => SchemaViolation`. Since every core contract failure is a `require`, this converts any residual contract throw into a reject — "good defense in depth."

- **F9** — `XmlRecordStream.scala:115,123-128`. The reviewer traced `<article key="a>b"/>`: the `>` arrives with `quote >= 0` and is appended as content, the closing `/>` sets `selfClosing`, depth stays 0, the record closes. Misparse gone.
- **F10** — `:118-120` sets `exhausted`/`closed` at EOF and returns the partial text; the torn fragment reaches `parse` and becomes a `MalformedXml` reject.
- **F11** — `:148-153` uses a reporting `UTF_8.newDecoder()`.

**The owner's correction about the map-level `try` is CONFIRMED.** `readRecordBody` is reached from `DblpXmlRecordReader.nextKeyValue`, which Hadoop's `MapTask` calls outside the user `map` body, so no `try` in `DblpExtractionJob.map` could have covered F9 or F10 — and `map:51-90` contains **no** `try`. The guard is inside `DblpParser.parse`, which is the right place.

Runtime proof at `MapReduceEndToEndSuite.scala:496-547`: six records from one file containing a `>`-in-attribute record, a non-UTF-8 record and a truncated tail; the job **succeeds** (`:520`), `InputRecords=6`, `RejectedRecords=4`, and `:530-534` asserts no committed title carries `\u0000` or `�`. First evidence in the record that a malformed record cannot kill the stage.

Two non-blocking notes: **`parseIdentity` has no `try` backstop** (`:222-269`) while `parse` does — not reachable today (the reviewer chased it through `XmlFragment.attribute`/`textsOf`, `CanonicalResearcher` and `StableIds.external:162-166`, concluding Xerces rejects the triggering input first) but the same defect class, one line to remove. And **F11's mechanism is an outlier**: `:153` signals "reject this" by appending `"\u0000"` and relying on the XML parser refusing NUL, where every other reject in the module is a typed `Either`.

### F3 + F7 — CLOSED, and the owner's correction is UPHELD

`Blocking.emit:381-414` computes `expectedCoauthorKeys(n) = n*(n-1)` in `Long` (`:347-349`), compares to `run.maxPairsPerWork`, and on exceedance counts `SuppressedBlockingKeys`, emits a reject under a **distinct** `RejectReason.OversizeCoauthorFanOut` (`TaskSink.scala:68`, correctly avoiding F13b), withholds the coauthor keys *and* the repeated `coauthorNames` payload (`:400-401,:411`), and keeps the `O(n)` selective strategies. F7 excludes by `mentionId` at `:404`.

**The reviewer upheld the owner's correction and supplied the general form the owner did not.** Before the fix, exclusion by normalized name gave each mention `d-1` coauthor keys, where `d` is the number of distinct name blocks (`researcherKeys:245-262` applies `.distinct`). After the fix it gives `d` — the added key is the self-pair. **The delta is exactly +1 key per mention, +n per work: linear, not quadratic.** For an all-same-name team `d = 1`, hence one key per mention, as the owner argued. `AdversarialGiantTeamSuite.scala:192-204` confirms it: 20 homonyms → 20 coauthor keys, no rejects. The reviewer also verified the key spaces agree, since `fold` is idempotent.

**The final review's hazard claim is overturned.** Its conclusion — land F3's bound first — was still right, for the independent reason that distinct-name teams already emitted `n(n-1)`.

**The bound and the reuse of `maxPairsPerWork`: accepted.** One budget for both quadratic paths beats two that can disagree; the boundary is pinned exclusively at `AdversarialGiantTeamSuite.scala:166-190`; keeping the selective strategies is right. Two residual observations, neither blocking:

- **The bound is on records while the cost is in bytes.** Just under it (n=100), one input record still expands to ~9 900 records each carrying ~99 names. A byte-aware bound, or dropping `coauthorNames` above a lower threshold, would be tighter.
- **The bound over-suppresses exactly the case F7 was repaired to serve.** It tests `n*(n-1)`, not the actual distinct-block count. A 150-author all-homonym team would emit 150 keys if allowed, but `expected = 22350 > 10000` suppresses it, removing the coauthor signal from the common-name case. The actual count is `n * d`, and `d` is available in `O(n)`. Cheap tightening; a Phase 5 item.

### F4 + F13a — CLOSED, all five native-key sources

`IdentifierJoin.resolve:137` is `claimed.distinct` — full structural equality over all seven `IdentifierClaim` fields including `normalizedTitle`, so disagreeing titles on one native key survive to `:158-161` and the conflict fires. The fix is namespace-agnostic, and the reviewer verified all five native-key namespaces are `IdScope.Work` (`ExactIdentifiers.scala:21,22,24,29,32`), so the `workScoped` gate at `:142` admits DBLP, OpenAlex, SemanticScholar, Crossref→Doi and Arxiv alike. F13a corrected at `:183`.

**Byte-identical replay is still counted, but not by the mechanism the record claimed.** `sourceCounts:143-144` is computed from **`claimed`**, not `distinct`, so two identical claims give `duplicates = 1`. Executed at `AdversarialCounterSuite.scala:64-71`. **`identicalReplays` does not exist anywhere in the repository** — see the record correction below.

**Extra credit, noted by the reviewer:** `DblpExtractionJob.scala:149-167` now decodes the works group and raises `ConflictRecords` plus an `IdentifierConflict` reject, closing the gap the final review named under F4 ("conflicts are not compensated anywhere"). Exercised at `MapReduceEndToEndSuite.scala:523-524`. **Caveat:** `:150` is a **new** `ContractWire.decode` inside a reducer — a new throw-out-of-task site added during the cycle whose theme was removing them. The reject branch at `:135-140` correctly precedes it and the producer is the same job's mapper, so risk is low, but it is on the record.

### R1 — CLOSED, no fifth site

All seven reducers emit through the non-counting `sink.emit`: `DblpExtractionJob.scala:168`, `SourceNormalizationJob.scala:164`, `IdentifierJoinJob.scala:123`, `BlockingJob.scala:145,151`, `CandidateFeatureJob.scala:127`, `PublicationTeamJob.scala:104,114`, `GraphExportJob.scala:186`. The reviewer enumerated every `emitRecord`/`emit` call in `modules/hadoop/src/main`: **no reducer anywhere calls `emitRecord`**. No fifth site missed. Executed at `MapReduceEndToEndSuite.scala:425` (`EmittedRecords == 18`, not 36) and `AdversarialCounterSuite.scala:33-36,58-61`.

**Non-blocking consequence:** `EmittedRecords` is now map-side-only, so **no counter reports final-artifact volume** for blocking groups, match candidates or identifier keys. The pairs path solved this with a second counter; parity would be one reduce-side `CommittedRecords`. Does not falsify "counters and reject outputs are visible," but it is a visibility regression relative to the pairs path.

### R2 — CLOSED at four sites; sibling inconsistency resolved

`LocalPipelineRunner.scala:270-274`, `:314-318`, `:408-412`, `:483-487`. Each excludes `RejectedRecords` from the wholesale copy, then forwards `mapSink.rejects.foreach(reduceSink.reject)`, so `RecordingSink.reject` (`TaskSink.scala:171-173`) counts each reject exactly once. The reviewer confirmed these are the **only** four `counterValues.foreach` sites, each paired with a forward — no site copies without forwarding and none does both. `block()`'s previous asymmetry is fixed, which matters because F3's new map-side reject travels that path. Executed at `AdversarialCounterSuite.scala:29-32` and `:56`.

## F12: declining the write-time `require` was correct

The `require` and the framing address different halves. Framing closes the collision, reachable from `RunContext` alone (`:58-67`, now length-framed exactly as `StableIds.digestId:183-193`). A write-time guard cannot close it, since `snapshotId` is computed with no round-trip. Adding one would have closed the **serialization** half — a carried-forward item — and tripped the two twins at `AdversarialJobConfigSuite.scala:161,177` the architect decided to keep green. "A specialist widening scope to close a carried-forward defect would itself be a deviation. Owner right on mechanism and on process."

**On `test-engineer`'s unanticipated consequence: partly agreed, and sharpened.** The reviewer verified independently that `JobConfig.readSnapshots:52` uses `split("=", 2)`, so an equals in the *version* round-trips cleanly and only an equals in the *dataset name* is lossy. With the collision closed the accidental agreement is gone. **But the dominant observable is not silent provenance drift:** `RunContext.snapshotOf:71-75` throws on a miss, so a stage asking for its configured dataset name fails **loudly**, pinned at `AdversarialJobConfigSuite.scala:132-148` — more precise than either the final review's or the orchestrator's framing. Silent wrong provenance requires a stage asking for the *truncated* name.

So it strengthens the case enough to **schedule** the fix, not to make it a Phase 4 blocker. Reachability is unchanged and low: `PipelineOptions.runContext:21` reads snapshots through the same parser, so no operator `-D` can express it.

**Revised recommendation:** put the guard in **`RunContext`'s constructor**, not at write time. It covers in-code construction, the only reachable path, costs two lines, and closes both halves at once. Tighten the carried-forward condition from "before any config- or manifest-driven snapshot source" to **"with the next change touching `RunContext` or `JobConfig`."**

## The F9/F10/F11 pins test behavior, not shape

Checked specifically, since `test-engineer` flagged writing against an API shape it did not pick. All three assert at a stable boundary and would survive the reject channel moving into the reader, the input format or the job:

- **F9**, `AdversarialXmlSuite.scala:106-119` — asserts the reader returns the exact record text. No sink, no counter, no reject. The reviewer also notes `test-engineer`'s departure from the dispatch was **correct on the XML spec**: a literal `>` is legal in an attribute value, so demanding a reject there would have been a second defect.
- **F10**, `:132-145` — two records read, first parses, torn one yields `Left(MalformedXml)`. Tests the behavior without naming the channel.
- **F11**, `:213-219` — asserts `Left(MalformedXml)`, the outcome, not the NUL-sentinel mechanism.

No shape-matching; the concern does not materialise. Only gap: F11 has no assertion distinguishing encoding corruption from ordinary malformed XML.

## The `.fail` twins: all eight open, none over a closed defect

Checked against source rather than against the table. F5 (`TeamExpansion.scala:163-164` still one discriminator), F13e (`IdentifierJoin.scala:86,100-104`), the `SingleAuthorWorks` conflation, F12 comma and equals (`JobConfig.scala:30-35,46-57` unchanged), F13b (`TeamExpansion.scala:117,130` still share `OversizeAuthorTeam`), F8 skip-region (`XmlRecordStream.scala:89-90` states the limitation), R6 (`XmlFragment.scala:27-30` unchanged, correctly). **No reverse silent-fix hazard found.**

The digest twin was flipped to a plain assertion at `AdversarialJobConfigSuite.scala:193`, and `:126-129` **strengthens** it by pinning the digest as a pure function of the map. "That is the right shape for a flip." No F2 twin with the reason stated in place (`AdversarialUnicodeSuite.scala:82-88`); no F6 twin with the defect still pinned (`AdversarialCommonNameSuite.scala:181-185`). Both match the architect's decision. The F8 comment correction is in place at `XmlRecordStream.scala:89-90` and `:101-103`.

## New observations, carried forward, not blocking

**N1 — `StackOverflowError` escapes the reject channel.** `XmlFragment.text:105-114` walks the DOM with unbounded recursion, and `parse:73-77` catches `Exception`, not `Error`. A sufficiently nested title fails the task attempt and, after retries, the stage — **the exact failure shape F1/F9/F10 were must-fix for.** Not among the thirteen and not in the final review. Reachability needs thousands of nesting levels, so Low. Remedy is an iterative walk or a depth bound, not a broader `catch`.

**N2 — truncated digest.** `RunContext.scala:67` takes 12 hex characters (48 bits). Framing makes the digest input injective; the truncation does not. Irrelevant at this scale; recorded so nobody later reads "framing" as "collision-free."

## On authorship, asked directly

The Codex-authored portion is **comparable in quality** to the Claude-authored work and **in two places better judged** — the reject-before-decode ordering in `DeduplicateReducer`, and extending R2 to four sites rather than the one dispatched. Three differences:

1. **One mechanism is stylistically out of keeping** — the F11 NUL sentinel, where the rest of the module returns typed rejects.
2. **One asymmetry a Claude subagent working from the "one defect class" framing would plausibly have caught** — `parseIdentity` left without the backstop `parse` received.
3. **One silent substitution the record did not catch** — `identicalReplays` removed with the property preserved by other means. The reviewer calls this the material one, "and not because the code is wrong: it shows that a single non-Claude pass editing over another agent's work in the same files can revise it without the revision surfacing in any report, because only the orchestrator's read-through stands between it and ratification."

**On the process boundary:** one actor wrote across `modules/hadoop/**` and `tests/**` in one pass. The human architect may direct any owner's work, so this is not an authority violation — but **the one-writer rule held nominally rather than structurally for repair 8**, and this verdict plus run 11 are the entire compensating control. "That is adequate here and should not become routine."

## On `.agents/`, `.codex/` and `AGENTS.md`

Not reviewed, as instructed. **No effect on the code verdict** — the reviewer read the shipped sources directly, and the `.codex/hooks/` copies are inert unless Codex is driving.

**One bounded effect on the enforcement context:** repair 8 was authored by a tool whose hook copies the reviewer has not read and whose equivalence to `.claude/hooks/` is unverified, so the formatter, test-gate and dangerous-command protections that normally bracket an authoring session **cannot be assumed to have applied to the 19:24–19:26 window.** Run 11 and this review substitute for them. Sufficient for this cycle, and exactly why the drift risk recorded in [versions.md](versions.md) should not wait long.

**A concrete commit-gate hazard, in the reviewer's remit even though the files are not:** those three paths are untracked and `.gitignore` does not exclude them. **An unscoped `git add` at ratification would commit an unreviewed parallel control plane, including copies of the constitutional enforcement scripts. Commit with explicit pathspecs.**

## Conditions on ratification

Neither of the first two is a code change; both are record corrections, "and the record is what a ratification decision reads."

1. **`repairs.md`** — remove or correct the `identicalReplays` claim; correct the "map-level guard" attribution for `DblpExtractionJob`; correct the F13c twin label. **Done 2026-11-06**, each as an in-place correction naming the error rather than a silent edit.
2. **`validation.md`** — the stale run-5-era "Outstanding blockers" block contradicted the gate table 20 lines above it in the same section. **Done 2026-11-06**; replaced with current status and an explicit note that the orchestrator's own archival error left superseded narrative beneath a table it had kept current.
3. **Commit with explicit pathspecs.** Outstanding, for the human at ratification.

## Carried forward

**Unchanged by this cycle:** F2 and F6 (remedies decided in [decisions.md](decisions.md), before Phase 5 consumes blocking keys); F5; F8's skip-region half; F12's serialization half; F13b–e; R3; R5; R6.

**Added by this review:** N1; the coauthor-bound tightening to `n * d`; a byte-oriented blocking bound; a reduce-side `CommittedRecords` counter for parity; `parseIdentity`'s missing backstop; the new reducer decode at `DblpExtractionJob.scala:150`.

**Revised condition:** F12's serialization half to be dispatched with the next change touching `RunContext` or `JobConfig`, as a constructor-time `require`.

**Pending, not verifiable by reading:** the minicluster experiment ([decisions.md](decisions.md) section 3), correctly held until after this cycle's green check. Multi-reducer fan-out remains unexercised, which is R5's and R6's standing Phase 5 condition.

## Next gate

Human ratification of Phase 4. This verdict does not approve its own findings and is itself subject to independent review. Nothing committed.
