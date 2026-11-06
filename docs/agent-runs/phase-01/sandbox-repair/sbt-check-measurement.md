# Phase 1 sandbox-repair: `sbt check` measurement under the repaired allowlist

> **Archival note (orchestrator).** Author: `build-engineer` (subagent), dispatched 2026-11-05. The work
> order was a read-and-run measurement only: run `sbt check`, report the JVM version and the exact result,
> make no edits. The author confirms it made no edit, including to its own owned outputs, and that the
> `.sbtopts` toolchain pin is a proposal it did not apply.
>
> The report is preserved as received. The author was instructed not to write under `docs/agent-runs/**`,
> which is orchestrator-owned under `docs/agents.md`; it returned the report as its message and the
> orchestrator archives it here with no edit to its content. Shell redirection operators that were HTML-escaped
> in transit (`2&gt;&amp;1`) are restored to plain text.
>
> This report is the author's own evidence from a single run. It is **not ratified, not reviewed, and not a
> pass.** `sbt check` did not complete.

- Date: 2026-11-05 · Phase 1 · Author: `build-engineer` (subagent) · Working tree at `751d926` plus the uncommitted sandbox repair and Coursier addition
- Result: **exit 1, run did not complete.** Failure mode: Zinc compiler-bridge rebuild under the wrong JVM.
- P1 acceptance criterion (an agent completing `sbt check` inside its own turn): **still not met.**

---

No edits were made. Here is the measurement.

## Status: FAILED (the run did not complete) — a clear negative result

- **Owner:** `build-engineer`
- **Work order:** read-and-run measurement of `sbt check` under the repaired four-entry sandbox write allowlist, Phase 1
- **Files created or modified: none.** This was a read-and-run order and I made no edit, including to my own owned outputs.

### 1. JVM

`sbt` ran under **Homebrew Java 27.0.1.0.3**, verbatim from the banner:

```
[info] welcome to sbt 1.11.7 (Homebrew Java 27.0.1.0.3)
```

This is the wrong JVM. The recorded working toolchain is **Eclipse Adoptium Temurin 21.0.13, macOS arm64** (`/Users/winnguyen/ScholarChain/docs/agent-runs/phase-02/versions.md` line 9; `/Users/winnguyen/ScholarChain/docs/agent-runs/phase-02/validation.md` line 3 records "JDK 21").

I could not independently report the shell's default JVM — both probes were refused by the guard:

- `java -version` → `ScholarChain policy: This shell command is outside the role's permitted commands. Use structured file tools or route an approved check or script to its owner.`
- `/usr/libexec/java_home -V` → same message.
- `java -version 2>&1` and `/usr/libexec/java_home -V 2>&1` → `ScholarChain policy: Use explicit commands and paths. Shell expansion, pipelines, chaining and redirection require a reviewed script.`
- `find`, `grep` → the "outside the role's permitted commands" message.

So the banner is my only JVM evidence. I made no attempt to prefix `JAVA_HOME` or otherwise work around the guard.

### 2. Exact result of `sbt check`

**Exit code 1.** The failure is unambiguously the **Zinc-bridge / `Could not create temporary directory`** mode described in the repair report — not the boot-lock mode, not a Coursier failure, and not a compile or test failure in project sources.

Failing frames, verbatim:

```
[error] java.lang.RuntimeException: Could not create temporary directory.
[error] scala.sys.package$.error(package.scala:30)
[error] sbt.io.IO$.create$1(IO.scala:515)
...
[error] sbt.io.IO$.createUniqueDirectory(IO.scala:525)
[error] sbt.io.IO$.createTemporaryDirectory(IO.scala:509)
[error] sbt.io.IO$.withTemporaryDirectory(IO.scala:506)
[error] sbt.internal.inc.ZincComponentCompiler.compileAndInstall(ZincComponentCompiler.scala:259)
[error] sbt.internal.inc.ZincComponentCompiler.compiledBridgeJar(ZincComponentCompiler.scala:227)
[error] sbt.internal.inc.ZincComponentCompiler$ZincCompilerBridgeProvider.fetchCompiledBridge(ZincComponentCompiler.scala:75)
```

Final output line:

```
[error] (Compile / compileIncremental) Could not create temporary directory.
[warn] Project loading failed: (r)etry, (q)uit, (l)ast, or (i)gnore? (default: r)
```

Distinguishing the modes explicitly:

- **Boot lock (`sbt.boot.lock (Operation not permitted)`): gone.** Did not occur. The `~/.sbt` allowlist entry works; sbt launched and loaded the build.
- **Coursier download or cache failure: did not occur.** No resolution or download error appeared.
- **Genuine compile or test failure in project sources: did not occur, and could not have.** The failure is in `/Users/winnguyen/ScholarChain/project/project` — the meta-meta build — during *project loading*. The build never finished loading, so the `check` alias (`addCommandAlias("check", "root / markTestRun")`, `build.sbt:140`) never resolved and no project source was compiled, formatted-checked or tested.
- **Zinc-bridge mode: this is it.** Corroborating evidence I gathered directly: the only cached bridge in `/Users/winnguyen/.sbt/1.0/zinc/org.scala-sbt/` is `org.scala-sbt-compiler-bridge_2.12-1.11.0-bin_2.12.20__65.0-1.11.0_20250927T224111.jar`. Class-file major **65 is Java 21**. There is no Java-27 bridge, so Zinc tries to build one, which needs `java.io.tmpdir` (macOS `/var/folders`, not in the write allowlist).

One incidental, unrelated warning, reported for completeness: `[warn] [launcher] could not parse ftp_proxy setting: java.net.MalformedURLException: unknown protocol: socks5h`. Non-fatal environment noise from the sandbox proxy, not the failure cause.

### 3. Was `~/Library/Caches/Coursier` exercised?

**Observed: no write. The new entry was inert on this run.** The directory's mtimes are byte-identical before and after my run (`/Users/winnguyen/Library/Caches/Coursier` at Nov 5 20:56, its `v1` child at Nov 5 20:57 — both predate my run and match the prior agent's run).

**Observed: `~/.sbt` was written.** `/Users/winnguyen/.sbt/1.0/zinc/.sbt.cache.lock` carries mtime Nov 5 21:42, i.e. my run.

**Inference, labeled as such:** the run failed before any dependency resolution, so nothing needed downloading and everything that was reached resolved from existing caches. I therefore cannot say whether the Coursier entry is *correct*, only that it was not needed here. Open item 2 of the repair report remains unproven in both directions.

### 4. Test marker

**Not updated.** I did not write it and did not attempt to. The marker `/Users/winnguyen/ScholarChain/.claude/.last-test-run` exists and is empty, unchanged.

Evidence quality caveat: I could not read the marker's mtime, because `ls -la` on that path is refused (`Use structured file tools for constitutional paths and the factory workflow for edits`), and it is gitignored (`.gitignore:5`), so `git status --short` cannot show it either. The conclusion is therefore an inference from the build definition, but a firm one: in `build.sbt:77-99` the marker write is the final step of a `Def.sequential` chain behind `scalafmtCheck`, `scalafmtSbtCheck`, `Compile/compile` and `Test/test`, and the build never loaded, so no step in that chain ran.

### 5. Recommendation for open item 1 — proposal only, NOT applied

Pin the JVM in a new file **`/Users/winnguyen/ScholarChain/.sbtopts`** (my owned output; it does not currently exist, nor does `.jvmopts`):

```
-java-home /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
```

Rationale: `.sbtopts` is read by the sbt launcher script before the JVM is chosen, so bare `sbt check` — the only form the guard permits (`policy.py:120`, one argument) — becomes correct with no guard widening. That is the decisive advantage over the factory-owned alternative of teaching the guard to accept a `JAVA_HOME=` prefix, which widens the security surface to fix a build problem.

Tradeoffs, stated honestly:

- **It hardcodes an absolute path.** I have not verified that path exists on this machine; `java -version` and `/usr/libexec/java_home -V` are both refused to me, so I cannot resolve the real Temurin 21 home from inside my turn. Before this is applied, the human (or a permitted probe) must supply the exact `/usr/libexec/java_home -v 21` output. **Do not apply my literal path unverified.**
- **It is not portable.** A contributor or CI image without Temurin 21 at that exact path gets a launcher failure. `-java-home` takes no fallback. A CI-friendlier variant is a `JAVA_HOME` export in a reviewed wrapper script, but `scripts/**` is `infra-engineer`'s, and a wrapper would not be invocable as bare `sbt check` anyway.
- **It pins the whole build, including the human's own runs**, which is arguably the point: `versions.md` already treats Temurin 21 as the verification JVM, so the file makes a recorded convention enforceable rather than adding a new constraint.
- **`project/build.properties` is not the right place** — it pins the sbt version, not the JVM. I would not use it for this.

A cheaper non-build alternative worth putting to the human: add `/var/folders` (or the Darwin per-user temp dir) to the sandbox write allowlist. That would let Zinc rebuild the bridge under any JVM. It is `factory-engineer`'s file, it widens the sandbox considerably, and it treats the symptom rather than the toolchain drift, so I do not recommend it over the pin.

### Assumptions, risks, gates

- **Assumption:** the uncommitted four-entry allowlist was live during my run. Supported by the boot-lock failure being gone and by `~/.sbt/1.0/zinc/.sbt.cache.lock` being written.
- **Risk:** because the run died at project loading, this measurement says nothing about whether the module graph, formatting, dependency pins or tests are healthy. The Phase 2 record says they passed under JDK 21; I did not re-verify any of it and do not claim it.
- **Manual action required:** supply the verified Temurin 21 `JAVA_HOME` path, then route the `.sbtopts` pin to me as a work order.
- **`BLOCKED-ON human`** — the toolchain pin is the remedy for repair-report open item 1, which is `BLOCKED-ON build-engineer`, but this order explicitly places it out of scope. I need an authorizing work order plus the verified path before I write anything.
- **Bottom line on the P1 acceptance criterion:** still **not met**. An agent cannot yet complete `sbt check` inside its own turn. The specific boot-lock failure named in the original finding is fixed; the gate is not.
- **Next gate:** independent review by `code-reviewer` of this evidence, then a human decision on the `.sbtopts` pin and on repair-report open item 2.

This report is my own evidence from a single run. It is **not ratified, not reviewed, and not a pass.**
