# Report 10: spark-engineer, repair round 1 (L3) (as received; condensed)

**Status: COMPLETE.** Owner: spark-engineer. Work order 10 (review L3). Local only, no new dependency, no test deleted or weakened, no edits outside `modules/spark/**`.

## Test-to-item (all in `modules/spark/src/test/scala/scholarchain/spark/JsonlExportSuite.scala`; written before the implementation)
- Overlap via `toRealPath()`: "an output path that reaches the run directory by a link is refused" (four cases: link to run dir + `/jsonl`; link that is the run dir; link to the run's parent + `/jsonl`; link that is the run's parent): code 2, "overlap", run tree digests unchanged, no output or staging dir.
- Link into repo or `target`: "an output link into the repository or a target directory is refused" (link to repo root: code 2, "repository", nothing written; link to `<tmp>/target` + `/export`; link that is the `target` directory: code 2, "target", holder tree empty).
- Staging without a marker is not deleted: "a staging directory without the marker is kept and the export stops" (missing marker, forged marker): code 2, "staging marker", `keep.txt` survives, output absent.
- "a marked staging directory with a foreign entry is kept": code 2, "unexpected entries", untouched.
- Never follow symlinks when deleting: "a staging link is refused and its target is not deleted": code 2, "symbolic link", victim keeps marker and `keep.txt`.
- "a stale staging directory with the marker is cleaned and it proceeds": code 0, staging gone, output has `_SUCCESS` and manifest, no marker file, no stale line (5 rows).
- Regression: existing "output inside the run directory..." and "a repeat run gives byte-identical files and manifest" still pass.

## Files modified
- `modules/spark/src/main/scala/scholarchain/spark/JsonlExport.scala`: `exportRun` compares `runDir.toRealPath()` with `OutputPolicy.resolved(requested)` (real path of nearest existing ancestor plus missing names); refuses if either is under the other ("overlap"); output and staging sibling use the resolved real path; a dangling symlink as the output path is refused. `deleteTree(staging)` replaced by `prepareStaging`: an existing `.staging-<name>` is deleted only if it is a plain directory (not a symlink) holding a regular marker file `.export-staging-marker` matching `scholarchain-export-staging <exportVersion> <random UUID>` and containing only names the tool writes (marker, `_work`, three table dirs, `export-manifest.json`, `_SUCCESS`); otherwise refuse "Refusing to delete ...; remove it by hand" before any work. New staging dir via `createDirectory` with a fresh marker; marker deleted just before the atomic move. `deleteTree` uses `walkFileTree` with `NOFOLLOW_LINKS`.
- `modules/spark/src/main/scala/scholarchain/spark/Config.scala`: `OutputPolicy.resolved` made public; `OutputPolicy.check` now tests the `target` component on the real path as well as the lexical one (repo test already used the real path). **`CollaborationPipeline` also calls `check`, so the pipeline now also refuses a symlink into `target`.**
- `JsonlExportSuite.scala`: six tests added, none removed.

## Commands
`sbt quick` once, alone: `Passed: Total 62, Failed 0, Errors 0` for the spark group; `[success] Total time: 130 s`, completed 12:33:51 PM. Other module totals not read. `sbt check` not run, per the work order.

## Assumptions and risks
- The repo-symlink test needs `OutputPolicy.repositoryRoot(Paths.get(""))` to find a `.git` above the working directory.
- A crash between marker removal and the atomic move leaves an unmarked `.staging-<name>`, which the next run refuses and the human must delete (safe direction). A `.staging-*` left by a pre-repair run has no marker and needs manual removal.
- New tests are fast (0.3 to 0.9 s each); no `slowSuites` change.

## Gate status
Quick set passed on these sources (turn evidence; wrote the marker). Pending: the human's `sbt clean check`; the independent re-review of L3 including the `OutputPolicy` change that reaches `CollaborationPipeline`; the real-data runs.
