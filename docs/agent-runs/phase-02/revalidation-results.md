# Phase 02 revalidation gate results

Step 1 of `docs/agent-runs/phase-02/revalidation.md`. Date: November 5, 2026. Repository root: `/Users/winnguyen/ScholarChain`. HEAD `f4453d6`, working tree clean at dispatch.

The human architect ran the gate command with the `!` prefix, which `CLAUDE.md` records as the accepted workflow for the test gate. An agent cannot run `sbt check` inside the shell sandbox; that is an accepted open gate, not a defect. The orchestrator did not run it and recorded the human's observed output.

## Command and result

```
SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false sbt check
```

Result: `[success] Total time: 1 s, completed Nov 5, 2026, 11:13:28 PM`. No failures or errors.

The server environment variables disable sbt's server autostart and force startup without binding a local socket, matching the restricted-runner note in `docs/agent-runs/phase-02/versions.md`.

## Observed output

```
[info] welcome to sbt 1.11.7 (Eclipse Adoptium Java 21.0.13)
[info] loading settings for project scholarchain-build-build from build.sbt...
[info] loading project definition from /Users/winnguyen/ScholarChain/project/project
[info] loading settings for project scholarchain-build from plugins.sbt...
[info] loading project definition from /Users/winnguyen/ScholarChain/project
[info] loading settings for project root from build.sbt...
[info] set current project to scholarchain (in build file:/Users/winnguyen/ScholarChain/)
[info] scalafmt: Checking 1 Scala sources (/Users/winnguyen/ScholarChain)...
[info] scalafmt: Checking 3 Scala sources (/Users/winnguyen/ScholarChain)...
scholarchain.core.ConfigSuite:
  + load versioned local YAML with explicit environment precedence 0.019s
  + reject invalid environment numbers, windows, thresholds and secret-bearing endpoints 0.003s
  + reject unknown configuration keys and missing or unsupported versions 0.002s
scholarchain.core.ContractSuite:
  + freeze work and match-decision wire shape, values, and option representation 0.024s
  + reject absent, null, incomplete and unversioned provenance 0.003s
  + reject absent and unsupported top-level and nested versions 0.001s
  + reject fractional and text schema versions 0.001s
  + reject malformed stable IDs including noncanonical base64url 0.002s
  + reject invalid confidence through constructors and wire decoding 0.001s
  + canonical edge key is independent of pair order, but the contract requires sorted distinct IDs 0.004s
  + candidate pairs sort mention IDs while preserving author positions 0.004s
  + reject reversed year windows through the wire and invalid edge observation years 0.001s
  + stable internal IDs use framed fields and bind identity, text, versions, and graph evidence 0.003s
  + stable IDs reject isolated UTF-16 surrogates and preserve supplementary characters 0.003s
  + run IDs are random UUIDv4 and the immutable manifest preserves the run and snapshot IDs 0.001s
  + claims resolve to evidence in the wire response 0.004s
  + reject unknown enum wire values 0.001s
  + coauthor graph edges require matching collaboration statistics 0.003s
[info] Passed: Total 18, Failed 0, Errors 0, Passed 18
[success] Total time: 1 s, completed Nov 5, 2026, 11:13:28 PM
```

## Gate item 1 assessment

`sbt check` exits 0. Per `build.sbt`, the `check` alias runs `root / markTestRun`, whose `Def.sequential` chain is formatting check, sbt formatting check, `Compile / compile` across all projects, `Test / test` across all projects, then the marker write. `[success]` on the alias therefore means every stage passed, including Test compilation, which `Test / test` requires. The marker at `.claude/.last-test-run` is written only after that chain.

Two precise limits on what this first run proves:

- `sbt Test/compile` was not issued as a separate command. Its work is subsumed by the `Test / test` stage, so the gate's intent is met, but the literal second command in the gate text was not run on its own.
- Scalafmt reported checking 1 and then 3 Scala sources, while `modules/core` holds 5 main and 2 test Scala files. Scalafmt skips files whose cached state is unchanged, so this is consistent with an incremental run rather than a full-tree format check. The compile stages likewise printed no compilation lines, consistent with up-to-date incremental state.

The second limit is closed by the clean-state run below. Neither limit was a failure. Both are recorded so the acceptance record states what was observed rather than what was assumed.

Status: **PASS**, with the `Test/compile` scope note above.

## Clean-state confirmation

To close the incremental-run gap, the human architect ran:

```
SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false sbt clean check
```

Result: `[success] Total time: 7 s, completed Nov 5, 2026, 11:20:10 PM`, with `Passed: Total 18, Failed 0, Errors 0, Passed 18`.

```
[info] welcome to sbt 1.11.7 (Eclipse Adoptium Java 21.0.13)
[info] loading settings for project scholarchain-build-build from build.sbt...
[info] loading project definition from /Users/winnguyen/ScholarChain/project/project
[info] loading settings for project scholarchain-build from plugins.sbt...
[info] loading project definition from /Users/winnguyen/ScholarChain/project
[info] loading settings for project root from build.sbt...
[info] set current project to scholarchain (in build file:/Users/winnguyen/ScholarChain/)
[success] Total time: 0 s, completed Nov 5, 2026, 11:20:04 PM
[info] scalafmt: Checking 5 Scala sources (/Users/winnguyen/ScholarChain/modules/core)...
[info] scalafmt: Checking 2 Scala sources (/Users/winnguyen/ScholarChain/modules/core)...
[info] scalafmt: Checking 1 Scala sources (/Users/winnguyen/ScholarChain)...
[info] scalafmt: Checking 3 Scala sources (/Users/winnguyen/ScholarChain)...
[info] compiling 5 Scala sources to /Users/winnguyen/ScholarChain/modules/core/target/scala-3.3.7/classes ...
[info] done compiling
[info] compiling 2 Scala sources to /Users/winnguyen/ScholarChain/modules/core/target/scala-3.3.7/test-classes ...
[info] done compiling
```

The 18 test lines were identical to the first run and are not repeated. What this run adds over the incremental one:

- **Formatting observed across every source.** Scalafmt checked all 5 `modules/core` main sources and both test sources from a cold cache, closing the reviewer's pending item on the seven new Phase 03 files.
- **Compilation observed from scratch.** Both `modules/core` compile steps ran and printed `done compiling`, so `Compile / compile` and `Test / compile` are confirmed on the current tree rather than inherited from a cached state.
- **Scala 3.3.7 observed directly.** The output paths `modules/core/target/scala-3.3.7/classes` and `.../test-classes` confirm the compiler version from the build itself, not only from the ledger pin.
- **Stage order confirmed in practice.** The emitted order is formatting, then compilation, then tests, then success, matching the `Def.sequential` chain the reviewer read statically in `build.sbt:77-99`.

Two observations from this run, neither a failure:

- No module other than `core` emitted a compile or test line, confirming the reviewer's observation that `(Test / test).all(allProjects)` currently succeeds vacuously for the six modules with no test sources, and that the root `tests/` source paths hold no Scala files.
- The root-level Scalafmt lines report 1 and then 3 sources. Since the root project has no Compile sources and its `tests/` test paths are empty, these come from the sbt-file checks. Three files under the build definition are therefore being checked, which suggests `project/project/build.sbt` is in fact reached, contrary to the reviewer's observation 4. The output does not name the files, so this is not conclusive and the observation is left open rather than retracted.

Gate item 1 status after both runs: **PASS**. The only remaining scope note is that `sbt Test/compile` was never issued as a standalone command.

## Gate items 2 and 3

Reviewed statically by `code-reviewer`. See `revalidation-review.md`.
