# Sandbox-repair review diff (orchestrator-produced)

Produced by the orchestrator on 2026-11-05 for `code-reviewer`, which has no Bash tool and cannot run
git itself. Generated with the read-only forms the P3 change newly permits — `git diff --stat` and
`git diff -- <pathspec>` — against the uncommitted working tree at base revision `751d926` on `main`.
Nothing is committed.

The diff below is the verbatim output of those commands, reassembled per file. It is evidence, not a
verdict.

## Diffstat

```
 .claude/hooks/policy.py       |  68 ++++++++++++++++--
 .claude/hooks/probe_policy.py | 163 ++++++++++++++++++++++++++++++++++++++++++
 .claude/settings.json         |   6 ++
 CLAUDE.md                     |   6 ++
 docs/agents.md                |   2 +-
 5 files changed, 238 insertions(+), 7 deletions(-)
```

Untracked, not part of the diff: `docs/agent-runs/phase-01/sandbox-repair/` (`work-order.md`,
`repair-report.md`, `coursier-addition-report.md`, this file) — orchestrator-owned evidence.

## `.claude/settings.json`

```diff
diff --git a/.claude/settings.json b/.claude/settings.json
index 00520e2..f003248 100644
--- a/.claude/settings.json
+++ b/.claude/settings.json
@@ -98,6 +98,12 @@
         "~/.git-credentials",
         "~/.npmrc"
       ],
+      "allowWrite": [
+        "~/.sbt",
+        "~/.cache/coursier",
+        "~/Library/Caches/Coursier",
+        "~/.ivy2"
+      ],
       "denyWrite": [
         ".claude/settings.json",
         ".claude/hooks",
```

## `.claude/hooks/policy.py`

```diff
diff --git a/.claude/hooks/policy.py b/.claude/hooks/policy.py
index aa67883..d5c51f4 100644
--- a/.claude/hooks/policy.py
+++ b/.claude/hooks/policy.py
@@ -53,6 +53,9 @@ def checkout_path(value):
 
 
 READ_GIT = {"status", "diff", "log", "show", "ls-files", "rev-parse", "branch"}
+# Exactly the read-only review forms that may name a constitutional path. Longest prefix first so
+# `git diff --stat` is matched as its own form rather than as `git diff` with a rejected option.
+REVIEW_GIT = (("diff", "--stat"), ("log", "--oneline"), ("show", "--stat"), ("diff",))
 CHECK_SBT = {"check", "Test/compile"}
 SCRIPT_ROOTS = {
     "dataset-engineer": ("datasets/scripts/",),
@@ -60,6 +63,23 @@ SCRIPT_ROOTS = {
 }
 
 
+def review_git(tokens):
+    """Match exactly `git diff`, `git diff --stat`, `git log --oneline` and `git show --stat`,
+    with revision ranges and pathspecs. Every remaining argument must be a revision, a pathspec or
+    the `--` separator, so `--output`, `--ext-diff`, `--textconv`, `--exec-path` and `--no-index`
+    stay refused and no other option can ride along. Callers apply this only after the sensitive-path
+    and destructive-operation scans, which therefore keep their effect on these forms."""
+    if not tokens or tokens[0] != "git":
+        return False
+    arguments = tokens[1:]
+    for form in REVIEW_GIT:
+        if tuple(arguments[: len(form)]) != form:
+            continue
+        if all(item == "--" or not item.startswith("-") for item in arguments[len(form):]):
+            return True
+    return False
+
+
 def guard_shell(command, cwd, agent):
     # Normalize quoted words for direct policy probes without executing input.
     if re.search(r"[$`*?\[\]{}\n\r<>;|&]", command):
@@ -87,6 +107,11 @@ def guard_shell(command, cwd, agent):
             or not re.fullmatch(r"(?:describe|list)-[a-z-]+|get-caller-identity|ls", tokens[2])
         ):
             return deny("Direct AWS shell calls are limited to metadata inspection. Route mutations through the approved human workflow.")
+    # Read-only review of constitutional files. Reviews need to read the diff of the very files the
+    # token scan below protects, so this allowance precedes it. It grants read only: no write, no
+    # checkout, no branch change. The scans above still apply, so a diff cannot name a secret path.
+    if review_git(tokens):
+        return 0
     if any(
         CONSTITUTION.search(value) or CONSTITUTION.search(checkout_path(resolved_path(value, cwd)) or "")
         for value in tokens
@@ -228,22 +253,53 @@ def changed_at(path):
     return target.stat().st_mtime_ns
 
 
-def verify_tests():
+def verify_tests(payload=None):
     source = [path for path in changed_paths() if source_path(path)]
     if not source:
         return 0
     if TEST_MARKER.is_symlink() or not TEST_MARKER.is_file():
-        return deny("Changed sources require a fresh passing sbt check. The test marker is missing.")
-    if TEST_MARKER.stat().st_mtime_ns <= max(changed_at(path) for path in source):
-        return deny("Changed sources are newer than the test marker. Run sbt check after the final edit.")
-    return 0
+        message = "Changed sources require a fresh passing sbt check. The test marker is missing."
+    elif TEST_MARKER.stat().st_mtime_ns <= max(changed_at(path) for path in source):
+        message = "Changed sources are newer than the test marker. Run sbt check after the final edit."
+    else:
+        return 0
+    # Trade-off, loop termination against gate strength. Blocking again after Claude Code has already
+    # re-run this hook for the same stop produces an unsatisfiable loop, which Claude Code eventually
+    # overrides anyway, so the stop is allowed on the second pass. The gate is NOT satisfied: report it
+    # as a non-blocking error (exit 1, stderr shown to the operator) instead of returning a silent 0,
+    # so a stale marker is visible and attributable rather than inferred to be success. No state is
+    # written: the test marker stays build-generated and guard() keeps refusing agent writes to it.
+    if (payload or {}).get("stop_hook_active"):
+        print(
+            "ScholarChain policy: UNVERIFIED STOP. The test gate was not satisfied, and this hook "
+            f"already ran for this stop, so it is not blocking again. {message} Operator action: treat "
+            ".claude/.last-test-run as stale, run sbt check over the changed sources listed below, and "
+            "record the result before this work is reviewed, committed or ratified. Changed sources: "
+            f"{', '.join(sorted(path.as_posix() for path in source))}.",
+            file=sys.stderr,
+        )
+        return 1
+    return deny(message)
+
+
+def stop_payload():
+    """Stop and SubagentStop deliver a JSON payload carrying stop_hook_active. An absent payload keeps
+    the gate fully enforced, which is the closed direction; non-empty malformed input raises so that
+    main() fails closed. isatty keeps a direct terminal invocation from blocking on stdin."""
+    raw = "" if sys.stdin.isatty() else sys.stdin.read()
+    if not raw.strip():
+        return {}
+    payload = json.loads(raw)
+    if not isinstance(payload, dict):
+        raise ValueError("A stop payload must be a JSON object.")
+    return payload
 
 
 def main():
     try:
         action = sys.argv[1]
         if action == "tests":
-            return verify_tests()
+            return verify_tests(stop_payload())
         payload = json.load(sys.stdin)
         return {"guard": guard, "format": format_scala}[action](payload)
     except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.CalledProcessError):
```

## `.claude/hooks/probe_policy.py`

```diff
diff --git a/.claude/hooks/probe_policy.py b/.claude/hooks/probe_policy.py
index 889b728..7f0cd01 100644
--- a/.claude/hooks/probe_policy.py
+++ b/.claude/hooks/probe_policy.py
@@ -28,6 +28,142 @@ def guard(name, tool, expected=2, agent=None, **data):
     probe(name, "guard-dangerous.sh", expected, tool_name=tool, agent_type=agent, tool_input=data)
 
 
+def raw_probe(name, script, expected, payload, *, root=ROOT, env=None):
+    """Send bytes that are deliberately not a JSON object, to check the fail-closed path."""
+    result = subprocess.run(
+        [root / ".claude/hooks" / script], cwd=root, input=payload,
+        capture_output=True, text=True, env=env,
+    )
+    if result.returncode != expected:
+        raise SystemExit(f"FAIL: {name}: expected {expected}, got {result.returncode}\n{result.stderr}")
+    print(f"PASS: {name} (exit {expected})")
+
+
+def expect(name, condition):
+    if not condition:
+        raise SystemExit(f"FAIL: {name}")
+    print(f"PASS: {name}")
+
+
+def test_settings():
+    """P1. The sandbox write allowlist admits exactly sbt's four authorized cache directories and
+    nothing else, and no unsandboxing switch or deny list moved. Asserts file content only: the
+    effective OS boundary must be observed live in Claude Code, not inferred from this probe."""
+    settings = json.loads((ROOT / ".claude/settings.json").read_text())
+    sandbox = settings["sandbox"]
+    filesystem = sandbox["filesystem"]
+    expect(
+        "P1 sandbox allowWrite lists exactly the four authorized sbt cache directories",
+        filesystem.get("allowWrite") == [
+            "~/.sbt", "~/.cache/coursier", "~/Library/Caches/Coursier", "~/.ivy2",
+        ],
+    )
+    expect(
+        "P1 no unsandboxed escape was opened",
+        sandbox["enabled"] is True
+        and sandbox["failIfUnavailable"] is True
+        and sandbox["autoAllowBashIfSandboxed"] is False
+        and sandbox["allowUnsandboxedCommands"] is False,
+    )
+    expect(
+        "P1 sandbox denyWrite still protects every constitutional path",
+        filesystem["denyWrite"] == [
+            ".claude/settings.json", ".claude/hooks", ".claude/agents", ".claude/commands",
+            ".mcp.json", "CLAUDE.md", "docs/agents.md",
+        ],
+    )
+    secrets = [
+        ".env*", ".aws/**", ".ssh/**", ".gnupg/**", ".kube/**", "secret/**", "secrets/**",
+        "credential/**", "credentials/**", ".netrc", ".git-credentials", ".npmrc",
+        "*credentials*", "*.pem", "*.key", "*.p12", "*.pfx", "*.tfstate*",
+    ]
+    home = [".aws/**", ".ssh/**", ".gnupg/**", ".kube/**", ".netrc", ".git-credentials", ".npmrc"]
+    expect(
+        "P1 sandbox denyRead is exactly the unchanged secret and credential deny list",
+        filesystem["denyRead"] == [f"**/{item}" for item in secrets] + [f"~/{item}" for item in home],
+    )
+    expect(
+        "P1 no sandbox network setting was added",
+        "network" not in sandbox and set(sandbox) == {
+            "enabled", "failIfUnavailable", "autoAllowBashIfSandboxed",
+            "allowUnsandboxedCommands", "filesystem",
+        },
+    )
+    permissions = settings["permissions"]
+    expect(
+        "P1 permission deny rules are exactly the unchanged read and destructive-command list",
+        permissions["deny"] == [f"Read(/**/{item})" for item in secrets]
+        + [f"Read(~/{item})" for item in home]
+        + [
+            "Bash(terraform apply *)", "Bash(terraform destroy *)", "Bash(tofu apply *)",
+            "Bash(tofu destroy *)", "Bash(git push *--force*)", "Bash(git push * -f *)",
+            "Bash(git push -f *)", "Bash(git push --mirror *)", "Bash(aws s3 rm *)",
+            "Bash(aws s3 rb *)", "Bash(aws s3api delete-* *)", "Bash(aws dynamodb delete-* *)",
+            "Bash(aws neptune delete-* *)", "Bash(aws neptunedata delete-* *)",
+            "Bash(aws neptunedata cancel-* *)", "Bash(aws s3vectors delete-* *)",
+            "Bash(aws ecr delete-* *)", "Bash(aws ecr batch-delete-* *)",
+            "Bash(aws kms schedule-key-deletion *)", "Bash(aws kms disable-* *)",
+            "Bash(aws emr terminate-* *)", "Bash(aws emr modify-* *)",
+            "Bash(aws emr add-instance-* *)", "Bash(aws emr put-auto-scaling-* *)",
+            "PowerShell", "Monitor",
+        ],
+    )
+    expect(
+        "P1 permission modes and the allow list are unchanged",
+        permissions["defaultMode"] == "default"
+        and permissions["disableBypassPermissionsMode"] == "disable"
+        and settings["enableAllProjectMcpServers"] is False
+        and permissions["allow"] == [
+            "Read", "Grep", "Glob", "Bash(git status --short)", "Bash(git diff --stat)",
+            "Bash(.claude/hooks/validate-agent-files.sh)",
+        ],
+    )
+    # The guard already permits `sbt check`, so P1 needs no guard change. Both directions:
+    guard("P1 sbt check permitted for an implementing role", "Bash", 0, "domain-engineer", command="sbt check")
+    guard("P1 sbt check still refused to deploy-engineer", "Bash", 2, "deploy-engineer", command="sbt check")
+    guard("P1 sbt is still limited to the check tasks", "Bash", 2, "domain-engineer", command="sbt publish")
+
+
+def test_review_git():
+    """P3. Exactly `git diff`, `git diff --stat`, `git log --oneline` and `git show --stat` are read
+    even when a pathspec names a constitutional file. Everything adjacent still refuses."""
+    for command in (
+        "git diff", "git diff --stat", "git log --oneline", "git show --stat",
+        "git diff HEAD~1..HEAD", "git diff --stat HEAD~1 HEAD", "git log --oneline -3",
+        "git show --stat HEAD",
+        "git diff -- .claude/hooks/policy.py",
+        "git diff --stat HEAD~1 -- .claude/settings.json",
+        "git diff --stat HEAD~1 -- docs/agents.md",
+        "git log --oneline HEAD~3..HEAD -- CLAUDE.md",
+        "git show --stat HEAD -- .mcp.json",
+        "git diff HEAD~1 -- .claude/agents/factory-engineer.md .claude/commands",
+    ):
+        guard(f"P3 allowed: {command}", "Bash", 0, "code-reviewer-orchestrator", command=command)
+    for command in (
+        # The option refusals survive on the newly allowed forms.
+        "git diff --output=/tmp/leak", "git diff --stat --output=/tmp/leak",
+        "git diff --ext-diff -- CLAUDE.md", "git diff --textconv -- CLAUDE.md",
+        "git diff --exec-path=/tmp -- CLAUDE.md", "git diff --no-index CLAUDE.md docs/agents.md",
+        "git log --oneline --textconv -- CLAUDE.md",
+        # Narrowness: a near-miss form naming a constitutional path is still refused.
+        "git log -- CLAUDE.md", "git show -- CLAUDE.md", "git diff --cached -- CLAUDE.md",
+        "git status --short .claude/settings.json", "git ls-files .claude/hooks",
+        "git rev-parse --show-toplevel .claude", "git branch --contains .claude",
+        # No write, checkout, branch change or patch application.
+        "git checkout HEAD -- CLAUDE.md", "git restore --source HEAD CLAUDE.md",
+        "git add CLAUDE.md", "git apply .claude/patch.diff", "git rm docs/agents.md",
+        "git stash push -- CLAUDE.md", "git commit -am wip",
+        # Other verbs naming a constitutional path remain refused.
+        "ls -la .claude/hooks", "cat .claude/settings.json", "rg allowWrite .claude/settings.json",
+        # The sensitive-path scan still precedes the new allowance.
+        "git diff -- .env", "git diff --stat HEAD -- project/secrets/key",
+        "git log --oneline -- credentials.json", "git show --stat HEAD -- infra/dev.tfstate",
+    ):
+        guard(f"P3 still refused: {command}", "Bash", 2, "code-reviewer-orchestrator", command=command)
+    # A reviewer has no shell authority at all; the new allowance does not reach it.
+    guard("P3 reviewer still has no shell authority", "Bash", 2, "code-reviewer", command="git diff -- CLAUDE.md")
+
+
 def test_commands():
     for command in (
         "echo 'terraform destroy'", "terraform -chdir=infra/terraform apply", "tofu destroy",
@@ -88,9 +224,34 @@ def test_lifecycle():
         marker.touch()
         os.utime(marker, (1, 1))
         probe("stale marker blocks Scala stop", "verify-tests-ran.sh", 2, root=root, env=env)
+        # P2. First stop still blocks, so the gate keeps its strength. Only a repeat stop, which
+        # Claude Code signals with stop_hook_active, terminates the loop, and it terminates with a
+        # non-blocking error (exit 1) that names the unverified sources instead of a silent pass.
+        probe("P2 stale marker still blocks on the first stop", "verify-tests-ran.sh", 2,
+              root=root, env=env, stop_hook_active=False)
+        probe("P2 repeat stop ends the loop as an unverified stop, not a pass",
+              "verify-tests-ran.sh", 1, root=root, env=env, stop_hook_active=True)
+        expect("P2 the non-blocking path writes no state and leaves the marker untouched",
+               marker.stat().st_mtime_ns == 1_000_000_000
+               and sorted(item.name for item in (root / ".claude").iterdir()) == [".last-test-run", "hooks"])
+        marker.unlink()
+        probe("P2 missing marker still blocks on the first stop", "verify-tests-ran.sh", 2,
+              root=root, env=env)
+        probe("P2 missing marker on a repeat stop reports instead of blocking",
+              "verify-tests-ran.sh", 1, root=root, env=env, stop_hook_active=True)
+        marker.touch()
+        os.utime(marker, (1, 1))
+        raw_probe("P2 malformed stop payload fails closed", "verify-tests-ran.sh", 2,
+                  "{not json", root=root, env=env)
+        raw_probe("P2 non-object stop payload fails closed", "verify-tests-ran.sh", 2,
+                  "[]", root=root, env=env)
+        raw_probe("P2 absent stop payload keeps the gate enforced", "verify-tests-ran.sh", 2,
+                  "", root=root, env=env)
         timestamp = source.stat().st_mtime_ns + 1_000_000_000
         os.utime(marker, ns=(timestamp, timestamp))
         probe("fresh marker permits Scala stop", "verify-tests-ran.sh", 0, root=root, env=env)
+        probe("P2 a satisfied gate reports nothing on a repeat stop", "verify-tests-ran.sh", 0,
+              root=root, env=env, stop_hook_active=True)
         probe("formatter detects Scala before Phase 2 build", "format-scala.sh", 2, root=root,
               tool_name="Edit", tool_input={"file_path": "Probe.scala"})
         # Substitute an inert fixture for sbt to verify invocation without building any application code.
@@ -118,4 +279,6 @@ def test_lifecycle():
 
 if __name__ == "__main__":
     test_commands()
+    test_settings()
+    test_review_git()
     test_lifecycle()
```

## `CLAUDE.md`

```diff
diff --git a/CLAUDE.md b/CLAUDE.md
index 314186d..cb11a13 100644
--- a/CLAUDE.md
+++ b/CLAUDE.md
@@ -42,6 +42,12 @@ Use Bash, Git and Python 3 to run the policy probes. The checked-in settings con
 
 The Phase 1 guard keeps ordinary live deployment, rollback, pipeline and load script execution closed. Before opening those paths, ratify a launcher that verifies the approval record, script digest, exact arguments, target environment and bounds. Until then, approved live operations belong to the human-controlled execution path.
 
+The sandbox write allowlist admits sbt's cache directories (`~/.sbt`, `~/.cache/coursier`, `~/Library/Caches/Coursier`, `~/.ivy2`) so that an agent can satisfy the test gate itself. It adds nothing else: unsandboxed execution stays off, and the read and write deny lists are unchanged. On macOS the Coursier cache sits at `~/Library/Caches/Coursier`, which is now in the allowlist, so a dependency fetch can write its cache from an agent turn; `~/.cache/coursier` stays listed to cover non-macOS runtimes. Agent `sbt` runs must use the recorded JVM, because a different JVM forces a compiler-bridge rebuild that needs the JVM temporary directory, which the sandbox denies.
+
+The test gate blocks the first Stop or SubagentStop when changed sources are newer than the marker. On a repeat stop, which Claude Code signals with `stop_hook_active`, it stops blocking so the turn cannot loop, and reports an unverified stop as a non-blocking error naming the unverified sources. That is a deliberate trade of gate strength for loop termination, and it is not a pass. When it fires, treat `.claude/.last-test-run` as stale, run `sbt check` over the named sources and record the result before review, commit or ratification. The non-blocking path writes no state. An absent stop payload keeps the gate fully enforced and a malformed one fails closed.
+
+Reviews may read constitutional files through exactly `git diff`, `git diff --stat`, `git log --oneline` and `git show --stat`, with ranges and pathspecs. These grant read only. Every other command naming a constitutional path, every other option on these forms, and every write, checkout or patch verb stay refused, and the sensitive-path scan still precedes the allowance.
+
 Keep a clean formatting baseline and serialize source edits while the formatter runs. The formatter runs project-wide `sbt fmt` and checks for Scala or sbt changes outside the tool's stated edit paths. If it detects collateral changes, route the diff to the affected owners. Do not continue until each owner has reviewed the changes. Hooks can run outside the shell sandbox, so use a trusted build and keep privileged credentials out of authoring sessions. Claude Code applies file read rules to broad searches on a best-effort basis. Verify secret isolation at the runtime boundary.
 
 `.claude/settings.json` configures the hooks to run natively, and each subagent's tool list declares its permitted tools. Treat native hook execution and subagent tool enforcement as pending until a fresh-session probe in `docs/agent-runs/phase-01/orchestrator-update/restart-probes.md` observes each one. Keep direct script probes and observed live interception as separate evidence, and do not report automatic enforcement from the existence of these files alone. If a hook event does not carry a verified `agent_type`, the guard refuses structured constitutional edits; record that as an open gate. Do not supply unrestricted cloud or secret credentials to authoring sessions.
```

## `docs/agents.md`

```diff
diff --git a/docs/agents.md b/docs/agents.md
index 671265a..a2960d9 100644
--- a/docs/agents.md
+++ b/docs/agents.md
@@ -72,6 +72,6 @@ The orchestrator owns dispatch and repair routing. Subagents never invoke one an
 | Costly operations and scaling | Human approves duration, upper bounds, cost, monitoring and automatic stop criteria before dispatch. |
 | Incident recovery and rollback | Incident-responder diagnoses and proposes recovery. Human approves the reviewed script, target, bounds and verification. |
 
-The main Claude Code session is the orchestrator. Before dispatch it reads `CLAUDE.md`, this map and the assigned role file, then delegates to that subagent with the work order and necessary evidence. Treat declared tools as the maximum role authority. If the runtime does not enforce a particular tool or path boundary, record it as open. Nothing in the runtime stops the main session from performing a specialist's work inline, because its events carry no `agent_type` and the guard allows its non-constitutional edits; this rule stays an open, behavior-only control. A passing restart probe 8 is recorded as behavioral evidence only; the gate closes only through a ratified runtime control or an explicit human acceptance recorded in `docs/agent-runs/phase-01/orchestrator-update/ratification.md`. Restart probe 2 verifies the reviewer tool-list fence (Read, Grep and Glob only). The guard's reviewer branches are defense in depth that the reviewer cannot reach while it has no write or shell tools. Restart probe 9 verifies that `agent_type` reaches the hook. Keep cloud write credentials out of ordinary authoring sessions. Passing a direct hook probe demonstrates script behavior; verify automatic invocation separately in a fresh Claude Code session.
+The main Claude Code session is the orchestrator. Before dispatch it reads `CLAUDE.md`, this map and the assigned role file, then delegates to that subagent with the work order and necessary evidence. Treat declared tools as the maximum role authority. If the runtime does not enforce a particular tool or path boundary, record it as open. Nothing in the runtime stops the main session from performing a specialist's work inline, because its events carry no `agent_type` and the guard allows its non-constitutional edits; this rule stays an open, behavior-only control. A passing restart probe 8 is recorded as behavioral evidence only; the gate closes only through a ratified runtime control or an explicit human acceptance recorded in `docs/agent-runs/phase-01/orchestrator-update/ratification.md`. Restart probe 2 verifies the reviewer tool-list fence (Read, Grep and Glob only). The guard now lets a shell-bearing role read constitutional files through exactly `git diff`, `git diff --stat`, `git log --oneline` and `git show --stat`, with ranges and pathspecs, so the orchestrator can produce a review diff itself instead of depending on a human-supplied one. `code-reviewer` has no Bash tool and so cannot run them; it still receives the diff as evidence from the orchestrator. The allowance is read-only and moves no ownership boundary. The guard's reviewer branches are defense in depth that the reviewer cannot reach while it has no write or shell tools. Restart probe 9 verifies that `agent_type` reaches the hook. Keep cloud write credentials out of ordinary authoring sessions. Passing a direct hook probe demonstrates script behavior; verify automatic invocation separately in a fresh Claude Code session.
 
 A work order can narrow authority. Only a ratified constitutional revision can widen it. Independent reviews never fix their own findings or approve their own work.
```
