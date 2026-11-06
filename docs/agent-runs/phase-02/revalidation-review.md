# Phase 02 revalidation independent review

Step 2 of `docs/agent-runs/phase-02/revalidation.md`. Date: November 5, 2026.

- Reviewer: `code-reviewer`, dispatched by the orchestrator against the current build files.
- Evidence supplied: `CLAUDE.md`, `docs/agents.md`, `revalidation.md`, `revalidation-diff.md` (orchestrator-produced, read-only Git) and the preserved Phase 02 records.
- Reviewer tools: Read, Grep, Glob. No Bash. The reviewer performed no repository write and ran no commands, and states it did not fix or self-approve anything.
- Tree reviewed: HEAD `f4453d6`, working tree clean. Build files byte-unchanged since the accepted Phase 02 commit `32ebc6d`.

## Verdict

**APPROVE**, scoped to gate items 2 and 3. The reviewer explicitly declined to approve gate item 1, which it could not run.

Verbatim verdict line as received:

> ## APPROVE (gate items 2 and 3 only; gate item 1 is PENDING and I cannot approve it)

## Gate table

| # | Gate item | Reviewer status | Orchestrator status after the human run |
| --- | --- | --- | --- |
| 1 | `sbt Test/compile` and `sbt check` exit 0 from the repository root | PENDING | **PASS**, with scope notes. See `revalidation-results.md`. |
| 2 | Module graph has no cycles and matches the `CLAUDE.md` boundaries | PASS | PASS |
| 3 | No application logic exists outside the scaffold | PASS | PASS |

Gate item 1 moved to PASS after this review was returned. The human architect ran `sbt check` and then `sbt clean check` from the repository root; both reported `[success]` with `Passed: Total 18, Failed 0, Errors 0`, exercising `scholarchain.core.ConfigSuite` and `scholarchain.core.ContractSuite`. The clean run additionally observed formatting over all seven `modules/core` sources, compilation of both source sets from scratch, and the Scala 3.3.7 output paths. Both runs post-date the review, so the reviewer saw neither, and the PASS is the orchestrator's record of the human's evidence rather than a reviewer finding. One scope limit remains, recorded in `revalidation-results.md`: `sbt Test/compile` was never issued as a standalone command, its work being subsumed by the `Test/test` stage.

## Gate 2, as observed by the reviewer

Projects, paths and edges read from `build.sbt:37-137`:

| Project | Path | Depends on |
| --- | --- | --- |
| `core` | `modules/core` | nothing (lines 102-106) |
| `hadoop` | `modules/hadoop` | `core` |
| `entityResolution` | `modules/entity-resolution` | `core` |
| `sparkAnalytics` | `modules/spark` | `core` |
| `storage` | `modules/storage` | `core` |
| `rag` | `modules/rag` | `core`, `storage` |
| `services` | `modules/services` | `core`, `storage`, `rag` |
| `root` | `.` | aggregates all seven; `core % "test->compile"` |

Acyclic: every edge points toward `core`, which is a sink. Aggregation is not a classpath edge, so the root aggregate list plus its single test-scoped `core` edge adds no cycle. Boundary conformance confirmed on each specific rule the work order named: no edge among the three job modules; `storage` depends only on `core`; no runtime module depends on a batch job module, so the batch modules stay outside the runtime dependency graph.

## Gate 3, and the Phase 03 content under `modules/core`

The reviewer states plainly that the seven Phase 03 Scala files under `modules/core/src/{main,test}/scala/scholarchain/core/` are **inside their owner's scaffold**: `docs/agents.md` grants `domain-engineer` exclusive ownership of `modules/core/**`, and the files match the contracts role `CLAUDE.md` assigns to core, with module tests kept in the module as the boundary rules require. A `Glob` for `**/*.{scala,java,sc}` returned only those seven files plus `project/DependencyVersions.scala`, which is the build ledger rather than application logic.

The reviewer did not review the content of those files for correctness and makes no claim about it. That is the Phase 03 review's scope.

## Version ledger, `check` chain, deployment and secrets

- One JSON library: `upickle` at `build.sbt:105`. A Grep for `circe|play.api.libs.json|spray.json|jackson|json4s|zio.json` returned no matches.
- No library version literal bypasses the ledger. Versions that legitimately live elsewhere, each with one source: `sbt.version` in `project/build.properties:1` (read by the launcher before any Scala loads), the Scalafmt engine pin in `.scalafmt.conf:1` (distinct from the `sbtScalafmt` plugin pin), and the base-image tag and digest in `Dockerfile:1`.
- The `check` chain is a single ordered `Def.sequential` ending in the marker write, so the reviewer found no path that records a marker without every prior stage passing. The marker write uses an atomic move and fails closed if `.claude/` is absent.
- `buildDockerLocal` is the build's only process execution, runs `docker build --network=none`, has no push, registry, Terraform or AWS reference, and is unreachable from `markTestRun`, so `sbt check` cannot trigger a container build.
- No credentials, keys or secret material in any file reviewed, and no `sys.env`, `sys.props` or secret-file read anywhere in `build.sbt`, `project/**`, `Dockerfile` or `.scalafmt.conf`.
- No authority violation found. `tests/check_test_marker.py` is attributed to `test-engineer` in `test-report.md:3-5`, not to the build owner.

## Findings

Both findings are latent. Neither fails a gate item. The reviewer named the owner and proposed no fix.

| ID | Severity | Location | Finding | Owner |
| --- | --- | --- | --- | --- |
| F1 | LOW, latent | `build.sbt:52-53` | The root `Test` source path covers only `tests/src/test/scala` and `tests/contracts`. The reserved `tests/adversarial`, `tests/integration` and `tests/end-to-end` are on no source path, so a future `.scala` test placed there would be neither compiled nor run while `sbt check` still wrote the marker. Not reachable today: those directories hold only `.gitkeep`. Weakens the gate against the `docs/agents.md` rule that independent adversarial and cross-module tests live in `tests/`. | `build-engineer` |
| F2 | LOW, latent | `build.sbt:48` | `root.dependsOn(core % "test->compile")` puts only `core` on the root test classpath, and aggregation is not a classpath edge. A test under `tests/` cannot yet reference `storage`, `rag`, `services` or the three job modules, so the cross-module testing purpose `docs/agents.md` assigns to `tests/` is not satisfiable beyond `core`. The current scoping is deliberate and correctly keeps `tests/` off the runtime graph. Any widening must keep batch job modules out of the runtime graph. | `build-engineer` |

Routing is recorded in `revalidation-repairs.md`. Neither finding was fixed inline, and neither was repaired before the human's acceptance commit.

## Pending checks

| Check | Who must run it | Result that closes it | State |
| --- | --- | --- | --- |
| `sbt check` on the current tree including `modules/core/**` | Human architect | Exit 0 with the four stages passing and the marker refreshed | **Closed.** See `revalidation-results.md`. |
| `sbt Test/compile` as its own command | Human architect | Exit 0, transcript recorded | Open. Subsumed by the `Test/test` stage, but not issued separately. |
| `scalafmtCheck` over all seven new `modules/core` sources | Human architect | A clean-state transcript showing every source checked | **Closed.** `sbt clean check` checked all 5 main and both test sources from a cold cache and compiled both sets from scratch. See `revalidation-results.md`. |
| `tests/check_test_marker.py` regression against the current tree | `test-engineer` | Exit 0 with both printed assertions observed | Open. Not dispatched. |
| Byte identity of the current build files with the Phase 02-approved digests (`review.md:13`) | Human architect | Matching SHA-256 digests | **Closed. All three match exactly.** See the digest table below. |
| Git history claim that no build-owned file changed between `32ebc6d` and HEAD | Orchestrator | Empty diff output | Closed as orchestrator evidence in `revalidation-diff.md`. The reviewer could not verify history and corroborated it instead by reading all ten files and matching every line count against the diffstat. |

## Build-file digest verification

The human architect ran `shasum -a 256 build.sbt Dockerfile tests/check_test_marker.py` from the repository root. The policy guard refuses `shasum` to the orchestrator as outside the role's permitted commands, and `code-reviewer` has no hashing tool, so this is the human's observation.

| File | Observed SHA-256 | Phase 02-approved digest (`review.md:13`) | Match |
| --- | --- | --- | --- |
| `build.sbt` | `6f0a2a28e359152b409fd5a5c94e2a977c8a792ab828de4268eb047b88aa53b4` | `6f0a2a28e359152b409fd5a5c94e2a977c8a792ab828de4268eb047b88aa53b4` | Yes |
| `Dockerfile` | `ff6ad537965a26a9cd1d3374bb85c473fa99c1e1d9d86494c17fb9fc240c5d6c` | `ff6ad537965a26a9cd1d3374bb85c473fa99c1e1d9d86494c17fb9fc240c5d6c` | Yes |
| `tests/check_test_marker.py` | `79c505c02663acb778bd9477c68b4ce490050abd8a342840ba306a760191da96` | `79c505c02663acb778bd9477c68b4ce490050abd8a342840ba306a760191da96` | Yes |

All three digests match the approved Phase 02 full-course candidate byte for byte. This is independent cryptographic confirmation of the orchestrator's Git-history claim in `revalidation-diff.md` and of the reviewer's line-by-line corroboration: the reviewed build is the approved build, by three separate methods that do not share a failure mode. The earlier `build.sbt` digest `ae59c705…` at `review.md:3` is the superseded pre-candidate revision, not a conflict.

## Reviewer observations that are not findings

Recorded as received, condensed. The reviewer separated these from its findings.

1. It independently read all ten build files and confirmed each is line-for-line identical to the added lines in `revalidation-diff.md`, with every line count matching the diffstat. It still treats the commit-range provenance as orchestrator evidence rather than its own observation.
2. Formatting evidence carries over from Phase 02 because the build files are unchanged; compile and test evidence does not, because `modules/core/**` was added afterward. This is the substantive reason gate 1 had to be re-run rather than inherited.
3. `.scalafmt.conf` sets no `maxColumn`, so the default 80 applies and `build.sbt:52-53` exceed it. Scalafmt evidently emits them in that shape, noted so a future formatter upgrade is not mistaken for a new defect.
4. `Compile / scalafmtSbtCheck` likely does not reach `project/project/build.sbt`. Cosmetic coverage gap on a two-line file. **Partially contradicted by later evidence:** the `sbt clean check` run reports three sources checked under the build definition, which suggests the file is reached. The output does not name the files, so this observation is left open rather than retracted.
5. Root `Test / javaSource` and `Test / resourceDirectory` still default to a nonexistent `src/test/*`. Harmless.
6. The marker is a zero-byte file, so the Stop hook necessarily compares modification time rather than content.
7. `(Test / test).all(allProjects)` succeeds vacuously for the six modules with no test sources, so "check runs tests" is currently meaningful only for `core` and the root `tests/` sources.
8. `--network=none` isolates the Docker build steps; the base-image pull still goes through the daemon outside that flag, which `packaging/README.md:8` states accurately.
9. `ScholarChainConfig.scala` reads configuration from the filesystem. The reviewer did not review it and makes no claim about its secret handling; that belongs to the Phase 03 review of `domain-engineer`'s work.
10. `revalidation.md:16` directs the reviewer at the accepted Phase 02 commit, while the dispatched work order directed it at the current build files. It reviewed the current files as instructed and found them consistent with the accepted commit.

## Attribution and sequence

The verdict above is `code-reviewer`'s, preserved as received. The orchestrator recorded it, produced the review diff with read-only Git, and routed the findings. The orchestrator fixed nothing and ran no gate command.

Sequence note for the acceptance record: the human architect appended an acceptance line to `revalidation.md` and committed the revalidation evidence as `8c8bbe0` before this verdict was available. The verdict is `APPROVE` on the two gate items that were statically reviewable, so the acceptance is not contradicted. The two latent findings above were open at the time of that commit and remain unrepaired.
