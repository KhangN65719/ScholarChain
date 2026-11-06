# Phase 1 sandbox-repair: second `sbt check` measurement, under Temurin 21

> **Archival note (orchestrator).** Author: `build-engineer` (subagent), dispatched 2026-11-05 as a re-run of
> the read-and-run measurement in [`sbt-check-measurement.md`](sbt-check-measurement.md). One variable
> changed: the human stated that this session runs under Temurin 21 with `JAVA_HOME` set in their shell, and
> that no `.sbtopts` pin is needed. The author's previously proposed pin is therefore **withdrawn as
> unnecessary**; the work order forbade creating it and the author confirms it made no edit.
>
> The report is preserved as received. The author was instructed not to write under `docs/agent-runs/**`,
> which is orchestrator-owned under `docs/agents.md`; it returned the report as its message and the
> orchestrator archives it here with no edit to its content.
>
> This report is the author's own evidence from a single run. It is **not ratified, not reviewed, and not a
> pass.** `sbt check` did not complete.
>
> **Orchestrator note on what this run settles and what it does not.** Settled: `JAVA_HOME` propagates from
> the human's shell into a subagent's sandboxed Bash, the correct toolchain was used, and repair-report open
> item 1 (toolchain drift) is resolved with no build-file change. Not settled: P1's acceptance criterion,
> which this run moves closer but does not meet. A **new** blocker replaced the old one — see section 6.

- Date: 2026-11-05 · Phase 1 · Author: `build-engineer` (subagent) · Working tree at `751d926` plus the uncommitted sandbox repair and Coursier addition
- JVM: **Eclipse Adoptium Java 21.0.13 (Temurin 21)** — correct, confirmed from the sbt banner
- Result: **exit 1, run did not complete.** New failure mode: `java.io.tmpdir` denial in Zinc's analysis-store write
- P1 acceptance criterion (an agent completing `sbt check` inside its own turn): **still not met**
- New blocker: `BLOCKED-ON factory-engineer` + human approval — the JVM's real `java.io.tmpdir` under `/var/folders` is not writable

---

No edits were made. Here is the measurement.

## Status: FAILED (the run did not complete) — but a different, more informative failure than last time

- **Owner:** `build-engineer`
- **Work order:** Phase 1 read-and-run measurement of `sbt check` under the uncommitted four-entry sandbox write allowlist, now with the human's Temurin 21 shell
- **Files created or modified: none.** I made no edit, including to my owned outputs. I did not create the `.sbtopts` pin; that proposal is withdrawn as instructed.
- **Commands run:** `java -version` (refused), `/usr/libexec/java_home -v 21` (refused), `printenv JAVA_HOME` (refused), `ls -la` on the Coursier cache, the Zinc bridge cache, `project/project/target/...` and `target/` (permitted), `ls -la` on the test marker (refused), `sbt check` (ran, exit 1).

### 1. JVM — the human's `JAVA_HOME` **does** propagate into my sandboxed Bash

Verbatim banner line:

```
[info] welcome to sbt 1.11.7 (Eclipse Adoptium Java 21.0.13)
```

**This is Temurin 21** — Eclipse Adoptium 21.0.13, exactly the recorded toolchain in `/Users/winnguyen/ScholarChain/docs/agent-runs/phase-02/versions.md`. The Homebrew Java 27 of my last run is gone. So the environment variable reaches agent turns, and open item 1 of the repair report (toolchain drift) is resolved without any build-file change.

Corroboration was unavailable. All three direct probes were refused by the guard, verbatim:

- `java -version` → `ScholarChain policy: This shell command is outside the role's permitted commands. Use structured file tools or route an approved check or script to its owner.`
- `/usr/libexec/java_home -v 21` → same message.
- `printenv JAVA_HOME` → same message.

The banner is again my only direct JVM evidence, but it is corroborated indirectly by item 3 below: the cached Java-21 bridge was accepted without a rebuild.

### 2. Exact result of `sbt check`

**Exit code 1.** The run died during project loading, before `build.sbt` was evaluated.

Classification: **none of the previously named modes.** This is a **new, fourth mode** — a `java.io.tmpdir` write denial in Zinc's *analysis-store persistence*, not in bridge compilation.

```
[info] loading project definition from /Users/winnguyen/ScholarChain/project/project
[info] compiling 1 Scala source to /Users/winnguyen/ScholarChain/project/project/target/scala-2.12/sbt-1.0/classes ...
[info] done compiling
[error] java.nio.file.FileSystemException: /var/folders/k6/1c1ms0x54c9d6rl_b6_rxs6h0000gn/T/inc_compile_2.12.zip2156257012756249756.tmp: Operation not permitted
```

The decisive frames:

```
[error] 	at java.base/java.nio.file.Files.createTempFile(Files.java:879)
[error] 	at sbt.internal.inc.FileAnalysisStore$BinaryFileStore.set(FileAnalysisStore.scala:92)
[error] 	at xsbti.compile.AnalysisStore$CachedAnalysisStore.set(AnalysisStore.java:126)
[error] 	at xsbti.compile.AnalysisStore$SyncedAnalysisStore.set(AnalysisStore.java:150)
[error] 	at sbt.Defaults$.$anonfun$compileTask$1(Defaults.scala:2424)
```

Final output lines:

```
[error] (Compile / compile) java.nio.file.FileSystemException: /var/folders/k6/1c1ms0x54c9d6rl_b6_rxs6h0000gn/T/inc_compile_2.12.zip2156257012756249756.tmp: Operation not permitted
[warn] Project loading failed: (r)etry, (q)uit, (l)ast, or (i)gnore? (default: r)
```

Mode-by-mode:

- **Zinc-bridge `Could not create temporary directory`: gone.** No `fetchCompiledBridge` / `compileAndInstall` frame. The Java-21 bridge matched and was reused. The JVM fix worked.
- **`sbt.boot.lock (Operation not permitted)`: did not occur.** The `~/.sbt` entry works.
- **Coursier download or cache failure: did not occur.** No resolution or download error.
- **Genuine formatting, compile or test failure in project sources: did not occur, and could not have.** The failure is in the meta-build at `/Users/winnguyen/ScholarChain/project/project`, during *project loading*. `build.sbt` never loaded, so the alias `addCommandAlias("check", "root / markTestRun")` (`build.sbt:140`) never resolved.
- **This mode: `java.io.tmpdir` denial on analysis-store write.** The compile itself *succeeded* — `[info] done compiling`, and `project/project/target/scala-2.12/sbt-1.0/classes` carries my run's mtime (Nov 5 21:51). Only persisting the incremental-compilation analysis zip failed, because Zinc writes it via `Files.createTempFile` into `java.io.tmpdir`.

**What `check` covered: nothing.** It did not run `scalafmtCheck`, `scalafmtSbtCheck`, `Compile / compile` or `Test / test` on any module. No test counts exist to report. `/Users/winnguyen/ScholarChain/target/scala-3.3.7` still carries mtime Nov 5 19:02, predating my run, confirming no project-source compile.

One incidental warning, unchanged and non-fatal: `[warn] [launcher] could not parse ftp_proxy setting: java.net.MalformedURLException: unknown protocol: socks5h`. Also two `Picked up JAVA_TOOL_OPTIONS: -Djava.net.preferIPv4Stack=true` lines — environment noise, not the cause.

**The load-bearing finding:** `java.io.tmpdir` is needed by *every* sbt compile, not just a bridge rebuild. The allowlist contains `$TMPDIR`, but the JVM resolved `java.io.tmpdir` to the real Darwin per-user directory `/var/folders/k6/.../T/`, which is not allowlisted. Observation: that exact path was denied. Labeled inference: the sandbox's `$TMPDIR` entry points at the session scratch directory, so it does not cover the path the JVM actually chose, and sbt is not inheriting a remapped `TMPDIR`. I did not attempt to set or verify `TMPDIR` — the guard permits only bare `sbt check`.

### 3. Was `~/Library/Caches/Coursier` exercised?

**Observed: no write. Inert again.** Byte-identical before and after my run — `/Users/winnguyen/Library/Caches/Coursier` at Nov 5 20:56, its `v1` child at Nov 5 20:57, `v1/https` at 20:56. All predate my run.

**Observed, and new this run:** `/Users/winnguyen/.sbt/1.0/zinc/org.scala-sbt/` is *also* unchanged (Nov 5 20:56, single Java-21 bridge jar). Last run that directory's lock was touched by the bridge-rebuild attempt; this run it was not written at all. That is independent corroboration that sbt ran under Java 21.

**Inference, labeled:** the run again died before dependency resolution for the main build, so nothing needed downloading. **Repair-report open item 2 remains unproven in both directions** — I still cannot say the Coursier entry is correct, only that it was not needed. Two runs now, both inert.

### 4. Test marker

**Not updated.** I did not write it and did not attempt to.

I could not read its mtime. `ls -la /Users/winnguyen/ScholarChain/.claude/.last-test-run` was refused, verbatim: `ScholarChain policy: Use structured file tools for constitutional paths and the factory workflow for edits.` It is also gitignored, so `git status` cannot show it.

Inference, and a firm one, from `build.sbt:77-99`: the marker write is the final step of a `Def.sequential` chain behind `scalafmtCheck`, `scalafmtSbtCheck`, `Compile / compile` and `Test / test`. `build.sbt` never loaded, so no step in that chain ran and nothing could have written the marker. Treat the marker as stale.

### 5. P1 acceptance criterion

**No — not met.** An agent still cannot complete `sbt check` inside its own turn. Evidence: exit 1, project loading failed, zero check steps executed.

Progress is real and narrow: the JVM is now correct and the Zinc-bridge mode is eliminated. The gate is blocked by one remaining missing write path.

### 6. Blocked-on

**`BLOCKED-ON factory-engineer`** — `.claude/settings.json` sandbox write allowlist. The run needs the JVM's real `java.io.tmpdir` writable. Evidence: the denied path `/var/folders/k6/1c1ms0x54c9d6rl_b6_rxs6h0000gn/T/`, frame `FileAnalysisStore$BinaryFileStore.set`. That file is `factory-engineer`'s, and the change is a sandbox widening requiring human approval and ratification. It is not mine to make, and I have no build-file remedy for it: `java.io.tmpdir` is a JVM property, and the only lever I own would be a `.sbtopts`/`.jvmopts` edit, which this order forbids and which I am not proposing.

I note the tradeoff for the human without recommending it: allowlisting the per-user Darwin temp directory is broader than the four cache entries, since it is a shared scratch area rather than a tool-specific cache. A narrower alternative is redirecting sbt's `java.io.tmpdir` into an already-allowlisted directory, which would be a build-file change and would need its own work order. I am not asking for either; the decision is the human's.

### Assumptions, risks, gates

- **Assumption:** the uncommitted four-entry allowlist was live. Supported by the absence of the boot-lock failure and by the successful in-tree write to `project/project/target/`.
- **Risk:** this measurement says nothing about module graph, formatting, dependency pins or test health. The Phase 2 record says they passed under JDK 21; I did not re-verify any of it and claim none of it.
- **Dependency pins / module graph:** unverified this run. The graph as declared in `build.sbt:102-137` is `core` standalone; `hadoop`, `entityResolution`, `sparkAnalytics` each `dependsOn(core)` only; `storage` on `core`; `rag` on `core, storage`; `services` on `core, storage, rag`. All versions route through `DependencyVersions`. Read, not validated by a compile.
- **Manual action required:** human decision on the `java.io.tmpdir` write path, then a `factory-engineer` work order.
- **Next gate:** independent review by `code-reviewer` of this evidence, then the human decision above.

This report is my own evidence from a single run. It is **not ratified, not reviewed, and not a pass.** `sbt check` did not complete.
