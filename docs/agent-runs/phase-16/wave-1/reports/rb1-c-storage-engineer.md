# RB1-C report (storage-engineer), received 2026-11-18, archived as received

**RB1-C report (storage-engineer), finding 15**

**Status: COMPLETE.** Owner: storage-engineer. Order: Phase 16 Wave 1, RB1-C. The sbt slot is released.

**Design choice**
- **Link escape in `listSizes`:** a file whose real path leaves the root is no longer listed. The check is `toRealPath().startsWith(base.toRealPath())`, the same rule `get` already applies. A link that stays inside the root is still listed.
- **Overlap between `emptyObjects` and real files:** the constructor now refuses it with `StorageException(InvalidKey)`. Refusal over silent serving: serving one value consistently would either hide a real file's content behind a zero-byte object or list it at a size `get` does not return. The check covers a regular file and a symlink at that path.
- **Related inconsistency also fixed:** with `manifestOverride` set and no `export-manifest.json` on disk, `get` served the override but `listSizes` omitted it. `listSizes` now lists it at the override length. Extras are deduplicated by key.

**Files**
- Modified `modules/storage/src/main/scala/scholarchain/storage/LocalExportStore.scala`.
- Modified `modules/storage/src/test/scala/scholarchain/storage/LocalExportStoreSuite.scala`. No existing test was edited or weakened.

**Tests added**
1. "listSizes omits a file whose real path leaves the root" (exact key list; `list` agrees with `listSizes`).
2. "a link that stays inside the root is still listed" (control).
3. "an emptyObjects entry that names a real file is refused" (real file and symlink; `InvalidKey`).
4. "get and listSizes agree on the override and empty objects" (override not on disk; `get` length equals listed size for every key).

**Command and results**
- Bare `sbt quick`: `Passed: Total 77, Failed 0, Errors 0, Passed 77`, then `Passed: Total 1096, Failed 0, Errors 0, Passed 1096`, then `[success] Total time: 162 s (0:02:42.0), completed Nov 18, 2026, 1:54:28 AM`.
- The log shows scalafmt checking the storage sources and `compiling 1 Scala source` into storage `classes` and `test-classes`.
- `sbt check` not run.

**Not verified**
- Per-test lines for `LocalExportStoreSuite` not read (grep refused); pass inferred from Failed 0 and Errors 0.
- Storage's own total unread; the 1096 line is not storage's (it equals WO-4's earlier run).
- `ExportHeapMeasureSuite` real-data path not run.
- Phase gate pending.

**Risks**
- A caller passing an `emptyObjects` entry naming an existing file now gets `InvalidKey` at construction. No such caller found in the storage module; other modules not scanned.

## Orchestrator note (not part of the report)
- `quick` compiles the whole tree: it ran while experiment-engineer (RB1-A) and test-engineer (RB1-B) were editing without sbt. The run passed, so the partially edited files compiled at that moment; it is not evidence for RB1-A or RB1-B.
