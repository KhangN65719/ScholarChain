# WO-2 report (storage-engineer), received 2026-11-18, archived as received

**Status: COMPLETE.** Owner: storage-engineer. Order: Phase 16 Wave 1, WO-2. The slot is released.

**Test-to-gate mapping (planned before the implementation)**
- Read: "get reads a nested part file and the manifest".
- List: "list and listSizes return sorted keys under a prefix".
- Missing key: "a missing key, or a key outside the prefix, is absent".
- Escape refused: "a key that escapes the root is refused" and "a symbolic link out of the root is refused".
- Write refused: "a write is rejected and the directory is unchanged".
- Override and empty objects, which the heap suite needs: "a manifest override and empty objects are served and listed".
- Use through `fromExport`: "AwsReadModels.fromExport loads an export through the store".
- Heap suite assertions are unchanged. It still skips unless `SCHOLARCHAIN_S_EXPORT_DIR` is set.

**Files**
- Created `modules/storage/src/main/scala/scholarchain/storage/LocalExportStore.scala`.
- Created `modules/storage/src/test/scala/scholarchain/storage/LocalExportStoreSuite.scala`.
- Modified `modules/storage/src/test/scala/scholarchain/storage/ExportHeapMeasureSuite.scala`. I removed the private `DirStore` and construct `LocalExportStore` instead. The `assert(loaded.metrics.nonEmpty)` line is unchanged.

**Public API**
```scala
package scholarchain.storage
final class LocalExportStore(
    dir: Path,
    keyPrefix: String,                          // e.g. AwsReadModels.exportPrefixOf(slug)
    manifestOverride: Option[Array[Byte]] = None,
    emptyObjects: Vector[String] = Vector.empty // relative paths served and listed as zero-byte objects
) extends ObjectStore
```
Typical use is `AwsReadModels.fromExport(new LocalExportStore(dir, AwsReadModels.exportPrefixOf(slug)), runId, exportRunId = Some(slug))`.

**Behavior**
- **Keys:** the key for a file is `keyPrefix + <path relative to dir>`.
- **Reads:** `get`, `head`, `list` and `listSizes` all work. `listSizes` reads no file contents.
- **Absent keys:** a key outside `keyPrefix`, or without a file, is `None`.
- **Escapes:** a `..` segment, a path outside the root, or a symlink out of the root throws `StorageException(InvalidKey)`.
- **Writes:** a new key throws `UnsupportedOperationException`. Different content under an existing key throws `ImmutableKeyConflict`. `putImmutable` of identical content to an existing key returns a no-op rather than an error, because `head` reports honestly instead of always returning `None` as `DirStore` did. The directory is never written to.

**Commands and results**
- I ran bare `sbt quick`. It ended with `[success] Total time: 171 s`.
- The final totals were `Passed: Total 77, Failed 0, Errors 0, Passed 77` and `Passed: Total 1041, Failed 0, Errors 0, Passed 1041`.
- The log showed scalafmt checking the storage sources and `compiling 2 Scala sources to ... storage ... test-classes`, so the new suite compiled.
- `sbt check` was not run, as the order did not ask for it.

**Not verified**
- I could not read `LocalExportStoreSuite`'s per-test lines in the 672 KB log. The guard blocked grep on that path. Its pass is inferred from the run's Failed 0 and Errors 0 totals and the test-class compile.
- I did not report which of the two totals (77 or 1041) is storage's.
- The heap suite's real-data path was not run, because the environment variable is unset.
- I did not load the real `/Users/winnguyen/scholarchain-data/rehearsal/a-export`. The `fromExport` test uses a hand-built fixture with every required table empty.

**Risks**
- `list` hashes every file because `head` computes the SHA-256. Callers should use `listSizes` for bulk listing, as `fromExport` does.
- No caller-facing `forRun` helper was added. The evaluation entry point must derive the slug and prefix itself through `AwsReadModels.slugOf` and `exportPrefixOf`.

**Next gate:** independent review, then the evaluation project's use of the class (WO-4).

## Orchestrator note (not part of the report)
- The two totals (77, 1041) are the same last two totals WO-1's `sbt quick` printed before this change; the per-project totals above them were not read by either agent. Review should confirm the new suite's test count from a full-suite run.
