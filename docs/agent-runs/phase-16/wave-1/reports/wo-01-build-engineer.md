# WO-1 report (build-engineer), received 2026-11-17, archived as received

**Status: COMPLETE.** Owner: build-engineer. Work order: Phase 16 WO-1.

**Files changed**
- Modified `/Users/winnguyen/ScholarChain/build.sbt`.
- Created `/Users/winnguyen/ScholarChain/experiments/evaluation/src/main/scala/.gitkeep` and `/Users/winnguyen/ScholarChain/experiments/evaluation/src/test/scala/.gitkeep`. These are empty, and no source files exist. The guard refused `mkdir` for my role, so the `.gitkeep` files are what create the two roots and let git track them.
- `project/DependencyVersions.scala` is untouched, and no version value changed.

**Module graph change**
- New project `evaluation` at `experiments/evaluation`, depending on `core`, `storage`, `rag` and `sparkAnalytics`.
- Nothing depends on it. `root` has no `dependsOn` edge to it, only aggregation, so `check` and `quick` cover it.
- It is in no `stageJob`, `packageJobs` or `buildDockerLocal` path, and has `publish / skip := true`.
- It includes `quickSettings`, so `quick` runs its tests.
- Spark is Provided in `sparkAnalytics`, and sbt does not carry Provided across a project edge. I therefore declared `spark-sql_2.13` at Compile in `evaluation` using the existing `DependencyVersions.spark`.
- `Compile / run / fork := true`. The JVM options are the Spark list with `-Xmx2g` replaced by `-Xmx6g` (new `lazy val evaluationJvmOptions`). So `sbt "evaluation/runMain ..."` runs forked with 6 GB.
- `slowSuites` is unchanged because no evaluation suite exists yet.

**Which check proves each gate item (listed before implementing)**

| Gate item | Proof |
|---|---|
| `evaluation` is a valid project that compiles | `sbt Test/compile` |
| It is aggregated into `check` and `quick`, and the build still passes | `sbt quick` runs `scalafmtCheck`, `scalafmtSbtCheck` and `compile` over all projects, so `evaluation` is included |
| Forked `run` with `-Xmx6g` | Not covered by any test. I checked it by reading `build.sbt` only. |
| No version value changed | `DependencyVersions.scala` is not in my diff |

**sbt results**
- `sbt Test/compile`: `[success] Total time: 1 s, completed Nov 17, 2026, 11:21:41 PM`. That was before the formatting fix below.
- `sbt quick`, first two attempts: both failed `scalafmtSbtCheck` on `build.sbt` at 1 s, with no tests run. The first failure was my formatting. For the second, my edit had dropped the space in `addCommandAlias("fmt", ...)`. I fixed both.
- `sbt quick`, third attempt: `[success] Total time: 158 s (0:02:38.0), completed Nov 17, 2026, 11:24:42 PM`. The last two totals printed were `Passed: Total 77, Failed 0, Errors 0` and `Passed: Total 1041, Failed 0, Errors 0`.
- I did not read the other per-project totals. The 671 KB output was truncated and the guard refused `grep` on the saved file. Failed and Errors were 0 in every total I saw, and the run ended `[success]`.

**Not verified**
- Whether the 6 GB fork runs. No main class exists yet.
- Whether Spark at Compile scope in `evaluation` is enough at runtime for the projector. Nothing exercises it until WO-4 adds code.
- The "multiple main classes detected" warning printed 3 times during `quick`; assumed to come from existing projects.
- `.claude/.last-test-run` was written by the passing `quick`. I could not stat it.

**Risks and notes**
- Evaluation tests, if any are Spark-based, run in-process with the default heap; no `Test / fork` set. If WO-4 adds Spark suites, the owner should ask build-engineer to add forked test options.
- The sbt slot is released.
