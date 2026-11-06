# Phase 1 sandbox-repair: `java.io.tmpdir` redirect experiment

> **Archival note (orchestrator).** Author: `build-engineer` (subagent), dispatched 2026-11-05 in parallel with
> `factory-engineer`'s F1-F5 repairs, on disjoint files. The human approved exactly the remedy
> `code-reviewer` identified in [`review-verdict.md`](review-verdict.md) as narrower than either option the
> two agents had named: redirect sbt's `java.io.tmpdir` into a project-local directory under `target/`, which
> already sits inside the implicit project write allowance, adding **zero** sandbox surface.
>
> The report is preserved as received. The author was instructed not to write under `docs/agent-runs/**`,
> which is orchestrator-owned; it returned the report as its message and the orchestrator archives it here
> with no edit to its content.
>
> This report is the author's own evidence from a single sitting of four runs. It is **not ratified, not
> reviewed, and not a pass.** `sbt check` did not complete.
>
> **Orchestrator summary of what this run settles.** The approved redirect **works** — observed, not
> inferred. The `java.io.tmpdir` blocker is eliminated by a build-owned change with no sandbox widening, and
> the author withdraws its earlier `BLOCKED-ON factory-engineer` ask to make `/var/folders` writable. The
> reviewer's preferred outcome holds on that axis. `build.sbt` loaded for the first time in this sequence.
> A **new, pre-existing-but-previously-masked** blocker then surfaced: local Unix-domain socket `bind()` is
> refused in this sandbox. It was unreachable in all three earlier runs because the ipcsocket native library
> could never be extracted while `java.io.tmpdir` was unwritable. Two further matters need a human decision:
> the committed absolute path is machine-specific, and nothing recreates `target/sbt-tmp` after a `clean`.

- Date: 2026-11-05 · Phase 1 · Author: `build-engineer` (subagent) · Four `sbt check` runs
- Created: `.jvmopts` (tracked-eligible, currently untracked), `target/sbt-tmp/.keep` (inside gitignored `target/`)
- JVM: Eclipse Adoptium Java 21.0.13, unchanged by the experiment
- Result: **exit 2, run did not complete.** New mode: `UnixDomainServerSocket.bind` → EPERM
- P1 acceptance criterion: **still not met**
- Old ask **withdrawn**; new narrower ask: `BLOCKED-ON factory-engineer` + human approval for local socket binding

---

Done. Final state of the mechanism is in place; here is the report.

## Status: FAILED (the run did not complete) — but the approved redirect **works** and a new, different blocker is exposed

- **Owner:** `build-engineer` · **Work order:** Phase 1, redirect sbt's `java.io.tmpdir` into a project-local directory under `target/` via the narrowest build-owned option, then run `sbt check`
- **Files created:** `/Users/winnguyen/ScholarChain/.jvmopts` (new), `/Users/winnguyen/ScholarChain/target/sbt-tmp/.keep` (new, untracked — `target/` is gitignored at `.gitignore:1`)
- **Files modified:** none. I did not touch `.claude/**`, `CLAUDE.md`, `docs/agents.md`, `docs/agent-runs/**`, `build.sbt`, `.sbtopts` (which does not exist), or `.gitignore`.
- **Commands run:** `ls -la` on several paths (permitted), `mkdir -p` (refused), `printenv TMPDIR` (refused), `which -a sbt` (refused), `grep` (refused), `git -C … status --short` (refused), `sbt check` ×4.

### 1. The mechanism

File: `/Users/winnguyen/ScholarChain/.jvmopts`, final contents exactly one line:

```
-Djava.io.tmpdir=/Users/winnguyen/ScholarChain/target/sbt-tmp
```

**Why `.jvmopts` over `.sbtopts`:** `.jvmopts` carries only JVM options for the sbt launcher's own JVM and nothing else, so its effect is strictly "this JVM's system properties" — it cannot alter sbt options, commands or the toolchain, which keeps it unambiguously distinct from the **withdrawn** `.sbtopts` `-java-home` toolchain pin (that proposal stays withdrawn; I did not create `.sbtopts`). `.sbtopts` would reach the same result via `-J-Djava.io.tmpdir=…` but is a general launcher-argument file, so it is wider in what it *could* do and would blur the two changes. It affects **only the sbt JVM**: forked test JVMs receive `javaOptions`, which `build.sbt` never sets, and this build sets `fork` nowhere, so tests run in the sbt JVM and inherit the property.

**Absolute is required for correctness — observed, not inferred.** I tried relative `target/sbt-tmp` first. The analysis-store write succeeded and `build.sbt` loaded, but native-library loading died:

```
Couldn't load native library java.lang.UnsatisfiedLinkError: Expecting an absolute path of the library: target/sbt-tmp/.sbt6e0833ea/swoval/swoval-jni/3556887111055803783-libswoval-files0.dylib
```

`System.load` requires an absolute path, and swoval/ipcsocket build their extraction paths from `java.io.tmpdir`. I also confirmed from the launcher source that a portable relative-to-absolute trick is impossible: `/opt/homebrew/Cellar/sbt/2.0.12/libexec/bin/sbt:811-816` (`loadConfigFile`) emits each line with `printf '%s\n' "$line"` specifically to prevent shell expansion, and line 953 assigns it inside double quotes, so `$PWD` in `.jvmopts` would be passed literally. The same function reads `.sbtopts` (line 945), so `.sbtopts` has the same limitation.

**Portability defect — needs a human decision, flagged not decided.** The absolute path hardcodes `/Users/winnguyen/ScholarChain`, so this committed file breaks any other checkout or CI image. I deliberately did **not** add `.jvmopts` to `.gitignore`, so the defect stays visible in the review diff. The two candidate remedies are: (a) gitignore `.jvmopts` as a per-machine local file, accepting that the redirect is not reproducible from the repo; (b) keep it tracked and accept single-machine scope for Phase 1. Both are mine to execute once chosen.

### 2. The directory

**It did not exist; I created it.** `mkdir -p` was guard-refused (`ScholarChain policy: This shell command is outside the role's permitted commands…`), so I created it with the Write tool by writing `/Users/winnguyen/ScholarChain/target/sbt-tmp/.keep`, which creates parent directories. Verified by `ls -la` before the first run. The reviewer's caveat is real and is now also a standing fragility: nothing recreates this directory, so a `clean` or a fresh clone that removes it will reintroduce `createTempFile` failure. A build-owned auto-create is not possible at this level, because the directory is needed before `build.sbt` loads.

### 3. JVM — unchanged and correct

Verbatim banner, identical in every run:

```
[info] welcome to sbt 1.11.7 (Eclipse Adoptium Java 21.0.13)
```

Eclipse Adoptium 21.0.13. My change did not disturb the toolchain.

### 4. Did `java.io.tmpdir` actually move? **Yes — observed directly.**

After the first run, `ls -la /Users/winnguyen/ScholarChain/target/sbt-tmp` contained `.sbt/sbt-socket-1484041832931296528/`, `.sbt-50798868/ipcsocket/` and `.sbt6e0833ea/swoval/swoval-jni/` — all sbt/swoval/ipcsocket temp trees, created at my run's timestamp. The error message also names the redirected path. No `/var/folders` path appears anywhere in any of the four runs.

**The prior blocker is gone — observed.** No `FileSystemException … inc_compile_2.12.zip…tmp`, no `FileAnalysisStore$BinaryFileStore.set` frame. Positive corroboration: `project/target/config-classes` carries mtime 22:31 (my run), so the meta-build's incremental analysis **persisted successfully**. The approved experiment did exactly what it was supposed to do.

### 5. Exact result of `sbt check`

**Exit code 2** for the shipped (absolute) configuration. Verbatim failing frames:

```
sbt thinks that server is already booting because of this exception:
sbt.internal.ServerAlreadyBootingException: java.io.IOException: org.scalasbt.ipcsocket.NativeErrorException: Error 1: Operation not permitted
	at sbt.internal.BootServerSocket.newSocket(BootServerSocket.java:357)
	at sbt.xMain$.getSocketOrExit(Main.scala:153)
Caused by: org.scalasbt.ipcsocket.NativeErrorException: Error 1: Operation not permitted
	at org.scalasbt.ipcsocket.JNIUnixDomainSocketLibraryProvider.bind(JNIUnixDomainSocketLibraryProvider.java:16)
	at org.scalasbt.ipcsocket.UnixDomainServerSocket.bind(UnixDomainServerSocket.java:136)
```

**Classification: a different sandbox denial — local Unix-domain socket `bind()` is not permitted.** Explicitly not the other modes: not a `java.io.tmpdir` denial (no `/var/folders`, no temp-file frame, and the redirect demonstrably took effect), not Coursier, not a formatting/compile/test failure in project sources, not the boot lock, not the Zinc bridge.

I ruled out a stale socket: `ls -laR` showed every socket directory **empty**, so `bind()` itself was refused, not blocked by an existing file. The message "server is already booting" is sbt misreporting EPERM as contention; `getSocketOrExit` then exits 2 because stdin is not a console.

**This denial is pre-existing and was previously masked, not caused by my change.** In all three earlier runs `java.io.tmpdir` was unwritable, so the ipcsocket JNI library could never be extracted; `bind()` was never reached and sbt degraded past it. My run is the first in which the native library loaded, which is why the restriction surfaced now. This matches the environment note that local binding is off in this sandbox.

**What `check` covered: nothing.** `scalafmtCheck`, `scalafmtSbtCheck`, `Compile / compile` and `Test / test` ran on zero modules. No test counts exist. On the reviewer's specific point about the main build: `build.sbt` **did load** in the relative-path run — `[info] loading settings for project root from build.sbt...` and `[info] set current project to scholarchain (in build file:/Users/winnguyen/ScholarChain/)`, which is further than any previous run reached — but project loading then failed inside `BuiltinCommands.doLoadProject`, so the `check` alias never resolved and no task ran. `target/scala-3.3.7` still carries mtime Nov 5 19:02, predating every run of mine: **no project source was compiled.** In the shipped absolute configuration `build.sbt` is not reached at all.

**Test marker: not updated.** I did not write it and did not attempt to. Firm inference from `build.sbt:77-99`: the marker write is the last step of a `Def.sequential` chain behind the four check steps, none of which ran.

Two bounded negative experiments, reported for completeness and then reverted out of the file: `-Dsbt.io.virtual=false`, `-Dsbt.server.autostart=false` and `-Dsbt.boot.server=false` all leave `BootServerSocket` creation and the swoval watcher unchanged (byte-identical failures). I found **no build-owned switch** that avoids either. Non-fatal noise, unchanged: the `socks5h` `ftp_proxy` warning and two `Picked up JAVA_TOOL_OPTIONS` lines.

### 6. Was `~/Library/Caches/Coursier` exercised? **No — observed inert for a third time.**

`/Users/winnguyen/Library/Caches/Coursier` Nov 5 20:56, `v1` Nov 5 20:57, `v1/https` Nov 5 20:56 — all predate every run of mine. Labeled inference: every run died before the main build resolved dependencies, and the meta-build resolved from existing caches, so nothing needed downloading. **Repair-report open item 2 remains unproven in both directions.** I cannot settle it.

### 7. `TMPDIR` inside the sandbox — **still open**

`printenv TMPDIR` was refused, verbatim: `ScholarChain policy: This shell command is outside the role's permitted commands. Use structured file tools or route an approved check or script to its owner.` `which -a sbt`, `grep` and `git -C … status --short` drew the same refusal; `mkdir -p` likewise. I made no attempt to work around the guard. The reviewer's question — whether the sandbox's `$TMPDIR` allowlist entry expands as expected — is **unresolved**, and my redirect makes it moot for sbt rather than answering it.

### 8. P1 acceptance criterion: **No, not met**

An agent still cannot complete `sbt check` in its own turn. Evidence: exit 2, project loading not reached, zero check steps, no test counts, marker unwritten. Progress is real and narrow: the `java.io.tmpdir` blocker is eliminated by a build-owned change with zero sandbox surface, and `build.sbt` loaded for the first time.

### 9. Is a sandbox change still needed? **Yes — but a much smaller one than I previously asked for**

- **Withdraw the old ask.** My earlier `BLOCKED-ON factory-engineer` for making the JVM's real `java.io.tmpdir` (`/var/folders/k6/…/T`) writable is **unnecessary and should be withdrawn.** The redirect replaces it, adds zero sandbox surface, and `/var/folders` stays denied. The reviewer's preferred outcome holds on that axis.
- **`BLOCKED-ON factory-engineer` + human approval — new, narrower ask.** Minimum remaining: permit local socket binding for the sandbox (`sandbox.network.allowLocalBinding: true` in the settings this session reads). Evidence: `UnixDomainServerSocket.bind` → EPERM, at `BootServerSocket.java:351/357`. It adds **no filesystem** surface at all, which is materially narrower than any writable-path widening. Per the environment note it applies without a restart. The alternative, excluding `sbt` from the sandbox, is broader and I do not recommend it. The file is `factory-engineer`'s and the decision is the human's; I am not asking for it unilaterally.

### Assumptions, risks, gates

- **Assumption:** the uncommitted four-entry allowlist was live. Supported by in-tree writes to `project/target/config-classes` and `target/sbt-tmp`.
- **Risk:** nothing recreates `target/sbt-tmp`; a clean removes it and the old failure returns. Needs a follow-up decision.
- **Risk:** the committed absolute path is machine-specific (see §1).
- **Unverified:** module graph, dependency pins, formatting and test health. The Phase 2 record says they passed under JDK 21; I re-verified none of it and claim none of it.
- **Stop test gate:** it had not fired at the time of writing. Per the reviewer's reading of `policy.py:241-246`, `source_path()` covers `.scala`/`.java`/`.sbt`, `.scalafmt.conf` and `project/build.properties`, so `.jvmopts` is not a tracked source type and should not trip it. If it does fire, treat `.claude/.last-test-run` as stale — it is stale regardless.
- **Next gate:** independent re-review by `code-reviewer`, then the human decision on local socket binding and on the `.jvmopts` portability question.

This report is my own evidence from a single sitting of four runs. It is **not ratified, not reviewed, and not a pass.** `sbt check` did not complete.
